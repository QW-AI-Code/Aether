# Clean-IP scan settings — 1.6.0

Open **Settings → Clean IP scan settings**. Both the per-server action and the
current-tab group action use the same saved options, snapshotted at scan start.
Opening settings never starts a probe.

| Profile | Candidate budget | Timeout (ms) | Attempts/IP | Parallel probes |
| --- | ---: | ---: | ---: | ---: |
| Fast | 120 | 3500 | 1 | 60 |
| Balanced (default) | 300 | 5000 | 2 | 50 |
| Deep | 600 | 6000 | 3 | 40 |

**Use manual ping limit** enables an ASCII/LTR numeric field (50–5000 ms,
default 2000 ms for a new installation). A valid previous saved value is preserved
when the field is temporarily empty or out of range. With the limit enabled, the
first stable candidate at or below the limit is accepted. With it disabled,
candidates are ordered by measured real-proxy latency before stability confirmation.
No qualifying candidate means no endpoint write.

## Using the scanner

- Disconnect first. In a compatible server menu choose **Scan / change clean IP**;
  it starts immediately without a second scrolling dialog.
- The active row displays discovery and real-proxy validation progress. Validation
  shares the existing private-core real-delay path; a preliminary TCP/TLS/trace
  response is not a successful proxy ping.
- The bottom result card shows the stable server label, old endpoint, new endpoint
  and confirmed ping. Unchanged, cancelled and failed runs say so explicitly.
  Group results are queued until dismissed; starting a new run clears old notices.
- **Scan clean IPs for servers in this tab** processes compatible servers in a
  fixed snapshot serially. The same candidate cannot be assigned to two scanned
  servers. Other current server addresses are excluded.
- **Stop scan** cancels work and closes active discovery sockets. Starting a VPN
  session also cancels it. A previously confirmed write is not rolled back by
  stopping a later server in a group.
- Locked, expired, incompatible, deleted or concurrently edited configurations are
  never overwritten. Endpoint replacement preserves protocol, credentials, TLS
  server name and transport headers/path. Storage is committed before reporting
  success and publishing the new ping.

The supported configurations are compatible VLESS/VMess/Trojan CDN transports
(WS, HTTP upgrade, XHTTP) with eligible ports, not every protocol or endpoint.
Discovery is the previously licensed QWVPN scanner, not a new tunnelling module.
The UI/server-number and version contracts remain unchanged.

## Runtime and validation boundaries

A scan runs on the application IO scope, not the main thread. It survives a screen
change while this app process is alive; it is not a persistent operating-system
job and is not promised to survive process termination. It can consume substantial
mobile data (including micro-downloads for healthy candidates). Scan only where
permitted by the network and service policies.

Real proxy validation requires the correctly built native Xray executable and a
working user configuration. The source archive does not include native executables;
follow the existing native build workflow before treating an assembled APK as a
functional VPN. Successful compilation, unit tests or rendered fixtures do not
prove reachability from a particular mobile network. Test artifacts and exact
executed results belong in the separate evidence archive, not this source tree.
