# tor-dirclient

Implements a minimal directory client for Tor.

## Overview

Tor makes its directory requests as HTTP/1.0 requests tunneled over
Tor circuits.  For most objects, Tor uses a one-hop tunnel.  Tor
also uses a few strange and ad-hoc HTTP headers to select
particular functionality, such as asking for diffs, compression,
or multiple documents.

This crate provides an API for downloading Tor directory resources
over a Tor circuit.

This crate is part of
[Arti](https://gitlab.torproject.org/tpo/core/arti/), a project to
implement [Tor](https://www.torproject.org/) in Rust.

## Features

`xz` -- enable XZ compression.  This can be expensive in RAM and CPU,
but it saves a lot of bandwidth.  (On by default.)

`zstd` -- enable ZSTD compression.  (On by default.)

`routerdesc` -- Add support for downloading router descriptors.

License: MIT OR Apache-2.0
