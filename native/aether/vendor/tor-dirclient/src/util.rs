//! Helper functions for the directory client code

use std::fmt::Write;

use crate::body::{EncodedRequest, RequestBody};

/// Encode an HTTP request in a quick and dirty HTTP 1.0 format.
pub(crate) fn encode_request(req: &http::Request<RequestBody>) -> EncodedRequest {
    let mut s = format!("{} {} HTTP/1.0\r\n", req.method(), req.uri());

    for (key, val) in req.headers().iter() {
        write!(
            s,
            "{}: {}\r\n",
            key,
            val.to_str()
                .expect("Added an HTTP header that wasn't UTF-8!")
        )
        .expect("Write to string failed");
    }

    if req.method() == http::Method::POST || !req.body().is_empty() {
        write!(s, "Content-Length: {}\r\n", req.body().len())
            .expect("Added an HTTP header that wasn't UTF-8!");
    }

    s.push_str("\r\n");

    let mut body = EncodedRequest::from_header(s);
    body.set_body(req.body().clone());

    body
}

/// Testing helper: convert a request to a String.
///
/// # Panics
///
/// Panics if the request's body is not valid UTF-8
#[cfg(test)]
pub(crate) fn request_to_string(req: &http::Request<RequestBody>) -> String {
    let body = encode_request(req);
    let bytes: Vec<u8> = body.to_owned();
    String::from_utf8(bytes).expect("Body was not UTF-8")
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
    use std::sync::Arc;

    use super::*;

    fn build_request(body: String, headers: &[(&str, &str)]) -> http::Request<RequestBody> {
        let mut builder = http::Request::builder().method("GET").uri("/index.html");

        for (name, value) in headers {
            builder = builder.header(*name, *value);
        }

        let body = RequestBody::from(Arc::<str>::from(body));

        builder.body(body).unwrap()
    }

    #[test]
    fn format() {
        fn chk_format(body: &str, content_length_expected: &str) {
            let req = build_request(body.to_string(), &[]);

            assert_eq!(
                request_to_string(&req),
                format!("GET /index.html HTTP/1.0\r\n{content_length_expected}\r\n{body}")
            );

            let req = build_request(body.to_string(), &[("X-Marsupial", "Opossum")]);
            assert_eq!(
                request_to_string(&req),
                format!(
                    "GET /index.html HTTP/1.0\r\nx-marsupial: Opossum\r\n{content_length_expected}\r\n{body}",
                )
            );
        }

        chk_format("", "");
        chk_format("hello", "Content-Length: 5\r\n");
    }
}
