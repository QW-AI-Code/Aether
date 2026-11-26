package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.custom.ChainBuilderPolicy
import studio.cluvex.aether.custom.ChainBuilderPolicy.Core
import studio.cluvex.aether.custom.CustomConfigStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.SegmentedSelector

/*
 * 1.7.0 (same version): Settings -> Connection starts with ONE place that sets
 * the whole connection mode. "Use custom config" (Custom configs page) and the
 * network backend (this page) used to be two separate switches that together
 * decided the chain; now the user picks the number of nodes, then the core of
 * each node, and taps a node to see that core's settings:
 *
 *   Aether  -> protocol (Smart / MASQUE / WireGuard ...), scan, IP version
 *              here, and Transport & anti-DPI (HTTP/2 or HTTP/3, obfuscation)
 *   Psiphon -> exit country (automatic or chosen)
 *   Tor     -> bridges, country, bridge lines, check URL
 *   Config  -> the Custom configs page (servers, auto-select, ping, speed)
 *
 * Only chains the app can actually run are offered (ChainBuilderPolicy).
 */

/**
 * 1.7.0 (same version): a chain with a config node that the user picked before
 * importing / selecting a config. It is SHOWN as chosen (instead of jumping back
 * to the engine-only chain) and switched on by itself as soon as a config exists.
 * Process-wide, so it survives the trip to the Custom configs page.
 */
internal object ChainDraft {
    var pending by androidx.compose.runtime.mutableStateOf<List<Core>?>(null)
}

/** Which node's settings the Connection page shows; "" shows all of them. */
internal const val FOCUS_ALL = ""

private val NodeText = Color(0xFFFFFFFF)
private val NodeMuted = Color(0xFFCBD5E1)
private val NodeAccent = Color(0xFF32E0C4)

@Composable
internal fun coreName(core: Core): String = when (core) {
    Core.CONFIG -> stringResource(R.string.chain_core_config)
    Core.AETHER -> "Aether"
    Core.PSIPHON -> "Psiphon"
    Core.TOR -> "Tor"
}

@Composable
private fun coreSummary(core: Core): String = stringResource(when (core) {
    Core.CONFIG -> R.string.chain_core_config_desc
    Core.AETHER -> R.string.chain_core_aether_desc
    Core.PSIPHON -> R.string.chain_core_psiphon_desc
    Core.TOR -> R.string.chain_core_tor_desc
})

@Composable
internal fun ChainBuilderCard(
    profile: ConnectionProfile,
    cc: CustomConfigStore.State,
    editable: Boolean,
    focus: String,
    onFocus: (String) -> Unit,
    onProfileChange: (ConnectionProfile) -> Unit,
    onOpen: (SettingsRoute) -> Unit,
) {
    val context = LocalContext.current
    val actual = ChainBuilderPolicy.current(profile.backend, cc.enabled && cc.active != null, cc.mode)
    val pending = ChainDraft.pending
    val chain = pending ?: actual

    fun applyChain(next: List<Core>) {
        val target = ChainBuilderPolicy.target(next) ?: return
        target.backend?.let { if (it != profile.backend) onProfileChange(profile.copy(backend = it)) }
        target.mode?.let { CustomConfigStore.setMode(context, it) }
        CustomConfigStore.setEnabled(context, target.customEnabled && cc.active != null)
        // No config yet: keep the choice on screen; it switches on when one exists.
        ChainDraft.pending = if (target.customEnabled && cc.active == null) next else null
    }

    // A config was added / selected meanwhile: the drafted chain becomes the real one.
    androidx.compose.runtime.LaunchedEffect(cc.active?.id, pending) {
        val draft = ChainDraft.pending
        if (draft != null && cc.active != null) {
            ChainBuilderPolicy.target(draft)?.mode?.let { CustomConfigStore.setMode(context, it) }
            CustomConfigStore.setEnabled(context, true)
            ChainDraft.pending = null
        }
    }

    GroupCaption(stringResource(R.string.chain_builder_title), aiTopic = AiTopic.BACKEND)
    SettingsGroup {
        // The chain as a diagram: device -> 1 -> 2 -> 3 -> internet.
        ChainDiagram(chain)
        RowDivider(inset = false)
        SettingsBlock(title = stringResource(R.string.chain_node_count), aiTopic = AiTopic.BACKEND) {
            SegmentedSelector(
                options = listOf(1, 2, 3),
                selected = chain.size,
                onSelect = { n ->
                    if (n != chain.size) {
                        (ChainBuilderPolicy.complete(chain.take(1), n, chain)
                            ?: ChainBuilderPolicy.complete(emptyList(), n, chain))?.let { applyChain(it) }
                    }
                },
                label = { n -> stringResource(R.string.chain_nodes_n, n) },
                enabled = editable,
            )
            if (ChainBuilderPolicy.lengthNeedsConfig(chain.size)) {
                Text(stringResource(R.string.chain_three_needs_config), color = NodeMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        chain.forEachIndexed { slot, core ->
            RowDivider(inset = false)
            SettingsChoiceRow(
                title = stringResource(R.string.chain_node_title, slot + 1),
                icon = if (core == Core.CONFIG) Icons.Rounded.VpnKey else Icons.Rounded.Layers,
                options = ChainBuilderPolicy.options(chain, chain.size, slot),
                selected = core,
                label = { coreName(it) },
                optionDescription = { coreSummary(it) },
                onSelect = { picked ->
                    if (picked != core) {
                        ChainBuilderPolicy.complete(chain.take(slot) + picked, chain.size, chain)?.let { applyChain(it) }
                    }
                },
                summary = stringResource(when {
                    chain.size == 1 -> R.string.cd_role_single
                    slot == 0 -> R.string.cd_role_first
                    slot == chain.lastIndex -> R.string.cd_role_exit
                    else -> R.string.cd_role_middle
                }),
                enabled = editable,
                aiTopic = AiTopic.BACKEND,
            )
            SettingsNavRow(
                title = stringResource(R.string.chain_node_settings, coreName(core)),
                icon = Icons.Rounded.Tune,
                aiTopic = AiTopic.BACKEND,
                value = if (focus == core.name) stringResource(R.string.chain_node_shown) else null,
                onClick = {
                    if (core == Core.CONFIG) onOpen(SettingsRoute.CUSTOM_CONFIGS)
                    else onFocus(if (focus == core.name) FOCUS_ALL else core.name)
                },
            )
        }
        if (Core.CONFIG in chain && cc.active == null) {
            RowDivider(inset = false)
            SettingsNoticeRow(text = stringResource(R.string.chain_need_config), icon = Icons.Rounded.Info)
            SettingsNavRow(
                title = stringResource(R.string.cc_title),
                icon = Icons.Rounded.VpnKey,
                aiTopic = AiTopic.CUSTOM_CONFIGS,
                onClick = { onOpen(SettingsRoute.CUSTOM_CONFIGS) },
            )
        }
        if (focus == Core.AETHER.name) {
            RowDivider(inset = false)
            SettingsNavRow(
                title = stringResource(R.string.chain_aether_transport),
                summary = stringResource(R.string.chain_aether_transport_desc),
                icon = Icons.Rounded.Route,
                aiTopic = AiTopic.BACKEND,
                onClick = { onOpen(SettingsRoute.TRANSPORT) },
            )
        }
        if (focus != FOCUS_ALL) {
            RowDivider(inset = false)
            SettingsActionRow(
                title = stringResource(R.string.chain_show_all),
                icon = Icons.Rounded.Tune,
                aiTopic = AiTopic.BACKEND,
                onClick = { onFocus(FOCUS_ALL) },
            )
        }
    }
    GroupFooter(stringResource(R.string.chain_builder_footer))
}

@Composable
private fun ChainDiagram(chain: List<Core>) {
    // 1.7.0 (same version): always ONE line; scaled down to the width it gets.
    studio.cluvex.aether.ui.components.FitToWidth(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDCF1", fontSize = 16.sp)
                chain.forEachIndexed { i, core ->
                    Text(" \u2192 ", color = NodeMuted, fontSize = 13.sp)
                    Box(Modifier.size(20.dp).background(NodeAccent.copy(alpha = .35f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = NodeText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.background(Color(0xFF1E2B4A), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(coreName(core), color = NodeText, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false)
                    }
                }
                Text(" \u2192 ", color = NodeMuted, fontSize = 13.sp)
                Text("\uD83C\uDF10", fontSize = 16.sp)
            }
        }
    }
}
