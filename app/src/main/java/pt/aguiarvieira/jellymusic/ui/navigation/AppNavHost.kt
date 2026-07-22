package pt.aguiarvieira.jellymusic.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pt.aguiarvieira.jellymusic.ui.components.LocalNavAnimatedVisibilityScope
import pt.aguiarvieira.jellymusic.ui.components.LocalSharedTransitionScope
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(
    startAtHome: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    // One SharedTransitionLayout around the whole graph; each destination provides its own
    // AnimatedContentScope below, letting the album cover morph between grid and viewer.
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
    NavHost(
        navController = navController,
        startDestination = if (startAtHome) Routes.Home else Routes.ConnectServer,
        // Plain crossfade between destinations so the shared album cover is the only thing that
        // moves; the default scale/slide made the album grid appear to shrink behind the morphing
        // cover. Destinations that want something different (e.g. Player's slide) still override.
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
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
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                BrowseShell(
                    onAlbumClick = { id, name, art -> navController.navigateSingleTop(Routes.AlbumDetail(id, name, art)) },
                    onArtistClick = { id, name -> navController.navigateSingleTop(Routes.ArtistDetail(id, name)) },
                    onPlaylistClick = { id, name -> navController.navigateSingleTop(Routes.PlaylistDetail(id, name)) },
                    onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                    onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
                    onOpenSearch = { navController.navigateSingleTop(Routes.Search) },
                )
            }
        }

        composable<Routes.Search> {
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onAlbumClick = { id, name, art -> navController.navigateSingleTop(Routes.AlbumDetail(id, name, art)) },
                    onArtistClick = { id, name -> navController.navigateSingleTop(Routes.ArtistDetail(id, name)) },
                    onPlaylistClick = { id, name -> navController.navigateSingleTop(Routes.PlaylistDetail(id, name)) },
                    onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                )
            }
        }

        composable<Routes.AlbumDetail> {
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                AlbumDetailScreen(
                    onBack = { navController.popBackStack() },
                    onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                    onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
                )
            }
        }

        composable<Routes.ArtistDetail> {
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                ArtistDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAlbumClick = { id, name, art -> navController.navigateSingleTop(Routes.AlbumDetail(id, name, art)) },
                    onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                    onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
                )
            }
        }

        composable<Routes.PlaylistDetail> {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
            )
        }

        composable<Routes.Player> {
            // The now-playing cover expands out of the mini player bar (shared element) while the rest
            // of the sheet crossfades in — replacing the old rise-up slide.
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                FullPlayerScreen(
                    onCollapse = { navController.popBackStack() },
                    onOpenSettings = { navController.navigateSingleTop(Routes.Settings) },
                    onNavigateToAlbum = { id, name, art -> navController.navigateSingleTop(Routes.AlbumDetail(id, name, art)) },
                    onNavigateToArtist = { id, name -> navController.navigateSingleTop(Routes.ArtistDetail(id, name)) },
                )
            }
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
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                DownloadsScreen(
                    onBack = { navController.popBackStack() },
                    onAlbumClick = { id, name, art -> navController.navigateSingleTop(Routes.AlbumDetail(id, name, art)) },
                    onPlaylistClick = { id, name -> navController.navigateSingleTop(Routes.PlaylistDetail(id, name)) },
                    onExpandPlayer = { navController.navigateSingleTop(Routes.Player) },
                )
            }
        }

        composable<Routes.Changelog> {
            ChangelogScreen(onBack = { navController.popBackStack() })
        }

        composable<Routes.About> {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
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
