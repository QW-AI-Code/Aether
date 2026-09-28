# Zero Trust in this app — what works, what does not, and why

> **1.4.0: "sign in first, then connect" is implemented.** The premise
> below that a pre-login needs the engine loaded as a library was wrong: the
> e-mail flow is four plain HTTPS exchanges with `https://<team>.cloudflareaccess.com`.
> `core/ZeroTrustSignIn.kt` performs them exactly as `zerotrust.rs` does
> (`begin_email_signin`, `request_email_code`, `submit_code`; cookies scoped to the
> team host, https only), `core/TeamSignInRecord.kt` + `data/TeamSignInStore.kt`
> seal the resulting JWT in `SecretStore`, bound to team + address, and
> `AetherVpnService.hydrateSecrets` hands it to the engine as `AETHER_ACCESS_TOKEN`
> (instead of `AETHER_ACCESS_EMAIL`) when `core/TeamSignInHandoff.kt` says it is
> valid, matching and the device is not yet enrolled. UI:
> `ui/settings/ZeroTrustMembership.kt` (membership status, sign-in dialog, remove
> membership via `core/TeamMembership.kt`, service-token test). The in-connect code
> prompt described below remains as the fallback. Tests: `ZeroTrustWebTest`,
> `ZeroTrustSignInTest`, `TeamSignInRecordTest`, `TeamMembershipTest`, `ZeroTrustEnvTest`.

Issue #12, reopened by a user who had the diagnosis right: *"you cannot do the
authentication inside the connection. It has to be signed in beforehand."*

This document exists because the answer is not one line. Two of the three sign-in
methods now work, one of them only in a shape the reporter is right to call a
compromise, and the reason is an architectural boundary rather than a missing
feature.

## The three methods

| Method | `TeamAuth` | Works in 1.3.1 | Needs the user present |
| --- | --- | --- | --- |
| Enrolment token | `TOKEN` | Yes | Once, in a browser, beforehand |
| Service token | `SERVICE_TOKEN` | Yes | No |
| E-mail one-time code | `EMAIL` | Yes, since 1.3.1 | Yes, **during** the connect |

Before 1.3.1 the e-mail method did nothing at all. The app put
`AETHER_ACCESS_EMAIL` into the engine's environment and started a tunnel; the
engine mailed a code and waited for somebody to type it; nobody was listening, so
the connect stalled and then failed. From the outside that is
indistinguishable from "this setting is ignored", which is exactly how it was
reported.

## How the e-mail code works now

Core 2.0.0 was written for a non-interactive parent. `zerotrust::prompt_login_code`
tests `std::io::stdin().is_terminal()`, and when stdin is not a terminal — which is
how this app spawns the engine — it does not print a human prompt. It writes one
machine-readable line to stdout:

```
[zerotrust] login-code-needed attempt=1 email=someone@example.com
```

and then blocks on `read_line` from **stdin**, bounded by `CODE_WAIT` (300 s) and
retried up to `CODE_ATTEMPTS` (3) times.

So the whole protocol is: watch stdout for that line, ask the user, write the code
and a newline to stdin. No JNI, no engine change. The app already read the
engine's stdout line by line; it had simply never looked for this line, and had
never written a byte to the engine's stdin.

1.3.1 adds:

- `core/LoginCodePrompt.kt` — recognises the marker, parses `attempt` and `email`,
  publishes a `StateFlow<Request?>`, and writes the code back with the newline and
  an explicit `flush()`. The code itself is never logged: it is a single-use
  credential.
- `AetherProcess` — hands the child's `outputStream` to it on spawn, calls `ingest`
  in the existing stdout loop, and `detach`es when the engine exits, so a dialog
  cannot outlive the process that asked.
- `ui/LoginCodeDialog.kt` — placed at the top of the composition in `MainActivity`,
  because the prompt can arrive while the user is on any screen. Tapping outside
  does **not** dismiss it: that is how a one-time code gets lost by accident.
  "Not now" writes nothing and says so in the log — the prompt belongs to the
  engine, which keeps waiting until its own timeout, and pretending the app can
  cancel it would be a lie about what happens next.

### This is still authentication during a connect

Which is the part of the original criticism that stands. The tunnel starts, the
engine stops halfway, a dialog appears, and only then does the session continue.
It is not "sign in, then connect".

A real pre-login is possible — the engine already exports it. `ffi.rs` has
`aether_team_code_request`, `aether_team_code_resend`, `aether_team_code_submit`
and `aether_team_sign_in`, which are exactly a sign-in flow decoupled from any
tunnel, plus `zerotrust::store_token` / `cached_token` to hold the result.

**The app cannot call them.** It runs the engine as a child PROCESS
(`ProcessBuilder` on `libaether.so`, which Android permits because jniLibs are
extracted with the exec bit). Those functions are a C ABI in the same `.so`, and
reaching them needs the library LOADED into the app process and a JNI or JNA
binding written for it — which means building a bridge against the NDK, and
deciding what happens when a library that is currently also being exec'd as a
binary is dlopen'd in the same package. That is a design change, not a patch, and
it is not in 1.3.1.

If it is taken on, the shape is:

1. A "Sign in" screen under Zero Trust, reachable while disconnected.
2. `aether_team_code_request` with team + e-mail, then a code field, then
   `aether_team_code_submit`, all off the connect path.
3. Store the returned JWT in `SecretStore` (already AES-256-GCM under a
   non-exportable keystore key) and from then on pass only `AETHER_ACCESS_TOKEN`.
4. `AccessToken.inspect` already knows when it is about to expire, so the screen
   can prompt for a fresh sign-in before a connect fails.

Step 3 and 4 already exist. Only the binding is missing.

## The enrolment token path

This is the one that matches "signed in beforehand" today, and it is what the
engine's own help text recommends:

```
--access-token <jwt>   an enrolment token you already obtained by signing in
                       at https://<team>.cloudflareaccess.com/warp
```

Sign in there in a browser, copy the value after `token=`, paste it into
**Settings → Zero Trust**. 1.3.1 checks the paste as you make it, with the same
two rules the engine applies (`looks_like_jwt`, `jwt_expired`), so the two
mistakes that path invites — pasting the URL instead of the token, and pasting
something that expired last week — are named at the field instead of becoming a
connect that fails for reasons only the diagnostics log knows.

`core/AccessToken.kt` does this, covered by `AccessTokenTest` (14 cases). It is
**not** verification: the signature is not checked, it cannot be, the key is
Cloudflare's. A token that passes these checks is not thereby trustworthy — the
engine remains the only thing that decides.

## What is stored where

The two secrets — the service-token secret and the enrolment token — never travel
in an Intent and never appear on a command line. They live in `SecretStore`
(AES-256-GCM, non-exportable Android Keystore key) and the VPN service reads them
itself in `hydrateSecrets`, handing them to the engine through the environment.
`/proc/<pid>/cmdline` is world-readable on Android; the environment is not.

## Known limitation, stated plainly

The reporter also wrote that Zero Trust fails in every fork except Aether's own
core. This document only claims something about THIS app. The e-mail flow above
depends on one specific engine behaviour — the non-TTY marker line and the stdin
read — so a fork whose engine predates that, or that closes the child's stdin,
will not work no matter what its UI does.
