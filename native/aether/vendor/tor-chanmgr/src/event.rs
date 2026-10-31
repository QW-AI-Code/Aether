//! Code for exporting events from the channel manager.
#![allow(dead_code, unreachable_pub)]

use educe::Educe;
use futures::{Stream, StreamExt};
use postage::watch;
use std::fmt;
use tor_basic_utils::skip_fmt;
use web_time_compat::{Duration, Instant, InstantExt};

/// The status of our connection to the internet.
#[derive(Default, Debug, Clone)]
pub struct ConnStatus {
    /// Have we been able to make TCP connections?
    ///
    /// True if we've been able to make outgoing connections recently.
    /// False if we've definitely been failing.
    /// None if we haven't succeeded yet, but it's too early to say if
    /// that's a problem.
    online: Option<bool>,

    /// Have we ever been able to make TLS handshakes and negotiate
    /// certificates, _not including timeliness checking_?
    ///
    /// True if we've been able to make TLS handshakes and talk to Tor relays we
    /// like recently. False if we've definitely been failing. None if we
    /// haven't succeeded yet, but it's too early to say if that's a problem.
    auth_works: Option<bool>,

    /// Have we been able to successfully negotiate full Tor handshakes?
    ///
    /// True if we've been able to make Tor handshakes recently.
    /// False if we've definitely been failing.
    /// None if we haven't succeeded yet, but it's too early to say if
    /// that's a problem.
    handshake_works: Option<bool>,
}

/// A problem detected while connecting to the Tor network.
#[derive(Debug, Clone, Eq, PartialEq, derive_more::Display)]
#[non_exhaustive]
pub enum ConnBlockage {
    #[display("unable to connect to the internet")]
    /// We haven't been able to make successful TCP connections.
    NoTcp,
    /// We've made TCP connections, but our TLS connections either failed, or
    /// got hit by an attempted man-in-the-middle attack.
    #[display("our internet connection seems to be filtered")]
    NoHandshake,
    /// We've made TCP connections, and our TLS connections mostly succeeded,
    /// but we encountered failures that are well explained by clock skew,
    /// or expired certificates.
    #[display("relays all seem to be using expired certificates")]
    CertsExpired,
}

impl ConnStatus {
    /// Return true if this status is equal to `other`.
    ///
    /// Note:(This would just be a PartialEq implementation, but I'm not sure I
    /// want to expose that PartialEq for this struct.)
    fn eq(&self, other: &ConnStatus) -> bool {
        self.online == other.online && self.handshake_works == other.handshake_works
    }

    /// Return true if this status indicates that we can successfully open Tor channels.
    pub fn usable(&self) -> bool {
        self.online == Some(true) && self.handshake_works == Some(true)
    }

    /// Return a float representing "how bootstrapped" we are with respect to
    /// connecting to the Tor network, where 0 is "not at all" and 1 is
    /// "successful".
    ///
    /// Callers _should not_ depend on the specific meaning of any particular
    /// fraction; we may change these fractions in the future.
    pub fn frac(&self) -> f32 {
        match self {
            Self {
                online: Some(true),
                auth_works: Some(true),
                handshake_works: Some(true),
            } => 1.0,
            Self {
                online: Some(true), ..
            } => 0.5,
            _ => 0.0,
        }
    }

    /// Return the cause of why we aren't able to connect to the Tor network,
    /// if we think we're stuck.
    pub fn blockage(&self) -> Option<ConnBlockage> {
        match self {
            Self {
                online: Some(false),
                ..
            } => Some(ConnBlockage::NoTcp),
            Self {
                auth_works: Some(false),
                ..
            } => Some(ConnBlockage::NoHandshake),
            Self {
                handshake_works: Some(false),
                ..
            } => Some(ConnBlockage::CertsExpired),
            _ => None,
        }
    }
}

impl fmt::Display for ConnStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ConnStatus { online: None, .. } => write!(f, "connecting to the internet"),
            ConnStatus {
                online: Some(false),
                ..
            } => write!(f, "unable to connect to the internet"),
            ConnStatus {
                handshake_works: None,
                ..
            } => write!(f, "handshaking with Tor relays"),
            ConnStatus {
                auth_works: Some(true),
                handshake_works: Some(false),
                ..
            } => write!(
                f,
                "unable to handshake with Tor relays, possibly due to clock skew"
            ),
            ConnStatus {
                handshake_works: Some(false),
                ..
            } => write!(f, "unable to handshake with Tor relays"),
            ConnStatus {
                online: Some(true),
                handshake_works: Some(true),
                ..
            } => write!(f, "connecting successfully"),
        }
    }
}

/// A stream of [`ConnStatus`] events describing changes in our connected-ness.
///
/// This stream is lossy; a reader might not see some events on the stream, if
/// they are produced faster than the reader can consume.  In that case, the
/// reader will see more recent updates, and miss older ones.
///
/// Note that the bootstrap status is not monotonic: we might become less
/// bootstrapped than we were before.  (For example, the internet could go
/// down.)
#[derive(Clone, Educe)]
#[educe(Debug)]
pub struct ConnStatusEvents {
    /// The receiver that implements this stream.
    ///
    /// (We wrap it in a new type here so that we can replace the implementation
    /// later on if we need to.)
    #[educe(Debug(method = "skip_fmt"))]
    inner: watch::Receiver<ConnStatus>,
}

impl Stream for ConnStatusEvents {
    type Item = ConnStatus;
    fn poll_next(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Self::Item>> {
        self.inner.poll_next_unpin(cx)
    }
}

/// Crate-internal view of "how connected are we to the internet?"
///
/// This is a more complex and costly structure than ConnStatus, so we track
/// this here, and only expose the minimum via ConnStatus over a
/// `postage::watch`.  Later, we might want to expose more of this information.
//
// TODO: Eventually we should add some ability to reset our bootstrap status, if
// our connections start failing.
#[derive(Debug, Clone)]
struct ChanMgrStatus {
    /// When did we first get initialized?
    startup: Instant,

    /// Since we started, how many channels have we tried to build?
    n_attempts: usize,

    /// When (if ever) have we made a TCP connection to (what we hoped was) a
    /// Tor relay?
    ///
    /// If we don't reach this point, we're probably not on the internet.
    ///
    /// If we get no further than this, we're probably having our TCP
    /// connections captured or replaced.
    last_tcp_success: Option<Instant>,

    /// When (if ever) have we successfully finished a TLS handshake to (what we
    /// hoped was) a Tor relay?
    ///
    /// If we get no further than this, we might be facing a TLS MITM attack.
    //
    // TODO: We don't actually use this information yet: our output doesn't
    // distinguish filtering where TLS succeeds but gets MITM'd from filtering
    // where TLS fails.
    last_tls_success: Option<Instant>,

    /// When (if ever) have we ever finished the inner Tor handshake with a relay,
    /// up to the point where we check for certificate timeliness?
    last_chan_auth_success: Option<Instant>,

    /// When (if ever) have we successfully finished the inner Tor handshake
    /// with a relay?
    ///
    /// If we get to this point, we can successfully talk to something that
    /// holds the private key that it's supposed to.
    last_chan_success: Option<Instant>,
}

impl ChanMgrStatus {
    /// Construct a new ChanMgr status.
    ///
    /// It will be built as having been initialized at the time `now`.
    fn new_at(now: Instant) -> ChanMgrStatus {
        ChanMgrStatus {
            startup: now,
            n_attempts: 0,
            last_tcp_success: None,
            last_tls_success: None,
            last_chan_auth_success: None,
            last_chan_success: None,
        }
    }

    /// Return a [`ConnStatus`] for the current state, at time `now`.
    ///
    /// (The time is necessary because a lack of success doesn't indicate a
    /// problem until enough time has passed.)
    fn conn_status_at(&self, now: Instant) -> ConnStatus {
        /// How long do we need to be online before we'll acknowledge failure?
        const MIN_DURATION: Duration = Duration::from_secs(60);
        /// How many attempts do we need to launch before we'll acknowledge failure?
        const MIN_ATTEMPTS: usize = 6;

        // If set, it's too early to determine failure.
        let early = now < self.startup + MIN_DURATION || self.n_attempts < MIN_ATTEMPTS;

        let online = match (self.last_tcp_success.is_some(), early) {
            (true, _) => Some(true),
            (_, true) => None,
            (false, false) => Some(false),
        };

        let auth_works = match (self.last_chan_auth_success.is_some(), early) {
            (true, _) => Some(true),
            (_, true) => None,
            (false, false) => Some(false),
        };

        let handshake_works = match (self.last_chan_success.is_some(), early) {
            (true, _) => Some(true),
            (_, true) => None,
            (false, false) => Some(false),
        };

        ConnStatus {
            online,
            auth_works,
            handshake_works,
        }
    }

    /// Note that an attempt to connect has been started.
    fn record_attempt(&mut self) {
        self.n_attempts += 1;
    }

    /// Note that we've successfully done a TCP handshake with an alleged relay.
    fn record_tcp_success(&mut self, now: Instant) {
        self.last_tcp_success = Some(now);
    }

    /// Note that we've completed a TLS handshake with an alleged relay.
    ///
    /// (Its identity won't be verified till the next step.)
    fn record_tls_finished(&mut self, now: Instant) {
        self.last_tls_success = Some(now);
    }

    /// Note that we've completed a Tor handshake with a relay, _but failed to
    /// verify the certificates in a way that could indicate clock skew_.
    fn record_handshake_done_with_skewed_clock(&mut self, now: Instant) {
        self.last_chan_auth_success = Some(now);
    }

    /// Note that we've completed a Tor handshake with a relay.
    ///
    /// (This includes performing the TLS handshake, and verifying that the
    /// relay was indeed the one that we wanted to reach.)
    fn record_handshake_done(&mut self, now: Instant) {
        self.last_chan_auth_success = Some(now);
        self.last_chan_success = Some(now);
    }
}

/// Object that manages information about a `ChanMgr`'s status, and sends
/// information about connectivity changes over an asynchronous channel
pub(crate) struct ChanMgrEventSender {
    /// The last ConnStatus that we sent over the channel.
    last_conn_status: ConnStatus,
    /// The unsummarized status information from the ChanMgr.
    mgr_status: ChanMgrStatus,
    /// The channel that we use for sending ConnStatus information.
    sender: watch::Sender<ConnStatus>,
}

impl ChanMgrEventSender {
    /// If the status has changed as of `now`, tell any listeners.
    ///
    /// (This takes a time because we need to know how much time has elapsed
    /// without successful attempts.)
    ///
    /// # Limitations
    ///
    /// We are dependent on calls to `record_attempt()` and similar methods to
    /// actually invoke this function; if they were never called, we'd never
    /// notice that we had gone too long without building connections.  That's
    /// okay for now, though, since any Tor client will immediately start
    /// building circuits, which will launch connection attempts until one
    /// succeeds or the client gives up entirely.  
    fn push_at(&mut self, now: Instant) {
        let status = self.mgr_status.conn_status_at(now);
        if !status.eq(&self.last_conn_status) {
            self.last_conn_status = status.clone();
            let mut b = self.sender.borrow_mut();
            *b = status;
        }
    }

    /// Note that an attempt to connect has been started.
    pub(crate) fn record_attempt(&mut self) {
        self.mgr_status.record_attempt();
        self.push_at(Instant::get());
    }

    /// Note that we've successfully done a TCP handshake with an alleged relay.
    pub(crate) fn record_tcp_success(&mut self) {
        let now = Instant::get();
        self.mgr_status.record_tcp_success(now);
        self.push_at(now);
    }

    /// Note that we've completed a TLS handshake with an alleged relay.
    ///
    /// (Its identity won't be verified till the next step.)
    pub(crate) fn record_tls_finished(&mut self) {
        let now = Instant::get();
        self.mgr_status.record_tls_finished(now);
        self.push_at(now);
    }

    /// Record that a handshake has succeeded _except for the certificate
    /// timeliness check, which may indicate a skewed clock.
    pub(crate) fn record_handshake_done_with_skewed_clock(&mut self) {
        let now = Instant::get();
        self.mgr_status.record_handshake_done_with_skewed_clock(now);
        self.push_at(now);
    }

    /// Note that we've completed a Tor handshake with a relay.
    ///
    /// (This includes performing the TLS handshake, and verifying that the
    /// relay was indeed the one that we wanted to reach.)
    pub(crate) fn record_handshake_done(&mut self) {
        let now = Instant::get();
        self.mgr_status.record_handshake_done(now);
        self.push_at(now);
    }
}

/// Create a new channel for sending connectivity status events to other crates.
pub(crate) fn channel() -> (ChanMgrEventSender, ConnStatusEvents) {
    let (sender, receiver) = watch::channel();
    let receiver = ConnStatusEvents { inner: receiver };
    let sender = ChanMgrEventSender {
        last_conn_status: ConnStatus::default(),
        mgr_status: ChanMgrStatus::new_at(Instant::get()),
        sender,
    };
    (sender, receiver)
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
    use float_eq::assert_float_eq;

    /// Tolerance for float comparison.
    const TOL: f32 = 0.00001;

    #[test]
    fn status_basics() {
        let s1 = ConnStatus::default();
        assert_eq!(s1.to_string(), "connecting to the internet");
        assert_float_eq!(s1.frac(), 0.0, abs <= TOL);
        assert!(s1.eq(&s1));
        assert!(s1.blockage().is_none());
        assert!(!s1.usable());

        let s2 = ConnStatus {
            online: Some(false),
            auth_works: None,
            handshake_works: None,
        };
        assert_eq!(s2.to_string(), "unable to connect to the internet");
        assert_float_eq!(s2.frac(), 0.0, abs <= TOL);
        assert!(s2.eq(&s2));
        assert!(!s2.eq(&s1));
        assert_eq!(s2.blockage(), Some(ConnBlockage::NoTcp));
        assert_eq!(
            s2.blockage().unwrap().to_string(),
            "unable to connect to the internet"
        );
        assert!(!s2.usable());

        let s3 = ConnStatus {
            online: Some(true),
            auth_works: None,
            handshake_works: None,
        };
        assert_eq!(s3.to_string(), "handshaking with Tor relays");
        assert_float_eq!(s3.frac(), 0.5, abs <= TOL);
        assert_eq!(s3.blockage(), None);
        assert!(!s3.eq(&s1));
        assert!(!s3.usable());

        let s4 = ConnStatus {
            online: Some(true),
            auth_works: Some(false),
            handshake_works: Some(false),
        };
        assert_eq!(s4.to_string(), "unable to handshake with Tor relays");
        assert_float_eq!(s4.frac(), 0.5, abs <= TOL);
        assert_eq!(s4.blockage(), Some(ConnBlockage::NoHandshake));
        assert_eq!(
            s4.blockage().unwrap().to_string(),
            "our internet connection seems to be filtered"
        );
        assert!(!s4.eq(&s1));
        assert!(!s4.eq(&s2));
        assert!(!s4.eq(&s3));
        assert!(s4.eq(&s4));
        assert!(!s4.usable());

        let s5 = ConnStatus {
            online: Some(true),
            auth_works: Some(true),
            handshake_works: Some(true),
        };
        assert_eq!(s5.to_string(), "connecting successfully");
        assert_float_eq!(s5.frac(), 1.0, abs <= TOL);
        assert!(s5.blockage().is_none());
        assert!(s5.eq(&s5));
        assert!(!s5.eq(&s4));
        assert!(s5.usable());
    }

    #[test]
    fn derive_status() {
        let start = Instant::get();
        let sec = Duration::from_secs(1);
        let hour = Duration::from_secs(3600);

        let mut ms = ChanMgrStatus::new_at(start);

        // when we start, we're unable to reach any conclusions.
        let s0 = ms.conn_status_at(start);
        assert!(s0.online.is_none());
        assert!(s0.handshake_works.is_none());

        // Time won't let us make conclusions either, unless there have been
        // attempts.
        let s = ms.conn_status_at(start + hour);
        assert!(s.eq(&s0));

        // But if there have been attempts, _and_ time has passed, we notice
        // failure.
        for _ in 0..10 {
            ms.record_attempt();
        }
        // (Not immediately...)
        let s = ms.conn_status_at(start);
        assert!(s.eq(&s0));
        // (... but after a while.)
        let s = ms.conn_status_at(start + hour);
        assert_eq!(s.online, Some(false));
        assert_eq!(s.handshake_works, Some(false));

        // If TCP has succeeded, we should notice that.
        ms.record_tcp_success(start + sec);
        let s = ms.conn_status_at(start + sec * 2);
        assert_eq!(s.online, Some(true));
        assert!(s.handshake_works.is_none());
        let s = ms.conn_status_at(start + hour);
        assert_eq!(s.online, Some(true));
        assert_eq!(s.handshake_works, Some(false));

        // If the handshake succeeded, we can notice that too.
        ms.record_handshake_done(start + sec * 2);
        let s = ms.conn_status_at(start + sec * 3);
        assert_eq!(s.online, Some(true));
        assert_eq!(s.handshake_works, Some(true));
    }

    #[test]
    fn sender() {
        let (mut snd, rcv) = channel();

        {
            let s = rcv.inner.borrow().clone();
            assert_float_eq!(s.frac(), 0.0, abs <= TOL);
        }

        snd.record_attempt();
        snd.record_tcp_success();
        snd.record_tls_finished();
        snd.record_handshake_done();

        {
            let s = rcv.inner.borrow().clone();
            assert_float_eq!(s.frac(), 1.0, abs <= TOL);
        }
    }
}
