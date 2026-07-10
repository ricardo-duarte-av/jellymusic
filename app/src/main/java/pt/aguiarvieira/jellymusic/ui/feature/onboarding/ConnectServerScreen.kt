package pt.aguiarvieira.jellymusic.ui.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.core.network.LocalNetworkAccess
import pt.aguiarvieira.jellymusic.core.network.rememberLocalNetworkPermission
import pt.aguiarvieira.jellymusic.domain.model.Server

@Composable
fun ConnectServerScreen(
    onConnected: (Server) -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val ensureLocalNetwork = rememberLocalNetworkPermission()

    LaunchedEffect(state.connectedServer) {
        state.connectedServer?.let {
            onConnected(it)
            viewModel.onNavigated()
        }
    }

    // Error is also shown as a persistent card below; the error clears when the user edits the field.

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Connect to Jellyfin",
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Enter your server address, or pick one found on your network.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.manualInput,
                onValueChange = viewModel::onManualInputChange,
                label = { Text("Server address") },
                placeholder = { Text("https://jellyfin.example.com") },
                singleLine = true,
                // "Dumb" input: no autocorrect / auto-capitalization so e.g. "https://" is left
                // exactly as typed (autocorrect would mangle it to "Https://").
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Go,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    // Connecting to a private address needs local-network permission on Android 17+;
                    // remote/public servers connect straight through with no prompt.
                    if (LocalNetworkAccess.isLocalAddress(state.manualInput)) {
                        ensureLocalNetwork { granted ->
                            if (granted) viewModel.connectToManual() else viewModel.onLocalNetworkDenied()
                        }
                    } else {
                        viewModel.connectToManual()
                    }
                },
                enabled = state.manualInput.isNotBlank() && !state.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Connect")
                }
            }

            state.error?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = "  On your network  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            OutlinedButton(
                onClick = {
                    ensureLocalNetwork { granted ->
                        if (granted) viewModel.discover() else viewModel.onLocalNetworkDenied()
                    }
                },
                enabled = !state.discovering,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.discovering) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Text("  Search your network")
                }
            }

            if (!state.discovering && state.discoveredServers.isEmpty()) {
                Text(
                    text = "No servers found yet. Search your network, or enter the address above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.discoveredServers, key = { it.address }) { server ->
                    Card(
                        onClick = { viewModel.connectToDiscovered(server) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ListItem(
                            headlineContent = { Text(server.name) },
                            supportingContent = { Text(server.address) },
                            leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }
}
