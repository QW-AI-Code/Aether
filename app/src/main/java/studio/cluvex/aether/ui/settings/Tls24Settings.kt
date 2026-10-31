package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Tls24Options
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.SegmentedSelector

/**
 * Engine core 2.4.0 "TLS fingerprint & API" block of "Advanced core settings".
 *
 * UI only. The values are stored in [ConnectionProfile], validated by
 * [Tls24Options] and reach the engine through `ConnectionProfile.toEnv()`
 * exactly like the options that were already here (ciphers, GREASE, verify,
 * API address). The layout, field order, placeholders and defaults follow the
 * Windows build's `tls` view (src/views/advanced.js): Server names, Certificate
 * checks, TLS fingerprint. Every empty field / off switch means "engine
 * default" and sends nothing.
 */
fun LazyListScope.tls24Sections(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
) {
    // ---------------------------------------------------------- Server names
    settingsSection {
        GroupCaption(stringResource(R.string.tls24_names_caption))
        SettingsGroup {
            Tls24TextBlock(
                value = profile.masqueSni,
                onValueChange = { onProfileChange(profile.copy(masqueSni = it.trim())) },
                enabled = editable,
                isError = Tls24Options.invalid(profile.masqueSni, Tls24Options.masqueSni(profile.masqueSni)),
                label = stringResource(R.string.masque_sni_label),
                placeholder = "www.cloudflare.com",
                help = stringResource(R.string.masque_sni_help),
                aiTopic = AiTopic.MASQUE_SNI,
            )
            RowDivider(inset = false)
            SettingsBlock(aiTopic = AiTopic.API_ADDRESS) {
                LtrOutlinedTextField(
                    value = profile.apiAddress,
                    onValueChange = { onProfileChange(profile.copy(apiAddress = it.trim())) },
                    enabled = editable,
                    singleLine = true,
                    isError = profile.apiAddress.isNotBlank() &&
                        profile.sanitizedApiAddress() == null,
                    label = { Text(stringResource(R.string.api_address_label)) },
                    placeholder = { Text("203.0.113.5:443") },
                    supportingText = { Text(stringResource(R.string.api_address_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            Tls24TextBlock(
                value = profile.apiSni,
                onValueChange = { onProfileChange(profile.copy(apiSni = it.trim())) },
                enabled = editable,
                isError = Tls24Options.invalid(profile.apiSni, Tls24Options.apiSni(profile.apiSni)),
                label = stringResource(R.string.api_sni_label),
                placeholder = "example.com",
                help = stringResource(R.string.api_sni_help),
                aiTopic = AiTopic.API_SNI,
            )
            RowDivider(inset = false)
            Tls24TextBlock(
                value = profile.apiHost,
                onValueChange = { onProfileChange(profile.copy(apiHost = it.trim())) },
                enabled = editable,
                isError = Tls24Options.invalid(profile.apiHost, Tls24Options.apiHost(profile.apiHost)),
                label = stringResource(R.string.api_host_label),
                placeholder = "api.cloudflareclient.com",
                help = stringResource(R.string.api_host_help),
                aiTopic = AiTopic.API_HOST,
            )
        }
    }

    // ------------------------------------------------------ Certificate checks
    settingsSection {
        GroupCaption(stringResource(R.string.tls24_cert_caption))
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.tls_verify_title),
                summary = stringResource(R.string.tls_verify_desc),
                checked = profile.tlsVerify,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(tlsVerify = it)) },
                aiTopic = AiTopic.TLS_VERIFY,
            )
            RowDivider(inset = false)
            Tls24TextBlock(
                value = profile.tlsTrustAnchors,
                onValueChange = { onProfileChange(profile.copy(tlsTrustAnchors = it.trim())) },
                enabled = editable,
                isError = Tls24Options.invalid(profile.tlsTrustAnchors, Tls24Options.trustAnchors(profile.tlsTrustAnchors)),
                label = stringResource(R.string.trust_anchors_label),
                placeholder = "11129.9.1:11129.9.4",
                help = stringResource(R.string.trust_anchors_help),
                aiTopic = AiTopic.TLS_TRUST_ANCHORS,
            )
            RowDivider(inset = false)
            SettingsBlock(title = stringResource(R.string.cert_compression_label), aiTopic = AiTopic.TLS_CERT_COMPRESSION) {
                // "" = off, otherwise brotli / zlib / zstd (a value outside this list shows as off).
                val options = listOf("") + Tls24Options.CERT_COMPRESSION
                SegmentedSelector(
                    options = options,
                    selected = profile.tlsCertCompression.takeIf { it in options } ?: "",
                    onSelect = { onProfileChange(profile.copy(tlsCertCompression = it)) },
                    label = { if (it.isEmpty()) stringResource(R.string.tls24_off) else it },
                    enabled = editable,
                )
            }
        }
    }

    // ------------------------------------------------------- TLS fingerprint
    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_tls), aiTopic = AiTopic.TLS_CIPHERS)
        SettingsGroup {
            SettingsBlock(aiTopic = AiTopic.TLS_CIPHERS) {
                LtrOutlinedTextField(
                    value = profile.tlsCiphers,
                    onValueChange = { onProfileChange(profile.copy(tlsCiphers = it.trim())) },
                    enabled = editable,
                    singleLine = true,
                    isError = profile.tlsCiphers.isNotBlank() &&
                        profile.sanitizedTlsCiphers() == null,
                    label = { Text(stringResource(R.string.tls_ciphers_label)) },
                    placeholder = { Text(ConnectionProfile.DEFAULT_TLS_CIPHERS) },
                    supportingText = { Text(stringResource(R.string.tls_ciphers_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            Tls24TextBlock(
                value = profile.tls13Ciphers,
                onValueChange = { onProfileChange(profile.copy(tls13Ciphers = it.trim())) },
                enabled = editable,
                isError = Tls24Options.invalid(profile.tls13Ciphers, Tls24Options.tls13Ciphers(profile.tls13Ciphers)),
                label = stringResource(R.string.tls13_ciphers_label),
                placeholder = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256",
                help = stringResource(R.string.tls13_ciphers_help),
                aiTopic = AiTopic.TLS13_CIPHERS,
            )
            RowDivider(inset = false)
            Tls24TextBlock(
                value = profile.tlsKeyShares,
                onValueChange = { onProfileChange(profile.copy(tlsKeyShares = it.trim())) },
                enabled = editable,
                isError = Tls24Options.invalid(profile.tlsKeyShares, Tls24Options.keyShares(profile.tlsKeyShares)),
                label = stringResource(R.string.key_shares_label),
                placeholder = "X25519MLKEM768:X25519",
                help = stringResource(R.string.key_shares_help),
                aiTopic = AiTopic.TLS_KEY_SHARES,
            )
            RowDivider(inset = false)
            Tls24TextBlock(
                value = profile.tlsSigalgs,
                onValueChange = { onProfileChange(profile.copy(tlsSigalgs = it.trim())) },
                enabled = editable,
                isError = Tls24Options.invalid(profile.tlsSigalgs, Tls24Options.sigalgs(profile.tlsSigalgs)),
                label = stringResource(R.string.sigalgs_label),
                placeholder = "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256",
                help = stringResource(R.string.sigalgs_help),
                aiTopic = AiTopic.TLS_SIGALGS,
            )
            RowDivider(inset = false)
            SettingsBlock(title = stringResource(R.string.alps_label), aiTopic = AiTopic.TLS_ALPS) {
                // "" = off, "new" or "old".
                val options = listOf("") + Tls24Options.ALPS
                SegmentedSelector(
                    options = options,
                    selected = profile.tlsAlps.takeIf { it in options } ?: "",
                    onSelect = { onProfileChange(profile.copy(tlsAlps = it)) },
                    label = {
                        when (it) {
                            "new" -> stringResource(R.string.alps_new)
                            "old" -> stringResource(R.string.alps_old)
                            else -> stringResource(R.string.tls24_off)
                        }
                    },
                    enabled = editable,
                )
            }
            RowDivider(inset = false)
            SettingsBlock(aiTopic = AiTopic.TLS_SERVER_PADDING) {
                LtrOutlinedTextField(
                    value = profile.tlsServerPadding,
                    // Digits only, like a number field; 0..65535 is checked by Tls24Options.
                    onValueChange = {
                        onProfileChange(profile.copy(tlsServerPadding = it.filter(Char::isDigit).take(5)))
                    },
                    enabled = editable,
                    singleLine = true,
                    isError = Tls24Options.invalid(profile.tlsServerPadding, Tls24Options.serverPadding(profile.tlsServerPadding)),
                    keyboardType = KeyboardType.Number,
                    label = { Text(stringResource(R.string.server_padding_label)) },
                    placeholder = { Text("6000") },
                    supportingText = { Text(stringResource(R.string.server_padding_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.disable_grease_title),
                summary = stringResource(R.string.disable_grease_desc),
                checked = profile.disableGrease,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(disableGrease = it)) },
                aiTopic = AiTopic.DISABLE_GREASE,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.grease_sigalgs_title),
                summary = stringResource(R.string.grease_sigalgs_desc),
                checked = profile.enableGreaseSigalgs,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(enableGreaseSigalgs = it)) },
                aiTopic = AiTopic.GREASE_SIGALGS,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.ech_grease_title),
                summary = stringResource(R.string.ech_grease_desc),
                checked = profile.enableEchGrease,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(enableEchGrease = it)) },
                aiTopic = AiTopic.ECH_GREASE,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.no_ocsp_title),
                summary = stringResource(R.string.no_ocsp_desc),
                checked = profile.disableOcspStapling,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(disableOcspStapling = it)) },
                aiTopic = AiTopic.DISABLE_OCSP_STAPLING,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.no_sct_title),
                summary = stringResource(R.string.no_sct_desc),
                checked = profile.disableSct,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(disableSct = it)) },
                aiTopic = AiTopic.DISABLE_SCT,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.no_ticket_title),
                summary = stringResource(R.string.no_ticket_desc),
                checked = profile.disableSessionTicket,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(disableSessionTicket = it)) },
                aiTopic = AiTopic.DISABLE_SESSION_TICKET,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.no_perm_title),
                summary = stringResource(R.string.no_perm_desc),
                checked = profile.disableExtensionPermutation,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(disableExtensionPermutation = it)) },
                aiTopic = AiTopic.DISABLE_EXTENSION_PERMUTATION,
            )
        }
        GroupFooter(stringResource(R.string.tls_group_footer))
    }
}

/** A labelled single-line technical text field with the 2.4.0 help line below it. */
@androidx.compose.runtime.Composable
private fun Tls24TextBlock(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    label: String,
    placeholder: String,
    help: String,
    aiTopic: AiTopic,
) {
    SettingsBlock(aiTopic = aiTopic) {
        LtrOutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            isError = isError,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            supportingText = { Text(help) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
