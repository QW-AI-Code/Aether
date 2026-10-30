# Same-version functional repair — Aether 1.6.0

App versionName **1.6.0**, base versionCode **19**, PATCHLEVEL **1.6.0**.
No engine version, signing identity, NPVT cryptography or ordinary backend is replaced.
This follow-up preserves the security/CI fixes and bilingual release notes.

## Catalog and table architecture

The old saved-config card and two-layer group/subscription navigation have been
removed. `ConfigCatalog` is the pure projection/transaction layer, encrypted
`CustomConfigStore` is the durable source, and `ConfigDataTable` renders keyed rows.
The store schema is **v3**; this is a storage schema change, not an app version bump.
Existing v1/v2 configs, custom tabs, author locks, selected server and preferences
are preserved. Old subscription destinations are inferred only when all existing
members consistently belong to one tab; mixed memberships are not guessed.

- **All** always exists. Its counter counts server rows PLUS subscription-source
  rows, including downloaded members. It is not a count of distinct remote hosts.
- Automatic **V2Ray** and **Npvt** tabs appear when their data exists. NPVT format
  takes precedence over its underlying VLESS/SS/etc protocol. Non-NPVT configs
  supported by the Xray importer appear in V2Ray. A mixed subscription source can
  appear in both format views; All still shows each source once.
- Custom tabs have stable IDs independent of labels, CRUD controls and an in-tab
  **Add config** action (text/link, file, QR camera/image or subscription). Import
  destination is captured before asynchronous work and kept for password retries;
  it is not silently changed to whichever tab is visible when downloading ends.
- Moving one config relocates its custom membership, while All and its format tab
  remain inclusive. Moving a subscription moves its source and all members. A
  member can subsequently be moved independently. Deleting a custom tab removes
  memberships, NOT configs or subscriptions. Format tabs are classifications,
  not mutable destination folders; moving does not rewrite a config's protocol.
- Subscription refresh commits once, retains matching IDs and individual moves,
  assigns new members to the current source destination and does not resurrect a
  cleared destination. A deleted subscription cannot be restored by an in-flight
  refresh that started while it existed. Failed imports do not corrupt old data.
- Manual/subscription filter chips remain available inside every tab; All is the
  default filter, so data is no longer hidden behind a separate subscription page.
- The table has name, type, protocol, ping, port and management columns. Headers
  and rows share ONE horizontal viewport; rows use a bounded, keyed LazyColumn for
  vertical scrolling. Widths expand on wide screens; text shrinks to a legible
  floor then ellipsizes, never changes neighboring column sizes. Android RTL
  layout direction is respected. Tabs/toolbar also scroll horizontally.
- Successful non-negative ping results sort low-to-high immediately as results
  arrive, with stable tie-breaking; testing/untested/failed results follow them.
  When a ping batch completes the viewport scrolls to the best-ranked first row.
  Subscription-source rows follow server rows and never receive invented pings.
  Ports of author-locked NPVT configs remain hidden even if their outbound contains
  an endpoint. The requested port column does not override author restrictions.

## Exit-IP regression

The previous UI waited up to **100 seconds** for Diagnostics to publish an exit
IP. Custom readiness deliberately does not run Diagnostics/geo-IP, so no result
arrived; the UI then queried `PortLease.socks` rather than the custom exit port.
The supplied log has a custom exit on **18831**, explaining the wrong fallback.

`TunnelExitMonitor` is owned by the VPN service, not the home-screen composition.
On Connected it queries the exact loopback endpoint from `Connected.socksAddr`
immediately, through SOCKS, with bounded attempts and coroutine retry pacing.
Each Connected session has an epoch, including same-port reconnects, so flow
conflation cannot reuse a previous session. Already-published diagnostic IPs are
not overwritten. Late cancellation cannot clear a newer idle/direct lookup spinner.
Cancellation discards late
results; busy phases clear old exit metadata so rotation cannot reuse it. Provider
failure clears the spinner and does not disconnect a working VPN. There is no
fallback to a direct/operator lookup for a connected tunnel. Exit IP is independent
of the optional initial verification gate and works with that gate off.

Provider reachability and on-device/network behavior still need a device check;
the code does not promise that blocked geo-IP providers will always answer.

## Immediate auto-select feedback

The controller publishes Launching before service startup. Before ranking custom
servers, the service publishes the busy/cancellable Selecting state and localized
“Selecting the best server…” text; afterward it resumes Launching. The home card,
animated busy button, widget state mapping and AI status handling cover this state.
No success is reported until normal startup and the configured gate are complete.

## Ordinary verification preference

Settings → Connection now exposes a persistent default-on **Verify connection**
setting for all SIX built-in modes. DataStore and the service profile codec preserve
it; old/malformed profiles default on. All final readiness paths and ordinary
reconnects use `VerificationPolicy` / `verifyFinal`.

Turning it off skips optional initial checks (including non-Tor intermediate
stage checks), not process/listener startup, essential Tor bootstrap, TLS/certificate
validation, strategy-race winner tests or ongoing liveness/kill-switch logic. Tor
bootstrap is presented as Connecting when the optional gate is disabled, not as
optional Verifying. Custom sessions retain their separate default-on preference;
it is not overridden by the ordinary setting. IP lookup remains informational and
non-blocking in both modes. “Connected” with the gate explicitly disabled means
local startup completed, not that an end-to-end self-test passed.

## Validation

Current executed results and limitations are recorded in `FUNCTIONAL_BUILD_VALIDATION.md`
and `validation/functional-fix-results.json`. Previous validation files are historical.
A source-only ZIP is not a remote GitHub Actions guarantee. A real repository run
still depends on upstream sync, advisory changes, downloads, signing permissions,
runner resources and secrets; a device run is required for actual UI/connection QA.
