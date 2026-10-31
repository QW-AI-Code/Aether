//! Traits and code to define different mechanisms for building Channels to
//! different kinds of targets.

use std::sync::{Arc, Mutex};

use crate::event::ChanMgrEventSender;
use async_trait::async_trait;
use tor_error::{HasKind, HasRetryTime, internal};
use tor_linkspec::{HasChanMethod, OwnedChanTarget, PtTransportName};
use tor_proto::channel::Channel;
use tor_proto::memquota::ChannelAccount;
use tracing::{debug, instrument};

#[cfg(feature = "relay")]
use safelog::Sensitive;

/// An opaque type that lets a `ChannelFactory` update the `ChanMgr` about bootstrap progress.
///
/// A future release of this crate might make this type less opaque.
// FIXME(eta): Do that.
#[derive(Clone)]
pub struct BootstrapReporter(pub(crate) Arc<Mutex<ChanMgrEventSender>>);

impl BootstrapReporter {
    #[cfg(test)]
    /// Create a useless version of this type to satisfy some test.
    pub(crate) fn fake() -> Self {
        let (snd, _rcv) = crate::event::channel();
        Self(Arc::new(Mutex::new(snd)))
    }
}

/// An object that knows how to build `Channels` to `ChanTarget`s.
///
/// This trait must be object-safe.
///
/// Every [`ChanMgr`](crate::ChanMgr) has a `ChannelFactory` that it uses to
/// construct all of its channels.
///
/// A `ChannelFactory` can be implemented in terms of a
/// [`TransportImplHelper`](crate::transport::TransportImplHelper), by wrapping it in a
/// `ChanBuilder`.
///
// FIXME(eta): Rectify the below situation.
/// (In fact, as of the time of writing, this is the *only* way to implement this trait
/// outside of this crate while keeping bootstrap status reporting, since `BootstrapReporter`
/// is an opaque type.)
#[async_trait]
pub trait ChannelFactory: Send + Sync {
    /// Open an authenticated channel to `target`.
    ///
    /// This method does does not necessarily handle retries or timeouts,
    /// although some of its implementations may.
    ///
    /// This method does not necessarily handle every kind of transport. If the
    /// caller provides a target with an unsupported
    /// [`TransportId`](tor_linkspec::TransportId), this method should return
    /// [`Error::NoSuchTransport`](crate::Error::NoSuchTransport).
    async fn connect_via_transport(
        &self,
        target: &OwnedChanTarget,
        reporter: BootstrapReporter,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<Channel>>;
}

/// Similar to [`ChannelFactory`], but for building channels from incoming streams.
// This is a separate trait since for some `ChannelFactory`s like the one returned from
// `tor_ptmgr::PtMgr::factory_for_transport`, it doesn't make sense to deal with incoming streams
// (all PT connections are outgoing).
#[async_trait]
pub trait IncomingChannelFactory: Send + Sync {
    /// The type of byte stream that's required to build channels for incoming connections.
    type Stream: Send + Sync + 'static;

    /// Open a channel from `peer` with the given `stream`. The channel may or may not be
    /// authenticated.
    #[cfg(feature = "relay")]
    async fn accept_from_transport(
        &self,
        peer: Sensitive<std::net::SocketAddr>,
        stream: Self::Stream,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<Channel>>;
}

#[async_trait]
impl<CF> crate::mgr::AbstractChannelFactory for CF
where
    CF: ChannelFactory + IncomingChannelFactory + Sync,
{
    type Channel = tor_proto::channel::Channel;
    type BuildSpec = OwnedChanTarget;
    type Stream = CF::Stream;

    #[instrument(skip_all, level = "trace")]
    async fn build_channel(
        &self,
        target: &Self::BuildSpec,
        reporter: BootstrapReporter,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<Self::Channel>> {
        debug!("Attempting to open a new channel to {target}");
        self.connect_via_transport(target, reporter, memquota).await
    }

    #[cfg(feature = "relay")]
    #[instrument(skip_all, level = "trace")]
    async fn build_channel_using_incoming(
        &self,
        peer: Sensitive<std::net::SocketAddr>,
        stream: Self::Stream,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<tor_proto::channel::Channel>> {
        debug!("Attempting to open a new channel from {peer}");
        self.accept_from_transport(peer, stream, memquota).await
    }
}

/// The error type returned by a pluggable transport manager.
pub trait AbstractPtError:
    std::error::Error + HasKind + HasRetryTime + Send + Sync + std::fmt::Debug
{
}

/// A pluggable transport manager.
///
/// We can't directly reference the `PtMgr` type from `tor-ptmgr`, because of dependency resolution
/// constraints, so this defines the interface for what one should look like.
#[async_trait]
pub trait AbstractPtMgr: Send + Sync {
    /// Get a `ChannelFactory` for the provided `PtTransportName`.
    async fn factory_for_transport(
        &self,
        transport: &PtTransportName,
    ) -> Result<Option<Arc<dyn ChannelFactory + Send + Sync>>, Arc<dyn AbstractPtError>>;
}

#[async_trait]
impl<P> AbstractPtMgr for Option<P>
where
    P: AbstractPtMgr,
{
    async fn factory_for_transport(
        &self,
        transport: &PtTransportName,
    ) -> Result<Option<Arc<dyn ChannelFactory + Send + Sync>>, Arc<dyn AbstractPtError>> {
        match self {
            Some(mgr) => mgr.factory_for_transport(transport).await,
            None => Ok(None),
        }
    }
}

/// A ChannelFactory built from an optional PtMgr to use for pluggable transports, and a
/// ChannelFactory to use for everything else.
pub(crate) struct CompoundFactory<CF> {
    #[cfg(feature = "pt-client")]
    /// The PtMgr to use for pluggable transports
    ptmgr: Option<Arc<dyn AbstractPtMgr + 'static>>,
    /// The factory to use for everything else
    default_factory: Arc<CF>,
}

impl<CF> Clone for CompoundFactory<CF> {
    fn clone(&self) -> Self {
        Self {
            #[cfg(feature = "pt-client")]
            ptmgr: self.ptmgr.as_ref().map(Arc::clone),
            default_factory: Arc::clone(&self.default_factory),
        }
    }
}

#[async_trait]
impl<CF: ChannelFactory> ChannelFactory for CompoundFactory<CF> {
    #[instrument(skip_all, level = "trace")]
    async fn connect_via_transport(
        &self,
        target: &OwnedChanTarget,
        reporter: BootstrapReporter,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<Channel>> {
        use tor_linkspec::ChannelMethod::*;
        let factory = match target.chan_method() {
            Direct(_) => self.default_factory.clone(),
            #[cfg(feature = "pt-client")]
            Pluggable(a) => match self.ptmgr.as_ref() {
                Some(mgr) => mgr
                    .factory_for_transport(a.transport())
                    .await
                    .map_err(crate::Error::Pt)?
                    .ok_or_else(|| crate::Error::NoSuchTransport(a.transport().clone().into()))?,
                None => return Err(crate::Error::NoSuchTransport(a.transport().clone().into())),
            },
            #[allow(unreachable_patterns)]
            _ => {
                return Err(crate::Error::Internal(internal!(
                    "No support for channel method"
                )));
            }
        };

        factory
            .connect_via_transport(target, reporter, memquota)
            .await
    }
}

#[async_trait]
impl<CF: IncomingChannelFactory> IncomingChannelFactory for CompoundFactory<CF> {
    type Stream = CF::Stream;

    #[cfg(feature = "relay")]
    async fn accept_from_transport(
        &self,
        peer: Sensitive<std::net::SocketAddr>,
        stream: Self::Stream,
        memquota: ChannelAccount,
    ) -> crate::Result<Arc<Channel>> {
        self.default_factory
            .accept_from_transport(peer, stream, memquota)
            .await
    }
}

impl<CF: ChannelFactory + 'static> CompoundFactory<CF> {
    /// Create a new `Factory` that will try to use `ptmgr` to handle pluggable
    /// transports requests, and `default_factory` to handle everything else.
    pub(crate) fn new(
        default_factory: Arc<CF>,
        #[cfg(feature = "pt-client")] ptmgr: Option<Arc<dyn AbstractPtMgr + 'static>>,
    ) -> Self {
        Self {
            default_factory,
            #[cfg(feature = "pt-client")]
            ptmgr,
        }
    }

    #[cfg(feature = "pt-client")]
    /// Replace the PtMgr in this object.
    pub(crate) fn replace_ptmgr(&mut self, ptmgr: Arc<dyn AbstractPtMgr + 'static>) {
        self.ptmgr = Some(ptmgr);
    }

    /// Return a reference to the default channel factory.
    #[cfg(feature = "relay")]
    pub(crate) fn default_factory(&self) -> &CF {
        &self.default_factory
    }

    /// Replace the default channel factory.
    #[cfg(feature = "relay")]
    pub(crate) fn replace_default_factory(&mut self, factory: Arc<CF>) {
        self.default_factory = factory;
    }
}
