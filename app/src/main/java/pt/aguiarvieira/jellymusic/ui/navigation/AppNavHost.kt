package pt.aguiarvieira.jellymusic.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pt.aguiarvieira.jellymusic.ui.feature.browse.BrowseShell
import pt.aguiarvieira.jellymusic.ui.feature.detail.AlbumDetailScreen
import pt.aguiarvieira.jellymusic.ui.feature.detail.ArtistDetailScreen
import pt.aguiarvieira.jellymusic.ui.feature.detail.PlaylistDetailScreen
import pt.aguiarvieira.jellymusic.ui.feature.onboarding.ConnectServerScreen
import pt.aguiarvieira.jellymusic.ui.feature.onboarding.LoginScreen
import pt.aguiarvieira.jellymusic.ui.feature.player.FullPlayerScreen

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
                // Settings screen is future work; the gear is wired but inert for now.
                onOpenSettings = { },
            )
        }

        composable<Routes.AlbumDetail> {
            AlbumDetailScreen(
                onBack = { navController.popBackStack() },
                onExpandPlayer = { navController.navigate(Routes.Player) },
            )
        }

        composable<Routes.ArtistDetail> {
            ArtistDetailScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { id, name -> navController.navigate(Routes.AlbumDetail(id, name)) },
                onExpandPlayer = { navController.navigate(Routes.Player) },
            )
        }

        composable<Routes.PlaylistDetail> {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onExpandPlayer = { navController.navigate(Routes.Player) },
            )
        }

        composable<Routes.Player> {
            FullPlayerScreen(onCollapse = { navController.popBackStack() })
        }
    }
}
