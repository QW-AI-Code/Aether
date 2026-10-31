//! Simple implementation for the internal map state of a ChanMgr.

use std::time::Duration;

use super::AbstractChannelFactory;
use super::{AbstractChannel, Pending, Sending, select};
use crate::{ChannelConfig, Dormancy, Error, Result};

use futures::FutureExt;
use std::result::Result as StdResult;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use tor_async_utils::oneshot;
use tor_basic_utils::RngExt as _;
use tor_cell::chancell::msg::PaddingNegotiate;
use tor_config::PaddingLevel;
use tor_error::{error_report, internal, into_internal};
use tor_linkspec::{HasRelayIds, ListByRelayIds, RelayIds};
use tor_netdir::{params::CHANNEL_PADDING_TIMEOUT_UPPER_BOUND, params::NetParameters};
use tor_proto::ChannelPaddingInstructions;
use tor_proto::channel::ChannelPaddingInstructionsUpdates;
use tor_proto::channel::kist::{KistMode, KistParams};
use tor_proto::channel::padding::Parameters as PaddingParameters;
use tor_proto::channel::padding::ParametersBuilder as PaddingParametersBuilder;
use tor_units::{BoundedInt32, IntegerMilliseconds};
use tracing::{info, instrument};
use void::{ResultVoidExt as _, Void};

#[cfg(test)]
mod padding_test;

/// All mutable state held by an `AbstractChannelMgr`.
///
/// One reason that this is an isolated type is that we want to
/// to limit the amount of code that can see and
/// lock the Mutex here.  (We're using a blocking mutex close to async
/// code, so we need to be careful.)
pub(crate) struct MgrState<C: AbstractChannelFactory> {
    /// The data, within a lock
    ///
    /// (Danger: this uses a blocking mutex close to async code.  This mutex
    /// must never be held while an await is happening.)
    inner: std::sync::Mutex<Inner<C>>,
}

/// Parameters for channels that we create, and that all existing channels are using
struct ChannelParams {
    /// Channel padding instructions
    padding: ChannelPaddingInstructions,

    /// KIST parameters
    kist: KistParams,
}

/// A map from channel id to channel state, plus necessary auxiliary state - inside lock
struct Inner<C: AbstractChannelFactory> {
    /// The channel factory type that we store.
    ///
    /// In this module we never use this _as_ an AbstractChannelFactory: we just
    /// hand out clones of it when asked.
    builder: C,

    /// A map from identity to channels, or to pending channel statuses.
    channels: ListByRelayIds<ChannelState<C::Channel>>,

    /// A list of unauthenticated channels meaning they are client/bridge -> relay. We populate
    /// this list as a relay responder accepting incoming connections.
    ///
    /// Notice here that [`ChannelState`] is NOT used because we don't need a pending state of
    /// unauthenticated channels (inbound client/bridge) because multiple channel from the same
    /// peer can coexist. Furthermore, these channels are never looked up for normal relay
    /// operations and so a pending state is not needed.
    #[cfg(feature = "relay")]
    unauth_channels: Vec<Arc<C::Channel>>,

    /// Parameters for channels that we create, and that all existing channels are using
    ///
    /// Will be updated by a background task, which also notifies all existing
    /// `Open` channels via `channels`.
    ///
    /// (Must be protected by the same lock as `channels`, or a channel might be
    /// created using being-replaced parameters, but not get an update.)
    channels_params: ChannelParams,

    /// The configuration (from the config file or API caller)
    config: ChannelConfig,

    /// Dormancy
    ///
    /// The last dormancy information we have been told about and passed on to our channels.
    /// Updated via `MgrState::set_dormancy` and hence `MgrState::reconfigure_general`,
    /// which then uses it to calculate how to reconfigure the channels.
    dormancy: Dormancy,
}

/// The state of a channel (or channel build attempt) within a map.
///
/// A ChannelState can be Open (representing a fully negotiated channel) or
/// Building (representing a pending attempt to build a channel). Both states
/// have a set of RelayIds, but these RelayIds represent slightly different
/// things:
///  * On a Building channel, the set of RelayIds is all the identities that we
///    require the peer to have. (The peer may turn out to have _more_
///    identities than this.)
///  * On an Open channel, the set of RelayIds is all the identities that
///    we were able to successfully authenticate for the peer.
pub(crate) enum ChannelState<C> {
    /// An open channel.
    ///
    /// This channel might not be usable: it might be closing or
    /// broken.  We need to check its is_usable() method before
    /// yielding it to the user.
    Open(OpenEntry<C>),
    /// A channel that's getting built.
    Building(PendingEntry),
}

/// An open channel entry.
#[derive(Clone)]
pub(crate) struct OpenEntry<C> {
    /// The underlying open channel.
    pub(crate) channel: Arc<C>,
    /// The maximum unused duration allowed for this channel.
    pub(crate) max_unused_duration: Duration,
}

/// A unique ID for a pending ([`PendingEntry`]) channel.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) struct UniqPendingChanId(u64);

impl UniqPendingChanId {
    /// Construct a new `UniqPendingChanId`.
    pub(crate) fn new() -> Self {
        /// The next unique ID.
        static NEXT_ID: AtomicU64 = AtomicU64::new(0);
        // Relaxed ordering is fine; we don't care about how this
        // is instantiated with respect to other channels.
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        assert!(id != u64::MAX, "Exhausted the pending channel ID namespace");
        Self(id)
    }
}

impl std::fmt::Display for UniqPendingChanId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "PendingChan {}", self.0)
    }
}

/// An entry for a not-yet-build channel
#[derive(Clone)]
pub(crate) struct PendingEntry {
    /// The keys of the relay to which we're trying to open a channel.
    pub(crate) ids: RelayIds,

    /// A future we can clone and listen on to learn when this channel attempt
    /// is successful or failed.
    ///
    /// This entry will be removed from the map (and possibly replaced with an
    /// `OpenEntry`) _before_ this future becomes ready.
    pub(crate) pending: Pending,

    /// A unique ID that allows us to find this exact pending entry later.
    pub(crate) unique_id: UniqPendingChanId,
}

impl<C> HasRelayIds for ChannelState<C>
where
    C: HasRelayIds,
{
    fn identity(
        &self,
        key_type: tor_linkspec::RelayIdType,
    ) -> Option<tor_linkspec::RelayIdRef<'_>> {
        match self {
            ChannelState::Open(OpenEntry { channel, .. }) => channel.identity(key_type),
            ChannelState::Building(PendingEntry { ids, .. }) => ids.identity(key_type),
        }
    }
}

impl<C: Clone> ChannelState<C> {
    /// For testing: either give the Open channel inside this state,
    /// or panic if there is none.
    #[cfg(test)]
    fn unwrap_open(&self) -> &C {
        match self {
            ChannelState::Open(ent) => &ent.channel,
            _ => panic!("Not an open channel"),
        }
    }
}

/// Type of the `nf_ito_*` netdir parameters, convenience alias
type NfIto = IntegerMilliseconds<BoundedInt32<0, CHANNEL_PADDING_TIMEOUT_UPPER_BOUND>>;

/// Extract from a `NetParameters` which we need, conveniently organized for our processing
///
/// This type serves two functions at once:
///
///  1. Being a subset of the parameters, we can copy it out of
///     the netdir, before we do more complex processing - and, in particular,
///     before we obtain the lock on `inner` (which we need to actually handle the update,
///     because we need to combine information from the config with that from the netdir).
///
///  2. Rather than four separate named fields for the padding options,
///     it has arrays, so that it is easy to
///     select the values without error-prone recapitulation of field names.
#[derive(Debug, Clone)]
struct NetParamsExtract {
    /// `nf_ito_*`, the padding timeout parameters from the netdir consensus
    ///
    /// `nf_ito[ 0=normal, 1=reduced ][ 0=low, 1=high ]`
    /// are `nf_ito_{low,high}{,_reduced` from `NetParameters`.
    // TODO we could use some enum or IndexVec or something to make this less `0` and `1`
    nf_ito: [[NfIto; 2]; 2],

    /// The KIST parameters.
    kist: KistParams,
}

impl From<&NetParameters> for NetParamsExtract {
    fn from(p: &NetParameters) -> Self {
        let kist_enabled = kist_mode_from_net_parameter(p.kist_enabled);
        // NOTE: in theory, this cast shouldn't be needed
        // (kist_tcp_notsent_lowat is supposed to be a u32, not an i32).
        // In practice, however, the type conversion is needed
        // because consensus params are i32s.
        //
        // See the `NetParameters::kist_tcp_notsent_lowat` docs for more details.
        let tcp_notsent_lowat = u32::from(p.kist_tcp_notsent_lowat);
        let kist = KistParams::new(kist_enabled, tcp_notsent_lowat);

        NetParamsExtract {
            nf_ito: [
                [p.nf_ito_low, p.nf_ito_high],
                [p.nf_ito_low_reduced, p.nf_ito_high_reduced],
            ],
            kist,
        }
    }
}

/// Build a `KistMode` from [`NetParameters`].
///
/// Used for converting [`kist_enabled`](NetParameters::kist_enabled)
/// to a corresponding `KistMode`.
fn kist_mode_from_net_parameter(val: BoundedInt32<0, 1>) -> KistMode {
    caret::caret_int! {
        /// KIST flavor, defined by a numerical value read from the consensus.
        struct KistType(i32) {
            /// KIST disabled
            DISABLED = 0,
            /// KIST using TCP_NOTSENT_LOWAT.
            TCP_NOTSENT_LOWAT = 1,
        }
    }

    match val.get().into() {
        KistType::DISABLED => KistMode::Disabled,
        KistType::TCP_NOTSENT_LOWAT => KistMode::TcpNotSentLowat,
        _ => unreachable!("BoundedInt32 was not bounded?!"),
    }
}

impl NetParamsExtract {
    /// Return the padding timer parameter low end, for reduced-ness `reduced`, as a `u32`
    fn pad_low(&self, reduced: bool) -> IntegerMilliseconds<u32> {
        self.pad_get(reduced, 0)
    }
    /// Return the padding timer parameter high end, for reduced-ness `reduced`, as a `u32`
    fn pad_high(&self, reduced: bool) -> IntegerMilliseconds<u32> {
        self.pad_get(reduced, 1)
    }

    /// Return and converts one padding parameter timer
    ///
    /// Internal function.
    fn pad_get(&self, reduced: bool, low_or_high: usize) -> IntegerMilliseconds<u32> {
        self.nf_ito[usize::from(reduced)][low_or_high]
            .try_map(|v| Ok::<_, Void>(v.into()))
            .void_unwrap()
    }
}

impl<C: AbstractChannel> ChannelState<C> {
    /// Return true if a channel is ready to expire.
    /// Update `expire_after` if a smaller duration than
    /// the given value is required to expire this channel.
    fn ready_to_expire(&self, expire_after: &mut Duration) -> bool {
        let ChannelState::Open(ent) = self else {
            return false;
        };
        let Some(unused_duration) = ent.channel.duration_unused() else {
            // still in use
            return false;
        };
        let max_unused_duration = ent.max_unused_duration;
        let Some(remaining) = max_unused_duration.checked_sub(unused_duration) else {
            // no time remaining; drop now.
            return true;
        };
        if remaining.is_zero() {
            // Ignoring this edge case would result in a fairly benign race
            // condition outside of Shadow, but deadlock in Shadow.
            return true;
        }
        *expire_after = std::cmp::min(*expire_after, remaining);
        false
    }
}

impl<C: AbstractChannelFactory> MgrState<C> {
    /// Create a new empty `MgrState`.
    pub(crate) fn new(
        builder: C,
        config: ChannelConfig,
        dormancy: Dormancy,
        netparams: &NetParameters,
    ) -> Self {
        let mut padding_params = ChannelPaddingInstructions::default();
        let netparams = NetParamsExtract::from(netparams);
        let kist_params = netparams.kist;
        let update = parameterize(&mut padding_params, &config, dormancy, &netparams)
            .unwrap_or_else(|e: tor_error::Bug| panic!("bug detected on startup: {:?}", e));
        let _: Option<_> = update; // there are no channels yet, that would need to be told

        let channels_params = ChannelParams {
            padding: padding_params,
            kist: kist_params,
        };

        MgrState {
            inner: std::sync::Mutex::new(Inner {
                builder,
                channels: ListByRelayIds::new(),
                #[cfg(feature = "relay")]
                unauth_channels: Vec::new(),
                config,
                channels_params,
                dormancy,
            }),
        }
    }

    /// Run a function on the [`ListByRelayIds`] that implements the map in this `MgrState`.
    ///
    /// This function grabs a mutex: do not provide a slow function.
    ///
    /// We provide this function rather than exposing the channels set directly,
    /// to make sure that the calling code doesn't await while holding the lock.
    ///
    /// This is only `cfg(test)` since it can deadlock.
    ///
    /// # Deadlock
    ///
    /// Calling a method on [`MgrState`] from within `func` may cause a deadlock.
    #[cfg(test)]
    pub(crate) fn with_channels<F, T>(&self, func: F) -> Result<T>
    where
        F: FnOnce(&mut ListByRelayIds<ChannelState<C::Channel>>) -> T,
    {
        let mut inner = self.inner.lock()?;
        Ok(func(&mut inner.channels))
    }

    /// Return a copy of the builder stored in this state.
    pub(crate) fn builder(&self) -> C
    where
        C: Clone,
    {
        let inner = self.inner.lock().expect("lock poisoned");
        inner.builder.clone()
    }

    /// Run a function to modify the builder stored in this state.
    ///
    /// # Deadlock
    ///
    /// Calling a method on [`MgrState`] from within `func` may cause a deadlock.
    #[allow(unused)]
    pub(crate) fn with_mut_builder<F>(&self, func: F)
    where
        F: FnOnce(&mut C),
    {
        let mut inner = self.inner.lock().expect("lock poisoned");
        func(&mut inner.builder);
    }

    /// Add an open channel into our list.
    #[cfg(feature = "relay")]
    pub(crate) fn add_open(&self, channel: Arc<C::Channel>) -> Result<()> {
        let mut inner = self.inner.lock()?;
        // Make sure this channel has verified relay identities. Else, put it in the
        // unauthenticated channel list (ex: client channel as a relay responder).
        if channel.has_any_identity() {
            inner.channels.insert(ChannelState::Open(OpenEntry {
                channel,
                // TODO(relay): Relay need a different unused duration (if any). We can't use the
                // client timeout value. Need to be figured out before production.
                max_unused_duration: Self::random_max_unused_duration(),
            }));
        } else {
            inner.unauth_channels.push(channel);
        }
        Ok(())
    }

    /// Remove every unusable state from the map in this state.
    #[cfg(test)]
    pub(crate) fn remove_unusable(&self) -> Result<()> {
        let mut inner = self.inner.lock()?;
        inner.channels.retain(|state| match state {
            ChannelState::Open(ent) => ent.channel.is_usable(),
            ChannelState::Building(_) => true,
        });
        Ok(())
    }

    /// Request an open or pending channel to `target`. If `add_new_entry_if_not_found` is true and
    /// an open or pending channel isn't found, a new pending entry will be added and
    /// [`ChannelForTarget::NewEntry`] will be returned. This is all done as part of the same method
    /// so that all operations are performed under the same lock acquisition.
    pub(crate) fn request_channel(
        &self,
        target: &C::BuildSpec,
        add_new_entry_if_not_found: bool,
    ) -> Result<Option<ChannelForTarget<C>>> {
        use ChannelState::*;

        let mut inner = self.inner.lock()?;

        // The idea here is to choose the channel in two steps:
        //
        // - Eligibility: Get channels from the channel map and filter them down to only channels
        //   which are eligible to be returned.
        // - Ranking: From the eligible channels, choose the best channel.
        //
        // Another way to choose the channel could be something like: first try all canonical open
        // channels, then all non-canonical open channels, then all pending channels with all
        // matching relay ids, then remaining pending channels, etc. But this ends up being hard to
        // follow and inflexible (what if you want to prioritize pending channels over non-canonical
        // open channels?).

        // Open channels which are allowed for requests to `target`.
        let open_channels = inner
            .channels
            // channels with all target relay identifiers
            .by_all_ids(target)
            .filter(|entry| match entry {
                Open(x) => select::open_channel_is_allowed(x, target),
                Building(_) => false,
            });

        // Pending channels which will *probably* be allowed for requests to `target` once they
        // complete.
        let pending_channels = inner
            .channels
            // channels that have a subset of the relay ids of `target`
            .all_subset(target)
            .into_iter()
            .filter(|entry| match entry {
                Open(_) => false,
                Building(x) => select::pending_channel_maybe_allowed(x, target),
            });

        match select::choose_best_channel(open_channels.chain(pending_channels), target) {
            Some(Open(OpenEntry { channel, .. })) => {
                // This entry is a perfect match for the target keys: we'll return the open
                // entry.
                return Ok(Some(ChannelForTarget::Open(Arc::clone(channel))));
            }
            Some(Building(PendingEntry { pending, .. })) => {
                // This entry is potentially a match for the target identities: we'll return the
                // pending entry. (We don't know for sure if it will match once it completes,
                // since we might discover additional keys beyond those listed for this pending
                // entry.)
                return Ok(Some(ChannelForTarget::Pending(pending.clone())));
            }
            None => {}
        }

        // It's possible we know ahead of time that building a channel would be unsuccessful.
        if inner
            .channels
            // channels with at least one id in common with `target`
            .all_overlapping(target)
            .into_iter()
            // but not channels which completely satisfy the id requirements of `target`
            .filter(|entry| !entry.has_all_relay_ids_from(target))
            .any(|entry| matches!(entry, Open(OpenEntry{ channel, ..}) if channel.is_usable()))
        {
            // At least one *open, usable* channel has been negotiated that overlaps only
            // partially with our target: it has proven itself to have _one_ of our target
            // identities, but not all.
            //
            // Because this channel exists, we know that our target cannot succeed, since relays
            // are not allowed to share _any_ identities.
            //return Ok(Some(Action::Return(Err(Error::IdentityConflict))));
            return Err(Error::IdentityConflict);
        }

        if !add_new_entry_if_not_found {
            return Ok(None);
        }

        // Great, nothing interfered at all.
        let any_relay_id = target
            .identities()
            .next()
            .ok_or(internal!("relay target had no id"))?
            .to_owned();
        let (new_state, send, unique_id) = setup_launch(RelayIds::from_relay_ids(target));
        inner
            .channels
            .try_insert(ChannelState::Building(new_state))?;
        let handle = PendingChannelHandle::new(any_relay_id, unique_id);
        Ok(Some(ChannelForTarget::NewEntry((handle, send))))
    }

    /// Remove the pending channel identified by its `handle`.
    pub(crate) fn remove_pending_channel(&self, handle: PendingChannelHandle) -> Result<()> {
        let mut inner = self.inner.lock()?;
        remove_pending(&mut inner.channels, handle);
        Ok(())
    }

    /// Upgrade the pending channel identified by its `handle` by replacing it with a new open
    /// `channel`.
    #[instrument(skip_all, level = "trace")]
    pub(crate) fn upgrade_pending_channel_to_open(
        &self,
        handle: PendingChannelHandle,
        channel: Arc<C::Channel>,
    ) -> Result<()> {
        // Do all operations under the same lock acquisition.
        let mut inner = self.inner.lock()?;

        remove_pending(&mut inner.channels, handle);

        // This isn't great.  We context switch to the newly-created
        // channel just to tell it how and whether to do padding.  Ideally
        // we would pass the params at some suitable point during
        // building.  However, that would involve the channel taking a
        // copy of the params, and that must happen in the same channel
        // manager lock acquisition span as the one where we insert the
        // channel into the table so it will receive updates.  I.e.,
        // here.
        let update = inner.channels_params.padding.initial_update();
        if let Some(update) = update {
            channel
                .reparameterize(update.into())
                .map_err(|_| internal!("failure on new channel"))?;
        }
        let new_entry = ChannelState::Open(OpenEntry {
            channel,
            max_unused_duration: Self::random_max_unused_duration(),
        });
        inner.channels.insert(new_entry);

        Ok(())
    }

    /// Reconfigure all channels as necessary
    ///
    /// (By reparameterizing channels as needed)
    /// This function will handle
    ///   - netdir update
    ///   - a reconfiguration
    ///   - dormancy
    ///
    /// For `new_config` and `new_dormancy`, `None` means "no change to previous info".
    ///
    /// **Note:** As described in `ChanMgr::set_create_request_handler()`,
    /// this doesn't update the parameters for any `CreateRequestHandler` if one exists.
    pub(super) fn reconfigure_general(
        &self,
        new_config: Option<&ChannelConfig>,
        new_dormancy: Option<Dormancy>,
        netparams: Arc<dyn AsRef<NetParameters>>,
    ) -> StdResult<(), tor_error::Bug> {
        use ChannelState as CS;

        // TODO when we support operation as a relay, inter-relay channels ought
        // not to get padding.
        let netdir = {
            let extract = NetParamsExtract::from((*netparams).as_ref());
            drop(netparams);
            extract
        };

        let mut inner = self
            .inner
            .lock()
            .map_err(|_| internal!("poisoned channel manager"))?;
        let inner = &mut *inner;

        if let Some(new_config) = new_config {
            inner.config = new_config.clone();
        }
        if let Some(new_dormancy) = new_dormancy {
            inner.dormancy = new_dormancy;
        }

        let update = parameterize(
            &mut inner.channels_params.padding,
            &inner.config,
            inner.dormancy,
            &netdir,
        )?;

        let update = update.map(Arc::new);

        let new_kist_params = netdir.kist;
        let kist_params = if new_kist_params != inner.channels_params.kist {
            // The KIST params have changed: remember their value,
            // and reparameterize_kist()
            inner.channels_params.kist = new_kist_params;
            Some(new_kist_params)
        } else {
            // If the new KIST params are identical to the previous ones,
            // we don't need to call reparameterize_kist()
            None
        };

        if update.is_none() && kist_params.is_none() {
            // Return early, nothing to reconfigure
            return Ok(());
        }

        let channels = inner.channels.values().filter_map(|chan| match chan {
            CS::Open(OpenEntry { channel, .. }) => Some(channel),
            CS::Building(_) => None,
        });
        #[cfg(feature = "relay")]
        let channels = channels.chain(inner.unauth_channels.iter());

        for channel in channels {
            if let Some(ref update) = update {
                // Ignore error (which simply means the channel is closed or gone)
                let _ = channel.reparameterize(Arc::clone(update));
            }

            if let Some(kist) = kist_params {
                // Ignore error (which simply means the channel is closed or gone)
                let _ = channel.reparameterize_kist(kist);
            }
        }

        Ok(())
    }

    /// Expire all channels that have been unused for too long.
    ///
    /// Return a Duration until the next time at which
    /// a channel _could_ expire.
    pub(crate) fn expire_channels(&self) -> Duration {
        // TODO(relay): First, I don't think dropping the ChannelState<> from the channel list
        // actually closes a channel reactor. Because it holds a Arc<Channel>, the ref counter is
        // simply decremented but the reactor still goes on. We can't have guarantees on an object
        // in an `Arc<>` to be cleaned up when we drop it as it defeats the purpose of using an Arc
        // as we don't know if other reference are held elsewhere.
        //
        // Second, Unauthenticated channels are relay only as in client/bridge connecting inbound.
        // We need to expire any unused as well.
        //
        // Taking both points above, maybe it could be better to move the expiry logic into the
        // channel reactor itself and make this function simply retain any channel that are
        // `is_usable()`. This would remove the convoluted needs for ChannelDetails shared between
        // a `Channel` (reactor handle) and the channel `Reactor`.
        //
        // We'll address all this in https://gitlab.torproject.org/tpo/core/arti/-/work_items/1600

        let mut ret = Duration::from_secs(180);
        self.inner
            .lock()
            .expect("Poisoned lock")
            .channels
            .retain(|chan| !chan.ready_to_expire(&mut ret));
        ret
    }

    /// Helper: Return the default max unused duration for a channel.
    fn random_max_unused_duration() -> Duration {
        Duration::from_secs(
            rand::rng()
                .gen_range_checked(180..270)
                .expect("not 180 < 270 !"),
        )
    }
}

/// A channel for a given target relay.
pub(crate) enum ChannelForTarget<CF: AbstractChannelFactory> {
    /// A channel that is open.
    Open(Arc<CF::Channel>),
    /// A channel that is building.
    Pending(Pending),
    /// Information about a new pending channel entry.
    NewEntry((PendingChannelHandle, Sending)),
}

/// A handle for a pending channel.
///
/// WARNING: This handle should never be dropped, and should always be passed back into
/// [`MgrState::remove_pending_channel`] or [`MgrState::upgrade_pending_channel_to_open`], otherwise
/// the pending channel may be left in the channel map forever.
///
/// This handle must only be used with the `MgrState` from which it was given.
pub(crate) struct PendingChannelHandle {
    /// Any relay ID for this pending channel.
    relay_id: tor_linkspec::RelayId,
    /// The unique ID for this pending channel.
    unique_id: UniqPendingChanId,
    /// The pending channel has been removed from the channel map.
    chan_has_been_removed: bool,
}

impl PendingChannelHandle {
    /// Create a new [`PendingChannelHandle`].
    fn new(relay_id: tor_linkspec::RelayId, unique_id: UniqPendingChanId) -> Self {
        Self {
            relay_id,
            unique_id,
            chan_has_been_removed: false,
        }
    }

    /// This should be called when the pending channel has been removed from the pending channel
    /// map. Not calling this will result in an error log message (and panic in debug builds) when
    /// this handle is dropped.
    fn chan_has_been_removed(mut self) {
        self.chan_has_been_removed = true;
    }
}

impl std::ops::Drop for PendingChannelHandle {
    fn drop(&mut self) {
        if !self.chan_has_been_removed {
            #[allow(clippy::missing_docs_in_private_items)]
            const MSG: &str = "Dropped the 'PendingChannelHandle' without removing the channel";
            error_report!(
                internal!("{MSG}"),
                "'PendingChannelHandle' dropped unexpectedly",
            );
        }
    }
}

/// Helper: return the objects used to inform pending tasks about a newly open or failed channel.
fn setup_launch(ids: RelayIds) -> (PendingEntry, Sending, UniqPendingChanId) {
    let (snd, rcv) = oneshot::channel();
    let pending = rcv.shared();
    let unique_id = UniqPendingChanId::new();
    let entry = PendingEntry {
        ids,
        pending,
        unique_id,
    };

    (entry, snd, unique_id)
}

/// Helper: remove the pending channel identified by `handle` from `channel_map`.
fn remove_pending<C: AbstractChannel>(
    channel_map: &mut tor_linkspec::ListByRelayIds<ChannelState<C>>,
    handle: PendingChannelHandle,
) {
    // we need only one relay id to locate it, even if it has multiple relay ids
    let removed = channel_map.remove_by_id(&handle.relay_id, |c| {
        let ChannelState::Building(c) = c else {
            return false;
        };
        c.unique_id == handle.unique_id
    });
    debug_assert_eq!(removed.len(), 1, "expected to remove exactly one channel");

    handle.chan_has_been_removed();
}

/// Converts config, dormancy, and netdir, into parameter updates
///
/// Calculates new parameters, updating `channels_params` as appropriate.
/// If anything changed, the corresponding update instruction is returned.
///
/// `channels_params` is updated with the new parameters,
/// and the update message, if one is needed, is returned.
///
/// This is called in two places:
///
///  1. During chanmgr creation, it is called once to analyze the initial state
///     and construct a corresponding ChannelPaddingInstructions.
///
///  2. During reconfiguration.
fn parameterize(
    channels_params: &mut ChannelPaddingInstructions,
    config: &ChannelConfig,
    dormancy: Dormancy,
    netdir: &NetParamsExtract,
) -> StdResult<Option<ChannelPaddingInstructionsUpdates>, tor_error::Bug> {
    // Everything in this calculation applies to *all* channels, disregarding
    // channel usage.  Usage is handled downstream, in the channel frontend.
    // See the module doc in `crates/tor-proto/src/channel/padding.rs`.

    let padding_of_level = |level| padding_parameters(level, netdir);
    let send_padding = padding_of_level(config.padding)?;
    let padding_default = padding_of_level(PaddingLevel::default())?;

    let send_padding = match dormancy {
        Dormancy::Active => send_padding,
        Dormancy::Dormant => None,
    };

    let recv_padding = match config.padding {
        PaddingLevel::Reduced => None,
        PaddingLevel::Normal => send_padding,
        PaddingLevel::None => None,
    };

    // Whether the inbound padding approach we are to use, is the same as the default
    // derived from the netdir (disregarding our config and dormancy).
    //
    // Ie, whether the parameters we want are precisely those that a peer would
    // use by default (assuming they have the same view of the netdir as us).
    let recv_equals_default = recv_padding == padding_default;

    let padding_negotiate = if recv_equals_default {
        // Our padding approach is the same as peers' defaults.  So the PADDING_NEGOTIATE
        // message we need to send is the START(0,0).  (The channel frontend elides an
        // initial message of this form, - see crates/tor-proto/src/channel.rs::note_usage.)
        //
        // If the netdir default is no padding, and we previously negotiated
        // padding being enabled, and now want to disable it, we would send
        // START(0,0) rather than STOP.  That is OK (even, arguably, right).
        PaddingNegotiate::start_default()
    } else {
        match recv_padding {
            None => PaddingNegotiate::stop(),
            Some(params) => params.padding_negotiate_cell()?,
        }
    };

    let mut update = channels_params
        .start_update()
        .padding_enable(send_padding.is_some())
        .padding_negotiate(padding_negotiate);
    if let Some(params) = send_padding {
        update = update.padding_parameters(params);
    }
    let update = update.finish();

    Ok(update)
}

/// Given a `NetDirExtract` and whether we're reducing padding, return a `PaddingParameters`
///
/// With `PaddingLevel::None`, or the consensus specifies no padding, will return `None`;
/// but does not account for other reasons why padding might be enabled/disabled.
fn padding_parameters(
    config: PaddingLevel,
    netdir: &NetParamsExtract,
) -> StdResult<Option<PaddingParameters>, tor_error::Bug> {
    let reduced = match config {
        PaddingLevel::Reduced => true,
        PaddingLevel::Normal => false,
        PaddingLevel::None => return Ok(None),
    };

    padding_parameters_builder(reduced, netdir)
        .unwrap_or_else(|e: &str| {
            info!(
                "consensus channel padding parameters wrong, using defaults: {}",
                &e,
            );
            Some(PaddingParametersBuilder::default())
        })
        .map(|p| {
            p.build()
                .map_err(into_internal!("failed to build padding parameters"))
        })
        .transpose()
}

/// Given a `NetDirExtract` and whether we're reducing padding,
/// return a `PaddingParametersBuilder`
///
/// If the consensus specifies no padding, will return `None`;
/// but does not account for other reasons why padding might be enabled/disabled.
///
/// If `Err`, the string is a description of what is wrong with the parameters;
/// the caller should use `PaddingParameters::Default`.
fn padding_parameters_builder(
    reduced: bool,
    netdir: &NetParamsExtract,
) -> StdResult<Option<PaddingParametersBuilder>, &'static str> {
    let mut p = PaddingParametersBuilder::default();

    let low = netdir.pad_low(reduced);
    let high = netdir.pad_high(reduced);
    if low > high {
        return Err("low > high");
    }
    if low.as_millis() == 0 && high.as_millis() == 0 {
        // Zeroes for both channel padding consensus parameters means "don't send padding".
        // padding-spec.txt s2.6, see description of `nf_ito_high`.
        return Ok(None);
    }
    p.low(low);
    p.high(high);
    Ok::<_, &'static str>(Some(p))
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
    use crate::factory::BootstrapReporter;
    use async_trait::async_trait;
    #[cfg(feature = "relay")]
    use safelog::Sensitive;
    use std::sync::{Arc, Mutex};
    use tor_llcrypto::pk::ed25519::Ed25519Identity;
    use tor_proto::channel::params::ChannelPaddingInstructionsUpdates;
    use tor_proto::memquota::ChannelAccount;

    fn new_test_state() -> MgrState<FakeChannelFactory> {
        MgrState::new(
            FakeChannelFactory::default(),
            ChannelConfig::default(),
            Default::default(),
            &Default::default(),
        )
    }

    #[derive(Clone, Debug, Default)]
    struct FakeChannelFactory {}

    #[allow(clippy::diverging_sub_expression)] // for unimplemented!() + async_trait
    #[async_trait]
    impl AbstractChannelFactory for FakeChannelFactory {
        type Channel = FakeChannel;

        type BuildSpec = tor_linkspec::OwnedChanTarget;

        type Stream = ();

        async fn build_channel(
            &self,
            _target: &Self::BuildSpec,
            _reporter: BootstrapReporter,
            _memquota: ChannelAccount,
        ) -> Result<Arc<FakeChannel>> {
            unimplemented!()
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

    #[derive(Clone, Debug)]
    struct FakeChannel {
        ed_ident: Ed25519Identity,
        usable: bool,
        unused_duration: Option<u64>,
        params_update: Arc<Mutex<Option<Arc<ChannelPaddingInstructionsUpdates>>>>,
    }
    impl AbstractChannel for FakeChannel {
        fn is_canonical(&self) -> bool {
            unimplemented!()
        }
        fn is_canonical_to_peer(&self) -> bool {
            unimplemented!()
        }
        fn is_usable(&self) -> bool {
            self.usable
        }
        fn duration_unused(&self) -> Option<Duration> {
            self.unused_duration.map(Duration::from_secs)
        }
        fn reparameterize(
            &self,
            update: Arc<ChannelPaddingInstructionsUpdates>,
        ) -> tor_proto::Result<()> {
            *self.params_update.lock().unwrap() = Some(update);
            Ok(())
        }
        fn reparameterize_kist(&self, _kist_params: KistParams) -> tor_proto::Result<()> {
            Ok(())
        }
        fn engage_padding_activities(&self) {}
    }
    impl tor_linkspec::HasRelayIds for FakeChannel {
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
    /// Get a fake ed25519 identity from the first byte of a string.
    fn str_to_ed(s: &str) -> Ed25519Identity {
        let byte = s.as_bytes()[0];
        [byte; 32].into()
    }
    fn ch(ident: &'static str) -> ChannelState<FakeChannel> {
        let channel = FakeChannel {
            ed_ident: str_to_ed(ident),
            usable: true,
            unused_duration: None,
            params_update: Arc::new(Mutex::new(None)),
        };
        ChannelState::Open(OpenEntry {
            channel: Arc::new(channel),
            max_unused_duration: Duration::from_secs(180),
        })
    }
    fn ch_with_details(
        ident: &'static str,
        max_unused_duration: Duration,
        unused_duration: Option<u64>,
    ) -> ChannelState<FakeChannel> {
        let channel = FakeChannel {
            ed_ident: str_to_ed(ident),
            usable: true,
            unused_duration,
            params_update: Arc::new(Mutex::new(None)),
        };
        ChannelState::Open(OpenEntry {
            channel: Arc::new(channel),
            max_unused_duration,
        })
    }
    fn closed(ident: &'static str) -> ChannelState<FakeChannel> {
        let channel = FakeChannel {
            ed_ident: str_to_ed(ident),
            usable: false,
            unused_duration: None,
            params_update: Arc::new(Mutex::new(None)),
        };
        ChannelState::Open(OpenEntry {
            channel: Arc::new(channel),
            max_unused_duration: Duration::from_secs(180),
        })
    }

    #[test]
    fn rmv_unusable() -> Result<()> {
        let map = new_test_state();

        map.with_channels(|map| {
            map.insert(closed("machen"));
            map.insert(closed("wir"));
            map.insert(ch("wir"));
            map.insert(ch("feinen"));
            map.insert(ch("Fug"));
            map.insert(ch("Fug"));
        })?;

        map.remove_unusable().unwrap();

        map.with_channels(|map| {
            assert_eq!(map.by_id(&str_to_ed("m")).len(), 0);
            assert_eq!(map.by_id(&str_to_ed("w")).len(), 1);
            assert_eq!(map.by_id(&str_to_ed("f")).len(), 1);
            assert_eq!(map.by_id(&str_to_ed("F")).len(), 2);
        })?;

        Ok(())
    }

    #[test]
    fn reparameterize_via_netdir() -> Result<()> {
        let map = new_test_state();

        // Set some non-default parameters so that we can tell when an update happens
        let _ = map
            .inner
            .lock()
            .unwrap()
            .channels_params
            .padding
            .start_update()
            .padding_parameters(
                PaddingParametersBuilder::default()
                    .low(1234.into())
                    .build()
                    .unwrap(),
            )
            .finish();

        map.with_channels(|map| {
            map.insert(ch("track"));
        })?;

        let netdir = tor_netdir::testnet::construct_netdir()
            .unwrap_if_sufficient()
            .unwrap();
        let netdir = Arc::new(netdir);

        let with_ch = |f: &dyn Fn(&FakeChannel)| {
            let inner = map.inner.lock().unwrap();
            let mut ch = inner.channels.by_ed25519(&str_to_ed("t"));
            let ch = ch.next().unwrap().unwrap_open();
            f(ch);
        };

        eprintln!("-- process a default netdir, which should send an update --");
        map.reconfigure_general(None, None, netdir.clone()).unwrap();
        with_ch(&|ch| {
            assert_eq!(
                format!("{:?}", ch.params_update.lock().unwrap().take().unwrap()),
                // evade field visibility by (ab)using Debug impl
                "ChannelPaddingInstructionsUpdates { padding_enable: None, \
                    padding_parameters: Some(Parameters { \
                        low: IntegerMilliseconds { value: 1500 }, \
                        high: IntegerMilliseconds { value: 9500 } }), \
                    padding_negotiate: None }"
            );
        });
        eprintln!();

        eprintln!("-- process a default netdir again, which should *not* send an update --");
        map.reconfigure_general(None, None, netdir).unwrap();
        with_ch(&|ch| assert!(ch.params_update.lock().unwrap().is_none()));

        Ok(())
    }

    #[test]
    fn expire_channels() -> Result<()> {
        let map = new_test_state();

        // Channel that has been unused beyond max duration allowed is expired
        map.with_channels(|map| {
            map.insert(ch_with_details(
                "wello",
                Duration::from_secs(180),
                Some(181),
            ));
        })?;

        // Minimum value of max unused duration is 180 seconds
        assert_eq!(180, map.expire_channels().as_secs());
        map.with_channels(|map| {
            assert_eq!(map.by_ed25519(&str_to_ed("w")).len(), 0);
        })?;

        let map = new_test_state();

        // Channel that has been unused for shorter than max unused duration
        map.with_channels(|map| {
            map.insert(ch_with_details(
                "wello",
                Duration::from_secs(180),
                Some(120),
            ));

            map.insert(ch_with_details(
                "yello",
                Duration::from_secs(180),
                Some(170),
            ));

            // Channel that has been unused beyond max duration allowed is expired
            map.insert(ch_with_details(
                "gello",
                Duration::from_secs(180),
                Some(181),
            ));

            // Closed channel should be retained
            map.insert(closed("hello"));
        })?;

        // Return duration until next channel expires
        assert_eq!(10, map.expire_channels().as_secs());
        map.with_channels(|map| {
            assert_eq!(map.by_ed25519(&str_to_ed("w")).len(), 1);
            assert_eq!(map.by_ed25519(&str_to_ed("y")).len(), 1);
            assert_eq!(map.by_ed25519(&str_to_ed("h")).len(), 1);
            assert_eq!(map.by_ed25519(&str_to_ed("g")).len(), 0);
        })?;
        Ok(())
    }
}
