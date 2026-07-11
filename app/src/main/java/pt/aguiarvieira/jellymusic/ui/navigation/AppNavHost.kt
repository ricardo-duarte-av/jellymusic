package pt.aguiarvieira.jellymusic.ui.navigation

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pt.aguiarvieira.jellymusic.ui.feature.browse.BrowseShell
import pt.aguiarvieira.jellymusic.ui.feature.detail.AlbumDetailScreen
import pt.aguiarvieira.jellymusic.ui.feature.detail.ArtistDetailScreen
import pt.aguiarvieira.jellymusic.ui.feature.detail.PlaylistDetailScreen
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadsScreen
import pt.aguiarvieira.jellymusic.ui.feature.onboarding.ConnectServerScreen
import pt.aguiarvieira.jellymusic.ui.feature.onboarding.LoginScreen
import pt.aguiarvieira.jellymusic.ui.feature.player.FullPlayerScreen
import pt.aguiarvieira.jellymusic.ui.feature.search.SearchScreen
import pt.aguiarvieira.jellymusic.ui.feature.settings.SettingsScreen

@Composable
fun AppNavHost(
    startAtHome: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = if (startAtHome) Routes.Home else Routes.ConnectServer,
    ) {
        composable<Routes.ConnectServer> {
            ConnectServerScreen(
                onConnected = { server ->
                    navController.navigate(
                        Routes.Login(
                            serverId = server.id,
                            serverName = server.name,
                            serverAddress = server.address,
                        ),
                    )
                },
            )
        }

        composable<Routes.Login> {
            LoginScreen(
                onAuthenticated = {
                    // Straight to browse; default to All music. Library is switchable via the
                    // dropdown in the top bar.
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.ConnectServer) { inclusive = true }
                    }
                },
            )
        }

        composable<Routes.Home> {
            BrowseShell(
                onAlbumClick = { id, name -> navController.navigate(Routes.AlbumDetail(id, name)) },
                onArtistClick = { id, name -> navController.navigate(Routes.ArtistDetail(id, name)) },
                onPlaylistClick = { id, name -> navController.navigate(Routes.PlaylistDetail(id, name)) },
                onExpandPlayer = { navController.navigate(Routes.Player) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenSearch = { navController.navigate(Routes.Search) },
            )
        }

        composable<Routes.Search> {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { id, name -> navController.navigate(Routes.AlbumDetail(id, name)) },
                onArtistClick = { id, name -> navController.navigate(Routes.ArtistDetail(id, name)) },
                onPlaylistClick = { id, name -> navController.navigate(Routes.PlaylistDetail(id, name)) },
                onExpandPlayer = { navController.navigate(Routes.Player) },
            )
        }

        composable<Routes.AlbumDetail> {
            AlbumDetailScreen(
                onBack = { navController.popBackStack() },
                onExpandPlayer = { navController.navigate(Routes.Player) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }

        composable<Routes.ArtistDetail> {
            ArtistDetailScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { id, name -> navController.navigate(Routes.AlbumDetail(id, name)) },
                onExpandPlayer = { navController.navigate(Routes.Player) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }

        composable<Routes.PlaylistDetail> {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onExpandPlayer = { navController.navigate(Routes.Player) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }

        composable<Routes.Player>(
            // Rise up when opened from the mini player; collapse straight down on close.
            enterTransition = { slideInVertically(initialOffsetY = { it }) },
            popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
        ) {
            FullPlayerScreen(
                onCollapse = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }

        composable<Routes.Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDownloads = { navController.navigate(Routes.Downloads) },
            )
        }

        composable<Routes.Downloads> {
            DownloadsScreen(onBack = { navController.popBackStack() })
        }
    }
}
