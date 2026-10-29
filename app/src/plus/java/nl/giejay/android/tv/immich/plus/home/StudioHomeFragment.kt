package nl.giejay.android.tv.immich.plus.home

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseOnItemViewSelectedListener
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
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
import nl.giejay.android.tv.immich.plus.ui.StudioAmbient
import nl.giejay.android.tv.immich.plus.ui.StudioCardPresenter
import nl.giejay.android.tv.immich.plus.ui.StudioGroupCard
import nl.giejay.android.tv.immich.plus.ui.StudioGroupCardPresenter
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.Year
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Start page of Immich TV Studio: horizontal rows like a streaming app. Memories first, then the
 * newest photos, favorites, places, people, albums and a random selection. Each row loads on its
 * own and appears as soon as it has content, always in the same order.
 *
 * Every row has its own pastel colour (title and "see all" tile). The tile in front of a row
 * opens the whole group as its menu page; the cards behind it open the photos.
 *
 * Above the rows sits the hero: a large photo (today's memory, otherwise the newest photo)
 * drifting slowly, with a greeting for the time of day. The page background takes on the colours
 * of the focused photo, and on opening the rows glide in one after the other.
 */
class StudioHomeFragment : RowsSupportFragment() {
    private val rowsAdapter = ArrayObjectAdapter(
        EntranceRowPresenter().apply {
            shadowEnabled = false
            selectEffectEnabled = false
            headerPresenter = ColoredRowHeaderPresenter()
        }
    )
    private val actions = IdentityHashMap<Any, () -> Unit>()
    private val rowKeys = mutableListOf<Int>()

    private var hero: View? = null
    private var heroAnimator: Animator? = null
    private var heroUrl: String? = null
    private var heroPriority = 0
    private var heroSubline: String? = null

    /** When each row was added: a row glides in when it is shown right after arriving. */
    private val rowAddedAt = mutableMapOf<Long, Long>()
    private val enteredRows = mutableSetOf<Long>()
    private var lastEntranceAt = 0L
    private var entranceSlot = 0

    private var ambientUrl: String? = null
    private val ambientUpdate = Runnable { loadAmbient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> actions[item]?.invoke() }
        setOnItemViewSelectedListener(null)
        if (PreferenceManager.isLoggedId()) load()
    }

    /**
     * The Browse fragment around this page installs its own selection listener here; ours always
     * runs first, then the one passed in.
     */
    override fun setOnItemViewSelectedListener(listener: BaseOnItemViewSelectedListener<*>?) {
        @Suppress("UNCHECKED_CAST")
        val next = listener as BaseOnItemViewSelectedListener<Any?>?
        super.setOnItemViewSelectedListener(BaseOnItemViewSelectedListener<Any?> { itemViewHolder, item, rowViewHolder, row ->
            onSelected(item, row)
            next?.onItemSelected(itemViewHolder, item, rowViewHolder, row)
        })
    }

    private fun onSelected(item: Any?, row: Any?) {
        // the hero belongs to the top of the page: it gives way as soon as the rows scroll up
        hero?.animate()?.alpha(if ((row?.let { rowsAdapter.indexOf(it) } ?: 0) <= 0) 1f else 0f)?.setDuration(350L)?.start()
        val card = item as? Card ?: return
        ambientUrl = (card.backgroundUrl ?: card.thumbnailUrl)?.takeIf { it.startsWith("http") } ?: return
        view?.removeCallbacks(ambientUpdate)
        view?.postDelayed(ambientUpdate, AMBIENT_DELAY_MS)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val rows = super.onCreateView(inflater, container, savedInstanceState)!!
        // hero behind the (transparent) rows
        return FrameLayout(inflater.context).apply {
            addView(inflater.inflate(R.layout.studio_home_hero, this, false))
            addView(rows)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hero = view.findViewById<View>(R.id.studio_hero).apply {
            // the mask view cuts the photo out softly (DST_IN); that needs the hero in its own layer
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            findViewById<View>(R.id.studio_hero_mask).setLayerType(
                View.LAYER_TYPE_HARDWARE, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            )
        }
        // a new view (e.g. back from another page) shows its rows again: let them glide in once more
        val now = SystemClock.uptimeMillis()
        rowAddedAt.keys.forEach { rowAddedAt[it] = now }
        enteredRows.clear()
        showGreeting(view)
        heroUrl?.let { loadHeroImage(it) }
    }

    override fun onResume() {
        super.onResume()
        heroAnimator?.resume()
        // other pages leave their photo backdrop behind; right after the app starts no page has
        // attached the background layer yet
        backgroundManager().drawable = ContextCompat.getDrawable(requireContext(), R.drawable.studio_page_bg)
    }

    override fun onPause() {
        heroAnimator?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        hero?.findViewById<View>(R.id.studio_hero_image)?.animate()?.cancel()
        heroAnimator?.cancel()
        heroAnimator = null
        view?.removeCallbacks(ambientUpdate)
        hero = null
        super.onDestroyView()
    }

    private fun load() {
        val api = ApiClient.getClient(ApiClientConfig.fromPrefs())
        lifecycleScope.launch {
            launch {
                api.getMemories().getOrNull()?.filter { it.assets.isNotEmpty() }?.let { memories ->
                    memories.firstOrNull()?.let { showMemoryInHero(it) }
                    addRow(ROW_MEMORIES, getString(R.string.studio_row_memories), CardShape.HERO, null,
                        memories.map { memory -> memoryCard(memory) to { SliderLauncher.open(this@StudioHomeFragment, memory.assets, null, story = true) } })
                }
            }
            launch {
                api.listAssets(1, ROW_SIZE, contentType = ContentType.ALL).getOrNull()?.let {
                    setHero(it.assets.firstOrNull { asset -> asset.type == "IMAGE" }?.let { asset -> ApiUtil.getThumbnailUrl(asset.id, "preview") }, HERO_RECENT)
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

    // ---------------------------------------------------------------------------------------------
    // Hero: greeting and slowly drifting photo
    // ---------------------------------------------------------------------------------------------

    private fun showGreeting(root: View) {
        val (text, icon) = when (LocalTime.now().hour) {
            in 5..10 -> R.string.studio_greeting_morning to R.drawable.studio_ic_sun
            in 11..13 -> R.string.studio_greeting_noon to R.drawable.studio_ic_sun
            in 14..17 -> R.string.studio_greeting_afternoon to R.drawable.studio_ic_sun
            in 18..21 -> R.string.studio_greeting_evening to R.drawable.studio_ic_moon
            else -> R.string.studio_greeting_night to R.drawable.studio_ic_moon
        }
        val greeting = root.findViewById<TextView>(R.id.studio_hero_greeting)
        greeting.text = getString(text, getString(R.string.studio_greeting_name))
        root.findViewById<ImageView>(R.id.studio_hero_icon).setImageResource(icon)
        updateSubline()
        // the greeting glides in from below, the line under it shortly after
        val lift = 24 * resources.displayMetrics.density
        listOf(greeting.parent as View to 150L, root.findViewById<View>(R.id.studio_hero_subline) to 450L).forEach { (line, delay) ->
            line.alpha = 0f
            line.translationY = lift
            line.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(750L)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    /** "Heute vor 3 Jahren: 12 Fotos" under the greeting, the memory's photo as hero. */
    private fun showMemoryInHero(memory: Memory) {
        val years = (Year.now().value - memory.data.year).coerceAtLeast(1)
        heroSubline = getString(
            R.string.studio_hero_memory,
            resources.getQuantityString(R.plurals.years_ago, years, years),
            resources.getQuantityString(R.plurals.studio_photo_count, memory.assets.size, memory.assets.size)
        )
        updateSubline()
        setHero(ApiUtil.getThumbnailUrl(memory.assets.first().id, "preview"), HERO_MEMORY)
    }

    /** Today's date ("Mittwoch, 29. Oktober") until a memory has something better to say. */
    private fun updateSubline() {
        val subline = view?.findViewById<TextView>(R.id.studio_hero_subline) ?: return
        subline.text = heroSubline ?: run {
            val locale = Locale.getDefault()
            SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date())
        }
    }

    /** A memory beats the newest photo, whichever loads first. */
    private fun setHero(url: String?, priority: Int) {
        if (url == null || priority <= heroPriority) return
        heroPriority = priority
        heroUrl = url
        if (view != null) loadHeroImage(url)
    }

    private fun loadHeroImage(url: String) {
        val image = hero?.findViewById<ImageView>(R.id.studio_hero_image) ?: return
        Glide.with(this).load(url).centerCrop().into(object : CustomTarget<Drawable>(HERO_WIDTH_PX, HERO_HEIGHT_PX) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (url != heroUrl) return
                heroAnimator?.cancel()
                image.alpha = 0f
                image.scaleX = HERO_REVEAL_SCALE
                image.scaleY = HERO_REVEAL_SCALE
                image.translationX = 0f
                image.setImageDrawable(resource)
                // it comes in from close up, then the camera starts gliding over it
                image.animate().alpha(HERO_ALPHA).scaleX(KEN_BURNS_FROM).scaleY(KEN_BURNS_FROM)
                    .setDuration(HERO_REVEAL_MS).setInterpolator(DecelerateInterpolator(2f))
                    .withEndAction { if (hero != null) startKenBurns(image) }.start()
                if (ambientUrl == null) {
                    ambientUrl = url
                    loadAmbient()
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                image.setImageDrawable(null)
            }
        })
    }

    /** Slow zoom and pan back and forth, like a camera gliding over the photo. */
    private fun startKenBurns(image: ImageView) {
        heroAnimator?.cancel()
        val pan = -48 * resources.displayMetrics.density
        heroAnimator = ObjectAnimator.ofPropertyValuesHolder(
            image,
            PropertyValuesHolder.ofFloat(View.SCALE_X, KEN_BURNS_FROM, KEN_BURNS_TO),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, KEN_BURNS_FROM, KEN_BURNS_TO),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, pan)
        ).apply {
            duration = KEN_BURNS_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
            if (!isResumed) pause()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ambient light: the page takes on the colours of the focused photo (see StudioAmbient)
    // ---------------------------------------------------------------------------------------------

    private fun backgroundManager(): BackgroundManager =
        BackgroundManager.getInstance(requireActivity()).apply { if (!isAttached) attach(requireActivity().window) }

    private fun loadAmbient() {
        val url = ambientUrl ?: return
        if (!isResumed) return
        // the BackgroundManager crossfades to it
        Glide.with(requireActivity()).load(url).centerCrop().into(object : CustomTarget<Drawable>(
            StudioAmbient.WIDTH_PX, StudioAmbient.HEIGHT_PX
        ) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (!isResumed || url != ambientUrl) return
                (resource as? BitmapDrawable)?.isFilterBitmap = true
                backgroundManager().drawable = LayerDrawable(arrayOf(resource, ColorDrawable(StudioAmbient.TINT)))
            }

            override fun onLoadCleared(placeholder: Drawable?) {}
        })
    }

    // ---------------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------------

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
        rowAddedAt[key.toLong()] = SystemClock.uptimeMillis()
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

    /**
     * Rows glide in from below as they arrive, one after the other. Only right after arriving:
     * rows scrolled into view later are simply there.
     */
    private inner class EntranceRowPresenter : ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL, false) {
        override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
            super.onBindRowViewHolder(holder, item)
            val id = (item as? ListRow)?.id ?: return
            val now = SystemClock.uptimeMillis()
            val addedAt = rowAddedAt[id] ?: return
            if (now - addedAt > ENTRANCE_AFTER_ADD_MS || !enteredRows.add(id)) return
            // rows arriving close together line up one after the other
            entranceSlot = if (now - lastEntranceAt < ENTRANCE_STAGGER_MS * 3) entranceSlot + 1 else 0
            lastEntranceAt = now
            val row = holder.view
            row.alpha = 0f
            row.translationY = ENTRANCE_LIFT_DP * row.resources.displayMetrics.density
            row.scaleX = ENTRANCE_SCALE
            row.scaleY = ENTRANCE_SCALE
            row.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay((entranceSlot * ENTRANCE_STAGGER_MS).coerceAtMost(ENTRANCE_MAX_DELAY_MS))
                .setDuration(800L).setInterpolator(DecelerateInterpolator(2.5f)).start()
        }
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

        private const val HERO_RECENT = 1
        private const val HERO_MEMORY = 2
        private const val HERO_WIDTH_PX = 1280
        private const val HERO_HEIGHT_PX = 720
        private const val HERO_ALPHA = 1f
        private const val HERO_REVEAL_SCALE = 1.35f
        private const val HERO_REVEAL_MS = 1800L
        private const val KEN_BURNS_FROM = 1.06f
        private const val KEN_BURNS_TO = 1.22f
        private const val KEN_BURNS_MS = 22_000L

        private const val AMBIENT_DELAY_MS = 450L

        private const val ENTRANCE_AFTER_ADD_MS = 1500L
        private const val ENTRANCE_STAGGER_MS = 130L
        private const val ENTRANCE_MAX_DELAY_MS = 780L
        private const val ENTRANCE_LIFT_DP = 110
        private const val ENTRANCE_SCALE = 0.94f

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
