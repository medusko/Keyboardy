import Foundation
@testable import KeyboardyCore

#if canImport(XCTest)
import XCTest

final class SearchParsersTests: XCTestCase {

    func testParseBingAsyncImagesHtmlExtractsFullAndThumbUrls() {
        let sampleHtml = """
        <div class="imgpt">
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://media.tenor.com/parrot.gif&quot;,&quot;turl&quot;:&quot;https://tse1.mm.bing.net/th?id=OIP.123&quot;,&quot;t&quot;:&quot;Party Parrot GIF&quot;}"></a>
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://example.com/cat.png&quot;,&quot;turl&quot;:&quot;https://tse2.mm.bing.net/th?id=OIP.456&quot;,&quot;t&quot;:&quot;Cool Cat&quot;}"></a>
        </div>
        """

        let results = SearchParsers.parseBingAsyncImagesHtml(sampleHtml)
        XCTAssertEqual(results.count, 2)
        XCTAssertEqual(results[0].fullImageUrl, "https://media.tenor.com/parrot.gif")
        XCTAssertEqual(results[0].thumbnailUrl, "https://tse1.mm.bing.net/th?id=OIP.123")
        XCTAssertTrue(results[0].isAnimatedGif)
        XCTAssertEqual(results[0].sourceLabel, "⚡")

        XCTAssertEqual(results[1].fullImageUrl, "https://example.com/cat.png")
        XCTAssertFalse(results[1].isAnimatedGif)
    }

    func testParseGoogleWebViewJsonHandlesQuotedEvaluateJsString() {
        let rawJs = "\"[{\\\"thumb\\\":\\\"https://encrypted-tbn0.gstatic.com/images?q=tbn:111\\\",\\\"full\\\":\\\"https://example.org/meme.gif\\\",\\\"title\\\":\\\"Funny Meme\\\",\\\"isGif\\\":true}]\""
        let results = SearchParsers.parseGoogleWebViewJson(rawJs)
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].fullImageUrl, "https://example.org/meme.gif")
        XCTAssertEqual(results[0].thumbnailUrl, "https://encrypted-tbn0.gstatic.com/images?q=tbn:111")
        XCTAssertTrue(results[0].isAnimatedGif)
        XCTAssertEqual(results[0].sourceLabel, "G")
    }

    func testDetectImageMimeTypeAndExtension() {
        let gifBytes = Data([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00])
        let (gifMime, gifExt) = SearchParsers.detectImageMimeTypeAndExtension(gifBytes)
        XCTAssertEqual(gifMime, "image/gif")
        XCTAssertEqual(gifExt, "gif")

        let pngBytes = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        let (pngMime, pngExt) = SearchParsers.detectImageMimeTypeAndExtension(pngBytes)
        XCTAssertEqual(pngMime, "image/png")
        XCTAssertEqual(pngExt, "png")

        let webpBytes = Data("RIFF\u{00}\u{00}\u{00}\u{00}WEBPVP8 ".utf8)
        let (webpMime, webpExt) = SearchParsers.detectImageMimeTypeAndExtension(webpBytes)
        XCTAssertEqual(webpMime, "image/webp")
        XCTAssertEqual(webpExt, "webp")
    }

    func testSearchFilterBuildsEffectiveQuery() {
        XCTAssertEqual(SearchFilter.all.buildEffectiveQuery("party parrot"), "party parrot")
        XCTAssertEqual(SearchFilter.gif.buildEffectiveQuery("party parrot"), "party parrot gif")
        XCTAssertEqual(SearchFilter.gif.buildEffectiveQuery("party parrot gif"), "party parrot gif")
        XCTAssertEqual(SearchFilter.transparent.buildEffectiveQuery("doge"), "doge transparent png sticker")
    }

    func testIsSafeHttpUrlAllowsPublicHttpAndRejectsPrivateAndLocalSchemes() {
        XCTAssertTrue(SearchParsers.isSafeHttpUrl("https://media.tenor.com/parrot.gif"))
        XCTAssertTrue(SearchParsers.isSafeHttpUrl("http://example.com/image.png"))

        XCTAssertFalse(SearchParsers.isSafeHttpUrl("file:///etc/passwd"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("javascript:alert(1)"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://localhost:8080/test.png"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://127.0.0.1/secret.png"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://[::1]:8080/secret.png"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://169.254.169.254/latest/meta-data/"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://10.0.0.1/internal.jpg"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://172.16.0.1/internal.jpg"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://172.31.255.255/internal.jpg"))
        XCTAssertTrue(SearchParsers.isSafeHttpUrl("http://172.32.0.1/public.jpg"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://192.168.1.1/router.png"))
        XCTAssertFalse(SearchParsers.isSafeHttpUrl("http://0.0.0.0/zero.png"))
    }
}
#elseif canImport(Testing)
import Testing

struct SearchParsersSwiftTests {
    @Test func parseBingAsyncImagesHtmlExtractsFullAndThumbUrls() {
        let sampleHtml = """
        <div class="imgpt">
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://media.tenor.com/parrot.gif&quot;,&quot;turl&quot;:&quot;https://tse1.mm.bing.net/th?id=OIP.123&quot;,&quot;t&quot;:&quot;Party Parrot GIF&quot;}"></a>
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://example.com/cat.png&quot;,&quot;turl&quot;:&quot;https://tse2.mm.bing.net/th?id=OIP.456&quot;,&quot;t&quot;:&quot;Cool Cat&quot;}"></a>
        </div>
        """

        let results = SearchParsers.parseBingAsyncImagesHtml(sampleHtml)
        #expect(results.count == 2)
        #expect(results[0].fullImageUrl == "https://media.tenor.com/parrot.gif")
        #expect(results[0].thumbnailUrl == "https://tse1.mm.bing.net/th?id=OIP.123")
        #expect(results[0].isAnimatedGif)
        #expect(results[0].sourceLabel == "⚡")

        #expect(results[1].fullImageUrl == "https://example.com/cat.png")
        #expect(!results[1].isAnimatedGif)
    }

    @Test func parseGoogleWebViewJsonHandlesQuotedEvaluateJsString() {
        let rawJs = "\"[{\\\"thumb\\\":\\\"https://encrypted-tbn0.gstatic.com/images?q=tbn:111\\\",\\\"full\\\":\\\"https://example.org/meme.gif\\\",\\\"title\\\":\\\"Funny Meme\\\",\\\"isGif\\\":true}]\""
        let results = SearchParsers.parseGoogleWebViewJson(rawJs)
        #expect(results.count == 1)
        #expect(results[0].fullImageUrl == "https://example.org/meme.gif")
        #expect(results[0].thumbnailUrl == "https://encrypted-tbn0.gstatic.com/images?q=tbn:111")
        #expect(results[0].isAnimatedGif)
        #expect(results[0].sourceLabel == "G")
    }

    @Test func detectImageMimeTypeAndExtension() {
        let gifBytes = Data([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00])
        let (gifMime, gifExt) = SearchParsers.detectImageMimeTypeAndExtension(gifBytes)
        #expect(gifMime == "image/gif")
        #expect(gifExt == "gif")

        let pngBytes = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        let (pngMime, pngExt) = SearchParsers.detectImageMimeTypeAndExtension(pngBytes)
        #expect(pngMime == "image/png")
        #expect(pngExt == "png")

        let webpBytes = Data("RIFF\u{00}\u{00}\u{00}\u{00}WEBPVP8 ".utf8)
        let (webpMime, webpExt) = SearchParsers.detectImageMimeTypeAndExtension(webpBytes)
        #expect(webpMime == "image/webp")
        #expect(webpExt == "webp")
    }

    @Test func searchFilterBuildsEffectiveQuery() {
        #expect(SearchFilter.all.buildEffectiveQuery("party parrot") == "party parrot")
        #expect(SearchFilter.gif.buildEffectiveQuery("party parrot") == "party parrot gif")
        #expect(SearchFilter.gif.buildEffectiveQuery("party parrot gif") == "party parrot gif")
        #expect(SearchFilter.transparent.buildEffectiveQuery("doge") == "doge transparent png sticker")
    }

    @Test func isSafeHttpUrlAllowsPublicHttpAndRejectsPrivateAndLocalSchemes() {
        #expect(SearchParsers.isSafeHttpUrl("https://media.tenor.com/parrot.gif"))
        #expect(SearchParsers.isSafeHttpUrl("http://example.com/image.png"))

        #expect(!SearchParsers.isSafeHttpUrl("file:///etc/passwd"))
        #expect(!SearchParsers.isSafeHttpUrl("javascript:alert(1)"))
        #expect(!SearchParsers.isSafeHttpUrl("http://localhost:8080/test.png"))
        #expect(!SearchParsers.isSafeHttpUrl("http://127.0.0.1/secret.png"))
        #expect(!SearchParsers.isSafeHttpUrl("http://[::1]:8080/secret.png"))
        #expect(!SearchParsers.isSafeHttpUrl("http://169.254.169.254/latest/meta-data/"))
        #expect(!SearchParsers.isSafeHttpUrl("http://10.0.0.1/internal.jpg"))
        #expect(!SearchParsers.isSafeHttpUrl("http://172.16.0.1/internal.jpg"))
        #expect(!SearchParsers.isSafeHttpUrl("http://172.31.255.255/internal.jpg"))
        #expect(SearchParsers.isSafeHttpUrl("http://172.32.0.1/public.jpg"))
        #expect(!SearchParsers.isSafeHttpUrl("http://192.168.1.1/router.png"))
        #expect(!SearchParsers.isSafeHttpUrl("http://0.0.0.0/zero.png"))
    }
}
#endif
