package nl.giejay.android.tv.immich.plus.viewer

import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.plugin.SliderControllerPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPluginContext
import nl.giejay.mediaslider.view.MediaSliderController

/**
 * Photo info of the viewer, replacing the configurable metadata lists: date and time top left,
 * place bottom right. It appears with every new photo and fades out after a few seconds, so it
 * never covers the picture for long. While the controls are open it stays visible.
 */
class StudioInfoOverlayPlugin : SliderViewPlugin<Unit?>, SliderControllerPlugin {
    private var root: View? = null
    private var dateView: TextView? = null
    private var timeView: TextView? = null
    private var placeView: TextView? = null
    private var shownAssetId: String? = null
    private var controlsVisible = false
    private val hide = Runnable { fade(false) }

    override fun attachView(rootView: ConstraintLayout, state: Unit?) {
        val view = LayoutInflater.from(rootView.context).inflate(R.layout.studio_viewer_info, rootView, false)
        root = view
        dateView = view.findViewById(R.id.studio_info_date)
        timeView = view.findViewById(R.id.studio_info_time)
        placeView = view.findViewById(R.id.studio_info_place)
        rootView.addView(view, ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT
        ))
    }

    override fun onPageSelected(context: SliderViewPluginContext, sliderItemIndex: Int, state: Unit?) {
        // the previous photo's info must not linger while the next one slides in
        shownAssetId = null
        root?.apply {
            removeCallbacks(hide)
            animate().cancel()
            alpha = 0f
        }
    }

    override fun onPageSettled(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        sliderItem: SliderItemViewHolder,
        sliderItemIndex: Int,
        handler: Handler,
        state: Unit?
    ) {
        val item = sliderItem.mainItem
        shownAssetId = item.id
        context.ioScope.launch {
            val date = item.get(MetaDataType.DATE)?.trim().orEmpty()
            val time = item.get(MetaDataType.TIME)?.trim().orEmpty()
            val place = listOfNotNull(item.get(MetaDataType.CITY), item.get(MetaDataType.COUNTRY))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
            withContext(Dispatchers.Main) {
                if (shownAssetId != item.id) return@withContext
                show(date, time, place)
            }
        }
    }

    private fun show(date: String, time: String, place: String) {
        val view = root ?: return
        dateView?.text = date
        dateView?.visibility = if (date.isEmpty()) View.GONE else View.VISIBLE
        val shortTime = TIME_WITH_SECONDS.matchEntire(time)?.groupValues?.get(1) ?: time
        timeView?.text = if (shortTime.isEmpty()) "" else view.context.getString(R.string.studio_time_of_day, shortTime)
        timeView?.visibility = if (shortTime.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.studio_info_when).visibility =
            if (date.isEmpty() && shortTime.isEmpty()) View.GONE else View.VISIBLE
        placeView?.text = place
        placeView?.visibility = if (place.isEmpty()) View.GONE else View.VISIBLE
        fade(true)
        scheduleHide(SHOW_MS)
    }

    override fun onControllerVisibilityChanged(
        isVisible: Boolean,
        rootView: View,
        controller: MediaSliderController,
        config: MediaSliderConfiguration
    ) {
        controlsVisible = isVisible
        // the place sits in the corner of the control bar: lift it above the bar while it is open
        placeView?.let { place ->
            val bar = rootView.findViewById<View>(com.zeuskartik.mediaslider.R.id.image_controller)
            val lift = if (isVisible) -((bar?.height ?: 0).coerceAtLeast(MIN_BAR_PX)).toFloat() else 0f
            place.animate().translationY(lift).setDuration(220L).start()
        }
        if (shownAssetId == null) return
        if (isVisible) {
            root?.removeCallbacks(hide)
            fade(true)
        } else {
            scheduleHide(AFTER_CONTROLS_MS)
        }
    }

    private fun scheduleHide(delayMs: Long) {
        val view = root ?: return
        view.removeCallbacks(hide)
        if (!controlsVisible) view.postDelayed(hide, delayMs)
    }

    private fun fade(visible: Boolean) {
        root?.animate()?.cancel()
        root?.animate()?.alpha(if (visible) 1f else 0f)?.setDuration(if (visible) 280L else 700L)?.start()
    }

    override fun onDestroy(context: SliderViewPluginContext, state: Unit?) {
        root?.let { view ->
            view.removeCallbacks(hide)
            view.animate().cancel()
            (view.parent as? ConstraintLayout)?.removeView(view)
        }
        root = null
        dateView = null
        timeView = null
        placeView = null
        shownAssetId = null
    }

    private companion object {
        val TIME_WITH_SECONDS = Regex("""(\d{1,2}:\d{2}):\d{2}""")
        const val SHOW_MS = 4500L
        const val MIN_BAR_PX = 150
        const val AFTER_CONTROLS_MS = 2500L
    }
}
