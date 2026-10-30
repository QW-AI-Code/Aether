#!/usr/bin/env python3
"""Fails if automatic engine (core) updates from GitHub come back."""
import pathlib, re, subprocess, unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent


class CoreSyncDisabled(unittest.TestCase):
    def read(self, rel):
        return (ROOT / rel).read_text()

    def test_workflow_does_not_sync_or_commit_core(self):
        wf = self.read('.github/workflows/build.yml')
        code = "\n".join(l for l in wf.splitlines() if not l.lstrip().startswith('#'))
        for bad in ('sync-core.sh', 'core-rollback', '.core-prev', 'core-sync-state',
                    'Commit core upgrade', 'AETHER_FORCE_CLONE'):
            self.assertNotIn(bad, code)

    def test_no_scheduled_trigger(self):
        wf = self.read('.github/workflows/build.yml')
        self.assertNotRegex(wf, r'(?m)^\s*schedule:')

    def test_sync_script_is_noop_stub(self):
        r = subprocess.run(['bash', str(ROOT / 'scripts/sync-core.sh')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0)
        self.assertIn('DISABLED', r.stdout)
        self.assertLess(len(self.read('scripts/sync-core.sh').splitlines()), 20)

    def test_fetch_natives_never_clones_engine(self):
        s = self.read('scripts/fetch-natives.sh')
        seg = s.split('Providing Aether engine source', 1)[1].split('The Aether binary crate', 1)[0]
        self.assertNotIn('clone_repo', seg)
        self.assertIn('refusing to download', seg)

    def test_core_version_is_vendored_240(self):
        self.assertEqual(self.read('native/aether/CORE_VERSION').strip(), '2.4.0')

    def test_app_version_unchanged(self):
        self.assertEqual(self.read('PATCHLEVEL').strip(), '1.6.0')
        g = self.read('app/build.gradle.kts')
        self.assertRegex(g, r'versionName\s*=\s*"1\.6\.0"')


if __name__ == '__main__':
    unittest.main(verbosity=2)
