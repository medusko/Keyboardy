import AppKit
import Foundation
import KeyboardyCore

if CommandLine.arguments.contains("--verify") {
    let sampleHtml = """
    <a class="iusc" m="{&quot;murl&quot;:&quot;https://media.tenor.com/parrot.gif&quot;,&quot;turl&quot;:&quot;https://tse1.mm.bing.net/th?id=OIP.123&quot;,&quot;t&quot;:&quot;Party Parrot GIF&quot;}"></a>
    """
    let parsed = SearchParsers.parseBingAsyncImagesHtml(sampleHtml)
    precondition(parsed.count == 1 && parsed[0].isAnimatedGif, "Bing parser verification failed")

    let gifHeader = Data([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00])
    let (mime, ext) = SearchParsers.detectImageMimeTypeAndExtension(gifHeader)
    precondition(mime == "image/gif" && ext == "gif", "GIF magic byte verification failed")

    let appImg = StatusBarBadgeRenderer.makeStatusBarImage(style: .keyboardyAppIcon)
    let whiteImg = StatusBarBadgeRenderer.makeStatusBarImage(style: .keyboardyWhiteIcon)
    let blackImg = StatusBarBadgeRenderer.makeStatusBarImage(style: .keyboardyBlackIcon)
    precondition(appImg.size.width > 0 && whiteImg.size.width > 0 && blackImg.size.width > 0, "StatusBar badge render failed")

    print("✅ Keyboardy macOS verification passed (parsers, magic bytes, and App/White/Black status bar icons)!")
    exit(0)
}

MainActor.assumeIsolated {
    let app = NSApplication.shared
    let delegate = KeyboardyAppDelegate()
    app.delegate = delegate
    app.run()
}
