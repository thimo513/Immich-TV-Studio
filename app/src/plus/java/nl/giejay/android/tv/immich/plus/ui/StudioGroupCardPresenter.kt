package nl.giejay.android.tv.immich.plus.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.card.ICard

/** "See all" tile that opens a whole group (favorites, places, ...) from the start page. */
data class StudioGroupCard(
    override val title: String,
    @DrawableRes val icon: Int,
    @ColorRes val color: Int,
    override val id: String = "studio_group_$title",
    override val description: String? = null,
    override val thumbnailUrl: String? = null,
    override val backgroundUrl: String? = null,
    override val selected: Boolean = false
) : ICard

class StudioGroupCardPresenter(private val shape: CardShape) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.studio_group_card, parent, false)
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
        val card = item as? StudioGroupCard ?: return
        val view = viewHolder.view
        // Leanback's row helper swaps in its own, almost square clip outline after the view was
        // created, so the photo stuck out of the rounded frame at the corners; restore ours
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        val context = view.context
        view.findViewById<TextView>(R.id.studio_group_title).text = card.title
        view.findViewById<ImageView>(R.id.studio_group_icon).apply {
            setImageResource(card.icon)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.studio_on_accent))
        }
        // own copy of the background, every row has its own colour
        val background = (ContextCompat.getDrawable(context, R.drawable.studio_group_bg) as LayerDrawable).mutate() as LayerDrawable
        (background.findDrawableByLayerId(R.id.studio_group_fill) as GradientDrawable).setColor(ContextCompat.getColor(context, card.color))
        view.background = background
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
