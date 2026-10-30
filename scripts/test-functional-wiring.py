#!/usr/bin/env python3
"""Offline guards for service/UI/persistence wiring that host unit tests cannot instantiate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / 'app/src/main/java/studio/cluvex/aether'

class FunctionalWiring(unittest.TestCase):
    def source(self, relative):
        return (BASE / relative).read_text()

    def test_ip_service_owned_and_actual_endpoint(self):
        service = self.source('vpn/AetherVpnService.kt')
        self.assertRegex(service, r'TunnelExitMonitor\.watch\(\s*AetherController\.state')
        self.assertIn('fetch = { endpoint -> fetchTunnelExit(endpoint.host, endpoint.port) }', service)
        fetch = service.split('private suspend fun fetchTunnelExit(', 1)[1].split('private fun onExitEnriched(', 1)[0]
        self.assertRegex(fetch, r'NetProbe\.fetchIpInfoViaSocksRaced\(\s*host,\s*port,')
        self.assertIn('onEnriched = { enriched -> onExitEnriched(epoch, published.get(), enriched) }', fetch)
        self.assertIn('alternateIp = first.alternateIp', fetch)
        activity = self.source('MainActivity.kt')
        self.assertNotIn('withTimeoutOrNull(100_000L)', activity)
        self.assertNotIn('fetchIpInfoViaSocksWithRetry(', activity)

    def test_immediate_loading_precedes_service_start(self):
        controller = self.source('core/AetherController.kt').split('fun connect(', 1)[1].split('fun disconnect(', 1)[0]
        self.assertLess(controller.index('setState(ConnectionState.Launching)'), controller.index('startForegroundService'))
        service = self.source('vpn/AetherVpnService.kt').split('if (custom != null && CustomConfigStore.state.value.autoSelect)', 1)[1]
        self.assertLess(service.index('ConnectionState.Selecting'), service.index('chooseBestCustom('))
        self.assertIn('ConnectionState.Selecting -> stringResource(R.string.state_selecting)', self.source('ui/HomeScreen.kt'))

    def test_verification_persists_and_has_ordinary_setting(self):
        store = self.source('data/ProfileStore.kt')
        self.assertIn('verifyConnection = prefs[Keys.verifyConnection] ?: true', store)
        self.assertIn('prefs[Keys.verifyConnection] = profile.verifyConnection', store)
        self.assertIn('profile.copy(verifyConnection = it)', self.source('ui/settings/SettingsScreen.kt'))
        service = self.source('vpn/AetherVpnService.kt')
        self.assertIn('VerificationPolicy.enabled(profile', service)
        self.assertIn('verifyFinal(profile, sessionExitPort.takeIf', service)

    def test_capsules_are_bounded_keyed_and_technical_text_is_ltr(self):
        source = self.source('ui/settings/ConfigDataTable.kt')
        self.assertIn('heightIn(max = 560.dp)', source)
        self.assertIn('itemsIndexed(rows, key = { _, row -> row.key })', source)
        self.assertIn('RoundedCornerShape(24.dp)', source)
        self.assertIn('LocalLayoutDirection provides LayoutDirection.Ltr', source)
        self.assertIn('textDirection = TextDirection.Ltr', source)
        self.assertIn('textAlign = TextAlign.Left', source)
        self.assertIn('TextOverflow.Ellipsis', source)
        self.assertNotIn('MetadataHeader', source)
        self.assertNotIn('horizontalScroll', source)
        page = self.source('ui/settings/CustomConfigsPage.kt')
        self.assertIn('R.string.cc_ping_all_short', page)
        self.assertNotIn('R.string.cc_select', page)
        self.assertNotIn('GroupFooter(stringResource(R.string.cc_table_footer))', page)
        self.assertNotIn('title = stringResource(R.string.cc_ping_all)', page)
        strip = self.source('ui/settings/ConfigTabStrip.kt')
        self.assertIn('FilledIconButton(onClick = onNew', strip)
        self.assertNotIn('Icons.Rounded.Close', strip)
        self.assertNotIn('onDelete', strip)
        self.assertIn('Icons.Rounded.Edit', strip)

    def test_tab_projection_and_password_destination(self):
        source = self.source('ui/settings/CustomConfigsPage.kt')
        self.assertIn('ConfigCatalog.rows(st, effectiveTab, pings = pings, pingSort = pingSort)', source)
        self.assertIn('checked = pingSort == ConfigCatalog.PingSort.DESC', source)
        self.assertIn('runImport(password, p.destination, p.retry)', source)
        self.assertIn('importDestination = ConfigCatalog.groupFor(effectiveTab)', source)
        self.assertNotIn('private fun ConfigRow(', source)
        self.assertNotIn('subscriptionsView', source)
        self.assertIn('key(effectiveTab)', source)

    def test_chain_exit_port_and_carrier_policy(self):
        service = self.source('vpn/AetherVpnService.kt')
        self.assertIn('CustomChainPolicy.engineProfile', service)
        self.assertIn('sessionExitPort = exitPort', service)
        self.assertIn('probeTunnelCycle(sessionExitPort.takeIf', service)
        self.assertIn('ConnectionState.Connected("$SOCKS_HOST:${sessionExitPort.takeIf', service)

    def test_fixed_controls_no_filter_and_no_old_onboarding(self):
        table = self.source('ui/settings/ConfigDataTable.kt')
        self.assertIn('PingChip(pings[c.id])', table)
        self.assertIn('IconButton(enabled = !testingAll', table)
        for status in ['OFFLINE', 'BLOCKED', 'NO_REPLY', 'INVALID', 'NO_NETWORK']:
            self.assertIn('ConfigPing.PingStatus.' + status, table)
        page = self.source('ui/settings/CustomConfigsPage.kt')
        self.assertNotIn('listFilter', page)
        self.assertNotIn('subscriptionEditor', page)
        self.assertIn('renameAutomaticTab(context, tabKey, name)', page)
        self.assertIn('ConfigTabStrip(tabs, effectiveTab', page)
        self.assertIn('key(tab.key)', self.source('ui/settings/ConfigTabStrip.kt'))
        self.assertNotIn('ScrollableTabRow', page)
        activity = self.source('MainActivity.kt')
        self.assertNotIn('Onboarding', activity)
        self.assertIn('WhatsNewGate()', activity)

    def test_scanner_is_independent_and_has_ordinary_settings(self):
        page = self.source('ui/settings/SettingsScreen.kt')
        self.assertIn('SettingsRoute.CLEAN_IP_SETTINGS', page)
        self.assertIn('CleanIpSettingsPage(editable, back, modifier)', page)

    def test_sounds_are_service_owned_and_optional(self):
        self.assertIn('ConnectionSounds.onState(newState)', self.source('core/AetherController.kt'))
        self.assertIn('ConnectionSounds.attach(this)', self.source('AetherApp.kt'))
        self.assertIn('.getBoolean("enabled", true)', self.source('core/ConnectionSounds.kt'))
        self.assertIn('AudioManager.RINGER_MODE_NORMAL', self.source('core/ConnectionSounds.kt'))

    def test_custom_menu_cards_and_contextual_ai(self):
        page = self.source('ui/settings/CustomConfigsPage.kt')
        self.assertIn('SettingsMenuAction(', page)
        self.assertIn('ConfigDialog(', page)
        for topic in ['CUSTOM_CONFIGS', 'CUSTOM_CHAIN', 'CUSTOM_PING', 'CUSTOM_VERIFY']:
            self.assertIn('AiTopic.' + topic, page)
        helper = self.source('ui/settings/ConfigMenuUi.kt')
        self.assertIn('AiTopicIcon(topic)', helper)
        self.assertIn('heightIn(max = maxHeight)', helper)
        self.assertIn('BorderStroke', helper)

    def test_custom_tcp_native_policy_and_local_socks_readiness(self):
        service = self.source('vpn/AetherVpnService.kt')
        self.assertIn('CustomChainPolicy.runtimeEnvironment(profile, customSelection?.mode)', service)
        self.assertIn('NetProbe.checkSocksHandshake(SOCKS_HOST, port, 2_000)', service)
        prober = (ROOT / 'native/aether/aether/src/prober.rs').read_text()
        self.assertIn('AETHER_CUSTOM_TCP_UPSTREAM', prober)
        self.assertIn('tune_custom_tcp_scan(&mut st, custom_tcp)', prober)
        self.assertIn('&& !crate::masque_h2::enabled()', prober)

    def test_ip_race_publishes_before_country_and_ai_uses_final_endpoint(self):
        probe = self.source('core/NetProbe.kt').split('fun fetchIpInfoViaSocksRaced(',1)[1].split('private val geoPool',1)[0]
        self.assertNotIn('refineCountry(', probe)
        self.assertIn('winner.compareAndSet(null, info)', probe)
        session = self.source('ai/AiSession.kt')
        self.assertNotIn('AiAvailability.socksPort(profile.backend)', session)
        self.assertIn('AiAvailability.socksPort(profile.backend, state)', session)

    def test_exit_enrichment_checks_epoch_ip_and_tunnel(self):
        service = self.source('vpn/AetherVpnService.kt')
        merge = service.split('private fun applyExitEnrichment(', 1)[1].split('override fun attachBaseContext(', 1)[0]
        self.assertIn('if (epoch != now)', merge)
        self.assertIn('current == null || !current.viaTunnel', merge)
        self.assertIn('current.ip != ip', merge)
        self.assertIn('AetherController.setIpInfo(merged)', merge)
        self.assertIn('?: current.alternateIp', merge)

    def test_custom_upstream_scan_has_bounded_turbo_and_fallback(self):
        service = self.source('vpn/AetherVpnService.kt')
        plan = service.split('private fun customUpstreamPlan(', 1)[1].split('private fun upstreamOf(', 1)[0]
        self.assertIn('behindConfig.copy(scanMode = ScanMode.TURBO)', plan)
        self.assertIn('fullBudget.coerceAtMost(UPSTREAM_QUICK_PASS_MAX_MS)', plan)
        self.assertIn('if (profile.scanMode == ScanMode.TURBO)', plan)
        self.assertIn('customUpstreamPlan(profile)', service)

    def test_native_teardown_requires_reason_and_emits_trace(self):
        service = self.source('vpn/AetherVpnService.kt')
        self.assertNotIn('cleanupNativeOnly()', service)
        self.assertIn('private fun cleanupNativeOnly(reason: String)', service)
        self.assertIn('sessionEpoch.incrementAndGet()', service)
        self.assertIn('"cleanup-native"', service)
        self.assertIn('private object ChainTrace', service)
        self.assertIn('"engine-fail"', service)
        self.assertIn('"ladder-cancelled"', service)

    def test_version_unchanged(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('versionName = "1.6.0"', gradle)
        self.assertIn('versionCode = 19', gradle)
        self.assertEqual('1.6.0', (ROOT / 'PATCHLEVEL').read_text().strip())

if __name__ == '__main__':
    unittest.main(verbosity=2)
