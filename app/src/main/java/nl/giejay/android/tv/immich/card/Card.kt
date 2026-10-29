package nl.giejay.android.tv.immich.card

data class Card(
    override val title: String,
    override val description: String?,
    override val id: String,
    override val thumbnailUrl: String?,
    override val backgroundUrl: String?,
    override var selected: Boolean = false,
    // Studio: set for videos, shown as a play badge with this length (e.g. "0:42")
    val videoDuration: String? = null) : ICard
