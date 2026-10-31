package studio.cluvex.aether.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.ui.ai.AiTopicIcon
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.custom.ConfigCatalog
import studio.cluvex.aether.custom.tabServerNumbers
import studio.cluvex.aether.custom.ChainMode
import studio.cluvex.aether.custom.ConfigFormat
import studio.cluvex.aether.custom.ConfigImporter
import studio.cluvex.aether.custom.ConfigParser
import studio.cluvex.aether.custom.ConfigPing
import studio.cluvex.aether.custom.ConfigSpeedTest
import studio.cluvex.aether.custom.ConfigPingStore
import studio.cluvex.aether.custom.ConfigSource
import studio.cluvex.aether.custom.CustomConfig
import studio.cluvex.aether.custom.CustomConfigStore
import studio.cluvex.aether.custom.NpvtPasswordException
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.theme.OnDarkDim
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/** An import that stopped at a NapsternetV password prompt, and how to retry it. */
private class PendingImport(
    val wrong: Boolean,
    val destination: String?,
    val retry: suspend (String?) -> List<CustomConfig>,
)

/**
 * 1.5.0: Settings -> Custom configs.
 *
 * Import (link / text / subscription, file, camera QR, QR in an image), the
 * saved list (select, edit, rename, delete), the master switch and the chain the
 * selected config runs in. Everything here only takes effect on the next
 * connect, which is why - like every engine setting - it is read-only while a
 * session is up.
 */
@Composable
internal fun CustomConfigsPage(
    editable: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CustomConfigStore.load(context) } }
    val st by CustomConfigStore.state.collectAsState()
    val pings by ConfigPingStore.results.collectAsState()
    val testingAll by ConfigPingStore.testingAll.collectAsState()
    var speedFor by remember { mutableStateOf<CustomConfig?>(null) }
    val scanState by studio.cluvex.aether.scanner.CleanIpScanManager.state.collectAsState()
    var pingSort by rememberSaveable { mutableStateOf(ConfigCatalog.PingSort.ASC) }

    var busy by remember { mutableStateOf(false) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var actionsFor by remember { mutableStateOf<CustomConfig?>(null) }
    var editing by remember { mutableStateOf<CustomConfig?>(null) }
    var renaming by remember { mutableStateOf<CustomConfig?>(null) }
    var deleting by remember { mutableStateOf<CustomConfig?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val selectedTab = st.activeTab
    fun selectTab(key: String) {
        try { CustomConfigStore.setActiveTab(context, key) }
        catch (e: RuntimeException) { Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show() }
    }
    var importDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var actionsForSubscription by remember { mutableStateOf<CustomConfigStore.Subscription?>(null) }
    var moveSubscription by remember { mutableStateOf<CustomConfigStore.Subscription?>(null) }
    var deleteSubscription by remember { mutableStateOf<CustomConfigStore.Subscription?>(null) }
    var deletingTab by remember { mutableStateOf<String?>(null) }
    var groupEditor by remember { mutableStateOf(false) }
    var groupToEdit by remember { mutableStateOf<CustomConfigStore.Group?>(null) }
    var moveConfig by remember { mutableStateOf<CustomConfig?>(null) }
    var automaticTabEditor by remember { mutableStateOf<String?>(null) }
    var automaticTabNaming by remember { mutableStateOf<String?>(null) }
    var subscriptionPassword by remember { mutableStateOf<CustomConfigStore.Subscription?>(null) }


    val importedMsg = stringResource(R.string.cc_imported)
    val failedMsg = stringResource(R.string.cc_import_failed)
    val noQrMsg = stringResource(R.string.cc_qr_none)
    val clipboardEmptyMsg = stringResource(R.string.cc_paste_empty)

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun runImport(password: String? = null, destination: String? = importDestination, task: suspend (String?) -> List<CustomConfig>) {
        busy = true
        scope.launch {
            try {
                val list = task(password)
                withContext(Dispatchers.IO) { CustomConfigStore.add(context, list.map { it.copy(groupId = destination) }) }
                toast(String.format(importedMsg, list.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NpvtPasswordException) {
                pending = PendingImport(e.wrong, destination, task)
            } catch (e: Exception) {
                toast(String.format(failedMsg, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    fun refresh(sub: CustomConfigStore.Subscription, password: String? = null) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CustomConfigStore.refreshSubscription(context, sub, password = password)
                }
                toast(context.getString(R.string.cc_sub_updated))
            } catch (e: CancellationException) { throw e
            } catch (e: NpvtPasswordException) { subscriptionPassword = sub
            } catch (e: Exception) { toast(String.format(failedMsg, e.message ?: e.javaClass.simpleName))
            } finally { busy = false }
        }
    }

    fun importText(text: String, source: ConfigSource) {
        val input = ConfigParser.cleanInput(text)
        if (input.lines().size == 1 && (input.startsWith("https://") || input.startsWith("http://"))) {
            val existing = st.subscriptions.firstOrNull { it.url == input }
            val name = runCatching { java.net.URI(input).host }.getOrNull() ?: "Subscription"
            if (existing != null && existing.groupId != importDestination) {
                try { CustomConfigStore.moveSubscription(context, existing.id, importDestination) }
                catch (e: IllegalArgumentException) { toast(e.message.orEmpty()); return }
            }
            refresh(existing?.copy(groupId = importDestination) ?: CustomConfigStore.Subscription(
                java.util.UUID.randomUUID().toString(), name, input, groupId = importDestination))
        } else runImport { p -> ConfigImporter.fromText(context, input, source, p) }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runImport { p -> ConfigImporter.fromFile(context, uri, p) }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) runImport { p -> ConfigImporter.fromImage(context, uri, p) }
    }
    val scanQr = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrBlank()) {
            if (result.originalIntent != null) toast(noQrMsg)
        } else {
            importText(text, ConfigSource.QR)
        }
    }
    val scanPrompt = stringResource(R.string.cc_scan_prompt)

    val active = st.active
    val chainName = active?.chainLabel ?: stringResource(R.string.cc_config_word)

    val tabs = ConfigCatalog.tabs(st, stringResource(R.string.cc_tab_all))
    val effectiveTab = selectedTab
    val visibleRows = ConfigCatalog.rows(st, effectiveTab, pings = pings, pingSort = pingSort)
    val visibleConfigs = visibleRows.mapNotNull { it.config }
    // Server numbers (S1, S2, ...) are dense and per tab: a tab with N servers always
    // shows S1..SN. They follow the stored order, never the row position, so sorting
    // by ping moves a row but keeps its S-number; deleting a server closes the gap.
    val serverNumbers = remember(st.configs, effectiveTab, visibleConfigs.map { it.id }) {
        val inTab = visibleConfigs.map { it.id }.toSet()
        tabServerNumbers(st.configs.map { it.id }.filter { it in inTab })
    }

    Box(modifier.fillMaxSize()) {
    SettingsScaffold(stringResource(R.string.cc_title), onBack, Modifier.fillMaxSize()) {
        if (!editable) {
            settingsSection {
            GroupCaption(stringResource(R.string.cc_title), aiTopic = AiTopic.CUSTOM_CONFIGS)
                SettingsGroup {
                    SettingsNoticeRow(
                        text = stringResource(R.string.settings_locked_hint),
                        icon = Icons.Rounded.Info,
                    )
                }
            }
        }

        settingsSection {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.cc_enable), aiTopic = AiTopic.CUSTOM_CONFIGS,
                    summary = if (active == null) {
                        stringResource(R.string.cc_enable_need)
                    } else {
                        stringResource(R.string.cc_enable_desc, active.name)
                    },
                    icon = Icons.Rounded.VpnKey,
                    checked = st.enabled && active != null,
                    enabled = editable && (active != null || st.enabled),
                    onCheckedChange = { on -> CustomConfigStore.setEnabled(context, on) },
                )
                RowDivider()
                SettingsChoiceRow(
                    title = stringResource(R.string.cc_chain_title), aiTopic = AiTopic.CUSTOM_CHAIN,
                    summary = stringResource(R.string.cc_chain_desc),
                    icon = Icons.Rounded.Route,
                    options = ChainMode.entries.toList(),
                    selected = st.mode,
                    label = { it.label(chainName) },
                    optionDescription = { hopsLabel(it) },
                    enabled = editable,
                    onSelect = { CustomConfigStore.setMode(context, it) },
                )
            }
            GroupFooter(stringResource(R.string.cc_chain_footer))
        }

        settingsSection {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.cc_verify), aiTopic = AiTopic.CUSTOM_VERIFY,
                    summary = stringResource(R.string.cc_verify_desc),
                    icon = Icons.Rounded.Info, checked = st.verifyConnection, enabled = editable,
                    onCheckedChange = { CustomConfigStore.setVerify(context, it) },
                )
                // 1.6.0: same inset divider as the Enable/Chain card above.
                RowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.cc_auto), aiTopic = AiTopic.CUSTOM_PING,
                    summary = stringResource(R.string.cc_auto_desc),
                    icon = Icons.Rounded.Speed, checked = st.autoSelect, enabled = editable,
                    onCheckedChange = { CustomConfigStore.setAutoSelect(context, it) },
                )
                RowDivider()
                SettingsChoiceRow(
                    title = stringResource(R.string.cc_health_interval), aiTopic = AiTopic.CUSTOM_PING, icon = Icons.Rounded.Speed,
                    options = listOf(60L, 180L, 300L, 600L, 1800L, 3600L),
                    selected = st.healthIntervalSeconds,
                    label = { "${it / 60} min" }, enabled = editable,
                    onSelect = { CustomConfigStore.setHealthInterval(context, it) },
                )
                if (st.configs.isNotEmpty()) {
                    RowDivider()
                    SettingsSwitchRow(aiTopic = AiTopic.CUSTOM_PING, title = stringResource(R.string.cc_sort_ping_desc),
                        summary = stringResource(R.string.cc_sort_ping_desc_summary),
                        icon = Icons.Rounded.Speed,
                        checked = pingSort == ConfigCatalog.PingSort.DESC,
                        onCheckedChange = { pingSort = if (it) ConfigCatalog.PingSort.DESC else ConfigCatalog.PingSort.ASC })
                }
            }
        }
        settingsSection {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !testingAll && !scanState.running && visibleConfigs.isNotEmpty() && !busy,
                        onClick = { scope.launch { ConfigPingStore.testAll(context, visibleConfigs) } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(50)) {
                        if (testingAll) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.cc_ping_all_short), Modifier.padding(start = 6.dp))
                    }
                    Button(enabled = editable && !busy, onClick = {
                        importDestination = ConfigCatalog.groupFor(effectiveTab)
                        addMenu = true
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.cc_add_config), Modifier.padding(start = 6.dp))
                    }
                }
                OutlinedButton(enabled = if (scanState.running) true else editable && !busy && !testingAll &&
                    visibleConfigs.any { studio.cluvex.aether.scanner.CleanIpScanManager.supported(it) },
                    onClick = {
                        if (scanState.running) studio.cluvex.aether.scanner.CleanIpScanManager.stop()
                        else studio.cluvex.aether.scanner.CleanIpScanManager.scanServers(context, visibleConfigs)
                    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(stringResource(if (scanState.running) R.string.cip_stop else R.string.cip_scan_all))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    AiTopicIcon(AiTopic.CLEAN_IP_SCANNER)
                }
                if (scanState.running) Text(stringResource(R.string.cip_group_progress,
                    (scanState.completed + 1).coerceAtMost(scanState.total), scanState.total),
                    modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                else CleanIpScanNotice(scanState.message.takeIf {
                    scanState.messageConfigs == st.configs && scanState.messageTab == effectiveTab
                }.orEmpty(), scanState.applied)
                ConfigTabStrip(tabs, effectiveTab, enabled = !busy, editable = editable && !busy,
                    onSelect = { selectTab(it) }, onRename = { tab ->
                        if (tab.editable) {
                            groupToEdit = st.groups.firstOrNull { ConfigCatalog.customKey(it.id) == tab.key }
                            groupEditor = true
                        } else automaticTabEditor = tab.key
                    }, onNew = { groupToEdit = null; groupEditor = true })
                key(effectiveTab) {
                    ConfigDataTable(visibleRows, st.activeId, pings, editable && !busy, testingAll,
                        onSelect = { CustomConfigStore.select(context, it.id) },
                        onConfigMenu = { actionsFor = it }, onSubscriptionMenu = { actionsForSubscription = it },
                        serverNumbers = serverNumbers, scanning = scanState.running,
                        scanTargetId = scanState.targetId, scanProgress = scanState.progress,
                        scanValidating = scanState.validating,
                        onScanIp = { studio.cluvex.aether.scanner.CleanIpScanManager.scanServer(context, it) })
                }
            }
        }

        // Its own section, so it gets the same gap every other group has instead
        // of sitting glued to the bottom of the saved-configs card.
        if (st.configs.isNotEmpty()) {
            settingsSection {
                SettingsGroup {
                    SettingsActionRow(
                        aiTopic = AiTopic.CUSTOM_CONFIGS,
                        title = stringResource(R.string.cc_delete_all),
                        icon = Icons.Rounded.DeleteSweep,
                        destructive = true,
                        enabled = editable,
                        onClick = { confirmDeleteAll = true },
                    )
                }
            }
        }
    }

        scanState.pending.firstOrNull()?.let { event ->
            CleanIpScanResultCard(event.report, serverNumbers[event.report.configId],
                onDismiss = { studio.cluvex.aether.scanner.CleanIpScanManager.dismissResult(event.id) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .navigationBarsPadding().padding(16.dp))
        }
    }

    // ------------------------------------------------------------ dialogs

    if (addMenu) ConfigDialog(onDismissRequest = { addMenu = false },
        title = { Text(stringResource(R.string.cc_add_config)) },
        text = { Column {
            // 1.6.0: SettingsMenuAction owns the spacing (outer gap + 56 dp min row +
            // inner padding); the caller no longer stacks a second layer on top.
            SettingsMenuAction(onClick = { addMenu = false; pasteOpen = true }) { Text(stringResource(R.string.cc_import_paste), Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold) }
            SettingsMenuAction(onClick = { addMenu = false; runCatching { pickFile.launch(arrayOf("*/*")) } }) {
                Text(stringResource(R.string.cc_import_file), Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
            }
            SettingsMenuAction(onClick = { addMenu = false; runCatching { scanQr.launch(ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(scanPrompt).setBeepEnabled(false)
                .setOrientationLocked(true).setCaptureActivity(PortraitCaptureActivity::class.java)) } }) {
                Text(stringResource(R.string.cc_import_camera), Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
            }
            SettingsMenuAction(onClick = { addMenu = false; runCatching { pickImage.launch("image/*") } }) {
                Text(stringResource(R.string.cc_import_image), Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
            }
        } }, confirmButton = { TextButton(onClick = { addMenu = false }) { Text(stringResource(R.string.action_cancel)) } })
    actionsForSubscription?.let { sub ->
        ConfigDialog(onDismissRequest = { actionsForSubscription = null }, title = { Text(sub.name) },
            text = { Column {
                SettingsMenuAction(enabled = editable && !busy, onClick = { actionsForSubscription = null; refresh(sub) }) {
                    Text(stringResource(R.string.cc_sub_update))
                }
                SettingsMenuAction(enabled = editable && !busy, onClick = { actionsForSubscription = null; moveSubscription = sub }) {
                    Text(stringResource(R.string.cc_move_group))
                }
                SettingsMenuAction(enabled = editable && !busy, onClick = { actionsForSubscription = null; deleteSubscription = sub }) {
                    Text(stringResource(R.string.cc_action_delete))
                }
            } }, confirmButton = { TextButton(onClick = { actionsForSubscription = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    moveSubscription?.let { sub ->
        fun moveTo(group: String?) {
            try { CustomConfigStore.moveSubscription(context, sub.id, group); moveSubscription = null }
            catch (e: IllegalArgumentException) { toast(e.message.orEmpty()) }
        }
        ConfigDialog(onDismissRequest = { moveSubscription = null }, title = { Text(stringResource(R.string.cc_move_group)) },
            text = { Column {
                SettingsMenuAction(onClick = { moveTo(null) }) { Text(stringResource(R.string.cc_ungrouped)) }
                st.groups.forEach { g -> SettingsMenuAction(onClick = { moveTo(g.id) }) { Text(g.name) } }
            } }, confirmButton = { TextButton(onClick = { moveSubscription = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    deleteSubscription?.let { sub ->
        ConfigDialog(onDismissRequest = { deleteSubscription = null }, title = { Text(stringResource(R.string.cc_action_delete)) },
            text = { Text(stringResource(R.string.cc_sub_delete_confirm, sub.name)) },
            confirmButton = { TextButton(onClick = {
                CustomConfigStore.deleteSubscription(context, sub.id); deleteSubscription = null
            }) { Text(stringResource(R.string.cc_action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteSubscription = null }) { Text(stringResource(R.string.action_cancel)) } })
    }

    automaticTabEditor?.let { tabKey ->
        ConfigDialog(onDismissRequest = { automaticTabEditor = null },
            title = { Text(tabs.firstOrNull { it.key == tabKey }?.name.orEmpty()) },
            text = { Column {
                SettingsMenuAction(enabled = editable && !busy, onClick = {
                    importDestination = null; automaticTabEditor = null; addMenu = true
                }) { Icon(Icons.Rounded.Add, null); Text(stringResource(R.string.cc_add_config)) }
                SettingsMenuAction(enabled = editable && !busy, onClick = {
                    automaticTabEditor = null; automaticTabNaming = tabKey
                }) { Text(stringResource(R.string.cc_action_rename)) }
                if (tabKey != ConfigCatalog.ALL) SettingsMenuAction(enabled = editable && !busy, onClick = {
                    automaticTabEditor = null; deletingTab = tabKey
                }) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { automaticTabEditor = null }) {
                Text(stringResource(R.string.action_cancel))
            } })
    }
    automaticTabNaming?.let { tabKey ->
        var name by remember(tabKey) { mutableStateOf(tabs.firstOrNull { it.key == tabKey }?.name.orEmpty()) }
        ConfigDialog(onDismissRequest = { automaticTabNaming = null },
            title = { Text(stringResource(R.string.cc_action_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                label = { Text(stringResource(R.string.cc_group_name)) }) },
            confirmButton = { TextButton(enabled = editable && !busy && name.isNotBlank(), onClick = {
                CustomConfigStore.renameAutomaticTab(context, tabKey, name); automaticTabNaming = null
            }) { Text(stringResource(R.string.cc_group_save)) } },
            dismissButton = { TextButton(onClick = { automaticTabNaming = null }) {
                Text(stringResource(R.string.action_cancel))
            } })
    }
    if (groupEditor) {
        var name by remember(groupToEdit) { mutableStateOf(groupToEdit?.name.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        ConfigDialog(onDismissRequest = { groupEditor = false },
            title = { Text(stringResource(if (groupToEdit == null) R.string.cc_group_new else R.string.cc_group_edit)) },
            text = { Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    label = { Text(stringResource(R.string.cc_group_name)) })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                try {
                    val next = CustomConfigStore.putGroup(context, groupToEdit?.id, name)
                    selectTab(ConfigCatalog.customKey(groupToEdit?.id ?: next.groups.last().id))
                    groupEditor = false
                }
                catch (e: IllegalArgumentException) { error = e.message }
            }) { Text(stringResource(R.string.cc_group_save)) } },
            dismissButton = { Column {
                groupToEdit?.let { group -> TextButton(enabled = editable && !busy, onClick = {
                    importDestination = group.id; groupEditor = false; addMenu = true
                }) { Icon(Icons.Rounded.Add, null); Text(stringResource(R.string.cc_add_config)) } }
                Row {
                groupToEdit?.let { group -> TextButton(onClick = {
                    deletingTab = ConfigCatalog.customKey(group.id); groupEditor = false
                }) { Text(stringResource(R.string.cc_action_delete)) } }
                TextButton(onClick = { groupEditor = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            } })
    }
    deletingTab?.let { tabKey ->
        val tab = tabs.firstOrNull { it.key == tabKey }
        ConfigDialog(onDismissRequest = { deletingTab = null },
            title = { Text(stringResource(R.string.cc_delete_tab_title)) },
            text = { Text(stringResource(R.string.cc_delete_tab_confirm, tab?.name.orEmpty())) },
            confirmButton = { TextButton(enabled = editable && !busy, onClick = {
                deletingTab = null; busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { CustomConfigStore.deleteTab(context, tabKey) }
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { toast(String.format(failedMsg, e.javaClass.simpleName))
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingTab = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    moveConfig?.let { cfg ->
        ConfigDialog(onDismissRequest = { moveConfig = null },
            title = { Text(stringResource(R.string.cc_move_group)) },
            text = { Column {
                SettingsMenuAction(onClick = { CustomConfigStore.move(context, cfg.id, null); moveConfig = null }) {
                    Text(stringResource(R.string.cc_ungrouped))
                }
                st.groups.forEach { group -> SettingsMenuAction(onClick = {
                    CustomConfigStore.move(context, cfg.id, group.id); moveConfig = null
                }) { Text(group.name) } }
            } }, confirmButton = { TextButton(onClick = { moveConfig = null }) {
                Text(stringResource(R.string.action_cancel))
            } })
    }
    subscriptionPassword?.let { sub ->
        var password by remember(sub.id) { mutableStateOf("") }
        ConfigDialog(onDismissRequest = { subscriptionPassword = null },
            title = { Text(stringResource(R.string.cc_password_title)) },
            text = { LtrOutlinedTextField(value = password, onValueChange = { password = it },
                visualTransformation = PasswordVisualTransformation()) },
            confirmButton = { TextButton(enabled = password.isNotBlank(), onClick = {
                subscriptionPassword = null; refresh(sub, password)
            }) { Text(stringResource(R.string.cc_import_action)) } },
            dismissButton = { TextButton(onClick = { subscriptionPassword = null }) {
                Text(stringResource(R.string.action_cancel))
            } })
    }

    if (pasteOpen) {
        var text by remember { mutableStateOf("") }
        var pasteCount by remember { mutableIntStateOf(0) }
        ConfigDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text(stringResource(R.string.cc_import_paste), Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp) {
                    LtrOutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = false,
                        placeholder = { Text(stringResource(R.string.cc_paste_hint)) },
                        // 1.6.0: 6 dp keeps the field clear of the 18 dp corner radius;
                        // the old 12 dp inset only shrank the typing area.
                        modifier = Modifier.fillMaxWidth().padding(6.dp).heightIn(min = 120.dp, max = 260.dp),
                        // The field is focused while this dialog is open; without
                        // the key a pasted value would never reach it.
                        replaceKey = pasteCount,
                    )
                    }
                    TextButton(onClick = {
                        val clip = clipboardText(context)
                        if (clip.isNullOrBlank()) {
                            toast(clipboardEmptyMsg)
                        } else {
                            text = clip
                            pasteCount++
                        }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                        Text(stringResource(R.string.cc_paste_clipboard), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        pasteOpen = false
                        val input = text
                        importText(input, ConfigSource.LINK)
                    },
                ) { Text(stringResource(R.string.cc_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pasteOpen = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    pending?.let { p ->
        var password by remember(p) { mutableStateOf("") }
        ConfigDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.cc_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (p.wrong) R.string.cc_password_wrong else R.string.cc_password_desc),
                        color = if (p.wrong) MaterialTheme.colorScheme.error else OnDarkDim,
                    )
                    LtrOutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty(),
                    onClick = {
                        pending = null
                        runImport(password, p.destination, p.retry)
                    },
                ) { Text(stringResource(R.string.cc_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }


    actionsFor?.let { cfg ->
        ConfigDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(cfg.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (cfg.locked) {
                        Text(
                            stringResource(R.string.cc_locked_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkDim,
                        )
                    }
                    if (cfg.note.isNotBlank()) {
                        Text(
                            cfg.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkDim,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    SettingsMenuAction(
                        enabled = editable && cfg.id != st.activeId,
                        onClick = { CustomConfigStore.select(context, cfg.id); CustomConfigStore.setEnabled(context, true); actionsFor = null },
                    ) { Text(stringResource(R.string.cc_action_use)) }
                    SettingsMenuAction(
                        onClick = {
                            actionsFor = null
                            scope.launch { ConfigPingStore.test(context, cfg) }
                        },
                    ) { Text(stringResource(R.string.cc_ping_one)) }
                    if (studio.cluvex.aether.scanner.CleanIpScanManager.supported(cfg)) {
                        SettingsMenuAction(enabled = editable && !busy && !testingAll && !scanState.running,
                            onClick = { actionsFor = null; studio.cluvex.aether.scanner.CleanIpScanManager.scanServer(context, cfg) }) {
                            Text(stringResource(R.string.cip_server_shortcut))
                        }
                    }
                    SettingsMenuAction(enabled = editable,
                        onClick = { speedFor = cfg; actionsFor = null }) {
                        Text(stringResource(R.string.cc_speed_test))
                    }
                    SettingsMenuAction(enabled = editable,
                        onClick = { moveConfig = cfg; actionsFor = null }) {
                        Text(stringResource(R.string.cc_move_group))
                    }
                    SettingsMenuAction(
                        enabled = editable && !cfg.locked && cfg.raw.isNotBlank(),
                        onClick = { editing = cfg; actionsFor = null },
                    ) { Text(stringResource(R.string.cc_action_edit)) }
                    SettingsMenuAction(
                        enabled = editable,
                        onClick = { renaming = cfg; actionsFor = null },
                    ) { Text(stringResource(R.string.cc_action_rename)) }
                    if (!cfg.locked && cfg.raw.isNotBlank()) {
                        SettingsMenuAction(onClick = {
                            // Holds the server's credentials: flagged sensitive so the
                            // system clipboard preview and keyboards do not show it.
                            studio.cluvex.aether.ui.components.copySensitive(context, cfg.raw, "config")
                            actionsFor = null
                        }) { Text(stringResource(R.string.cc_action_copy)) }
                    }
                    SettingsMenuAction(
                        enabled = editable,
                        onClick = { deleting = cfg; actionsFor = null },
                    ) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionsFor = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    speedFor?.let { cfg ->
        ConfigDialog(onDismissRequest = { speedFor = null },
            title = { Text(stringResource(R.string.cc_speed_test)) },
            text = { Text(stringResource(R.string.cc_speed_consent)) },
            confirmButton = { TextButton(onClick = {
                speedFor = null
                scope.launch { ConfigSpeedTest.measure(context, cfg) }
            }) { Text(stringResource(R.string.cc_speed_start)) } },
            dismissButton = { TextButton(onClick = { speedFor = null }) {
                Text(stringResource(R.string.action_cancel))
            } })
    }

    editing?.let { cfg ->
        var raw by remember(cfg.id) { mutableStateOf(cfg.raw) }
        ConfigDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.cc_edit_title)) },
            text = {
                Column {
                    LtrOutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = raw.isNotBlank() && raw != cfg.raw,
                    onClick = {
                        val edited = raw
                        editing = null
                        scope.launch {
                            try {
                                val next = ConfigImporter.reparse(context, cfg, edited)
                                withContext(Dispatchers.IO) { CustomConfigStore.replace(context, next) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                toast(String.format(failedMsg, e.message ?: e.javaClass.simpleName))
                            }
                        }
                    },
                ) { Text(stringResource(R.string.cc_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    renaming?.let { cfg ->
        var name by remember(cfg.id) { mutableStateOf(cfg.name) }
        ConfigDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.cc_action_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cc_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        CustomConfigStore.replace(context, cfg.copy(name = name.trim()))
                        renaming = null
                    },
                ) { Text(stringResource(R.string.cc_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    deleting?.let { cfg ->
        ConfigDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.cc_delete_title)) },
            text = { Text(stringResource(R.string.cc_delete_body, cfg.name)) },
            confirmButton = {
                TextButton(onClick = {
                    CustomConfigStore.delete(context, cfg.id)
                    deleting = null
                }) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmDeleteAll) {
        ConfigDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.cc_delete_all)) },
            text = { Text(stringResource(R.string.cc_delete_all_body)) },
            confirmButton = {
                TextButton(onClick = {
                    CustomConfigStore.deleteAll(context)
                    confirmDeleteAll = false
                }) { Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun hopsLabel(mode: ChainMode): String = when (mode.hops) {
    1 -> stringResource(R.string.cc_hops_single)
    2 -> stringResource(R.string.cc_hops_double)
    else -> stringResource(R.string.cc_hops_triple)
}

/** The label for the settings home row: the chain in use, or "Off". */
@Composable
internal fun customConfigsValue(): String {
    val st by CustomConfigStore.state.collectAsState()
    val sel = st.selection
    return sel?.mode?.label(sel.config.chainLabel) ?: stringResource(R.string.cc_off)
}

/**
 * Reads the clipboard the way a user means it.
 *
 * ROOT CAUSE this fixes (1.5.0-r2, "Paste from clipboard does nothing"): the
 * button used Compose's `ClipboardManager.getText()`, which returns the FIRST
 * clip item and only when that item already carries plain text. A config link
 * copied from a browser's address bar, from Telegram, or from a QR-reader app
 * arrives as a `text/uri-list` item, or as styled HTML, or as a second item - and
 * for every one of those `getText()` returns null. The button then silently did
 * nothing at all, with no message, which is exactly what was reported.
 *
 * `ClipData.Item.coerceToText` is the platform's own answer: it returns the text
 * of a text item, the URI of a URI item, and the stripped text of an HTML item.
 * Every item is read, not just the first, so a multi-link clip (a whole
 * subscription pasted in one go) imports as the several configs it is.
 */
/**
 * Every text item on the clipboard, cleaned for the importer.
 *
 * Read with the item's own text first and `coerceToText` only as a fallback
 * (that one may open a content URI and can throw on some OEM clipboards), and
 * never allowed to throw: a SecurityException from a clipboard read in the
 * background, or a provider that has gone away, means "nothing to paste", not
 * a crash. Invisible marks that messengers put around links (RLM/LRM, BOM,
 * zero-width spaces) are removed here so the link is recognised as one.
 */
private fun clipboardText(context: android.content.Context): String? {
    val manager = context.getSystemService(android.content.ClipboardManager::class.java) ?: return null
    val clip = runCatching { manager.primaryClip }.getOrNull() ?: return null
    val text = (0 until clip.itemCount)
        .mapNotNull { index ->
            runCatching {
                val item = clip.getItemAt(index)
                item.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: item.htmlText?.let { html ->
                        androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
                            .toString()
                    }?.takeIf { it.isNotBlank() }
                    ?: item.coerceToText(context)?.toString()
            }.getOrNull()
        }
        .map { ConfigParser.cleanInput(it) }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    return text.ifBlank { null }
}

