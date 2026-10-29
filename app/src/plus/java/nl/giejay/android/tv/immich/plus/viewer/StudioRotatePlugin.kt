package nl.giejay.android.tv.immich.plus.viewer

import android.content.Context
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.children
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.plus.api.PlusApi
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.plugin.ControllerButtonPlacement
import nl.giejay.mediaslider.plugin.ControllerButtonSpec
import nl.giejay.mediaslider.plugin.ControllerPluginContext
import nl.giejay.mediaslider.plugin.SliderControllerPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPluginContext
import nl.giejay.mediaslider.view.TouchImageView
import kotlin.math.min

/**
 * Rotate button in the controller bar: every press turns the photo 90 degrees further clockwise
 * (twice 180, three times 270, four times back to the start). As soon as it is turned, a save
 * button appears next to it, which stores the rotation in Immich as an edit (the original file
 * stays untouched). A rotation that was not saved is undone when moving to the next photo.
 */
class StudioRotatePlugin(private val scope: CoroutineScope) : SliderControllerPlugin, SliderViewPlugin<Unit?> {
    private var pager: ViewPager? = null
    private var assetId: String? = null
    private var rotatedView: View? = null

    /** Quarter turns not saved yet (0..3). */
    private var pendingTurns = 0

    /** Quarter turns shown, counting on so the animation always turns clockwise. */
    private var shownTurns = 0

    private var rotateButton: ImageButton? = null
    private var saveButton: ImageButton? = null
    private var buttonContext: ControllerPluginContext? = null
    private var saving = false

    override fun provideControllerButton(context: ControllerPluginContext): ControllerButtonSpec? {
        saveButton = null
        if (context.isVideo || context.hasSecondaryItem) {
            rotateButton = null
            return null
        }
        pager = context.rootView.rootView.findViewById(com.zeuskartik.mediaslider.R.id.pager)
        val id = context.sliderItem.mainItem.id
        if (id != assetId) {
            discardPending()
            assetId = id
        }
        buttonContext = context
        val button = createDefaultControllerButton(context, R.string.studio_rotate, R.drawable.studio_ic_rotate)
        button.setOnClickListener { rotate() }
        rotateButton = button
        return ControllerButtonSpec(
            button = button,
            placement = ControllerButtonPlacement.LEFT_OF,
            anchorViewId = com.zeuskartik.mediaslider.R.id.image_slideshow_reverse
        )
    }

    override fun onConfigureController(context: ControllerPluginContext) {
        // the bar is rebuilt while a rotation is still waiting to be saved: offer saving again
        if (pendingTurns != 0 && context.sliderItem.mainItem.id == assetId) showSaveButton()
    }

    override fun onPageSelected(context: SliderViewPluginContext, sliderItemIndex: Int, state: Unit?) {
        discardPending()
    }

    /** Moving on without saving undoes the rotation (a saved one stays: Immich has it now). */
    private fun discardPending() {
        if (pendingTurns != 0) {
            rotatedView?.animate()?.rotation(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(150L)?.start()
        }
        pendingTurns = 0
        shownTurns = 0
        rotatedView = null
    }

    override fun onPageSettled(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        sliderItem: SliderItemViewHolder,
        sliderItemIndex: Int,
        handler: Handler,
        state: Unit?
    ) {}

    private fun rotate() {
        val image = currentImageView() ?: return
        if (image.isZoomed) image.resetZoom()
        pendingTurns = (pendingTurns + 1) % 4
        shownTurns++
        rotatedView = image
        image.animate()
            .rotation(shownTurns * 90f)
            .scaleX(fitScale(image, shownTurns))
            .scaleY(fitScale(image, shownTurns))
            .setDuration(260L)
            .start()
        if (pendingTurns == 0) removeSaveButton() else showSaveButton()
    }

    /** Sideways the photo is shrunk so that all of it stays on screen. */
    private fun fitScale(image: TouchImageView, turns: Int): Float {
        if (turns % 2 == 0) return 1f
        val drawable = image.drawable ?: return 1f
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        if (w <= 0 || h <= 0 || dw <= 0 || dh <= 0) return 1f
        val fit = min(w / dw, h / dh)
        return min(w / (dh * fit), h / (dw * fit))
    }

    private fun showSaveButton() {
        if (saveButton?.parent != null) return
        val context = buttonContext ?: return
        val rotate = rotateButton ?: return
        val row = rotate.parent as? LinearLayout ?: return
        val save = createDefaultControllerButton(context, R.string.studio_rotate_save, R.drawable.studio_ic_save)
        save.setOnClickListener { save(save.context) }
        row.addView(save, row.indexOfChild(rotate) + 1)
        // keep the D-pad chain intact: rotate -> save -> whatever followed rotate
        save.nextFocusLeftId = rotate.id
        save.nextFocusRightId = rotate.nextFocusRightId
        row.findViewById<View>(rotate.nextFocusRightId)?.nextFocusLeftId = save.id
        rotate.nextFocusRightId = save.id
        saveButton = save
    }

    private fun removeSaveButton(focusRotate: Boolean = false) {
        val save = saveButton ?: return
        val row = save.parent as? LinearLayout
        rotateButton?.let { rotate ->
            rotate.nextFocusRightId = save.nextFocusRightId
            row?.findViewById<View>(save.nextFocusRightId)?.nextFocusLeftId = rotate.id
            if (focusRotate || save.hasFocus()) rotate.requestFocus()
        }
        row?.removeView(save)
        saveButton = null
    }

    private fun save(context: Context) {
        val id = assetId ?: return
        val turns = pendingTurns
        if (turns == 0 || saving) return
        // not disabled while saving: a disabled button drops the focus out of the bar
        saving = true
        scope.launch {
            PlusApi.rotate(id, turns).fold(
                { error ->
                    saving = false
                    Toast.makeText(context, context.getString(R.string.studio_rotate_failed, error), Toast.LENGTH_LONG).show()
                },
                {
                    // the photo on screen stays turned; from now on Immich delivers it turned
                    saving = false
                    if (assetId == id) pendingTurns = 0
                    removeSaveButton(focusRotate = true)
                    Toast.makeText(context, R.string.studio_rotate_saved, Toast.LENGTH_SHORT).show()
                    // cached pictures of the old orientation must not come back
                    Glide.get(context).clearMemory()
                    withContext(Dispatchers.IO) { Glide.get(context).clearDiskCache() }
                }
            )
        }
    }

    private fun currentImageView(): TouchImageView? {
        val pager = pager ?: return null
        return pager.findViewWithTag<ViewGroup>("view${pager.currentItem}")?.children?.firstOrNull() as? TouchImageView
    }
}
