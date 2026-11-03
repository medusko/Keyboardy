package com.medusko.keyboardy.ime

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.medusko.keyboardy.R
import com.medusko.keyboardy.search.ImageResult
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
        items.clear()
        items.addAll(newItems)
        loadingItemId = null
        notifyDataSetChanged()
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

            val previewUrl = if (item.isAnimatedGif && item.fullImageUrl.lowercase().endsWith(".gif")) {
                item.fullImageUrl
            } else {
                item.thumbnailUrl
            }

            val request = ImageRequest.Builder(itemView.context)
                .data(previewUrl)
                .target(ivThumbnail)
                .listener(
                    onError = { _, _ ->
                        if (previewUrl != item.thumbnailUrl) {
                            val fallbackReq = ImageRequest.Builder(itemView.context)
                                .data(item.thumbnailUrl)
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
