package pt.aguiarvieira.jellymusic.ui.common

/** Simple loading/error/data wrapper shared by the browse and detail screens. */
sealed interface ContentState<out T> {
    data object Loading : ContentState<Nothing>
    data class Error(val message: String) : ContentState<Nothing>
    data class Data<T>(val value: T) : ContentState<T>
}

fun <T> Result<T>.toContentState(): ContentState<T> = fold(
    onSuccess = { ContentState.Data(it) },
    onFailure = { ContentState.Error(it.message ?: "Something went wrong") },
)
