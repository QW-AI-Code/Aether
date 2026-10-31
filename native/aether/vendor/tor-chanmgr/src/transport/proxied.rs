//! Connect to relays via a proxy.
//!
//! This code is here for two reasons:
//!   1. To connect via external pluggable transports (for which we use SOCKS to
//!      build our connections).
//!   2. To support users who are behind a firewall that requires them to use a
//!      SOCKS proxy to connect.
//!
//! Supports SOCKS4/4a/5 and HTTP CONNECT proxies.
//
// TODO: Maybe refactor this so that tor-ptmgr can exist in a more freestanding
// way, with fewer arti dependencies.
#![allow(dead_code)]

use std::{
    net::{IpAddr, SocketAddr},
    sync::Arc,
};

use base64ct::{Base64, Encoding};
use futures::io::{AsyncBufReadExt, BufReader};
use futures::{AsyncReadExt, AsyncWriteExt};
use httparse;
use safelog::Sensitive;
use tor_linkspec::PtTargetAddr;
use tor_rtcompat::NetStreamProvider;
use tor_socksproto::{
    Handshake as _, SocksAddr, SocksAuth, SocksClientHandshake, SocksCmd, SocksRequest,
    SocksStatus, SocksVersion,
};
use tracing::trace;

#[cfg(feature = "pt-client")]
use super::TransportImplHelper;
#[cfg(feature = "pt-client")]
use async_trait::async_trait;
#[cfg(feature = "pt-client")]
use safelog::sensitive as sv;
#[cfg(feature = "pt-client")]
use tor_error::bad_api_usage;
#[cfg(feature = "pt-client")]
use tor_linkspec::{ChannelMethod, HasChanMethod, OwnedChanTarget};
#[cfg(feature = "pt-client")]
use tor_proto::peer::PeerAddr;

/// Information about what proxy protocol to use, and how to use it.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum Protocol {
    /// Connect via SOCKS 4, SOCKS 4a, or SOCKS 5.
    Socks(SocksVersion, SocksAuth),
    /// Connect via HTTP CONNECT proxy (RFC 7231).
    HttpConnect {
        /// Optional Basic auth credentials (username, password) for Proxy-Authorization header.
        auth: Option<(Sensitive<String>, Sensitive<String>)>,
    },
}

/// An address to use when told to connect to "no address."
const NO_ADDR: IpAddr = IpAddr::V4(std::net::Ipv4Addr::new(0, 0, 0, 1));
/// Maximum number of bytes allowed in HTTP response headers from proxy.
const MAX_HTTP_HEADER_BYTES: usize = 16 * 1024;

/// Open a connection to `target` via the proxy at `proxy`, using the protocol
/// at `protocol`.
///
/// # Limitations
///
/// We will give an error if the proxy sends us any data on the connection along
/// with its final handshake: due to our implementation, any such data will be
/// discarded, and so we give an error rather than fail silently.
///
/// This limitation doesn't matter when the underlying protocol is Tor, or
/// anything else where the initiator is expected to speak before the responder
/// says anything.  To lift it, we would have to make this function's return
/// type become something buffered.
//
// TODO: Perhaps we should refactor this someday so it can be a general-purpose
// proxy function, not only for Arti.
pub(crate) async fn connect_via_proxy<R: NetStreamProvider + Send + Sync>(
    runtime: &R,
    proxy: &SocketAddr,
    protocol: &Protocol,
    target: &PtTargetAddr,
) -> Result<R::Stream, ProxyError> {
    trace!(
        "Launching a proxied connection to {} via proxy at {} using {:?}",
        target, proxy, protocol
    );
    // We don't (yet) use any custom options on the socket.
    let connect_options = Default::default();
    let stream = runtime
        .connect(proxy, &connect_options)
        .await
        .map_err(|e| ProxyError::ProxyConnect(Arc::new(e)))?;

    match protocol {
        Protocol::Socks(version, auth) => {
            do_socks_handshake::<R>(stream, version, auth, target).await
        }
        Protocol::HttpConnect { auth } => {
            do_http_connect_handshake::<R>(stream, auth, target).await
        }
    }
}

/// Perform SOCKS proxy handshake.
async fn do_socks_handshake<R: NetStreamProvider + Send + Sync>(
    mut stream: R::Stream,
    version: &SocksVersion,
    auth: &SocksAuth,
    target: &PtTargetAddr,
) -> Result<R::Stream, ProxyError> {
    let (target_addr, target_port): (SocksAddr, u16) = match target {
        PtTargetAddr::IpPort(a) => (SocksAddr::Ip(a.ip()), a.port()),
        #[cfg(feature = "pt-client")]
        PtTargetAddr::HostPort(host, port) => (
            SocksAddr::Hostname(
                host.clone()
                    .try_into()
                    .map_err(ProxyError::InvalidSocksAddr)?,
            ),
            *port,
        ),
        #[cfg(feature = "pt-client")]
        PtTargetAddr::None => (SocksAddr::Ip(NO_ADDR), 1),
        _ => return Err(ProxyError::UnrecognizedAddr),
    };

    let request = SocksRequest::new(
        *version,
        SocksCmd::CONNECT,
        target_addr,
        target_port,
        auth.clone(),
    )
    .map_err(ProxyError::InvalidSocksRequest)?;
    let mut handshake = SocksClientHandshake::new(request);

    let mut buf = tor_socksproto::Buffer::new();
    let reply = loop {
        use tor_socksproto::NextStep as NS;
        match handshake.step(&mut buf).map_err(ProxyError::SocksProto)? {
            NS::Send(send) => {
                stream.write_all(&send).await?;
                stream.flush().await?;
            }
            NS::Finished(fin) => {
                break fin
                    .into_output_forbid_pipelining()
                    .map_err(ProxyError::SocksProto)?;
            }
            NS::Recv(mut recv) => {
                let n = stream.read(recv.buf()).await?;
                recv.note_received(n).map_err(ProxyError::SocksProto)?;
            }
        }
    };

    let status = reply.status();
    trace!("SOCKS handshake succeeded, status {:?}", status);

    if status != SocksStatus::SUCCEEDED {
        return Err(ProxyError::SocksError(status));
    }

    Ok(stream)
}

/// Format target address for HTTP CONNECT request line and Host header.
fn format_connect_target(target: &PtTargetAddr) -> Result<String, ProxyError> {
    match target {
        PtTargetAddr::IpPort(a) => {
            let host = match a.ip() {
                IpAddr::V4(ip) => ip.to_string(),
                IpAddr::V6(ip) => format!("[{}]", ip),
            };
            Ok(format!("{}:{}", host, a.port()))
        }
        #[cfg(feature = "pt-client")]
        PtTargetAddr::HostPort(host, port) => Ok(format!("{}:{}", host, port)),
        #[cfg(feature = "pt-client")]
        PtTargetAddr::None => Err(ProxyError::UnrecognizedAddr),
        _ => Err(ProxyError::UnrecognizedAddr),
    }
}

/// Build HTTP CONNECT request string with optional Basic auth.
fn build_http_connect_request(
    target_str: &str,
    auth: &Option<(Sensitive<String>, Sensitive<String>)>,
) -> String {
    // Build CONNECT request: CONNECT host:port HTTP/1.1\r\nHost: host:port\r\n[...]\r\n
    let mut request = format!(
        "CONNECT {} HTTP/1.1\r\nHost: {}\r\n",
        target_str, target_str
    );

    if let Some((user, pass)) = auth {
        // Proxy-Authorization: Basic base64(username:password) per RFC 7617
        let credentials = format!("{}:{}", user.as_ref(), pass.as_ref());
        let encoded = Base64::encode_string(credentials.as_bytes());
        request.push_str(&format!("Proxy-Authorization: Basic {}\r\n", encoded));
    }

    request.push_str("\r\n");
    request
}

/// Parse HTTP CONNECT response and extract status code.
///
/// Uses httparse for spec-compliant parsing. Rejects:
/// - Responses larger than 16KB (prevents header bomb attacks)
/// - Pipelined data after headers (connection is dedicated to tunnel)
fn parse_http_connect_response(response_bytes: &[u8]) -> Result<u16, ProxyError> {
    if response_bytes.len() > MAX_HTTP_HEADER_BYTES {
        return Err(ProxyError::HttpConnectMalformed);
    }

    let mut headers = [httparse::EMPTY_HEADER; 64];
    let mut resp = httparse::Response::new(&mut headers);

    match resp.parse(response_bytes) {
        Ok(httparse::Status::Complete(header_end)) => {
            let status = resp.code.ok_or(ProxyError::HttpConnectMalformed)?;

            if !(200..300).contains(&status) {
                return Err(ProxyError::HttpConnectError(status));
            }

            // Reject any pipelined data after headers
            if header_end < response_bytes.len() {
                return Err(ProxyError::UnexpectedData);
            }

            trace!("HTTP CONNECT successful, status {}", status);
            Ok(status)
        }
        Ok(httparse::Status::Partial) => Err(ProxyError::HttpConnectMalformed),
        Err(_) => Err(ProxyError::HttpConnectMalformed),
    }
}

/// Send HTTP CONNECT request to proxy.
async fn send_http_connect_request<R: NetStreamProvider + Send + Sync>(
    stream: &mut R::Stream,
    auth: &Option<(Sensitive<String>, Sensitive<String>)>,
    target_str: &str,
) -> Result<(), ProxyError> {
    let request = build_http_connect_request(target_str, auth);
    trace!("Sending HTTP CONNECT request for {}", target_str);
    stream.write_all(request.as_bytes()).await?;
    stream.flush().await?;
    Ok(())
}

/// Perform HTTP CONNECT proxy handshake (RFC 7231, RFC 7617 for Basic auth).
async fn do_http_connect_handshake<R: NetStreamProvider + Send + Sync>(
    mut stream: R::Stream,
    auth: &Option<(Sensitive<String>, Sensitive<String>)>,
    target: &PtTargetAddr,
) -> Result<R::Stream, ProxyError> {
    let target_str = format_connect_target(target)?;
    send_http_connect_request::<R>(&mut stream, auth, &target_str).await?;

    // Read response until we see the double CRLF that terminates headers
    let mut response_buffer = Vec::new();
    let mut reader = BufReader::new(stream);
    let mut line = String::new();

    loop {
        line.clear();
        let n = reader.read_line(&mut line).await?;
        if n == 0 {
            return Err(ProxyError::HttpConnectMalformed);
        }

        response_buffer.extend_from_slice(line.as_bytes());
        if response_buffer.len() > MAX_HTTP_HEADER_BYTES {
            return Err(ProxyError::HttpConnectMalformed);
        }

        // Check for blank line (end of headers)
        if line == "\r\n" || line == "\n" {
            break;
        }
    }

    // Parse and validate response
    let _status_code = parse_http_connect_response(&response_buffer)?;

    // Return the underlying stream (BufReader is dropped, stream continues)
    Ok(reader.into_inner())
}

/// An error that occurs while negotiating a connection with a proxy.
#[derive(Clone, Debug, thiserror::Error)]
#[non_exhaustive]
pub enum ProxyError {
    /// We had an IO error while trying to open a connection to the proxy.
    #[error("Problem while connecting to proxy")]
    ProxyConnect(#[source] Arc<std::io::Error>),

    /// We had an IO error while talking to the proxy.
    #[error("Problem while communicating with proxy")]
    ProxyIo(#[source] Arc<std::io::Error>),

    /// We tried to use an address which socks doesn't support.
    #[error("SOCKS proxy does not support target address")]
    InvalidSocksAddr(#[source] tor_socksproto::Error),

    /// We tried to use an address type which _we_ don't recognize.
    #[error("Got an address type we don't recognize")]
    UnrecognizedAddr,

    /// Our SOCKS implementation told us that this request cannot be encoded.
    #[error("Tried to make an invalid SOCKS request")]
    InvalidSocksRequest(#[source] tor_socksproto::Error),

    /// The peer refused our request, or spoke SOCKS incorrectly.
    #[error("Protocol error while communicating with SOCKS proxy")]
    SocksProto(#[source] tor_socksproto::Error),

    /// We encountered an internal programming error.
    #[error("Internal error")]
    Bug(#[from] tor_error::Bug),

    /// We got extra data immediately after our handshake, before we actually
    /// sent anything.
    ///
    /// This is not a bug in the calling code or in the peer protocol: it just
    /// means that the remote peer sent us data before we actually sent it any
    /// data. Unfortunately, there's a limitation in our code that makes it
    /// discard any such data, and therefore we have to give this error to
    /// prevent bugs.
    ///
    /// We could someday remove this limitation.
    #[error("Received unexpected early data from peer")]
    UnexpectedData,

    /// The proxy told us that our attempt failed.
    #[error("SOCKS proxy reported an error: {0}")]
    SocksError(SocksStatus),

    /// HTTP CONNECT proxy returned a non-2xx status code.
    #[error("HTTP CONNECT proxy returned status: {0}")]
    HttpConnectError(u16),

    /// HTTP CONNECT proxy returned a malformed response.
    #[error("HTTP CONNECT proxy returned invalid response")]
    HttpConnectMalformed,
}

impl From<std::io::Error> for ProxyError {
    fn from(e: std::io::Error) -> Self {
        ProxyError::ProxyIo(Arc::new(e))
    }
}

impl From<ProxyError> for std::io::Error {
    fn from(e: ProxyError) -> Self {
        std::io::Error::other(e)
    }
}

impl tor_error::HasKind for ProxyError {
    fn kind(&self) -> tor_error::ErrorKind {
        use ProxyError as E;
        use tor_error::ErrorKind as EK;
        match self {
            E::ProxyConnect(_) | E::ProxyIo(_) => EK::LocalNetworkError,
            E::InvalidSocksAddr(_) | E::InvalidSocksRequest(_) => EK::BadApiUsage,
            E::UnrecognizedAddr => EK::NotImplemented,
            E::SocksProto(_) => EK::LocalProtocolViolation,
            E::Bug(e) => e.kind(),
            E::UnexpectedData => EK::NotImplemented,
            E::SocksError(_) => EK::LocalProtocolViolation,
            E::HttpConnectError(_) | E::HttpConnectMalformed => EK::LocalProtocolViolation,
        }
    }
}

impl tor_error::HasRetryTime for ProxyError {
    fn retry_time(&self) -> tor_error::RetryTime {
        use ProxyError as E;
        use SocksStatus as S;
        use tor_error::RetryTime as RT;
        match self {
            E::ProxyConnect(_) | E::ProxyIo(_) => RT::AfterWaiting,
            E::InvalidSocksAddr(_) => RT::Never,
            E::UnrecognizedAddr => RT::Never,
            E::InvalidSocksRequest(_) => RT::Never,
            E::SocksProto(_) => RT::AfterWaiting,
            E::Bug(_) => RT::Never,
            E::UnexpectedData => RT::Never,
            E::SocksError(e) => match *e {
                S::CONNECTION_REFUSED
                | S::GENERAL_FAILURE
                | S::HOST_UNREACHABLE
                | S::NETWORK_UNREACHABLE
                | S::TTL_EXPIRED => RT::AfterWaiting,
                _ => RT::Never,
            },
            E::HttpConnectError(code) => {
                // 502/503/504 may be transient; auth and policy errors are not.
                if *code == 502 || *code == 503 || *code == 504 {
                    RT::AfterWaiting
                } else {
                    RT::Never
                }
            }
            E::HttpConnectMalformed => RT::Never,
        }
    }
}

#[cfg(feature = "pt-client")]
/// An object that connects to a Tor bridge via an external pluggable transport
/// that provides a proxy.
#[derive(Clone, Debug)]
pub struct ExternalProxyPlugin<R> {
    /// The runtime to use for connections.
    runtime: R,
    /// The location of the proxy.
    proxy_addr: SocketAddr,
    /// The SOCKS protocol version to use.
    proxy_version: SocksVersion,
}

#[cfg(feature = "pt-client")]
impl<R: NetStreamProvider + Send + Sync> ExternalProxyPlugin<R> {
    /// Make a new `ExternalProxyPlugin`.
    pub fn new(rt: R, proxy_addr: SocketAddr, proxy_version: SocksVersion) -> Self {
        Self {
            runtime: rt,
            proxy_addr,
            proxy_version,
        }
    }
}

#[cfg(feature = "pt-client")]
#[async_trait]
impl<R: NetStreamProvider + Send + Sync> TransportImplHelper for ExternalProxyPlugin<R> {
    type Stream = R::Stream;

    async fn connect(&self, target: &OwnedChanTarget) -> crate::Result<(PeerAddr, R::Stream)> {
        let pt_target = match target.chan_method() {
            ChannelMethod::Direct(_) => {
                return Err(crate::Error::UnusableTarget(bad_api_usage!(
                    "Used pluggable transport for a TCP connection."
                )));
            }
            ChannelMethod::Pluggable(target) => target,
            other => {
                return Err(crate::Error::UnusableTarget(bad_api_usage!(
                    "Used unknown, unsupported, transport {:?} for a TCP connection.",
                    other,
                )));
            }
        };

        let into_err = |e: ProxyError| crate::Error::Connect {
            addresses: vec![(sv(pt_target.to_string()), e.into())],
        };
        let protocol =
            settings_to_protocol(self.proxy_version, encode_settings(pt_target.settings()))
                .map_err(into_err)?;
        let stream =
            connect_via_proxy(&self.runtime, &self.proxy_addr, &protocol, pt_target.addr())
                .await
                .map_err(into_err)?;

        Ok((pt_target.into(), stream))
    }
}

/// Encode the PT settings from `IT` in a format that a pluggable transport can use.
#[cfg(feature = "pt-client")]
fn encode_settings<'a, IT>(settings: IT) -> String
where
    IT: Iterator<Item = (&'a str, &'a str)>,
{
    /// Escape a character in the way expected by pluggable transports.
    ///
    /// This escape machinery is a mirror of that in the standard library.
    enum EscChar {
        /// Return a backslash then a character.
        Backslash(char),
        /// Return a character.
        Literal(char),
        /// Return nothing.
        Done,
    }
    impl EscChar {
        /// Create an iterator to escape one character.
        fn new(ch: char, in_key: bool) -> Self {
            match ch {
                '\\' | ';' => EscChar::Backslash(ch),
                '=' if in_key => EscChar::Backslash(ch),
                _ => EscChar::Literal(ch),
            }
        }
    }
    impl Iterator for EscChar {
        type Item = char;

        fn next(&mut self) -> Option<Self::Item> {
            match *self {
                EscChar::Backslash(ch) => {
                    *self = EscChar::Literal(ch);
                    Some('\\')
                }
                EscChar::Literal(ch) => {
                    *self = EscChar::Done;
                    Some(ch)
                }
                EscChar::Done => None,
            }
        }
    }

    /// escape a key or value string.
    fn esc(s: &str, in_key: bool) -> impl Iterator<Item = char> + '_ {
        s.chars().flat_map(move |c| EscChar::new(c, in_key))
    }

    let mut result = String::new();
    for (k, v) in settings {
        result.extend(esc(k, true));
        result.push('=');
        result.extend(esc(v, false));
        result.push(';');
    }
    result.pop(); // remove the final ';' if any. Yes this is ugly.

    result
}

/// Transform a string into a representation that can be sent as SOCKS
/// authentication.
// NOTE(eta): I am very unsure of the logic in here.
#[cfg(feature = "pt-client")]
pub fn settings_to_protocol(vers: SocksVersion, s: String) -> Result<Protocol, ProxyError> {
    let mut bytes: Vec<_> = s.into();
    Ok(if bytes.is_empty() {
        Protocol::Socks(vers, SocksAuth::NoAuth)
    } else if vers == SocksVersion::V4 {
        if bytes.contains(&0) {
            return Err(ProxyError::InvalidSocksRequest(
                tor_socksproto::Error::NotImplemented(
                    "SOCKS 4 doesn't support internal NUL bytes (for PT settings list)".into(),
                ),
            ));
        } else {
            Protocol::Socks(SocksVersion::V4, SocksAuth::Socks4(bytes))
        }
    } else if bytes.len() <= 255 {
        // The [0] here is mandatory according to the pt-spec.
        Protocol::Socks(SocksVersion::V5, SocksAuth::Username(bytes, vec![0]))
    } else if bytes.len() <= (255 * 2) {
        let password = bytes.split_off(255);
        Protocol::Socks(SocksVersion::V5, SocksAuth::Username(bytes, password))
    } else {
        return Err(ProxyError::InvalidSocksRequest(
            tor_socksproto::Error::NotImplemented("PT settings list too long for SOCKS 5".into()),
        ));
    })
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
    #[allow(unused_imports)]
    use super::*;

    #[test]
    fn protocol_debug_redacts_http_connect_auth() {
        let proto = Protocol::HttpConnect {
            auth: Some((
                Sensitive::new("user_name".to_owned()),
                Sensitive::new("pass_word".to_owned()),
            )),
        };

        let formatted = format!("{proto:?}");
        assert!(formatted.contains("HttpConnect"));
        assert!(!formatted.contains("user_name"));
        assert!(!formatted.contains("pass_word"));
    }

    #[cfg(feature = "pt-client")]
    #[test]
    fn setting_encoding() {
        fn check(settings: Vec<(&str, &str)>, expected: &str) {
            assert_eq!(encode_settings(settings.into_iter()), expected);
        }

        // Easy cases, no escapes.
        check(vec![], "");
        check(vec![("hello", "world")], "hello=world");
        check(
            vec![("hey", "verden"), ("hello", "world")],
            "hey=verden;hello=world",
        );
        check(
            vec![("hey", "verden"), ("hello", "world"), ("selv", "tak")],
            "hey=verden;hello=world;selv=tak",
        );

        check(
            vec![("semi;colon", "equals=sign")],
            r"semi\;colon=equals=sign",
        );
        check(
            vec![("equals=sign", "semi;colon")],
            r"equals\=sign=semi\;colon",
        );
        check(
            vec![("semi;colon", "equals=sign"), ("also", "back\\slash")],
            r"semi\;colon=equals=sign;also=back\\slash",
        );
    }

    #[cfg(feature = "pt-client")]
    #[test]
    fn split_settings() {
        use SocksVersion::*;
        let long_string = "examplestrg".to_owned().repeat(50);
        assert_eq!(long_string.len(), 550);
        let sv = |v, a, b| settings_to_protocol(v, long_string[a..b].to_owned()).unwrap();
        let s = |a, b| sv(V5, a, b);
        let v = |a, b| long_string.as_bytes()[a..b].to_vec();

        assert_eq!(s(0, 0), Protocol::Socks(V5, SocksAuth::NoAuth));
        assert_eq!(
            s(0, 50),
            Protocol::Socks(V5, SocksAuth::Username(v(0, 50), vec![0]))
        );
        assert_eq!(
            s(0, 255),
            Protocol::Socks(V5, SocksAuth::Username(v(0, 255), vec![0]))
        );
        assert_eq!(
            s(0, 256),
            Protocol::Socks(V5, SocksAuth::Username(v(0, 255), v(255, 256)))
        );
        assert_eq!(
            s(0, 300),
            Protocol::Socks(V5, SocksAuth::Username(v(0, 255), v(255, 300)))
        );
        assert_eq!(
            s(0, 510),
            Protocol::Socks(V5, SocksAuth::Username(v(0, 255), v(255, 510)))
        );

        // This one needs to use socks4, or it won't fit. :P
        assert_eq!(
            sv(V4, 0, 511),
            Protocol::Socks(V4, SocksAuth::Socks4(v(0, 511)))
        );

        // Small requests with "0" bytes work fine...
        assert_eq!(
            settings_to_protocol(V5, "\0".to_owned()).unwrap(),
            Protocol::Socks(V5, SocksAuth::Username(vec![0], vec![0]))
        );
        assert_eq!(
            settings_to_protocol(V5, "\0".to_owned().repeat(510)).unwrap(),
            Protocol::Socks(V5, SocksAuth::Username(vec![0; 255], vec![0; 255]))
        );

        // Huge requests with "0" simply can't be encoded.
        assert!(settings_to_protocol(V5, "\0".to_owned().repeat(511)).is_err());

        // Huge requests without "0" can't be encoded as V5
        assert!(settings_to_protocol(V5, long_string[0..512].to_owned()).is_err());

        // Requests with "0" can't be encoded as V4.
        assert!(settings_to_protocol(V4, "\0".to_owned()).is_err());
    }

    #[test]
    fn parse_http_connect_200_ok() {
        let response = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        assert_eq!(parse_http_connect_response(response).unwrap(), 200);
    }

    #[test]
    fn parse_http_connect_407_auth_required() {
        let response = b"HTTP/1.1 407 Proxy Authentication Required\r\n\r\n";
        match parse_http_connect_response(response) {
            Err(ProxyError::HttpConnectError(407)) => (), // Expected
            other => panic!("Expected 407 error, got {:?}", other),
        }
    }

    #[test]
    fn parse_http_connect_malformed_no_status() {
        let response = b"INVALID HTTP";
        assert!(matches!(
            parse_http_connect_response(response),
            Err(ProxyError::HttpConnectMalformed)
        ));
    }

    #[test]
    fn parse_http_connect_with_headers() {
        let response = b"HTTP/1.1 200 Connection Established\r\nConnection: close\r\nProxy-Agent: Proxy/1.0\r\n\r\n";
        assert_eq!(parse_http_connect_response(response).unwrap(), 200);
    }

    #[test]
    fn parse_http_connect_rejects_pipelined_data() {
        let response = b"HTTP/1.1 200 OK\r\n\r\nEXTRA_DATA";
        assert!(matches!(
            parse_http_connect_response(response),
            Err(ProxyError::UnexpectedData)
        ));
    }

    #[test]
    fn parse_http_connect_oversized_headers() {
        let huge_header = vec![b'X'; MAX_HTTP_HEADER_BYTES + 1];
        assert!(matches!(
            parse_http_connect_response(&huge_header),
            Err(ProxyError::HttpConnectMalformed)
        ));
    }
}
