package com.medusko.keyboardy.ime

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medusko.keyboardy.MainActivity
import com.medusko.keyboardy.R
import com.medusko.keyboardy.prefs.KeyboardScale
import com.medusko.keyboardy.prefs.KeyboardyPrefs
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
    private var imeTopBoundary: LinearLayout? = null
    private var imeTopLine: View? = null
    private var keyboardBodySheet: LinearLayout? = null

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

    // Real-time preference listener so changes in MainActivity apply immediately to the keyboard
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        mainHandler.post {
            applyScaleAndTheme()
            if (key == "nsfw_mode_enabled" && getCurrentQuery().isNotBlank()) {
                triggerSearch(immediate = true, hideKeyboardOnStart = false)
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
        imeTopBoundary = root.findViewById(R.id.ime_top_boundary)
        imeTopLine = root.findViewById(R.id.ime_top_line)
        keyboardBodySheet = root.findViewById(R.id.keyboard_body_sheet)

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
            applyScaleAndTheme()
        }

        // Fullscreen Toggle Chip (toggles between standard ~74% height and ~96% fullscreen)
        chipFullScreen?.setOnClickListener { view ->
            performTapHaptic(view)
            prefs.isFullScreenMode = !prefs.isFullScreenMode
            applyScaleAndTheme()
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
                applyScaleAndTheme()
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

        applyScaleAndTheme()
        return root
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applyScaleAndTheme()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyScaleAndTheme()

        if (!restarting) {
            currentLayer = KeyboardLayer.LETTERS
            isShiftActive = false
            setHistoryPanelVisible(false)
            hideSelectionActionBar()

            // Optional Auto-Search from Chat Box (OFF by default so every search starts blank unless user enables it!)
            if (prefs.autoReadAndReplaceChatQuery) {
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
        }
    }

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
            "Type below to search Google Images & GIFs — or tap 🕒 for your search history!"
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
        isHistoryVisible = visible
        historyPanelContainer?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            populateHistoryPanel(ThemePalette.forMode(prefs.isDarkMode))
        }
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
     * Dynamically applies Light/Dark mode colors, full-screen search results height,
     * the selected KeyboardScale for the typing pad, and Bottom Navigation Bar clearance.
     */
    private fun applyScaleAndTheme() {
        val palette = ThemePalette.forMode(prefs.isDarkMode)
        val scale = prefs.keyboardScale
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        // Direct Bottom Bar Offset in pixels so every tap of -6 / +6 visibly moves the bottom of the keyboard!
        val bottomOffsetPx = dpToPx(prefs.bottomNavClearanceDp)
        val isFull = prefs.isFullScreenMode

        // Height: standard mode (~74% screen) leaves clear room to see and scroll host app content,
        // while Full Screen Mode (~96% screen) maximizes search & thumbnail viewing area.
        val screenHeightPx = resources.displayMetrics.heightPixels
        val targetContainerHeightPx = if (isFull) {
            (screenHeightPx * 0.96f).toInt()
        } else {
            (screenHeightPx * 0.74f).toInt().coerceAtLeast(dpToPx(420))
        }

        // Set IME dialog window background to transparent for seamless transparency behind the top line
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Root container is transparent so host app content is visible behind the top boundary
        rootImeContainer?.let { root ->
            root.setBackgroundColor(Color.TRANSPARENT)
            val lp = root.layoutParams
            if (lp != null) {
                lp.height = targetContainerHeightPx
                root.layoutParams = lp
            }
            root.requestLayout()
        }

        // Top Blue Bar: goes across the top with rounded off edges and transparent background behind it
        imeTopBoundary?.let { boundary ->
            val horizPadding = if (isFull) 0 else dpToPx(12)
            val topPadding = if (isFull) 0 else dpToPx(6)
            val bottomPadding = if (isFull) 0 else dpToPx(5)
            boundary.setPadding(horizPadding, topPadding, horizPadding, bottomPadding)
        }

        imeTopLine?.let { line ->
            val lp = line.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.height = if (isFull) dpToPx(2) else dpToPx(3.5f.toInt().coerceAtLeast(3))
                line.layoutParams = lp
            }
            val lineRadius = if (isFull) 0f else dpToPx(2).toFloat()
            line.background = palette.createRoundedRect(
                fillColor = palette.accentBlue,
                cornerRadiusPx = lineRadius
            )
        }

        // Main Keyboard Body Sheet: rounded off top edges in standard mode with palette background
        keyboardBodySheet?.let { sheet ->
            val topRadius = if (isFull) 0f else dpToPx(16).toFloat()
            val sheetBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(palette.background)
                if (topRadius > 0) {
                    cornerRadii = floatArrayOf(
                        topRadius, topRadius, // top-left
                        topRadius, topRadius, // top-right
                        0f, 0f,               // bottom-right
                        0f, 0f                // bottom-left
                    )
                }
            }
            sheet.background = sheetBg
        }

        // Update Top Bar height
        topBarContainer?.let { bar ->
            val lp = bar.layoutParams
            if (lp != null) {
                lp.height = dpToPx(scale.topBarHeightDp)
                bar.layoutParams = lp
            }
        }

        // Update Bottom Nav Spacer height and color directly from bottomOffsetPx
        bottomNavSpacer?.let { spacer ->
            spacer.setBackgroundColor(palette.keyboardTrayBg)
            val lp = spacer.layoutParams
            if (lp != null) {
                lp.height = bottomOffsetPx
                spacer.layoutParams = lp
            }
            spacer.requestLayout()
        }

        // Style Top Bar buttons & Search Bar
        btnBackKeyboard?.background = palette.createKeyDrawable(
            isAccent = false,
            cornerRadiusPx = 8f * density,
            strokeWidthPx = strokePx
        )
        ivBackIcon?.setColorFilter(palette.textPrimary)
        tvBackLabel?.setTextColor(palette.textPrimary)

        btnHistory?.background = palette.createKeyDrawable(
            isAccent = isHistoryVisible,
            cornerRadiusPx = 8f * density,
            strokeWidthPx = strokePx
        )
        ivHistoryIcon?.setColorFilter(if (isHistoryVisible) Color.WHITE else palette.textPrimary)

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

        // Style Copy / Cut / Paste / Select All Action Bar
        selectionActionBar?.setBackgroundColor(palette.surfaceVariant)
        for (ctxBtn in listOf(btnCtxPaste, btnCtxCopy, btnCtxCut, btnCtxSelectAll)) {
            ctxBtn?.background = palette.createRoundedRect(
                fillColor = palette.surface,
                cornerRadiusPx = 8f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.accentBlue
            )
            ctxBtn?.setTextColor(palette.textPrimary)
        }

        // Style History Panel
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
        if (isHistoryVisible) {
            populateHistoryPanel(palette)
        }

        // Style Filter Chips & Engine Mode Chip
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

        chipEngineMode?.apply {
            background = palette.createRoundedRect(
                fillColor = palette.chipInactiveBg,
                cornerRadiusPx = 14f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.accentBlue
            )
            setTextColor(palette.accentBlue)
            text = "Engine: ${prefs.engineMode.displayName}"
        }

        chipGridScale?.apply {
            background = palette.createRoundedRect(
                fillColor = palette.chipInactiveBg,
                cornerRadiusPx = 14f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.accentBlue
            )
            setTextColor(palette.accentBlue)
            text = "⊞ ${prefs.gridColumns}x Grid"
        }

        chipFullScreen?.apply {
            val isFull = prefs.isFullScreenMode
            background = palette.createRoundedRect(
                fillColor = if (isFull) palette.chipActiveBg else palette.chipInactiveBg,
                cornerRadiusPx = 14f * density,
                strokeWidthPx = strokePx,
                strokeColor = if (isFull) palette.accentBluePressed else palette.accentBlue
            )
            setTextColor(if (isFull) palette.chipActiveText else palette.accentBlue)
            text = if (isFull) "⛶ Height: Full" else "⛶ Height: Standard"
        }

        // Empty State Text
        tvStatusMessage?.setTextColor(palette.textSecondary)

        // Update Image Grid columns and calculated thumbnail height
        gridLayoutManager?.spanCount = prefs.gridColumns
        val dynamicHeightDp = prefs.getGridItemHeightDp(prefs.gridColumns)
        gridAdapter?.updateStyle(dpToPx(dynamicHeightDp), palette)

        // Rebuild Scalable QWERTY / Numbers / Symbols Pad
        suggestionBarScroll?.setBackgroundColor(palette.keyboardTrayBg)
        updateWordSuggestions(getCurrentQuery())
        containerMiniQwerty?.let { tray ->
            tray.setBackgroundColor(palette.keyboardTrayBg)
            buildKeyboardPad(tray, scale, palette)
        }

        window?.window?.decorView?.requestLayout()
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
        chipEngineMode?.text = "Engine: ${prefs.engineMode.displayName}"
    }

    private fun setMiniQwertyVisible(visible: Boolean) {
        containerMiniQwerty?.visibility = if (visible) View.VISIBLE else View.GONE
        suggestionBarScroll?.visibility = if (visible) View.VISIBLE else View.GONE
        etSearchQuery?.setCursorActive(visible)
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
        val container = suggestionBarContainer ?: return
        container.removeAllViews()
        if (items.isEmpty()) return

        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        for (item in items) {
            val chipTv = TextView(this).apply {
                val displayLabel = when {
                    item.isSpellCorrection -> "✓ ${item.text}"
                    item.isDefaultKeyword -> "✨ ${item.text}"
                    else -> item.text
                }
                text = displayLabel
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
                setPadding(dpToPx(12), dpToPx(3), dpToPx(12), dpToPx(3))
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

        if (hideKeyboardOnStart) {
            setMiniQwertyVisible(false)
            setHistoryPanelVisible(false)
            hideSelectionActionBar()
            prefs.addSearchHistoryQuery(queryToRun)
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

            // Record search execution count (once per distinct query/filter execution)
            val countKey = "${queryToRun.lowercase()}|${currentFilter.name}|${prefs.engineMode.name}|${prefs.nsfwModeEnabled}"
            if (countKey != lastCountedSearchQuery) {
                lastCountedSearchQuery = countKey
                prefs.recordSearchExecuted()
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
        val q = getCurrentQuery().trim()
        if (q.isNotEmpty()) {
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
                    prefs.recordImageInserted()
                    if (prefs.autoReturnToPreviousKeyboard) {
                        switchToPreviousKeyboard()
                    }
                }

                CommitOutcome.COPIED_TO_CLIPBOARD_FALLBACK -> {
                    prefs.recordImageInserted()
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
}
