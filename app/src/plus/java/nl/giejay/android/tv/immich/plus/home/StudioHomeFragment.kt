package nl.giejay.android.tv.immich.plus.home

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Memory
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.plus.api.PlusApi
import nl.giejay.android.tv.immich.plus.places.PlaceAssetsFragment
import nl.giejay.android.tv.immich.plus.toDateCard
import nl.giejay.android.tv.immich.plus.ui.CardShape
import nl.giejay.android.tv.immich.plus.ui.SliderLauncher
import nl.giejay.android.tv.immich.plus.ui.StudioCardPresenter
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import java.time.Year
import java.util.IdentityHashMap

/**
 * Start page of Immich TV Studio: horizontal rows like a streaming app. Memories first, then the
 * newest photos, favorites, places, people, albums and a random selection. Each row loads on its
 * own and appears as soon as it has content, always in the same order.
 */
class StudioHomeFragment : RowsSupportFragment() {
    private val rowsAdapter = ArrayObjectAdapter(
        ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL, false).apply {
            shadowEnabled = false
            selectEffectEnabled = false
        }
    )
    private val actions = IdentityHashMap<Card, () -> Unit>()
    private val rowKeys = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> actions[item]?.invoke() }
        if (PreferenceManager.isLoggedId()) load()
    }

    override fun onResume() {
        super.onResume()
        // other pages leave their photo backdrop behind
        BackgroundManager.getInstance(requireActivity()).takeIf { it.isAttached }?.drawable =
            ColorDrawable(ContextCompat.getColor(requireContext(), R.color.studio_bg))
    }

    private fun load() {
        val api = ApiClient.getClient(ApiClientConfig.fromPrefs())
        lifecycleScope.launch {
            launch {
                api.getMemories().getOrNull()?.filter { it.assets.isNotEmpty() }?.let { memories ->
                    addRow(ROW_MEMORIES, getString(R.string.studio_row_memories), CardShape.HERO,
                        memories.map { memory -> memoryCard(memory) to { SliderLauncher.open(this@StudioHomeFragment, memory.assets, null, story = true) } })
                }
            }
            launch {
                api.listAssets(1, ROW_SIZE, contentType = ContentType.ALL).getOrNull()?.let {
                    addPhotoRow(ROW_RECENT, getString(R.string.studio_row_recent), it.assets)
                }
            }
            launch {
                PlusApi.favorites(1, ROW_SIZE, ContentType.ALL).getOrNull()?.let {
                    addPhotoRow(ROW_FAVORITES, getString(R.string.plus_favorites), it)
                }
            }
            launch {
                PlusApi.cities().getOrNull()?.let { cities ->
                    addRow(ROW_PLACES, getString(R.string.plus_places), CardShape.WIDE,
                        cities.sortedBy { it.exifInfo?.city?.lowercase() }.map { asset -> placeCard(asset) to { openPlace(asset) } })
                }
            }
            launch {
                api.listPeople().getOrNull()?.let { people ->
                    addRow(ROW_PEOPLE, getString(R.string.people), CardShape.SQUARE, people.map { person ->
                        val name = person.name ?: ""
                        Card(name, "", person.id.toString(), ApiUtil.getPersonThumbnail(person.id), null) to {
                            findNavController().navigate(HomeFragmentDirections.actionHomeFragmentToPersonAssetsFragment(person.id.toString(), name))
                        }
                    })
                }
            }
            launch {
                api.listAlbums().getOrNull()?.let { albums ->
                    addRow(ROW_ALBUMS, getString(R.string.albums), CardShape.WIDE,
                        albums.sortedByDescending { it.endDate ?: it.updatedAt }.map { album ->
                            Card(
                                album.albumName,
                                resources.getQuantityString(R.plurals.studio_photo_count, album.assetCount, album.assetCount),
                                album.id,
                                ApiUtil.getThumbnailUrl(album.albumThumbnailAssetId, "thumbnail"),
                                null
                            ) to {
                                findNavController().navigate(HomeFragmentDirections.actionHomeFragmentToAlbumDetailsFragment(album.id, album.albumName))
                            }
                        })
                }
            }
            launch {
                api.listAssets(1, ROW_SIZE, random = true, contentType = ContentType.ALL).getOrNull()?.let {
                    addPhotoRow(ROW_RANDOM, getString(R.string.studio_row_random), it.assets)
                }
            }
        }
    }

    private fun addPhotoRow(key: Int, title: String, assets: List<Asset>) {
        addRow(key, title, CardShape.WIDE, assets.map { asset ->
            asset.toDateCard() to { SliderLauncher.open(this, assets, asset.id) }
        })
    }

    /** Adds a row at the position given by its [key], so rows keep their order however fast they load. */
    private fun addRow(key: Int, title: String, shape: CardShape, cards: List<Pair<Card, () -> Unit>>) {
        if (cards.isEmpty() || !isAdded) return
        val items = ArrayObjectAdapter(StudioCardPresenter(shape))
        cards.forEach { (card, action) ->
            items.add(card)
            actions[card] = action
        }
        val position = rowKeys.count { it < key }
        rowKeys.add(position, key)
        rowsAdapter.add(position, ListRow(HeaderItem(key.toLong(), title), items))
    }

    private fun memoryCard(memory: Memory): Card {
        val years = (Year.now().value - memory.data.year).coerceAtLeast(1)
        val cover = memory.assets.first()
        return Card(
            resources.getQuantityString(R.plurals.years_ago, years, years),
            resources.getQuantityString(R.plurals.studio_photo_count, memory.assets.size, memory.assets.size),
            memory.id,
            ApiUtil.getThumbnailUrl(cover.id, "preview"),
            null
        )
    }

    private fun placeCard(asset: Asset): Card = Card(
        asset.exifInfo?.city ?: "",
        asset.exifInfo?.country ?: "",
        asset.id,
        ApiUtil.getThumbnailUrl(asset.id, "thumbnail"),
        null
    )

    private fun openPlace(asset: Asset) {
        val args = Bundle().apply {
            putString(PlaceAssetsFragment.ARG_CITY, asset.exifInfo?.city)
            putString(PlaceAssetsFragment.ARG_COUNTRY, asset.exifInfo?.country)
        }
        findNavController().navigate(R.id.plusPlaceAssetsFragment, args)
    }

    companion object {
        private const val ROW_SIZE = 30
        private const val ROW_MEMORIES = 0
        private const val ROW_RECENT = 1
        private const val ROW_FAVORITES = 2
        private const val ROW_PLACES = 3
        private const val ROW_PEOPLE = 4
        private const val ROW_ALBUMS = 5
        private const val ROW_RANDOM = 6
    }
}
