package com.medusko.keyboardy.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatEditText
import kotlin.math.max
import kotlin.math.min

/**
 * Custom EditText engineered specifically for use inside an Android InputMethodService window.
 * Supports:
 * 1. Blinking cursor & character-accurate tap placement.
 * 2. Drag-to-select, double-tap word selection, and long-press to invoke Copy/Cut/Paste/Select All.
 */
class ImeSearchEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var cursorStart: Int = 0
    private var cursorEnd: Int = 0
    private var cursorBlinkOn: Boolean = true
    private var showCursor: Boolean = true
    private var dragAnchorOffset: Int = 0

    var onSearchBarTapped: (() -> Unit)? = null
    var onQueryTextChanged: ((String) -> Unit)? = null
    var onSelectionOrLongPressAction: ((hasSelection: Boolean, hasText: Boolean) -> Unit)? = null
    var onDismissSelectionMenu: (() -> Unit)? = null

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E6BD9")
        style = Paint.Style.FILL
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#552E6BD9")
        style = Paint.Style.FILL
    }

    private val selectionRect = RectF()
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorBlinkOn = !cursorBlinkOn
            invalidate()
            blinkHandler.postDelayed(this, 500L)
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val txt = text?.toString().orEmpty()
                if (txt.isNotEmpty()) {
                    selectWordAt(e.x)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onSelectionOrLongPressAction?.invoke(hasSelection(), true)
                } else {
                    onSelectionOrLongPressAction?.invoke(false, false)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val txt = text?.toString().orEmpty()
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (txt.isNotEmpty() && !hasSelection()) {
                    selectWordAt(e.x)
                }
                onSelectionOrLongPressAction?.invoke(hasSelection(), txt.isNotEmpty())
            }
        }
    )

    init {
        showSoftInputOnFocus = false
        isCursorVisible = false
        setBackgroundColor(Color.TRANSPARENT)
        setSingleLine(true)
    }

    private fun selectWordAt(touchX: Float) {
        val txt = text?.toString().orEmpty()
        if (txt.isEmpty()) return
        val offset = resolveOffset(touchX).coerceIn(0, txt.length)
        var start = offset
        var end = offset
        while (start > 0 && !txt[start - 1].isWhitespace()) start--
        while (end < txt.length && !txt[end].isWhitespace()) end++
        if (start == end) {
            start = 0
            end = txt.length
        }
        setCursorSelection(start, end)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        blinkHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    fun setAccentColor(accentColor: Int) {
        cursorPaint.color = accentColor
        selectionPaint.color = Color.argb(
            85,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        invalidate()
    }

    fun setCursorActive(active: Boolean) {
        showCursor = active
        if (active) {
            restartBlink()
        } else {
            blinkHandler.removeCallbacksAndMessages(null)
            invalidate()
        }
    }

    override fun hasSelection(): Boolean = cursorStart != cursorEnd

    fun selectAllText() {
        val len = text?.length ?: 0
        setCursorSelection(0, len)
        onSelectionOrLongPressAction?.invoke(len > 0, len > 0)
    }

    fun getSelectedOrAllText(): String {
        val current = text?.toString().orEmpty()
        if (current.isEmpty()) return ""
        val s = min(cursorStart, cursorEnd).coerceIn(0, current.length)
        val e = max(cursorStart, cursorEnd).coerceIn(0, current.length)
        return if (s != e) current.substring(s, e) else current
    }

    fun cutSelectedOrAllText(): String {
        val current = text?.toString().orEmpty()
        if (current.isEmpty()) return ""
        val s = min(cursorStart, cursorEnd).coerceIn(0, current.length)
        val e = max(cursorStart, cursorEnd).coerceIn(0, current.length)
        return if (s != e) {
            val cut = current.substring(s, e)
            val updated = current.substring(0, s) + current.substring(e)
            super.setText(updated)
            cursorStart = s
            cursorEnd = s
            restartBlink()
            onQueryTextChanged?.invoke(updated)
            cut
        } else {
            setQueryText("", placeCursorAtEnd = true)
            current
        }
    }

    fun setQueryText(newText: String, placeCursorAtEnd: Boolean = true) {
        super.setText(newText)
        val len = newText.length
        if (placeCursorAtEnd) {
            cursorStart = len
            cursorEnd = len
        } else {
            cursorStart = cursorStart.coerceIn(0, len)
            cursorEnd = cursorEnd.coerceIn(0, len)
        }
        onDismissSelectionMenu?.invoke()
        restartBlink()
        onQueryTextChanged?.invoke(newText)
    }

    fun getQueryText(): String = text?.toString().orEmpty()

    fun setCursorSelection(start: Int, end: Int = start) {
        val len = text?.length ?: 0
        cursorStart = min(start, end).coerceIn(0, len)
        cursorEnd = max(start, end).coerceIn(0, len)
        restartBlink()
    }

    fun insertTextAtCursor(str: String) {
        val current = text?.toString().orEmpty()
        val s = min(cursorStart, cursorEnd).coerceIn(0, current.length)
        val e = max(cursorStart, cursorEnd).coerceIn(0, current.length)
        val updated = current.substring(0, s) + str + current.substring(e)
        super.setText(updated)
        val newPos = (s + str.length).coerceIn(0, updated.length)
        cursorStart = newPos
        cursorEnd = newPos
        onDismissSelectionMenu?.invoke()
        restartBlink()
        onQueryTextChanged?.invoke(updated)
    }

    fun backspaceAtCursor(): Boolean {
        val current = text?.toString().orEmpty()
        if (current.isEmpty()) return false
        val s = min(cursorStart, cursorEnd).coerceIn(0, current.length)
        val e = max(cursorStart, cursorEnd).coerceIn(0, current.length)

        val updated: String
        val newPos: Int
        if (s != e) {
            updated = current.substring(0, s) + current.substring(e)
            newPos = s
        } else if (s > 0) {
            updated = current.substring(0, s - 1) + current.substring(s)
            newPos = s - 1
        } else {
            return false
        }

        super.setText(updated)
        cursorStart = newPos.coerceIn(0, updated.length)
        cursorEnd = cursorStart
        onDismissSelectionMenu?.invoke()
        restartBlink()
        onQueryTextChanged?.invoke(updated)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handledByGesture = gestureDetector.onTouchEvent(event)
        if (handledByGesture) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onSearchBarTapped?.invoke()
                showCursor = true
                val offset = resolveOffset(event.x)
                dragAnchorOffset = offset
                setCursorSelection(offset, offset)
                onDismissSelectionMenu?.invoke()
            }

            MotionEvent.ACTION_MOVE -> {
                val offset = resolveOffset(event.x)
                if (offset != dragAnchorOffset) {
                    setCursorSelection(dragAnchorOffset, offset)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (hasSelection()) {
                    onSelectionOrLongPressAction?.invoke(true, text?.isNotEmpty() == true)
                }
            }
        }
        return true
    }

    private fun resolveOffset(touchX: Float): Int {
        val txt = text?.toString().orEmpty()
        if (txt.isEmpty()) return 0
        val currentLayout = layout ?: return txt.length
        val localX = (touchX - totalPaddingLeft + scrollX).coerceAtLeast(0f)
        return currentLayout.getOffsetForHorizontal(0, localX).coerceIn(0, txt.length)
    }

    private fun restartBlink() {
        cursorBlinkOn = true
        blinkHandler.removeCallbacksAndMessages(null)
        if (showCursor) {
            blinkHandler.postDelayed(blinkRunnable, 500L)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val txt = text?.toString().orEmpty()
        val currentLayout = layout
        val topY = totalPaddingTop.toFloat()
        val bottomY = (height - totalPaddingBottom).toFloat()

        // 1. Draw Selection Highlight if a range is selected
        if (showCursor && currentLayout != null && cursorStart != cursorEnd && txt.isNotEmpty()) {
            val s = min(cursorStart, cursorEnd).coerceIn(0, txt.length)
            val e = max(cursorStart, cursorEnd).coerceIn(0, txt.length)
            val startX = totalPaddingLeft + currentLayout.getPrimaryHorizontal(s)
            val endX = totalPaddingLeft + currentLayout.getPrimaryHorizontal(e)
            selectionRect.set(
                min(startX, endX),
                topY + dpToPx(2f),
                max(startX, endX),
                bottomY - dpToPx(2f)
            )
            canvas.drawRoundRect(selectionRect, dpToPx(4f), dpToPx(4f), selectionPaint)
        }

        super.onDraw(canvas)

        // 2. Draw Blinking Cursor Bar when cursorStart == cursorEnd
        if (showCursor && cursorBlinkOn && cursorStart == cursorEnd) {
            val pos = cursorStart.coerceIn(0, txt.length)
            val cursorX = if (currentLayout != null && txt.isNotEmpty()) {
                totalPaddingLeft + currentLayout.getPrimaryHorizontal(pos)
            } else {
                totalPaddingLeft.toFloat()
            }
            val barWidth = dpToPx(2.2f)
            canvas.drawRoundRect(
                cursorX,
                topY + dpToPx(3f),
                cursorX + barWidth,
                bottomY - dpToPx(3f),
                barWidth / 2f,
                barWidth / 2f,
                cursorPaint
            )
        }
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
