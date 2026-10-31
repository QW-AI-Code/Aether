#!/usr/bin/env python3
"""Offline wiring guards, complementary to production Compose interaction tests.
Plain text fields use their containing SettingsBlock/ConfigDialog topic; OTP inputs
are intentionally never copied to AI. Back/confirm/copy buttons are not settings.
"""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / 'app/src/main/java/studio/cluvex/aether'

def calls(source, names):
    for match in re.finditer(r'\b(' + '|'.join(names) + r')\(', source):
        index, depth, quoted, escaped = match.end(), 1, False, False
        while index < len(source) and depth:
            char = source[index]
            if quoted:
                if escaped: escaped = False
                elif char == '\\': escaped = True
                elif char == '"': quoted = False
            elif char == '"': quoted = True
            elif char == '(': depth += 1
            elif char == ')': depth -= 1
            index += 1
        if depth: raise AssertionError('Unclosed call at ' + str(match.start()))
        yield source[match.end():index-1], source[:match.start()].count('\n') + 1

class AiSettingsCoverage(unittest.TestCase):
    def source(self, path): return (BASE / path).read_text()
    def test_every_semantic_settings_row_has_contextual_help(self):
        names = ['Settings' + kind + 'Row' for kind in ['Nav', 'Switch', 'Choice', 'Value', 'Action', 'Radio']]
        checked = 0
        for path in (BASE / 'ui').rglob('*.kt'):
            if path.name == 'SettingsUi.kt': continue # reusable declarations
            for body, line in calls(path.read_text(), names):
                self.assertIn('aiTopic', body, f'{path.name}:{line} has no AI topic')
                checked += 1
        self.assertGreater(checked, 65)
    def test_scanner_depth_cards_and_manual_limit_are_wired(self):
        source = self.source('ui/settings/CleanIpSettingsPage.kt')
        for topic in ['CLEAN_IP_SCANNER', 'CLEAN_IP_PROFILE', 'CLEAN_IP_FAST',
                      'CLEAN_IP_BALANCED', 'CLEAN_IP_DEEP', 'CLEAN_IP_LIMIT']:
            self.assertIn('AiTopic.' + topic, source)
    def test_safe_context_reaches_prompt_cache_and_followup(self):
        source = self.source('ui/ai/AiCommon.kt')
        self.assertIn('AiSettingContext.read(context, topic)', source)
        self.assertIn('gate, currentValue)', source)
        self.assertIn('persian, currentValue)', source)
        self.assertIn('currentValue = currentValue,', source)
        session = self.source('ai/AiSession.kt')
        self.assertIn('${topic.id}|$value|', session)
        self.assertIn('UI clean-IP scanner (read-only)', session)
    def test_drawer_has_host_and_respects_hint_preference(self):
        source = self.source('ui/HomeScreen.kt')
        self.assertIn('LocalAiHost provides AiHostContext(profile, state, aiSettings.showHints', source)
        self.assertIn('openChat = { route = SettingsRoute.AI_CHAT }', source)
    def test_non_row_submenus_have_help_without_copying_credentials(self):
        source = self.source('ui/SharePanel.kt')
        for topic in ['SHARE_MODE', 'SHARE_AUTH', 'SHARE_WEBRTC']:
            self.assertIn('AiTopic.' + topic, source)
        self.assertIn('AiTopicIcon(topic)', source)
        self.assertIn('AiTopicIcon(AiTopic.PRESETS)', self.source('ui/components/PresetSelector.kt'))
        self.assertIn('AiTopicIcon(AiTopic.BLOCKED_APPS)', self.source('ui/components/AppPickerDialog.kt'))
        self.assertIn('AiTopicIcon(AiTopic.ABOUT)', self.source('ui/AboutPanel.kt'))
        self.assertIn('AiTopicIcon(AiTopic.DIAGNOSTICS)', self.source('ui/components/DiagnosticsPanel.kt'))
        context = self.source('ai/AiSettingContext.kt')
        for secret in ['proxyPassword', 'accessToken', 'apiKey', 'outbound']:
            self.assertNotIn(secret, context)

    def test_ci_collects_both_render_suites_outside_checkout(self):
        source = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn('AETHER_REPAIR_ARTIFACTS: ${{ runner.temp }}/aether-validation/screenshots', source)
        self.assertIn('python3 scripts/test-ai-settings-coverage.py', source)

if __name__ == '__main__': unittest.main(verbosity=2)
