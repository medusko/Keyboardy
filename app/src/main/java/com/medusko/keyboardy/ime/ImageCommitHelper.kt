package com.medusko.keyboardy.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.medusko.keyboardy.search.ImageResult
import com.medusko.keyboardy.search.SafeNetworkClient
import com.medusko.keyboardy.search.SearchParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

enum class CommitOutcome {
    COMMITTED_DIRECTLY,
    COPIED_TO_CLIPBOARD_FALLBACK,
    DOWNLOAD_FAILED
}

class ImageCommitHelper(private val context: Context) {

    suspend fun downloadAndCommitImage(
        item: ImageResult,
        inputConnection: InputConnection?,
        editorInfo: EditorInfo?,
        onBeforeCommit: (() -> Unit)? = null
    ): CommitOutcome {
        val rawBytes = downloadBytes(item.fullImageUrl)
            ?: if (item.thumbnailUrl != item.fullImageUrl) {
                downloadBytes(item.thumbnailUrl)
            } else {
                null
            } ?: return CommitOutcome.DOWNLOAD_FAILED

        var (mimeType, ext) = SearchParsers.detectImageMimeTypeAndExtension(rawBytes)
        var finalBytes = rawBytes

        // If the downloaded image is WebP and the target app doesn't advertise WebP support,
        // transcode static WebP to PNG for maximum compatibility across all chat apps.
        val acceptedMimes = editorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) } ?: emptyArray()
        val supportsWebp = acceptedMimes.any {
            it.equals("image/*", ignoreCase = true) || it.equals("image/webp", ignoreCase = true)
        }
        if (mimeType == "image/webp" && !supportsWebp) {
            withContext(Dispatchers.IO) {
                try {
                    val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                    if (bmp != null) {
                        try {
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            finalBytes = out.toByteArray()
                            mimeType = "image/png"
                            ext = "png"
                        } finally {
                            bmp.recycle()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val contentUri = withContext(Dispatchers.IO) {
            saveToSharedCacheAndGetUri(finalBytes, ext)
        } ?: return CommitOutcome.DOWNLOAD_FAILED

        // Run any pre-commit hook on main thread (e.g., clearing temporary search query in chat box)
        onBeforeCommit?.invoke()

        if (inputConnection != null && editorInfo != null) {
            val description = ClipDescription(
                item.title.ifBlank { "Image from Keyboardy" },
                arrayOf(mimeType)
            )
            val linkUri = try {
                Uri.parse(item.fullImageUrl)
            } catch (_: Exception) {
                null
            }
            val contentInfo = InputContentInfoCompat(contentUri, description, linkUri)
            val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION

            val committed = try {
                InputConnectionCompat.commitContent(
                    inputConnection,
                    editorInfo,
                    contentInfo,
                    flags,
                    null
                )
            } catch (_: Exception) {
                false
            }

            if (committed) {
                return CommitOutcome.COMMITTED_DIRECTLY
            }
        }

        // Fallback for text-only fields (like in-meeting Zoom text chat): copy image URI to system clipboard
        copyUriToClipboard(contentUri, item.title)
        return CommitOutcome.COPIED_TO_CLIPBOARD_FALLBACK
    }

    private fun saveToSharedCacheAndGetUri(bytes: ByteArray, ext: String): Uri? {
        return try {
            val dir = File(context.cacheDir, "shared_images")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            // Prune cached images older than 10 minutes
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }

            val targetFile = File(dir, "keyboardy_${System.currentTimeMillis()}.$ext")
            targetFile.outputStream().use { it.write(bytes) }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun copyUriToClipboard(uri: Uri, label: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newUri(context.contentResolver, label.ifBlank { "Image" }, uri)
            clipboard?.setPrimaryClip(clip)
        } catch (_: Exception) {
        }
    }

    private suspend fun downloadBytes(urlStr: String): ByteArray? {
        return SafeNetworkClient.fetchBytesBounded(
            url = urlStr,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            ),
            maxBytes = MAX_IMAGE_BYTES
        )
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024 // 25 MB
    }
}
