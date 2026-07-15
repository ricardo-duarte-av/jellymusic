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
import pt.aguiarvieira.jellymusic.ui.feature.settings.AboutScreen
import pt.aguiarvieira.jellymusic.ui.feature.settings.ChangelogScreen
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
                onAlbumClick = { id, name -> navController.navigateSingleTop(Routes.AlbumDetail(id, name)) },
                onArtistClick = { id, name -> navController.navigateSingleTop(Routes.ArtistDetail(id, name)) },
                onPlaylistClick = { id, name -> navController.navigateSingleTop(Routes.PlaylistDetail(id, name)) },
                onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
                onOpenSearch = { navController.navigateSingleTop(Routes.Search) },
            )
        }

        composable<Routes.Search> {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { id, name -> navController.navigateSingleTop(Routes.AlbumDetail(id, name)) },
                onArtistClick = { id, name -> navController.navigateSingleTop(Routes.ArtistDetail(id, name)) },
                onPlaylistClick = { id, name -> navController.navigateSingleTop(Routes.PlaylistDetail(id, name)) },
                onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
            )
        }

        composable<Routes.AlbumDetail> {
            AlbumDetailScreen(
                onBack = { navController.popBackStack() },
                onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
            )
        }

        composable<Routes.ArtistDetail> {
            ArtistDetailScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { id, name -> navController.navigateSingleTop(Routes.AlbumDetail(id, name)) },
                onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
            )
        }

        composable<Routes.PlaylistDetail> {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
            )
        }

        composable<Routes.Player>(
            // Rise up when opened from the mini player; collapse straight down on close.
            enterTransition = { slideInVertically(initialOffsetY = { it }) },
            popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
        ) {
            FullPlayerScreen(
                onCollapse = { navController.popBackStack() },
                onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
            )
        }

        composable<Routes.Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDownloads = { navController.navigateSingleTop(Routes.Downloads) },
                onOpenChangelog = { navController.navigateSingleTop(Routes.Changelog) },
                onOpenAbout = { navController.navigateSingleTop(Routes.About) },
            )
        }

        composable<Routes.Downloads> {
            DownloadsScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { id, name -> navController.navigateSingleTop(Routes.AlbumDetail(id, name)) },
                onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
            )
        }

        composable<Routes.Changelog> {
            ChangelogScreen(onBack = { navController.popBackStack() })
        }

        composable<Routes.About> {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Navigates with [launchSingleTop] so a rapid double-tap (or re-entering a destination that's
 * already on top) collapses onto the existing entry instead of stacking a duplicate — the source of
 * the "back lands on the same album several times" behaviour.
 */
private fun NavHostController.navigateSingleTop(route: Any) {
    navigate(route) { launchSingleTop = true }
}
