package com.medusko.keyboardy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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

    private lateinit var rootScrollView: ScrollView
    private lateinit var mainContentContainer: LinearLayout
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var btnToggleTheme: Button

    private lateinit var cardSetup: LinearLayout
    private lateinit var tvImeStatus: TextView
    private lateinit var tvSetupDesc: TextView
    private lateinit var btnEnableIme: Button
    private lateinit var btnSwitchIme: Button

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
    private lateinit var tvApiKeyLabel: TextView
    private lateinit var etOptionalApiKey: EditText
    private lateinit var btnSaveSettings: Button

    private lateinit var cardIcons: LinearLayout
    private lateinit var tvIconsHeader: TextView
    private lateinit var tvIconsDesc: TextView
    private lateinit var iconGridContainer: LinearLayout

    private data class IconOption(
        val index: Int,
        val title: String,
        val drawableRes: Int,
        val aliasClassName: String
    )

    private val iconOptions = listOf(
        IconOption(1, "1. Neon Cyber", R.drawable.ic_app_option_1, "com.medusko.keyboardy.IconAlias1"),
        IconOption(2, "2. Magnifying Keys", R.drawable.ic_app_option_2, "com.medusko.keyboardy.IconAlias2"),
        IconOption(3, "3. Portal Spark", R.drawable.ic_app_option_3, "com.medusko.keyboardy.IconAlias3"),
        IconOption(4, "4. Minimal Grid", R.drawable.ic_app_option_4, "com.medusko.keyboardy.IconAlias4"),
        IconOption(5, "5. Pixel Bolt", R.drawable.ic_app_option_5, "com.medusko.keyboardy.IconAlias5"),
        IconOption(6, "6. Retro Cream", R.drawable.ic_app_option_6, "com.medusko.keyboardy.IconAlias6")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = KeyboardyPrefs(this)

        rootScrollView = findViewById(R.id.root_scroll_view)
        mainContentContainer = findViewById(R.id.main_content_container)
        tvAppTitle = findViewById(R.id.tv_app_title)
        tvAppSubtitle = findViewById(R.id.tv_app_subtitle)
        btnToggleTheme = findViewById(R.id.btn_toggle_theme)

        cardSetup = findViewById(R.id.card_setup)
        tvImeStatus = findViewById(R.id.tv_ime_status)
        tvSetupDesc = findViewById(R.id.tv_setup_desc)
        btnEnableIme = findViewById(R.id.btn_enable_ime)
        btnSwitchIme = findViewById(R.id.btn_switch_ime)

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

        cardSandbox = findViewById(R.id.card_sandbox)
        tvSandboxHeader = findViewById(R.id.tv_sandbox_header)
        tvSandboxDesc = findViewById(R.id.tv_sandbox_desc)
        sandboxReceivedContainer = findViewById(R.id.sandbox_received_container)
        ivSandboxReceived = findViewById(R.id.iv_sandbox_received)
        tvSandboxReceivedInfo = findViewById(R.id.tv_sandbox_received_info)
        etChatSandbox = findViewById(R.id.et_chat_sandbox)

        cardSettings = findViewById(R.id.card_settings)
        tvSettingsHeader = findViewById(R.id.tv_settings_header)
        cbAutoReturn = findViewById(R.id.cb_auto_return_gboard)
        cbAutoReadChat = findViewById(R.id.cb_auto_read_chat_text)
        tvApiKeyLabel = findViewById(R.id.tv_api_key_label)
        etOptionalApiKey = findViewById(R.id.et_optional_api_key)
        btnSaveSettings = findViewById(R.id.btn_save_settings)

        cardIcons = findViewById(R.id.card_icons)
        tvIconsHeader = findViewById(R.id.tv_icons_header)
        tvIconsDesc = findViewById(R.id.tv_icons_desc)
        iconGridContainer = findViewById(R.id.icon_grid_container)

        // Apply system bar & camera cutout insets so content always sits below the status bar!
        ViewCompat.setOnApplyWindowInsetsListener(mainContentContainer) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val horizontalPx = dpToPx(20)
            val topPx = bars.top + dpToPx(18)
            val bottomPx = bars.bottom + dpToPx(28)
            view.setPadding(horizontalPx, topPx, horizontalPx, bottomPx)
            insets
        }

        // Load saved preferences
        cbAutoReturn.isChecked = prefs.autoReturnToPreviousKeyboard
        cbAutoReadChat.isChecked = prefs.autoReadAndReplaceChatQuery
        etOptionalApiKey.setText(prefs.optionalApiKey)

        // Light / Dark Mode Toggle (Lagatron style)
        btnToggleTheme.setOnClickListener {
            prefs.isDarkMode = !prefs.isDarkMode
            applyThemeAndState()
        }

        // 1. Open Android Keyboard Settings
        btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        // 2. Show System Keyboard Switcher Dialog
        btnSwitchIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        // 3. Keyboard Scale Selector Buttons
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

        // Bottom Navigation Clearance Stepper
        btnClearanceMinus.setOnClickListener {
            prefs.bottomNavClearanceDp = (prefs.bottomNavClearanceDp - 6).coerceAtLeast(0)
            applyThemeAndState()
        }
        btnClearancePlus.setOnClickListener {
            prefs.bottomNavClearanceDp = (prefs.bottomNavClearanceDp + 6).coerceAtMost(100)
            applyThemeAndState()
        }

        // 4. Configure Live Chat Sandbox EditText to accept rich images & GIFs via CommitContent API!
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

        // 5. Save Preferences
        cbAutoReturn.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoReturnToPreviousKeyboard = isChecked
        }
        cbAutoReadChat.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoReadAndReplaceChatQuery = isChecked
        }
        btnSaveSettings.setOnClickListener {
            prefs.autoReturnToPreviousKeyboard = cbAutoReturn.isChecked
            prefs.autoReadAndReplaceChatQuery = cbAutoReadChat.isChecked
            prefs.optionalApiKey = etOptionalApiKey.text?.toString().orEmpty()
            Toast.makeText(this, "Preferences saved!", Toast.LENGTH_SHORT).show()
        }

        applyThemeAndState()
    }

    override fun onResume() {
        super.onResume()
        updateImeStatusBanner()
    }

    private fun applyThemeAndState() {
        val isDark = prefs.isDarkMode
        val palette = ThemePalette.forMode(isDark)
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        // Status & Navigation Bar Colors
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        rootScrollView.setBackgroundColor(palette.background)
        tvAppTitle.setTextColor(palette.textPrimary)
        tvAppSubtitle.setTextColor(palette.textSecondary)

        btnToggleTheme.apply {
            text = if (isDark) "☀️ Light" else "🌙 Dark"
            backgroundTintList = ColorStateList.valueOf(palette.accentBlue)
            setTextColor(Color.WHITE)
        }

        // Style all 5 Cards
        val cards = listOf(cardSetup, cardScale, cardSandbox, cardSettings, cardIcons)
        for (card in cards) {
            card.background = palette.createRoundedRect(
                fillColor = palette.surface,
                cornerRadiusPx = 16f * density,
                strokeWidthPx = strokePx,
                strokeColor = palette.border
            )
        }

        // Headers & Descriptions
        val headers = listOf(tvImeStatus, tvScaleHeader, tvSandboxHeader, tvSettingsHeader, tvIconsHeader)
        for (h in headers) {
            h.setTextColor(palette.accentBlue)
        }
        val descriptions = listOf(tvSetupDesc, tvScaleDesc, tvSandboxDesc, tvApiKeyLabel, tvIconsDesc)
        for (d in descriptions) {
            d.setTextColor(palette.textSecondary)
        }

        btnEnableIme.backgroundTintList = ColorStateList.valueOf(palette.accentBlue)
        btnEnableIme.setTextColor(Color.WHITE)
        btnSwitchIme.backgroundTintList = ColorStateList.valueOf(palette.surfaceVariant)
        btnSwitchIme.setTextColor(palette.textPrimary)

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

        // Bottom Clearance Stepper
        tvBottomClearanceLabel.text =
            "Bottom Bar Offset: ${prefs.bottomNavClearanceDp}dp (Clears nav bar & switcher)"
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

        // Checkboxes
        for (cb in listOf(cbAutoReturn, cbAutoReadChat)) {
            cb.setTextColor(palette.textPrimary)
            cb.buttonTintList = ColorStateList.valueOf(palette.accentBlue)
        }

        btnSaveSettings.backgroundTintList = ColorStateList.valueOf(palette.accentBlue)
        btnSaveSettings.setTextColor(Color.WHITE)

        // Build 6 App Icon Options Grid
        buildIconSelectorGrid(palette)
    }

    private fun buildIconSelectorGrid(palette: ThemePalette) {
        iconGridContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val strokePx = (1.5f * density).toInt().coerceAtLeast(1)
        val selectedIdx = prefs.selectedIconOption

        val rows = iconOptions.chunked(3)
        for (rowItems in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(8)
                }
            }

            for (opt in rowItems) {
                val isSelected = opt.index == selectedIdx
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10))
                    background = palette.createRoundedRect(
                        fillColor = if (isSelected) palette.surfaceVariant else palette.background,
                        cornerRadiusPx = 12f * density,
                        strokeWidthPx = if (isSelected) strokePx * 2 else strokePx,
                        strokeColor = if (isSelected) palette.accentBlue else palette.border
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginStart = dpToPx(4)
                        marginEnd = dpToPx(4)
                    }
                    setOnClickListener {
                        applyLauncherIconOption(opt)
                    }
                }

                val iv = ImageView(this).apply {
                    setImageResource(opt.drawableRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dpToPx(62), dpToPx(62))
                }

                val tv = TextView(this).apply {
                    text = if (isSelected) "✓ ${opt.title}" else opt.title
                    gravity = Gravity.CENTER
                    setTextColor(if (isSelected) palette.accentBlue else palette.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(6)
                    }
                }

                card.addView(iv)
                card.addView(tv)
                rowLayout.addView(card)
            }
            iconGridContainer.addView(rowLayout)
        }
    }

    private fun applyLauncherIconOption(selected: IconOption) {
        prefs.selectedIconOption = selected.index
        applyThemeAndState()
        try {
            val pm = packageManager
            for (opt in iconOptions) {
                val state = if (opt.index == selected.index) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    ComponentName(packageName, opt.aliasClassName),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            }
            Toast.makeText(
                this,
                "App icon switched to ${selected.title}!",
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
        }
    }

    private fun updateImeStatusBanner() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val isEnabled = imm?.enabledInputMethodList?.any {
            it.packageName == packageName
        } == true

        tvImeStatus.text = if (isEnabled) {
            "Step 1: Keyboardy is ENABLED! Tap '2. Switch Keyboard' or use the keyboard icon in any chat."
        } else {
            "Step 1: Enable Keyboardy in Android Settings (Keep Gboard as your main keyboard)"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
