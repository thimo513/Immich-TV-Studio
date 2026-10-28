package nl.giejay.android.tv.immich.plus.zoom

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.children
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.R
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.plugin.ControllerButtonPlacement
import nl.giejay.mediaslider.plugin.ControllerButtonSpec
import nl.giejay.mediaslider.plugin.ControllerPluginContext
import nl.giejay.mediaslider.plugin.SliderControllerPlugin
import nl.giejay.mediaslider.plugin.SliderKeyEventPlugin
import nl.giejay.mediaslider.plugin.SliderKeyEventResult
import nl.giejay.mediaslider.plugin.SliderKeyEventState
import nl.giejay.mediaslider.view.MediaSliderController
import nl.giejay.mediaslider.view.TouchImageView

/**
 * Zoom for photos in the viewer: a magnifier button in the controller bar opens a choice of zoom
 * levels. While zoomed the arrow keys move the visible part (hold to glide), a mini map in the
 * corner shows where it is, Back zooms out again. Made for plain D-pad remotes (e.g. Nokia
 * Streaming Box 8000): arrows, OK and Back are all it needs.
 */
class ZoomPlugin : SliderControllerPlugin, SliderKeyEventPlugin {
    private var pager: ViewPager? = null

    override fun provideControllerButton(context: ControllerPluginContext): ControllerButtonSpec? {
        if (context.isVideo || context.hasSecondaryItem) return null
        pager = context.rootView.rootView.findViewById(com.zeuskartik.mediaslider.R.id.pager)

        val button = createDefaultControllerButton(
            context = context,
            contentDescriptionRes = R.string.plus_zoom,
            iconRes = R.drawable.ic_plus_zoom
        )
        button.visibility = View.VISIBLE
        button.setOnClickListener { showZoomChoice(context) }
        return ControllerButtonSpec(
            button = button,
            placement = ControllerButtonPlacement.RIGHT_OF,
            anchorViewId = com.zeuskartik.mediaslider.R.id.media_play_pause
        )
    }

    private fun currentImageView(): TouchImageView? {
        val pager = pager ?: return null
        return pager.findViewWithTag<ViewGroup>("view${pager.currentItem}")?.children?.firstOrNull() as? TouchImageView
    }

    private fun showZoomChoice(context: ControllerPluginContext) {
        val image = currentImageView() ?: return
        val ctx = context.context
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = GradientDrawable().apply {
                setColor(0xF2202124.toInt())
                cornerRadius = dp(12).toFloat()
            }
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.plus_zoom_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(8))
            })
        }
        var firstButton: Button? = null
        for (level in LEVELS) {
            val label = if (level == 1f) ctx.getString(R.string.plus_zoom_off) else "${level.toInt()}x"
            val choice = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setOnClickListener {
                    dialog.dismiss()
                    applyZoom(context, image, level)
                }
            }
            if (firstButton == null) firstButton = choice
            content.addView(choice, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
        }
        dialog.show()
        // start on the current level so a second press of OK changes it by one step
        val current = LEVELS.indexOfFirst { it >= image.currentZoom - 0.05f }.coerceAtLeast(0)
        (content.getChildAt(1 + current) ?: firstButton)?.requestFocus()
    }

    private fun applyZoom(context: ControllerPluginContext, image: TouchImageView, level: Float) {
        // a running slideshow would move on to the next photo while the user looks at a detail
        if (level > 1f && context.controller.slideShowPlaying) {
            context.controller.toggleSlideshow(false)
        }
        image.maxZoom = MAX_ZOOM
        if (level <= 1f) image.resetZoom() else image.setZoom(level)
        context.controller.hideOverlayControls()
        if (level > 1f) loadFullResolution(image, context.sliderItem.mainItem.id)
        // the matrix is applied on the next layout pass, read the visible part after it
        image.post { updateMiniMap(image) }
        if (level > 1f) {
            Toast.makeText(context.context, context.context.getString(R.string.plus_zoom_hint, level.toInt()), Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyDown(event: KeyEvent, state: SliderKeyEventState): SliderKeyEventResult = handleKey(event, state)

    override fun onKeyUp(event: KeyEvent, state: SliderKeyEventState): SliderKeyEventResult = handleKey(event, state)

    private fun handleKey(event: KeyEvent, state: SliderKeyEventState): SliderKeyEventResult {
        if (state.currentItemType != SliderItemType.IMAGE || state.isControllerVisible) {
            return SliderKeyEventResult.UNHANDLED
        }
        val image = currentImageView()?.takeIf { it.isZoomed }
        if (image == null) {
            // swiped to another photo or zoomed out with a gesture
            hideMiniMap()
            return SliderKeyEventResult.UNHANDLED
        }
        val isDown = event.action == KeyEvent.ACTION_DOWN
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (isDown) {
                    image.resetZoom()
                    hideMiniMap()
                }
                SliderKeyEventResult.HANDLED_CONSUME
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isDown) {
                    pan(image, event.keyCode, held = event.repeatCount > 0)
                    updateMiniMap(image)
                }
                SliderKeyEventResult.HANDLED_CONSUME
            }

            else -> SliderKeyEventResult.UNHANDLED
        }
    }

    private fun pan(image: TouchImageView, keyCode: Int, held: Boolean) {
        val center = image.scrollPosition ?: return
        // the visible part is 1/zoom of the picture: a single press moves by a quarter of it,
        // holding the key (auto repeat of the remote) glides in smaller steps
        val step = (if (held) 0.08f else 0.25f) / image.currentZoom
        var x = center.x
        var y = center.y
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> x -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> x += step
            KeyEvent.KEYCODE_DPAD_UP -> y -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> y += step
        }
        image.setScrollPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private var sharpAssetId: String? = null

    /**
     * The viewer shows the preview (about 1440 px), which gets soft when zoomed. Immich's
     * "fullsize" rendition is the full resolution as JPEG (also for HEIC originals); it replaces the
     * preview once loaded, keeping zoom and position. Capped at 4096 px in RGB_565 for TV memory.
     */
    private fun loadFullResolution(image: TouchImageView, assetId: String) {
        if (sharpAssetId == assetId) return
        val url = ApiUtil.getThumbnailUrl(assetId, "fullsize") ?: return
        Glide.with(image).asBitmap().load(url)
            .format(DecodeFormat.PREFER_RGB_565)
            .override(4096)
            .downsample(DownsampleStrategy.CENTER_INSIDE)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (!image.isAttachedToWindow || currentImageView() !== image) return
                    val zoom = image.currentZoom
                    val center = image.scrollPosition ?: PointF(0.5f, 0.5f)
                    image.setImageBitmap(resource)
                    image.post {
                        image.setZoom(zoom, center.x, center.y)
                        updateMiniMap(image)
                    }
                    sharpAssetId = assetId
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
    }

    private var miniMap: ZoomMiniMapView? = null

    /** Small overview in the lower right corner: the whole photo and the part that is visible. */
    private fun updateMiniMap(image: TouchImageView) {
        if (!image.isZoomed) {
            hideMiniMap()
            return
        }
        val drawable = image.drawable ?: return
        val decor = image.rootView as? FrameLayout ?: return
        val map = miniMap ?: ZoomMiniMapView(image.context).also { view ->
            val density = image.resources.displayMetrics.density
            // top right: the lower corners hold the photo's place, date and counter
            decor.addView(view, FrameLayout.LayoutParams((160 * density).toInt(), (160 * density).toInt(), Gravity.TOP or Gravity.END).apply {
                rightMargin = (32 * density).toInt()
                topMargin = (24 * density).toInt()
            })
            miniMap = view
        }
        map.update(drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight, image.zoomedRect)
        map.visibility = View.VISIBLE
    }

    private fun hideMiniMap() {
        sharpAssetId = null
        miniMap?.let { (it.parent as? ViewGroup)?.removeView(it) }
        miniMap = null
    }

    override fun onDestroy(controller: MediaSliderController) {
        hideMiniMap()
    }

    companion object {
        private val LEVELS = listOf(1f, 2f, 3f, 5f, 8f)
        private const val MAX_ZOOM = 10f
    }
}
