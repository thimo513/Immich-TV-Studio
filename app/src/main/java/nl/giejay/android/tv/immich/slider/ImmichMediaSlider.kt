package nl.giejay.android.tv.immich.slider

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.plus.viewer.StudioInfoOverlayPlugin
import nl.giejay.android.tv.immich.plus.viewer.StudioInfoPanelPlugin
import nl.giejay.android.tv.immich.plus.viewer.StudioRotatePlugin
import nl.giejay.mediaslider.plugin.DateOverlayViewPlugin
import nl.giejay.mediaslider.plugin.MetadataViewPlugin
import nl.giejay.mediaslider.plugin.TimelineStoryProgressPlugin
import nl.giejay.mediaslider.view.MediaSliderFragment
import nl.giejay.mediaslider.view.MediaSliderView
import timber.log.Timber

class ImmichMediaSlider : MediaSliderFragment() {
    private val favoriteService = FavoriteService()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return MediaSliderView(requireContext())
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Timber.i("Loading ${this.javaClass.simpleName}")

        val bundle = ImmichMediaSliderArgs.fromBundle(requireArguments())
        val config = sliderViewModel.configuration

        if (config == null || config.items.isEmpty()) {
            Timber.i("No items to play for photoslider")
            Toast.makeText(requireContext(), getString(R.string.no_items_to_play), Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        setDefaultExoFactory(
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("x-api-key" to PreferenceManager.get(API_KEY)))
        )

        // the configuration lives in the activity view model: add plugins only once
        val alreadyConfigured = config.keyEventPlugins.any { it is nl.giejay.android.tv.immich.plus.zoom.ZoomPlugin }
        if (bundle.timelineView && !alreadyConfigured) {
            val timelinePlugin = TimelineStoryProgressPlugin()
            config.viewPlugins += timelinePlugin
            config.controllerPlugins += timelinePlugin
            config.keyEventPlugins += timelinePlugin
        }

        if (!alreadyConfigured) {
            val enabledPlugins = PreferenceManager.createEnabledSliderPlugins(lifecycleScope, favoriteService)
            // Studio: one fixed photo info (date + time top left, place bottom right, fades out)
            // instead of the configurable metadata lists and the separate date line
            val isOldInfo = { plugin: Any -> plugin is MetadataViewPlugin || plugin is DateOverlayViewPlugin }
            val info = StudioInfoOverlayPlugin()
            // Studio: info panel and rotation (both buttons sit before the slideshow buttons)
            val details = StudioInfoPanelPlugin(info)
            val rotate = StudioRotatePlugin(lifecycleScope)
            config.controllerPlugins += enabledPlugins.controllerPlugins.filterNot(isOldInfo) + info + details + rotate
            config.viewPlugins += enabledPlugins.viewPlugins.filterNot(isOldInfo) + info + details + rotate
            config.keyEventPlugins += enabledPlugins.keyEventPlugins
            // zoom levels for photos (first in the key list, so it sees the arrow keys while zoomed)
            nl.giejay.android.tv.immich.plus.zoom.ZoomPlugin().let { zoom ->
                config.controllerPlugins += zoom
                // the info panel first: Back closes it before anything else
                config.keyEventPlugins = listOfNotNull(config.controllerPlugins.filterIsInstance<StudioInfoPanelPlugin>().firstOrNull(), zoom) +
                    config.keyEventPlugins
            }
        }

        loadMediaSliderView(config)

        if (bundle.timelineView) {
            // Memories: timeline plugin mounts story progress; start autoplay.
            (view as MediaSliderView).toggleSlideshow(false)
        }
    }
}
