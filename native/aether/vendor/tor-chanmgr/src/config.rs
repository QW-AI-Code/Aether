//! Configuration for a channel manager (and, therefore, channels)
//!
//! # Semver note
//!
//! Most types in this module are re-exported by `arti-client`.

use derive_deftly::Deftly;
use percent_encoding::{AsciiSet, CONTROLS, percent_decode_str, utf8_percent_encode};
use serde::Deserialize;
use std::net::{IpAddr, SocketAddr};
use tor_config::PaddingLevel;
use tor_config::derive::prelude::*;
use tor_socksproto::SocksAuth;
use tor_socksproto::SocksVersion;
use url::{Host, Url};

/// Error parsing a proxy URI string
#[derive(Debug, Clone, thiserror::Error)]
#[non_exhaustive]
pub enum ProxyProtocolParseError {
    /// Proxy URI has an unsupported or missing scheme.
    #[error("unsupported or missing proxy scheme: {0}")]
    UnsupportedScheme(String),
    /// Proxy URI includes a password for a scheme that does not support it.
    #[error("password not supported for proxy scheme: {0}")]
    UnsupportedPassword(String),
    /// Proxy URI had an invalid or unparsable address.
    #[error("invalid proxy address: {0}")]
    InvalidAddress(String),
    /// Proxy URI is missing a port or has an invalid port.
    #[error("missing or invalid port")]
    InvalidPort,
    /// Proxy URI does not match the expected format.
    #[error("invalid proxy URI format: {0}")]
    InvalidFormat(String),
}

/// Authentication credentials for HTTP CONNECT proxy.
///
/// This struct enforces the invariant that a password can only exist when a username
/// is present. If you have both username and password, use the struct directly. If you
/// only have a username, set password to `None`.
#[derive(Debug, Clone, Eq, PartialEq)]
pub struct HttpConnectAuth {
    /// Username for Basic auth (required when auth is present)
    pub username: String,
    /// Optional password for Basic auth
    pub password: Option<String>,
}

/// Information about what proxy protocol to use, and how to use it.
///
/// This type can be parsed from a URI string using the same format as curl's
/// proxy URL syntax (see <https://curl.se/docs/url-syntax.html>).
///
/// Supported formats:
///
/// - `socks4://ip:port` - SOCKS4 proxy
/// - `socks4://user@ip:port` - SOCKS4 proxy with user ID
/// - `socks4a://ip:port` - SOCKS4a proxy (treated same as socks4)
/// - `socks5://ip:port` - SOCKS5 proxy without auth
/// - `socks5://user:pass@ip:port` - SOCKS5 proxy with username/password auth
/// - `socks5://user@ip:port` - SOCKS5 proxy with username only (empty password)
/// - `socks5h://ip:port` - SOCKS5 with remote hostname resolution (treated same as socks5)
///
/// - Hostnames for the proxy server itself are not supported (applies to all proxy types).
/// - Credentials must be embedded in the URI; curl's `-U user:pass` style is not supported.
/// - For `socks4://`, passwords are not supported and will return an error.
/// - Special characters in credentials are percent-encoded using the `url` crate's
///   userinfo encoding.
///
/// HTTP CONNECT:
///
/// Hostnames for the proxy server itself are not supported (only IP addresses).
///
/// - `http://ip:port` - HTTP CONNECT proxy without auth
/// - `http://user:pass@ip:port` - HTTP CONNECT proxy with Basic auth (RFC 7617)
/// - `http://user@ip:port` - HTTP CONNECT proxy with username only (empty password)
#[derive(
    Debug, Clone, Eq, PartialEq, serde_with::DeserializeFromStr, serde_with::SerializeDisplay,
)]
#[non_exhaustive]
pub enum ProxyProtocol {
    /// Connect via SOCKS 4, SOCKS 4a, or SOCKS 5.
    Socks {
        /// The SOCKS version to use
        version: SocksVersion,
        /// The authentication method to use
        auth: SocksAuth,
        /// The proxy server address
        addr: SocketAddr,
    },
    /// Connect via HTTP CONNECT proxy.
    HttpConnect {
        /// The proxy server address
        addr: SocketAddr,
        /// Optional credentials for Basic auth (RFC 7617)
        credentials: Option<HttpConnectAuth>,
    },
}

impl std::str::FromStr for ProxyProtocol {
    type Err = ProxyProtocolParseError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let url = Url::parse(s).map_err(|e| match e {
            url::ParseError::InvalidPort => ProxyProtocolParseError::InvalidPort,
            url::ParseError::InvalidIpv4Address
            | url::ParseError::InvalidIpv6Address
            | url::ParseError::EmptyHost
            | url::ParseError::InvalidDomainCharacter
            | url::ParseError::IdnaError => ProxyProtocolParseError::InvalidAddress(s.to_string()),
            _ => ProxyProtocolParseError::InvalidFormat(s.to_string()),
        })?;

        let scheme_lower = url.scheme().to_ascii_lowercase();

        if url.query().is_some() || url.fragment().is_some() {
            return Err(ProxyProtocolParseError::InvalidFormat(s.to_string()));
        }

        let path = url.path();
        if !path.is_empty() && path != "/" {
            return Err(ProxyProtocolParseError::InvalidFormat(s.to_string()));
        }

        let port = url.port().ok_or(ProxyProtocolParseError::InvalidPort)?;
        let host = url
            .host()
            .ok_or_else(|| ProxyProtocolParseError::InvalidAddress(s.to_string()))?;
        let ip = match host {
            Host::Ipv4(ip) => IpAddr::V4(ip),
            Host::Ipv6(ip) => IpAddr::V6(ip),
            Host::Domain(domain) => domain
                .parse::<IpAddr>()
                .map_err(|_| ProxyProtocolParseError::InvalidAddress(domain.to_string()))?,
        };
        let addr = SocketAddr::new(ip, port);

        match scheme_lower.as_str() {
            "http" => {
                // HTTP CONNECT: optional Basic auth via user:pass@host:port
                let user = url.username();
                let pass = url.password();
                // Reject password-only auth (http://:pass@host:port) - username is required
                if user.is_empty() && pass.is_some() {
                    return Err(ProxyProtocolParseError::InvalidFormat(
                        "password without username not supported".to_string(),
                    ));
                }
                let credentials = if user.is_empty() {
                    None
                } else {
                    let username = percent_decode_str(user)
                        .decode_utf8()
                        .map_err(|_| {
                            ProxyProtocolParseError::InvalidFormat(
                                "invalid UTF-8 in username".to_string(),
                            )
                        })?
                        .into_owned();
                    let password = pass
                        .map(|p| {
                            percent_decode_str(p).decode_utf8().map_err(|_| {
                                ProxyProtocolParseError::InvalidFormat(
                                    "invalid UTF-8 in password".to_string(),
                                )
                            })
                        })
                        .transpose()?
                        .map(|s| s.into_owned());
                    Some(HttpConnectAuth { username, password })
                };
                Ok(ProxyProtocol::HttpConnect { addr, credentials })
            }
            "socks4" | "socks4a" | "socks5" | "socks5h" => {
                let version = match scheme_lower.as_str() {
                    "socks4" | "socks4a" => SocksVersion::V4,
                    "socks5" | "socks5h" => SocksVersion::V5,
                    _ => unreachable!(),
                };
                // Check for authentication credentials (user:pass@host:port or user@host:port).
                let user = url.username();
                let pass = url.password();
                if version == SocksVersion::V4 && pass.is_some() {
                    return Err(ProxyProtocolParseError::UnsupportedPassword(
                        url.scheme().to_string(),
                    ));
                }
                let user_decoded = percent_decode_str(user).decode_utf8().map_err(|_| {
                    ProxyProtocolParseError::InvalidFormat("invalid UTF-8 in username".to_string())
                })?;
                let pass_decoded = pass
                    .map(|p| {
                        percent_decode_str(p).decode_utf8().map_err(|_| {
                            ProxyProtocolParseError::InvalidFormat(
                                "invalid UTF-8 in password".to_string(),
                            )
                        })
                    })
                    .transpose()?;
                let auth = if user.is_empty() && pass.is_none() {
                    SocksAuth::NoAuth
                } else {
                    match version {
                        SocksVersion::V4 => SocksAuth::Socks4(user_decoded.as_bytes().to_vec()),
                        SocksVersion::V5 => {
                            let pass = pass_decoded.as_deref().unwrap_or("");
                            SocksAuth::Username(
                                user_decoded.as_bytes().to_vec(),
                                pass.as_bytes().to_vec(),
                            )
                        }
                        _ => SocksAuth::NoAuth,
                    }
                };
                Ok(ProxyProtocol::Socks {
                    version,
                    auth,
                    addr,
                })
            }
            _ => Err(ProxyProtocolParseError::UnsupportedScheme(
                url.scheme().to_string(),
            )),
        }
    }
}

impl std::fmt::Display for ProxyProtocol {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ProxyProtocol::Socks {
                version,
                auth,
                addr,
            } => {
                // Use SocksVersion's Display impl for the scheme (e.g., "socks5")
                match auth {
                    SocksAuth::NoAuth => write!(f, "{}://{}", version, addr),
                    SocksAuth::Socks4(user_id) => {
                        // SOCKS4: user@host format (no password in SOCKS4)
                        let user = String::from_utf8_lossy(user_id);
                        match encode_userinfo(*version, *addr, &user, None) {
                            Some((user_encoded, _)) => {
                                write!(f, "{}://{}@{}", version, user_encoded, addr)
                            }
                            None => write!(f, "{}://{}@{}", version, user, addr),
                        }
                    }
                    SocksAuth::Username(user, pass) => {
                        // SOCKS5: user:pass@host format
                        let user = String::from_utf8_lossy(user);
                        let pass = String::from_utf8_lossy(pass);
                        match encode_userinfo(*version, *addr, &user, Some(&pass)) {
                            Some((user_encoded, pass_encoded)) => {
                                let pass_encoded = pass_encoded.unwrap_or_default();
                                write!(
                                    f,
                                    "{}://{}:{}@{}",
                                    version, user_encoded, pass_encoded, addr
                                )
                            }
                            None => write!(f, "{}://{}:{}@{}", version, user, pass, addr),
                        }
                    }
                    // Handle potential future auth types
                    _ => write!(f, "{}://{}", version, addr),
                }
            }
            ProxyProtocol::HttpConnect { addr, credentials } => {
                if let Some(auth) = credentials {
                    // encode_userinfo_http should always succeed for valid SocketAddr,
                    // but if it fails, we still percent-encode to produce a valid URI
                    let (user_encoded, pass_encoded) =
                        encode_userinfo_http(*addr, &auth.username, auth.password.as_deref())
                            .unwrap_or_else(|| {
                                // Fallback: use url crate to percent-encode directly
                                debug_assert!(
                                    false,
                                    "encode_userinfo_http failed for addr={}, user={}",
                                    addr, auth.username
                                );
                                let encoded_user = percent_encode_userinfo(&auth.username);
                                let encoded_pass =
                                    auth.password.as_ref().map(|p| percent_encode_userinfo(p));
                                (encoded_user, encoded_pass)
                            });
                    if let Some(p) = pass_encoded {
                        write!(f, "http://{}:{}@{}", user_encoded, p, addr)
                    } else {
                        write!(f, "http://{}@{}", user_encoded, addr)
                    }
                } else {
                    write!(f, "http://{}", addr)
                }
            }
        }
    }
}

impl ProxyProtocol {
    /// Check whether the proxy server address is on the loopback interface.
    pub fn is_loopback(&self) -> bool {
        let addr = match self {
            ProxyProtocol::Socks { addr, .. } => addr,
            ProxyProtocol::HttpConnect { addr, .. } => addr,
        };
        addr.ip().is_loopback()
    }
}

/// Characters that must be percent-encoded in userinfo (RFC 3986 section 3.2.1).
/// This includes: gen-delims (:/?#[]@) and sub-delims (!$&'()*+,;=) except those allowed.
/// For userinfo, we encode: : @ / ? # [ ] and space, plus control characters.
const USERINFO_ENCODE_SET: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b':')
    .add(b'@')
    .add(b'/')
    .add(b'?')
    .add(b'#')
    .add(b'[')
    .add(b']');

/// Percent-encode a string for use in URI userinfo (username or password).
fn percent_encode_userinfo(s: &str) -> String {
    utf8_percent_encode(s, USERINFO_ENCODE_SET).to_string()
}

/// URL-encodes username and optional password for a given scheme and address.
///
/// Builds a URL from `scheme://addr`, sets username/password, and returns
/// the percent-encoded forms suitable for URI userinfo display.
fn encode_userinfo_with_scheme(
    scheme: &str,
    addr: SocketAddr,
    username: &str,
    password: Option<&str>,
) -> Option<(String, Option<String>)> {
    let url_str = format!("{}://{}", scheme, addr);
    let mut url = Url::parse(&url_str).ok()?;
    if url.set_username(username).is_err() {
        return None;
    }
    if url.set_password(password).is_err() {
        return None;
    }
    let user_encoded = url.username().to_string();
    let pass_encoded = url.password().map(str::to_string);
    Some((user_encoded, pass_encoded))
}

/// URL-encodes username and optional password for HTTP CONNECT proxy userinfo display.
fn encode_userinfo_http(
    addr: SocketAddr,
    username: &str,
    password: Option<&str>,
) -> Option<(String, Option<String>)> {
    encode_userinfo_with_scheme("http", addr, username, password)
}

/// URL-encodes username and optional password for SOCKS proxy userinfo display.
///
/// Uses `Url` parsing to produce percent-encoded forms suitable for
/// `socks://user:pass@host:port` style output.
fn encode_userinfo(
    version: SocksVersion,
    addr: SocketAddr,
    username: &str,
    password: Option<&str>,
) -> Option<(String, Option<String>)> {
    encode_userinfo_with_scheme(&version.to_string(), addr, username, password)
}

impl ProxyProtocol {
    /// Create a new SOCKS proxy configuration with no authentication
    pub fn socks_no_auth(version: SocksVersion, addr: SocketAddr) -> Self {
        ProxyProtocol::Socks {
            version,
            auth: SocksAuth::NoAuth,
            addr,
        }
    }
}

/// Deserialize an outbound proxy, treating empty strings as unset.
#[allow(clippy::option_option)]
fn deserialize_outbound_proxy<'de, D>(
    deserializer: D,
) -> Result<Option<Option<ProxyProtocol>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = Option::<String>::deserialize(deserializer)?;
    match value {
        None => Ok(None),
        Some(s) => {
            if s.trim().is_empty() {
                return Ok(Some(None));
            }
            let parsed = s.parse().map_err(serde::de::Error::custom)?;
            Ok(Some(Some(parsed)))
        }
    }
}

/// Channel configuration
///
/// This type is immutable once constructed.  To build one, use
/// [`ChannelConfigBuilder`], or deserialize it from a string.
#[derive(Debug, Clone, Deftly, Eq, PartialEq)]
#[derive_deftly(TorConfig)]
pub struct ChannelConfig {
    /// Control of channel padding
    #[deftly(tor_config(default))]
    pub(crate) padding: PaddingLevel,

    /// Outbound proxy to use for all direct connections
    #[deftly(tor_config(
        default,
        serde = r#" deserialize_with = "deserialize_outbound_proxy" "#
    ))]
    pub(crate) outbound_proxy: Option<ProxyProtocol>,
}

impl ChannelConfig {
    /// Return the outbound proxy configured for this channel, if any.
    pub fn outbound_proxy(&self) -> Option<&ProxyProtocol> {
        self.outbound_proxy.as_ref()
    }
}

#[cfg(feature = "testing")]
impl ChannelConfig {
    /// The padding level (accessor for testing)
    pub fn padding(&self) -> PaddingLevel {
        self.padding
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

    #[test]
    fn channel_config() {
        let config = ChannelConfig::default();

        assert_eq!(PaddingLevel::Normal, config.padding);
    }

    #[test]
    fn proxy_protocol_parse_socks5_basic() {
        let p: ProxyProtocol = "socks5://127.0.0.1:1080".parse().unwrap();
        match p {
            ProxyProtocol::Socks {
                version,
                auth,
                addr,
            } => {
                assert_eq!(version, SocksVersion::V5);
                assert_eq!(auth, SocksAuth::NoAuth);
                assert_eq!(addr, "127.0.0.1:1080".parse().unwrap());
            }
            ProxyProtocol::HttpConnect { .. } => panic!("expected Socks"),
        }
    }

    #[test]
    fn proxy_protocol_parse_socks5_with_auth() {
        let p: ProxyProtocol = "socks5://myuser:mypass@192.168.1.1:9050".parse().unwrap();
        match p {
            ProxyProtocol::Socks {
                version,
                auth,
                addr,
            } => {
                assert_eq!(version, SocksVersion::V5);
                assert_eq!(
                    auth,
                    SocksAuth::Username(b"myuser".to_vec(), b"mypass".to_vec())
                );
                assert_eq!(addr, "192.168.1.1:9050".parse().unwrap());
            }
            ProxyProtocol::HttpConnect { .. } => panic!("expected Socks"),
        }
    }

    #[test]
    fn proxy_protocol_parse_socks4() {
        let p: ProxyProtocol = "socks4://10.0.0.1:1080".parse().unwrap();
        match p {
            ProxyProtocol::Socks {
                version,
                auth,
                addr,
            } => {
                assert_eq!(version, SocksVersion::V4);
                assert_eq!(auth, SocksAuth::NoAuth);
                assert_eq!(addr, "10.0.0.1:1080".parse().unwrap());
            }
            ProxyProtocol::HttpConnect { .. } => panic!("expected Socks"),
        }
    }

    #[test]
    fn proxy_protocol_parse_socks4a() {
        let p: ProxyProtocol = "socks4a://10.0.0.1:1080".parse().unwrap();
        match p {
            ProxyProtocol::Socks { version, auth, .. } => {
                assert_eq!(version, SocksVersion::V4);
                assert_eq!(auth, SocksAuth::NoAuth);
            }
            ProxyProtocol::HttpConnect { .. } => panic!("expected Socks"),
        }
    }

    #[test]
    fn proxy_protocol_parse_ipv6() {
        let p: ProxyProtocol = "socks5://[::1]:1080".parse().unwrap();
        match p {
            ProxyProtocol::Socks { addr, .. } => {
                assert_eq!(addr, "[::1]:1080".parse().unwrap());
            }
            ProxyProtocol::HttpConnect { .. } => panic!("expected Socks"),
        }
    }

    #[test]
    fn proxy_protocol_display_roundtrip() {
        for uri in [
            "socks5://127.0.0.1:1080",
            "socks4://10.0.0.1:9050",
            "socks5://user:pass@192.168.1.1:1080",
            "socks5://[::1]:1080",
            "http://127.0.0.1:8080",
            "http://user:pass@192.168.1.1:3128",
        ] {
            let p: ProxyProtocol = uri.parse().unwrap();
            let s = p.to_string();
            let p2: ProxyProtocol = s.parse().unwrap();
            assert_eq!(p, p2, "Round-trip failed for: {}", uri);
        }
    }

    #[test]
    fn proxy_protocol_parse_errors() {
        // Missing scheme
        assert!("127.0.0.1:1080".parse::<ProxyProtocol>().is_err());

        // Invalid scheme
        assert!("invalid://127.0.0.1:1080".parse::<ProxyProtocol>().is_err());

        // Missing port
        assert!("socks5://127.0.0.1".parse::<ProxyProtocol>().is_err());

        // Invalid address
        assert!("socks5://not-an-ip:1080".parse::<ProxyProtocol>().is_err());

        // SOCKS4 does not support passwords
        assert!(
            "socks4://user:pass@10.0.0.1:1080"
                .parse::<ProxyProtocol>()
                .is_err()
        );
    }

    #[test]
    fn proxy_protocol_case_insensitive() {
        // Scheme parsing should be case-insensitive
        let p1: ProxyProtocol = "SOCKS5://127.0.0.1:1080".parse().unwrap();
        let p2: ProxyProtocol = "socks5://127.0.0.1:1080".parse().unwrap();
        let p3: ProxyProtocol = "SoCkS5://127.0.0.1:1080".parse().unwrap();

        assert_eq!(p1, p2);
        assert_eq!(p2, p3);
    }

    #[test]
    fn proxy_protocol_parse_socks5h() {
        // socks5h:// should be treated as socks5
        let p: ProxyProtocol = "socks5h://127.0.0.1:1080".parse().unwrap();
        match p {
            ProxyProtocol::Socks { version, auth, .. } => {
                assert_eq!(version, SocksVersion::V5);
                assert_eq!(auth, SocksAuth::NoAuth);
            }
            ProxyProtocol::HttpConnect { .. } => panic!("expected Socks"),
        }
    }

    #[test]
    fn proxy_protocol_parse_socks4_user_only() {
        // SOCKS4 with user only (no password)
        let p: ProxyProtocol = "socks4://myuser@10.0.0.1:1080".parse().unwrap();
        match p {
            ProxyProtocol::Socks {
                version,
                auth,
                addr,
            } => {
                assert_eq!(version, SocksVersion::V4);
                assert_eq!(auth, SocksAuth::Socks4(b"myuser".to_vec()));
                assert_eq!(addr, "10.0.0.1:1080".parse().unwrap());
            }
            ProxyProtocol::HttpConnect { .. } => panic!("expected Socks"),
        }
    }

    #[test]
    fn proxy_protocol_parse_socks5_user_only() {
        // SOCKS5 with user only (empty password)
        let p: ProxyProtocol = "socks5://myuser@192.168.1.1:9050".parse().unwrap();
        match p {
            ProxyProtocol::Socks {
                version,
                auth,
                addr,
            } => {
                assert_eq!(version, SocksVersion::V5);
                assert_eq!(auth, SocksAuth::Username(b"myuser".to_vec(), b"".to_vec()));
                assert_eq!(addr, "192.168.1.1:9050".parse().unwrap());
            }
            ProxyProtocol::HttpConnect { .. } => panic!("expected Socks"),
        }
    }

    #[test]
    fn proxy_protocol_percent_encoding_roundtrip() {
        // Test percent-encoding round-trip for special characters
        // User with @ and : characters that need encoding
        let p = ProxyProtocol::Socks {
            version: SocksVersion::V5,
            auth: SocksAuth::Username(b"user@domain".to_vec(), b"pass:word".to_vec()),
            addr: "127.0.0.1:1080".parse().unwrap(),
        };
        let s = p.to_string();
        // Should contain percent-encoded characters
        assert!(s.contains("%40"), "@ should be encoded as %40");
        assert!(
            s.contains("%3A") || s.contains("%3a"),
            ": in password should be encoded"
        );

        // Parse it back
        let p2: ProxyProtocol = s.parse().unwrap();
        assert_eq!(p, p2, "Round-trip failed for percent-encoded URI");
    }

    #[test]
    fn proxy_protocol_socks4_user_roundtrip() {
        // SOCKS4 user-only format should round-trip
        let uri = "socks4://testuser@10.0.0.1:1080";
        let p: ProxyProtocol = uri.parse().unwrap();
        let s = p.to_string();
        let p2: ProxyProtocol = s.parse().unwrap();
        assert_eq!(p, p2, "SOCKS4 user-only round-trip failed");
    }

    #[test]
    fn proxy_protocol_parse_http_connect_basic() {
        let p: ProxyProtocol = "http://127.0.0.1:8080".parse().unwrap();
        match p {
            ProxyProtocol::HttpConnect { addr, credentials } => {
                assert_eq!(addr, "127.0.0.1:8080".parse().unwrap());
                assert!(credentials.is_none());
            }
            _ => panic!("expected HttpConnect"),
        }
    }

    #[test]
    fn proxy_protocol_parse_http_connect_with_auth() {
        let p: ProxyProtocol = "http://myuser:mypass@192.168.1.1:3128".parse().unwrap();
        match p {
            ProxyProtocol::HttpConnect { addr, credentials } => {
                assert_eq!(addr, "192.168.1.1:3128".parse().unwrap());
                let auth = credentials.expect("expected credentials");
                assert_eq!(auth.username, "myuser");
                assert_eq!(auth.password.as_deref(), Some("mypass"));
            }
            _ => panic!("expected HttpConnect"),
        }
    }

    #[test]
    fn proxy_protocol_parse_http_connect_ipv6() {
        let p: ProxyProtocol = "http://[::1]:8080".parse().unwrap();
        match p {
            ProxyProtocol::HttpConnect { addr, .. } => {
                assert_eq!(addr, "[::1]:8080".parse().unwrap());
            }
            _ => panic!("expected HttpConnect"),
        }
    }

    #[test]
    fn proxy_protocol_parse_http_connect_user_only() {
        // user@host means username only; password is None (empty when building Basic auth)
        let p: ProxyProtocol = "http://myuser@127.0.0.1:8080".parse().unwrap();
        match p {
            ProxyProtocol::HttpConnect { credentials, .. } => {
                let auth = credentials.expect("expected credentials");
                assert_eq!(auth.username, "myuser");
                assert!(auth.password.is_none());
            }
            _ => panic!("expected HttpConnect"),
        }
    }

    #[test]
    fn proxy_protocol_reject_password_only() {
        // http://:pass@host:port is invalid - username is required for auth
        let result: Result<ProxyProtocol, _> = "http://:secretpass@127.0.0.1:8080".parse();
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(
            err.to_string().contains("password without username"),
            "error should mention password without username: {}",
            err
        );
    }

    #[test]
    fn proxy_protocol_is_loopback() {
        // Loopback IPv4
        let p: ProxyProtocol = "socks5://127.0.0.1:1080".parse().unwrap();
        assert!(p.is_loopback());

        // Loopback IPv6
        let p: ProxyProtocol = "http://[::1]:8080".parse().unwrap();
        assert!(p.is_loopback());

        // Non-loopback IPv4
        let p: ProxyProtocol = "socks5://10.0.0.1:1080".parse().unwrap();
        assert!(!p.is_loopback());

        // Non-loopback IPv6
        let p: ProxyProtocol = "http://[2001:db8::1]:8080".parse().unwrap();
        assert!(!p.is_loopback());
    }

    #[test]
    fn proxy_protocol_http_connect_percent_encoding_roundtrip() {
        // Test percent-encoding round-trip for HTTP CONNECT with special characters
        // Username contains @ and password contains : - both need encoding
        let p = ProxyProtocol::HttpConnect {
            addr: "127.0.0.1:8080".parse().unwrap(),
            credentials: Some(HttpConnectAuth {
                username: "user@domain".to_string(),
                password: Some("pass:word".to_string()),
            }),
        };
        let s = p.to_string();

        // Verify percent-encoded characters are present
        assert!(s.contains("%40"), "@ should be encoded as %40: {}", s);
        assert!(
            s.contains("%3A") || s.contains("%3a"),
            ": in password should be encoded: {}",
            s
        );

        // Parse it back and verify equality
        let p2: ProxyProtocol = s.parse().unwrap();
        assert_eq!(
            p, p2,
            "Round-trip failed for percent-encoded HTTP CONNECT URI"
        );
    }

    #[test]
    fn proxy_protocol_http_connect_parse_percent_encoded() {
        // Parse an already percent-encoded URI and verify credentials decode correctly
        let p: ProxyProtocol = "http://user%40domain:pass%3Aword@127.0.0.1:8080"
            .parse()
            .unwrap();
        match p {
            ProxyProtocol::HttpConnect { addr, credentials } => {
                assert_eq!(addr, "127.0.0.1:8080".parse().unwrap());
                let auth = credentials.expect("expected credentials");
                assert_eq!(
                    auth.username, "user@domain",
                    "username should decode %40 to @"
                );
                assert_eq!(
                    auth.password.as_deref(),
                    Some("pass:word"),
                    "password should decode %3A to :"
                );
            }
            _ => panic!("expected HttpConnect"),
        }
    }
}
