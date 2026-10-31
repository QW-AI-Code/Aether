Pristine upstream copies of the files this app patches, at core 2.3.0.

Do not edit. scripts/sync-core.sh uses them as the merge base so the app's
engine patches can be rebased onto a new core instead of overwriting it.

1.5.0: refreshed to 2.3.0. Upstream 2.3.0 changed Cargo.toml, lib.rs,
prober.rs, psiphon.rs, quic.rs, upstream.rs and socks.rs; those were three-way
merged (app patch x 2.1.0 x 2.3.0). netstack.rs, sysprofile.rs, wg_prober.rs,
wireguard.rs and routing.rs are byte-identical between 2.1.0 and 2.3.0, so the
app's copies were kept verbatim. build.rs still has no baseline on purpose (an
app file in full).

routing.rs and socks.rs had no cached baseline before 1.5.0. Their 2.1.0 merge
base was reconstructed by hand (the marker-stripped app file, with the patch's
in-place line edits reverted) because stripping the markers alone does not give
a pristine file there. Both now have a real 2.3.0 baseline here.

Hand resolutions in 1.5.0:
- lib.rs "quick-reconnect-rtt-budget": kept the app's timing lines around
  upstream's new quick_verify_masque_peer(.., tls::session_ech()) call.
- psiphon.rs "psiphon-embedded-servers": upstream 2.3.0 now passes -serverList
  itself (AETHER_PSIPHON_SERVER_ENTRIES / --psiphon-server-entries). The app's
  embedded list (AETHER_PSIPHON_EMBEDDED_SERVERS) is now only a fallback fed
  into upstream's client_arguments(), so -serverList is never passed twice.
  The patch has two marked blocks plus the helper function (was three blocks).
- Cargo.lock: upstream 2.3.0's lock, with smoltcp re-pinned to 0.12.0 (plus
  heapless 0.8.0 and defmt 0.3.100 that it needs) to match the app's pin.

netstack.rs and the Cargo.toml smoltcp pin: same warning as before - read them
by hand on every upgrade, never let a machine merge take the upstream line.
