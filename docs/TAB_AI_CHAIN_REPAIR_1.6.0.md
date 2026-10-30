# Aether 1.6.0 — tab crash, contextual assistant and custom TCP chains

This is a same-version repair. App versionName/PATCHLEVEL remain **1.6.0**, base
versionCode remains **19**, engine package remains **2.3.0**. See
`../TAB_AI_CHAIN_VALIDATION.md` for executed tests, failures and build scope.

## Tab crash and ping presentation

The supplied `loge3` is an `IndexOutOfBoundsException` in Material3
`ScrollableTabRow`'s indicator: a newly selected index outlives the old measured
positions. It is not a Room exception (this custom vault is not a Room database).
`ConfigTabStrip` now renders stable keyed, independently selected cards; it has
no selected index or separately subcomposed indicator. Selection/list updates in
either order, renames and deletion of the selected tab are covered by an actual
Compose/Robolectric regression (12 create/rename/delete cycles).

Numeric ping colors: successful **0–149 ms mint/green**, **150–300 ms amber**,
**301+ ms red**. Missing, testing and failed reports (including -1) are muted and
retain their text status. Color is never the only status signal.

## Shared cards and assistant

Import, server actions and move choices use the same outlined navy card family as
the app's common choice sheets. Dialog bodies have a screen-bounded scrolling
region; confirmation buttons remain compact. Disabled actions are visibly muted.
English import, Persian actions and Persian table component renders are tested;
this is not a replacement for physical-device/font-scale QA of every app page.

The custom-config navigation row, page, chain picker, verification, ping/health
settings and dialog headers now connect to the existing `AiTopicIcon`/AI host.
Four grounded topics explain import/tabs, chain order, ping and verification.
Topic help is available offline; live Gemini responses require the user's own
key/model and a permitted active tunnel. Global “show AI hints” remains respected.
Custom-server exits can use the existing client. Plain WARP exits retain the
existing gate. Google may still refuse a particular exit: no API availability
promise is made. No Gemini API request or billable test was made for this repair.

All Gemini requests now use the **Connected state's final loopback SOCKS port**,
not a backend's default intermediate port. Remote/non-loopback endpoints are
refused. Assistant status includes only chain/protocol labels, not config names,
links, credentials, private outbounds or a locked author's endpoint. The assistant
cannot mutate the custom vault through a profile patch.

## IP lookup

The service-owned race publishes the first authenticated IP immediately, without
waiting for optional country refinement. A third independent TLS provider
(api.ipify.org) complements Cloudflare domain/IP trace probes; JSON IP values are
validated as literals, without DNS resolution of arbitrary response text. TLS
host checks are not disabled to work around the certificate mismatch in loge2.
The blocking race is interruptible on session change. Retries and stale-session
protection remain. “Immediate” means the earliest successful network response,
not a guarantee when all remote providers are blocked or slow.

## Config → Aether

Both newly supplied connection logs actually describe **Aether → Config**, with
an established tunnel. They do not contain a failed Config → Aether startup.
The reverse path was retained and tested again, not silently switched to a
different chain order.

Repairs to the forward path:
- Confirm an actual local Xray SOCKS greeting before starting the dependent core,
  even when optional remote connection verification is off.
- Fix Ironclad choosing a QUIC probe while the session carrier is HTTP/2.
- Only the explicit custom-TCP upstream path uses a bounded four-way scan with at
  least 20 seconds per remote data-plane probe and commits the first verified
  gateway. Ordinary scans/other six backends keep their existing strategy.
- Only that custom TCP path, without ECH, omits clear SNI so a remote TLS-sniffing
  proxy cannot replace the requested gateway IP with the visible hostname.
  ECH behavior, client certificate and the existing TLS/SPKI verification settings
  are preserved. This does not enable previously disabled engine pinning; the
  inherited engine defaults remain unchanged. Final HTTPS probes validate the
  public endpoint certificate. No `curl -k`, direct fallback, extra proxy hop or
  automatic reversal of chain order is used.

Both supplied NPVT generations and the controlled local VLESS route passed real
HTTPS through the actual Aether host engine. A healthy representative from the
hosted V2Ray list did not establish a WARP gateway; additional representative
results and anonymized probe-error counts are recorded in the validation report.
A successful ordinary HTTPS ping does not prove arbitrary CONNECT-IP/WARP traffic
is permitted through a remote server. HTTP 400/TLS failures are evidence of a
remote-path failure, not proof of one specific censor or server implementation.

## Reproduce

Keep private samples and identities outside the checkout. Set
`AETHER_PRIVATE_FIXTURES` and `AETHER_CHAIN_FIXTURES` to that directory, containing
legacy.npvt, binary.npvt and optional v2ray-body.bin. Unit tests export the real
Xray session JSON there. `scripts/test-chain-routing.py` tests actual Xray payload
and negative no-bypass cases; `scripts/test-custom-forward-live.py` uses
`XRAY_BIN` and `AETHER_HOST_BIN` for live HTTPS in both directions. It does not test
Android TUN. Never commit its identities, raw logs or actual supplied configs.

The complete three-ABI Android native pipeline and a remote GitHub Actions run are
separate from local Kotlin/Java/resources/tests/Release packaging. Do not treat a
compiler-only local APK as a runnable VPN build. Read the validation scope before
publishing a release.
