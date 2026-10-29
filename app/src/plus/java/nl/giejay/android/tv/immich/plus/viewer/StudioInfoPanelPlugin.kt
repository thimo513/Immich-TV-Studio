package nl.giejay.android.tv.immich.plus.viewer

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.plugin.ControllerButtonPlacement
import nl.giejay.mediaslider.plugin.ControllerButtonSpec
import nl.giejay.mediaslider.plugin.ControllerPluginContext
import nl.giejay.mediaslider.plugin.SliderControllerPlugin
import nl.giejay.mediaslider.plugin.SliderKeyEventPlugin
import nl.giejay.mediaslider.plugin.SliderKeyEventResult
import nl.giejay.mediaslider.plugin.SliderKeyEventState
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPluginContext

/**
 * Info button in the controller bar: opens a panel with everything known about the photo (date,
 * time, place, people, camera, file, description, album). It follows the photo when paging and
 * closes with the button again or with Back. While it is open the fading photo info stays hidden.
 */
class StudioInfoPanelPlugin(private val overlay: StudioInfoOverlayPlugin) :
    SliderControllerPlugin, SliderViewPlugin<Unit?>, SliderKeyEventPlugin {

    private var panel: View? = null
    private var rows: LinearLayout? = null
    private var ioScope: CoroutineScope? = null
    private var current: SliderItem? = null
    private var open = false

    override fun attachView(rootView: ConstraintLayout, state: Unit?) {
        val view = LayoutInflater.from(rootView.context).inflate(R.layout.studio_viewer_details, rootView, false)
        panel = view.findViewById(R.id.studio_details_panel)
        rows = view.findViewById(R.id.studio_details_rows)
        rootView.addView(view, ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT
        ))
    }

    override fun onLoadConfig(context: SliderViewPluginContext, config: MediaSliderConfiguration, state: Unit?) {
        ioScope = context.ioScope
    }

    override fun onPageSettled(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        sliderItem: SliderItemViewHolder,
        sliderItemIndex: Int,
        handler: Handler,
        state: Unit?
    ) {
        current = sliderItem.mainItem
        if (open) fill(sliderItem.mainItem)
    }

    override fun provideControllerButton(context: ControllerPluginContext): ControllerButtonSpec {
        current = context.sliderItem.mainItem
        val button = createDefaultControllerButton(context, R.string.studio_info, R.drawable.studio_ic_info)
        button.setOnClickListener { if (open) close() else show() }
        return ControllerButtonSpec(
            button = button,
            placement = ControllerButtonPlacement.LEFT_OF,
            anchorViewId = com.zeuskartik.mediaslider.R.id.image_slideshow_reverse
        )
    }

    override fun onKeyDown(event: KeyEvent, state: SliderKeyEventState): SliderKeyEventResult {
        if (open && event.keyCode == KeyEvent.KEYCODE_BACK) {
            close()
            return SliderKeyEventResult.HANDLED_CONSUME
        }
        return SliderKeyEventResult.UNHANDLED
    }

    private fun show() {
        val view = panel ?: return
        open = true
        overlay.suppressed = true
        current?.let { fill(it) }
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationX = 60 * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationX(0f).setDuration(260L).start()
    }

    private fun close() {
        val view = panel ?: return
        open = false
        overlay.suppressed = false
        view.animate().alpha(0f).translationX(40 * view.resources.displayMetrics.density).setDuration(200L)
            .withEndAction { if (!open) view.visibility = View.GONE }.start()
    }

    private fun fill(item: SliderItem) {
        val scope = ioScope ?: return
        val context = panel?.context ?: return
        scope.launch {
            fun clean(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
            val place = listOfNotNull(clean(item.get(MetaDataType.CITY)), clean(item.get(MetaDataType.COUNTRY)))
                .distinct().joinToString(", ")
            val entries = listOf(
                Entry(R.drawable.studio_ic_event, R.string.studio_info_date, clean(item.get(MetaDataType.DATE))),
                Entry(R.drawable.studio_ic_clock, R.string.studio_info_time, formatTimeOfDay(context, item.get(MetaDataType.TIME))),
                Entry(R.drawable.studio_ic_place, R.string.studio_info_place, place),
                Entry(R.drawable.studio_ic_person, R.string.studio_info_people, formatPeople(context, item.get(MetaDataType.PEOPLE))),
                Entry(R.drawable.studio_ic_camera, R.string.studio_info_camera, clean(item.get(MetaDataType.CAMERA))),
                Entry(R.drawable.studio_ic_album, R.string.studio_info_album, clean(item.get(MetaDataType.ALBUM_NAME))),
                Entry(R.drawable.studio_ic_notes, R.string.studio_info_description, clean(item.get(MetaDataType.DESCRIPTION))),
                Entry(R.drawable.studio_ic_file, R.string.studio_info_file, clean(item.get(MetaDataType.FILENAME)))
            ).filter { !it.value.isNullOrEmpty() }
            withContext(Dispatchers.Main) {
                if (item !== current) return@withContext
                val container = rows ?: return@withContext
                container.removeAllViews()
                entries.forEach { container.addView(row(container.context, it)) }
            }
        }
    }

    private class Entry(@DrawableRes val icon: Int, @StringRes val label: Int, val value: String?)

    private fun row(context: Context, entry: Entry): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // one line per entry (label and value side by side), so all of it fits on a TV screen
            setPadding(0, dp(5), 0, dp(5))
            addView(ImageView(context).apply {
                setImageResource(entry.icon)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.studio_latte))
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
            addView(TextView(context).apply {
                setText(entry.label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, R.color.studio_text_dim))
            }, LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = entry.value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ContextCompat.getColor(context, R.color.studio_text))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    override fun onDestroy(context: SliderViewPluginContext, state: Unit?) {
        panel?.let { view ->
            view.animate().cancel()
            (view.parent?.parent as? ConstraintLayout)?.removeView(view.parent as View)
        }
        panel = null
        rows = null
        current = null
        open = false
    }
}
