//! Declare a type to represent the encoding of a request or its body.

use std::sync::Arc;

/// The body of an HTTP request.
#[derive(Clone, Debug, Default)]
pub struct RequestBody(
    /// Whenever we make a request with a body, it is an _upload_ request.
    /// Therefore, we would like to make sure the uploaded document is shared
    /// among all the requests that we make, to avoid copies.
    //
    // NOTE: We will someday to support uploading multiple documents in one request
    // as we do for routerdescs and extrainfo documents.
    // When we reach that point, we will want to alter this type, unless it turns out that
    // we always encode the multiple documents as a single string.
    Option<Arc<str>>,
);

impl RequestBody {
    /// Create a new empty RequestBody.
    pub fn new_empty() -> Self {
        Self(None)
    }

    /// Return a reference to the bytes in this body, if there are any.
    fn as_bytes(&self) -> Option<&[u8]> {
        self.0.as_ref().map(|s| s.as_bytes())
    }
}

impl From<Arc<str>> for RequestBody {
    fn from(value: Arc<str>) -> Self {
        Self((!value.is_empty()).then_some(value))
    }
}

impl RequestBody {
    /// Return true if this [`RequestBody`] is empty.
    pub fn is_empty(&self) -> bool {
        self.0.as_ref().is_none_or(|s| s.is_empty())
    }

    /// Return the number of bytes in this [`RequestBody`].
    pub fn len(&self) -> usize {
        self.0.as_ref().map(|s| s.len()).unwrap_or(0)
    }
}

/// The encoding of a http request.
///
/// This type is meant to avoid copies in the case where we want
/// to upload a document in the body of a request.
#[derive(Clone, Debug, Default)]
pub(crate) struct EncodedRequest {
    /// The request header.  We generate this fresh for each request.
    header: String,

    /// The request body.  This can be shared by multiple requests
    /// (and generally is, for uploads).
    body: RequestBody,
}

impl EncodedRequest {
    /// Create a new EncodedRequest from a header.
    pub(crate) fn from_header(header: String) -> Self {
        Self {
            header,
            body: RequestBody::default(),
        }
    }

    /// Return a [`Vec`] holding all the contents of this [`RequestBody`].
    ///
    /// This method is testing-only: it can copy more than we would want.
    #[cfg(test)]
    pub(crate) fn to_owned(&self) -> Vec<u8> {
        let mut v = self.header.as_bytes().to_owned();
        v.extend_from_slice(self.body.as_bytes().unwrap_or(&[]));
        v
    }

    /// Set the body in this request.
    pub(crate) fn set_body(&mut self, body: RequestBody) {
        self.body = body;
    }

    /// Return an iterator over the byte slices that make up this request.
    pub(crate) fn iter(&self) -> impl Iterator<Item = &'_ [u8]> {
        use std::iter;
        iter::once(self.header.as_bytes()).chain(self.body.as_bytes())
    }
}
