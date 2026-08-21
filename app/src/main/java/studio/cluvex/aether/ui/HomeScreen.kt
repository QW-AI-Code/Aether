package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.AmbientBackground
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.ConnectButton
import studio.cluvex.aether.ui.components.ConnectionCard
import studio.cluvex.aether.ui.components.DiagnosticsPanel
import studio.cluvex.aether.ui.theme.AetherMint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = when {
        state.isConnected -> ButtonMode.CONNECTED
        state.isBusy -> ButtonMode.BUSY
        state is ConnectionState.Error -> ButtonMode.ERROR
        else -> ButtonMode.IDLE
    }

    val accent = when (mode) {
        // Brand mint, the same accent the connection card and its animated edge
        // use, so the whole screen reads as one palette.
        ButtonMode.CONNECTED -> AetherMint
        ButtonMode.ERROR -> Color(0xFFFF5C7A)
        else -> Color(0xFF4C8DFF)
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // 1.2.2 UI-SPEED FIX: ModalNavigationDrawer composes its drawer content
    // even while the drawer is CLOSED, so the diagnostics, share, advanced and
    // about cards were live at all times — recomposing on every profile change
    // and on every log line, behind a panel nobody was looking at. They are now
    // only composed while the drawer is open or opening.
    val drawerVisible = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open

    // Advanced settings, reachable directly from the home screen (top-right).
    var showAdvancedSheet by remember { mutableStateOf(false) }
    val advancedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settingsEnabled = state is ConnectionState.Idle || state is ConnectionState.Error

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(0.9f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(20.dp))

                    if (drawerVisible) {
                        DiagnosticsPanel()

                        Spacer(Modifier.height(16.dp))

                        SharePanel(
                            state = state,
                            profile = profile,
                            onProfileChange = onProfileChange,
                        )

                        Spacer(Modifier.height(16.dp))

                        AdvancedPanel(
                            profile = profile,
                            onProfileChange = onProfileChange,
                            enabled = settingsEnabled,
                        )

                        Spacer(Modifier.height(16.dp))

                        AboutPanel()
                    }
                }
            }
        },
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            AmbientBackground(accent = accent, active = state.isConnected)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(28.dp))

                ConnectButton(mode = mode, onClick = onToggleConnection)

                Spacer(Modifier.height(28.dp))

                // 1.2.6: status, timer, IP, speeds and the protocol row used to
                // be four separate floating surfaces here. They are one unified
                // glass card now - see ConnectionCard.
                ConnectionCard(
                    connected = state.isConnected,
                    statusTitle = stateTitle(state),
                    statusCaption = stateSubtitle(state),
                    connectedSince = connectedSince,
                    ipInfo = ipInfo,
                    ipLoading = ipLoading,
                    error = state is ConnectionState.Error,
                )

                Spacer(Modifier.height(16.dp))
            }

            IconButton(
                onClick = { drawerScope.launch { drawerState.open() } },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.menu_open),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            // Advanced settings straight from the home screen.
            IconButton(
                onClick = { showAdvancedSheet = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.advanced_open),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }

    if (showAdvancedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAdvancedSheet = false },
            sheetState = advancedSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    // The advanced card is much taller than a phone screen.
                    // Give the sheet a bounded viewport and scroll that viewport;
                    // otherwise Compose measures the whole card and Material's
                    // bottom sheet clips its lower controls behind the nav bar.
                    .fillMaxHeight(0.92f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
            ) {
                // 1.2.2 UI-SPEED FIX: the advanced card is ~40 controls tall and
                // used to be composed in the SAME frame the sheet starts its
                // slide-in animation, so the sheet visibly stuttered on open.
                // The first frame now shows the empty sheet (instant) and the
                // controls are composed immediately afterwards.
                var sheetReady by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { sheetReady = true }
                if (sheetReady) {
                    AdvancedPanel(
                        profile = profile,
                        onProfileChange = onProfileChange,
                        enabled = settingsEnabled,
                        startExpanded = true,
                    )
                } else {
                    Spacer(Modifier.height(320.dp))
                }
            }
        }
    }
}

@Composable
private fun stateTitle(state: ConnectionState): String = when (state) {
    is ConnectionState.Idle -> stringResource(R.string.state_idle)
    is ConnectionState.Launching -> stringResource(R.string.state_launching)
    is ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    is ConnectionState.Verifying -> stringResource(R.string.state_verifying)
    is ConnectionState.Connected -> stringResource(R.string.state_connected)
    is ConnectionState.Reconnecting -> stringResource(R.string.state_reconnecting)
    is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
    is ConnectionState.Error -> stringResource(R.string.state_error)
}

@Composable
private fun stateSubtitle(state: ConnectionState): String = when (state) {
    is ConnectionState.Idle -> stringResource(R.string.tap_to_connect)
    // The exit IP + flag is shown inside the card, so keep the subtitle generic
    // instead of leaking the internal 127.0.0.1:port address.
    is ConnectionState.Connected -> stringResource(R.string.tap_to_disconnect)
    is ConnectionState.Reconnecting ->
        stringResource(R.string.reconnect_attempt, state.attempt, state.maxAttempts)
    is ConnectionState.Error -> state.message
    else -> stringResource(R.string.tap_to_disconnect)
}
