package studio.cluvex.aether

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.custom.*
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.ui.theme.AetherTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation

/** Isolated exported entry point. No remote URI is opened and no tunnel is started. */
class ConfigImportActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base?.let { LanguagePrefs.wrap(it) } ?: base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (intent.action != Intent.ACTION_VIEW || uri == null || uri.scheme !in listOf("content", "file")) {
            finish(); return
        }
        if (uri.scheme == "file") {
            val path = runCatching { java.io.File(uri.path ?: "").canonicalPath }.getOrNull()
            val privateRoot = java.io.File(applicationInfo.dataDir).canonicalPath
            if (path == null || path == privateRoot || path.startsWith("$privateRoot/")) {
                finish(); return
            }
        }
        setContent {
            AetherTheme {
                var configs by remember { mutableStateOf<List<CustomConfig>?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                var needsPassword by remember { mutableStateOf(false) }
                var password by remember { mutableStateOf("") }
                var group by remember { mutableStateOf<String?>(null) }
                var saving by remember { mutableStateOf(false) }
                val state by CustomConfigStore.state.collectAsState()
                fun parse(p: String? = null) {
                    error = null; needsPassword = false
                    lifecycleScope.launch {
                        try {
                            configs = withContext(Dispatchers.IO) {
                                CustomConfigStore.load(applicationContext)
                                ConfigImporter.fromFile(applicationContext, uri, p)
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (_: NpvtPasswordException) { needsPassword = true
                        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                    }
                }
                LaunchedEffect(Unit) { parse() }
                AlertDialog(onDismissRequest = { if (!saving) finish() },
                    title = { Text(stringResource(R.string.cc_import_file)) },
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                        when {
                            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                            needsPassword -> OutlinedTextField(value = password, onValueChange = { password = it },
                                label = { Text(stringResource(R.string.cc_password_title)) },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true)
                            configs == null || saving -> CircularProgressIndicator()
                            else -> {
                                Text(stringResource(R.string.cc_group_saved, configs!!.size))
                                TextButton(onClick = { group = null }) {
                                    Text((if (group == null) "• " else "") + stringResource(R.string.cc_ungrouped))
                                }
                                state.groups.forEach { g -> TextButton(onClick = { group = g.id }) {
                                    Text((if (group == g.id) "• " else "") + g.name)
                                } }
                            }
                        }
                    } },
                    confirmButton = {
                        if (needsPassword) TextButton(enabled = password.isNotBlank(), onClick = {
                            val p = password; password = ""; parse(p)
                        }) { Text(stringResource(R.string.cc_import_action)) }
                        else if (configs != null && error == null) TextButton(enabled = !saving, onClick = {
                            saving = true
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        CustomConfigStore.add(applicationContext, configs!!.map { it.copy(groupId = group) })
                                    }
                                    android.widget.Toast.makeText(this@ConfigImportActivity,
                                        getString(R.string.cc_imported, configs!!.size), android.widget.Toast.LENGTH_LONG).show()
                                    finish()
                                } catch (e: CancellationException) { throw e
                                } catch (e: Exception) { error = e.message; saving = false }
                            }
                        }) { Text(stringResource(R.string.cc_import_action)) }
                    },
                    dismissButton = { TextButton(enabled = !saving, onClick = { finish() }) {
                        Text(stringResource(R.string.action_cancel))
                    } })
            }
        }
    }
}
