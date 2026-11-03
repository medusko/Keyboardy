package com.medusko.keyboardy.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var progressSearching: ProgressBar? = null
    private var rvImageResults: RecyclerView? = null
    private var emptyStateContainer: LinearLayout? = null
    private var tvStatusMessage: TextView? = null
    private var containerMiniQwerty: LinearLayout? = null
    private var bottomNavSpacer: View? = null
    private var chipEngineMode: TextView? = null
    private val filterChipViews = mutableMapOf<SearchFilter, TextView>()

    // State
    private var currentFilter: SearchFilter = SearchFilter.ALL
    private var currentLayer: KeyboardLayer = KeyboardLayer.LETTERS
    private var isShiftActive: Boolean = false
    private var queryOriginatedFromHostEditor: Boolean = false
    private var systemNavInsetBottomPx: Int = 0
    private var systemStatusInsetTopPx: Int = 0

    // Real-time preference listener so changes in MainActivity apply immediately to the keyboard
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post {
            applyScaleAndTheme()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardyPrefs(this)
        prefs.registerListener(prefChangeListener)
        commitHelper = ImageCommitHelper(this)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Keep false so Android doesn't cover our UI with a legacy ExtractEditText;
        // our rootImeContainer itself expands to full screen height!
        return false
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
        etSearchQuery = root.findViewById(R.id.et_search_query)
        btnClearQuery = root.findViewById(R.id.btn_clear_query)
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

        // Listen for Android 15/16 system bar insets
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            systemNavInsetBottomPx = bars.bottom
            systemStatusInsetTopPx = bars.top
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

        // Interactive Search EditText callbacks (tap anywhere to position cursor or drag to select text!)
        etSearchQuery?.apply {
            onSearchBarTapped = {
                setMiniQwertyVisible(true)
            }
            onQueryTextChanged = { updatedText ->
                btnClearQuery?.visibility = if (updatedText.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        searchBarContainer?.setOnClickListener { view ->
            performTapHaptic(view)
            setMiniQwertyVisible(true)
            val len = etSearchQuery?.getQueryText()?.length ?: 0
            etSearchQuery?.setCursorSelection(len, len)
        }

        // Clear search button
        btnClearQuery?.setOnClickListener { view ->
            performTapHaptic(view)
            resetToBlankSearch(showQwerty = true)
        }

        // Filter Chips
        for ((filter, chipView) in filterChipViews) {
            chipView.setOnClickListener { view ->
                performTapHaptic(view)
                currentFilter = filter
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyScaleAndTheme()

        if (!restarting) {
            currentLayer = KeyboardLayer.LETTERS
            isShiftActive = false

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
        queryOriginatedFromHostEditor = false
        progressSearching?.visibility = View.GONE
        etSearchQuery?.setQueryText("", placeCursorAtEnd = true)
        gridAdapter?.submitList(emptyList())
        emptyStateContainer?.visibility = View.VISIBLE
        tvStatusMessage?.text =
            "Type below to search Google Images & GIFs — tap any result to insert directly into your chat!"
        if (showQwerty) {
            setMiniQwertyVisible(true)
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

        // Calculate bottom safe clearance so the bottom row of keys sits cleanly above the gesture bar & keyboard switcher button
        val configuredClearancePx = dpToPx(prefs.bottomNavClearanceDp)
        val effectiveBottomSpacerPx = maxOf(systemNavInsetBottomPx, configuredClearancePx)

        // Full-Screen Keyboard Container:
        // Expands to fill the screen below the status bar so the search results grid takes the rest of the screen!
        val screenHeightPx = resources.displayMetrics.heightPixels
        val topStatusClearancePx = maxOf(systemStatusInsetTopPx, dpToPx(36))
        val fullScreenHeightPx = (screenHeightPx - topStatusClearancePx).coerceAtLeast(dpToPx(480))

        rootImeContainer?.let { root ->
            root.setBackgroundColor(palette.background)
            val lp = root.layoutParams
            if (lp != null) {
                lp.height = fullScreenHeightPx
                root.layoutParams = lp
            }
            root.requestLayout()
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
        btnBackKeyboard?.background = palette.createKeyDrawable(
            isAccent = false,
            cornerRadiusPx = 8f * density,
            strokeWidthPx = strokePx
        )
        ivBackIcon?.setColorFilter(palette.textPrimary)
        tvBackLabel?.setTextColor(palette.textPrimary)

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

        // Rebuild Scalable QWERTY / Numbers / Symbols Pad
        containerMiniQwerty?.let { tray ->
            tray.setBackgroundColor(palette.keyboardTrayBg)
            buildKeyboardPad(tray, scale, palette)
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
        etSearchQuery?.setCursorActive(visible)
    }

    private fun triggerSearch(immediate: Boolean, hideKeyboardOnStart: Boolean) {
        val queryToRun = getCurrentQuery().trim()
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

    private fun buildKeyboardPad(
        container: LinearLayout,
        scale: KeyboardScale,
        palette: ThemePalette
    ) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)
        val cornerRadiusPx = 7f * density

        val rows: List<List<String>> = when (currentLayer) {
            KeyboardLayer.LETTERS -> {
                val baseRows = listOf(
                    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                    listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫")
                )
                if (isShiftActive) {
                    baseRows.map { row ->
                        row.map { key ->
                            if (key.length == 1 && key[0].isLetter()) key.uppercase() else key
                        }
                    }
                } else {
                    baseRows
                }
            }

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
                val weight = when {
                    isBackspace -> 1.5f
                    isShift || isLayerSwitch -> 1.45f
                    else -> 1f
                }

                val keyView = TextView(this).apply {
                    text = keyLabel
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
                        setOnClickListener { view ->
                            performTapHaptic(view)
                            queryOriginatedFromHostEditor = false
                            when {
                                isShift -> {
                                    isShiftActive = !isShiftActive
                                    buildKeyboardPad(container, scale, palette)
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
                                    etSearchQuery?.insertTextAtCursor(keyLabel)
                                    if (isShiftActive) {
                                        isShiftActive = false
                                        buildKeyboardPad(container, scale, palette)
                                    }
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

        // 1. Left button: Down Arrow + Image of a Keyboard to hide typing pad
        val hideKeyboardBtn = createHideKeyboardButton(1.25f, palette) {
            setMiniQwertyVisible(false)
        }

        // 2. Layer Toggle Button (?123 <-> ABC)
        val layerToggleLabel = if (currentLayer == KeyboardLayer.LETTERS) "?123" else "ABC"
        val layerToggleBtn = createActionKey(layerToggleLabel, 1.25f, false, scale, palette) {
            currentLayer = if (currentLayer == KeyboardLayer.LETTERS) {
                KeyboardLayer.NUMBERS_PUNCTUATION
            } else {
                KeyboardLayer.LETTERS
            }
            buildKeyboardPad(container, scale, palette)
        }

        // 3. Quick Comma key
        val commaBtn = createActionKey(",", 0.75f, false, scale, palette) {
            queryOriginatedFromHostEditor = false
            etSearchQuery?.insertTextAtCursor(",")
            triggerSearch(immediate = false, hideKeyboardOnStart = false)
        }

        // 4. Spacebar
        val spaceBtn = createActionKey("space", 2.9f, false, scale, palette) {
            queryOriginatedFromHostEditor = false
            etSearchQuery?.insertTextAtCursor(" ")
        }

        // 5. Quick Period key
        val periodBtn = createActionKey(".", 0.75f, false, scale, palette) {
            queryOriginatedFromHostEditor = false
            etSearchQuery?.insertTextAtCursor(".")
            triggerSearch(immediate = false, hideKeyboardOnStart = false)
        }

        // 6. Search Action Button
        val searchBtn = createActionKey("Search 🔍", 1.85f, true, scale, palette) {
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
    private fun attachRepeatingBackspace(view: View) {
        val repeatRunnable = object : Runnable {
            override fun run() {
                queryOriginatedFromHostEditor = false
                val deleted = etSearchQuery?.backspaceAtCursor() == true
                if (deleted) {
                    performTapHaptic(view)
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
        palette: ThemePalette,
        onClick: () -> Unit
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

            setOnClickListener { view ->
                performTapHaptic(view)
                onClick()
            }
        }
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
        prefs.unregisterListener(prefChangeListener)
        mainHandler.removeCallbacksAndMessages(null)
        searchJob?.cancel()
        commitJob?.cancel()
        headlessGoogleSearcher?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }
}
