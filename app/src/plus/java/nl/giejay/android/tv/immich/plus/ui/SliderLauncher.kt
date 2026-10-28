package nl.giejay.android.tv.immich.plus.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_DPAD_SEEK_IN_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MERGE_PORTRAIT_PHOTOS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_PAN_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_SHOW_DATE_TOP_LEFT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_SCROLL_PANORAMAS
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel

/** Opens the photo viewer for a list of assets, starting at [startAssetId]. */
object SliderLauncher {

    /**
     * @param story memories: autoplay with a story progress bar, no portrait merging (memory
     * payloads have no reliable dimensions) and no "photo x of y" counter.
     */
    fun open(fragment: Fragment, assets: List<Asset>, startAssetId: String?, story: Boolean = false) {
        if (assets.isEmpty()) return
        val sliderViewModel = ViewModelProvider(fragment.requireActivity())[MediaSliderViewModel::class.java]
        fragment.lifecycleScope.launch(Dispatchers.Default) {
            val items = assets.toSliderItems(
                keepOrder = true,
                mergePortrait = !story && PreferenceManager.get(SLIDER_MERGE_PORTRAIT_PHOTOS)
            )
            val start = items.indexOfFirst { startAssetId != null && it.ids().contains(startAssetId) }.coerceAtLeast(0)
            withContext(Dispatchers.Main) {
                if (!fragment.isAdded) return@withContext
                sliderViewModel.configuration = MediaSliderConfiguration(
                    start,
                    PreferenceManager.get(SLIDER_INTERVAL),
                    PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
                    isVideoSoundEnable = true,
                    items,
                    null,
                    animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
                    maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
                    maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
                    glideTransformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
                    enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
                    gradiantOverlay = false,
                    metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER)
                        .filter { !story || it.type != MetaDataType.MEDIA_COUNT },
                    zoomAndScrollPanorama = PreferenceManager.get(SLIDER_ZOOM_SCROLL_PANORAMAS),
                    zoomEffectPercent = PreferenceManager.get(SLIDER_ZOOM_EFFECT),
                    panEffectPercent = PreferenceManager.get(SLIDER_PAN_EFFECT),
                    useLargeVideoBuffer = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
                    dpadSeeksInVideo = PreferenceManager.get(SLIDER_DPAD_SEEK_IN_VIDEO),
                    showDateTopLeft = PreferenceManager.get(SLIDER_SHOW_DATE_TOP_LEFT)
                )
                fragment.findNavController().navigate(
                    AlbumDetailsFragmentDirections.actionToPhotoSlider(timelineView = story)
                )
            }
        }
    }
}
