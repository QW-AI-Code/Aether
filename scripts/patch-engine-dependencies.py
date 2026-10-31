#!/usr/bin/env python3
"""Apply narrowly scoped security fixes before compiling either synced or fallback cores."""
import argparse
import pathlib
import re
import subprocess
import tomllib

FIXES = (("quinn-proto", (0, 11), (0, 11, 18), "RUSTSEC-2026-0338"),
         ("rustls", (0, 23), (0, 23, 46), "RUSTSEC-2026-0346/0347/0348/0349 (and 0285)"),
         ("rustls-webpki", (0, 103), (0, 103, 16), "RUSTSEC-2026-0382"),
         ("webpki-roots", (1, 0), (1, 0, 10), "RUSTSEC-2026-0381"),
         # 2.4.0 core: zstd 0.14.3 also lifts zstd-safe to 8.2.0 and zstd-sys to 2.1.2.
         ("zstd", (0, 14), (0, 14, 3), "RUSTSEC-2026-0391"),
         ("zstd-safe", (8, 0), (8, 2, 0), "RUSTSEC-2026-0398/0403 (and unsound 0395-0406)"),
         ("zstd-sys", (2, 1), (2, 1, 2), "RUSTSEC-2026-0410"),
         ("ctutils", (0, 4), (0, 4, 4), "RUSTSEC-2026-0385"))


def patch(crate):
    lock = crate / "Cargo.lock"
    if not lock.is_file():
        raise RuntimeError(f"Missing required engine lockfile: {lock}")
    for name, line, minimum, advisory in FIXES:
        packages = tomllib.loads(lock.read_text())["package"]
        for package in packages:
            if package["name"] != name:
                continue
            version = package["version"]
            # Only the affected stable version line; never downgrade a newer core.
            # Semver build metadata (e.g. 2.1.0+zstd.1.5.7) does not affect ordering.
            if not re.fullmatch(r"\d+\.\d+\.\d+(\+[0-9A-Za-z.-]+)?", version):
                raise RuntimeError(f"Review unsupported {name} version: {version}")
            parts = tuple(map(int, version.split("+")[0].split(".")))
            if parts[:2] == line and parts < minimum:
                target = ".".join(map(str, minimum))
                # A previous fix may already have moved this package (zstd drags zstd-safe/-sys along).
                print(f"Patching {name} {version} -> {target} ({advisory})", flush=True)
                subprocess.run(["cargo", "update", "--package", f"{name}@{version}",
                                "--precise", target], cwd=crate, check=True)
                updated = tomllib.loads(lock.read_text())["package"]
                if any(p["name"] == name and p["version"] == version for p in updated):
                    raise RuntimeError(f"Cargo did not replace vulnerable {name}@{version}")
    print("Engine security version floors verified.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("crate", type=pathlib.Path)
    args = parser.parse_args()
    try:
        patch(args.crate.resolve())
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Engine dependency patch failed: {error}\n")
