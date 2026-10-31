//! Abstract implementation of a channel manager

use crate::factory::BootstrapReporter;
use crate::mgr::state::{ChannelForTarget, PendingChannelHandle};
use crate::{ChanProvenance, ChannelConfig, ChannelUsage, Dormancy, Error, Result};

use async_trait::async_trait;
use futures::future::Shared;
use oneshot_fused_workaround as oneshot;
use std::result::Result as StdResult;
use std::sync::Arc;
use std::time::Duration;
use tor_error::{error_report, internal};
use tor_linkspec::{HasChanMethod, HasRelayIds};
use tor_netdir::params::NetParameters;
use tor_proto::channel::kist::KistParams;
use tor_proto::channel::params::ChannelPaddingInstructionsUpdates;
use tor_proto::memquota::{ChannelAccount, SpecificAccount as _, ToplevelAccount};
use tracing::{instrument, trace};

#[cfg(feature = "relay")]
use {safelog::Sensitive, std::net::SocketAddr, tor_proto::RelayChannelAuthMaterial};

mod select;
mod state;

/// Trait to describe as much of a
/// [`Channel`](tor_proto::channel::Channel) as `AbstractChanMgr`
/// needs to use.
pub(crate) trait AbstractChannel: HasRelayIds {
    /// Return true iff this channel is considered canonical by us.
    fn is_canonical(&self) -> bool;
    /// Return true if we think the peer considers this channel as canonical.
    fn is_canonical_to_peer(&self) -> bool;
    /// Return true if this channel is usable.
    ///
    /// A channel might be unusable because it is closed, because it has
    /// hit a bug, or for some other reason.  We don't return unusable
    /// channels back to the user.
    fn is_usable(&self) -> bool;
    /// Return the amount of time a channel has not been in use.
    /// Return None if the channel is currently in use.
    fn duration_unused(&self) -> Option<Duration>;

    /// Reparameterize this channel according to the provided `ChannelPaddingInstructionsUpdates`
    ///
    /// The changed parameters may not be implemented "immediately",
    /// but this will be done "reasonably soon".
    fn reparameterize(
        &self,
        updates: Arc<ChannelPaddingInstructionsUpdates>,
    ) -> tor_proto::Result<()>;

    /// Update the KIST parameters.
    ///
    /// The changed parameters may not be implemented "immediately",
    /// but this will be done "reasonably soon".
    fn reparameterize_kist(&self, kist_params: KistParams) -> tor_proto::Result<()>;

    /// Specify that this channel should do activities related to channel padding
    ///
    /// See [`Channel::engage_padding_activities`]
    ///
    /// [`Channel::engage_padding_activities`]: tor_proto::channel::Channel::engage_padding_activities
    fn engage_padding_activities(&self);
}

/// Trait to describe how channels-like objects are created.
///
/// This differs from [`ChannelFactory`](crate::factory::ChannelFactory) in that
/// it's a purely crate-internal type that we use to decouple the
/// AbstractChanMgr code from actual "what is a channel" concerns.
#[async_trait]
pub(crate) trait AbstractChannelFactory {
    /// The type of channel that this factory can build.
    type Channel: AbstractChannel;
    /// Type that explains how to build an outgoing channel.
    type BuildSpec: HasRelayIds + HasChanMethod;
    /// The type of byte stream that's required to build channels for incoming connections.
    type Stream;

    /// Construct a new channel to the destination described at `target`.
    ///
    /// This function must take care of all timeouts, error detection,
    /// and so on.
    ///
    /// It should not retry; that is handled at a higher level.
    async fn build_channel(
        &self,
        target: &Self::BuildSpec,
        reporter: BootstrapReporter,
        memquota: ChannelAccount,
    ) -> Result<Arc<Self::Channel>>;

    /// Construct a new channel for an incoming connection.
    #[cfg(feature = "relay")]
    async fn build_channel_using_incoming(
        &self,
        peer: Sensitive<std::net::SocketAddr>,
        stream: Self::Stream,
        memquota: ChannelAccount,
    ) -> Result<Arc<Self::Channel>>;
}

/// This is the configuration for a [`ChanMgr`](crate::ChanMgr) given to the constructor.
#[derive(Default)]
pub struct ChanMgrConfig {
    /// Channel configuration which usually comes from a configuration file.
    pub(crate) cfg: ChannelConfig,
    /// Relay authentication key material for relay channels.
    #[cfg(feature = "relay")]
    pub(crate) auth_material: Option<Arc<RelayChannelAuthMaterial>>,
    /// Our address(es). When building outgoing channel, we need our addresses in order to send
    /// them in the NETINFO cell. It will also be used to validate initiator channel target.
    #[cfg(feature = "relay")]
    pub(crate) my_addrs: Vec<SocketAddr>,
    // TODO: Would be good to add more things such as NetParameters and Dormancy maybe?
}

impl ChanMgrConfig {
    /// Constructor.
    pub fn new(cfg: ChannelConfig) -> Self {
        Self {
            cfg,
            #[cfg(feature = "relay")]
            auth_material: None,
            #[cfg(feature = "relay")]
            my_addrs: Vec::new(),
        }
    }

    /// Set the relay channel authentication key material and return itself.
    #[cfg(feature = "relay")]
    pub fn with_auth_material(mut self, auth_material: Arc<RelayChannelAuthMaterial>) -> Self {
        self.auth_material = Some(auth_material);
        self
    }

    /// Set our addresses that we advertise to the world.
    #[cfg(feature = "relay")]
    pub fn with_my_addrs(mut self, my_addrs: Vec<SocketAddr>) -> Self {
        self.my_addrs = my_addrs;
        self
    }
}

/// A type- and network-agnostic implementation for [`ChanMgr`](crate::ChanMgr).
///
/// This type does the work of keeping track of open channels and pending
/// channel requests, launching requests as needed, waiting for pending
/// requests, and so forth.
///
/// The actual job of launching connections is deferred to an
/// `AbstractChannelFactory` type.
pub(crate) struct AbstractChanMgr<CF: AbstractChannelFactory> {
    /// All internal state held by this channel manager.
    ///
    /// The most important part is the map from relay identity to channel, or
    /// to pending channel status.
    pub(crate) channels: state::MgrState<CF>,

    /// A bootstrap reporter to give out when building channels.
    pub(crate) reporter: BootstrapReporter,

    /// The memory quota account that every channel will be a child of
    pub(crate) memquota: ToplevelAccount,

    /// Metrics counters / gauges / histograms.
    #[cfg(feature = "metrics")]
    pub(crate) metrics: ChanMgrMetrics,
}

/// Struct to hold all the metrics counters / gauges / histograms we use.
///
/// We create these and store them in the [`AbstractChanMgr`] in order to avoid
/// the performance hit associated with re-registering counters.
#[cfg(feature = "metrics")]
pub(crate) struct ChanMgrMetrics {
    /// Number of inbound channels successfully built.
    pub(crate) inbound_channels_built_success: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::UnusableTarget`] error.
    pub(crate) inbound_channels_built_failure_unusable_target: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::PendingFailed`] error.
    pub(crate) inbound_channels_built_failure_pending_failed: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::ChanTimeout`] error.
    pub(crate) inbound_channels_built_failure_chan_timeout: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::Proto`] error.
    pub(crate) inbound_channels_built_failure_proto: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::Io`] error.
    pub(crate) inbound_channels_built_failure_io: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::Connect`] error.
    pub(crate) inbound_channels_built_failure_connect: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::Spawn`] error.
    pub(crate) inbound_channels_built_failure_spawn: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::MissingId`] error.
    pub(crate) inbound_channels_built_failure_missing_id: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::IdentityConflict`] error.
    pub(crate) inbound_channels_built_failure_identity_conflict: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::NoSuchTransport`] error.
    pub(crate) inbound_channels_built_failure_no_such_transport: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::RequestCancelled`] error.
    pub(crate) inbound_channels_built_failure_request_cancelled: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::Pt`] error.
    pub(crate) inbound_channels_built_failure_pt: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::Memquota`] error.
    pub(crate) inbound_channels_built_failure_memquota: metrics::Counter,
    /// Number of inbound channels that we tried to build but had an [`Error::Internal`] error.
    pub(crate) inbound_channels_built_failure_internal: metrics::Counter,
}

#[cfg(feature = "metrics")]
impl ChanMgrMetrics {
    /// Create a new instance of [`ChanMgrMetrics`].
    pub(crate) fn new() -> Self {
        ChanMgrMetrics {
            inbound_channels_built_success: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "success",
                "direction" => "inbound",
            ),
            inbound_channels_built_failure_unusable_target: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "unusable_target",
            ),
            inbound_channels_built_failure_pending_failed: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "pending_failed",
            ),
            inbound_channels_built_failure_chan_timeout: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "chan_timeout",
            ),
            inbound_channels_built_failure_proto: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "proto",
            ),
            inbound_channels_built_failure_io: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "io",
            ),
            inbound_channels_built_failure_connect: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "connect",
            ),
            inbound_channels_built_failure_spawn: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "spawn",
            ),
            inbound_channels_built_failure_missing_id: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "missing_id",
            ),
            inbound_channels_built_failure_identity_conflict: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "identity_conflict",
            ),
            inbound_channels_built_failure_no_such_transport: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "no_such_transport",
            ),
            inbound_channels_built_failure_request_cancelled: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "request_cancelled",
            ),
            inbound_channels_built_failure_pt: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "pt",
            ),
            inbound_channels_built_failure_memquota: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "memquota",
            ),
            inbound_channels_built_failure_internal: metrics::counter!(
                description: "Total number of channels built",
                unit: metrics::Unit::Count,
                "arti_chanmgr_channels_built",
                "result" => "failure",
                "direction" => "inbound",
                "error" => "internal",
            ),
        }
    }

    /// Increment the correct inbound_channels_built counter for the given result.
    pub(crate) fn increment_inbound_channels_built<R>(&self, result: &Result<R>) {
        match result {
            Ok(_) => self.inbound_channels_built_success.increment(1),
            Err(Error::UnusableTarget(_)) => self
                .inbound_channels_built_failure_unusable_target
                .increment(1),
            Err(Error::PendingFailed { .. }) => self
                .inbound_channels_built_failure_pending_failed
                .increment(1),
            Err(Error::ChanTimeout { .. }) => self
                .inbound_channels_built_failure_chan_timeout
                .increment(1),
            Err(Error::Proto { .. }) => self.inbound_channels_built_failure_proto.increment(1),
            Err(Error::Io { .. }) => self.inbound_channels_built_failure_io.increment(1),
            Err(Error::Connect { .. }) => self.inbound_channels_built_failure_connect.increment(1),
            Err(Error::Spawn { .. }) => self.inbound_channels_built_failure_spawn.increment(1),
            Err(Error::MissingId) => self.inbound_channels_built_failure_missing_id.increment(1),
            Err(Error::IdentityConflict) => self
                .inbound_channels_built_failure_identity_conflict
                .increment(1),
            Err(Error::NoSuchTransport(_)) => self
                .inbound_channels_built_failure_no_such_transport
                .increment(1),
            Err(Error::RequestCancelled) => self
                .inbound_channels_built_failure_request_cancelled
                .increment(1),
            Err(Error::Pt(_)) => self.inbound_channels_built_failure_pt.increment(1),
            Err(Error::Memquota(_)) => self.inbound_channels_built_failure_memquota.increment(1),
            Err(Error::Internal(_)) => self.inbound_channels_built_failure_internal.increment(1),
        }
    }
}

/// Type alias for a future that we wait on to see when a pending
/// channel is done or failed.
type Pending = Shared<oneshot::Receiver<Result<()>>>;

/// Type alias for the sender we notify when we complete a channel (or fail to
/// complete it).
type Sending = oneshot::Sender<Result<()>>;

/// Keeps a pending launch entry and its waiters in sync.
///
/// Every exit path from a launch attempt must either remove the pending entry
/// or upgrade it to an open channel, and must notify all waiters with the
/// outcome. This guard makes cancellation and early returns follow the same
/// cleanup path as ordinary failures.
struct PendingLaunchGuard<'a, CF: AbstractChannelFactory> {
    /// Channel state used to remove or upgrade the pending entry.
    channels: &'a state::MgrState<CF>,
    /// Handle to the pending entry, if it has not yet been removed.
    handle: Option<PendingChannelHandle>,
    /// Sender used to notify tasks waiting on this launch.
    send: Option<Sending>,
    /// Result to report to the waiters if the launch ends here.
    result: Result<()>,
}

impl<'a, CF: AbstractChannelFactory> PendingLaunchGuard<'a, CF> {
    /// Create a new guard for a pending launch.
    fn new(channels: &'a state::MgrState<CF>, handle: PendingChannelHandle, send: Sending) -> Self {
        Self {
            channels,
            handle: Some(handle),
            send: Some(send),
            result: Err(Error::RequestCancelled),
        }
    }

    /// Record the result that should be reported to any waiters.
    fn note_result(&mut self, result: Result<()>) {
        self.result = result;
    }

    /// Replace the pending channel with an open one.
    fn upgrade_pending_channel_to_open(&mut self, channel: Arc<CF::Channel>) -> Result<()> {
        let handle = self
            .handle
            .take()
            .expect("pending launch guard lost its handle before upgrade");
        self.channels
            .upgrade_pending_channel_to_open(handle, channel)
    }
}

impl<'a, CF: AbstractChannelFactory> Drop for PendingLaunchGuard<'a, CF> {
    fn drop(&mut self) {
        if let Some(handle) = self.handle.take() {
            if let Err(e) = self.channels.remove_pending_channel(handle) {
                // Just log an error if we're unable to remove it, since there's
                // nothing else we can do here, and returning the error would
                // hide the actual error that we care about (the channel build
                // failure).
                #[allow(clippy::missing_docs_in_private_items)]
                const MSG: &str = "Unable to remove the pending channel";
                error_report!(internal!("{e}"), "{}", MSG);
            }
        }

        if let Some(send) = self.send.take() {
            // It's okay if all the receivers went away:
            // that means that nobody was waiting for this channel.
            let _ignore_err = send.send(self.result.clone());
        }
    }
}

impl<CF: AbstractChannelFactory + Clone> AbstractChanMgr<CF> {
    /// Make a new empty channel manager.
    pub(crate) fn new(
        connector: CF,
        config: ChannelConfig,
        dormancy: Dormancy,
        netparams: &NetParameters,
        reporter: BootstrapReporter,
        memquota: ToplevelAccount,
    ) -> Self {
        AbstractChanMgr {
            channels: state::MgrState::new(connector, config, dormancy, netparams),
            reporter,
            memquota,
            #[cfg(feature = "metrics")]
            metrics: ChanMgrMetrics::new(),
        }
    }

    /// Run a function to modify the channel builder in this object.
    #[allow(unused)]
    pub(crate) fn with_mut_builder<F>(&self, func: F)
    where
        F: FnOnce(&mut CF),
    {
        self.channels.with_mut_builder(func);
    }

    /// Remove every unusable entry from this channel manager.
    #[cfg(test)]
    pub(crate) fn remove_unusable_entries(&self) -> Result<()> {
        self.channels.remove_unusable()
    }

    /// Build a channel for an incoming stream. See
    /// [`ChanMgr::handle_incoming`](crate::ChanMgr::handle_incoming).
    #[cfg(feature = "relay")]
    pub(crate) async fn handle_incoming(
        &self,
        src: Sensitive<std::net::SocketAddr>,
        stream: CF::Stream,
    ) -> Result<Arc<CF::Channel>> {
        let chan_builder = self.channels.builder();
        let memquota = ChannelAccount::new(&self.memquota)?;
        let channel = chan_builder
            .build_channel_using_incoming(src, stream, memquota)
            .await?;
        // Add it to our list.
        self.channels.add_open(channel.clone())?;
        Ok(channel)
    }

    /// Get a channel corresponding to the identities of `target`.
    ///
    /// If a usable channel exists with that identity, return it.
    ///
    /// If no such channel exists already, and none is in progress,
    /// launch a new request using `target`.
    ///
    /// If no such channel exists already, but we have one that's in
    /// progress, wait for it to succeed or fail.
    #[instrument(skip_all, level = "trace")]
    pub(crate) async fn get_or_launch(
        &self,
        target: CF::BuildSpec,
        usage: ChannelUsage,
    ) -> Result<(Arc<CF::Channel>, ChanProvenance)> {
        use ChannelUsage as CU;

        let chan = self.get_or_launch_internal(target).await?;

        match usage {
            CU::Dir | CU::UselessCircuit => {}
            CU::UserTraffic => chan.0.engage_padding_activities(),
        }

        Ok(chan)
    }

    /// Get a channel whose identity is `ident` - internal implementation
    #[instrument(skip_all, level = "trace")]
    async fn get_or_launch_internal(
        &self,
        target: CF::BuildSpec,
    ) -> Result<(Arc<CF::Channel>, ChanProvenance)> {
        /// How many times do we try?
        const N_ATTEMPTS: usize = 2;
        let mut attempts_so_far = 0;
        let mut final_attempt = false;
        let mut provenance = ChanProvenance::Preexisting;

        // TODO(nickm): It would be neat to use tor_retry instead.
        let mut last_err = None;

        while attempts_so_far < N_ATTEMPTS || final_attempt {
            attempts_so_far += 1;

            // For each attempt, we _first_ look at the state of the channel map
            // to decide on an `Action`, and _then_ we execute that action.

            // First, see what state we're in, and what we should do about it.
            let action = self.choose_action(&target, final_attempt)?;

            // We are done deciding on our Action! It's time act based on the
            // Action that we chose.
            match action {
                // If this happens, we were trying to make one final check of our state, but
                // we would have had to make additional attempts.
                None => {
                    if !final_attempt {
                        return Err(Error::Internal(internal!(
                            "No action returned while not on final attempt"
                        )));
                    }
                    break;
                }
                // Easy case: we have an error or a channel to return.
                Some(Action::Return(v)) => {
                    trace!("Returning existing channel");
                    return v.map(|chan| (chan, provenance));
                }
                // There's an in-progress channel.  Wait for it.
                Some(Action::Wait(pend)) => {
                    trace!("Waiting for in-progress channel");
                    match pend.await {
                        Ok(Ok(())) => {
                            // We were waiting for a channel, and it succeeded, or it
                            // got cancelled.  But it might have gotten more
                            // identities while negotiating than it had when it was
                            // launched, or it might have failed to get all the
                            // identities we want. Check for this.
                            final_attempt = true;
                            provenance = ChanProvenance::NewlyCreated;
                            last_err.get_or_insert(Error::RequestCancelled);
                        }
                        Ok(Err(e)) => {
                            last_err = Some(e);
                        }
                        Err(_) => {
                            last_err =
                                Some(Error::Internal(internal!("channel build task disappeared")));
                        }
                    }
                }
                // We need to launch a channel.
                Some(Action::Launch((handle, send))) => {
                    trace!("Launching channel");
                    let connector = self.channels.builder();
                    let mut launch = PendingLaunchGuard::new(&self.channels, handle, send);
                    let memquota = match ChannelAccount::new(&self.memquota) {
                        Ok(memquota) => memquota,
                        Err(e) => {
                            let e: Error = e.into();
                            launch.note_result(Err(e.clone()));
                            return Err(e);
                        }
                    };

                    let outcome = connector
                        .build_channel(&target, self.reporter.clone(), memquota)
                        .await;

                    match outcome {
                        Ok(ref chan) => {
                            // Replace the pending channel with the newly built channel.
                            match launch.upgrade_pending_channel_to_open(Arc::clone(chan)) {
                                Ok(()) => launch.note_result(Ok(())),
                                Err(e) => {
                                    launch.note_result(Err(e.clone()));
                                    return Err(e);
                                }
                            }
                        }
                        Err(_) => {
                            launch.note_result(outcome.clone().map(|_| ()));
                        }
                    }

                    match outcome {
                        Ok(chan) => {
                            return Ok((chan, ChanProvenance::NewlyCreated));
                        }
                        Err(e) => last_err = Some(e),
                    }
                }
            }

            // End of this attempt. We will try again...
        }

        Err(last_err.unwrap_or_else(|| Error::Internal(internal!("no error was set!?"))))
    }

    /// Helper: based on our internal state, decide which action to take when
    /// asked for a channel, and update our internal state accordingly.
    ///
    /// If `final_attempt` is true, then we will not pick any action that does
    /// not result in an immediate result. If we would pick such an action, we
    /// instead return `Ok(None)`.  (We could instead have the caller detect
    /// such actions, but it's less efficient to construct them, insert them,
    /// and immediately revert them.)
    #[instrument(skip_all, level = "trace")]
    fn choose_action(
        &self,
        target: &CF::BuildSpec,
        final_attempt: bool,
    ) -> Result<Option<Action<CF::Channel>>> {
        // don't create new channels on the final attempt
        let response = self.channels.request_channel(
            target,
            /* add_new_entry_if_not_found= */ !final_attempt,
        );

        match response {
            Ok(Some(ChannelForTarget::Open(channel))) => Ok(Some(Action::Return(Ok(channel)))),
            Ok(Some(ChannelForTarget::Pending(pending))) => {
                if !final_attempt {
                    Ok(Some(Action::Wait(pending)))
                } else {
                    // don't return a pending channel on the final attempt
                    Ok(None)
                }
            }
            Ok(Some(ChannelForTarget::NewEntry((handle, send)))) => {
                // do not drop the handle if refactoring; see `PendingChannelHandle` for details
                Ok(Some(Action::Launch((handle, send))))
            }
            Ok(None) => Ok(None),
            Err(e @ Error::IdentityConflict) => Ok(Some(Action::Return(Err(e)))),
            Err(e) => Err(e),
        }
    }

    /// Update the netdir
    pub(crate) fn update_netparams(
        &self,
        netparams: Arc<dyn AsRef<NetParameters>>,
    ) -> StdResult<(), tor_error::Bug> {
        self.channels.reconfigure_general(None, None, netparams)
    }

    /// Notifies the chanmgr to be dormant like dormancy
    pub(crate) fn set_dormancy(
        &self,
        dormancy: Dormancy,
        netparams: Arc<dyn AsRef<NetParameters>>,
    ) -> StdResult<(), tor_error::Bug> {
        self.channels
            .reconfigure_general(None, Some(dormancy), netparams)
    }

    /// Reconfigure all channels
    pub(crate) fn reconfigure(
        &self,
        config: &ChannelConfig,
        netparams: Arc<dyn AsRef<NetParameters>>,
    ) -> StdResult<(), tor_error::Bug> {
        self.channels
            .reconfigure_general(Some(config), None, netparams)
    }

    /// Expire any channels that have been unused longer than
    /// their maximum unused duration assigned during creation.
    ///
    /// Return a duration from now until next channel expires.
    ///
    /// If all channels are in use or there are no open channels,
    /// return 180 seconds which is the minimum value of
    /// max_unused_duration.
    pub(crate) fn expire_channels(&self) -> Duration {
        self.channels.expire_channels()
    }

    /// Test only: return the open usable channels with a given `ident`.
    #[cfg(test)]
    pub(crate) fn get_nowait<'a, T>(&self, ident: T) -> Vec<Arc<CF::Channel>>
    where
        T: Into<tor_linkspec::RelayIdRef<'a>>,
    {
        use state::ChannelState::*;
        self.channels
            .with_channels(|channel_map| {
                channel_map
                    .by_id(ident)
                    .filter_map(|entry| match entry {
                        Open(ent) if ent.channel.is_usable() => Some(Arc::clone(&ent.channel)),
                        _ => None,
                    })
                    .collect()
            })
            .expect("Poisoned lock")
    }
}

/// Possible actions that we'll decide to take when asked for a channel.
#[allow(clippy::large_enum_variant)]
enum Action<C: AbstractChannel> {
    /// We found no channel.  We're going to launch a new one,
    /// then tell everybody about it.
    Launch((PendingChannelHandle, Sending)),
    /// We found an in-progress attempt at making a channel.
    /// We're going to wait for it to finish.
    Wait(Pending),
    /// We found a usable channel.  We're going to return it.
    Return(Result<Arc<C>>),
}

#[cfg(test)]
mod test {
    // @@ begin test lint list maintained by maint/add_warning @@
    #![allow(clippy::bool_assert_comparison)]
    #![allow(clippy::clone_on_copy)]
    #![allow(clippy::dbg_macro)]
    #![allow(clippy::mixed_attributes_style)]
    #![allow(clippy::print_stderr)]
    #![allow(clippy::print_stdout)]
    #![allow(clippy::single_char_pattern)]
    #![allow(clippy::unwrap_used)]
    #![allow(clippy::unchecked_time_subtraction)]
    #![allow(clippy::useless_vec)]
    #![allow(clippy::needless_pass_by_value)]
    #![allow(clippy::string_slice)] // See arti#2571
    //! <!-- @@ end test lint list maintained by maint/add_warning @@ -->
    use super::*;
    use crate::Error;

    use futures::{join, poll};
    use std::error::Error as StdError;
    use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::time::Duration;
    use tor_error::bad_api_usage;
    use tor_linkspec::ChannelMethod;
    use tor_llcrypto::pk::ed25519::Ed25519Identity;
    use tor_memquota::ArcMemoryQuotaTrackerExt as _;

    use crate::ChannelUsage as CU;
    use tor_rtcompat::{Runtime, task::yield_now, test_with_one_runtime};

    // Two distinct addresses we can use in tests.
    const ADDR_A: SocketAddr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(1, 1, 1, 1), 443));
    const ADDR_B: SocketAddr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(2, 2, 2, 2), 443));

    #[derive(Clone)]
    struct FakeChannelFactory<RT> {
        runtime: RT,
        build_attempts: Arc<AtomicUsize>,
    }

    #[derive(Clone, Debug)]
    struct FakeChannel {
        ed_ident: Ed25519Identity,
        mood: char,
        closing: Arc<AtomicBool>,
        detect_reuse: Arc<char>,
        // last_params: Option<ChannelPaddingInstructionsUpdates>,
    }

    impl PartialEq for FakeChannel {
        fn eq(&self, other: &Self) -> bool {
            Arc::ptr_eq(&self.detect_reuse, &other.detect_reuse)
        }
    }

    impl AbstractChannel for FakeChannel {
        fn is_canonical(&self) -> bool {
            unimplemented!()
        }
        fn is_canonical_to_peer(&self) -> bool {
            unimplemented!()
        }
        fn is_usable(&self) -> bool {
            !self.closing.load(Ordering::SeqCst)
        }
        fn duration_unused(&self) -> Option<Duration> {
            None
        }
        fn reparameterize(
            &self,
            _updates: Arc<ChannelPaddingInstructionsUpdates>,
        ) -> tor_proto::Result<()> {
            // *self.last_params.lock().unwrap() = Some((*updates).clone());
            match self.mood {
                // Build succeeds, but installing the channel into the manager fails.
                'r' => Err(tor_proto::Error::ChanProto(
                    "synthetic reparameterize failure".into(),
                )),
                _ => Ok(()),
            }
        }
        fn reparameterize_kist(&self, _kist_params: KistParams) -> tor_proto::Result<()> {
            Ok(())
        }
        fn engage_padding_activities(&self) {}
    }

    impl HasRelayIds for FakeChannel {
        fn identity(
            &self,
            key_type: tor_linkspec::RelayIdType,
        ) -> Option<tor_linkspec::RelayIdRef<'_>> {
            match key_type {
                tor_linkspec::RelayIdType::Ed25519 => Some((&self.ed_ident).into()),
                _ => None,
            }
        }
    }

    impl FakeChannel {
        fn start_closing(&self) {
            self.closing.store(true, Ordering::SeqCst);
        }
    }

    impl<RT: Runtime> FakeChannelFactory<RT> {
        fn new(runtime: RT, build_attempts: Arc<AtomicUsize>) -> Self {
            FakeChannelFactory {
                runtime,
                build_attempts,
            }
        }
    }

    fn new_test_abstract_chanmgr<R: Runtime>(runtime: R) -> AbstractChanMgr<FakeChannelFactory<R>> {
        new_test_abstract_chanmgr_and_build_attempts(runtime).0
    }

    fn new_test_abstract_chanmgr_and_build_attempts<R: Runtime>(
        runtime: R,
    ) -> (AbstractChanMgr<FakeChannelFactory<R>>, Arc<AtomicUsize>) {
        let build_attempts = Arc::new(AtomicUsize::new(0));
        let cf = FakeChannelFactory::new(runtime, Arc::clone(&build_attempts));
        let mgr = AbstractChanMgr::new(
            cf,
            Default::default(),
            Default::default(),
            &Default::default(),
            BootstrapReporter::fake(),
            ToplevelAccount::new_noop(),
        );
        (mgr, build_attempts)
    }

    #[derive(Clone, Debug)]
    struct FakeBuildSpec(u32, char, Ed25519Identity, SocketAddr);

    impl HasRelayIds for FakeBuildSpec {
        fn identity(
            &self,
            key_type: tor_linkspec::RelayIdType,
        ) -> Option<tor_linkspec::RelayIdRef<'_>> {
            match key_type {
                tor_linkspec::RelayIdType::Ed25519 => Some((&self.2).into()),
                _ => None,
            }
        }
    }

    impl HasChanMethod for FakeBuildSpec {
        fn chan_method(&self) -> ChannelMethod {
            ChannelMethod::Direct(vec![self.3.clone()])
        }
    }

    /// Helper to make a fake Ed identity from a u32.
    fn u32_to_ed(n: u32) -> Ed25519Identity {
        let mut bytes = [0; 32];
        bytes[0..4].copy_from_slice(&n.to_be_bytes());
        bytes.into()
    }

    /// Return true if `needle` appears anywhere in `err`'s error chain.
    fn error_contains(err: &Error, needle: &str) -> bool {
        let mut source: Option<&(dyn StdError + 'static)> = Some(err);
        while let Some(err) = source {
            if err.to_string().contains(needle) || format!("{err:?}").contains(needle) {
                return true;
            }
            source = err.source();
        }
        false
    }

    #[async_trait]
    impl<RT: Runtime> AbstractChannelFactory for FakeChannelFactory<RT> {
        type Channel = FakeChannel;
        type BuildSpec = FakeBuildSpec;
        type Stream = ();

        async fn build_channel(
            &self,
            target: &Self::BuildSpec,
            _reporter: BootstrapReporter,
            _memquota: ChannelAccount,
        ) -> Result<Arc<FakeChannel>> {
            self.build_attempts.fetch_add(1, Ordering::SeqCst);
            yield_now().await;
            let FakeBuildSpec(ident, mood, id, _addr) = *target;
            let ed_ident = u32_to_ed(ident);
            assert_eq!(ed_ident, id);
            match mood {
                // "X" means never connect.
                '❌' | '🔥' => return Err(Error::UnusableTarget(bad_api_usage!("emoji"))),
                // "zzz" means wait for 15 seconds then succeed.
                '💤' => {
                    self.runtime.sleep(Duration::new(15, 0)).await;
                }
                _ => {}
            }
            Ok(Arc::new(FakeChannel {
                ed_ident,
                mood,
                closing: Arc::new(AtomicBool::new(false)),
                detect_reuse: Default::default(),
                // last_params: None,
            }))
        }

        #[cfg(feature = "relay")]
        async fn build_channel_using_incoming(
            &self,
            _peer: Sensitive<std::net::SocketAddr>,
            _stream: Self::Stream,
            _memquota: ChannelAccount,
        ) -> Result<Arc<Self::Channel>> {
            unimplemented!()
        }
    }

    #[test]
    fn connect_one_ok() {
        test_with_one_runtime!(|runtime| async {
            let mgr = new_test_abstract_chanmgr(runtime);
            let target = FakeBuildSpec(413, '!', u32_to_ed(413), ADDR_A);
            let chan1 = mgr
                .get_or_launch(target.clone(), CU::UserTraffic)
                .await
                .unwrap()
                .0;
            let chan2 = mgr.get_or_launch(target, CU::UserTraffic).await.unwrap().0;

            assert_eq!(chan1, chan2);
            assert_eq!(mgr.get_nowait(&u32_to_ed(413)), vec![chan1]);
        });
    }

    #[test]
    fn connect_one_fail() {
        test_with_one_runtime!(|runtime| async {
            let mgr = new_test_abstract_chanmgr(runtime);

            // This is set up to always fail.
            let target = FakeBuildSpec(999, '❌', u32_to_ed(999), ADDR_A);
            let res1 = mgr.get_or_launch(target, CU::UserTraffic).await;
            assert!(matches!(res1, Err(Error::UnusableTarget(_))));

            assert!(mgr.get_nowait(&u32_to_ed(999)).is_empty());
        });
    }

    #[test]
    fn connect_different_address() {
        test_with_one_runtime!(|runtime| async {
            let mgr = new_test_abstract_chanmgr(runtime);

            // Two targets that have different addresses.
            let target1 = FakeBuildSpec(413, '!', u32_to_ed(413), ADDR_A);
            let mut target2 = target1.clone();
            target2.3 = ADDR_B;

            let chan1 = mgr.get_or_launch(target1, CU::UserTraffic).await.unwrap().0;
            let chan2 = mgr.get_or_launch(target2, CU::UserTraffic).await.unwrap().0;

            // Even with different addresses, the original channel is returned.
            assert_eq!(chan1, chan2);
            assert_eq!(mgr.get_nowait(&u32_to_ed(413)), vec![chan1]);
        });
    }

    #[test]
    fn test_concurrent() {
        test_with_one_runtime!(|runtime| async {
            let mgr = new_test_abstract_chanmgr(runtime);

            let usage = CU::UserTraffic;

            // TODO(nickm): figure out how to make these actually run
            // concurrently. Right now it seems that they don't actually
            // interact.
            let (ch3a, ch3b, ch44a, ch44b, ch50a, ch50b, ch86a, ch86b) = join!(
                mgr.get_or_launch(FakeBuildSpec(3, 'a', u32_to_ed(3), ADDR_A), usage),
                mgr.get_or_launch(FakeBuildSpec(3, 'b', u32_to_ed(3), ADDR_A), usage),
                mgr.get_or_launch(FakeBuildSpec(44, 'a', u32_to_ed(44), ADDR_A), usage),
                mgr.get_or_launch(FakeBuildSpec(44, 'b', u32_to_ed(44), ADDR_A), usage),
                mgr.get_or_launch(FakeBuildSpec(50, 'a', u32_to_ed(50), ADDR_A), usage),
                mgr.get_or_launch(FakeBuildSpec(50, 'b', u32_to_ed(50), ADDR_B), usage),
                mgr.get_or_launch(FakeBuildSpec(86, '❌', u32_to_ed(86), ADDR_A), usage),
                mgr.get_or_launch(FakeBuildSpec(86, '🔥', u32_to_ed(86), ADDR_A), usage),
            );
            let ch3a = ch3a.unwrap();
            let ch3b = ch3b.unwrap();
            let ch44a = ch44a.unwrap();
            let ch44b = ch44b.unwrap();
            let ch50a = ch50a.unwrap();
            let ch50b = ch50b.unwrap();
            let err_a = ch86a.unwrap_err();
            let err_b = ch86b.unwrap_err();

            assert_eq!(ch3a, ch3b);
            assert_eq!(ch44a, ch44b);
            assert_eq!(ch50a, ch50b);
            assert_ne!(ch44a, ch3a);

            assert!(matches!(err_a, Error::UnusableTarget(_)));
            assert!(matches!(err_b, Error::UnusableTarget(_)));
        });
    }

    #[test]
    fn dropped_launch_reports_request_cancelled_to_waiters() {
        test_with_one_runtime!(|runtime| async {
            let mgr = new_test_abstract_chanmgr(runtime);
            let target = FakeBuildSpec(777, '💤', u32_to_ed(777), ADDR_A);
            let usage = CU::UserTraffic;

            let mut owner1 = Box::pin(mgr.get_or_launch(target.clone(), usage));
            assert!(poll!(&mut owner1).is_pending());

            let mut waiter = Box::pin(mgr.get_or_launch(target.clone(), usage));
            assert!(poll!(&mut waiter).is_pending());

            drop(owner1);

            let mut owner2 = Box::pin(mgr.get_or_launch(target, usage));
            assert!(poll!(&mut owner2).is_pending());

            assert!(poll!(&mut waiter).is_pending());

            drop(owner2);

            let waiter = waiter.await;
            assert!(
                matches!(&waiter, Err(Error::RequestCancelled)),
                "{waiter:?}"
            );
            if let Err(ref err) = waiter {
                assert!(!error_contains(err, "channel build task disappeared"));
            }
        });
    }

    #[test]
    fn failed_upgrade_reports_original_error_without_owner_retry() {
        test_with_one_runtime!(|runtime| async {
            let (mgr, build_attempts) = new_test_abstract_chanmgr_and_build_attempts(runtime);
            let target = FakeBuildSpec(778, 'r', u32_to_ed(778), ADDR_A);
            let usage = CU::UserTraffic;

            let mut owner = Box::pin(mgr.get_or_launch(target.clone(), usage));
            assert!(poll!(&mut owner).is_pending());

            let mut waiter = Box::pin(mgr.get_or_launch(target.clone(), usage));
            assert!(poll!(&mut waiter).is_pending());

            let owner = owner.await;
            assert!(matches!(&owner, Err(Error::Internal(_))), "{owner:?}");
            if let Err(ref err) = owner {
                assert!(error_contains(err, "failure on new channel"));
                assert!(!error_contains(err, "channel build task disappeared"));
            }

            assert_eq!(build_attempts.load(Ordering::SeqCst), 1);
            assert!(mgr.get_nowait(&u32_to_ed(778)).is_empty());

            let waiter = waiter.await;
            assert!(matches!(&waiter, Err(Error::Internal(_))), "{waiter:?}");
            if let Err(ref err) = waiter {
                assert!(error_contains(err, "failure on new channel"));
                assert!(!error_contains(err, "channel build task disappeared"));
            }
        });
    }

    #[test]
    fn unusable_entries() {
        test_with_one_runtime!(|runtime| async {
            let mgr = new_test_abstract_chanmgr(runtime);

            let (ch3, ch4, ch5) = join!(
                mgr.get_or_launch(FakeBuildSpec(3, 'a', u32_to_ed(3), ADDR_A), CU::UserTraffic),
                mgr.get_or_launch(FakeBuildSpec(4, 'a', u32_to_ed(4), ADDR_A), CU::UserTraffic),
                mgr.get_or_launch(FakeBuildSpec(5, 'a', u32_to_ed(5), ADDR_A), CU::UserTraffic),
            );

            let ch3 = ch3.unwrap().0;
            let _ch4 = ch4.unwrap();
            let ch5 = ch5.unwrap().0;

            ch3.start_closing();
            ch5.start_closing();

            let ch3_new = mgr
                .get_or_launch(FakeBuildSpec(3, 'b', u32_to_ed(3), ADDR_A), CU::UserTraffic)
                .await
                .unwrap()
                .0;
            assert_ne!(ch3, ch3_new);
            assert_eq!(ch3_new.mood, 'b');

            mgr.remove_unusable_entries().unwrap();

            assert!(!mgr.get_nowait(&u32_to_ed(3)).is_empty());
            assert!(!mgr.get_nowait(&u32_to_ed(4)).is_empty());
            assert!(mgr.get_nowait(&u32_to_ed(5)).is_empty());
        });
    }
}
