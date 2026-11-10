package com.medusko.keyboardy.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SearchParsersTest {

    @Test
    fun `parseBingAsyncImagesHtml extracts full image and animated gif urls`() {
        val sampleHtml = """
            <div class="imgpt">
                <a class="iusc" m="{&quot;murl&quot;:&quot;https://media.tenor.com/abc/funny-cat.gif&quot;,&quot;turl&quot;:&quot;https://ts2.mm.bing.net/th?id=OIF.123&quot;,&quot;t&quot;:&quot;Funny Cat GIF&quot;}"></a>
                <a class="iusc" m="{&quot;murl&quot;:&quot;https://example.com/photo.png&quot;,&quot;turl&quot;:&quot;https://ts1.mm.bing.net/th?id=OIP.456&quot;,&quot;t&quot;:&quot;Cute Kitten&quot;}"></a>
            </div>
        """.trimIndent()

        val results = SearchParsers.parseBingAsyncImagesHtml(sampleHtml)
        assertEquals(2, results.size)
        assertEquals("https://media.tenor.com/abc/funny-cat.gif", results[0].fullImageUrl)
        assertEquals("https://ts2.mm.bing.net/th?id=OIF.123", results[0].thumbnailUrl)
        assertTrue(results[0].isAnimatedGif)

        assertEquals("https://example.com/photo.png", results[1].fullImageUrl)
        assertEquals(false, results[1].isAnimatedGif)
    }

    @Test
    fun `parseGoogleWebViewJson handles string-escaped JSON from evaluateJavascript`() {
        val rawEvaluateJsOutput =
            "\"[{\\\"thumb\\\":\\\"https://encrypted-tbn0.gstatic.com/images?q=tbn:123\\\",\\\"full\\\":\\\"https://site.org/meme.gif\\\",\\\"title\\\":\\\"Meme\\\",\\\"isGif\\\":true}]\""

        val results = SearchParsers.parseGoogleWebViewJson(rawEvaluateJsOutput)
        assertEquals(1, results.size)
        assertEquals("https://site.org/meme.gif", results[0].fullImageUrl)
        assertEquals("https://encrypted-tbn0.gstatic.com/images?q=tbn:123", results[0].thumbnailUrl)
        assertTrue(results[0].isAnimatedGif)
        assertEquals("G", results[0].sourceLabel)
    }

    @Test
    fun `detectImageMimeTypeAndExtension identifies GIF PNG WEBP and JPEG magic bytes`() {
        val gifBytes = "GIF89a\u0001\u0000".toByteArray(Charsets.US_ASCII)
        assertEquals("image/gif" to "gif", SearchParsers.detectImageMimeTypeAndExtension(gifBytes))

        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        assertEquals("image/png" to "png", SearchParsers.detectImageMimeTypeAndExtension(pngBytes))

        val webpBytes = "RIFF____WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        assertEquals("image/webp" to "webp", SearchParsers.detectImageMimeTypeAndExtension(webpBytes))

        val jpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg" to "jpg", SearchParsers.detectImageMimeTypeAndExtension(jpgBytes))
    }

    @Test
    fun `SearchFilter appends suffix without duplicating`() {
        assertEquals("cat gif", SearchFilter.GIF.buildEffectiveQuery("cat"))
        assertEquals("cat gif", SearchFilter.GIF.buildEffectiveQuery("cat gif"))
        assertEquals("dog meme", SearchFilter.MEME.buildEffectiveQuery("dog"))
    }

    @Test
    fun `isSafeHttpUrl accepts public http and https urls and rejects local or non-http urls`() {
        assertTrue(SearchParsers.isSafeHttpUrl("https://media.tenor.com/abc/funny.gif"))
        assertTrue(SearchParsers.isSafeHttpUrl("http://example.com/photo.jpg"))
        assertTrue(SearchParsers.isSafeHttpUrl("http://8.8.8.8/photo.jpg"))
        assertTrue(SearchParsers.isSafeHttpUrl("https://[2607:f8b0:4005:80b::200e]/photo.jpg"))
        assertTrue(SearchParsers.isSafeHttpUrl("http://100.63.255.254/photo.jpg"))
        assertTrue(SearchParsers.isSafeHttpUrl("http://100.128.0.1/photo.jpg"))

        assertFalse(SearchParsers.isSafeHttpUrl("file:///etc/passwd"))
        assertFalse(SearchParsers.isSafeHttpUrl("javascript:alert(1)"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://localhost:8080/img.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://localhost./img.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://sub.localhost/img.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://router.local/img.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://metadata.google.internal/img.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://intranet/img.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://127.0.0.1/img.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://0.0.0.0/img.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://192.168.1.1/admin.png"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://10.0.0.1/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://172.16.0.1/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://100.64.0.1/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://100.127.255.254/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://224.0.0.1/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://255.255.255.255/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://[::1]/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://[::ffff:192.168.1.1]/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://[::ffff:7f00:1]/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://[fc12::1]/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://[fd12:3456:789a::1]/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://[fe80::1]/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://2130706433/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://0177.0.0.1/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://0x7f000001/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://127.1/test.gif"))
        assertFalse(SearchParsers.isSafeHttpUrl("http://google.com@127.0.0.1/test.gif"))
    }

    @Test
    fun `readBoundedBytes enforces size limit`() {
        val data = ByteArray(1024) { 0x42 }
        val ok = SearchParsers.readBoundedBytes(ByteArrayInputStream(data), 2048)
        assertEquals(1024, ok?.size)

        val tooBig = SearchParsers.readBoundedBytes(ByteArrayInputStream(data), 512)
        assertNull(tooBig)
    }
}
