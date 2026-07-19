package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

/**
 * Small filled-heart status marker for list rows and grid cards. Renders nothing unless [favorite],
 * so non-favourited items stay visually calm. Purely decorative — it does not handle taps.
 */
@Composable
fun FavoriteMarker(favorite: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 18.dp) {
    if (!favorite) return
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = "Favourite",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(size),
    )
}

/**
 * The same marker for placement over artwork: a filled heart on a theme-surface pill. Matches
 * [pt.aguiarvieira.jellymusic.ui.feature.downloads.AlbumArtDownloadBadge] exactly (pill colour, size
 * and 6.dp corner inset) so the favourite and downloaded badges sit symmetrically on opposite
 * corners. Place with just an `.align(...)`; the inset is built in. Renders nothing unless [favorite].
 */
@Composable
fun FavoriteArtworkMarker(favorite: Boolean, modifier: Modifier = Modifier) {
    if (!favorite) return
    Surface(
        modifier = modifier.padding(6.dp).size(26.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favourite",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Heart toggle for detail/player top bars. Filled + primary-tinted when favourited, outline
 * otherwise, with a subtle scale pop on check that leans into the M3 Expressive motion feel.
 */
@Composable
fun FavoriteToggleButton(
    favorite: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(targetValue = if (favorite) 1.15f else 1f, label = "favoriteScale")
    IconToggleButton(checked = favorite, onCheckedChange = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.scale(scale),
        )
    }
}
