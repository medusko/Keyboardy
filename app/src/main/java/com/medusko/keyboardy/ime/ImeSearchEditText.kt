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
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatEditText
import kotlin.math.max
import kotlin.math.min

/**
 * Custom EditText engineered specifically for use inside an Android InputMethodService window.
 * Because IME windows carry FLAG_NOT_FOCUSABLE (so the host chat app keeps window focus),
 * standard EditTexts do not render a blinking cursor or selection highlight inside an IME.
 *
 * ImeSearchEditText solves this by:
 * 1. Resolving exact character offsets on touch/drag/double-tap via [getOffsetForPosition].
 * 2. Rendering its own crisp blinking cursor bar and text selection highlight in [onDraw].
 * 3. Providing helper methods to insert/replace/delete text at the active cursor or selection.
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
                    val offset = resolveOffset(e.x).coerceIn(0, txt.length)
                    // Select the word around the double-tapped character
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
                return true
            }
        }
    )

    init {
        showSoftInputOnFocus = false
        isCursorVisible = false // We draw our own crisp blinking cursor in onDraw
        setBackgroundColor(Color.TRANSPARENT)
        setSingleLine(true)
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
            // Delete selected range
            updated = current.substring(0, s) + current.substring(e)
            newPos = s
        } else if (s > 0) {
            // Delete single character before cursor
            updated = current.substring(0, s - 1) + current.substring(s)
            newPos = s - 1
        } else {
            return false
        }

        super.setText(updated)
        cursorStart = newPos.coerceIn(0, updated.length)
        cursorEnd = cursorStart
        restartBlink()
        onQueryTextChanged?.invoke(updated)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onSearchBarTapped?.invoke()
                showCursor = true
                val offset = resolveOffset(event.x)
                dragAnchorOffset = offset
                setCursorSelection(offset, offset)
            }

            MotionEvent.ACTION_MOVE -> {
                val offset = resolveOffset(event.x)
                if (offset != dragAnchorOffset) {
                    setCursorSelection(dragAnchorOffset, offset)
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
            canvas.drawRoundRect(selectionRect, dpToPx(4f), dpToPx(4f), selectionRect.let { selectionPaint })
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
