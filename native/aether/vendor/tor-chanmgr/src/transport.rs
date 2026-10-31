//! Code to define the notion of a "Transport" and implement a default transport.

use async_trait::async_trait;
use futures::{AsyncRead, AsyncWrite};
use tor_linkspec::OwnedChanTarget;

pub(crate) mod default;
pub mod proxied;

pub(crate) use default::DefaultTransport;

#[cfg(feature = "pt-client")]
#[cfg_attr(docsrs, doc(cfg(feature = "experimental-api")))]
pub use proxied::ExternalProxyPlugin;
pub use proxied::ProxyError;
use tor_proto::peer::PeerAddr;
use tor_rtcompat::StreamOps;

/// A convenient API for defining transports for use in Tor and elsewhere.
///
/// This type's role is to let the implementor just define a replacement way to
/// pass bytes around, and return something that we can use in place of a
/// TcpStream.
///
/// This is the trait you should probably implement if you want to define a new
/// [`ChannelFactory`](crate::factory::ChannelFactory) that performs Tor over
/// TLS over some stream-like type, and you only want to define the stream-like
/// type.
///
/// To convert a [`TransportImplHelper`] into a
/// [`ChannelFactory`](crate::factory::ChannelFactory), wrap it in a
/// `ChanBuilder`.
//
// TODO: Maybe move this to a separate crate so that tor-ptmgr can be
// used without having to depend on chanmgr.
#[async_trait]
pub trait TransportImplHelper {
    /// The type of the resulting stream.
    type Stream: AsyncRead + AsyncWrite + StreamOps + Send + Sync + 'static;

    /// Implements the transport: make a TCP connection (possibly tunneled over
    /// whatever protocol) if possible.
    ///
    /// This method does does not necessarily handle retries or timeouts,
    /// although some of its implementations may.
    ///
    /// This method does not necessarily handle every kind of transport. If the
    /// caller provides a target with the wrong
    /// [`TransportId`](tor_linkspec::TransportId), this method should return
    /// [`Error::NoSuchTransport`](crate::Error::NoSuchTransport).
    async fn connect(&self, target: &OwnedChanTarget) -> crate::Result<(PeerAddr, Self::Stream)>;
}
