Pristine upstream copies of the files this app patches, at core 2.1.0.

Do not edit. scripts/sync-core.sh uses them as the merge base so the app's
engine patches can be rebased onto a new core instead of overwriting it.

1.4.0: refreshed to 2.1.0. Upstream 2.1.0 changed Cargo.toml, lib.rs,
prober.rs and wg_prober.rs; those were three-way merged (app patch x 2.0.0 x
2.1.0). netstack.rs, quic.rs, sysprofile.rs, upstream.rs and wireguard.rs are
byte-identical between 2.0.0 and 2.1.0, so the app's copies were kept verbatim.
build.rs still has no baseline on purpose (an app file in full).

New app patch in 1.4.0: lib.rs "tor-only-psiphon-chain" - lets --tor-only
carry the engine's own Psiphon (the app's Tor -> Psiphon mode).

netstack.rs and the Cargo.toml smoltcp pin: same warning as before - read them
by hand on every upgrade, never let a machine merge take the upstream line.
