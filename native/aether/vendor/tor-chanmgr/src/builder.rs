//! Implement a concrete type to build channels over a transport.

use async_trait::async_trait;
use futures::{AsyncRead, AsyncWrite};
use std::io;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tracing::instrument;

use crate::factory::{BootstrapReporter, ChannelFactory, IncomingChannelFactory};
use crate::transport::TransportImplHelper;
use crate::{Error, event::ChanMgrEventSender};

use safelog::MaybeSensitive;
use tor_basic_utils::rand_hostname;
use tor_error::internal;
use tor_linkspec::{ChanTarget, HasChanMethod, IntoOwnedChanTarget, OwnedChanTarget};
use tor_proto::channel::ChannelType;
use tor_proto::channel::kist::KistParams;
use tor_proto::channel::params::ChannelPaddingInstructionsUpdates;
use tor_proto::memquota::ChannelAccount;
use tor_proto::peer::PeerAddr;
use tor_rtcompat::SpawnExt;
use tor_rtcompat::{CertifiedConn, Runtime, StreamOps, TlsProvider, tls::TlsConnector};

#[cfg(feature = "relay")]
use {
    safelog::Sensitive, std::net::SocketAddr, tor_error::bad_api_usage,
    tor_proto::RelayChannelAuthMaterial, tor_proto::relay::CreateRequestHandler,
};

/// TLS-based channel builder.
///
/// This is a separate type so that we can keep our channel management code
/// network-agnostic.
///
/// It uses a provided `TransportHelper` type to make a connection (possibly
/// directly over TCP, and possibly over some other protocol).  It then
/// negotiates TLS over that connection, and negotiates a Tor channel over that
/// TLS session.
///
/// This channel builder does not retry on failure, but it _does_ implement a
/// time-out.
pub struct ChanBuilder<R: Runtime, H: TransportImplHelper>
where
    R: tor_rtcompat::TlsProvider<H::Stream>,
{
    /// Asynchronous runtime for TLS, TCP, spawning, and timeouts.
    runtime: R,
    /// The transport object that we use to construct streams.
    transport: H,
    /// Object to build TLS connections.
    tls_connector: <R as TlsProvider<H::Stream>>::Connector,
    /// Object to accept TLS connections.
    #[cfg(feature = "relay")]
    tls_acceptor: Option<<R as TlsProvider<H::Stream>>::Acceptor>,
    /// Relay authentication key material needed for relay channels.
    #[cfg(feature = "relay")]
    auth_material: Option<Arc<RelayChannelAuthMaterial>>,
    /// Our address(es) to use in the NETINFO cell.
    // TODO: We might want one day to support updating the addresses here in the same way we
    // support updating the auth_material. One use case for this is the relay config reload.
    #[cfg(feature = "relay")]
    my_addrs: Vec<SocketAddr>,
    /// Provided to each new channel so that they can handle CREATE* requests.
    #[cfg(feature = "relay")]
    create_request_handler: Option<Arc<CreateRequestHandler>>,
}

impl<R: Runtime, H: TransportImplHelper> ChanBuilder<R, H>
where
    R: TlsProvider<H::Stream>,
{
    /// Construct a new client specific ChanBuilder.
    pub fn new_client(runtime: R, transport: H) -> Self {
        let tls_connector = <R as TlsProvider<H::Stream>>::tls_connector(&runtime);
        ChanBuilder {
            runtime,
            transport,
            tls_connector,
            #[cfg(feature = "relay")]
            tls_acceptor: None,
            #[cfg(feature = "relay")]
            auth_material: None,
            #[cfg(feature = "relay")]
            my_addrs: Vec::new(),
            #[cfg(feature = "relay")]
            create_request_handler: None,
        }
    }

    /// Construct a new relay specific ChanBuilder.
    #[cfg(feature = "relay")]
    pub fn new_relay(
        runtime: R,
        transport: H,
        auth_material: Arc<RelayChannelAuthMaterial>,
        my_addrs: Vec<SocketAddr>,
        create_request_handler: Option<Arc<CreateRequestHandler>>,
    ) -> crate::Result<Self> {
        use tor_error::into_internal;
        use tor_rtcompat::tls::TlsAcceptorSettings;

        // Build the TLS acceptor.
        let tls_settings = TlsAcceptorSettings::new(auth_material.tls_key_and_cert().clone())
            .map_err(into_internal!("Unable to build TLS acceptor setting"))?;
        let tls_acceptor = <R as TlsProvider<H::Stream>>::tls_acceptor(&runtime, tls_settings)
            .map_err(into_internal!("Unable to build TLS acceptor"))?;

        // Same builder as a client but with auth material and acceptor.
        let mut builder = Self::new_client(runtime, transport);
        builder.auth_material = Some(auth_material);
        builder.tls_acceptor = Some(tls_acceptor);
        builder.my_addrs = my_addrs;
        builder.create_request_handler = create_request_handler;

        Ok(builder)
    }

    /// Build a new `ChanBuilder` with the given `auth_material`,
    /// cloning everything else.
    ///
    /// This is needed because some relay keys rotate over time.
    #[cfg(feature = "relay")]
    pub fn rebuild_with_auth_material(
        &self,
        auth_material: Arc<RelayChannelAuthMaterial>,
    ) -> crate::Result<Self>
    where
        H: Clone,
    {
        Self::new_relay(
            self.runtime.clone(),
            self.transport.clone(),
            auth_material,
            self.my_addrs.clone(),
            self.create_request_handler.as_ref().map(Arc::clone),
        )
    }

    /// Build a new `ChanBuilder` with the given CREATE* request `handler`,
    /// cloning everything else.
    ///
    /// This is needed so that we can set the handler,
    /// which isn't known when the builder is initially created.
    #[cfg(feature = "relay")]
    pub fn rebuild_with_create_request_handler(
        &self,
        handler: Arc<CreateRequestHandler>,
    ) -> crate::Result<Self>
    where
        H: Clone,
    {
        let auth_material = self.auth_material.clone().ok_or_else(|| {
            internal!("Trying to set a CREATE* request handler for a non-relay channel builder")
        })?;

        Self::new_relay(
            self.runtime.clone(),
            self.transport.clone(),
            auth_material,
            self.my_addrs.clone(),
            Some(handler),
        )
    }

    /// Return the outbound channel type of this config.
    ///
    /// The channel type is used when creating outbound channels. Relays always initiate channels
    /// as "relay initiator" while client and bridges behave like a "client initiator".
    ///
    /// Important: The wrong channel type is returned if this is called before `with_identities()`
    /// is called.
    fn outbound_chan_type(&self) -> ChannelType {
        #[cfg(feature = "relay")]
        if self.auth_material.is_some() {
            return ChannelType::RelayInitiator;
        }
        ChannelType::ClientInitiator
    }
}

#[async_trait]
impl<R: Runtime, H: TransportImplHelper> ChannelFactory for ChanBuilder<R, H>
where
    R: tor_rtcompat::TlsProvider<H::Stream> + Send + Sync,
    H: Send + Sync,
{
    #[instrument(skip_all, level = "trace")]
    async fn connect_via_transport(
        &self,
        target: &OwnedChanTarget,
        reporter: BootstrapReporter,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<tor_proto::channel::Channel>> {
        use tor_rtcompat::SleepProviderExt;

        // TODO: make this an option.  And make a better value.
        let delay = if target.chan_method().is_direct() {
            std::time::Duration::new(5, 0)
        } else {
            std::time::Duration::new(10, 0)
        };

        self.runtime
            .timeout(delay, self.connect_no_timeout(target, reporter.0, memquota))
            .await
            .map_err(|_| Error::ChanTimeout {
                peer: target.to_logged(),
            })?
    }
}

#[async_trait]
impl<R: Runtime, H: TransportImplHelper> IncomingChannelFactory for ChanBuilder<R, H>
where
    R: tor_rtcompat::TlsProvider<H::Stream> + Send + Sync,
    H: Send + Sync,
{
    type Stream = H::Stream;

    #[cfg(feature = "relay")]
    async fn accept_from_transport(
        &self,
        peer: Sensitive<std::net::SocketAddr>,
        stream: Self::Stream,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<tor_proto::channel::Channel>> {
        use tor_linkspec::OwnedChanTargetBuilder;
        use tor_proto::relay::MaybeVerifiableRelayResponderChannel;

        // Note that as we accept a connection, we don't expect any specific identities and so we
        // can only build a target from the peer address. This means that the verification process
        // will not match the identities seen (if a relay <-> relay channel) to this target which
        // is fine as we are a responder.
        let target_no_ids = OwnedChanTargetBuilder::default()
            .addrs(vec![peer.into_inner()])
            .build()
            .map_err(|e| internal!("Unable to build chan target from peer sockaddr: {e}"))?;
        // Convert into a PeerAddr but keep it sensitive, this can be a client/bridge.
        let peer_addr: MaybeSensitive<PeerAddr> =
            MaybeSensitive::sensitive(peer.into_inner().into());

        // Helpers: For error mapping.
        let map_ioe = |ioe, action| Error::Io {
            action,
            peer: peer_addr.clone(),
            source: ioe,
        };
        let map_proto = |source, target: &OwnedChanTarget, clock_skew| Error::Proto {
            source,
            peer: target.to_logged(),
            clock_skew,
        };

        let tls = self
            .tls_acceptor
            .as_ref()
            .ok_or(internal!("Accepting connection without TLS acceptor"))?
            .negotiate_unvalidated(stream, "ignored")
            .await
            .map_err(|e| map_ioe(e.into(), "TLS negotiation"))?;
        let auth_material = self
            .auth_material
            .as_ref()
            .ok_or(internal!(
                "Unable to build relay channel without auth material"
            ))?
            .clone();

        let our_cert = tls
            .own_certificate()
            .map_err(|e| map_ioe(e.into(), "TLS Certs"))?
            .ok_or_else(|| Error::Internal(internal!("TLS connection without our certificate")))?
            .into_owned();

        let create_request_handler = self.create_request_handler.as_ref().ok_or_else(|| {
            bad_api_usage!("Can't create a relay channel without a CREATE* request handler")
        })?;

        let builder = tor_proto::RelayChannelBuilder::new();

        let unverified = builder
            .accept(
                Sensitive::new(peer_addr.inner()),
                self.my_addrs.clone(),
                tls,
                self.runtime.clone(),
                auth_material,
                memquota,
                Arc::clone(create_request_handler),
            )
            .handshake(|| self.runtime.wallclock())
            .await
            .map_err(|e| map_proto(e, &target_no_ids, None))?;

        let (chan, reactor) = match unverified {
            MaybeVerifiableRelayResponderChannel::Verifiable(c) => {
                let clock_skew = c.clock_skew();
                let now = self.runtime.wallclock();
                c.verify(&target_no_ids, &our_cert, Some(now))
                    .map_err(|e| map_proto(e, &target_no_ids, Some(clock_skew)))?
                    .finish()
                    .await
                    .map_err(|e| map_proto(e, &target_no_ids, Some(clock_skew)))?
            }
            MaybeVerifiableRelayResponderChannel::NonVerifiable(c) => {
                c.finish().map_err(|e| map_proto(e, &target_no_ids, None))?
            }
        };

        // Launch a task to run the channel reactor.
        self.runtime
            .spawn(async {
                let _ = reactor.run().await;
            })
            .map_err(|e| Error::from_spawn("responder channel reactor", e))?;

        Ok(chan)
    }
}

impl<R: Runtime, H: TransportImplHelper> ChanBuilder<R, H>
where
    R: tor_rtcompat::TlsProvider<H::Stream> + Send + Sync,
    H: Send + Sync,
{
    /// Perform the work of `connect_via_transport`, but without enforcing a timeout.
    ///
    /// Return a [`Channel`](tor_proto::channel::Channel) on success.
    #[instrument(skip_all, level = "trace")]
    async fn connect_no_timeout(
        &self,
        target: &OwnedChanTarget,
        event_sender: Arc<Mutex<ChanMgrEventSender>>,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<tor_proto::channel::Channel>> {
        use tor_rtcompat::tls::CertifiedConn;

        {
            event_sender.lock().expect("Lock poisoned").record_attempt();
        }

        // Before actually doing the connect, we need to validate the channel target based on this
        // build outbound channel type. There are restrictions we need to apply.
        self.validate_target(target)?;

        // 1a. Negotiate the TCP connection or other stream.

        // The returned PeerAddr is the actual address we are connected to.
        let (peer_addr, stream) = self.transport.connect(target).await?;
        // The peer could be a bridge/guard or a relay. We have to shield it right away to avoid
        // leaking the info in the logs but we also want the info for a relay<-> relay.
        let peer_addr = match self.outbound_chan_type() {
            ChannelType::ClientInitiator => MaybeSensitive::sensitive(peer_addr),
            ChannelType::RelayInitiator => MaybeSensitive::not_sensitive(peer_addr),
            _ => return Err(Error::Internal(internal!("Unknown outbound channel type"))),
        };

        let map_ioe = |action: &'static str| {
            let peer = peer_addr.clone();
            move |ioe: io::Error| Error::Io {
                action,
                peer,
                source: ioe.into(),
            }
        };

        {
            // TODO(nickm): At some point, it would be helpful to the
            // bootstrapping logic if we could distinguish which
            // transport just succeeded.
            event_sender
                .lock()
                .expect("Lock poisoned")
                .record_tcp_success();
        }

        // 1b. Negotiate TLS.

        // AETHER-VENDOR-PATCH (proxy chains): see `link_sni_omitted`.
        let hostname = if link_sni_omitted() {
            // An IP literal is never sent as SNI (RFC 6066 s3), so the
            // ClientHello carries no server_name at all.
            NO_SNI_HOSTNAME.to_string()
        } else {
            rand_hostname::random_hostname(&mut rand::rng())
        };

        let tls = self
            .tls_connector
            .negotiate_unvalidated(stream, hostname.as_str())
            .await
            .map_err(map_ioe("TLS negotiation"))?;

        let peer_tls_cert = tls
            .peer_certificate()
            .map_err(map_ioe("TLS certs"))?
            .ok_or_else(|| Error::Internal(internal!("TLS connection with no peer certificate")))?
            // Note: we could skip this "into_owned" if we computed any necessary digest on the
            // certificate earlier.  That would require changing out channel negotiation APIs,
            // though, and might not be worth it.
            .into_owned();

        {
            event_sender
                .lock()
                .expect("Lock poisoned")
                .record_tls_finished();
        }

        // Store this so we can log it in case we don't recognize it.
        let outbound_chan_type = self.outbound_chan_type();
        let chan = match outbound_chan_type {
            ChannelType::ClientInitiator => {
                self.build_client_channel(
                    tls,
                    peer_addr,
                    target,
                    &peer_tls_cert,
                    memquota,
                    event_sender.clone(),
                )
                .await?
            }
            #[cfg(feature = "relay")]
            ChannelType::RelayInitiator => {
                self.build_relay_channel(
                    tls,
                    peer_addr,
                    target,
                    &peer_tls_cert,
                    memquota,
                    event_sender.clone(),
                )
                .await?
            }
            _ => {
                return Err(Error::Internal(internal!(
                    "Unusable channel type for outbound: {outbound_chan_type}",
                )));
            }
        };

        event_sender
            .lock()
            .expect("Lock poisoned")
            .record_handshake_done();

        Ok(chan)
    }

    /// Validate the given `target` as we (a relay) are attempting to connect to another relay.
    #[cfg(feature = "relay")]
    fn validate_target_as_relay<CT>(&self, target: &CT) -> crate::Result<()>
    where
        CT: ChanTarget,
    {
        use tor_linkspec::HasRelayIds;

        // Make sure we don't attempt to use a PT method for this relay channel.
        if !target.chan_method().is_direct() {
            // Return an internal error because the circuit reactor asking us to use a PT is a code
            // flow issue, not a protocol violation.
            return Err(Error::UnusableTarget(tor_error::bad_api_usage!(
                "Relays don't support outbound PT channels"
            )));
        }

        // Make sure that all addresses are allowed for an EXTEND. For instance, connecting to a
        // local/private network for security reasons as a it could lead to network
        // scanning by measuring latency between successful connect() and failures.
        //
        // If no addresses, it returns true and thus no error.
        if !target.all_addrs_allowed_for_outgoing_channels() {
            return Err(Error::Proto {
                source: tor_proto::Error::ChanProto(
                    "Target address is not allowed for outgoing channels".into(),
                ),
                peer: target.to_owned().into(),
                clock_skew: None,
            });
        }

        // Connecting to a relay as a relay without key material will fail. This should never
        // happen hence the internal error.
        let Some(auth_material) = &self.auth_material else {
            return Err(Error::Internal(tor_error::bad_api_usage!(
                "Relay initiating a channel without key auth material"
            )));
        };

        // Any of our identities match the given target, we are connecting to ourselves, refuse.
        if auth_material.has_any_relay_id_from(target) {
            Err(Error::Proto {
                source: tor_proto::Error::ChanProto("Refusing to build channel to ourself".into()),
                peer: target.to_owned().into(),
                clock_skew: None,
            })
        } else {
            Ok(())
        }
    }

    /// Validate the given target as in if it is fine to connect to it.
    ///
    ///There are several rules to follow based on the channel outbound type.
    fn validate_target<CT>(&self, target: &CT) -> crate::Result<()>
    where
        CT: ChanTarget,
    {
        // Make sure that each address has a valid port.
        if !target.has_all_nonzero_port() {
            return Err(Error::Proto {
                source: tor_proto::Error::ChanProto("Target address port is invalid".into()),
                peer: target.to_owned().into(),
                clock_skew: None,
            });
        }

        // Make sure no target address is ourself including the port. A relay is allowed to connect
        // to its IP address but on a different port. We also want to avoid a client bridge to
        // connect back to itself.
        #[cfg(feature = "relay")]
        for addr in target.addrs() {
            if self.my_addrs.contains(&addr) {
                return Err(Error::Proto {
                    source: tor_proto::Error::ChanProto("Target address is ours".into()),
                    peer: target.to_owned().into(),
                    clock_skew: None,
                });
            }
        }

        let chan_type = self.outbound_chan_type();
        match chan_type {
            // This is a client connecting to a relay.
            ChannelType::ClientInitiator => Ok(()),
            // This is a relay connecting to a relay.
            #[cfg(feature = "relay")]
            ChannelType::RelayInitiator => self.validate_target_as_relay(target),
            // ChannelType is non_exhaustive but also we only cover outbound channels.
            _ => Err(Error::UnusableTarget(tor_error::bad_api_usage!(
                "Channel type can't be used as a target: {chan_type}"
            ))),
        }
    }

    /// Build a client channel (always initiator).
    ///
    /// This spawns the Reactor and return the [`tor_proto::channel::Channel`].
    async fn build_client_channel<T>(
        &self,
        tls: T,
        peer_addr: MaybeSensitive<PeerAddr>,
        target: &OwnedChanTarget,
        peer_tls_cert: &[u8],
        memquota: ChannelAccount,
        event_sender: Arc<Mutex<ChanMgrEventSender>>,
    ) -> crate::Result<Arc<tor_proto::channel::Channel>>
    where
        T: AsyncRead + AsyncWrite + CertifiedConn + StreamOps + Send + Unpin + 'static,
    {
        // Helper to map protocol level error.
        //
        // We are logging the `target` here as these protocol error happens during the handshake
        // and we need to log the identities that are being tried but it will honor safe logging
        // for the relay <-> relay case which is not ideal but a tradeoff in complexity.
        let map_proto = |source, target: &OwnedChanTarget, clock_skew| Error::Proto {
            source,
            peer: target.to_logged(),
            clock_skew,
        };

        let now = self.runtime.wallclock();

        // Get the client specific channel builder.
        let mut builder = tor_proto::ClientChannelBuilder::new();
        builder.set_declared_method(target.chan_method());

        let unverified = builder
            .launch(
                tls,
                self.runtime.clone(), /* TODO provide ZST SleepProvider instead */
                memquota,
            )
            .connect(|| self.runtime.wallclock())
            .await
            .map_err(|e| Error::from_proto_no_skew(e, target))?;

        let clock_skew = unverified.clock_skew();
        let (chan, reactor) = unverified
            .verify(target, peer_tls_cert, Some(now))
            .map_err(|source| match &source {
                tor_proto::Error::HandshakeCertsExpired { .. } => {
                    event_sender
                        .lock()
                        .expect("Lock poisoned")
                        .record_handshake_done_with_skewed_clock();
                    map_proto(source, target, Some(clock_skew))
                }
                _ => Error::from_proto_no_skew(source, target),
            })?
            .finish(peer_addr)
            .await
            .map_err(|e| map_proto(e, target, Some(clock_skew)))?;

        // Launch a task to run the channel reactor.
        self.runtime
            .spawn(async {
                let _ = reactor.run().await;
            })
            .map_err(|e| Error::from_spawn("client channel reactor", e))?;
        Ok(chan)
    }

    /// Build a relay initiator channel.
    ///
    /// This spawns the Reactor and return the [`tor_proto::channel::Channel`].
    #[cfg(feature = "relay")]
    async fn build_relay_channel<T>(
        &self,
        tls: T,
        peer_addr: MaybeSensitive<PeerAddr>,
        target: &OwnedChanTarget,
        peer_tls_cert: &[u8],
        memquota: ChannelAccount,
        event_sender: Arc<Mutex<ChanMgrEventSender>>,
    ) -> crate::Result<Arc<tor_proto::channel::Channel>>
    where
        T: AsyncRead + AsyncWrite + CertifiedConn + StreamOps + Send + Unpin + 'static,
    {
        let builder = tor_proto::RelayChannelBuilder::new();
        let auth_material = self
            .auth_material
            .as_ref()
            .ok_or(internal!(
                "Unable to build relay channel without auth material"
            ))?
            .clone();

        let create_request_handler = self.create_request_handler.as_ref().ok_or_else(|| {
            bad_api_usage!("Can't create a relay channel without a CREATE* request handler")
        })?;

        let unverified = builder
            .launch(
                tls,
                self.runtime.clone(), /* TODO provide ZST SleepProvider instead */
                auth_material,
                self.my_addrs.clone(),
                target,
                memquota,
                Arc::clone(create_request_handler),
            )
            .connect(|| self.runtime.wallclock())
            .await
            .map_err(|e| Error::from_proto_no_skew(e, target))?;

        let now = self.runtime.wallclock();
        let clock_skew = unverified.clock_skew();
        let (chan, reactor) = unverified
            .verify(target, peer_tls_cert, Some(now))
            .map_err(|source| match &source {
                tor_proto::Error::HandshakeCertsExpired { .. } => {
                    event_sender
                        .lock()
                        .expect("Lock poisoned")
                        .record_handshake_done_with_skewed_clock();
                    Error::Proto {
                        source,
                        peer: target.to_logged(),
                        clock_skew: Some(clock_skew),
                    }
                }
                _ => Error::from_proto_no_skew(source, target),
            })?
            // Peer address is not sensitive as this is a relay<->relay connection.
            .finish(peer_addr.inner())
            .await
            .map_err(|source| Error::Proto {
                source,
                peer: target.to_logged(),
                clock_skew: Some(clock_skew),
            })?;

        // Launch a task to run the channel reactor.
        self.runtime
            .spawn(async {
                let _ = reactor.run().await;
            })
            .map_err(|e| Error::from_spawn("relay channel reactor", e))?;

        Ok(chan)
    }
}

impl crate::mgr::AbstractChannel for tor_proto::channel::Channel {
    fn is_canonical(&self) -> bool {
        self.is_canonical()
    }
    fn is_canonical_to_peer(&self) -> bool {
        self.is_canonical_to_peer()
    }
    fn is_usable(&self) -> bool {
        !self.is_closing()
    }
    fn duration_unused(&self) -> Option<Duration> {
        self.duration_unused()
    }
    fn reparameterize(
        &self,
        updates: Arc<ChannelPaddingInstructionsUpdates>,
    ) -> tor_proto::Result<()> {
        tor_proto::channel::Channel::reparameterize(self, updates)
    }
    fn reparameterize_kist(&self, kist_params: KistParams) -> tor_proto::Result<()> {
        tor_proto::channel::Channel::reparameterize_kist(self, kist_params)
    }
    fn engage_padding_activities(&self) {
        tor_proto::channel::Channel::engage_padding_activities(self);
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
    use crate::{
        Result,
        mgr::{AbstractChannel, AbstractChannelFactory},
    };
    use futures::StreamExt as _;
    use std::net::SocketAddr;
    use std::time::{Duration, SystemTime};
    use tor_linkspec::{ChannelMethod, HasRelayIds, RelayIdType};
    use tor_llcrypto::pk::ed25519::Ed25519Identity;
    use tor_llcrypto::pk::rsa::RsaIdentity;
    use tor_proto::channel::Channel;
    use tor_proto::memquota::{ChannelAccount, SpecificAccount as _};
    use tor_rtcompat::{NetStreamListener, test_with_one_runtime};
    use tor_rtmock::{io::LocalStream, net::MockNetwork};

    #[allow(deprecated)] // TODO #1885
    use tor_rtmock::MockSleepRuntime;

    // Make sure that the builder can build a real channel.  To test
    // this out, we set up a listener that pretends to have the right
    // IP, fake the current time, and use a canned response from
    // [`testing::msgs`] crate.
    #[test]
    fn build_ok() -> Result<()> {
        use crate::testing::msgs;
        let orport: SocketAddr = msgs::ADDR.parse().unwrap();
        let ed: Ed25519Identity = msgs::ED_ID.into();
        let rsa: RsaIdentity = msgs::RSA_ID.into();
        let client_addr = "192.0.2.17".parse().unwrap();
        let tls_cert = msgs::X509_CERT.into();
        let target = OwnedChanTarget::builder()
            .addrs(vec![orport])
            .method(ChannelMethod::Direct(vec![orport]))
            .ed_identity(ed)
            .rsa_identity(rsa)
            .build()
            .unwrap();
        let now = SystemTime::UNIX_EPOCH + Duration::new(msgs::NOW, 0);

        test_with_one_runtime!(|rt| async move {
            // Stub out the internet so that this connection can work.
            let network = MockNetwork::new();

            // Set up a client runtime with a given IP
            let client_rt = network
                .builder()
                .add_address(client_addr)
                .runtime(rt.clone());
            // Mock the current time too
            #[allow(deprecated)] // TODO #1885
            let client_rt = MockSleepRuntime::new(client_rt);

            // Set up a relay runtime with a different IP
            let relay_rt = network
                .builder()
                .add_address(orport.ip())
                .runtime(rt.clone());

            // open a fake TLS listener and be ready to handle a request.
            let lis = relay_rt.mock_net().listen_tls(&orport, tls_cert).unwrap();

            // Tell the client to believe in a different timestamp.
            client_rt.jump_to(now);

            // Create the channel builder that we want to test.
            let transport = crate::transport::DefaultTransport::new(client_rt.clone(), None);
            let builder = ChanBuilder::new_client(client_rt, transport);

            let (r1, r2): (Result<Arc<Channel>>, Result<LocalStream>) = futures::join!(
                async {
                    // client-side: build a channel!
                    builder
                        .build_channel(
                            &target,
                            BootstrapReporter::fake(),
                            ChannelAccount::new_noop(),
                        )
                        .await
                },
                async {
                    // relay-side: accept the channel
                    // (and pretend to know what we're doing).
                    let (mut con, addr) = lis
                        .incoming()
                        .next()
                        .await
                        .expect("Closed?")
                        .expect("accept failed");
                    assert_eq!(client_addr, addr.ip());
                    crate::testing::answer_channel_req(&mut con)
                        .await
                        .expect("answer failed");
                    Ok(con)
                }
            );

            let chan = r1.unwrap();
            assert_eq!(chan.identity(RelayIdType::Ed25519), Some((&ed).into()));
            assert!(chan.is_usable());
            // In theory, time could pass here, so we can't just use
            // "assert_eq!(dur_unused, dur_unused2)".
            let dur_unused = Channel::duration_unused(&chan);
            let dur_unused_2 = AbstractChannel::duration_unused(chan.as_ref());
            let dur_unused_3 = Channel::duration_unused(&chan);
            assert!(dur_unused.unwrap() <= dur_unused_2.unwrap());
            assert!(dur_unused_2.unwrap() <= dur_unused_3.unwrap());

            r2.unwrap();
            Ok(())
        })
    }

    // TODO: Write tests for timeout logic, once there is smarter logic.
}

/// AETHER-VENDOR-PATCH: placeholder "hostname" handed to the TLS connector when
/// no SNI must be sent. rustls never puts an IP address into server_name.
const NO_SNI_HOSTNAME: &str = "127.0.0.1";

/// AETHER-VENDOR-PATCH: true when the relay TLS handshake must carry no SNI.
///
/// Upstream always sends a made-up `www.<random>.com` server name. That is
/// harmless on a direct TCP connection, but fatal when the channel is carried
/// by a V2Ray/Xray proxy whose SERVER has destination sniffing on (the default
/// in 3x-ui, Marzban and most panels): the server reads the SNI out of the
/// ClientHello, replaces the relay's IP with that name, fails to resolve a
/// name that does not exist and drops the connection. arti then reports every
/// guard as "tls handshake eof" and stays at 93 %. Without an SNI the sniffer
/// finds no domain and the connection goes to the relay's real address.
///
/// Enabled with `AETHER_TOR_LINK_SNI=off` (the Aether app sets it whenever Tor
/// dials out through a custom-config proxy). Read once per process.
fn link_sni_omitted() -> bool {
    static OMIT: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *OMIT.get_or_init(|| {
        matches!(
            std::env::var("AETHER_TOR_LINK_SNI")
                .unwrap_or_default()
                .trim()
                .to_ascii_lowercase()
                .as_str(),
            "off" | "0" | "no" | "none" | "false"
        )
    })
}
