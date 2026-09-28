package studio.cluvex.aether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.core.SignerIdentity

private const val URL_ORIGINAL_GITHUB = "https://github.com/CluvexStudio/Aether"
private const val URL_ORIGINAL_TELEGRAM = "https://t.me/CluvexStudio"
private const val URL_PORT_GITHUB = "https://github.com/QW-AI-Code"

/**
 * Collapsible "About" card.
 *
 * The Android edition / GUI (QW-AI-Code) is credited FIRST with its GitHub link
 * and everything it adds on top of upstream - including the chained
 * `Aether -> Psiphon` transport - and the upstream Aether engine (Cluvex Studio)
 * follows with its own links and feature set.
 */
@Composable
fun AboutPanel(modifier: Modifier = Modifier, startExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "aboutArrow")
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.1.0"
    }

    // 1.2.9-r3: BUILD AUTHENTICITY, in the one place a user looks for "what am I
    // running". The release signing key is committed to this repository (audit
    // F-1) and cannot be rotated without breaking in-place updates for everyone,
    // so the mitigation is to make the identity of a build checkable: the
    // certificate that actually signed this APK, compared against the one the
    // project publishes, plus the APK's own hash to compare with the release page.
    val signerFingerprint = remember { SignerIdentity.fingerprint(context) }
    val signerStatus = remember { SignerIdentity.status(context) }
    // The APK hash means reading 20-70 MB off flash, so it is loaded only when the
    // card is actually opened, on the IO dispatcher, once per process.
    var apkHash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded) {
        if (expanded && apkHash == null) {
            apkHash = withContext(Dispatchers.IO) { SignerIdentity.apkSha256(context) }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.about_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.about_version, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Engine (core) version — same idea as the Windows edition's
                    // About page, which shows app version AND core version so a
                    // user can verify the bundled engine is current.
                    // BuildConfig.CORE_VERSION is stamped at build time from
                    // native/aether/CORE_VERSION, i.e. from whatever
                    // scripts/sync-core.sh actually vendored for THIS build.
                    Text(
                        text = stringResource(R.string.about_core_version, BuildConfig.CORE_VERSION),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 1.2.8-r5 BUILD IDENTITY, visible without opening the log.
                    //
                    // versionName is "1.2.8" for r2, r3, r4 and r5 alike and the
                    // core version is 1.8.0 in all of them, so neither row above
                    // can tell two revisions apart. That is not a cosmetic gap:
                    // the r4 field test was carried out on the r3 build and
                    // nobody could see it. This row is the one that answers
                    // "which build am I actually running", and it turns red when
                    // the engine inside the APK disagrees with the APK.
                    Text(
                        text = stringResource(
                            R.string.about_patch_level,
                            studio.cluvex.aether.core.BuildProvenance.summary(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (studio.cluvex.aether.core.BuildProvenance.enginePatchLevel != null &&
                            !studio.cluvex.aether.core.BuildProvenance.consistent
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

                    // ---- Build authenticity -------------------------------
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.about_signer,
                            SignerIdentity.short(signerFingerprint),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            when (signerStatus) {
                                SignerIdentity.Status.MATCHES -> R.string.about_signer_ok
                                SignerIdentity.Status.MISMATCH -> R.string.about_signer_bad
                                SignerIdentity.Status.UNPINNED -> R.string.about_signer_unpinned
                                SignerIdentity.Status.UNKNOWN -> R.string.about_signer_unknown
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (signerStatus == SignerIdentity.Status.MISMATCH) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = when (signerStatus) {
                            SignerIdentity.Status.MATCHES -> MaterialTheme.colorScheme.primary
                            SignerIdentity.Status.MISMATCH -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = stringResource(
                            R.string.about_apk_hash,
                            apkHash ?: stringResource(R.string.about_apk_hash_loading),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.about_verify_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))

                    // ORDER, deliberately: the author of THIS app (the GUI,
                    // the Android runtime and the chained transports) comes
                    // first, and the upstream engine's author follows. The
                    // reader is holding this edition, so this edition's credit
                    // and its feature list are what the panel opens on.

                    // ---- This Android edition / GUI (QW-AI-Code) ----
                    SectionHeader(
                        title = stringResource(R.string.about_port_title),
                        note = stringResource(R.string.about_port_note),
                    )
                    LinkRow(R.drawable.ic_github, "github.com/QW-AI-Code", URL_PORT_GITHUB)
                    Spacer(Modifier.height(6.dp))
                    FeatureList(stringArrayResource(R.array.about_port_improvements).toList())

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(14.dp))

                    // ---- Upstream engine (Cluvex Studio) ----
                    SectionHeader(
                        title = stringResource(R.string.about_original_title),
                        note = stringResource(R.string.about_original_note),
                    )
                    LinkRow(R.drawable.ic_github, "github.com/CluvexStudio/Aether", URL_ORIGINAL_GITHUB)
                    LinkRow(R.drawable.ic_telegram, "t.me/CluvexStudio", URL_ORIGINAL_TELEGRAM)
                    Spacer(Modifier.height(6.dp))
                    FeatureList(stringArrayResource(R.array.about_original_features).toList())
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, note: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LinkRow(iconRes: Int, label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FeatureList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
