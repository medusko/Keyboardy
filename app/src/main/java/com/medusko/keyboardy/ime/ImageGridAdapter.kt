package com.medusko.keyboardy.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import com.medusko.keyboardy.R
import com.medusko.keyboardy.search.ImageResult
import com.medusko.keyboardy.search.SearchParsers
import com.medusko.keyboardy.theme.ThemePalette

class ImageGridAdapter(
    context: Context,
    private val onImageClicked: (ImageResult) -> Unit
) : RecyclerView.Adapter<ImageGridAdapter.ImageViewHolder>() {

    private val items = mutableListOf<ImageResult>()
    private var loadingItemId: String? = null
    private var itemHeightPx: Int = (120 * context.resources.displayMetrics.density).toInt()
    private var palette: ThemePalette = ThemePalette.DARK
    private val imageLoader = context.applicationContext.imageLoader

    fun updateStyle(newHeightPx: Int, newPalette: ThemePalette) {
        val changed = itemHeightPx != newHeightPx || palette.isDark != newPalette.isDark
        itemHeightPx = newHeightPx
        palette = newPalette
        if (changed && items.isNotEmpty()) {
            notifyDataSetChanged()
        }
    }

    fun submitList(newItems: List<ImageResult>) {
        val oldList = items.toList()
        val prevLoadingId = loadingItemId
        loadingItemId = null
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].fullImageUrl == newItems[newItemPosition].fullImageUrl &&
                    oldList[oldItemPosition].isTransparent == newItems[newItemPosition].isTransparent
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldList[oldItemPosition]
                val newItem = newItems[newItemPosition]
                return oldItem == newItem && oldItem.id != prevLoadingId
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun setLoadingItem(id: String?) {
        val previousId = loadingItemId
        loadingItemId = id
        if (previousId != null) {
            val prevIdx = items.indexOfFirst { it.id == previousId }
            if (prevIdx != -1) notifyItemChanged(prevIdx)
        }
        if (id != null) {
            val newIdx = items.indexOfFirst { it.id == id }
            if (newIdx != -1) notifyItemChanged(newIdx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_result, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item.id == loadingItemId)
    }

    override fun getItemCount(): Int = items.size

    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.iv_thumbnail)
        private val tvBadgeGif: TextView = itemView.findViewById(R.id.tv_badge_gif)
        private val tvBadgeSource: TextView = itemView.findViewById(R.id.tv_badge_source)
        private val overlayLoading: FrameLayout = itemView.findViewById(R.id.overlay_loading)

        fun bind(item: ImageResult, isLoading: Boolean) {
            val lp = itemView.layoutParams
            if (lp != null && lp.height != itemHeightPx) {
                lp.height = itemHeightPx
                itemView.layoutParams = lp
            }
            val density = itemView.resources.displayMetrics.density
            itemView.background = if (item.isTransparent) {
                TransparencyCheckerboardDrawable(
                    isDark = palette.isDark,
                    cornerRadiusPx = 8f * density,
                    strokeWidthPx = (1f * density),
                    strokeColor = palette.border,
                    cellSizePx = (8f * density).toInt().coerceAtLeast(6)
                )
            } else {
                palette.createRoundedRect(
                    fillColor = palette.surfaceVariant,
                    cornerRadiusPx = 8f * density,
                    strokeWidthPx = (1f * density).toInt(),
                    strokeColor = palette.border
                )
            }

            tvBadgeGif.visibility = if (item.isAnimatedGif) View.VISIBLE else View.GONE
            tvBadgeSource.text = item.sourceLabel
            overlayLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            ivThumbnail.scaleType = ImageView.ScaleType.FIT_CENTER

            val fullUrl = item.fullImageUrl.takeIf { SearchParsers.isSafeHttpUrl(it) }
            val thumbUrl = item.thumbnailUrl.takeIf { SearchParsers.isSafeHttpUrl(it) }
            // Bing/Google thumbnails flatten transparent PNGs into opaque white-background JPEGs.
            // When transparent results are requested, load the full PNG/WebP directly so alpha renders cleanly.
            val thumbIsAnimated = thumbUrl?.lowercase()?.contains(".gif") == true
            val initialUrl: String
            val secondaryUrl: String?
            if (item.isTransparent) {
                initialUrl = fullUrl ?: thumbUrl ?: ""
                secondaryUrl = if (initialUrl == fullUrl) thumbUrl else fullUrl
            } else if (!item.isAnimatedGif || thumbIsAnimated) {
                initialUrl = thumbUrl ?: fullUrl ?: ""
                secondaryUrl = if (initialUrl == thumbUrl) fullUrl else thumbUrl
            } else {
                initialUrl = fullUrl ?: thumbUrl ?: ""
                secondaryUrl = if (initialUrl == fullUrl) thumbUrl else fullUrl
            }

            if (ivThumbnail.tag != initialUrl) {
                ivThumbnail.setImageDrawable(null)
                ivThumbnail.tag = initialUrl
            }

            val reqBuilder = ImageRequest.Builder(itemView.context)
                .data(initialUrl)
                .target(ivThumbnail)
                .crossfade(true)
                .listener(
                    onError = { _, _ ->
                        if (initialUrl != secondaryUrl && !secondaryUrl.isNullOrEmpty()) {
                            val fallbackReq = ImageRequest.Builder(itemView.context)
                                .data(secondaryUrl)
                                .target(ivThumbnail)
                                .build()
                            imageLoader.enqueue(fallbackReq)
                        }
                    }
                )
            if (item.isTransparent) {
                reqBuilder.size(512)
            }
            imageLoader.enqueue(reqBuilder.build())

            itemView.setOnClickListener {
                if (loadingItemId == null) {
                    onImageClicked(item)
                }
            }
        }
    }

    /**
     * Subtle rounded checkerboard background for transparent image results so alpha transparency is clearly visible.
     */
    private class TransparencyCheckerboardDrawable(
        isDark: Boolean,
        private val cornerRadiusPx: Float,
        private val strokeWidthPx: Float,
        strokeColor: Int,
        private val cellSizePx: Int
    ) : Drawable() {
        private val lightSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.parseColor("#232938") else Color.parseColor("#EFE9DD")
        }
        private val darkSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.parseColor("#1A1F2B") else Color.parseColor("#E1D9C8")
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = strokeColor
        }
        private val clipPath = Path()
        private val rectF = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            rectF.set(b)
            clipPath.reset()
            clipPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(rectF, lightSquarePaint)

            var row = 0
            var y = b.top
            while (y < b.bottom) {
                var col = if (row % 2 == 0) 1 else 0
                var x = b.left + col * cellSizePx
                while (x < b.right) {
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        minOf(x + cellSizePx, b.right).toFloat(),
                        minOf(y + cellSizePx, b.bottom).toFloat(),
                        darkSquarePaint
                    )
                    x += cellSizePx * 2
                }
                row++
                y += cellSizePx
            }
            canvas.restoreToCount(save)

            val halfStroke = strokeWidthPx / 2f
            rectF.inset(halfStroke, halfStroke)
            canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, borderPaint)
        }

        override fun setAlpha(alpha: Int) {
            lightSquarePaint.alpha = alpha
            darkSquarePaint.alpha = alpha
            borderPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            lightSquarePaint.colorFilter = colorFilter
            darkSquarePaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
