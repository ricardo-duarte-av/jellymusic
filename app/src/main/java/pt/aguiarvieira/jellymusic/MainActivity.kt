package pt.aguiarvieira.jellymusic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.ui.app.AppViewModel
import pt.aguiarvieira.jellymusic.ui.app.StartState
import pt.aguiarvieira.jellymusic.ui.navigation.AppNavHost
import pt.aguiarvieira.jellymusic.ui.theme.JellyMusicTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JellyMusicTheme {
                RequestNotificationPermission()
                val startState by appViewModel.startState.collectAsStateWithLifecycle()
                when (startState) {
                    StartState.Loading -> LoadingScreen()
                    StartState.Onboarding -> AppNavHost(startAtHome = false)
                    StartState.Home -> AppNavHost(startAtHome = true)
                }
            }
        }
    }
}

/** Requests POST_NOTIFICATIONS once on Android 13+ so the media playback notification can show. */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Playback still works if denied; only the notification is affected. */ }
    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun LoadingScreen() {
    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}
