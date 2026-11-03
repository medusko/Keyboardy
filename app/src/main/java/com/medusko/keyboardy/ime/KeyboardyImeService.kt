package com.medusko.keyboardy.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medusko.keyboardy.R
import com.medusko.keyboardy.prefs.KeyboardScale
import com.medusko.keyboardy.prefs.KeyboardyPrefs
import com.medusko.keyboardy.search.HeadlessGoogleWebViewSearcher
import com.medusko.keyboardy.search.ImageResult
import com.medusko.keyboardy.search.ImageSearchRepository
import com.medusko.keyboardy.search.SearchFilter
import com.medusko.keyboardy.theme.ThemePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class KeyboardyImeService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
    private var tvSearchQuery: TextView? = null
    private var btnClearQuery: ImageView? = null
    private var btnCycleScale: TextView? = null
    private var btnToggleQwerty: TextView? = null
    private var progressSearching: ProgressBar? = null
    private var rvImageResults: RecyclerView? = null
    private var emptyStateContainer: LinearLayout? = null
    private var tvStatusMessage: TextView? = null
    private var containerMiniQwerty: LinearLayout? = null
    private var bottomNavSpacer: View? = null
    private var chipEngineMode: TextView? = null
    private val filterChipViews = mutableMapOf<SearchFilter, TextView>()

    // State
    private var currentQuery: String = ""
    private var currentFilter: SearchFilter = SearchFilter.ALL
    private var queryOriginatedFromHostEditor: Boolean = false
    private var systemNavInsetBottomPx: Int = 0

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardyPrefs(this)
        commitHelper = ImageCommitHelper(this)
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.view_keyboardy_ime, null)

        rootImeContainer = root.findViewById(R.id.root_ime_container)
        topBarContainer = root.findViewById(R.id.top_bar_container)
        btnBackKeyboard = root.findViewById(R.id.btn_back_keyboard)
        ivBackIcon = root.findViewById(R.id.iv_back_icon)
        tvBackLabel = root.findViewById(R.id.tv_back_label)
        searchBarContainer = root.findViewById(R.id.search_bar_container)
        ivSearchIcon = root.findViewById(R.id.iv_search_icon)
        tvSearchQuery = root.findViewById(R.id.tv_search_query)
        btnClearQuery = root.findViewById(R.id.btn_clear_query)
        btnCycleScale = root.findViewById(R.id.btn_cycle_scale)
        btnToggleQwerty = root.findViewById(R.id.btn_toggle_qwerty)
        progressSearching = root.findViewById(R.id.progress_searching)
        rvImageResults = root.findViewById(R.id.rv_image_results)
        emptyStateContainer = root.findViewById(R.id.empty_state_container)
        tvStatusMessage = root.findViewById(R.id.tv_status_message)
        containerMiniQwerty = root.findViewById(R.id.container_mini_qwerty)
        bottomNavSpacer = root.findViewById(R.id.bottom_nav_spacer)
        chipEngineMode = root.findViewById(R.id.chip_engine_mode)

        val webViewHolder: FrameLayout = root.findViewById(R.id.headless_webview_holder)

        filterChipViews[SearchFilter.ALL] = root.findViewById(R.id.chip_filter_all)
        filterChipViews[SearchFilter.GIF] = root.findViewById(R.id.chip_filter_gif)
        filterChipViews[SearchFilter.MEME] = root.findViewById(R.id.chip_filter_meme)
        filterChipViews[SearchFilter.REACTION] = root.findViewById(R.id.chip_filter_reaction)
        filterChipViews[SearchFilter.TRANSPARENT] = root.findViewById(R.id.chip_filter_sticker)

        // Listen for Android 15/16 bottom navigation bar insets so we always clear the gesture bar & keyboard switcher button
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.navigationBars()
            )
            systemNavInsetBottomPx = navInsets.bottom
            applyScaleAndTheme()
            insets
        }

        // Initialize Headless Google Images WebView searcher & repository
        headlessGoogleSearcher?.destroy()
        val googleSearcher = HeadlessGoogleWebViewSearcher(this, webViewHolder)
        headlessGoogleSearcher = googleSearcher
        searchRepository = ImageSearchRepository(googleSearcher)

        // Setup 3-Column Image & GIF RecyclerView
        val adapter = ImageGridAdapter(this) { selectedItem ->
            onImageResultSelected(selectedItem)
        }
        gridAdapter = adapter
        rvImageResults?.apply {
            layoutManager = GridLayoutManager(this@KeyboardyImeService, 3)
            this.adapter = adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        setMiniQwertyVisible(false)
                    }
                }
            })
        }

        // Back to Gboard / Previous Keyboard button
        btnBackKeyboard?.setOnClickListener { view ->
            performTapHaptic(view)
            switchToPreviousKeyboard()
        }

        // Search bar tap opens the built-in Mini-QWERTY pad
        searchBarContainer?.setOnClickListener { view ->
            performTapHaptic(view)
            setMiniQwertyVisible(true)
        }

        // Clear search button
        btnClearQuery?.setOnClickListener { view ->
            performTapHaptic(view)
            resetToBlankSearch(showQwerty = true)
        }

        // Quick Scale button (cycles Compact -> Normal -> Large -> XL right from the keyboard!)
        btnCycleScale?.setOnClickListener { view ->
            performTapHaptic(view)
            prefs.keyboardScale = prefs.keyboardScale.next()
            applyScaleAndTheme()
        }

        // Toggle Mini-QWERTY button
        btnToggleQwerty?.setOnClickListener { view ->
            performTapHaptic(view)
            val currentlyVisible = containerMiniQwerty?.visibility == View.VISIBLE
            setMiniQwertyVisible(!currentlyVisible)
        }

        // Filter Chips
        for ((filter, chipView) in filterChipViews) {
            chipView.setOnClickListener { view ->
                performTapHaptic(view)
                currentFilter = filter
                applyScaleAndTheme()
                if (currentQuery.isNotBlank()) {
                    triggerSearch(immediate = true, hideKeyboardOnStart = true)
                }
            }
        }

        // Engine Switcher Chip (cycles Hybrid -> Google WebView -> Instant Web)
        chipEngineMode?.setOnClickListener { view ->
            performTapHaptic(view)
            prefs.engineMode = prefs.engineMode.next()
            updateEngineChipUi()
            if (currentQuery.isNotBlank()) {
                triggerSearch(immediate = true, hideKeyboardOnStart = false)
            }
        }

        applyScaleAndTheme()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyScaleAndTheme()

        if (!restarting) {
            // Optional Auto-Search from Chat Box (OFF by default so every search starts blank unless user enables it!)
            if (prefs.autoReadAndReplaceChatQuery) {
                val hostText = readHostEditorText()?.trim().orEmpty()
                if (hostText.isNotEmpty() && hostText.length <= 60 && !hostText.contains("\n")) {
                    currentQuery = hostText
                    queryOriginatedFromHostEditor = true
                    updateQueryDisplay()
                    setMiniQwertyVisible(false)
                    triggerSearch(immediate = true, hideKeyboardOnStart = true)
                    return
                }
            }

            // Default behavior: Start every new search session fresh & blank!
            resetToBlankSearch(showQwerty = true)
        }
    }

    private fun resetToBlankSearch(showQwerty: Boolean) {
        searchJob?.cancel()
        currentQuery = ""
        queryOriginatedFromHostEditor = false
        progressSearching?.visibility = View.GONE
        updateQueryDisplay()
        gridAdapter?.submitList(emptyList())
        emptyStateContainer?.visibility = View.VISIBLE
        tvStatusMessage?.text =
            "Type below to search Google Images & GIFs — tap any result to insert directly into your chat!"
        if (showQwerty) {
            setMiniQwertyVisible(true)
        }
    }

    /**
     * Dynamically applies Light/Dark mode colors (matching Lagatron's Blue & Black Dark Mode or Warm Cream Light Mode)
     * as well as the selected KeyboardScale and Bottom Navigation Bar clearance.
     */
    private fun applyScaleAndTheme() {
        val palette = ThemePalette.forMode(prefs.isDarkMode)
        val scale = prefs.keyboardScale
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        // Calculate bottom safe clearance so the bottom row of keys sits cleanly above the gesture bar & keyboard switcher button
        val configuredClearancePx = dpToPx(prefs.bottomNavClearanceDp)
        val effectiveBottomSpacerPx = maxOf(systemNavInsetBottomPx, configuredClearancePx)

        // Update total keyboard container height and background
        rootImeContainer?.let { root ->
            root.setBackgroundColor(palette.background)
            val totalHeightPx = dpToPx(scale.totalHeightDp) + effectiveBottomSpacerPx
            val lp = root.layoutParams
            if (lp != null) {
                lp.height = totalHeightPx
                root.layoutParams = lp
            }
        }

        // Update Top Bar height
        topBarContainer?.let { bar ->
            val lp = bar.layoutParams
            if (lp != null) {
                lp.height = dpToPx(scale.topBarHeightDp)
                bar.layoutParams = lp
            }
        }

        // Update Bottom Nav Spacer height and color
        bottomNavSpacer?.let { spacer ->
            spacer.setBackgroundColor(palette.keyboardTrayBg)
            val lp = spacer.layoutParams
            if (lp != null) {
                lp.height = effectiveBottomSpacerPx
                spacer.layoutParams = lp
            }
        }

        // Style Top Bar buttons & Search Bar
        val buttonBg = palette.createKeyDrawable(
            isAccent = false,
            cornerRadiusPx = 8f * density,
            strokeWidthPx = strokePx
        )
        btnBackKeyboard?.background = buttonBg
        ivBackIcon?.setColorFilter(palette.textPrimary)
        tvBackLabel?.setTextColor(palette.textPrimary)

        btnCycleScale?.apply {
            background = palette.createKeyDrawable(
                isAccent = false,
                cornerRadiusPx = 8f * density,
                strokeWidthPx = strokePx
            )
            setTextColor(palette.accentBlue)
            text = when (scale) {
                KeyboardScale.COMPACT -> "↕ 100%"
                KeyboardScale.NORMAL -> "↕ 115%"
                KeyboardScale.LARGE -> "↕ 130%"
                KeyboardScale.XLARGE -> "↕ 145%"
            }
        }

        btnToggleQwerty?.apply {
            background = palette.createKeyDrawable(
                isAccent = false,
                cornerRadiusPx = 8f * density,
                strokeWidthPx = strokePx
            )
            setTextColor(palette.textPrimary)
        }

        searchBarContainer?.background = palette.createRoundedRect(
            fillColor = palette.surface,
            cornerRadiusPx = 20f * density,
            strokeWidthPx = strokePx,
            strokeColor = palette.border
        )
        ivSearchIcon?.setColorFilter(palette.textSecondary)
        btnClearQuery?.setColorFilter(palette.textSecondary)
        tvSearchQuery?.apply {
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
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

        // Empty State Text
        tvStatusMessage?.setTextColor(palette.textSecondary)

        // Update Image Grid Adapter scale & palette
        gridAdapter?.updateStyle(dpToPx(scale.gridItemHeightDp), palette)

        // Rebuild Mini-QWERTY Pad with current scale and palette
        containerMiniQwerty?.let { tray ->
            tray.setBackgroundColor(palette.keyboardTrayBg)
            buildMiniQwertyPad(tray, scale, palette)
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
                ic.deleteSurroundingText(currentQuery.length, 0)
            }
            queryOriginatedFromHostEditor = false
        } catch (_: Exception) {
        }
    }

    private fun updateQueryDisplay() {
        val tv = tvSearchQuery ?: return
        tv.text = currentQuery
        btnClearQuery?.visibility = if (currentQuery.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateEngineChipUi() {
        chipEngineMode?.text = "Engine: ${prefs.engineMode.displayName}"
    }

    private fun setMiniQwertyVisible(visible: Boolean) {
        containerMiniQwerty?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun triggerSearch(immediate: Boolean, hideKeyboardOnStart: Boolean) {
        val queryToRun = currentQuery.trim()
        if (queryToRun.isEmpty()) {
            searchJob?.cancel()
            progressSearching?.visibility = View.GONE
            return
        }

        if (hideKeyboardOnStart) {
            setMiniQwertyVisible(false)
        }

        searchJob?.cancel()
        searchJob = serviceScope.launch {
            if (!immediate) {
                delay(360L)
            }
            progressSearching?.visibility = View.VISIBLE
            if (gridAdapter?.itemCount == 0) {
                emptyStateContainer?.visibility = View.VISIBLE
                tvStatusMessage?.text = "Searching ${prefs.engineMode.displayName} for \"$queryToRun\"..."
            }

            val repo = searchRepository ?: return@launch
            val finalResults = repo.searchImages(
                query = queryToRun,
                filter = currentFilter,
                engineMode = prefs.engineMode,
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
                    if (prefs.autoReturnToPreviousKeyboard) {
                        switchToPreviousKeyboard()
                    }
                }

                CommitOutcome.COPIED_TO_CLIPBOARD_FALLBACK -> {
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

    private fun buildMiniQwertyPad(
        container: LinearLayout,
        scale: KeyboardScale,
        palette: ThemePalette
    ) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)
        val cornerRadiusPx = 7f * density

        val rows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m", "⌫")
        )

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
                val keyView = TextView(this).apply {
                    text = keyLabel
                    gravity = Gravity.CENTER
                    setTextColor(palette.keyText)
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        if (isBackspace) scale.keyTextSp + 1f else scale.keyTextSp
                    )
                    typeface = Typeface.DEFAULT_BOLD
                    background = palette.createKeyDrawable(
                        isAccent = false,
                        cornerRadiusPx = cornerRadiusPx,
                        strokeWidthPx = strokePx
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        if (isBackspace) 1.55f else 1f
                    ).apply {
                        marginStart = dpToPx(2)
                        marginEnd = dpToPx(2)
                    }
                    setOnClickListener { view ->
                        performTapHaptic(view)
                        queryOriginatedFromHostEditor = false
                        if (isBackspace) {
                            if (currentQuery.isNotEmpty()) {
                                currentQuery = currentQuery.dropLast(1)
                                updateQueryDisplay()
                                triggerSearch(immediate = false, hideKeyboardOnStart = false)
                            }
                        } else {
                            currentQuery += keyLabel
                            updateQueryDisplay()
                            triggerSearch(immediate = false, hideKeyboardOnStart = false)
                        }
                    }
                }
                rowLayout.addView(keyView)
            }
            container.addView(rowLayout)
        }

        // Bottom row: Hide Grid button + Spacebar + Blue Search Button
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(scale.keyHeightDp + 2)
            )
        }

        val hideBtn = createActionKey("▼ Grid", 1.35f, false, scale, palette) {
            setMiniQwertyVisible(false)
        }
        val spaceBtn = createActionKey("space", 3.4f, false, scale, palette) {
            if (currentQuery.isNotEmpty() && !currentQuery.endsWith(" ")) {
                currentQuery += " "
                queryOriginatedFromHostEditor = false
                updateQueryDisplay()
            }
        }
        val searchBtn = createActionKey("Search 🔍", 1.85f, true, scale, palette) {
            triggerSearch(immediate = true, hideKeyboardOnStart = true)
        }

        bottomRow.addView(hideBtn)
        bottomRow.addView(spaceBtn)
        bottomRow.addView(searchBtn)
        container.addView(bottomRow)
    }

    private fun createActionKey(
        label: String,
        weight: Float,
        isAccent: Boolean,
        scale: KeyboardScale,
        palette: ThemePalette,
        onClick: () -> Unit
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
            setOnClickListener { view ->
                performTapHaptic(view)
                onClick()
            }
        }
    }

    private fun performTapHaptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        commitJob?.cancel()
        headlessGoogleSearcher?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }
}
