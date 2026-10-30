# Chain/UI repair — same Aether 1.6.0

App versionName 1.6.0, base versionCode 19 and PATCHLEVEL 1.6.0 remain unchanged.
The engine stays 2.3.0. This follow-up changes app routing/orchestration, not the
six native backend implementations. The native Cargo.lock alone updates rustls
0.23.45 → 0.23.46 for newly published security advisories. The dependency-floor
helper applies that correction to either a synchronized or fallback CI core.
Strict audit failure is NOT suppressed; the existing documented RSA exception is
unchanged, and unmaintained bincode/paste warnings remain visible.

## Diagnosis and correction

The supplied first log shows a healthy direct custom config, followed by a
remembered DIRECT-network WireGuard route and a UDP race behind that config.
A successful HTTPS/real-delay test does not establish SOCKS UDP capability. In
Config → Aether with AUTO, CustomChainPolicy chooses MASQUE/HTTP2 (TCP) without
the direct network's strategy memory/fingerprint/race. Explicit WireGuard remains
the user's explicit UDP choice. Other chain bases and ordinary modes keep their
existing policies. The TCP chain gets one chain-sized budget (at least 180 s),
preserves the chosen scan mode and does not repeatedly apply direct anti-DPI
fragmentation tactics inside an already proxied path. A healthy proxy may still
be unable to reach WARP's registration API or usable MASQUE gateways.

The supplied second log is Npvt → Tor, not Aether → Config: bootstrap finishes,
the tunnel is ready and warm pings are recorded. It does not prove a reverse-chain
failure. Source review separately found that the plain-engine path overwrote the
actual custom exit with the base engine port when publishing Connected. The
service now retains sessionExitPort for Connected, ping, health checks and the
verification gate after a restart. TUN and sharing already used the custom exit.
Cleanup clears the saved port, so a failed ladder cannot leak a stale endpoint.
Xray's existing dialerProxy/DNS routing is retained and exercised by real-payload
and negative no-bypass tests. No silent direct fallback or disabled TLS validation
is introduced to make a broken remote route look successful.

## Table, tabs, import and first run

- Name + exact real-delay verdict and the per-server menu are pinned in view.
  Type/protocol/port share a synchronized horizontal metadata viewport. Long text
  shrinks then ellipsizes; author-locked endpoints remain hidden.
- OFFLINE, BLOCKED, NO_REPLY, INVALID and NO_NETWORK retain their existing localized
  labels, not one generic failure label. These are diagnostic heuristics, not
  proof that a remote host is permanently dead or censored.
- One keyed lazy vertical list owns all cells. Visible vertical/horizontal scroll
  tracks show position when scrolling is possible; successful delays sort first.
- All/manual/source filter chips are removed. Every tab projects inclusive rows
  directly from the catalog; stable tab/row keys and bounded/reset anchors avoid
  hidden membership or an invalid old scroll anchor when switching families.
- Automatic tab names are persisted aliases of stable All/V2Ray/Npvt keys; renaming
  never reclassifies configs or loses members. A pencil edits each tab. Custom-tab
  create/rename/delete remains, with a distinct filled New tab button.
- No dedicated source-creation button/form or subscription title remains in the
  UI or README. Links and files use one entry flow; a hosted list is recognized
  automatically. Existing encrypted source records, refresh and membership remain
  intact for data compatibility, presented simply as imported links.
- Import choices use rounded filled cards and the text input uses an elevated
  rounded surface; credentials remain sensitive and locked imports stay locked.
- The onboarding runtime gate and its screen/store source are removed. Home opens
  directly, with the 1.6.0 announcement and the retained first-connect/Tor/waiting,
  anti-DPI, airplane-mode and network troubleshooting guidance. The obsolete 1.5.0
  introduction is no longer in the announcement archive.

See CHAIN_UI_BUILD_VALIDATION.md and validation/chain-ui-results.json for ACTUAL
executed results, including live failures. Source packaging is not a remote
GitHub Actions guarantee or an Android device/UI certification.
