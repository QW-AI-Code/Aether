//! Logic for filtering and selecting channels in order to find suitable channels for a target.

use crate::mgr::AbstractChannel;
use crate::mgr::state::{ChannelState, OpenEntry, PendingEntry};
use tor_linkspec::{HasChanMethod, HasRelayIds, RelayIds};

/// Returns `true` if the open channel is allowed to be used for a new channel request to the
/// target.
pub(crate) fn open_channel_is_allowed<C: AbstractChannel>(
    chan: &OpenEntry<C>,
    target: &(impl HasRelayIds + HasChanMethod),
) -> bool {
    Some(chan)
        // only usable channels
        .filter(|entry| entry.channel.is_usable())
        // only channels which have *all* the relay ids of `target`
        .filter(|entry| entry.channel.has_all_relay_ids_from(target))
        // TODO: only channels that satisfy the torspec rules at
        // https://spec.torproject.org/tor-spec/creating-circuits.html#canonical-connections
        .filter(|_entry| {
            // Any of:
            // - the IP matches the requested IP
            // - the relay knows that the IP of the connection it's using is canonical because it
            //   was listed in the NETINFO cell
            // - the IP matches the relay address in the consensus
            true
        })
        .is_some()
}

/// Returns `true` if the pending channel could possibly be used for a new channel request to the
/// target. You still need to verify the final built channel with [`open_channel_is_allowed`] before
/// using it.
pub(crate) fn pending_channel_maybe_allowed(
    chan: &PendingEntry,
    target: &(impl HasRelayIds + HasChanMethod),
) -> bool {
    /// An empty [`RelayIds`].
    const EMPTY_IDS: RelayIds = RelayIds::empty();

    // TODO RELAY: The comments and behaviour below assume that it's better to create a new channel
    // than to wait around for a channel which may or may not end up being usable for `target`. This
    // has the benefit that malicious circuit extension requests won't delay legitimate circuit
    // extension requests, but also means that we could end up creating more channels than
    // necessary. This is different from C-tor, which will wait for a channel even if that channel
    // might not end up being usable for `target`. For example in tor's `channel_get_for_extend`,
    // tor will wait for an "in progress" channel if all of the following are true:
    //
    // - The requested ids are a subset of the channel's ids. (Note that in the comments below we
    //   require it to be a superset, not a subset.)
    // - The requested IPv4 or IPv6 address matches either the channel's IPv4 or IPv6 address. (Note
    //   that in the comments below, we require `target`s addresses to exactly match.)
    //
    // It might be good to re-evaluate what behaviour we want as we implement more channel code.
    //
    // NOTE (opara): It has been decided that C-tor's approach would be better. See the thread at:
    // https://gitlab.torproject.org/tpo/core/arti/-/merge_requests/2544#note_3094696

    // We want to avoid returning pending channels that were initially created from malicious
    // channel requests (for example from malicious relay-extend requests) that build channels which
    // will never complete successfully. Two cases where this can happen are:
    // 1. A malicious channel request asks us to build a channel to a target with a correct relay id
    //    and address, but also an additional incorrect relay id. Later when the target sends its
    //    CERTS cell, all of the relay ids won't match and the channel will fail to build. We don't
    //    want to assign non-malicious channel requests to this pending channel that will eventually
    //    fail to build.
    // 2. A malicious channel request asks us to build a channel to a target with an incorrect
    //    address. This pending channel may stall. We don't want to assign non-malicious channel
    //    requests to this pending channel that will stall for potentially a long time.
    Some(chan)
        // Only channels where `target`s relay ids are a superset of `entry`s relay ids.
        // - Hopefully the built channel will gain the additional ids that are requested by
        //   `target`. This should happen in most cases where none of the channels are made
        //   maliciously, since the `target` should return all of its relay ids in its CERTS cell.
        // - (Addressing 1. above) By only returning pending channels that have a subset of
        //   `target`s relay ids, we ensure that the returned pending channel does not have
        //   additional incorrect relay ids that will intentionally cause the pending channel to
        //   fail.
        // - If the built channel does not gain the remaining ids required by `target, then we won't
        //   be able to use this channel for the channel request to `target`. But we won't be able
        //   to create a new channel either, since we know that that a new channel also won't have
        //   all of the relay ids. So this channel request was doomed from the start.
        // - If the built channel gains additional ids that `target` doesn't have, that's fine and
        //   we can still use the channel for `target`.
        .filter(|entry| target.has_all_relay_ids_from(&entry.ids))
        // TODO: Only channels which have the exact same address list as `target` (the two sets of
        // addresses must match exactly).
        // - While an EXTEND2 message usually only contains one IPv4 and IPv6 address, `target`
        //   (which is a `HasAddrs`) may have more addresses. According to tor-spec, an EXTEND2
        //   message can contain multiple IPv4 and IPv6 addresses:
        //   > Nodes MUST ignore unrecognized specifiers, and MUST accept multiple instances of
        //   > specifiers other than 'legacy identity' and 'Ed25519 identity'. (Nodes SHOULD reject
        //   > link specifier lists that include multiple instances of either one of those
        //   > specifiers.)
        // - (Addressing 2. above) By only returning pending channels that have exactly the same
        //   addresses, we ensure that the returned pending channel does not have any incorrect
        //   addresses that will cause the pending channel to stall.
        // - If the pending channel had additional addresses compared to `target`, the channel could
        //   get built using an address that is not valid for `target` and we wouldn't be able to
        //   use the built channel.
        // - If the pending channel had fewer addresses compared to `target`, the channel would have
        //   a lower possibility of building successfully compared to a newly created channel to
        //   `target`, so this would not be a good channel for us to return.
        .filter(|_entry| true)
        // Don't allow a pending channel that has no relay ids. I don't have a good reason for
        // excluding this, other than "it seems weird".
        .filter(|entry| entry.ids != EMPTY_IDS)
        .is_some()
}

/// Returns the best channel for `target`.
// TODO: remove me when the below TODOs are implemented
#[allow(clippy::only_used_in_recursion)]
pub(crate) fn choose_best_channel<'a, C: AbstractChannel>(
    channels: impl IntoIterator<Item = &'a ChannelState<C>>,
    target: &(impl HasRelayIds + HasChanMethod),
) -> Option<&'a ChannelState<C>> {
    use ChannelState::*;
    use std::cmp::Ordering;

    let channels = channels.into_iter();

    /// Compare two channels to determine the better channel for `target`.
    fn choose_channel<C: AbstractChannel>(
        a: &&ChannelState<C>,
        b: &&ChannelState<C>,
        target: &(impl HasRelayIds + HasChanMethod),
    ) -> Choice {
        // TODO: follow `channel_is_better` in C tor
        match (a, b) {
            // if the open channel is not usable, prefer the pending channel
            (Open(a), Building(_b)) if !a.channel.is_usable() => Choice::Second,
            // otherwise prefer the open channel
            (Open(_a), Building(_b)) => Choice::First,

            // the logic above, but reversed
            (Building(_), Open(_)) => choose_channel(b, a, target).reverse(),

            // not much info to help choose when both channels are pending, but this should be rare
            (Building(_a), Building(_b)) => Choice::Either,

            // both channels are open
            (Open(a), Open(b)) => {
                let a_is_usable = a.channel.is_usable();
                let b_is_usable = b.channel.is_usable();

                // if neither open channel is usable, don't take preference
                if !a_is_usable && !b_is_usable {
                    return Choice::Either;
                }

                // prefer a channel that is usable
                if !a_is_usable {
                    return Choice::Second;
                }
                if !b_is_usable {
                    return Choice::First;
                }

                // Prefer a channel that we see as canonical.
                let a_is_canonical = a.channel.is_canonical();
                let b_is_canonical = b.channel.is_canonical();

                if a_is_canonical && !b_is_canonical {
                    return Choice::First;
                }
                if !a_is_canonical && b_is_canonical {
                    return Choice::Second;
                }

                // Prefer a channel that the peer sees as canonical.
                let a_is_canonical_to_peer = a.channel.is_canonical_to_peer();
                let b_is_canonical_to_peer = b.channel.is_canonical_to_peer();

                if a_is_canonical_to_peer && !b_is_canonical_to_peer {
                    return Choice::First;
                }
                if !a_is_canonical_to_peer && b_is_canonical_to_peer {
                    return Choice::Second;
                }

                // TODO: prefer a channel where the address matches the target

                // TODO: prefer older channels

                // TODO: use number of circuits as tie-breaker?

                Choice::Either
            }
        }
    }

    // preferred channels will be ordered higher, and we choose the max
    channels.max_by(|a, b| match choose_channel(a, b, target) {
        Choice::First => Ordering::Greater,
        Choice::Second => Ordering::Less,
        Choice::Either => Ordering::Equal,
    })
}

/// Similar to [`Ordering`](std::cmp::Ordering), but is easier to reason about when comparing two
/// objects that don't have a numeric sense of ordering (ex: returning `Greater` is confusing if the
/// ordering isn't numeric).
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
enum Choice {
    /// Choose the first.
    First,
    /// Choose the second.
    Second,
    /// Choose either.
    Either,
}

impl Choice {
    /// Reverses the `Choice`.
    ///
    /// - `First` becomes `Second`.
    /// - `Second` becomes `First`.
    /// - `Either` becomes `Either`.
    fn reverse(self) -> Self {
        match self {
            Self::First => Self::Second,
            Self::Second => Self::First,
            Self::Either => Self::Either,
        }
    }
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

    use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
    use std::sync::Arc;
    use std::time::Duration;

    use tor_linkspec::ChannelMethod;
    use tor_llcrypto::pk::ed25519::Ed25519Identity;
    use tor_llcrypto::pk::rsa::RsaIdentity;
    use tor_proto::channel::ChannelPaddingInstructionsUpdates;
    use tor_proto::channel::kist::KistParams;

    // Address we can use in tests.
    const ADDR_A: SocketAddr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(1, 1, 1, 1), 443));

    #[derive(Debug)]
    struct FakeChannel {
        usable: bool,
        ids: RelayIds,
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
            None
        }
        fn reparameterize(
            &self,
            _updates: Arc<ChannelPaddingInstructionsUpdates>,
        ) -> tor_proto::Result<()> {
            Ok(())
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
            self.ids.identity(key_type)
        }
    }

    #[derive(Clone, Debug)]
    struct FakeBuildSpec {
        ids: RelayIds,
        addrs: Vec<SocketAddr>,
    }

    impl FakeBuildSpec {
        fn new(ids: RelayIds, addrs: Vec<SocketAddr>) -> Self {
            Self { ids, addrs }
        }
    }

    impl HasRelayIds for FakeBuildSpec {
        fn identity(
            &self,
            key_type: tor_linkspec::RelayIdType,
        ) -> Option<tor_linkspec::RelayIdRef<'_>> {
            self.ids.identity(key_type)
        }
    }

    impl HasChanMethod for FakeBuildSpec {
        fn chan_method(&self) -> ChannelMethod {
            ChannelMethod::Direct(self.addrs.clone())
        }
    }

    /// Assert that two `Option<&T>` point to the same data.
    macro_rules! assert_opt_ptr_eq {
        ($a:expr, $b:expr) => {
            assert_opt_ptr_eq!($a, $b,);
        };
        ($a:expr, $b:expr, $($x:tt)*) => {
            assert_eq!($a.map(std::ptr::from_ref), $b.map(std::ptr::from_ref), $($x)*);
        };
    }

    /// Calls `f` with every permutation of `list`. Don't use with large lists :)
    fn with_permutations<T>(list: &[T], mut f: impl FnMut(Vec<&T>)) {
        use itertools::Itertools;
        for new_list in list.iter().permutations(list.len()) {
            f(new_list);
        }
    }

    /// Helper to make a fake Ed identity from some bytes.
    fn ed(a: &[u8]) -> Ed25519Identity {
        let mut bytes = [0; 32];
        bytes[0..a.len()].copy_from_slice(a);
        bytes.into()
    }

    /// Helper to make a fake rsa identity from some bytes.
    fn rsa(a: &[u8]) -> RsaIdentity {
        let mut bytes = [0; 20];
        bytes[0..a.len()].copy_from_slice(a);
        bytes.into()
    }

    /// Helper to build a `RelayIds` to make tests shorter.
    fn ids(
        rsa: impl Into<Option<RsaIdentity>>,
        ed: impl Into<Option<Ed25519Identity>>,
    ) -> RelayIds {
        let mut ids = tor_linkspec::RelayIdsBuilder::default();
        if let Some(rsa) = rsa.into() {
            ids.rsa_identity(rsa);
        }
        if let Some(ed) = ed.into() {
            ids.ed_identity(ed);
        }
        ids.build().unwrap()
    }

    /// Create an open channel entry.
    fn open_channel<C>(chan: C) -> OpenEntry<C> {
        OpenEntry {
            channel: Arc::new(chan),
            max_unused_duration: Duration::from_secs(0),
        }
    }

    /// Create a pending channel entry with the given IDs.
    fn pending_channel(ids: RelayIds) -> PendingEntry {
        use crate::mgr::state::UniqPendingChanId;
        use futures::FutureExt;
        use oneshot_fused_workaround as oneshot;

        PendingEntry {
            ids,
            pending: oneshot::channel().1.shared(),
            unique_id: UniqPendingChanId::new(),
        }
    }

    #[test]
    fn best_channel_usable_unusable() {
        // two channels where only the first is usable
        let channels = [
            ChannelState::Open(open_channel(FakeChannel {
                usable: true,
                ids: ids(None, ed(b"A")),
            })),
            ChannelState::Open(open_channel(FakeChannel {
                usable: false,
                ids: ids(None, ed(b"A")),
            })),
        ];

        // should return the usable channel
        let target = FakeBuildSpec::new(ids(None, ed(b"A")), vec![ADDR_A]);
        with_permutations(&channels, |x| {
            assert_opt_ptr_eq!(choose_best_channel(x, &target), Some(&channels[0]));
        });
    }

    #[test]
    fn best_channel_open_pending() {
        // a usable open channel and a pending channel
        let channels = [
            ChannelState::Open(open_channel(FakeChannel {
                usable: true,
                ids: ids(None, ed(b"A")),
            })),
            ChannelState::Building(pending_channel(ids(None, ed(b"A")))),
        ];

        // should return the open channel
        let target = FakeBuildSpec::new(ids(None, ed(b"A")), vec![ADDR_A]);
        with_permutations(&channels, |x| {
            assert_opt_ptr_eq!(choose_best_channel(x, &target), Some(&channels[0]));
        });

        // an unusable open channel and a pending channel
        let channels = [
            ChannelState::Open(open_channel(FakeChannel {
                usable: false,
                ids: ids(None, ed(b"A")),
            })),
            ChannelState::Building(pending_channel(ids(None, ed(b"A")))),
        ];

        // should return the pending channel
        let target = FakeBuildSpec::new(ids(None, ed(b"A")), vec![ADDR_A]);
        with_permutations(&channels, |x| {
            assert_opt_ptr_eq!(choose_best_channel(x, &target), Some(&channels[1]));
        });
    }

    #[test]
    fn best_channel_many() {
        // some misc channels (as we make `choose_best_channel` more complex, hopeful we can add
        // more channels here)
        let channels = [
            ChannelState::Open(open_channel(FakeChannel {
                usable: false,
                ids: ids(None, ed(b"A")),
            })),
            ChannelState::Open(open_channel(FakeChannel {
                usable: true,
                ids: ids(None, ed(b"A")),
            })),
            ChannelState::Building(pending_channel(ids(None, ed(b"A")))),
            ChannelState::Building(pending_channel(ids(None, None))),
        ];

        // should return the open+usable channel
        let target = FakeBuildSpec::new(ids(None, ed(b"A")), vec![ADDR_A]);
        with_permutations(&channels, |x| {
            assert_opt_ptr_eq!(choose_best_channel(x, &target), Some(&channels[1]));
        });
    }

    #[test]
    fn test_open_channel_is_allowed() {
        // target with an ed relay id
        let target = FakeBuildSpec::new(ids(None, ed(b"A")), vec![ADDR_A]);

        // not allowed: unusable channel
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: false,
                ids: ids(None, ed(b"A")),
            }),
            &target,
        ));

        // allowed: usable channel with correct relay id
        assert!(open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(None, ed(b"A")),
            }),
            &target,
        ));

        // not allowed: usable channel with incorrect relay id
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(None, ed(b"B")),
            }),
            &target,
        ));

        // not allowed: usable channel with no relay ids
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(None, None),
            }),
            &target,
        ));

        // allowed: usable channel with additional relay id
        assert!(open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(rsa(b"X"), ed(b"A")),
            }),
            &target,
        ));

        // not allowed: usable channel with missing ed relay id
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(rsa(b"X"), None),
            }),
            &target,
        ));

        // target with no relay id
        let target = FakeBuildSpec::new(ids(None, None), vec![ADDR_A]);

        // not allowed: unusable channel
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: false,
                ids: ids(None, None),
            }),
            &target,
        ));

        // allowed: usable channel with no relay ids
        assert!(open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(None, None),
            }),
            &target,
        ));

        // target with multiple relay ids
        let target = FakeBuildSpec::new(ids(rsa(b"X"), ed(b"A")), vec![ADDR_A]);

        // not allowed: unusable channel
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: false,
                ids: ids(rsa(b"X"), ed(b"A")),
            }),
            &target,
        ));

        // allowed: usable channel with correct relay ids
        assert!(open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(rsa(b"X"), ed(b"A")),
            }),
            &target,
        ));

        // not allowed: usable channel with partial relay ids
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(None, ed(b"A")),
            }),
            &target,
        ));
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(rsa(b"X"), None),
            }),
            &target,
        ));

        // not allowed: usable channel with one incorrect relay id
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(rsa(b"X"), ed(b"B")),
            }),
            &target,
        ));
        assert!(!open_channel_is_allowed(
            &open_channel(FakeChannel {
                usable: true,
                ids: ids(rsa(b"Y"), ed(b"A")),
            }),
            &target,
        ));
    }

    #[test]
    fn test_pending_channel_maybe_allowed() {
        // target with an ed relay id
        let target = FakeBuildSpec::new(ids(None, ed(b"A")), vec![ADDR_A]);

        // allowed: channel with same relay id
        assert!(pending_channel_maybe_allowed(
            &pending_channel(ids(None, ed(b"A"))),
            &target,
        ));

        // not allowed: channel with additional relay id
        assert!(!pending_channel_maybe_allowed(
            &pending_channel(ids(rsa(b"X"), ed(b"A"))),
            &target,
        ));

        // target with multiple relay ids
        let target = FakeBuildSpec::new(ids(rsa(b"X"), ed(b"A")), vec![ADDR_A]);

        // allowed: channel with same relay ids
        assert!(pending_channel_maybe_allowed(
            &pending_channel(ids(rsa(b"X"), ed(b"A"))),
            &target,
        ));

        // allowed: channel with fewer relay ids
        assert!(pending_channel_maybe_allowed(
            &pending_channel(ids(None, ed(b"A"))),
            &target,
        ));
        assert!(pending_channel_maybe_allowed(
            &pending_channel(ids(rsa(b"X"), None)),
            &target,
        ));

        // not allowed: channel with no relay ids
        assert!(!pending_channel_maybe_allowed(
            &pending_channel(ids(None, None)),
            &target,
        ));

        // target with no relay ids
        let target = FakeBuildSpec::new(ids(None, None), vec![ADDR_A]);

        // not allowed: channel with a relay id
        assert!(!pending_channel_maybe_allowed(
            &pending_channel(ids(None, ed(b"A"))),
            &target,
        ));

        // not allowed: channel with no relay ids
        assert!(!pending_channel_maybe_allowed(
            &pending_channel(ids(None, None)),
            &target,
        ));
    }
}
