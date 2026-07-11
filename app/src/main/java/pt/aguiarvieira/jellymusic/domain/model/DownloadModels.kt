package pt.aguiarvieira.jellymusic.domain.model

/** Lifecycle of a single track's offline copy. */
enum class DownloadState { QUEUED, DOWNLOADING, COMPLETED, FAILED }

/**
 * UI-facing status of one track's offline copy. [progress] is 0f..1f, or a negative value when the
 * total size is unknown (indeterminate).
 */
data class TrackDownloadStatus(
    val state: DownloadState,
    val progress: Float = 0f,
) {
    val isComplete: Boolean get() = state == DownloadState.COMPLETED
    val isActive: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.DOWNLOADING
    val isIndeterminate: Boolean get() = progress < 0f
}

/**
 * Aggregate status of an album's offline copy: how many of its [total] tracks are [completed], and
 * whether any are still in flight or failed.
 */
data class AlbumDownloadStatus(
    val total: Int,
    val completed: Int,
    val downloading: Boolean,
    val failed: Boolean,
) {
    val isComplete: Boolean get() = total > 0 && completed >= total
    val inProgress: Boolean get() = downloading || (completed in 1 until total)
    /** Fraction of the album downloaded, 0f..1f. */
    val progress: Float get() = if (total <= 0) 0f else completed.toFloat() / total
}
