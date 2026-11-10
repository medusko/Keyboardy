package com.medusko.keyboardy.ime

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medusko.keyboardy.MainActivity
import com.medusko.keyboardy.R
import com.medusko.keyboardy.prefs.KeyboardScale
import com.medusko.keyboardy.prefs.KeyboardyPrefs
import com.medusko.keyboardy.prefs.SearchEngineMode
import com.medusko.keyboardy.search.HeadlessGoogleWebViewSearcher
import com.medusko.keyboardy.search.ImageResult
import com.medusko.keyboardy.search.ImageSearchRepository
import com.medusko.keyboardy.search.SearchFilter
import com.medusko.keyboardy.suggest.SpellAndSuggestionEngine
import com.medusko.keyboardy.suggest.SuggestionItem
import com.medusko.keyboardy.theme.ThemePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class KeyboardyImeService : InputMethodService() {

    private enum class KeyboardLayer {
        LETTERS,
        NUMBERS_PUNCTUATION,
        SYMBOLS
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchJob: Job? = null
    private var commitJob: Job? = null

    private lateinit var prefs: KeyboardyPrefs
    private lateinit var commitHelper: ImageCommitHelper
    private var headlessGoogleSearcher: HeadlessGoogleWebViewSearcher? = null
    private var searchRepository: ImageSearchRepository? = null
    private var gridAdapter: ImageGridAdapter? = null

    // UI References
    private var rootImeContainer: LinearLayout? = null
    private var topBarContainer: LinearLayout? = null
    private var btnBackKeyboard: LinearLayout? = null
    private var ivBackIcon: ImageView? = null
    private var tvBackLabel: TextView? = null
    private var searchBarContainer: LinearLayout? = null
    private var ivSearchIcon: ImageView? = null
    private var etSearchQuery: ImeSearchEditText? = null
    private var btnClearQuery: ImageView? = null
    private var btnHistory: LinearLayout? = null
    private var ivHistoryIcon: ImageView? = null
    private var btnSettings: LinearLayout? = null
    private var ivSettingsIcon: ImageView? = null
    private var imeTopLine: View? = null

    private var selectionActionBar: LinearLayout? = null
    private var btnCtxPaste: TextView? = null
    private var btnCtxCopy: TextView? = null
    private var btnCtxCut: TextView? = null
    private var btnCtxSelectAll: TextView? = null

    private var progressSearching: ProgressBar? = null
    private var rvImageResults: RecyclerView? = null
    private var emptyStateContainer: LinearLayout? = null
    private var tvStatusMessage: TextView? = null

    private var historyPanelContainer: LinearLayout? = null
    private var tvHistoryTitle: TextView? = null
    private var btnClearHistory: TextView? = null
    private var historyItemsList: LinearLayout? = null

    private var suggestionBarScroll: HorizontalScrollView? = null
    private var suggestionBarContainer: LinearLayout? = null
    private var containerMiniQwerty: LinearLayout? = null
    private var bottomNavSpacer: View? = null
    private var chipEngineMode: TextView? = null
    private var chipGridScale: TextView? = null
    private var chipFullScreen: TextView? = null
    private val filterChipViews = mutableMapOf<SearchFilter, TextView>()
    private var gridLayoutManager: GridLayoutManager? = null
    private var vibrator: Vibrator? = null

    private data class AppliedImeStyle(
        val isDark: Boolean,
        val scale: KeyboardScale,
        val gridColumns: Int,
        val isFullScreen: Boolean,
        val bottomClearanceDp: Int,
        val isQwertyVisible: Boolean,
        val screenHeightPx: Int,
        val currentLayer: KeyboardLayer,
        val currentFilter: SearchFilter,
        val isHistoryVisible: Boolean,
        val engineMode: SearchEngineMode
    )

    // State
    private var currentFilter: SearchFilter = SearchFilter.ALL
    private var currentLayer: KeyboardLayer = KeyboardLayer.LETTERS
    private var isShiftActive: Boolean = false
    private var isHistoryVisible: Boolean = false
    private var queryOriginatedFromHostEditor: Boolean = false
    private var lastCountedSearchQuery: String = ""
    private var suggestJob: Job? = null
    private val letterKeyViews = mutableListOf<Pair<TextView, String>>()
    private var shiftKeyView: TextView? = null
    private var lastAppliedStyle: AppliedImeStyle? = null
    private var lastRenderedSuggestions: List<SuggestionItem> = SpellAndSuggestionEngine.DEFAULT_SUGGESTIONS

    // Real-time preference listener filtered by key so counter/history/unrelated writes never rebuild the IME.
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        mainHandler.post {
            when (classifyPrefChange(key)) {
                ImePrefReaction.STYLE_UPDATE -> applyStyleIfChanged()
                ImePrefReaction.ENGINE_CHIP_UPDATE -> updateEngineChipUi()
                ImePrefReaction.RE_RUN_SEARCH -> {
                    if (getCurrentQuery().isNotBlank()) {
                        triggerSearch(immediate = true, hideKeyboardOnStart = false)
                    }
                }
                ImePrefReaction.NONE -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardyPrefs(this)
        prefs.registerListener(prefChangeListener)
        commitHelper = ImageCommitHelper(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibrator = vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.view_keyboardy_ime, null)

        rootImeContainer = root.findViewById(R.id.root_ime_container)
        topBarContainer = root.findViewById(R.id.top_bar_container)
        btnBackKeyboard = root.findViewById(R.id.btn_back_keyboard)
        ivBackIcon = root.findViewById(R.id.iv_back_icon)
        tvBackLabel = root.findViewById(R.id.tv_back_label)
        searchBarContainer = root.findViewById(R.id.search_bar_container)
        ivSearchIcon = root.findViewById(R.id.iv_search_icon)
        etSearchQuery = root.findViewById(R.id.et_search_query)
        btnClearQuery = root.findViewById(R.id.btn_clear_query)
        btnHistory = root.findViewById(R.id.btn_history)
        ivHistoryIcon = root.findViewById(R.id.iv_history_icon)
        btnSettings = root.findViewById(R.id.btn_settings)
        ivSettingsIcon = root.findViewById(R.id.iv_settings_icon)
        imeTopLine = root.findViewById(R.id.ime_top_line)

        selectionActionBar = root.findViewById(R.id.selection_action_bar)
        btnCtxPaste = root.findViewById(R.id.btn_ctx_paste)
        btnCtxCopy = root.findViewById(R.id.btn_ctx_copy)
        btnCtxCut = root.findViewById(R.id.btn_ctx_cut)
        btnCtxSelectAll = root.findViewById(R.id.btn_ctx_select_all)

        progressSearching = root.findViewById(R.id.progress_searching)
        rvImageResults = root.findViewById(R.id.rv_image_results)
        emptyStateContainer = root.findViewById(R.id.empty_state_container)
        tvStatusMessage = root.findViewById(R.id.tv_status_message)

        historyPanelContainer = root.findViewById(R.id.history_panel_container)
        tvHistoryTitle = root.findViewById(R.id.tv_history_title)
        btnClearHistory = root.findViewById(R.id.btn_clear_history)
        historyItemsList = root.findViewById(R.id.history_items_list)

        suggestionBarScroll = root.findViewById(R.id.suggestion_bar_scroll)
        suggestionBarContainer = root.findViewById(R.id.suggestion_bar_container)
        containerMiniQwerty = root.findViewById(R.id.container_mini_qwerty)
        bottomNavSpacer = root.findViewById(R.id.bottom_nav_spacer)
        chipEngineMode = root.findViewById(R.id.chip_engine_mode)
        chipGridScale = root.findViewById(R.id.chip_grid_scale)
        chipFullScreen = root.findViewById(R.id.chip_full_screen)

        val webViewHolder: FrameLayout = root.findViewById(R.id.headless_webview_holder)

        filterChipViews[SearchFilter.ALL] = root.findViewById(R.id.chip_filter_all)
        filterChipViews[SearchFilter.GIF] = root.findViewById(R.id.chip_filter_gif)
        filterChipViews[SearchFilter.MEME] = root.findViewById(R.id.chip_filter_meme)
        filterChipViews[SearchFilter.REACTION] = root.findViewById(R.id.chip_filter_reaction)
        filterChipViews[SearchFilter.TRANSPARENT] = root.findViewById(R.id.chip_filter_sticker)

        // Initialize Headless Google Images WebView searcher & repository
        headlessGoogleSearcher?.destroy()
        val googleSearcher = HeadlessGoogleWebViewSearcher(this, webViewHolder)
        headlessGoogleSearcher = googleSearcher
        searchRepository = ImageSearchRepository(googleSearcher)

        // Setup Scalable Image & GIF RecyclerView (1x, 2x, 3x, or 4x columns)
        val adapter = ImageGridAdapter(this) { selectedItem ->
            onImageResultSelected(selectedItem)
        }
        gridAdapter = adapter
        val glm = GridLayoutManager(this@KeyboardyImeService, prefs.gridColumns)
        gridLayoutManager = glm
        rvImageResults?.apply {
            layoutManager = glm
            this.adapter = adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        setMiniQwertyVisible(false)
                        hideSelectionActionBar()
                    }
                }
            })
        }

        // Grid Scale Switcher Chip (cycles 3x -> 4x -> 1x -> 2x -> 3x)
        chipGridScale?.setOnClickListener { view ->
            performTapHaptic(view)
            prefs.cycleGridColumns()
            applyStyleIfChanged()
        }

        // Fullscreen Toggle Chip (toggles between standard ~74% height and ~96% fullscreen)
        chipFullScreen?.setOnClickListener { view ->
            performTapHaptic(view)
            prefs.isFullScreenMode = !prefs.isFullScreenMode
            applyStyleIfChanged()
        }

        // Settings Button (⚙️) opens Keyboardy Settings and lowers the keyboard
        btnSettings?.setOnClickListener { view ->
            performTapHaptic(view)
            requestHideSelf(0)
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Opening Keyboardy Settings...", Toast.LENGTH_SHORT).show()
            }
        }

        // Back to Gboard / Previous Keyboard button
        btnBackKeyboard?.setOnClickListener { view ->
            performTapHaptic(view)
            switchToPreviousKeyboard()
        }

        // Interactive Search EditText callbacks (tap to position cursor, long-press/select for Copy/Cut/Paste)
        etSearchQuery?.apply {
            onSearchBarTapped = {
                setHistoryPanelVisible(false)
                setMiniQwertyVisible(true)
            }
            onQueryTextChanged = { updatedText ->
                btnClearQuery?.visibility = if (updatedText.isNotEmpty()) View.VISIBLE else View.GONE
                updateWordSuggestions(updatedText)
            }
            onSelectionOrLongPressAction = { _, _ ->
                showSelectionActionBar()
            }
            onDismissSelectionMenu = {
                hideSelectionActionBar()
            }
        }

        searchBarContainer?.setOnClickListener { view ->
            performTapHaptic(view)
            setHistoryPanelVisible(false)
            setMiniQwertyVisible(true)
            val len = etSearchQuery?.getQueryText()?.length ?: 0
            etSearchQuery?.setCursorSelection(len, len)
        }

        // Clear search button
        btnClearQuery?.setOnClickListener { view ->
            performTapHaptic(view)
            resetToBlankSearch(showQwerty = true)
        }

        // History button (🕒) toggles the Search History Panel
        btnHistory?.setOnClickListener { view ->
            performTapHaptic(view)
            setHistoryPanelVisible(!isHistoryVisible)
        }

        btnClearHistory?.setOnClickListener { view ->
            performTapHaptic(view)
            prefs.clearSearchHistory()
            populateHistoryPanel(ThemePalette.forMode(prefs.isDarkMode))
        }

        // Copy / Cut / Paste / Select All Context Action Bar Buttons
        btnCtxPaste?.setOnClickListener { view ->
            performTapHaptic(view)
            val clipText = readClipboardText()
            if (!clipText.isNullOrEmpty()) {
                queryOriginatedFromHostEditor = false
                setHistoryPanelVisible(false)
                etSearchQuery?.insertTextAtCursor(clipText)
                hideSelectionActionBar()
                triggerSearch(immediate = true, hideKeyboardOnStart = false)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        btnCtxCopy?.setOnClickListener { view ->
            performTapHaptic(view)
            val toCopy = etSearchQuery?.getSelectedOrAllText().orEmpty()
            if (toCopy.isNotEmpty()) {
                copyTextToClipboard(toCopy)
                hideSelectionActionBar()
                Toast.makeText(this, "Copied \"$toCopy\"", Toast.LENGTH_SHORT).show()
            }
        }

        btnCtxCut?.setOnClickListener { view ->
            performTapHaptic(view)
            val cut = etSearchQuery?.cutSelectedOrAllText().orEmpty()
            if (cut.isNotEmpty()) {
                copyTextToClipboard(cut)
                hideSelectionActionBar()
                triggerSearch(immediate = false, hideKeyboardOnStart = false)
            }
        }

        btnCtxSelectAll?.setOnClickListener { view ->
            performTapHaptic(view)
            etSearchQuery?.selectAllText()
        }

        // Filter Chips
        for ((filter, chipView) in filterChipViews) {
            chipView.setOnClickListener { view ->
                performTapHaptic(view)
                currentFilter = filter
                setHistoryPanelVisible(false)
                applyStyleIfChanged()
                if (getCurrentQuery().isNotBlank()) {
                    triggerSearch(immediate = true, hideKeyboardOnStart = true)
                }
            }
        }

        // Engine Switcher Chip (cycles Hybrid -> Google WebView -> Instant Web)
        chipEngineMode?.setOnClickListener { view ->
            performTapHaptic(view)
            prefs.engineMode = prefs.engineMode.next()
            updateEngineChipUi()
            if (getCurrentQuery().isNotBlank()) {
                triggerSearch(immediate = true, hideKeyboardOnStart = false)
            }
        }

        lastAppliedStyle = null
        applyStyleIfChanged()
        return root
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applyStyleIfChanged()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyStyleIfChanged()

        if (!restarting) {
            currentLayer = KeyboardLayer.LETTERS
            isShiftActive = false
            setHistoryPanelVisible(false)
            hideSelectionActionBar()

            // Optional Auto-Search from Chat Box (OFF by default; blocked on password/sensitive/incognito fields)
            if (prefs.autoReadAndReplaceChatQuery &&
                ImePrivacyPolicy.shouldAllowAutoRead(info, packageName, R.id.et_optional_api_key)
            ) {
                val hostText = readHostEditorText()?.trim().orEmpty()
                if (hostText.isNotEmpty() && hostText.length <= 60 && !hostText.contains("\n")) {
                    queryOriginatedFromHostEditor = true
                    etSearchQuery?.setQueryText(hostText, placeCursorAtEnd = true)
                    setMiniQwertyVisible(false)
                    triggerSearch(immediate = true, hideKeyboardOnStart = true)
                    return
                }
            }

            // Default behavior: Start every new search session fresh & blank!
            resetToBlankSearch(showQwerty = true)
        } else if (!ImePrivacyPolicy.shouldAllowAutoRead(info, packageName, R.id.et_optional_api_key)) {
            queryOriginatedFromHostEditor = false
        }
    }

    private fun isIncognitoSession(): Boolean =
        ImePrivacyPolicy.isNoPersonalizedLearning(currentInputEditorInfo)

    private fun getCurrentQuery(): String = etSearchQuery?.getQueryText().orEmpty()

    private fun resetToBlankSearch(showQwerty: Boolean) {
        searchJob?.cancel()
        suggestJob?.cancel()
        headlessGoogleSearcher?.stopActiveLoading()
        queryOriginatedFromHostEditor = false
        lastCountedSearchQuery = ""
        progressSearching?.visibility = View.GONE
        hideSelectionActionBar()
        setHistoryPanelVisible(false)
        etSearchQuery?.setQueryText("", placeCursorAtEnd = true)
        gridAdapter?.submitList(emptyList())
        emptyStateContainer?.visibility = View.VISIBLE
        tvStatusMessage?.text =
            "Type below to search Images & GIFs — or tap history for previous searches!"
        updateWordSuggestions("")
        if (showQwerty) {
            setMiniQwertyVisible(true)
        }
    }

    private fun showSelectionActionBar() {
        selectionActionBar?.visibility = View.VISIBLE
    }

    private fun hideSelectionActionBar() {
        selectionActionBar?.visibility = View.GONE
    }

    private fun setHistoryPanelVisible(visible: Boolean) {
        if (isHistoryVisible == visible &&
            (historyPanelContainer?.visibility == View.VISIBLE) == visible
        ) {
            return
        }
        isHistoryVisible = visible
        historyPanelContainer?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            populateHistoryPanel(ThemePalette.forMode(prefs.isDarkMode))
        }
        applyStyleIfChanged()
    }

    private fun populateHistoryPanel(palette: ThemePalette) {
        val listContainer = historyItemsList ?: return
        listContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        val history = prefs.getSearchHistory()
        btnClearHistory?.visibility = if (history.isNotEmpty()) View.VISIBLE else View.GONE

        if (history.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No recent searches yet. Your searches will appear here for 1-tap access!"
                setTextColor(palette.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(32), dpToPx(16), dpToPx(32))
            }
            listContainer.addView(emptyTv)
            return
        }

        for (queryItem in history) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(12), dpToPx(8), dpToPx(8), dpToPx(8))
                background = palette.createRoundedRect(
                    fillColor = palette.surface,
                    cornerRadiusPx = 10f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = palette.border
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(6)
                }
                setOnClickListener { view ->
                    performTapHaptic(view)
                    queryOriginatedFromHostEditor = false
                    etSearchQuery?.setQueryText(queryItem, placeCursorAtEnd = true)
                    setHistoryPanelVisible(false)
                    triggerSearch(immediate = true, hideKeyboardOnStart = true)
                }
            }

            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_history)
                setColorFilter(palette.accentBlue)
                layoutParams = LinearLayout.LayoutParams(dpToPx(16), dpToPx(16)).apply {
                    marginEnd = dpToPx(10)
                }
            }

            val textTv = TextView(this).apply {
                text = queryItem
                setTextColor(palette.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val deleteBtn = ImageView(this).apply {
                setImageResource(R.drawable.ic_clear)
                setColorFilter(palette.textSecondary)
                setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
                setOnClickListener { view ->
                    performTapHaptic(view)
                    prefs.removeSearchHistoryQuery(queryItem)
                    populateHistoryPanel(palette)
                }
            }

            row.addView(icon)
            row.addView(textTv)
            row.addView(deleteBtn)
            listContainer.addView(row)
        }
    }

    /**
     * Applies Light/Dark mode colors, full-screen height, KeyboardScale, grid columns,
     * and bottom clearance only for the properties that actually changed since the last pass.
     */
    private fun applyStyleIfChanged() {
        val isDark = prefs.isDarkMode
        val scale = prefs.keyboardScale
        val gridCols = prefs.gridColumns
        val isFull = prefs.isFullScreenMode
        val bottomClearanceDp = prefs.bottomNavClearanceDp
        val isQwertyVisible = containerMiniQwerty?.visibility != View.GONE
        val screenHeightPx = resources.displayMetrics.heightPixels
        val engineMode = prefs.engineMode

        val next = AppliedImeStyle(
            isDark = isDark,
            scale = scale,
            gridColumns = gridCols,
            isFullScreen = isFull,
            bottomClearanceDp = bottomClearanceDp,
            isQwertyVisible = isQwertyVisible,
            screenHeightPx = screenHeightPx,
            currentLayer = currentLayer,
            currentFilter = currentFilter,
            isHistoryVisible = isHistoryVisible,
            engineMode = engineMode
        )
        val prev = lastAppliedStyle
        if (prev == next) return
        lastAppliedStyle = next

        val themeChanged = prev == null || prev.isDark != next.isDark
        val scaleChanged = prev == null || prev.scale != next.scale
        val heightOrSpacerChanged = themeChanged ||
            prev.isFullScreen != next.isFullScreen ||
            prev.isQwertyVisible != next.isQwertyVisible ||
            prev.bottomClearanceDp != next.bottomClearanceDp ||
            prev.screenHeightPx != next.screenHeightPx
        val historyChromeChanged = themeChanged || prev.isHistoryVisible != next.isHistoryVisible
        val filterChipsChanged = themeChanged || prev.currentFilter != next.currentFilter
        val engineChipChanged = themeChanged || prev.engineMode != next.engineMode
        val gridChanged = themeChanged || scaleChanged || prev.gridColumns != next.gridColumns
        val fullScreenChipChanged = themeChanged || prev.isFullScreen != next.isFullScreen
        val keyboardPadChanged = themeChanged ||
            scaleChanged ||
            prev.currentLayer != next.currentLayer ||
            containerMiniQwerty?.childCount == 0

        val palette = ThemePalette.forMode(isDark)
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        if (heightOrSpacerChanged) {
            val bottomOffsetPx = if (isQwertyVisible) {
                dpToPx(bottomClearanceDp)
            } else {
                dpToPx(8)
            }
            val targetContainerHeightPx = if (isFull) {
                (screenHeightPx * 0.96f).toInt()
            } else {
                (screenHeightPx * 0.74f).toInt().coerceAtLeast(dpToPx(420))
            }

            rootImeContainer?.let { root ->
                root.setBackgroundColor(palette.background)
                val lp = root.layoutParams
                if (lp != null && lp.height != targetContainerHeightPx) {
                    lp.height = targetContainerHeightPx
                    root.layoutParams = lp
                    root.requestLayout()
                }
            }

            bottomNavSpacer?.let { spacer ->
                spacer.setBackgroundColor(palette.keyboardTrayBg)
                val lp = spacer.layoutParams
                if (lp != null && lp.height != bottomOffsetPx) {
                    lp.height = bottomOffsetPx
                    spacer.layoutParams = lp
                    spacer.requestLayout()
                }
            }
        }

        if (scaleChanged) {
            topBarContainer?.let { bar ->
                val lp = bar.layoutParams
                val targetTopHeight = dpToPx(scale.topBarHeightDp)
                if (lp != null && lp.height != targetTopHeight) {
                    lp.height = targetTopHeight
                    bar.layoutParams = lp
                }
            }
        }

        if (themeChanged) {
            imeTopLine?.let { line ->
                line.setBackgroundColor(palette.accentBlue)
                val lp = line.layoutParams
                if (lp != null) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = dpToPx(3)
                    line.layoutParams = lp
                }
            }

            btnBackKeyboard?.background = palette.createKeyDrawable(
                isAccent = false,
                cornerRadiusPx = 8f * density,
                strokeWidthPx = strokePx
            )
            ivBackIcon?.setColorFilter(palette.textPrimary)
            tvBackLabel?.setTextColor(palette.textPrimary)

            btnSettings?.background = palette.createKeyDrawable(
                isAccent = false,
                cornerRadiusPx = 8f * density,
                strokeWidthPx = strokePx
            )
            ivSettingsIcon?.setColorFilter(palette.textPrimary)

            searchBarContainer?.background = palette.createRoundedRect(
                fillColor = palette.surface,
                cornerRadiusPx = 20f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
            ivSearchIcon?.setColorFilter(palette.textSecondary)
            btnClearQuery?.setColorFilter(palette.textSecondary)
            etSearchQuery?.apply {
                setTextColor(palette.textPrimary)
                setHintTextColor(palette.textSecondary)
                setAccentColor(palette.accentBlue)
            }

            selectionActionBar?.setBackgroundColor(palette.surfaceVariant)
            for (ctxBtn in listOf(btnCtxPaste, btnCtxCopy, btnCtxCut, btnCtxSelectAll)) {
                ctxBtn?.background = palette.createRoundedRect(
                    fillColor = palette.surface,
                    cornerRadiusPx = 8f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = palette.accentBlue
                )
                ctxBtn?.setTextColor(palette.textPrimary)
                ctxBtn?.let { btn ->
                    TextViewCompat.setCompoundDrawableTintList(btn, ColorStateList.valueOf(palette.accentBlue))
                }
            }

            historyPanelContainer?.setBackgroundColor(palette.background)
            tvHistoryTitle?.setTextColor(palette.accentBlue)
            btnClearHistory?.apply {
                background = palette.createRoundedRect(
                    fillColor = palette.surface,
                    cornerRadiusPx = 8f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = palette.border
                )
                setTextColor(palette.textPrimary)
            }
            if (isHistoryVisible && prev != null) {
                populateHistoryPanel(palette)
            }

            tvStatusMessage?.setTextColor(palette.textSecondary)
            suggestionBarScroll?.setBackgroundColor(palette.keyboardTrayBg)
            renderSuggestions(lastRenderedSuggestions, palette)
        }

        if (historyChromeChanged) {
            btnHistory?.background = palette.createKeyDrawable(
                isAccent = isHistoryVisible,
                cornerRadiusPx = 8f * density,
                strokeWidthPx = strokePx
            )
            ivHistoryIcon?.setColorFilter(if (isHistoryVisible) Color.WHITE else palette.textPrimary)
        }

        if (filterChipsChanged) {
            for ((filter, view) in filterChipViews) {
                val isActive = filter == currentFilter
                view.background = palette.createRoundedRect(
                    fillColor = if (isActive) palette.chipActiveBg else palette.chipInactiveBg,
                    cornerRadiusPx = 14f * density,
                    strokeWidthPx = if (isActive) 0 else strokePx,
                    strokeColor = palette.border
                )
                view.setTextColor(if (isActive) palette.chipActiveText else palette.chipInactiveText)
            }
        }

        if (engineChipChanged) {
            chipEngineMode?.apply {
                background = palette.createRoundedRect(
                    fillColor = palette.chipInactiveBg,
                    cornerRadiusPx = 14f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = palette.accentBlue
                )
                setTextColor(palette.accentBlue)
                text = "Engine: ${engineMode.displayName}"
            }
        }

        if (gridChanged) {
            chipGridScale?.apply {
                background = palette.createRoundedRect(
                    fillColor = palette.chipInactiveBg,
                    cornerRadiusPx = 14f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = palette.accentBlue
                )
                setTextColor(palette.accentBlue)
                text = "${gridCols}x Grid"
                val d = ContextCompat.getDrawable(this@KeyboardyImeService, R.drawable.ic_grid)?.mutate()
                d?.setBounds(0, 0, dpToPx(13), dpToPx(13))
                d?.setTint(palette.accentBlue)
                setCompoundDrawables(d, null, null, null)
                compoundDrawablePadding = dpToPx(4)
            }

            gridLayoutManager?.spanCount = gridCols
            val dynamicHeightDp = prefs.getGridItemHeightDp(gridCols)
            gridAdapter?.updateStyle(dpToPx(dynamicHeightDp), palette)
        }

        if (fullScreenChipChanged) {
            chipFullScreen?.apply {
                background = palette.createRoundedRect(
                    fillColor = if (isFull) palette.chipActiveBg else palette.chipInactiveBg,
                    cornerRadiusPx = 14f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = if (isFull) palette.accentBluePressed else palette.accentBlue
                )
                val iconColor = if (isFull) palette.chipActiveText else palette.accentBlue
                setTextColor(iconColor)
                text = if (isFull) "Height: Full" else "Height: Standard"
                val d = ContextCompat.getDrawable(this@KeyboardyImeService, R.drawable.ic_fullscreen)?.mutate()
                d?.setBounds(0, 0, dpToPx(13), dpToPx(13))
                d?.setTint(iconColor)
                setCompoundDrawables(d, null, null, null)
                compoundDrawablePadding = dpToPx(4)
            }
        }

        if (keyboardPadChanged) {
            containerMiniQwerty?.let { tray ->
                tray.setBackgroundColor(palette.keyboardTrayBg)
                buildKeyboardPad(tray, scale, palette)
            }
        }

        if (heightOrSpacerChanged || scaleChanged) {
            window?.window?.decorView?.requestLayout()
        }
    }

    private fun readClipboardText(): String? {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyTextToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Keyboardy Search", text))
        } catch (_: Exception) {
        }
    }

    private fun readHostEditorText(): String? {
        return try {
            val ic = currentInputConnection ?: return null
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
            if (!extracted.isNullOrBlank()) {
                extracted
            } else {
                ic.getTextBeforeCursor(60, 0)?.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clearHostEditorQueryIfApplicable() {
        if (!queryOriginatedFromHostEditor || !prefs.autoReadAndReplaceChatQuery) return
        try {
            val ic = currentInputConnection ?: return
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            if (extracted != null && extracted.text != null) {
                val len = extracted.text.length
                ic.beginBatchEdit()
                ic.setSelection(0, len)
                ic.commitText("", 1)
                ic.endBatchEdit()
            } else {
                ic.deleteSurroundingText(getCurrentQuery().length, 0)
            }
            queryOriginatedFromHostEditor = false
        } catch (_: Exception) {
        }
    }

    private fun updateEngineChipUi() {
        val mode = prefs.engineMode
        chipEngineMode?.text = "Engine: ${mode.displayName}"
        lastAppliedStyle = lastAppliedStyle?.copy(engineMode = mode)
    }

    private fun setMiniQwertyVisible(visible: Boolean) {
        containerMiniQwerty?.visibility = if (visible) View.VISIBLE else View.GONE
        suggestionBarScroll?.visibility = if (visible) View.VISIBLE else View.GONE
        etSearchQuery?.setCursorActive(visible)
        lastAppliedStyle = lastAppliedStyle?.copy(isQwertyVisible = visible)

        bottomNavSpacer?.let { spacer ->
            val targetHeightPx = if (visible) {
                dpToPx(prefs.bottomNavClearanceDp)
            } else {
                dpToPx(8)
            }
            val lp = spacer.layoutParams
            if (lp != null && lp.height != targetHeightPx) {
                lp.height = targetHeightPx
                spacer.layoutParams = lp
                spacer.requestLayout()
            }
        }
    }

    private fun updateWordSuggestions(query: String) {
        val palette = ThemePalette.forMode(prefs.isDarkMode)
        // 1. Instant local suggestions & spell check (0ms latency on Main thread)
        val instantList = SpellAndSuggestionEngine.getInstantSuggestions(query)
        renderSuggestions(instantList, palette)

        // 2. Enhanced async Google completions
        suggestJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            suggestJob = serviceScope.launch {
                val enhanced = SpellAndSuggestionEngine.fetchEnhancedSuggestions(trimmed)
                renderSuggestions(enhanced, palette)
            }
        }
    }

    private fun renderSuggestions(items: List<SuggestionItem>, palette: ThemePalette) {
        lastRenderedSuggestions = items
        val container = suggestionBarContainer ?: return
        container.removeAllViews()
        if (items.isEmpty()) return

        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        for (item in items) {
            val chipTv = TextView(this).apply {
                text = item.text
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = if (item.isSpellCorrection) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (item.isSpellCorrection) palette.accentBlue else palette.textPrimary)
                background = palette.createRoundedRect(
                    fillColor = if (item.isSpellCorrection) palette.surfaceVariant else palette.surface,
                    cornerRadiusPx = 14f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = if (item.isSpellCorrection) palette.accentBlue else palette.border
                )
                val iconRes = when {
                    item.isSpellCorrection -> R.drawable.ic_check
                    item.isDefaultKeyword -> R.drawable.ic_sparkle
                    else -> 0
                }
                if (iconRes != 0) {
                    val d = ContextCompat.getDrawable(this@KeyboardyImeService, iconRes)?.mutate()
                    d?.setBounds(0, 0, dpToPx(13), dpToPx(13))
                    d?.setTint(palette.accentBlue)
                    setCompoundDrawables(d, null, null, null)
                    compoundDrawablePadding = dpToPx(4)
                } else {
                    setCompoundDrawables(null, null, null, null)
                }
                setPadding(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dpToPx(28)
                ).apply {
                    marginEnd = dpToPx(6)
                }
            }

            attachKeyTouchHandler(chipTv) {
                queryOriginatedFromHostEditor = false
                setHistoryPanelVisible(false)
                etSearchQuery?.replaceCurrentWord(item.text)
                updateWordSuggestions(getCurrentQuery())
                triggerSearch(immediate = true, hideKeyboardOnStart = false)
            }

            container.addView(chipTv)
        }
    }

    private fun triggerSearch(immediate: Boolean, hideKeyboardOnStart: Boolean) {
        val queryToRun = getCurrentQuery().trim()
        if (queryToRun.isEmpty()) {
            searchJob?.cancel()
            headlessGoogleSearcher?.stopActiveLoading()
            progressSearching?.visibility = View.GONE
            return
        }

        val incognito = isIncognitoSession()
        if (hideKeyboardOnStart) {
            setMiniQwertyVisible(false)
            setHistoryPanelVisible(false)
            hideSelectionActionBar()
            if (!incognito) {
                prefs.addSearchHistoryQuery(queryToRun)
            }
        }

        // Cancel previous search and abort ongoing WebView activity immediately
        searchJob?.cancel()
        headlessGoogleSearcher?.stopActiveLoading()
        progressSearching?.visibility = View.GONE

        searchJob = serviceScope.launch {
            if (!immediate) {
                // Debounce search while typing by ~550ms so typing is silky smooth
                delay(550L)
                if (queryToRun.length < 2) {
                    return@launch
                }
            }
            progressSearching?.visibility = View.VISIBLE
            if (gridAdapter?.itemCount == 0) {
                emptyStateContainer?.visibility = View.VISIBLE
                tvStatusMessage?.text = "Searching ${prefs.engineMode.displayName} for \"$queryToRun\"..."
            }

            // Record search execution count (once per distinct query/filter execution), except in incognito sessions
            if (!incognito) {
                val countKey = "${queryToRun.lowercase()}|${currentFilter.name}|${prefs.engineMode.name}|${prefs.nsfwModeEnabled}"
                if (countKey != lastCountedSearchQuery) {
                    lastCountedSearchQuery = countKey
                    prefs.recordSearchExecuted()
                }
            }

            val repo = searchRepository ?: return@launch
            val finalResults = repo.searchImages(
                query = queryToRun,
                filter = currentFilter,
                engineMode = prefs.engineMode,
                nsfwMode = prefs.nsfwModeEnabled,
                optionalApiKey = prefs.optionalApiKey,
                onProgressiveResults = { partialResults ->
                    if (partialResults.isNotEmpty()) {
                        emptyStateContainer?.visibility = View.GONE
                        gridAdapter?.submitList(partialResults)
                    }
                }
            )

            progressSearching?.visibility = View.GONE
            if (finalResults.isEmpty()) {
                emptyStateContainer?.visibility = View.VISIBLE
                tvStatusMessage?.text =
                    "No images found for \"$queryToRun\". Try another search term or tap Engine to switch."
            } else {
                emptyStateContainer?.visibility = View.GONE
                gridAdapter?.submitList(finalResults)
            }
        }
    }

    private fun onImageResultSelected(item: ImageResult) {
        val incognito = isIncognitoSession()
        val q = getCurrentQuery().trim()
        if (!incognito && q.isNotEmpty()) {
            prefs.addSearchHistoryQuery(q)
        }

        commitJob?.cancel()
        gridAdapter?.setLoadingItem(item.id)

        commitJob = serviceScope.launch {
            val outcome = commitHelper.downloadAndCommitImage(
                item = item,
                inputConnection = currentInputConnection,
                editorInfo = currentInputEditorInfo,
                onBeforeCommit = {
                    clearHostEditorQueryIfApplicable()
                }
            )

            gridAdapter?.setLoadingItem(null)

            when (outcome) {
                CommitOutcome.COMMITTED_DIRECTLY -> {
                    if (!incognito) {
                        prefs.recordImageInserted()
                    }
                    if (prefs.autoReturnToPreviousKeyboard) {
                        switchToPreviousKeyboard()
                    }
                }

                CommitOutcome.COPIED_TO_CLIPBOARD_FALLBACK -> {
                    if (!incognito) {
                        prefs.recordImageInserted()
                    }
                    Toast.makeText(
                        this@KeyboardyImeService,
                        "App doesn't accept direct images — copied to clipboard! Paste in chat.",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (prefs.autoReturnToPreviousKeyboard) {
                        switchToPreviousKeyboard()
                    }
                }

                CommitOutcome.DOWNLOAD_FAILED -> {
                    Toast.makeText(
                        this@KeyboardyImeService,
                        "Couldn't load that image. Try another result!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun switchToPreviousKeyboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (switchToPreviousInputMethod()) return
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val token = window?.window?.attributes?.token
            if (imm != null && token != null) {
                @Suppress("DEPRECATION")
                val switched = imm.switchToLastInputMethod(token)
                if (!switched) {
                    @Suppress("DEPRECATION")
                    imm.switchToNextInputMethod(token, false)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateShiftState(palette: ThemePalette) {
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)
        val cornerRadiusPx = 7f * density

        for ((tv, base) in letterKeyViews) {
            tv.text = if (isShiftActive) base.uppercase() else base.lowercase()
        }
        shiftKeyView?.let { shiftTv ->
            shiftTv.setTextColor(if (isShiftActive) Color.WHITE else palette.keyText)
            shiftTv.background = palette.createKeyDrawable(
                isAccent = isShiftActive,
                cornerRadiusPx = cornerRadiusPx,
                strokeWidthPx = strokePx
            )
        }
    }

    private fun buildKeyboardPad(
        container: LinearLayout,
        scale: KeyboardScale,
        palette: ThemePalette
    ) {
        lastAppliedStyle = lastAppliedStyle?.copy(currentLayer = currentLayer)
        container.removeAllViews()
        letterKeyViews.clear()
        shiftKeyView = null
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)
        val cornerRadiusPx = 7f * density

        val rows: List<List<String>> = when (currentLayer) {
            KeyboardLayer.LETTERS -> listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫")
            )

            KeyboardLayer.NUMBERS_PUNCTUATION -> listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
                listOf("=\\<", "*", "\"", "'", ":", ";", "!", "?", "⌫")
            )

            KeyboardLayer.SYMBOLS -> listOf(
                listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "Δ"),
                listOf("£", "€", "¥", "¢", "^", "°", "=", "{", "}", "\\"),
                listOf("?123", "%", "©", "®", "™", "✓", "[", "]", "⌫")
            )
        }

        for (rowKeys in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(scale.keyHeightDp)
                ).apply {
                    bottomMargin = dpToPx(4)
                }
            }

            for (keyLabel in rowKeys) {
                val isBackspace = keyLabel == "⌫"
                val isShift = keyLabel == "⇧"
                val isLayerSwitch = keyLabel == "=\\<" || keyLabel == "?123"
                val isLetterKey = keyLabel.length == 1 && keyLabel[0].isLetter()
                val weight = when {
                    isBackspace -> 1.5f
                    isShift || isLayerSwitch -> 1.45f
                    else -> 1f
                }

                val keyView = TextView(this).apply {
                    text = if (isLetterKey && isShiftActive) keyLabel.uppercase() else keyLabel
                    gravity = Gravity.CENTER
                    setTextColor(
                        if (isShift && isShiftActive) Color.WHITE else palette.keyText
                    )
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        when {
                            isLayerSwitch -> scale.keyTextSp - 2.5f
                            isBackspace || isShift -> scale.keyTextSp + 1f
                            else -> scale.keyTextSp
                        }
                    )
                    typeface = Typeface.DEFAULT_BOLD
                    background = palette.createKeyDrawable(
                        isAccent = isShift && isShiftActive,
                        cornerRadiusPx = cornerRadiusPx,
                        strokeWidthPx = strokePx
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        weight
                    ).apply {
                        marginStart = dpToPx(2)
                        marginEnd = dpToPx(2)
                    }

                    if (isBackspace) {
                        attachRepeatingBackspace(this)
                    } else {
                        if (isShift) {
                            shiftKeyView = this
                        }
                        if (isLetterKey) {
                            letterKeyViews.add(Pair(this, keyLabel.lowercase()))
                        }
                        attachKeyTouchHandler(this) {
                            queryOriginatedFromHostEditor = false
                            setHistoryPanelVisible(false)
                            when {
                                isShift -> {
                                    isShiftActive = !isShiftActive
                                    updateShiftState(palette)
                                }

                                keyLabel == "=\\<" -> {
                                    currentLayer = KeyboardLayer.SYMBOLS
                                    buildKeyboardPad(container, scale, palette)
                                }

                                keyLabel == "?123" -> {
                                    currentLayer = KeyboardLayer.NUMBERS_PUNCTUATION
                                    buildKeyboardPad(container, scale, palette)
                                }

                                else -> {
                                    val charToInsert = this.text.toString()
                                    etSearchQuery?.insertTextAtCursor(charToInsert)
                                    if (isShiftActive) {
                                        isShiftActive = false
                                        updateShiftState(palette)
                                    }
                                    updateWordSuggestions(getCurrentQuery())
                                    triggerSearch(immediate = false, hideKeyboardOnStart = false)
                                }
                            }
                        }
                    }
                }
                rowLayout.addView(keyView)
            }
            container.addView(rowLayout)
        }

        // Bottom Row: [Down-Arrow + Keyboard Icon] + [?123 / ABC] + [,] + [space] + [.] + [Search 🔍]
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(scale.keyHeightDp + 2)
            )
        }

        val hideKeyboardBtn = createHideKeyboardButton(1.25f, palette)
        attachKeyTouchHandler(hideKeyboardBtn) {
            setMiniQwertyVisible(false)
        }

        val layerToggleLabel = if (currentLayer == KeyboardLayer.LETTERS) "?123" else "ABC"
        val layerToggleBtn = createActionKey(layerToggleLabel, 1.25f, false, scale, palette)
        attachKeyTouchHandler(layerToggleBtn) {
            currentLayer = if (currentLayer == KeyboardLayer.LETTERS) {
                KeyboardLayer.NUMBERS_PUNCTUATION
            } else {
                KeyboardLayer.LETTERS
            }
            buildKeyboardPad(container, scale, palette)
        }

        val commaBtn = createActionKey(",", 0.75f, false, scale, palette)
        attachKeyTouchHandler(commaBtn) {
            queryOriginatedFromHostEditor = false
            setHistoryPanelVisible(false)
            etSearchQuery?.insertTextAtCursor(",")
            updateWordSuggestions(getCurrentQuery())
            triggerSearch(immediate = false, hideKeyboardOnStart = false)
        }

        val spaceBtn = createActionKey("Space", 2.9f, false, scale, palette)
        attachKeyTouchHandler(spaceBtn) {
            queryOriginatedFromHostEditor = false
            setHistoryPanelVisible(false)
            etSearchQuery?.insertTextAtCursor(" ")
            updateWordSuggestions(getCurrentQuery())
        }

        val periodBtn = createActionKey(".", 0.75f, false, scale, palette)
        attachKeyTouchHandler(periodBtn) {
            queryOriginatedFromHostEditor = false
            setHistoryPanelVisible(false)
            etSearchQuery?.insertTextAtCursor(".")
            updateWordSuggestions(getCurrentQuery())
            triggerSearch(immediate = false, hideKeyboardOnStart = false)
        }

        val searchBtn = createActionKey("Search", 1.85f, true, scale, palette)
        attachKeyTouchHandler(searchBtn) {
            triggerSearch(immediate = true, hideKeyboardOnStart = true)
        }

        bottomRow.addView(hideKeyboardBtn)
        bottomRow.addView(layerToggleBtn)
        bottomRow.addView(commaBtn)
        bottomRow.addView(spaceBtn)
        bottomRow.addView(periodBtn)
        bottomRow.addView(searchBtn)
        container.addView(bottomRow)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachKeyTouchHandler(
        view: View,
        onDown: () -> Unit
    ) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    performTapHaptic(v)
                    onDown()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachRepeatingBackspace(view: View) {
        val repeatRunnable = object : Runnable {
            override fun run() {
                queryOriginatedFromHostEditor = false
                val deleted = etSearchQuery?.backspaceAtCursor() == true
                if (deleted) {
                    performTapHaptic(view)
                    updateWordSuggestions(getCurrentQuery())
                    triggerSearch(immediate = false, hideKeyboardOnStart = false)
                    mainHandler.postDelayed(this, 65L)
                }
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    performTapHaptic(v)
                    queryOriginatedFromHostEditor = false
                    if (etSearchQuery?.backspaceAtCursor() == true) {
                        updateWordSuggestions(getCurrentQuery())
                        triggerSearch(immediate = false, hideKeyboardOnStart = false)
                    }
                    mainHandler.removeCallbacks(repeatRunnable)
                    mainHandler.postDelayed(repeatRunnable, 380L)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    mainHandler.removeCallbacks(repeatRunnable)
                    true
                }

                else -> false
            }
        }
    }

    private fun createHideKeyboardButton(
        weight: Float,
        palette: ThemePalette
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = palette.createKeyDrawable(
                isAccent = false,
                cornerRadiusPx = 7f * density,
                strokeWidthPx = strokePx
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                weight
            ).apply {
                marginStart = dpToPx(2)
                marginEnd = dpToPx(2)
            }

            val iconView = ImageView(this@KeyboardyImeService).apply {
                setImageResource(R.drawable.ic_hide_keyboard)
                setColorFilter(palette.keyText)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(18))
            }
            addView(iconView)
        }
    }

    private fun createActionKey(
        label: String,
        weight: Float,
        isAccent: Boolean,
        scale: KeyboardScale,
        palette: ThemePalette
    ): TextView {
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (isAccent) Color.WHITE else palette.keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scale.keyTextSp - 2.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = palette.createKeyDrawable(
                isAccent = isAccent,
                cornerRadiusPx = 7f * density,
                strokeWidthPx = strokePx
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                weight
            ).apply {
                marginStart = dpToPx(2)
                marginEnd = dpToPx(2)
            }
        }
    }

    private fun performTapHaptic(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25L)
            }
        } catch (_: Exception) {
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        prefs.unregisterListener(prefChangeListener)
        mainHandler.removeCallbacksAndMessages(null)
        searchJob?.cancel()
        suggestJob?.cancel()
        commitJob?.cancel()
        headlessGoogleSearcher?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    enum class ImePrefReaction {
        NONE,
        STYLE_UPDATE,
        ENGINE_CHIP_UPDATE,
        RE_RUN_SEARCH
    }

    companion object {
        fun classifyPrefChange(key: String?): ImePrefReaction {
            return when (key) {
                null,
                KeyboardyPrefs.KEY_THEME_MODE,
                KeyboardyPrefs.KEY_KEYBOARD_SCALE,
                KeyboardyPrefs.KEY_BOTTOM_CLEARANCE_DP,
                KeyboardyPrefs.KEY_GRID_COLUMNS,
                KeyboardyPrefs.KEY_FULL_SCREEN_MODE -> ImePrefReaction.STYLE_UPDATE

                KeyboardyPrefs.KEY_ENGINE_MODE -> ImePrefReaction.ENGINE_CHIP_UPDATE

                KeyboardyPrefs.KEY_NSFW_MODE -> ImePrefReaction.RE_RUN_SEARCH

                else -> ImePrefReaction.NONE
            }
        }
    }
}
