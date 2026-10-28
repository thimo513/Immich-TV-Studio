package nl.giejay.android.tv.immich.plus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.card.Card

/** Size of a card in dp. Wide for photos and places, square for faces. */
enum class CardShape(val widthDp: Int, val heightDp: Int) {
    WIDE(206, 116),
    SQUARE(150, 150),
    HERO(320, 180)
}

/**
 * The Studio card: the picture fills the whole card with rounded corners, the caption (date,
 * place, name) sits on a gradient at the bottom. Replaces the Leanback ImageCardView with its
 * grey footer. Non-http thumbnails are drawable names (folder icons) and are shown as icons.
 */
class StudioCardPresenter(private val shape: CardShape = CardShape.WIDE) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.studio_card, parent, false)
        val density = parent.resources.displayMetrics.density
        view.layoutParams = view.layoutParams.apply {
            width = (shape.widthDp * density).toInt()
            height = (shape.heightDp * density).toInt()
        }
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as? Card ?: return
        val view = viewHolder.view
        val image = view.findViewById<ImageView>(R.id.studio_card_image)
        val title = view.findViewById<TextView>(R.id.studio_card_title)
        val subtitle = view.findViewById<TextView>(R.id.studio_card_subtitle)
        val scrim = view.findViewById<View>(R.id.studio_card_scrim)

        view.tag = card
        title.text = card.title
        title.visibility = if (card.title.isBlank()) View.GONE else View.VISIBLE
        subtitle.text = card.description
        subtitle.visibility = if (card.description.isNullOrBlank()) View.GONE else View.VISIBLE
        scrim.visibility = if (card.title.isBlank() && card.description.isNullOrBlank()) View.GONE else View.VISIBLE

        val url = card.thumbnailUrl
        if (url != null && url.startsWith("http")) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
            Glide.with(view).load(url).centerCrop().into(image)
        } else if (url != null) {
            // icon cards (folders): resource name of a drawable
            Glide.with(view).clear(image)
            val pad = (shape.heightDp * view.resources.displayMetrics.density / 4).toInt()
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setPadding(pad, pad / 2, pad, pad + pad / 2)
            val id = view.resources.getIdentifier(url, "drawable", view.context.packageName)
            if (id != 0) image.setImageResource(id) else image.setImageDrawable(null)
        } else {
            Glide.with(view).clear(image)
            image.setImageDrawable(null)
        }
        view.foreground = ContextCompat.getDrawable(
            view.context,
            if (card.selected) R.drawable.studio_card_selected else R.drawable.studio_card_focus
        )
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val image = viewHolder.view.findViewById<ImageView>(R.id.studio_card_image)
        try {
            Glide.with(viewHolder.view).clear(image)
        } catch (e: IllegalArgumentException) {
            // activity already gone
        }
        image.setImageDrawable(null)
    }
}
