#![cfg_attr(docsrs, feature(doc_cfg))]
#![doc = include_str!("../README.md")]
// @@ begin lint list maintained by maint/add_warning @@
#![allow(renamed_and_removed_lints)] // @@REMOVE_WHEN(ci_arti_stable)
#![allow(unknown_lints)] // @@REMOVE_WHEN(ci_arti_nightly)
#![warn(missing_docs)]
#![warn(noop_method_call)]
#![warn(unreachable_pub)]
#![warn(clippy::all)]
#![deny(clippy::await_holding_lock)]
#![deny(clippy::cargo_common_metadata)]
#![deny(clippy::cast_lossless)]
#![deny(clippy::checked_conversions)]
#![allow(clippy::cognitive_complexity)] // See arti#2556
#![deny(clippy::debug_assert_with_mut_call)]
#![deny(clippy::exhaustive_enums)]
#![deny(clippy::exhaustive_structs)]
#![deny(clippy::expl_impl_clone_on_copy)]
#![deny(clippy::fallible_impl_from)]
#![deny(clippy::implicit_clone)]
#![deny(clippy::large_stack_arrays)]
#![warn(clippy::manual_ok_or)]
#![deny(clippy::missing_docs_in_private_items)]
#![warn(clippy::needless_borrow)]
#![warn(clippy::needless_pass_by_value)]
#![warn(clippy::option_option)]
#![deny(clippy::print_stderr)]
#![deny(clippy::print_stdout)]
#![warn(clippy::rc_buffer)]
#![deny(clippy::ref_option_ref)]
#![warn(clippy::semicolon_if_nothing_returned)]
#![warn(clippy::trait_duplication_in_bounds)]
#![deny(clippy::unchecked_time_subtraction)]
#![deny(clippy::unnecessary_wraps)]
#![warn(clippy::unseparated_literal_suffix)]
#![deny(clippy::unwrap_used)]
#![deny(clippy::mod_module_files)]
#![allow(clippy::let_unit_value)] // This can reasonably be done for explicitness
#![allow(clippy::uninlined_format_args)]
#![allow(clippy::significant_drop_in_scrutinee)] // arti/-/merge_requests/588/#note_2812945
#![allow(clippy::result_large_err)] // temporary workaround for arti#587
#![allow(clippy::needless_raw_string_hashes)] // complained-about code is fine, often best
#![allow(clippy::needless_lifetimes)] // See arti#1765
#![allow(mismatched_lifetime_syntaxes)] // temporary workaround for arti#2060
#![allow(clippy::collapsible_if)] // See arti#2342
#![deny(clippy::unused_async)]
#![deny(clippy::string_slice)] // See arti#2571
//! <!-- @@ end lint list maintained by maint/add_warning @@ -->

pub mod builder;
mod config;
mod err;
mod event;
pub mod factory;
mod mgr;
#[cfg(test)]
mod testing;
pub mod transport;

use futures::StreamExt;
use futures::select_biased;
use std::result::Result as StdResult;
use std::sync::{Arc, Weak};
use std::time::Duration;
use tor_config::ReconfigureError;
use tor_error::error_report;
use tor_linkspec::{ChanTarget, OwnedChanTarget};
use tor_netdir::{NetDirProvider, params::NetParameters};
use tor_proto::channel::Channel;
#[cfg(feature = "experimental-api")]
use tor_proto::memquota::ChannelAccount;
use tor_proto::memquota::ToplevelAccount;
use tor_rtcompat::SpawnExt;
use tracing::debug;
use tracing::instrument;
use void::{ResultVoidErrExt, Void};

#[cfg(feature = "relay")]
use {
    async_trait::async_trait, safelog::Sensitive, tor_proto::relay::CreateRequestHandler,
    tor_proto::relay::channel_provider::ChannelProvider,
};

pub use err::Error;

pub use config::{ChannelConfig, ChannelConfigBuilder, ProxyProtocol};
pub use mgr::ChanMgrConfig;

use tor_rtcompat::Runtime;

/// A Result as returned by this crate.
pub type Result<T> = std::result::Result<T, Error>;

use crate::factory::BootstrapReporter;
pub use event::{ConnBlockage, ConnStatus, ConnStatusEvents};
use tor_rtcompat::scheduler::{TaskHandle, TaskSchedule};

/// An object that remembers a set of live channels, and launches new ones on
/// request.
///
/// Use the [`ChanMgr::get_or_launch`] function to create a new [`Channel`], or
/// get one if it exists.  (For a slightly lower-level API that does no caching,
/// see [`ChannelFactory`](factory::ChannelFactory) and its implementors.
///
/// Each channel is kept open as long as there is a reference to it, or
/// something else (such as the relay or a network error) kills the channel.
///
/// After a `ChanMgr` launches a channel, it keeps a reference to it until that
/// channel has been unused (that is, had no circuits attached to it) for a
/// certain amount of time. (Currently this interval is chosen randomly from
/// between 180-270 seconds, but this is an implementation detail that may change
/// in the future.)
pub struct ChanMgr<R: Runtime> {
    /// Internal channel manager object that does the actual work.
    ///
    /// ## How this is built
    ///
    /// This internal manager is parameterized over an
    /// [`mgr::AbstractChannelFactory`], which here is instantiated with a [`factory::CompoundFactory`].
    /// The `CompoundFactory` itself holds:
    ///   * A `dyn` [`factory::AbstractPtMgr`] that can provide a `dyn`
    ///     [`factory::ChannelFactory`] for each supported pluggable transport.
    ///     This starts out as `None`, but can be replaced with [`ChanMgr::set_pt_mgr`].
    ///     The `TorClient` code currently sets this using `tor_ptmgr::PtMgr`.
    ///     `PtMgr` currently returns `ChannelFactory` implementations that are
    ///     built using [`transport::proxied::ExternalProxyPlugin`], which implements
    ///     [`transport::TransportImplHelper`], which in turn is wrapped into a
    ///     `ChanBuilder` to implement `ChannelFactory`.
    ///   * A generic [`factory::ChannelFactory`] that it uses for everything else
    ///     We instantiate this with a
    ///     [`builder::ChanBuilder`] using a [`transport::default::DefaultTransport`].
    // This type is a bit long, but I think it's better to just state it here explicitly rather than
    // hiding parts of it behind a type alias to make it look nicer.
    mgr: mgr::AbstractChanMgr<
        factory::CompoundFactory<builder::ChanBuilder<R, transport::DefaultTransport<R>>>,
    >,

    /// Stream of [`ConnStatus`] events.
    bootstrap_status: event::ConnStatusEvents,

    /// The runtime. Needed to possibly spawn tasks.
    #[allow(unused)] // Relay use this, not client yet. Keep it here instead of gating.
    runtime: R,
}

/// Description of how we got a channel.
#[non_exhaustive]
#[derive(Debug, Copy, Clone, Eq, PartialEq)]
pub enum ChanProvenance {
    /// This channel was newly launched, or was in progress and finished while
    /// we were waiting.
    NewlyCreated,
    /// This channel already existed when we asked for it.
    Preexisting,
}

/// Dormancy state, as far as the channel manager is concerned
///
/// This is usually derived in higher layers from `arti_client::DormantMode`.
#[non_exhaustive]
#[derive(Debug, Default, Copy, Clone, Eq, PartialEq)]
pub enum Dormancy {
    /// Not dormant
    ///
    /// Channels will operate normally.
    #[default]
    Active,
    /// Totally dormant
    ///
    /// Channels will not perform any spontaneous activity (eg, netflow padding)
    Dormant,
}

/// The usage that we have in mind when requesting a channel.
///
/// A channel may be used in multiple ways.  Each time a channel is requested
/// from `ChanMgr` a separate `ChannelUsage` is passed in to tell the `ChanMgr`
/// how the channel will be used this time.
///
/// To be clear, the `ChannelUsage` is aspect of a _request_ for a channel, and
/// is not an immutable property of the channel itself.
///
/// This type is obtained from a `tor_circmgr::usage::SupportedCircUsage` in
/// `tor_circmgr::usage`, and it has roughly the same set of variants.
#[derive(Clone, Debug, Copy, Eq, PartialEq)]
#[non_exhaustive]
pub enum ChannelUsage {
    /// Requesting a channel to use for BEGINDIR-based non-anonymous directory
    /// connections.
    Dir,

    /// Requesting a channel to transmit user traffic (including exit traffic)
    /// over the network.
    ///
    /// This includes the case where we are constructing a circuit preemptively,
    /// and _planning_ to use it for user traffic later on.
    UserTraffic,

    /// Requesting a channel that the caller does not plan to used at all, or
    /// which it plans to use only for testing circuits.
    UselessCircuit,
}

impl<R: Runtime> ChanMgr<R> {
    /// Construct a new channel manager.
    ///
    /// A new `ChannelAccount` will be made from `memquota`, for each Channel.
    ///
    /// The `ChannelAccount` is used for data associated with this channel.
    ///
    /// This does *not* (currently) include downstream outbound data
    /// (ie, data processed by the channel implementation here,
    /// awaiting TLS processing and actual transmission).
    /// In any case we try to keep those buffers small.
    ///
    /// The ChannelAccount *does* track upstream outbound data
    /// (ie, data processed by a circuit, but not yet by the channel),
    /// even though that data relates to a specific circuit.
    /// TODO #1652 use `CircuitAccount` for circuit->channel queue.
    ///
    /// # Usage note
    ///
    /// For the manager to work properly, you will need to call `ChanMgr::launch_background_tasks`.
    ///
    /// The `keymgr` is only needed for a relay which is used for authenticating its channel to
    /// other relays. Pass `None` for a client.
    pub fn new(
        runtime: R,
        config: ChanMgrConfig,
        dormancy: Dormancy,
        netparams: &NetParameters,
        memquota: ToplevelAccount,
    ) -> Result<Self>
    where
        R: 'static,
    {
        let (sender, receiver) = event::channel();
        let sender = Arc::new(std::sync::Mutex::new(sender));
        let reporter = BootstrapReporter(sender);
        let transport =
            transport::DefaultTransport::new(runtime.clone(), config.cfg.outbound_proxy.clone());
        cfg_if::cfg_if! {
            if #[cfg(feature = "relay")] {
                let builder = if let Some(auth_material) = &config.auth_material {
                    builder::ChanBuilder::new_relay(runtime.clone(), transport, auth_material.clone(), config.my_addrs, None)?
                } else {
                    // Yes, clients can have the "relay" feature enabled (unit tests).
                    builder::ChanBuilder::new_client(runtime.clone(), transport)
                };
            } else {
                let builder =  builder::ChanBuilder::new_client(runtime.clone(), transport);
            }
        };

        let factory = factory::CompoundFactory::new(
            Arc::new(builder),
            #[cfg(feature = "pt-client")]
            None,
        );

        // Warn if outbound_proxy is configured to a non-loopback address
        if let Some(ref proxy) = config.cfg.outbound_proxy {
            if !proxy.is_loopback() {
                tracing::warn!(
                    proxy_addr = %proxy,
                    "outbound_proxy is configured to a non-loopback address; \
                     this may expose Tor traffic to an untrusted intermediate"
                );
            }
        }

        let mgr =
            mgr::AbstractChanMgr::new(factory, config.cfg, dormancy, netparams, reporter, memquota);

        Ok(ChanMgr {
            mgr,
            bootstrap_status: receiver,
            runtime,
        })
    }

    /// Launch the periodic daemon tasks required by the manager to function properly.
    ///
    /// Returns a [`TaskHandle`] that can be used to manage
    /// those daemon tasks that poll periodically.
    #[instrument(level = "trace", skip_all)]
    pub fn launch_background_tasks(
        self: &Arc<Self>,
        runtime: &R,
        netdir: Arc<dyn NetDirProvider>,
    ) -> Result<Vec<TaskHandle>> {
        runtime
            .spawn(Self::continually_update_channels_config(
                Arc::downgrade(self),
                netdir,
            ))
            .map_err(|e| Error::from_spawn("channels config task", e))?;

        let (sched, handle) = TaskSchedule::new(runtime.clone());
        runtime
            .spawn(Self::continually_expire_channels(
                sched,
                Arc::downgrade(self),
            ))
            .map_err(|e| Error::from_spawn("channel expiration task", e))?;
        Ok(vec![handle])
    }

    /// Build a channel for an incoming stream.
    ///
    /// The `my_addrs` are the IP address(es) that are advertised by the relay in the consensus. We
    /// need to pass them so they can be sent in the NETINFO cell.
    ///
    /// The channel may or may not be authenticated. This method will wait until the channel is
    /// usable, and may return an error if we already have an existing channel to this peer.
    #[cfg(feature = "relay")]
    pub async fn handle_incoming(
        &self,
        src: Sensitive<std::net::SocketAddr>,
        stream: <R as tor_rtcompat::NetStreamProvider>::Stream,
    ) -> Result<Arc<Channel>> {
        let result = self.mgr.handle_incoming(src, stream).await;

        #[cfg(feature = "metrics")]
        self.mgr.metrics.increment_inbound_channels_built(&result);

        result
    }

    /// Try to get a suitable channel to the provided `target`,
    /// launching one if one does not exist.
    ///
    /// This function does not guarantee that the returned channel
    /// satisfies all of the properties of `target`. For example if an
    /// existing channel is returned, it might not be connected to any
    /// of the addresses specified in `target`.
    // ^ see https://gitlab.torproject.org/tpo/core/arti/-/issues/2344
    ///
    /// If there is already a channel launch attempt in progress, this
    /// function will wait until that launch is complete, and succeed
    /// or fail depending on its outcome.
    #[instrument(level = "trace", skip_all)]
    pub async fn get_or_launch<T: ChanTarget + ?Sized>(
        &self,
        target: &T,
        usage: ChannelUsage,
    ) -> Result<(Arc<Channel>, ChanProvenance)> {
        let targetinfo = OwnedChanTarget::from_chan_target(target);

        let (chan, provenance) = self.mgr.get_or_launch(targetinfo, usage).await?;
        // Double-check the match to make sure that the RSA identity is
        // what we wanted too.
        chan.check_match(target)
            .map_err(|e| Error::from_proto_no_skew(e, target))?;
        Ok((chan, provenance))
    }

    /// Return a stream of [`ConnStatus`] events to tell us about changes
    /// in our ability to connect to the internet.
    ///
    /// Note that this stream can be lossy: the caller will not necessarily
    /// observe every event on the stream
    pub fn bootstrap_events(&self) -> ConnStatusEvents {
        self.bootstrap_status.clone()
    }

    /// Expire all channels that have been unused for too long.
    ///
    /// Return the duration from now until next channel expires.
    pub fn expire_channels(&self) -> Duration {
        self.mgr.expire_channels()
    }

    /// Notifies the chanmgr to be dormant like dormancy
    pub fn set_dormancy(
        &self,
        dormancy: Dormancy,
        netparams: Arc<dyn AsRef<NetParameters>>,
    ) -> StdResult<(), tor_error::Bug> {
        self.mgr.set_dormancy(dormancy, netparams)
    }

    /// Reconfigure all channels
    pub fn reconfigure(
        &self,
        config: &ChannelConfig,
        how: tor_config::Reconfigure,
        netparams: Arc<dyn AsRef<NetParameters>>,
    ) -> StdResult<(), ReconfigureError> {
        if how == tor_config::Reconfigure::CheckAllOrNothing {
            // Since `self.mgr.reconfigure` returns an error type of `Bug` and not
            // `ReconfigureError` (see check below), the reconfigure should only fail due to bugs.
            // This means we can return `Ok` here since there should never be an error with the
            // provided `config` values.
            return Ok(());
        }

        let r = self.mgr.reconfigure(config, netparams);

        // Check that `self.mgr.reconfigure` returns an error type of `Bug` (see comment above).
        let _: Option<&tor_error::Bug> = r.as_ref().err();

        Ok(r?)
    }

    /// Replace the transport registry with one that may know about
    /// more transports.
    ///
    /// Note that the [`ChannelFactory`](factory::ChannelFactory) instances returned by `ptmgr` are
    /// required to time-out channels that take too long to build.  You'll get
    /// this behavior by default if the factories implement [`ChannelFactory`](factory::ChannelFactory) using
    /// [`transport::proxied::ExternalProxyPlugin`], which `tor-ptmgr` does.
    #[cfg(feature = "pt-client")]
    pub fn set_pt_mgr(&self, ptmgr: Arc<dyn factory::AbstractPtMgr + 'static>) {
        self.mgr.with_mut_builder(|f| f.replace_ptmgr(ptmgr));
    }

    /// Replace the relay auth material used for building new channels.
    ///
    /// This rebuilds the internal channel builder with the provided `auth_material`, which includes a
    /// new TLS cert and key. Existing channels are not affected, only newly created channels will
    /// use the new keys.
    #[cfg(feature = "relay")]
    pub fn set_relay_auth_material(
        &self,
        auth_material: Arc<tor_proto::RelayChannelAuthMaterial>,
    ) -> Result<()> {
        let mut result = Ok(());
        self.mgr.with_mut_builder(|f| {
            match f
                .default_factory()
                .rebuild_with_auth_material(auth_material)
            {
                Ok(b) => f.replace_default_factory(Arc::new(b)),
                Err(e) => result = Err(e),
            }
        });
        result
    }

    /// This will be used to handle CREATE* requests on channels.
    ///
    /// This handler will only be used for new channels, not existing channels.
    ///
    /// This will *not* be updated in any way by the channel manager,
    /// for example by a netdir update or when any keys change.
    /// The caller must handle this.
    /// The idea is that the channel manager shouldn't need to deal with circuit-specific stuff.
    ///
    /// It's expected to only ever call this once.
    /// Ideally it would be an `Option` in the constructor,
    /// but we don't want conditionally-compiled constructor arguments,
    /// and the [`CreateRequestHandler`] requires a [`dyn ChannelProvider`]
    /// which is typically this [`ChanMgr`] itself.
    #[cfg(feature = "relay")]
    pub fn set_create_request_handler(&self, handler: Arc<CreateRequestHandler>) -> Result<()> {
        let mut result = Ok(());
        self.mgr.with_mut_builder(|f| {
            match f
                .default_factory()
                .rebuild_with_create_request_handler(handler)
            {
                Ok(b) => f.replace_default_factory(Arc::new(b)),
                Err(e) => result = Err(e),
            }
        });
        result
    }

    /// Try to create a new, unmanaged channel to `target`.
    ///
    /// Unlike [`get_or_launch`](ChanMgr::get_or_launch), this function always
    /// creates a new channel, never retries transient failure, and does not
    /// register this channel with the `ChanMgr`.
    ///
    /// Generally you should not use this function; `get_or_launch` is usually a
    /// better choice.  This function is the right choice if, for whatever
    /// reason, you need to manage the lifetime of the channel you create, and
    /// make sure that no other code with access to this `ChanMgr` will be able
    /// to use the channel.
    #[cfg(feature = "experimental-api")]
    #[instrument(level = "trace", skip_all)]
    pub async fn build_unmanaged_channel(
        &self,
        target: impl tor_linkspec::IntoOwnedChanTarget,
        memquota: ChannelAccount,
    ) -> Result<Arc<Channel>> {
        use factory::ChannelFactory as _;
        let target = target.to_owned();

        self.mgr
            .channels
            .builder()
            .connect_via_transport(&target, self.mgr.reporter.clone(), memquota)
            .await
    }

    /// Watch for things that ought to change the configuration of all channels in the client
    ///
    /// Currently this handles enabling and disabling channel padding.
    ///
    /// This is a daemon task that runs indefinitely in the background,
    /// and exits when we find that `chanmgr` is dropped.
    #[instrument(level = "trace", skip_all)]
    async fn continually_update_channels_config(
        self_: Weak<Self>,
        netdir: Arc<dyn NetDirProvider>,
    ) {
        use tor_netdir::DirEvent as DE;
        let mut netdir_stream = netdir.events().fuse();
        let netdir = {
            let weak = Arc::downgrade(&netdir);
            drop(netdir);
            weak
        };
        let termination_reason: std::result::Result<Void, &str> = async move {
            loop {
                select_biased! {
                    direvent = netdir_stream.next() => {
                        let direvent = direvent.ok_or("EOF on netdir provider event stream")?;
                        if ! matches!(direvent, DE::NewConsensus) { continue };
                        let self_ = self_.upgrade().ok_or("channel manager gone away")?;
                        let netdir = netdir.upgrade().ok_or("netdir gone away")?;
                        let netparams = netdir.params();
                        self_.mgr.update_netparams(netparams).map_err(|e| {
                            error_report!(e, "continually_update_channels_config: failed to process!");
                            "error processing netdir"
                        })?;
                    }
                }
            }
        }
        .await;
        debug!(
            "continually_update_channels_config: shutting down: {}",
            termination_reason.void_unwrap_err()
        );
    }

    /// Periodically expire any channels that have been unused beyond
    /// the maximum duration allowed.
    ///
    /// Exist when we find that `chanmgr` is dropped
    ///
    /// This is a daemon task that runs indefinitely in the background
    #[instrument(level = "trace", skip_all)]
    async fn continually_expire_channels(mut sched: TaskSchedule<R>, chanmgr: Weak<Self>) {
        while sched.next().await.is_some() {
            let Some(cm) = Weak::upgrade(&chanmgr) else {
                // channel manager is closed.
                return;
            };
            let delay = cm.expire_channels();
            // This will sometimes be an underestimate, but it's no big deal; we just sleep some more.
            sched.fire_in(delay);
        }
    }
}

#[cfg(feature = "relay")]
#[async_trait]
impl<R: Runtime> ChannelProvider for ChanMgr<R> {
    type BuildSpec = OwnedChanTarget;

    fn get_or_launch(
        self: Arc<Self>,
        reactor_id: tor_proto::circuit::UniqId,
        target: Self::BuildSpec,
        tx: tor_proto::relay::channel_provider::OutboundChanSender,
    ) -> tor_proto::Result<()> {
        use tor_error::into_internal;

        debug!("Get or launch channel to {target} for circuit reactor {reactor_id}");

        let chanmgr = self.clone();
        self.runtime
            .spawn(async move {
                let r = chanmgr
                    .mgr
                    .get_or_launch(target, ChannelUsage::UserTraffic)
                    .await
                    .map_err(|e| tor_proto::Error::ChanProto(e.to_string())); // Is it a ChanProto?
                // Send back the channel.
                tx.send(r.map(|(chan, _)| chan));
            })
            .map_err(into_internal!("Failed to launch channel provider task"))?;

        Ok(())
    }
}
