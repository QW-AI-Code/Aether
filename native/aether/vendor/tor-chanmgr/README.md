# tor-chanmgr

Manage a set of channels on the Tor network.

## Overview

This crate is part of
[Arti](https://gitlab.torproject.org/tpo/core/arti/), a project to
implement [Tor](https://www.torproject.org/) in Rust.

In Tor, a channel is a connection to a Tor relay.  It can be
direct via TLS, or indirect via TLS over a pluggable transport.

Since a channel can be used for more than one circuit, it's
important to reuse channels when possible.  This crate implements
a [`ChanMgr`] type that can be used to create channels on demand,
and return existing channels when they already exist.

## Compile-time features

* `pt-client` -- Build with APIs to support
  pluggable transports.

### Experimental and unstable features

 Note that the APIs enabled by these features are NOT covered by
 semantic versioning[^1] guarantees: we might break them or remove
 them between patch versions.

* `experimental` -- Build with all experimental features above.
  (Currently, there are no experimental features in this crate, 
  but there may be some in the future.)

[^1]: Remember, semantic versioning is what makes various `cargo`
features work reliably. To be explicit: if you want `cargo update`
to _only_ make safe changes, then you cannot enable these
features.

License: MIT OR Apache-2.0
