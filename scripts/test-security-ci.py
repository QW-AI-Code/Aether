#!/usr/bin/env python3
"""Offline regression tests for CI audit outcomes and dependency security floors."""
import json
import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent


class SecurityCI(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.crate = self.root / "crate"
        self.crate.mkdir()
        self.lock = self.crate / "Cargo.lock"
        self.lock.write_text('version = 4\n')
        self.summary = self.root / "summary.md"
        self.report = self.root / "audit.json"
        self.mock = self.root / "cargo"
        self.mock.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
if sys.argv[1] == 'audit':
    print(os.environ['MOCK_REPORT'])
    print('scanner diagnostic', file=sys.stderr)
    sys.exit(int(os.environ['MOCK_STATUS']))
if sys.argv[1] == 'update':
    with open(os.environ['MOCK_CALLS'], 'a') as out:
        out.write(json.dumps(sys.argv[1:]) + '\\n')
    if os.environ.get('MOCK_UPDATE_FAIL'):
        sys.exit(7)
    if not os.environ.get('MOCK_NO_CHANGE'):
        name, old = sys.argv[sys.argv.index('--package') + 1].split('@')
        target = sys.argv[sys.argv.index('--precise') + 1]
        lock = pathlib.Path('Cargo.lock')
        lock.write_text(lock.read_text().replace('name = "' + name + '"\\nversion = "' + old + '"',
                      'name = "' + name + '"\\nversion = "' + target + '"'))
    sys.exit(0)
sys.exit(9)
''')
        self.mock.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                        GITHUB_STEP_SUMMARY=str(self.summary), MOCK_STATUS='0',
                        MOCK_CALLS=str(self.root / 'calls'))
        self.data = {'database': {}, 'lockfile': {},
                     'vulnerabilities': {'found': False, 'count': 0, 'list': []}, 'warnings': {}}

    def audit(self, status=0, strict=False, raw=None):
        self.env.update(MOCK_STATUS=str(status), MOCK_REPORT=json.dumps(self.data) if raw is None else raw)
        # This is exactly the inherited GitHub Actions errexit case from the screenshot.
        command = ['python3', str(SCRIPTS / 'audit-rust.py'), '--crate', str(self.crate),
                   '--report', str(self.report)] + (['--strict'] if strict else [])
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', '"$@"', '--', *command],
                              env=self.env, capture_output=True, text=True)

    def finding(self, kind='vulnerability'):
        item = {'package': {'name': 'sample', 'version': '1.0.0'},
                'advisory': {'id': 'RUSTSEC-2099-0001', 'title': 'test finding'}}
        if kind == 'vulnerability':
            self.data['vulnerabilities'] = {'found': True, 'count': 1, 'list': [item]}
        else:
            self.data['warnings'][kind] = [item]

    def test_clean(self):
        self.assertEqual(self.audit(strict=True).returncode, 0)
        self.assertIn('Vulnerabilities: **0**', self.summary.read_text())

    def test_finding_under_errexit_advisory_policy(self):
        self.finding()
        self.assertEqual(self.audit(1).returncode, 0)
        self.assertIn('RUSTSEC-2099-0001', self.summary.read_text())
        self.assertEqual(json.loads(self.report.read_text()), self.data)

    def test_strict_finding_blocks(self):
        self.finding()
        self.assertEqual(self.audit(1, strict=True).returncode, 1)
        self.assertIn('block release', self.summary.read_text())

    def test_unmaintained_visible_not_blocking(self):
        self.finding('unmaintained')
        result = self.audit(strict=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn('::warning::unmaintained', result.stdout)

    def test_infrastructure_failure(self):
        self.assertEqual(self.audit(2).returncode, 2)

    def test_invalid_json(self):
        self.assertEqual(self.audit(raw='network error').returncode, 2)

    def test_exit_one_without_findings_not_clean(self):
        self.assertEqual(self.audit(1).returncode, 2)

    def test_inconsistent_count(self):
        self.data['vulnerabilities']['count'] = 1
        self.assertEqual(self.audit().returncode, 2)

    def test_missing_database(self):
        del self.data['database']
        self.assertEqual(self.audit().returncode, 2)

    def test_missing_lock_audit(self):
        self.lock.unlink()
        self.assertEqual(self.audit().returncode, 2)

    def versions(self, quinn='0.11.17', rustls='0.23.43'):
        self.lock.write_text('version = 4\n' + ''.join(
            f'[[package]]\nname = "{name}"\nversion = "{version}"\n'
            for name, version in [('quinn-proto', quinn), ('rustls', rustls)]))

    def patch(self):
        return subprocess.run(['python3', str(SCRIPTS / 'patch-engine-dependencies.py'), str(self.crate)],
                              env=self.env, capture_output=True, text=True)

    def test_both_security_floors(self):
        self.versions()
        self.assertEqual(self.patch().returncode, 0)
        self.assertIn('0.11.18', self.lock.read_text())
        self.assertIn('0.23.46', self.lock.read_text())
        self.assertEqual(len((self.root / 'calls').read_text().splitlines()), 2)

    def test_previous_tls_fix_is_upgraded_for_new_advisories(self):
        self.versions('0.11.18', '0.23.45')
        self.assertEqual(self.patch().returncode, 0)
        self.assertIn('0.23.46', self.lock.read_text())
        self.assertEqual(len((self.root / 'calls').read_text().splitlines()), 1)

    def test_fixed_lock_idempotent(self):
        self.versions('0.11.18', '0.23.46')
        self.assertEqual(self.patch().returncode, 0)
        self.assertFalse((self.root / 'calls').exists())

    def test_newer_lock_not_downgraded(self):
        self.versions('0.11.99', '0.23.99')
        self.assertEqual(self.patch().returncode, 0)
        self.assertFalse((self.root / 'calls').exists())

    def test_update_failure_blocks(self):
        self.versions()
        self.env['MOCK_UPDATE_FAIL'] = '1'
        self.assertNotEqual(self.patch().returncode, 0)

    def test_update_must_change_lock(self):
        self.versions()
        self.env['MOCK_NO_CHANGE'] = '1'
        self.assertNotEqual(self.patch().returncode, 0)

    def test_missing_lock_patch(self):
        self.lock.unlink()
        self.assertNotEqual(self.patch().returncode, 0)

    def test_absent_packages(self):
        self.lock.write_text('version = 4\n[[package]]\nname = "other"\nversion = "1.0.0"\n')
        self.assertEqual(self.patch().returncode, 0)

    def webpki_versions(self, webpki='0.103.15', roots='1.0.9'):
        self.lock.write_text('version = 4\n' + ''.join(
            f'[[package]]\nname = "{name}"\nversion = "{version}"\n'
            for name, version in [('rustls-webpki', webpki), ('webpki-roots', roots)]))

    def test_october_log_findings_are_both_patched(self):
        self.webpki_versions()
        self.assertEqual(self.patch().returncode, 0)
        self.assertIn('0.103.16', self.lock.read_text())
        self.assertIn('1.0.10', self.lock.read_text())
        calls = [json.loads(line) for line in (self.root / 'calls').read_text().splitlines()]
        self.assertEqual(calls, [
            ['update', '--package', 'rustls-webpki@0.103.15', '--precise', '0.103.16'],
            ['update', '--package', 'webpki-roots@1.0.9', '--precise', '1.0.10']])
        (self.root / 'calls').unlink()
        self.assertEqual(self.patch().returncode, 0)
        self.assertFalse((self.root / 'calls').exists())

    def test_webpki_newer_versions_not_downgraded(self):
        self.webpki_versions('0.103.99', '1.0.99')
        self.assertEqual(self.patch().returncode, 0)
        self.assertFalse((self.root / 'calls').exists())

    def test_webpki_no_change_blocks_release(self):
        self.webpki_versions()
        self.env['MOCK_NO_CHANGE'] = '1'
        self.assertNotEqual(self.patch().returncode, 0)

    def test_compatibility_root_package_is_not_mistaken_for_one_x(self):
        self.webpki_versions()
        with self.lock.open('a') as output:
            output.write('[[package]]\nname = "webpki-roots"\nversion = "0.26.11"\n')
        self.assertEqual(self.patch().returncode, 0)
        self.assertIn('0.26.11', self.lock.read_text())
        self.assertIn('1.0.10', self.lock.read_text())

    def test_synced_engine_is_hardened_before_fetch(self):
        workflow = (SCRIPTS.parent / '.github/workflows/build.yml').read_text()
        self.assertLess(workflow.index('Patch known-vulnerable engine dependencies'),
                        workflow.index('name: Fetch native sources'))

    def test_workflow_audits_built_lock_strictly(self):
        workflow = (SCRIPTS.parent / '.github/workflows/build.yml').read_text()
        block = workflow.split('      - name: Audit Rust dependencies (cargo audit)', 1)[1].split('      - name:', 1)[0]
        self.assertNotIn('continue-on-error', block)
        self.assertIn('--strict', block)
        self.assertIn('--crate "${RUNNER_TEMP}/aether-audit/built-engine"', block)
        self.assertIn('--version 0.22.2', block)

    def test_native_build_hardens_before_locked_compile(self):
        build = (SCRIPTS / 'build-natives.sh').read_text().split('build_aether() {', 1)[1].split('# build_pt:', 1)[0]
        self.assertLess(build.index('patch-engine-dependencies.py'), build.index('cargo ndk'))
        self.assertIn('build --release --locked', build)
        self.assertIn('cp "${crate}/Cargo.lock" "${RUNNER_TEMP}/aether-audit/built-engine/Cargo.lock"', build)

    def test_unreviewed_prerelease(self):
        self.versions('0.11.18-rc.1')
        self.assertNotEqual(self.patch().returncode, 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
