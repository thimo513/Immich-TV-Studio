package nl.giejay.android.tv.immich.plus.ui

/**
 * Ambient light behind the pages, like TV ambient lighting: the focused photo is reduced to a few
 * colour zones (a 4 x 3 pixel version) and scaled up smoothly by the BackgroundManager, so its
 * colours glow behind the page without the picture itself being recognisable.
 */
object StudioAmbient {
    const val WIDTH_PX = 4
    const val HEIGHT_PX = 3

    /** Darkens the colours just enough for text and cards to stay readable. */
    val TINT = 0xB817120F.toInt()
}
