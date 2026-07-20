package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Skeleton (placeholder) loaders — M3 "stable layouts": while data loads, render the shape of the
 * content that's about to land, pulsing gently, so the layout doesn't jump when it arrives.
 *
 * [SkeletonScreen] wraps a loading placeholder and drives one shared pulse so every box on the
 * screen breathes in sync; [SkeletonBox] is the primitive; the rest mirror the real cards/rows.
 */
private val LocalSkeletonAlpha = compositionLocalOf { 1f }

@Composable
fun SkeletonScreen(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    CompositionLocalProvider(LocalSkeletonAlpha provides alpha, content = content)
}

/** A single pulsing placeholder block. Alpha comes from the enclosing [SkeletonScreen]. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    Box(
        modifier
            .alpha(LocalSkeletonAlpha.current)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** Placeholder matching [AlbumCard]/[PlaylistCard]: framed square cover + two text lines. */
@Composable
fun AlbumCardSkeleton(modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(6.dp)) {
        Card(modifier = Modifier.padding(8.dp)) {
            SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1f), shape = RectangleShape)
        }
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            SkeletonBox(Modifier.fillMaxWidth(0.75f).height(14.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp))
        }
    }
}

/** Placeholder matching [ArtistCard]: circular avatar + a centred name line. */
@Composable
fun ArtistCardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1f), shape = CircleShape)
        Spacer(Modifier.height(8.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.6f).height(14.dp))
    }
}

/** Placeholder matching a track row in the album viewer: number, title/subtitle, duration. */
@Composable
fun TrackRowSkeleton(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBox(Modifier.size(20.dp), shape = CircleShape)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(Modifier.fillMaxWidth(0.7f).height(13.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.45f).height(11.dp))
            }
            Spacer(Modifier.width(12.dp))
            SkeletonBox(Modifier.width(26.dp).height(11.dp))
        }
    }
}

/**
 * A non-scrolling grid of placeholder [item]s matching the real grid's [columns], used as the
 * loading state for the browse tabs.
 */
@Composable
fun GridSkeleton(
    columns: Int,
    modifier: Modifier = Modifier,
    count: Int = 9,
    item: @Composable () -> Unit,
) {
    SkeletonScreen {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = modifier.fillMaxSize().padding(horizontal = 8.dp),
            userScrollEnabled = false,
        ) {
            items(count) { item() }
        }
    }
}

/** Loading state for the album viewer's track list: a column of [TrackRowSkeleton]s. */
@Composable
fun TrackListSkeleton(modifier: Modifier = Modifier, count: Int = 8) {
    SkeletonScreen {
        Column(modifier = modifier.fillMaxWidth()) {
            repeat(count) { TrackRowSkeleton() }
        }
    }
}
