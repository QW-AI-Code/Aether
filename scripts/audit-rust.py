#!/usr/bin/env python3
"""Audit the engine with explicit outcomes; scanner failures are never reported as success."""
import argparse
import json
import os
import pathlib
import subprocess
import tomllib

# Existing scoped exception: Tor RSA signature verification, not private-key decryption.
# No patched rsa release exists for Marvin; keep this exception visible in every report.
RSA_EXCEPTION = "RUSTSEC-2023-0071"


# Lockfile-only findings: cargo-audit scans Cargo.lock, which also lists packages of optional
# features that are never compiled. crypto-bigint 0.5.5 comes from elliptic-curve 0.13 (p256 /
# ecdsa via ssh-key-fork-arti), which needs ^0.5 so it cannot be raised. The exception is only
# applied after `cargo tree` proves the package is NOT in the build graph of any target with the
# shipped features; if it ever enters the graph the finding blocks the release again.
LOCKFILE_ONLY = {"RUSTSEC-2026-0387": ("crypto-bigint", "0.5.5")}


# Accepted, tightly scoped risk (1.7.0). RUSTSEC-2026-0459: bundled SQLite < 3.54.0 can return
# uninitialised page-cache memory when a process opens a database together with a sibling `-wal`
# file of a different page size that a less trusted party wrote. The fix is libsqlite3-sys 0.39
# (rusqlite 0.41), a semver-major step that arti's tor-dirmgr 0.46 (rusqlite ^0.39) does not
# accept, so no lockfile update can reach it. In this engine SQLite is used only by tor-dirmgr
# for Tor's directory cache, which lives in the app's private storage (AETHER_TOR_DIR under the
# app's files dir): only the app itself can write those files, so the precondition of the
# advisory cannot be met by another party. The exception is applied ONLY while the lockfile
# proves exactly that shape - this version, reached only through rusqlite and only from
# tor-dirmgr. Any other version or any other dependent blocks the release again.
ACCEPTED_RISK = {
    "RUSTSEC-2026-0459": {
        "package": ("libsqlite3-sys", "0.37.0"),
        "chain": (("libsqlite3-sys", {"rusqlite"}), ("rusqlite", {"tor-dirmgr"})),
        "reason": "SQLite only opens Tor's directory cache in app-private storage (no untrusted -wal); "
                  "fix needs rusqlite 0.41, which tor-dirmgr 0.46 does not accept",
    },
}


def lock_packages(crate):
    try:
        return tomllib.loads((crate / "Cargo.lock").read_text(encoding="utf-8")).get("package", [])
    except (OSError, ValueError):
        return []


def dependents_of(packages, name):
    names = set()
    for package in packages:
        for dependency in package.get("dependencies", []):
            if dependency.split()[0] == name:
                names.add(package["name"])
    return names


def accepted_risk_holds(packages, spec):
    name, version = spec["package"]
    if not any(p.get("name") == name and p.get("version") == version for p in packages):
        return False
    return all(dependents_of(packages, crate_name) == allowed for crate_name, allowed in spec["chain"])


def not_in_build_graph(crate, name, version):
    features = os.environ.get("AETHER_FEATURES", "tor")
    cmd = ["cargo", "tree", "--locked", "--target", "all", "-e", "normal,build",
           "-i", f"{name}@{version}"]
    if features:
        cmd += ["--features", features]
    result = subprocess.run(cmd, cwd=crate, capture_output=True, text=True)
    return result.stdout.strip() == ""


def emit(text):
    print(text, flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(text + "\n")


def audit(crate, report, strict):
    if not (crate / "Cargo.lock").is_file():
        raise RuntimeError(f"Missing engine Cargo.lock: {crate}")
    report.parent.mkdir(parents=True, exist_ok=True)
    ignores = [RSA_EXCEPTION]
    for advisory_id, (name, version) in LOCKFILE_ONLY.items():
        if not_in_build_graph(crate, name, version):
            ignores.append(advisory_id)
            emit(f"Lockfile-only exception verified: {name} {version} is not in the build graph ({advisory_id}).")
        else:
            emit(f"::warning::{name} {version} is in the build graph; {advisory_id} is NOT excepted.")
    packages = lock_packages(crate)
    for advisory_id, spec in ACCEPTED_RISK.items():
        name, version = spec["package"]
        if accepted_risk_holds(packages, spec):
            ignores.append(advisory_id)
            emit(f"Scoped accepted risk verified: {name} {version} ({advisory_id}) - {spec['reason']}.")
        elif any(p.get("name") == name for p in packages):
            emit(f"::warning::{name} is present but not in the reviewed shape; {advisory_id} is NOT excepted.")
    ignore_args = [arg for advisory_id in ignores for arg in ("--ignore", advisory_id)]
    result = subprocess.run(["cargo", "audit", "--json", *ignore_args],
                            cwd=crate, capture_output=True, text=True)
    report.write_text(result.stdout, encoding="utf-8")
    report.with_suffix(".stderr.log").write_text(result.stderr, encoding="utf-8")
    if result.returncode not in (0, 1):
        raise RuntimeError(f"cargo audit scanner failed (exit {result.returncode}); see {report.with_suffix('.stderr.log')}")
    try:
        data = json.loads(result.stdout)
        vulnerabilities = data["vulnerabilities"]["list"]
        warnings = data["warnings"]
        if not isinstance(vulnerabilities, list) or not isinstance(warnings, dict):
            raise ValueError("Invalid report collections")
        if data["vulnerabilities"]["count"] != len(vulnerabilities):
            raise ValueError("Inconsistent vulnerability count")
        if data["vulnerabilities"]["found"] != bool(vulnerabilities):
            raise ValueError("Inconsistent vulnerability status")
        if "database" not in data or "lockfile" not in data:
            raise ValueError("Incomplete scanner report")
        if result.returncode == 1 and not vulnerabilities:
            raise ValueError("Scanner exit 1 without vulnerability findings")
    except (ValueError, KeyError, TypeError) as error:
        raise RuntimeError(f"Unusable cargo audit JSON: {error}") from error
    emit("### Rust dependency audit")
    emit(f"Vulnerabilities: **{len(vulnerabilities)}**; informational warnings: **{sum(len(v) for v in warnings.values())}**.")
    emit(f"Existing scoped exception: `{RSA_EXCEPTION}` (Tor signature-verification use; not private-key decryption).")
    for kind, items in [("vulnerability", vulnerabilities), *warnings.items()]:
        for item in items:
            package = item["package"]
            advisory = item.get("advisory") or {}
            text = (f"{kind}: {package['name']} {package['version']} — "
                    f"{advisory.get('id', 'no advisory ID')}: {advisory.get('title', kind)}")
            emit("- " + text)
            # Visible warnings, never blanket --ignore or pretend maintenance warnings vanished.
            escaped = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::warning::{escaped}", flush=True)
    emit(f"Scanner completed (exit {result.returncode}). JSON report: `{report.name}`.")
    if vulnerabilities:
        emit("Unresolved vulnerabilities block release." if strict else "Advisory-only policy: findings are reported, not a build failure.")
    return 1 if vulnerabilities and strict else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crate", type=pathlib.Path, default=pathlib.Path("native/aether/aether"))
    parser.add_argument("--report", type=pathlib.Path, required=True)
    parser.add_argument("--strict", action="store_true", help="Fail on unresolved vulnerabilities, not maintenance warnings")
    args = parser.parse_args()
    try:
        code = audit(args.crate.resolve(), args.report.resolve(), args.strict)
    except (OSError, RuntimeError, KeyError, TypeError) as error:
        emit(f"::error::Rust audit did not complete: {error}")
        code = 2
    raise SystemExit(code)
