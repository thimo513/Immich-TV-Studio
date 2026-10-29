package nl.giejay.android.tv.immich.plus.home

import android.os.Bundle
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowHeaderPresenter
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
import nl.giejay.android.tv.immich.home.HomeFragment
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.home.IconHeaderItem
import nl.giejay.android.tv.immich.plus.api.PlusApi
import nl.giejay.android.tv.immich.plus.places.PlaceAssetsFragment
import nl.giejay.android.tv.immich.plus.toDateCard
import nl.giejay.android.tv.immich.plus.ui.CardShape
import nl.giejay.android.tv.immich.plus.ui.SliderLauncher
import nl.giejay.android.tv.immich.plus.ui.StudioCardPresenter
import nl.giejay.android.tv.immich.plus.ui.StudioGroupCard
import nl.giejay.android.tv.immich.plus.ui.StudioGroupCardPresenter
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import java.time.Year
import java.util.IdentityHashMap

/**
 * Start page of Immich TV Studio: horizontal rows like a streaming app. Memories first, then the
 * newest photos, favorites, places, people, albums and a random selection. Each row loads on its
 * own and appears as soon as it has content, always in the same order.
 *
 * Every row has its own pastel colour (title and "see all" tile). The tile in front of a row
 * opens the whole group as its menu page; the cards behind it open the photos.
 */
class StudioHomeFragment : RowsSupportFragment() {
    private val rowsAdapter = ArrayObjectAdapter(
        ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL, false).apply {
            shadowEnabled = false
            selectEffectEnabled = false
            headerPresenter = ColoredRowHeaderPresenter()
        }
    )
    private val actions = IdentityHashMap<Any, () -> Unit>()
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
            ContextCompat.getDrawable(requireContext(), R.drawable.studio_page_bg)
    }

    private fun load() {
        val api = ApiClient.getClient(ApiClientConfig.fromPrefs())
        lifecycleScope.launch {
            launch {
                api.getMemories().getOrNull()?.filter { it.assets.isNotEmpty() }?.let { memories ->
                    addRow(ROW_MEMORIES, getString(R.string.studio_row_memories), CardShape.HERO, null,
                        memories.map { memory -> memoryCard(memory) to { SliderLauncher.open(this@StudioHomeFragment, memory.assets, null, story = true) } })
                }
            }
            launch {
                api.listAssets(1, ROW_SIZE, contentType = ContentType.ALL).getOrNull()?.let {
                    addPhotoRow(ROW_RECENT, getString(R.string.studio_row_recent), it.assets,
                        Group(R.string.recent, R.drawable.studio_ic_recent))
                }
            }
            launch {
                PlusApi.favorites(1, ROW_SIZE, ContentType.ALL).getOrNull()?.let {
                    addPhotoRow(ROW_FAVORITES, getString(R.string.plus_favorites), it,
                        Group(R.string.plus_favorites, R.drawable.studio_ic_favorite))
                }
            }
            launch {
                PlusApi.cities().getOrNull()?.let { cities ->
                    addRow(ROW_PLACES, getString(R.string.plus_places), CardShape.WIDE,
                        Group(R.string.plus_places, R.drawable.studio_ic_place),
                        cities.sortedBy { it.exifInfo?.city?.lowercase() }.map { asset -> placeCard(asset) to { openPlace(asset) } })
                }
            }
            launch {
                api.listPeople().getOrNull()?.let { people ->
                    addRow(ROW_PEOPLE, getString(R.string.people), CardShape.SQUARE,
                        Group(R.string.people, R.drawable.studio_ic_person), people.map { person ->
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
                        Group(R.string.albums, R.drawable.studio_ic_album),
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
                    addPhotoRow(ROW_RANDOM, getString(R.string.studio_row_random), it.assets,
                        Group(R.string.random, R.drawable.studio_ic_shuffle))
                }
            }
        }
    }

    /** Menu page that shows the whole group of a row, see [HomeFragment.openSection]. */
    private class Group(@StringRes val menuName: Int, @DrawableRes val icon: Int)

    private fun addPhotoRow(key: Int, title: String, assets: List<Asset>, group: Group) {
        addRow(key, title, CardShape.WIDE, group, assets.map { asset ->
            asset.toDateCard() to { SliderLauncher.open(this, assets, asset.id) }
        })
    }

    /** Adds a row at the position given by its [key], so rows keep their order however fast they load. */
    private fun addRow(key: Int, title: String, shape: CardShape, group: Group?, cards: List<Pair<Card, () -> Unit>>) {
        if (cards.isEmpty() || !isAdded) return
        val items = ArrayObjectAdapter(
            ClassPresenterSelector()
                .addClassPresenter(Card::class.java, StudioCardPresenter(shape))
                .addClassPresenter(StudioGroupCard::class.java, StudioGroupCardPresenter(shape))
        )
        val home = parentFragment as? HomeFragment
        val menuName = group?.let { getString(it.menuName) }
        if (group != null && menuName != null && home?.hasSection(menuName) == true) {
            val tile = StudioGroupCard(title, group.icon, rowColor(key))
            items.add(tile)
            actions[tile] = { home.openSection(menuName) }
        }
        cards.forEach { (card, action) ->
            items.add(card)
            actions[card] = action
        }
        val position = rowKeys.count { it < key }
        rowKeys.add(position, key)
        rowsAdapter.add(position, ListRow(IconHeaderItem(key.toLong(), title, group?.icon ?: R.drawable.studio_ic_event), items))
        // rows load in any order: until the page is entered, the first row stays the selected one
        // (posted: the grid applies the insert first and would move the selection along with it)
        view?.post { if (isAdded && view?.hasFocus() != true) setSelectedPosition(0, false) }
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

    /** Row title in the colour of its row, with the row's icon in front. */
    private class ColoredRowHeaderPresenter : RowHeaderPresenter() {
        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
            super.onBindViewHolder(viewHolder, item)
            val header = (item as? ListRow)?.headerItem ?: return
            val title = viewHolder.view.findViewById<TextView>(androidx.leanback.R.id.row_header) ?: return
            val color = ContextCompat.getColor(title.context, rowColor(header.id.toInt()))
            title.setTextColor(color)
            val icon = (header as? IconHeaderItem)?.let { ContextCompat.getDrawable(title.context, it.icon)?.mutate() }
            icon?.apply {
                val size = (title.textSize * 1.1f).toInt()
                setBounds(0, 0, size, size)
                setTint(color)
            }
            title.setCompoundDrawablesRelative(icon, null, null, null)
            title.compoundDrawablePadding = (title.textSize * 0.5f).toInt()
        }

        // Leanback dims the titles of rows that are not selected far too much for these colours
        override fun onSelectLevelChanged(holder: RowHeaderPresenter.ViewHolder) {
            holder.view.alpha = 0.7f + 0.3f * holder.selectLevel
        }
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

        @ColorRes
        private fun rowColor(key: Int): Int = when (key) {
            ROW_MEMORIES -> R.color.studio_apricot
            ROW_RECENT -> R.color.studio_peach
            ROW_FAVORITES -> R.color.studio_rose
            ROW_PLACES -> R.color.studio_sky
            ROW_PEOPLE -> R.color.studio_sage
            ROW_ALBUMS -> R.color.studio_latte
            else -> R.color.studio_lavender
        }
    }
}
