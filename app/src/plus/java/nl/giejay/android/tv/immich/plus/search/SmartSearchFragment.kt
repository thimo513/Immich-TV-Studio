package nl.giejay.android.tv.immich.plus.search

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import arrow.core.Either
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.assets.GenericAssetFragment
import nl.giejay.android.tv.immich.plus.api.PlusApi
import nl.giejay.android.tv.immich.plus.toDateCard
import nl.giejay.android.tv.immich.shared.viewmodel.KeyEventsViewModel

/**
 * Smart search (CLIP) over the whole library, e.g. "mountains" or "sailboat".
 *
 * The query is entered in a dialog: OK while the page is empty, UP in the top row of the results,
 * or right at the grid's right edge (the spot where other pages open their settings).
 */
class SmartSearchFragment : GenericAssetFragment() {
    private var dialogOpen = false

    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { search(it) }
    }

    private var emptyHint: View? = null
    private var focusResults = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDialogOnUnhandledKeys()
    }

    /**
     * Key presses only reach MainActivity when no view handled them: OK on an empty page, or UP
     * in the top row of the results. Both open the search dialog, prefilled with the last query so
     * it can be extended. The key state is replayed to new collectors, hence the age check: only a
     * fresh press counts.
     */
    private fun openDialogOnUnhandledKeys() {
        val keyEvents = ViewModelProvider(requireActivity())[KeyEventsViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                keyEvents.state.collect { event ->
                    if (event == null || event.action != KeyEvent.ACTION_DOWN ||
                        SystemClock.uptimeMillis() - event.eventTime >= FRESH_KEY_MS
                    ) return@collect
                    val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    val isUp = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
                    // UP only counts above the results; on the start panel it is plain navigation
                    if ((isOk && assets.isEmpty() && lastQuery != null) || (isUp && assets.isNotEmpty())) {
                        showSearchDialog()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // start panel instead of a page title (a title overlaps the menu entry name "Suche"):
        // own search, recent searches and ideas as buttons, all reachable with the D-pad
        val panel = buildStartPanel()
        emptyHint = panel
        (view as? FrameLayout)?.addView(
            panel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        showStartPanel(lastQuery == null)
    }

    /**
     * The (empty) grid frame is focusable and would take the focus before the panel buttons, so
     * only one of the two is ever visible.
     */
    private fun showStartPanel(show: Boolean) {
        val gridFrame = view?.findViewById<View>(R.id.grid_frame)
        if (show) {
            emptyHint?.visibility = View.VISIBLE
            gridFrame?.visibility = View.GONE
        } else {
            // hand the focus to the grid frame before the focused chip disappears, otherwise it
            // falls back to the menu
            gridFrame?.visibility = View.VISIBLE
            gridFrame?.requestFocus()
            emptyHint?.visibility = View.GONE
        }
    }

    private fun buildStartPanel(): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        fun label(text: String, sizeSp: Float, topDp: Int) = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(ContextCompat.getColor(context, R.color.studio_text))
            setPadding(0, dp(topDp), 0, dp(10))
            gravity = Gravity.CENTER
        }

        fun chip(text: String, onClick: () -> Unit) = Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ContextCompat.getColorStateList(context, R.color.studio_chip_text))
            background = ContextCompat.getDrawable(context, R.drawable.studio_chip_bg)
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setOnClickListener { onClick() }
        }

        fun row(items: List<View>) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            items.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }) }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(getString(R.string.plus_search_title_empty), 30f, 0))
            addView(row(listOf(chip(getString(R.string.studio_search_custom)) { showSearchDialog() })))
            val recent = recentSearches(context)
            if (recent.isNotEmpty()) {
                addView(label(getString(R.string.studio_search_recent), 16f, 18))
                addView(row(recent.map { term -> chip(term) { search(term) } }))
            }
            addView(label(getString(R.string.studio_search_ideas), 16f, 18))
            resources.getStringArray(R.array.studio_search_suggestions).toList().chunked(6).forEach { terms ->
                addView(row(terms.map { term -> chip(term) { search(term) } }))
            }
        }
    }

    private fun recentSearches(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, "")
            .orEmpty().split(SEPARATOR).filter { it.isNotBlank() }

    private fun rememberSearch(context: Context, term: String) {
        val updated = (listOf(term) + recentSearches(context).filterNot { it.equals(term, ignoreCase = true) }).take(MAX_RECENT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RECENT, updated.joinToString(SEPARATOR)).apply()
    }

    override fun onDestroyView() {
        emptyHint = null
        super.onDestroyView()
    }

    override fun createCard(a: Asset): Card = a.toDateCard()

    override fun setData(assets: List<Asset>) {
        super.setData(assets)
        if (focusResults && adapter.size() > 0) {
            focusResults = false
            val grid = view?.findViewById<View>(androidx.leanback.R.id.browse_grid)
            grid?.post { grid.requestFocus() }
        }
    }

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Asset>> {
        val query = lastQuery ?: return Either.Right(emptyList())
        return PlusApi.smartSearch(query, page, pageCount, currentFilter)
    }

    // results are ordered by relevance, the user's photo sorting would scramble them
    override fun sortItems(items: List<Asset>): List<Asset> = items

    override fun openPopUpMenu() {
        // right edge of the results; on the start panel RIGHT is plain navigation between chips
        if (lastQuery != null) showSearchDialog()
    }

    private fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !isAdded) return
        lastQuery = trimmed
        rememberSearch(requireContext(), trimmed)
        ioScope.coroutineContext[Job]?.cancelChildren()
        clearState()
        allPagesLoaded = false
        progressBar?.visibility = View.VISIBLE
        showStartPanel(false)
        // the pressed chip disappears with the panel: move the focus to the first result instead
        // of letting it fall back to the menu
        focusResults = true
        Toast.makeText(requireContext(), getString(R.string.plus_search_title_with_query, trimmed), Toast.LENGTH_LONG).show()
        fetchInitialItems()
    }

    private fun showSearchDialog() {
        if (dialogOpen || !isAdded) return
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val input = EditText(context).apply {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            hint = getString(R.string.plus_search_hint)
            setText(lastQuery.orEmpty())
            setSelection(text.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
        }
        fun submit() {
            dialog.dismiss()
            search(input.text.toString())
        }
        fun button(label: String, onClick: () -> Unit) = Button(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { onClick() }
        }

        val voiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.plus_search_voice_prompt))
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (voiceIntent.resolveActivity(context.packageManager) != null) {
                addView(button(getString(R.string.plus_search_voice)) {
                    dialog.dismiss()
                    voiceInput.launch(voiceIntent)
                })
            }
            addView(button(getString(android.R.string.cancel)) { dialog.dismiss() })
            addView(button(getString(R.string.plus_search_action)) { submit() })
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = GradientDrawable().apply {
                setColor(0xF2202124.toInt())
                cornerRadius = dp(12).toFloat()
            }
            addView(TextView(context).apply {
                text = getString(R.string.plus_search_dialog_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
            })
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            })
            addView(buttons)
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        dialog.setContentView(content)
        dialog.setOnDismissListener { dialogOpen = false }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // compact box in the upper half, the on-screen keyboard needs the lower part
            setLayout(minOf(dp(560), (resources.displayMetrics.widthPixels * 0.7f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(48) }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        dialogOpen = true
        dialog.show()
        input.requestFocus()
    }

    companion object {
        // survives switching pages and returning from the photo slider
        private var lastQuery: String? = null
        private const val FRESH_KEY_MS = 500L
        private const val PREFS = "studio_search"
        private const val KEY_RECENT = "recent"
        private const val MAX_RECENT = 6
        private const val SEPARATOR = "|"
    }
}
