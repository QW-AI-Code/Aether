#!/usr/bin/env python3
"""Offline regressions for the early JVM gate and byte-exact release checkout."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / '.github/workflows/build.yml'


def steps():
    text = WORKFLOW.read_text()
    matches = list(re.finditer(r'^      - name: (.+)$', text, re.M))
    return [(m[1], text[m.start():matches[i + 1].start() if i + 1 < len(matches) else len(text)])
            for i, m in enumerate(matches)]


class PreflightTests(unittest.TestCase):
    def test_both_jvm_suites_fail_before_native_setup_or_build(self):
        entries = steps()
        names = [name for name, _ in entries]
        self.assertEqual(names.count('Run unit tests'), 1)
        unit = names.index('Run unit tests')
        for name in ['Install NDK', 'Set up Go', 'Set up Rust (Android targets)',
                     'Install cargo-ndk',
                     'Fetch native sources', 'Build tunnel core (hev)',
                     'Build engine (Aether, with Tor)']:
            self.assertLess(unit, names.index(name), name)
        for name in ['Set up JDK 17', 'Set up Android SDK', 'Set up Gradle',
                     'Prepare signing keystore (stable across builds)',
                     'Purge stale sources from older versions']:
            self.assertLess(names.index(name), unit, name)
        final = names.index('Revalidate unit tests against built core')
        self.assertLess(names.index('Build engine (Aether, with Tor)'), final)
        self.assertLess(final, names.index('Build release APKs'))
        final_body = dict(entries)['Revalidate unit tests against built core']
        self.assertIn(':app:testDebugUnitTest --rerun :app:testReleaseUnitTest --rerun', final_body)
        body = dict(entries)['Run unit tests']
        self.assertIn('./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest', body)
        self.assertIn('exit "$status"', body)
        self.assertNotIn('continue-on-error:', body)
        self.assertNotIn('--exclude-task', body)
        self.assertNotIn('-x test', body)

    def test_shipping_and_security_guards_are_still_present(self):
        names = [name for name, _ in steps()]
        for name in ['Enforce release tree (remove files from older versions)',
                     'Audit Rust dependencies (cargo audit)', 'Build release APKs',
                     'Verify release signature (Play Protect safeguard)',
                     'Verify native libraries inside APKs']:
            self.assertEqual(names.count(name), 1, name)
        text = WORKFLOW.read_text()
        self.assertIn('python3 scripts/test-ci-preflight.py', text)
        self.assertIn('python3 scripts/test-auto-signing.py', text)

    def test_git_checkout_preserves_wrapper_bytes_even_with_autocrlf(self):
        # Exercise Git itself instead of merely checking for an attribute string.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
            def git(*args):
                return subprocess.run(['git', '-C', str(root), *args], env=env,
                                      check=True, capture_output=True)
            git('init', '-q')
            git('config', 'core.autocrlf', 'true')
            for name in ['.gitattributes', 'gradlew', 'gradlew.bat']:
                (root / name).write_bytes((ROOT / name).read_bytes())
            git('add', '.gitattributes', 'gradlew', 'gradlew.bat')
            for name in ['gradlew', 'gradlew.bat']:
                self.assertEqual(git('show', ':' + name).stdout, (ROOT / name).read_bytes())
                (root / name).unlink()
            git('checkout-index', '--all', '--force')
            for name in ['gradlew', 'gradlew.bat']:
                self.assertEqual((root / name).read_bytes(), (ROOT / name).read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
