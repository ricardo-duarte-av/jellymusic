package pt.aguiarvieira.jellymusic

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import pt.aguiarvieira.jellymusic.ui.app.AppViewModel
import pt.aguiarvieira.jellymusic.ui.app.StartState
import pt.aguiarvieira.jellymusic.ui.navigation.AppNavHost
import pt.aguiarvieira.jellymusic.ui.navigation.Routes
import pt.aguiarvieira.jellymusic.ui.theme.JellyMusicTheme
import pt.aguiarvieira.jellymusic.ui.theme.LocalDynamicColorEnabled
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            JellyMusicTheme {
                RequestNotificationPermission()
                val startState by appViewModel.startState.collectAsStateWithLifecycle()
                if (startState == StartState.Loading) {
                    LoadingScreen()
                } else {
                    val navController = rememberNavController()
                    val openPlayer by appViewModel.openPlayer.collectAsStateWithLifecycle()
                    val dynamicTheme by appViewModel.dynamicAlbumTheme.collectAsStateWithLifecycle()
                    val session by appViewModel.session.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    LaunchedEffect(openPlayer, startState) {
                        if (openPlayer && startState == StartState.Home) {
                            navController.navigate(Routes.Player)
                            appViewModel.consumeOpenPlayer()
                        }
                    }
                    // Session lost mid-run (token rejected or signed out) → back to onboarding.
                    LaunchedEffect(session, startState) {
                        if (session == null && startState == StartState.Home) {
                            Toast.makeText(
                                context,
                                "Session expired — please sign in again",
                                Toast.LENGTH_LONG,
                            ).show()
                            navController.navigate(Routes.ConnectServer) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }
                    }
                    CompositionLocalProvider(LocalDynamicColorEnabled provides dynamicTheme) {
                        AppNavHost(
                            startAtHome = startState == StartState.Home,
                            navController = navController,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            appViewModel.requestOpenPlayer()
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "pt.aguiarvieira.jellymusic.OPEN_PLAYER"
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
