package com.medusko.keyboardy

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.medusko.keyboardy.prefs.KeyboardScale
import com.medusko.keyboardy.prefs.KeyboardyPrefs
import com.medusko.keyboardy.theme.ThemePalette

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: KeyboardyPrefs

    private lateinit var rootScreenContainer: LinearLayout
    private lateinit var topAppBar: LinearLayout
    private lateinit var topBarDivider: View
    private lateinit var rootScrollView: ScrollView
    private lateinit var mainContentContainer: LinearLayout

    private lateinit var tvAppTitle: TextView
    private lateinit var statusPillContainer: LinearLayout
    private lateinit var statusPillDot: View
    private lateinit var tvStatusPillText: TextView
    private lateinit var btnAboutInfo: ImageButton
    private lateinit var btnToggleTheme: ImageButton

    private lateinit var tvSectionEnablement: TextView
    private lateinit var tvSectionCustomization: TextView
    private lateinit var tvSectionHelp: TextView

    private lateinit var cardSetup: LinearLayout
    private lateinit var setupHeaderRow: LinearLayout
    private lateinit var tvImeStatus: TextView
    private lateinit var tvSetupChevron: TextView
    private lateinit var setupCollapsibleBody: LinearLayout
    private lateinit var tvSetupDesc: TextView
    private lateinit var btnEnableIme: Button
    private var setupCardManuallyExpanded: Boolean = false

    private lateinit var cardScale: LinearLayout
    private lateinit var tvScaleHeader: TextView
    private lateinit var tvScaleDesc: TextView
    private lateinit var btnScaleCompact: TextView
    private lateinit var btnScaleNormal: TextView
    private lateinit var btnScaleLarge: TextView
    private lateinit var btnScaleXLarge: TextView
    private lateinit var tvBottomClearanceLabel: TextView
    private lateinit var btnClearanceMinus: TextView
    private lateinit var btnClearancePlus: TextView

    private lateinit var cardGridScale: LinearLayout
    private lateinit var tvGridScaleHeader: TextView
    private lateinit var tvGridScaleDesc: TextView
    private lateinit var btnGrid1Col: TextView
    private lateinit var btnGrid2Col: TextView
    private lateinit var btnGrid3Col: TextView
    private lateinit var btnGrid4Col: TextView

    private lateinit var cardSandbox: LinearLayout
    private lateinit var tvSandboxHeader: TextView
    private lateinit var tvSandboxDesc: TextView
    private lateinit var sandboxReceivedContainer: LinearLayout
    private lateinit var ivSandboxReceived: ImageView
    private lateinit var tvSandboxReceivedInfo: TextView
    private lateinit var etChatSandbox: EditText

    private lateinit var cardSettings: LinearLayout
    private lateinit var tvSettingsHeader: TextView
    private lateinit var cbAutoReturn: CheckBox
    private lateinit var cbAutoReadChat: CheckBox
    private lateinit var cbNsfwMode: CheckBox
    private lateinit var cbFullScreenMode: CheckBox

    private lateinit var cardAppIcon: LinearLayout
    private lateinit var tvAppIconHeader: TextView
    private lateinit var tvAppIconDesc: TextView
    private lateinit var btnIconGold: LinearLayout
    private lateinit var tvIconGoldTitle: TextView
    private lateinit var tvIconGoldBadge: TextView
    private lateinit var btnIconClassic: LinearLayout
    private lateinit var tvIconClassicTitle: TextView
    private lateinit var tvIconClassicBadge: TextView

    private lateinit var cardBadges: LinearLayout
    private lateinit var tvBadgesHeader: TextView
    private lateinit var tvBadgesDesc: TextView
    private lateinit var badgeLegendBox: LinearLayout
    private lateinit var tvBadgeLegendBody: TextView

    private lateinit var cardQuota: LinearLayout
    private lateinit var tvQuotaHeader: TextView
    private lateinit var quotaStatsBox: LinearLayout
    private lateinit var tvQuotaStats: TextView
    private lateinit var tvQuotaTierNote: TextView
    private lateinit var tvWhyApiTitle: TextView
    private lateinit var tvApiKeyLabel: TextView
    private lateinit var etOptionalApiKey: EditText
    private lateinit var btnSaveSettings: Button

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread {
            updateQuotaStatsUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = KeyboardyPrefs(this)
        prefs.registerListener(prefChangeListener)

        rootScreenContainer = findViewById(R.id.root_screen_container)
        topAppBar = findViewById(R.id.top_app_bar)
        topBarDivider = findViewById(R.id.top_bar_divider)
        rootScrollView = findViewById(R.id.root_scroll_view)
        mainContentContainer = findViewById(R.id.main_content_container)

        tvAppTitle = findViewById(R.id.tv_app_title)
        statusPillContainer = findViewById(R.id.status_pill_container)
        statusPillDot = findViewById(R.id.status_pill_dot)
        tvStatusPillText = findViewById(R.id.tv_status_pill_text)
        btnAboutInfo = findViewById(R.id.btn_about_info)
        btnToggleTheme = findViewById(R.id.btn_toggle_theme)

        tvSectionEnablement = findViewById(R.id.tv_section_enablement)
        tvSectionCustomization = findViewById(R.id.tv_section_customization)
        tvSectionHelp = findViewById(R.id.tv_section_help)

        cardSetup = findViewById(R.id.card_setup)
        setupHeaderRow = findViewById(R.id.setup_header_row)
        tvImeStatus = findViewById(R.id.tv_ime_status)
        tvSetupChevron = findViewById(R.id.tv_setup_chevron)
        setupCollapsibleBody = findViewById(R.id.setup_collapsible_body)
        tvSetupDesc = findViewById(R.id.tv_setup_desc)
        btnEnableIme = findViewById(R.id.btn_enable_ime)

        cardScale = findViewById(R.id.card_scale)
        tvScaleHeader = findViewById(R.id.tv_scale_header)
        tvScaleDesc = findViewById(R.id.tv_scale_desc)
        btnScaleCompact = findViewById(R.id.btn_scale_compact)
        btnScaleNormal = findViewById(R.id.btn_scale_normal)
        btnScaleLarge = findViewById(R.id.btn_scale_large)
        btnScaleXLarge = findViewById(R.id.btn_scale_xlarge)
        tvBottomClearanceLabel = findViewById(R.id.tv_bottom_clearance_label)
        btnClearanceMinus = findViewById(R.id.btn_clearance_minus)
        btnClearancePlus = findViewById(R.id.btn_clearance_plus)

        cardGridScale = findViewById(R.id.card_grid_scale)
        tvGridScaleHeader = findViewById(R.id.tv_grid_scale_header)
        tvGridScaleDesc = findViewById(R.id.tv_grid_scale_desc)
        btnGrid1Col = findViewById(R.id.btn_grid_1col)
        btnGrid2Col = findViewById(R.id.btn_grid_2col)
        btnGrid3Col = findViewById(R.id.btn_grid_3col)
        btnGrid4Col = findViewById(R.id.btn_grid_4col)

        cardSandbox = findViewById(R.id.card_sandbox)
        tvSandboxHeader = findViewById(R.id.tv_sandbox_header)
        tvSandboxDesc = findViewById(R.id.tv_sandbox_desc)
        sandboxReceivedContainer = findViewById(R.id.sandbox_received_container)
        ivSandboxReceived = findViewById(R.id.iv_sandbox_received)
        tvSandboxReceivedInfo = findViewById(R.id.tv_sandbox_received_info)
        etChatSandbox = findViewById(R.id.et_chat_sandbox)

        cardAppIcon = findViewById(R.id.card_app_icon)
        tvAppIconHeader = findViewById(R.id.tv_app_icon_header)
        tvAppIconDesc = findViewById(R.id.tv_app_icon_desc)
        btnIconGold = findViewById(R.id.btn_icon_gold)
        tvIconGoldTitle = findViewById(R.id.tv_icon_gold_title)
        tvIconGoldBadge = findViewById(R.id.tv_icon_gold_badge)
        btnIconClassic = findViewById(R.id.btn_icon_classic)
        tvIconClassicTitle = findViewById(R.id.tv_icon_classic_title)
        tvIconClassicBadge = findViewById(R.id.tv_icon_classic_badge)

        btnIconGold.setOnClickListener {
            setAppIconStyle(useClassic = false)
        }
        btnIconClassic.setOnClickListener {
            setAppIconStyle(useClassic = true)
        }

        cardSettings = findViewById(R.id.card_settings)
        tvSettingsHeader = findViewById(R.id.tv_settings_header)
        cbAutoReturn = findViewById(R.id.cb_auto_return_gboard)
        cbAutoReadChat = findViewById(R.id.cb_auto_read_chat_text)
        cbNsfwMode = findViewById(R.id.cb_nsfw_mode)
        cbFullScreenMode = findViewById(R.id.cb_full_screen_mode)

        cardBadges = findViewById(R.id.card_badges)
        tvBadgesHeader = findViewById(R.id.tv_badges_header)
        tvBadgesDesc = findViewById(R.id.tv_badges_desc)
        badgeLegendBox = findViewById(R.id.badge_legend_box)
        tvBadgeLegendBody = findViewById(R.id.tv_badge_legend_body)

        cardQuota = findViewById(R.id.card_quota)
        tvQuotaHeader = findViewById(R.id.tv_quota_header)
        quotaStatsBox = findViewById(R.id.quota_stats_box)
        tvQuotaStats = findViewById(R.id.tv_quota_stats)
        tvQuotaTierNote = findViewById(R.id.tv_quota_tier_note)
        tvWhyApiTitle = findViewById(R.id.tv_why_api_title)
        tvApiKeyLabel = findViewById(R.id.tv_api_key_label)
        etOptionalApiKey = findViewById(R.id.et_optional_api_key)
        btnSaveSettings = findViewById(R.id.btn_save_settings)

        // Apply system bar & camera cutout insets so TopAppBar sits cleanly below the status bar!
        ViewCompat.setOnApplyWindowInsetsListener(rootScreenContainer) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topAppBar.setPadding(
                dpToPx(18),
                bars.top + dpToPx(12),
                dpToPx(14),
                dpToPx(12)
            )
            mainContentContainer.setPadding(
                dpToPx(18),
                dpToPx(14),
                dpToPx(18),
                bars.bottom + dpToPx(28)
            )
            insets
        }

        // Load saved preferences
        cbAutoReturn.isChecked = prefs.autoReturnToPreviousKeyboard
        cbAutoReadChat.isChecked = prefs.autoReadAndReplaceChatQuery
        if (!BuildConfig.ENABLE_NSFW) {
            cbNsfwMode.visibility = View.GONE
            prefs.nsfwModeEnabled = false
        } else {
            cbNsfwMode.visibility = View.VISIBLE
            cbNsfwMode.isChecked = prefs.nsfwModeEnabled
        }
        cbFullScreenMode.isChecked = prefs.isFullScreenMode
        etOptionalApiKey.setText(prefs.optionalApiKey)

        // Info IconButton -> Open Lagatron-style "About Keyboardy" Dialog
        btnAboutInfo.setOnClickListener {
            showAboutKeyboardyDialog()
        }

        // Light / Dark Mode IconButton (Sun in Dark Mode, Moon in Light Mode — matching Lagatron)
        btnToggleTheme.setOnClickListener {
            prefs.isDarkMode = !prefs.isDarkMode
            applyThemeAndState()
        }

        // Toggle Auxiliary Keyboard Setup card collapse/expand
        setupHeaderRow.setOnClickListener {
            setupCardManuallyExpanded = setupCollapsibleBody.visibility != View.VISIBLE
            updateImeStatusBanner()
        }

        // Open Android Keyboard Settings
        btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        // Keyboard Scale Selector Buttons
        val scaleMap = mapOf(
            KeyboardScale.COMPACT to btnScaleCompact,
            KeyboardScale.NORMAL to btnScaleNormal,
            KeyboardScale.LARGE to btnScaleLarge,
            KeyboardScale.XLARGE to btnScaleXLarge
        )
        for ((scaleOption, buttonView) in scaleMap) {
            buttonView.setOnClickListener {
                prefs.keyboardScale = scaleOption
                applyThemeAndState()
            }
        }

        // Bottom Navigation Clearance Stepper (− 4dp / + 4dp)
        btnClearanceMinus.setOnClickListener {
            prefs.bottomNavClearanceDp = (prefs.bottomNavClearanceDp - 4).coerceAtLeast(0)
            applyThemeAndState()
        }
        btnClearancePlus.setOnClickListener {
            prefs.bottomNavClearanceDp = (prefs.bottomNavClearanceDp + 4).coerceAtMost(160)
            applyThemeAndState()
        }

        // Thumbnail Scale & Grid Columns Buttons (1x, 2x, 3x, 4x)
        val gridColMap = mapOf(
            1 to btnGrid1Col,
            2 to btnGrid2Col,
            3 to btnGrid3Col,
            4 to btnGrid4Col
        )
        for ((cols, btnView) in gridColMap) {
            btnView.setOnClickListener {
                prefs.gridColumns = cols
                applyThemeAndState()
            }
        }

        // Configure Live Chat Sandbox EditText to accept rich images & GIFs via CommitContent API!
        val imageLoader = ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()

        ViewCompat.setOnReceiveContentListener(
            etChatSandbox,
            arrayOf("image/*", "image/png", "image/jpeg", "image/gif", "image/webp")
        ) { _, payload ->
            val clip = payload.clip
            if (clip.itemCount > 0) {
                val uri = clip.getItemAt(0).uri
                if (uri != null) {
                    sandboxReceivedContainer.visibility = View.VISIBLE
                    val mime = contentResolver.getType(uri) ?: "image/*"
                    tvSandboxReceivedInfo.text =
                        "Inserted directly via CommitContent!\nMIME: $mime"
                    val req = ImageRequest.Builder(this)
                        .data(uri)
                        .target(ivSandboxReceived)
                        .build()
                    imageLoader.enqueue(req)
                }
            }
            null
        }

        // Save Preferences
        cbAutoReturn.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoReturnToPreviousKeyboard = isChecked
        }
        cbAutoReadChat.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoReadAndReplaceChatQuery = isChecked
        }
        cbNsfwMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.nsfwModeEnabled = isChecked
        }
        cbFullScreenMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.isFullScreenMode = isChecked
        }
        btnSaveSettings.setOnClickListener {
            prefs.autoReturnToPreviousKeyboard = cbAutoReturn.isChecked
            prefs.autoReadAndReplaceChatQuery = cbAutoReadChat.isChecked
            prefs.nsfwModeEnabled = cbNsfwMode.isChecked
            prefs.isFullScreenMode = cbFullScreenMode.isChecked
            prefs.optionalApiKey = etOptionalApiKey.text?.toString().orEmpty()
            updateQuotaStatsUi()
            Toast.makeText(this, "Preferences saved!", Toast.LENGTH_SHORT).show()
        }

        applyThemeAndState()
    }

    override fun onResume() {
        super.onResume()
        updateImeStatusBanner()
        updateQuotaStatsUi()
    }

    override fun onDestroy() {
        prefs.unregisterListener(prefChangeListener)
        super.onDestroy()
    }

    private fun updateQuotaStatsUi() {
        val daySearches = prefs.searchesToday
        val monthSearches = prefs.searchesThisMonth
        val dayInserts = prefs.imagesInsertedToday
        val totalInserts = prefs.imagesInsertedTotal
        val hasApiKey = prefs.optionalApiKey.isNotBlank()

        tvQuotaStats.text =
            "Searches Today: $daySearches   •   This Month: $monthSearches\n" +
                "Images Inserted Today: $dayInserts   •   All-Time: $totalInserts (0 quota used)"

        tvQuotaTierNote.text = if (hasApiKey) {
            val isSerp = prefs.optionalApiKey.length >= 40 && prefs.optionalApiKey.all { it.isLetterOrDigit() }
            if (isSerp) {
                "Active Key: SerpApi ($monthSearches / 100 free monthly searches used) + Free Hybrid Fallback.\n" +
                    "Note: Quotas are counted strictly PER SEARCH QUERY, never per image tapped!"
            } else {
                "Active Key: Brave Search API ($monthSearches / 2,000 free monthly searches used) + Free Hybrid Fallback.\n" +
                    "Note: Quotas are counted strictly PER SEARCH QUERY, never per image tapped!"
            }
        } else {
            "Active Mode: Built-in Free Hybrid (G + ⚡) — Unlimited ($0)\n" +
                "(Benchmark vs. Google Custom Search 100/day free API tier: $daySearches / 100 today)\n" +
                "Note: Quotas are counted strictly PER SEARCH QUERY, never per image tapped! Tapping images/GIFs downloads directly from the host and uses 0 search quota."
        }
    }

    /**
     * Displays the "About Keyboardy" dialog matching Lagatron's exact AlertDialog layout & typography.
     */
    private fun showAboutKeyboardyDialog() {
        val palette = ThemePalette.forMode(prefs.isDarkMode)
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(22), dpToPx(20), dpToPx(22), dpToPx(16))
            background = palette.createRoundedRect(
                fillColor = palette.surface,
                cornerRadiusPx = 20f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
        }

        // Title Row: Info Icon + "About Keyboardy"
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val infoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_info)
            setColorFilter(palette.accentBlue)
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                marginEnd = dpToPx(8)
            }
        }
        val titleText = TextView(this).apply {
            text = "About Keyboardy"
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        titleRow.addView(infoIcon)
        titleRow.addView(titleText)
        container.addView(titleRow)

        val vName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.1-RC1.1"
        } catch (_: Exception) {
            "1.0.1-RC1.1"
        }

        val scrollArea = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Section Label: "About"
        val sectionLabel = TextView(this).apply {
            text = "About"
            setTextColor(palette.accentBlue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        }
        scrollContent.addView(sectionLabel)

        // Body Description
        val bodyText = TextView(this).apply {
            text = "Keyboardy is a zero-step auxiliary Google Image & GIF search keyboard engineered to eliminate manual downloading and file-picking when sharing pictures in Slack, Google Messages, WhatsApp, Discord, and other chat apps.\n\nSwitch from your default keyboard with one tap, search full-screen across our Hybrid Google WebView + Instant Web engine, tap any image or animated GIF to insert it directly into your conversation via Android's CommitContent API, and automatically jump right back to your default keyboard."
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dpToPx(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        }
        scrollContent.addView(bodyText)

        // Section Label: "Release Candidate 1 & What's New"
        val sectionRcLabel = TextView(this).apply {
            text = "Release Candidate 1 & What's New"
            setTextColor(palette.accentBlue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(14)
            }
        }
        scrollContent.addView(sectionRcLabel)

        val whatsNewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = palette.createRoundedRect(
                fillColor = palette.surfaceVariant,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        }

        val whatsNewText = TextView(this).apply {
            text = "• Release Candidate 1.1: Dual distribution & open-source prep\n" +
                   "• Compile-time Unfiltered Mode & Ad flags for Google Play compliance\n" +
                   "• Community support & GitHub Sponsor resource links\n" +
                   "• Solid boundary bar directly on top of the keyboard area\n" +
                   "• Instant Settings button (⚙️) directly in keyboard top bar\n" +
                   "• Transparent launcher app icon with smooth borders\n" +
                   "• Hybrid Google WebView + Instant Web engine with Tenor GIFs"
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setLineSpacing(dpToPx(3).toFloat(), 1f)
        }
        whatsNewBox.addView(whatsNewText)
        scrollContent.addView(whatsNewBox)

        // LINKS & RESOURCES SECTION
        val secLinksLabel = TextView(this).apply {
            text = "Links & Resources"
            setTextColor(palette.accentBlue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(14)
                bottomMargin = dpToPx(6)
            }
        }
        scrollContent.addView(secLinksLabel)

        fun createLinkBtn(title: String, url: String): TextView {
            return TextView(this).apply {
                text = title
                setTextColor(palette.accentBlue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpToPx(8), dpToPx(7), dpToPx(8), dpToPx(7))
                background = palette.createRoundedRect(
                    fillColor = palette.surfaceVariant,
                    cornerRadiusPx = 8f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = palette.border
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dpToPx(2), 0, dpToPx(2), 0)
                }
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (_: Exception) {}
                }
            }
        }

        val linksRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        linksRow1.addView(createLinkBtn("Changelog ↗", "https://github.com/medusko/Keyboardy/blob/main/CHANGELOG.md"))
        linksRow1.addView(createLinkBtn("Releases ↗", "https://github.com/medusko/Keyboardy/releases"))
        scrollContent.addView(linksRow1)

        val linksRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
            }
        }
        linksRow2.addView(createLinkBtn("Source Code ↗", "https://github.com/medusko/Keyboardy"))
        linksRow2.addView(createLinkBtn("Sponsor ☕ ↗", "https://github.com/sponsors/Zlatty"))
        scrollContent.addView(linksRow2)

        // Contact & Social Information Card
        val contactCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = palette.createRoundedRect(
                fillColor = palette.surfaceVariant,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }

        fun createContactRow(label: String, value: String, url: String?): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(2)
                    bottomMargin = dpToPx(2)
                }
                addView(TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(palette.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(dpToPx(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(this@MainActivity).apply {
                    text = value
                    setTextColor(if (url != null) palette.accentBlue else palette.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    if (url != null) {
                        typeface = Typeface.DEFAULT_BOLD
                        setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            } catch (_: Exception) {}
                        }
                    }
                })
            }
        }

        contactCard.addView(createContactRow("Website:", "medusko.com", "https://medusko.com"))
        contactCard.addView(createContactRow("Support:", "support@medusko.com", "mailto:support@medusko.com"))
        contactCard.addView(createContactRow("Social:", "@meduskodev on Twitter", "https://twitter.com/meduskodev"))
        scrollContent.addView(contactCard)

        // Trademark & Legal Disclaimer
        val disclaimerText = TextView(this).apply {
            text = "Google, Android, Gboard, Google Messages, and Google Images are trademarks of Google LLC. Tenor is a trademark of Tenor, Inc. / Google LLC. Slack is a trademark of Slack Technologies, LLC / Salesforce, Inc. WhatsApp is a trademark of Meta Platforms, Inc. Discord is a trademark of Discord Inc. Keyboardy is an independent product developed by Medusko and is not affiliated with, endorsed by, or sponsored by Google LLC, Tenor, Slack, Meta, Discord, or any other referenced company. All trademarks and registered trademarks are the property of their respective owners."
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(dpToPx(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }
        scrollContent.addView(disclaimerText)

        // Version Footer
        val footerText = TextView(this).apply {
            text = "Keyboardy v$vName (Build 9) • Release Candidate 1.1 • Built for Android 15 / Pixel\nDesigned & Engineered by Medusko"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(14)
                bottomMargin = dpToPx(4)
            }
        }
        scrollContent.addView(footerText)

        scrollArea.addView(scrollContent)
        container.addView(scrollArea)

        // Close Button Row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }
        val closeBtn = TextView(this).apply {
            text = "Close"
            setTextColor(palette.accentBlue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            setOnClickListener {
                dialog.dismiss()
            }
        }
        buttonRow.addView(closeBtn)
        container.addView(buttonRow)

        dialog.setContentView(container)
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun applyThemeAndState() {
        val isDark = prefs.isDarkMode
        val palette = ThemePalette.forMode(isDark)
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        // Status & Navigation Bar Colors
        @Suppress("DEPRECATION")
        window.statusBarColor = palette.surface
        @Suppress("DEPRECATION")
        window.navigationBarColor = palette.background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        rootScreenContainer.setBackgroundColor(palette.background)
        topAppBar.setBackgroundColor(palette.surface)
        topBarDivider.setBackgroundColor(palette.border)
        rootScrollView.setBackgroundColor(palette.background)

        tvAppTitle.setTextColor(palette.textPrimary)

        // Info Icon & Light/Dark Theme Icon matching Lagatron
        btnAboutInfo.setColorFilter(palette.accentBlue)
        btnToggleTheme.setImageResource(
            if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        )
        btnToggleTheme.setColorFilter(palette.accentBlue)

        // Section Headers (Enablement, Customization, Help)
        for (sec in listOf(tvSectionEnablement, tvSectionCustomization, tvSectionHelp)) {
            sec.setTextColor(palette.accentBlue)
        }

        // Style all Cards
        val cards = listOf(cardSetup, cardScale, cardGridScale, cardSandbox, cardAppIcon, cardSettings, cardBadges, cardQuota)
        for (card in cards) {
            card.background = palette.createRoundedRect(
                fillColor = palette.surface,
                cornerRadiusPx = 16f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
        }

        // Card Headers & Descriptions
        val headers = listOf(
            tvImeStatus,
            tvScaleHeader,
            tvGridScaleHeader,
            tvSandboxHeader,
            tvAppIconHeader,
            tvSettingsHeader,
            tvBadgesHeader,
            tvQuotaHeader,
            tvWhyApiTitle
        )
        for (h in headers) {
            h.setTextColor(palette.accentBlue)
        }
        val descriptions = listOf(tvSetupDesc, tvScaleDesc, tvGridScaleDesc, tvSandboxDesc, tvAppIconDesc, tvBadgesDesc, tvApiKeyLabel)
        for (d in descriptions) {
            d.setTextColor(palette.textSecondary)
        }
        tvSetupChevron.setTextColor(palette.textSecondary)

        btnEnableIme.backgroundTintList = ColorStateList.valueOf(palette.accentBlue)
        btnEnableIme.setTextColor(Color.WHITE)

        // Scale Option Buttons
        val scaleButtons = mapOf(
            KeyboardScale.COMPACT to btnScaleCompact,
            KeyboardScale.NORMAL to btnScaleNormal,
            KeyboardScale.LARGE to btnScaleLarge,
            KeyboardScale.XLARGE to btnScaleXLarge
        )
        for ((scaleOpt, btn) in scaleButtons) {
            val selected = prefs.keyboardScale == scaleOpt
            btn.background = palette.createRoundedRect(
                fillColor = if (selected) palette.accentBlue else palette.surfaceVariant,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = if (selected) 0 else strokePx,
                strokeColor = palette.border
            )
            btn.setTextColor(if (selected) Color.WHITE else palette.textPrimary)
        }

        // Thumbnail & Grid Column Buttons (1x, 2x, 3x, 4x)
        val gridColButtons = mapOf(
            1 to btnGrid1Col,
            2 to btnGrid2Col,
            3 to btnGrid3Col,
            4 to btnGrid4Col
        )
        for ((cols, btn) in gridColButtons) {
            val selected = prefs.gridColumns == cols
            btn.background = palette.createRoundedRect(
                fillColor = if (selected) palette.accentBlue else palette.surfaceVariant,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = if (selected) 0 else strokePx,
                strokeColor = palette.border
            )
            btn.setTextColor(if (selected) Color.WHITE else palette.textPrimary)
        }

        // Bottom Clearance Stepper
        tvBottomClearanceLabel.text =
            "Bottom Bar Offset: ${prefs.bottomNavClearanceDp}dp"
        tvBottomClearanceLabel.setTextColor(palette.textPrimary)

        for (stepBtn in listOf(btnClearanceMinus, btnClearancePlus)) {
            stepBtn.background = palette.createRoundedRect(
                fillColor = palette.surfaceVariant,
                cornerRadiusPx = 8f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
            stepBtn.setTextColor(palette.textPrimary)
        }

        // Sandbox Input & Preview
        sandboxReceivedContainer.background = palette.createRoundedRect(
            fillColor = palette.surfaceVariant,
            cornerRadiusPx = 10f * density,
            strokeWidthPx = strokePx,
            strokeColor = palette.border
        )
        tvSandboxReceivedInfo.setTextColor(palette.successGreen)

        for (edit in listOf(etChatSandbox, etOptionalApiKey)) {
            edit.background = palette.createRoundedRect(
                fillColor = palette.surfaceVariant,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
            edit.setTextColor(palette.textPrimary)
            edit.setHintTextColor(palette.textSecondary)
        }

        // Badge Legend Box & Quota Stats Box
        for (innerBox in listOf(badgeLegendBox, quotaStatsBox)) {
            innerBox.background = palette.createRoundedRect(
                fillColor = palette.surfaceVariant,
                cornerRadiusPx = 12f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
        }
        tvBadgeLegendBody.setTextColor(palette.textPrimary)
        tvQuotaStats.setTextColor(palette.textPrimary)
        tvQuotaTierNote.setTextColor(palette.textSecondary)

        // Checkboxes
        for (cb in listOf(cbAutoReturn, cbAutoReadChat, cbNsfwMode, cbFullScreenMode)) {
            cb.setTextColor(palette.textPrimary)
            cb.buttonTintList = ColorStateList.valueOf(palette.accentBlue)
        }

        btnSaveSettings.backgroundTintList = ColorStateList.valueOf(palette.accentBlue)
        btnSaveSettings.setTextColor(Color.WHITE)

        updateImeStatusBanner()
        updateQuotaStatsUi()
        updateAppIconUi()
    }

    private fun setAppIconStyle(useClassic: Boolean) {
        if (prefs.useClassicIcon == useClassic) return
        prefs.useClassicIcon = useClassic

        val pm = packageManager
        val defaultAlias = ComponentName(this, "${packageName}.MainActivityDefault")
        val classicAlias = ComponentName(this, "${packageName}.MainActivityClassic")

        val (enableComp, disableComp) = if (useClassic) {
            classicAlias to defaultAlias
        } else {
            defaultAlias to classicAlias
        }

        try {
            pm.setComponentEnabledSetting(
                enableComp,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                disableComp,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            val iconName = if (useClassic) "Classic (Original)" else "Gold (RC2 Default)"
            Toast.makeText(this, "Launcher icon updated to $iconName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("Keyboardy", "Failed to switch app icon: ${e.message}")
        }

        updateAppIconUi()
    }

    private fun updateAppIconUi() {
        val palette = ThemePalette.forMode(prefs.isDarkMode)
        val density = resources.displayMetrics.density
        val strokePx = (1.5f * density).toInt().coerceAtLeast(1)
        val useClassic = prefs.useClassicIcon

        val goldSelected = !useClassic
        btnIconGold.background = palette.createRoundedRect(
            fillColor = if (goldSelected) palette.surfaceVariant else palette.surface,
            cornerRadiusPx = 12f * density,
            strokeWidthPx = if (goldSelected) strokePx + 1 else strokePx,
            strokeColor = if (goldSelected) palette.accentBlue else palette.border
        )
        tvIconGoldTitle.setTextColor(if (goldSelected) palette.accentBlue else palette.textPrimary)
        tvIconGoldBadge.setTextColor(if (goldSelected) palette.accentBlue else palette.textSecondary)

        val classicSelected = useClassic
        btnIconClassic.background = palette.createRoundedRect(
            fillColor = if (classicSelected) palette.surfaceVariant else palette.surface,
            cornerRadiusPx = 12f * density,
            strokeWidthPx = if (classicSelected) strokePx + 1 else strokePx,
            strokeColor = if (classicSelected) palette.accentBlue else palette.border
        )
        tvIconClassicTitle.setTextColor(if (classicSelected) palette.accentBlue else palette.textPrimary)
        tvIconClassicBadge.setTextColor(if (classicSelected) palette.accentBlue else palette.textSecondary)
    }

    private fun updateImeStatusBanner() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val isEnabled = imm?.enabledInputMethodList?.any {
            it.packageName == packageName
        } == true

        val palette = ThemePalette.forMode(prefs.isDarkMode)
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        val pillColor = if (isEnabled) palette.successGreen else Color.parseColor("#FF1744")
        val pillBgAlpha = Color.argb(38, Color.red(pillColor), Color.green(pillColor), Color.blue(pillColor))

        statusPillContainer.background = palette.createRoundedRect(
            fillColor = pillBgAlpha,
            cornerRadiusPx = 20f * density,
            strokeWidthPx = strokePx,
            strokeColor = pillColor
        )
        statusPillDot.background = palette.createRoundedRect(
            fillColor = pillColor,
            cornerRadiusPx = 10f * density
        )
        tvStatusPillText.text = if (isEnabled) "ENABLED" else "SETUP NEEDED"
        tvStatusPillText.setTextColor(pillColor)

        val shouldExpand = !isEnabled || setupCardManuallyExpanded
        setupCollapsibleBody.visibility = if (shouldExpand) View.VISIBLE else View.GONE
        tvSetupChevron.text = if (shouldExpand) "▲" else "▼"

        tvImeStatus.text = if (isEnabled) {
            "Auxiliary Keyboard Enabled & Ready"
        } else {
            "Enable Keyboardy in Android Settings (Keep your default keyboard as your main keyboard)"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
