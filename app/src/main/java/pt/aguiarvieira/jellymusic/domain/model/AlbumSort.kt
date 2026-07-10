package pt.aguiarvieira.jellymusic.domain.model

/**
 * User-selectable album sort field. Kept SDK-free; the data layer maps each to a Jellyfin
 * `ItemSortBy`. ([ID] has no dedicated Jellyfin sort, so it maps to the server's default order.)
 */
enum class AlbumSort(val label: String) {
    ALBUM_ARTIST("Album Artist"),
    ID("ID"),
    COMMUNITY_RATING("Community Rating"),
    CRITIC_RATING("Critic Rating"),
    NAME("Name"),
    PLAY_COUNT("Play Count"),
    RANDOM("Random"),
    DATE_ADDED("Date added"),
    DATE_RELEASED("Date Released"),
    ;

    companion object {
        val DEFAULT = NAME
    }
}
