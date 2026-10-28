package nl.giejay.android.tv.immich.home

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DividerPresenter
import androidx.leanback.widget.DividerRow
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.PageRow
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.SectionRow
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.album.AlbumFragment
import nl.giejay.android.tv.immich.assets.AllAssetFragment
import nl.giejay.android.tv.immich.assets.FolderFragment
import nl.giejay.android.tv.immich.assets.RandomAssetsFragment
import nl.giejay.android.tv.immich.assets.RecentAssetsFragment
import nl.giejay.android.tv.immich.assets.SimilarTimeAssetsFragment
import nl.giejay.android.tv.immich.people.PeopleFragment
import nl.giejay.android.tv.immich.plus.favorites.FavoritesFragment
import nl.giejay.android.tv.immich.plus.home.StudioHomeFragment
import nl.giejay.android.tv.immich.plus.places.PlacesFragment
import nl.giejay.android.tv.immich.plus.search.SmartSearchFragment
import nl.giejay.android.tv.immich.settings.SettingsFragment
import nl.giejay.android.tv.immich.shared.fragment.GridFragment
import nl.giejay.android.tv.immich.shared.prefs.HIDDEN_HOME_ITEMS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.timeline.TimelineFragment
import timber.log.Timber

class HomeFragment : BrowseSupportFragment() {
    private lateinit var mRowsAdapter: ArrayObjectAdapter
    private lateinit var rows: List<PageRow>
    val immichRowPresenter = ImmichRowPresenter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("Loaded Home")

        setupUi()
        loadData()

        mainFragmentRegistry.registerFragment(PageRow::class.java, PageRowFragmentFactory())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headersSupportFragment.setOnHeaderViewSelectedListener { _, row ->
            selectedPosition = row?.let { mRowsAdapter.indexOf(it) } ?: 0
            val name = row?.headerItem?.name ?: "-"
            // Full-bleed pages (timeline, start, search) never show the Leanback page title
            // (it flashes and slides away when entering the page, or overlaps their content).
            val header = row?.headerItem?.id?.toInt()?.let { HEADERS.getOrNull(it) }
            if (header?.showsTitle == false) {
                title = ""
                showTitle(false)
            } else {
                title = name
            }
        }

        headersSupportFragment.setOnHeaderClickedListener { _, row ->
            if (row.headerItem.name == getString(R.string.edit)) {
                immichRowPresenter.editMode = !immichRowPresenter.editMode
                if(immichRowPresenter.editMode){
                    mRowsAdapter.clear()
                    mRowsAdapter.addAll(0, rows.filter { it.headerItem.name != getString(R.string.settings) })
                } else {
                    mRowsAdapter.clear();
                    mRowsAdapter.addAll(0, rows.filter { !PreferenceManager.itemInStringSet(it.headerItem.name, HIDDEN_HOME_ITEMS) })
                }
                adapter.notifyItemRangeChanged(0, mRowsAdapter.size());
            } else if(immichRowPresenter.editMode){
                PreferenceManager.toggleStringSetItem(row.headerItem.name, HIDDEN_HOME_ITEMS)
                adapter.notifyItemRangeChanged(0, mRowsAdapter.size())
            } else{
                if (!this.isInHeadersTransition) {
                    this.startHeadersTransition(false)
//                    this.mainFragment.requireView().requestFocus()
                }
            }
        }
    }

    private fun setupUi() {
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.studio_menu_bg)
        // First page is Timeline, which never shows a Browse title.
        title = ""
        showTitle(false)
//        setOnSearchClickedListener {
//            Toast.makeText(
//                activity, "Search!", Toast.LENGTH_SHORT
//            ).show()
//        }

        // Lines of code to be added
        val sHeaderPresenter: PresenterSelector = ClassPresenterSelector()
            .addClassPresenter(DividerRow::class.java, DividerPresenter())
            .addClassPresenter(
                SectionRow::class.java,
                RowHeaderPresenter()
            )
            .addClassPresenter(Row::class.java, immichRowPresenter)

        setHeaderPresenterSelector(sHeaderPresenter)
    }

    private fun loadData() {
        mRowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = mRowsAdapter
        rows = createRows()
        mRowsAdapter.addAll(0, rows.filter { !PreferenceManager.itemInStringSet(it.headerItem.name, HIDDEN_HOME_ITEMS) })
    }

    private fun createRows(): List<PageRow> {
        return HEADERS.mapIndexed { index, header -> PageRow(IconHeaderItem(index.toLong(), header.name, header.icon)) }
    }

    private class PageRowFragmentFactory : FragmentFactory<Fragment>() {
        override fun createFragment(rowObj: Any): Fragment {
            val row = rowObj as Row
            Timber.i("Going to show page: ${row.headerItem.name}")
            return HEADERS[row.headerItem.id.toInt()].fragment()
        }
    }

    companion object {
        private fun text(id: Int) = ImmichApplication.appContext!!.getString(id)

        private val HEADERS: List<Header> = listOf(
            Header(text(R.string.studio_start), R.drawable.studio_ic_home, showsTitle = false) { StudioHomeFragment() },
            Header(text(R.string.plus_search), R.drawable.studio_ic_search, showsTitle = false) { SmartSearchFragment() },
            Header(text(R.string.timeline), R.drawable.studio_ic_timeline, showsTitle = false) { TimelineFragment() },
            Header(text(R.string.plus_favorites), R.drawable.studio_ic_favorite) { FavoritesFragment() },
            Header(text(R.string.plus_places), R.drawable.studio_ic_place) { PlacesFragment() },
            Header(text(R.string.people), R.drawable.studio_ic_person) { PeopleFragment() },
            Header(text(R.string.albums), R.drawable.studio_ic_album) {
                AlbumFragment().apply {
                    arguments = bundleOf("selectionMode" to false)
                }
            },
            Header(text(R.string.photos), R.drawable.studio_ic_photo) { AllAssetFragment() },
            Header(text(R.string.random), R.drawable.studio_ic_shuffle) { RandomAssetsFragment() },
            Header(text(R.string.recent), R.drawable.studio_ic_recent) { RecentAssetsFragment() },
            Header(text(R.string.seasonal), R.drawable.studio_ic_event) { SimilarTimeAssetsFragment() },
            Header(text(R.string.folders), R.drawable.studio_ic_folder) { FolderFragment() },
            Header(text(R.string.edit), R.drawable.studio_ic_edit) { GridFragment(hideProgressBar = true) },
            Header(text(R.string.settings), R.drawable.studio_ic_settings) { SettingsFragment() },
        )
    }
}

class Header(
    val name: String,
    @androidx.annotation.DrawableRes val icon: Int,
    val showsTitle: Boolean = true,
    val fragment: () -> Fragment
)

/** Menu entry with an icon, shown by [ImmichRowPresenter]. */
class IconHeaderItem(id: Long, name: String, @androidx.annotation.DrawableRes val icon: Int) : HeaderItem(id, name)
