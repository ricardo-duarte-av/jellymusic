package pt.aguiarvieira.jellymusic.core.network

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Returns a function that ensures [LocalNetworkAccess.PERMISSION] is granted before running a local
 * network action. If already granted (or not required on this OS), the action runs immediately;
 * otherwise the system permission dialog is shown and the result delivered to [onResult].
 *
 * Usage: `val ensureLocalNetwork = rememberLocalNetworkPermission(); ensureLocalNetwork { granted -> ... }`
 */
@Composable
fun rememberLocalNetworkPermission(): ((onResult: (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pending.value?.invoke(granted)
        pending.value = null
    }

    return { onResult ->
        if (LocalNetworkAccess.isGranted(context)) {
            onResult(true)
        } else {
            pending.value = onResult
            launcher.launch(LocalNetworkAccess.PERMISSION)
        }
    }
}
