//! Descriptions objects for different kinds of directory requests
//! that we can make.

use tor_circmgr::ClientDirTunnel;
use tor_llcrypto::pk::rsa::RsaIdentity;
use tor_netdoc::doc::authcert::AuthCertKeyIds;
use tor_netdoc::doc::microdesc::MdDigest;
use tor_netdoc::doc::netstatus::ConsensusFlavor;
#[cfg(feature = "routerdesc")]
use tor_netdoc::doc::routerdesc::{ExtraInfoDigest, RdDigest};

#[cfg(feature = "hs-client")]
use tor_hscrypto::pk::HsBlindId;

/// Alias for a result with a `RequestError`.
type Result<T> = std::result::Result<T, crate::err::RequestError>;

use base64ct::{Base64Unpadded, Encoding as _};
use std::borrow::Cow;
use std::future::Future;
use std::iter::FromIterator;
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use itertools::Itertools;

use crate::AnonymizedRequest;
use crate::body::RequestBody;
use crate::err::RequestError;

/// Declare an inaccessible public type.
pub(crate) mod sealed {
    use tor_circmgr::ClientDirTunnel;

    use crate::body::RequestBody;

    use super::{AnonymizedRequest, Result};

    use std::future::Future;
    use std::pin::Pin;

    /// Sealed trait to help implement [`Requestable`](super::Requestable): not
    /// visible outside this crate, so we can change its methods however we like.
    pub trait RequestableInner: Send + Sync {
        /// Build an [`http::Request`] from this Requestable, if
        /// it is well-formed.
        fn make_request(&self) -> Result<http::Request<RequestBody>>;

        /// Return true if partial response bodies are potentially useful.
        ///
        /// This is true for request types where we're going to be downloading
        /// multiple documents, and we know how to parse out the ones we wanted
        /// if the answer is truncated.
        fn partial_response_body_ok(&self) -> bool;

        /// Return the maximum allowable response length we'll accept for this
        /// request.
        fn max_response_len(&self) -> usize {
            (16 * 1024 * 1024) - 1
        }

        /// Return an error if there is some problem with the provided circuit that
        /// would keep it from being used for this request.
        fn check_circuit<'a>(
            &self,
            tunnel: &'a ClientDirTunnel,
        ) -> Pin<Box<dyn Future<Output = Result<()>> + 'a + Send>> {
            let _ = tunnel;
            Box::pin(async { Ok(()) })
        }

        /// Return a value to say whether this request must be anonymized.
        fn anonymized(&self) -> AnonymizedRequest;
    }
}

/// A request for an object that can be served over the Tor directory system.
pub trait Requestable: sealed::RequestableInner {
    /// Return a wrapper around this [`Requestable`] that implements `Debug`,
    /// and whose output shows the actual HTTP request that will be generated.
    ///
    /// The format is not guaranteed to  be stable.
    fn debug_request(&self) -> DisplayRequestable<'_, Self>
    where
        Self: Sized,
    {
        DisplayRequestable(self)
    }
}
impl<T: sealed::RequestableInner> Requestable for T {}

/// A wrapper to implement [`Requestable::debug_request`].
pub struct DisplayRequestable<'a, R: Requestable>(&'a R);

impl<'a, R: Requestable> std::fmt::Debug for DisplayRequestable<'a, R> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self.0.make_request())
    }
}

impl sealed::RequestableInner for Arc<dyn Requestable> {
    fn make_request(&self) -> Result<http::Request<RequestBody>> {
        let r: &dyn Requestable = self.as_ref();
        r.make_request()
    }

    fn partial_response_body_ok(&self) -> bool {
        let r: &dyn Requestable = self.as_ref();
        r.partial_response_body_ok()
    }

    fn anonymized(&self) -> AnonymizedRequest {
        let r: &dyn Requestable = self.as_ref();
        r.anonymized()
    }
}

/// How much clock skew do we allow in the distance between the directory
/// cache's clock and our own?
///
///  If we find more skew than this, we end the
/// request early, on the theory that the directory will not tell us any
/// information we'd accept.
#[derive(Clone, Debug)]
struct SkewLimit {
    /// We refuse to proceed if the directory says we are more fast than this.
    ///
    /// (This is equivalent to deciding that, from our perspective, the
    /// directory is at least this slow.)
    max_fast: Duration,

    /// We refuse to proceed if the directory says that we are more slow than
    /// this.
    ///
    /// (This is equivalent to deciding that, from our perspective, the
    /// directory is at least this fast.)
    max_slow: Duration,
}

/// A Request for a consensus directory.
#[derive(Debug, Clone)]
pub struct ConsensusRequest {
    /// What flavor of consensus are we asking for?  Right now, only
    /// "microdesc" and "ns" are supported.
    flavor: ConsensusFlavor,
    /// A list of the authority identities that we believe in.  We tell the
    /// directory cache only to give us a consensus if it is signed by enough
    /// of these authorities.
    authority_ids: Vec<RsaIdentity>,
    /// The publication time of the most recent consensus we have.  Used to
    /// generate an If-Modified-Since header so that we don't get a document
    /// we already have.
    last_consensus_published: Option<SystemTime>,
    /// A set of SHA3-256 digests of the _signed portion_ of consensuses we have.
    /// Used to declare what diffs we would accept.
    last_consensus_sha3_256: Vec<[u8; 32]>,
    /// If present, the largest amount of clock skew to allow between ourself and a directory cache.
    skew_limit: Option<SkewLimit>,
}

impl ConsensusRequest {
    /// Create a new request for a consensus directory document.
    pub fn new(flavor: ConsensusFlavor) -> Self {
        ConsensusRequest {
            flavor,
            authority_ids: Vec::new(),
            last_consensus_published: None,
            last_consensus_sha3_256: Vec::new(),
            skew_limit: None,
        }
    }

    /// Add `id` to the list of authorities that this request should
    /// say we believe in.
    pub fn push_authority_id(&mut self, id: RsaIdentity) {
        self.authority_ids.push(id);
    }

    /// Add `d` to the list of consensus digests this request should
    /// say we already have.
    pub fn push_old_consensus_digest(&mut self, d: [u8; 32]) {
        self.last_consensus_sha3_256.push(d);
    }

    /// Set the publication time we should say we have for our last
    /// consensus to `when`.
    pub fn set_last_consensus_date(&mut self, when: SystemTime) {
        self.last_consensus_published = Some(when);
    }

    /// Return a slice of the consensus digests that we're saying we
    /// already have.
    pub fn old_consensus_digests(&self) -> impl Iterator<Item = &[u8; 32]> {
        self.last_consensus_sha3_256.iter()
    }

    /// Return an iterator of the authority identities that this request
    /// is saying we believe in.
    pub fn authority_ids(&self) -> impl Iterator<Item = &RsaIdentity> {
        self.authority_ids.iter()
    }

    /// Return the date we're reporting for our most recent consensus.
    pub fn last_consensus_date(&self) -> Option<SystemTime> {
        self.last_consensus_published
    }

    /// Tell the directory client that we should abort the request early if the
    /// directory's clock skew exceeds certain limits.
    ///
    /// The `max_fast` parameter is the most fast that we're willing to be with
    /// respect to the directory (or in other words, the most slow that we're
    /// willing to let the directory be with respect to us).
    ///
    /// The `max_slow` parameter is the most _slow_ that we're willing to be with
    /// respect to the directory ((or in other words, the most slow that we're
    /// willing to let the directory be with respect to us).
    pub fn set_skew_limit(&mut self, max_fast: Duration, max_slow: Duration) {
        self.skew_limit = Some(SkewLimit { max_fast, max_slow });
    }
}

/// Convert a list of digests in some format to a string, for use in a request
///
/// The digests `DL` will be sorted, converted to strings with `EF`,
/// separated with `sep`, and returned as an fresh `String`.
///
/// If the digests list is empty, returns None instead.
//
// In principle this ought to be doable with much less allocating,
// starting with hex::encode etc.
fn digest_list_stringify<'d, D, DL, EF>(digests: DL, encode: EF, sep: &str) -> Option<String>
where
    DL: IntoIterator<Item = &'d D> + 'd,
    D: PartialOrd + Ord + 'd,
    EF: Fn(&'d D) -> String,
{
    let mut digests = digests.into_iter().collect_vec();
    if digests.is_empty() {
        return None;
    }
    digests.sort_unstable();
    let ids = digests.into_iter().map(encode).map(Cow::Owned);
    // name collision with unstable Iterator::intersperse
    // https://github.com/rust-lang/rust/issues/48919
    let ids = Itertools::intersperse(ids, Cow::Borrowed(sep)).collect::<String>();
    Some(ids)
}

impl Default for ConsensusRequest {
    fn default() -> Self {
        Self::new(ConsensusFlavor::Microdesc)
    }
}

impl sealed::RequestableInner for ConsensusRequest {
    fn make_request(&self) -> Result<http::Request<RequestBody>> {
        // Build the URL.
        let mut uri = "/tor/status-vote/current/consensus".to_string();
        match self.flavor {
            ConsensusFlavor::Plain => {}
            flav => {
                uri.push('-');
                uri.push_str(flav.name());
            }
        }
        let d_encode_hex = |id: &RsaIdentity| hex::encode(id.as_bytes());
        if let Some(ids) = digest_list_stringify(&self.authority_ids, d_encode_hex, "+") {
            // With authorities, "../consensus/<F1>+<F2>+<F3>"
            uri.push('/');
            uri.push_str(&ids);
        }
        // Without authorities, "../consensus-microdesc"

        let mut req = http::Request::builder().method("GET").uri(uri);
        req = add_common_headers(req, self.anonymized());

        // Possibly, add an if-modified-since header.
        if let Some(when) = self.last_consensus_date() {
            req = req.header(
                http::header::IF_MODIFIED_SINCE,
                httpdate::fmt_http_date(when),
            );
        }

        // Possibly, add an X-Or-Diff-From-Consensus header.
        if let Some(ids) = digest_list_stringify(&self.last_consensus_sha3_256, hex::encode, ", ") {
            req = req.header("X-Or-Diff-From-Consensus", &ids);
        }

        Ok(req.body(RequestBody::default())?)
    }

    fn partial_response_body_ok(&self) -> bool {
        false
    }

    fn check_circuit<'a>(
        &self,
        tunnel: &'a ClientDirTunnel,
    ) -> Pin<Box<dyn Future<Output = Result<()>> + 'a + Send>> {
        let skew_limit = self.skew_limit.clone();
        Box::pin(async move {
            use tor_proto::ClockSkew::*;
            // This is the clock skew _according to the directory_.
            let skew = tunnel.first_hop_clock_skew().await?;
            match (&skew_limit, &skew) {
                (Some(SkewLimit { max_slow, .. }), Slow(slow)) if slow > max_slow => {
                    Err(RequestError::TooMuchClockSkew)
                }
                (Some(SkewLimit { max_fast, .. }), Fast(fast)) if fast > max_fast => {
                    Err(RequestError::TooMuchClockSkew)
                }
                (_, _) => Ok(()),
            }
        })
    }

    fn anonymized(&self) -> AnonymizedRequest {
        AnonymizedRequest::Direct
    }
}

/// A request for one or more authority certificates.
#[derive(Debug, Clone, Default)]
pub struct AuthCertRequest {
    /// The identity/signing keys of the certificates we want.
    ids: Vec<AuthCertKeyIds>,
}

impl AuthCertRequest {
    /// Create a new request, asking for no authority certificates.
    pub fn new() -> Self {
        AuthCertRequest::default()
    }

    /// Add `ids` to the list of certificates we're asking for.
    pub fn push(&mut self, ids: AuthCertKeyIds) {
        self.ids.push(ids);
    }

    /// Return a list of the keys that we're asking for.
    pub fn keys(&self) -> impl Iterator<Item = &AuthCertKeyIds> {
        self.ids.iter()
    }
}

impl sealed::RequestableInner for AuthCertRequest {
    fn make_request(&self) -> Result<http::Request<RequestBody>> {
        if self.ids.is_empty() {
            return Err(RequestError::EmptyRequest);
        }
        let mut ids = self.ids.clone();
        ids.sort_unstable();

        let ids: Vec<String> = ids
            .iter()
            .map(|id| {
                format!(
                    "{}-{}",
                    hex::encode(id.id_fingerprint.as_bytes()),
                    hex::encode(id.sk_fingerprint.as_bytes())
                )
            })
            .collect();

        let uri = format!("/tor/keys/fp-sk/{}", ids.join("+"));

        let req = http::Request::builder().method("GET").uri(uri);
        let req = add_common_headers(req, self.anonymized());

        Ok(req.body(RequestBody::default())?)
    }

    fn partial_response_body_ok(&self) -> bool {
        self.ids.len() > 1
    }

    fn max_response_len(&self) -> usize {
        // TODO: Pick a more principled number; I just made this one up.
        self.ids.len().saturating_mul(16 * 1024)
    }

    fn anonymized(&self) -> AnonymizedRequest {
        AnonymizedRequest::Direct
    }
}

impl FromIterator<AuthCertKeyIds> for AuthCertRequest {
    fn from_iter<I: IntoIterator<Item = AuthCertKeyIds>>(iter: I) -> Self {
        let mut req = Self::new();
        for i in iter {
            req.push(i);
        }
        req
    }
}

/// A request for one or more microdescriptors
#[derive(Debug, Clone, Default)]
pub struct MicrodescRequest {
    /// The SHA256 digests of the microdescriptors we want.
    digests: Vec<MdDigest>,
}

impl MicrodescRequest {
    /// Construct a request for no microdescriptors.
    pub fn new() -> Self {
        MicrodescRequest::default()
    }
    /// Add `d` to the list of microdescriptors we want to request.
    pub fn push(&mut self, d: MdDigest) {
        self.digests.push(d);
    }

    /// Return a list of the microdescriptor digests that we're asking for.
    pub fn digests(&self) -> impl Iterator<Item = &MdDigest> {
        self.digests.iter()
    }
}

impl sealed::RequestableInner for MicrodescRequest {
    fn make_request(&self) -> Result<http::Request<RequestBody>> {
        let d_encode_b64 = |d: &[u8; 32]| Base64Unpadded::encode_string(&d[..]);
        let ids = digest_list_stringify(&self.digests, d_encode_b64, "-")
            .ok_or(RequestError::EmptyRequest)?;
        let uri = format!("/tor/micro/d/{}", ids);
        let req = http::Request::builder().method("GET").uri(uri);

        let req = add_common_headers(req, self.anonymized());

        Ok(req.body(RequestBody::default())?)
    }

    fn partial_response_body_ok(&self) -> bool {
        self.digests.len() > 1
    }

    fn max_response_len(&self) -> usize {
        // TODO: Pick a more principled number; I just made this one up.
        self.digests.len().saturating_mul(8 * 1024)
    }

    fn anonymized(&self) -> AnonymizedRequest {
        AnonymizedRequest::Direct
    }
}

impl FromIterator<MdDigest> for MicrodescRequest {
    fn from_iter<I: IntoIterator<Item = MdDigest>>(iter: I) -> Self {
        let mut req = Self::new();
        for i in iter {
            req.push(i);
        }
        req
    }
}

/// A request for one, many or all router descriptors.
#[derive(Debug, Clone)]
#[cfg(feature = "routerdesc")]
pub struct RouterDescRequest {
    /// The descriptors to request.
    requested_descriptors: RequestedDescs,
}

/// Tracks the different router descriptor types.
#[derive(Debug, Clone)]
#[cfg(feature = "routerdesc")]
enum RequestedDescs {
    /// If this is set, we just ask for all the descriptors.
    AllDescriptors,
    /// A list of digests to download.
    Digests(Vec<RdDigest>),
}

#[cfg(feature = "routerdesc")]
// TODO: This is probably not a reasonable default.
impl Default for RouterDescRequest {
    fn default() -> Self {
        RouterDescRequest {
            requested_descriptors: RequestedDescs::Digests(Vec::new()),
        }
    }
}

#[cfg(feature = "routerdesc")]
impl RouterDescRequest {
    /// Construct a request for all router descriptors.
    pub fn all() -> Self {
        RouterDescRequest {
            requested_descriptors: RequestedDescs::AllDescriptors,
        }
    }
    /// Construct a new empty request.
    pub fn new() -> Self {
        RouterDescRequest::default()
    }
}

#[cfg(feature = "routerdesc")]
impl sealed::RequestableInner for RouterDescRequest {
    fn make_request(&self) -> Result<http::Request<RequestBody>> {
        let mut uri = "/tor/server/".to_string();

        match self.requested_descriptors {
            RequestedDescs::Digests(ref digests) => {
                uri.push_str("d/");
                let ids = digest_list_stringify(digests, hex::encode, "+")
                    .ok_or(RequestError::EmptyRequest)?;
                uri.push_str(&ids);
            }
            RequestedDescs::AllDescriptors => {
                uri.push_str("all");
            }
        }

        let req = http::Request::builder().method("GET").uri(uri);
        let req = add_common_headers(req, self.anonymized());

        Ok(req.body(RequestBody::default())?)
    }

    fn partial_response_body_ok(&self) -> bool {
        match self.requested_descriptors {
            RequestedDescs::Digests(ref digests) => digests.len() > 1,
            RequestedDescs::AllDescriptors => true,
        }
    }

    fn max_response_len(&self) -> usize {
        // TODO: Pick a more principled number; I just made these up.
        match self.requested_descriptors {
            RequestedDescs::Digests(ref digests) => digests.len().saturating_mul(8 * 1024),
            RequestedDescs::AllDescriptors => 64 * 1024 * 1024, // big but not impossible
        }
    }

    fn anonymized(&self) -> AnonymizedRequest {
        AnonymizedRequest::Direct
    }
}

#[cfg(feature = "routerdesc")]
impl FromIterator<RdDigest> for RouterDescRequest {
    fn from_iter<I: IntoIterator<Item = RdDigest>>(iter: I) -> Self {
        let digests = iter.into_iter().collect();

        RouterDescRequest {
            requested_descriptors: RequestedDescs::Digests(digests),
        }
    }
}

/// A request for the descriptor of whatever relay we are making the request to
#[derive(Debug, Clone, Default)]
#[cfg(feature = "routerdesc")]
#[non_exhaustive]
pub struct RoutersOwnDescRequest {}

#[cfg(feature = "routerdesc")]
impl RoutersOwnDescRequest {
    /// Construct a new request.
    pub fn new() -> Self {
        RoutersOwnDescRequest::default()
    }
}

#[cfg(feature = "routerdesc")]
impl sealed::RequestableInner for RoutersOwnDescRequest {
    fn make_request(&self) -> Result<http::Request<RequestBody>> {
        let uri = "/tor/server/authority";
        let req = http::Request::builder().method("GET").uri(uri);
        let req = add_common_headers(req, self.anonymized());

        Ok(req.body(RequestBody::default())?)
    }

    fn partial_response_body_ok(&self) -> bool {
        false
    }

    fn anonymized(&self) -> AnonymizedRequest {
        AnonymizedRequest::Direct
    }
}

/// A request for one or many extra-infos.
///
/// <https://spec.torproject.org/dir-spec/general-use-http-urls.html>
/// (search in page for "extra-info")
#[derive(Debug, Clone)]
#[cfg(feature = "routerdesc")]
pub struct ExtraInfoRequest {
    /// The extra-infos to request.
    requested_extra_infos: RequestedExtraInfos,
}

/// Which extra-info documents to download.
///
/// Currently only a subset of the available URLs are supported.
#[derive(Debug, Clone)]
#[cfg(feature = "routerdesc")]
#[non_exhaustive]
enum RequestedExtraInfos {
    /// Just ask for all the extra-infos.
    ///
    /// `http://<hostname>/tor/extra/all`
    // TODO: Rename this to `All`, alongside `RequestedRouterDescs`.
    AllExtraInfos,
    /// Download extra-infos with these SHA-1 digests.
    ///
    /// `http://<hostname>/tor/extra/d/...`
    Digests(Vec<ExtraInfoDigest>),
}

#[cfg(feature = "routerdesc")]
impl Default for ExtraInfoRequest {
    // TODO: This is probably not a reasonable default.
    fn default() -> Self {
        Self {
            requested_extra_infos: RequestedExtraInfos::Digests(Vec::new()),
        }
    }
}

#[cfg(feature = "routerdesc")]
impl ExtraInfoRequest {
    /// Construct a request for all extra-infos.
    pub fn all() -> Self {
        Self {
            requested_extra_infos: RequestedExtraInfos::AllExtraInfos,
        }
    }
    /// Construct a new empty request.
    pub fn new() -> Self {
        Self::default()
    }
}

#[cfg(feature = "routerdesc")]
impl sealed::RequestableInner for ExtraInfoRequest {
    fn make_request(&self) -> Result<http::Request<RequestBody>> {
        let mut uri = "/tor/extra/".to_string();

        match &self.requested_extra_infos {
            RequestedExtraInfos::AllExtraInfos => uri.push_str("all"),
            RequestedExtraInfos::Digests(digests) => {
                uri.push_str("d/");
                let ids = digest_list_stringify(digests, hex::encode_upper, "+")
                    .ok_or(RequestError::EmptyRequest)?;
                uri.push_str(&ids);
            }
        }

        let req = http::Request::builder().method("GET").uri(uri);
        let req = add_common_headers(req, self.anonymized());
        Ok(req.body(RequestBody::default())?)
    }

    fn partial_response_body_ok(&self) -> bool {
        match &self.requested_extra_infos {
            RequestedExtraInfos::Digests(digests) => digests.len() > 1,
            RequestedExtraInfos::AllExtraInfos => true,
        }
    }

    fn max_response_len(&self) -> usize {
        // TODO torspec#392: Pick more principled size limits.
        // These were copied from the RouterDescRequest impl and doubled.
        match &self.requested_extra_infos {
            RequestedExtraInfos::Digests(digests) => digests.len().saturating_mul(16 * 1024),
            RequestedExtraInfos::AllExtraInfos => 128 * 1024 * 1024,
        }
    }

    fn anonymized(&self) -> AnonymizedRequest {
        AnonymizedRequest::Direct
    }
}

#[cfg(feature = "routerdesc")]
impl FromIterator<ExtraInfoDigest> for ExtraInfoRequest {
    fn from_iter<T: IntoIterator<Item = ExtraInfoDigest>>(iter: T) -> Self {
        Self {
            requested_extra_infos: RequestedExtraInfos::Digests(iter.into_iter().collect()),
        }
    }
}

/// A request to download a hidden service descriptor
///
/// rend-spec-v3 2.2.6
#[derive(Debug, Clone)]
#[cfg(feature = "hs-client")]
pub struct HsDescDownloadRequest {
    /// What hidden service?
    hsid: HsBlindId,
    /// What's the largest acceptable response length?
    max_len: usize,
}

#[cfg(feature = "hs-client")]
impl HsDescDownloadRequest {
    /// Construct a request for a single onion service descriptor by its
    /// blinded ID.
    pub fn new(hsid: HsBlindId) -> Self {
        /// Default maximum length to use when we have no other information.
        const DEFAULT_HSDESC_MAX_LEN: usize = 50_000;
        HsDescDownloadRequest {
            hsid,
            max_len: DEFAULT_HSDESC_MAX_LEN,
        }
    }

    /// Set the maximum acceptable response length.
    pub fn set_max_len(&mut self, max_len: usize) {
        self.max_len = max_len;
    }
}

#[cfg(feature = "hs-client")]
impl sealed::RequestableInner for HsDescDownloadRequest {
    fn make_request(&self) -> Result<http::Request<RequestBody>> {
        let hsid = Base64Unpadded::encode_string(self.hsid.as_ref());
        // We hardcode version 3 here; if we ever have a v4 onion service
        // descriptor, it will need a different kind of Request.
        let uri = format!("/tor/hs/3/{}", hsid);
        let req = http::Request::builder().method("GET").uri(uri);
        let req = add_common_headers(req, self.anonymized());
        Ok(req.body(RequestBody::default())?)
    }

    fn partial_response_body_ok(&self) -> bool {
        false
    }

    fn max_response_len(&self) -> usize {
        self.max_len
    }

    fn anonymized(&self) -> AnonymizedRequest {
        AnonymizedRequest::Anonymized
    }
}

/// Define a request type for uploading a document.
#[allow(unused)]
macro_rules! upload_request {
    {
        $(
            $(#[$m:meta])*
            pub struct $t:ident (
                // Does this request require anonymity?
                // This should be a variant of AnonymizedRequest.
                $anonymity:ident,
                // To what URI at the server should the document be posted?
                // This should begin with "/tor">
                $uri:expr,
                // Total maximum length of the _response_ that we'll accept.
                // If the response is larger than this, we'll abort the request.
                //
                // Note that expected response body for a POST is typically _empty_,
                // but this needs to be nonzero in order to account for
                // the status line and headers.
                $max_response_len:expr
            )
        );*
        $(;)?
    } => {
        $(
            $(#[$m])*
            #[derive(Clone, Debug)]
            pub struct $t(Arc<str>);

            impl $t {
                /// Create a new upload request
                pub fn new(document: Arc<str>) -> Self {
                    Self(document)
                }
            }

            impl sealed::RequestableInner for $t {
                fn make_request(&self) -> Result<http::Request<RequestBody>> {
                    /// The upload URI.
                    const URI: &str = $uri;

                    let req = http::Request::builder().method("POST").uri(URI);
                    let req = add_common_headers(req, self.anonymized());
                    Ok(req.body(RequestBody::from(Arc::clone(&self.0)))?)
                }

                fn partial_response_body_ok(&self) -> bool {
                    false
                }

                fn max_response_len(&self) -> usize {
                    $max_response_len
                }

                fn anonymized(&self) -> AnonymizedRequest {
                    AnonymizedRequest::$anonymity
                }
            }
         )*
    }
}

#[cfg(feature = "hs-service")]
upload_request! {

    /// A request to upload a hidden service descriptor
    ///
    /// rend-spec-v3 2.2.6
    pub struct HsDescUploadRequest(
        Anonymized,
        "/tor/hs/3/publish",
        // A real Tor POST _response_ will always be less than this length, which
        // will fit into 3 DATA messages at most. (The reply will be a single
        // HTTP line, followed by a Date header.)
        // Do not increase this limit without thinking about side channels!
        //
        // (Note that the body will be empty, but we need to allow some space
        // to account for the status line and headers.)
        1024
    )
}

#[cfg(feature = "relay")]
upload_request! {
    /// A request to upload a router descriptor and optional extra-info document.
    pub struct UploadRouterDesc(Direct, "/tor/", 4096);

}

/// Encodings that all Tor clients support.
const UNIVERSAL_ENCODINGS: &str = "deflate, identity";

/// List all the encodings we accept
fn all_encodings() -> String {
    #[allow(unused_mut)]
    let mut encodings = UNIVERSAL_ENCODINGS.to_string();
    #[cfg(feature = "xz")]
    {
        encodings += ", x-tor-lzma";
    }
    #[cfg(feature = "zstd")]
    {
        encodings += ", x-zstd";
    }

    encodings
}

/// Add commonly used headers to the HTTP request.
///
/// (Right now, this is only Accept-Encoding.)
fn add_common_headers(
    req: http::request::Builder,
    anon: AnonymizedRequest,
) -> http::request::Builder {
    // TODO: gzip, brotli
    match anon {
        AnonymizedRequest::Anonymized => {
            // In an anonymized request, we do not admit to supporting any
            // encoding besides those that are always available.
            req.header(http::header::ACCEPT_ENCODING, UNIVERSAL_ENCODINGS)
        }
        AnonymizedRequest::Direct => req.header(http::header::ACCEPT_ENCODING, all_encodings()),
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
    use super::sealed::RequestableInner;
    use super::*;
    use web_time_compat::SystemTimeExt;

    #[test]
    fn test_md_request() -> Result<()> {
        let d1 = b"This is a testing digest. it isn";
        let d2 = b"'t actually SHA-256.............";

        let mut req = MicrodescRequest::default();
        req.push(*d1);
        assert!(!req.partial_response_body_ok());
        req.push(*d2);
        assert!(req.partial_response_body_ok());
        assert_eq!(req.max_response_len(), 16 << 10);

        let req = crate::util::request_to_string(&req.make_request()?);

        assert_eq!(
            req,
            format!(
                "GET /tor/micro/d/J3QgYWN0dWFsbHkgU0hBLTI1Ni4uLi4uLi4uLi4uLi4-VGhpcyBpcyBhIHRlc3RpbmcgZGlnZXN0LiBpdCBpc24 HTTP/1.0\r\naccept-encoding: {}\r\n\r\n",
                all_encodings()
            )
        );

        // Try it with FromIterator, and use some accessors.
        let req2: MicrodescRequest = vec![*d1, *d2].into_iter().collect();
        let ds: Vec<_> = req2.digests().collect();
        assert_eq!(ds, vec![d1, d2]);
        let req2 = crate::util::request_to_string(&req2.make_request()?);
        assert_eq!(req, req2);

        Ok(())
    }

    #[test]
    fn test_cert_request() -> Result<()> {
        let d1 = b"This is a testing dn";
        let d2 = b"'t actually SHA-256.";
        let key1 = AuthCertKeyIds {
            id_fingerprint: (*d1).into(),
            sk_fingerprint: (*d2).into(),
        };

        let d3 = b"blah blah blah 1 2 3";
        let d4 = b"I like pizza from Na";
        let key2 = AuthCertKeyIds {
            id_fingerprint: (*d3).into(),
            sk_fingerprint: (*d4).into(),
        };

        let mut req = AuthCertRequest::default();
        req.push(key1);
        assert!(!req.partial_response_body_ok());
        req.push(key2);
        assert!(req.partial_response_body_ok());
        assert_eq!(req.max_response_len(), 32 << 10);

        let keys: Vec<_> = req.keys().collect();
        assert_eq!(keys, vec![&key1, &key2]);

        let req = crate::util::request_to_string(&req.make_request()?);

        assert_eq!(
            req,
            format!(
                "GET /tor/keys/fp-sk/5468697320697320612074657374696e6720646e-27742061637475616c6c79205348412d3235362e+626c616820626c616820626c6168203120322033-49206c696b652070697a7a612066726f6d204e61 HTTP/1.0\r\naccept-encoding: {}\r\n\r\n",
                all_encodings()
            )
        );

        let req2: AuthCertRequest = vec![key1, key2].into_iter().collect();
        let req2 = crate::util::request_to_string(&req2.make_request()?);
        assert_eq!(req, req2);

        Ok(())
    }

    #[test]
    fn test_consensus_request() -> Result<()> {
        let d1 = RsaIdentity::from_bytes(
            &hex::decode("03479E93EBF3FF2C58C1C9DBF2DE9DE9C2801B3E").unwrap(),
        )
        .unwrap();

        let d2 = b"blah blah blah 12 blah blah blah";
        let d3 = SystemTime::get();
        let mut req = ConsensusRequest::default();

        let when = httpdate::fmt_http_date(d3);

        req.push_authority_id(d1);
        req.push_old_consensus_digest(*d2);
        req.set_last_consensus_date(d3);
        assert!(!req.partial_response_body_ok());
        assert_eq!(req.max_response_len(), (16 << 20) - 1);
        assert_eq!(req.old_consensus_digests().next(), Some(d2));
        assert_eq!(req.authority_ids().next(), Some(&d1));
        assert_eq!(req.last_consensus_date(), Some(d3));

        let req = crate::util::request_to_string(&req.make_request()?);

        assert_eq!(
            req,
            format!(
                "GET /tor/status-vote/current/consensus-microdesc/03479e93ebf3ff2c58c1c9dbf2de9de9c2801b3e HTTP/1.0\r\naccept-encoding: {}\r\nif-modified-since: {}\r\nx-or-diff-from-consensus: 626c616820626c616820626c616820313220626c616820626c616820626c6168\r\n\r\n",
                all_encodings(),
                when
            )
        );

        // Request without authorities
        let req = ConsensusRequest::default();
        let req = crate::util::request_to_string(&req.make_request()?);
        assert_eq!(
            req,
            format!(
                "GET /tor/status-vote/current/consensus-microdesc HTTP/1.0\r\naccept-encoding: {}\r\n\r\n",
                all_encodings()
            )
        );

        Ok(())
    }

    #[test]
    #[cfg(feature = "routerdesc")]
    fn test_rd_request_all() -> Result<()> {
        let req = RouterDescRequest::all();
        assert!(req.partial_response_body_ok());
        assert_eq!(req.max_response_len(), 1 << 26);

        let req = crate::util::request_to_string(&req.make_request()?);

        assert_eq!(
            req,
            format!(
                "GET /tor/server/all HTTP/1.0\r\naccept-encoding: {}\r\n\r\n",
                all_encodings()
            )
        );

        Ok(())
    }

    #[test]
    #[cfg(feature = "routerdesc")]
    fn test_rd_request() -> Result<()> {
        let d1 = b"at some point I got ";
        let d2 = b"of writing in hex...";

        let mut req = RouterDescRequest::default();

        if let RequestedDescs::Digests(ref mut digests) = req.requested_descriptors {
            digests.push(*d1);
        }
        assert!(!req.partial_response_body_ok());
        if let RequestedDescs::Digests(ref mut digests) = req.requested_descriptors {
            digests.push(*d2);
        }
        assert!(req.partial_response_body_ok());
        assert_eq!(req.max_response_len(), 16 << 10);

        let req = crate::util::request_to_string(&req.make_request()?);

        assert_eq!(
            req,
            format!(
                "GET /tor/server/d/617420736f6d6520706f696e74204920676f7420+6f662077726974696e6720696e206865782e2e2e HTTP/1.0\r\naccept-encoding: {}\r\n\r\n",
                all_encodings()
            )
        );

        // Try it with FromIterator, and use some accessors.
        let req2: RouterDescRequest = vec![*d1, *d2].into_iter().collect();
        let ds: Vec<_> = match req2.requested_descriptors {
            RequestedDescs::Digests(ref digests) => digests.iter().collect(),
            RequestedDescs::AllDescriptors => Vec::new(),
        };
        assert_eq!(ds, vec![d1, d2]);
        let req2 = crate::util::request_to_string(&req2.make_request()?);
        assert_eq!(req, req2);
        Ok(())
    }

    #[test]
    #[cfg(feature = "routerdesc")]
    fn test_extra_info_request() -> Result<()> {
        let req = ExtraInfoRequest::from_iter([[0; 20], [1; 20], [2; 20]]);
        assert_eq!(
            crate::util::request_to_string(&req.make_request()?),
            format!(
                "GET /tor/extra/d/{}+{}+{} HTTP/1.0\r\naccept-encoding: {}\r\n\r\n",
                hex::encode_upper([0; 20]),
                hex::encode_upper([1; 20]),
                hex::encode_upper([2; 20]),
                all_encodings()
            )
        );

        let req = ExtraInfoRequest::all();
        assert_eq!(
            crate::util::request_to_string(&req.make_request()?),
            format!(
                "GET /tor/extra/all HTTP/1.0\r\naccept-encoding: {}\r\n\r\n",
                all_encodings()
            )
        );
        Ok(())
    }

    #[test]
    #[cfg(feature = "hs-client")]
    fn test_hs_desc_download_request() -> Result<()> {
        use tor_llcrypto::pk::ed25519::Ed25519Identity;
        let hsid = [1, 2, 3, 4].iter().cycle().take(32).cloned().collect_vec();
        let hsid = Ed25519Identity::new(hsid[..].try_into().unwrap());
        let hsid = HsBlindId::from(hsid);
        let req = HsDescDownloadRequest::new(hsid);
        assert!(!req.partial_response_body_ok());
        assert_eq!(req.max_response_len(), 50 * 1000);

        let req = crate::util::request_to_string(&req.make_request()?);

        assert_eq!(
            req,
            format!(
                "GET /tor/hs/3/AQIDBAECAwQBAgMEAQIDBAECAwQBAgMEAQIDBAECAwQ HTTP/1.0\r\naccept-encoding: {}\r\n\r\n",
                UNIVERSAL_ENCODINGS
            )
        );

        Ok(())
    }
}
