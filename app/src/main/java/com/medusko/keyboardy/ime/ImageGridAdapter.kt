package com.medusko.keyboardy.ime

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
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

    private val imageLoader: ImageLoader = ImageLoader.Builder(context.applicationContext)
        .memoryCache {
            MemoryCache.Builder(context.applicationContext)
                .maxSizePercent(0.15)
                .build()
        }
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .crossfade(true)
        .build()

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
                return oldList[oldItemPosition].fullImageUrl == newItems[newItemPosition].fullImageUrl
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
            itemView.background = palette.createRoundedRect(
                fillColor = palette.surfaceVariant,
                cornerRadiusPx = 8f * density,
                strokeWidthPx = (1f * density).toInt(),
                strokeColor = palette.border
            )

            tvBadgeGif.visibility = if (item.isAnimatedGif) View.VISIBLE else View.GONE
            tvBadgeSource.text = item.sourceLabel
            overlayLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            ivThumbnail.scaleType = ImageView.ScaleType.FIT_CENTER

            val fullUrl = item.fullImageUrl.takeIf { SearchParsers.isSafeHttpUrl(it) }
            val thumbUrl = item.thumbnailUrl.takeIf { SearchParsers.isSafeHttpUrl(it) }
            // For static images (or Tenor AAAAM animated GIF thumbnails), load thumbnailUrl first for fast grid rendering.
            // For non-Tenor animated GIFs where thumbnailUrl is a static frame, load fullUrl first so the GIF animates in the grid.
            val thumbIsAnimated = thumbUrl?.lowercase()?.contains(".gif") == true
            val initialUrl: String
            val secondaryUrl: String?
            if (!item.isAnimatedGif || thumbIsAnimated) {
                initialUrl = thumbUrl ?: fullUrl ?: ""
                secondaryUrl = if (initialUrl == thumbUrl) fullUrl else thumbUrl
            } else {
                initialUrl = fullUrl ?: thumbUrl ?: ""
                secondaryUrl = if (initialUrl == fullUrl) thumbUrl else fullUrl
            }

            val request = ImageRequest.Builder(itemView.context)
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
                .build()
            imageLoader.enqueue(request)

            itemView.setOnClickListener {
                if (loadingItemId == null) {
                    onImageClicked(item)
                }
            }
        }
    }
}
