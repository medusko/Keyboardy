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

    let sampleTenorHtml = """
    <img src="https://media.tenor.com/1RVeTBOtmi4AAAAM/lil-jon-yeah.gif" alt="Lil Jon Yeah GIF">
    """
    let tenorParsed = SearchParsers.parseTenorSearchHtml(sampleTenorHtml)
    precondition(
        tenorParsed.count == 1 &&
        tenorParsed[0].isAnimatedGif &&
        tenorParsed[0].thumbnailUrl == "https://media.tenor.com/1RVeTBOtmi4AAAAM/lil-jon-yeah.gif" &&
        tenorParsed[0].fullImageUrl == "https://media1.tenor.com/m/1RVeTBOtmi4AAAAC/lil-jon-yeah.gif",
        "Tenor GIF parser verification failed"
    )

    let appImg = StatusBarBadgeRenderer.makeStatusBarImage(style: .keyboardyAppIcon)
    let whiteImg = StatusBarBadgeRenderer.makeStatusBarImage(style: .keyboardyWhiteIcon)
    let blackImg = StatusBarBadgeRenderer.makeStatusBarImage(style: .keyboardyBlackIcon)
    precondition(appImg.size.width > 0 && whiteImg.size.width > 0 && blackImg.size.width > 0, "StatusBar badge render failed")

    precondition(SearchFilter.all.requiresAnimatedGif(for: "happy cat gif") == true, "GIF query detection failed")
    precondition(SearchFilter.gif.requiresAnimatedGif(for: "happy cat") == true, "GIF filter detection failed")
    precondition(SearchFilter.all.requiresAnimatedGif(for: "sunset wallpaper") == false, "Non-GIF query detection failed")

    MainActor.assumeIsolated {
        let suite = UserDefaults(suiteName: "com.medusko.keyboardy.verify.\(UUID().uuidString)")!
        let testPrefs = KeyboardyPrefs(defaults: suite)
        testPrefs.applyThemeMode(.dark, syncIcon: true)
        precondition(testPrefs.isDarkMode == true && testPrefs.statusBarBadgeStyle == .keyboardyWhiteIcon, "Night mode must select White Icon")
        testPrefs.applyThemeMode(.light, syncIcon: true)
        precondition(testPrefs.isDarkMode == false && testPrefs.statusBarBadgeStyle == .keyboardyBlackIcon, "Light mode must select Black Icon")
    }

    print("✅ Keyboardy macOS verification passed (parsers, magic bytes, GIF filter detection, and Night=White / Light=Black icons)!")
    exit(0)
}

MainActor.assumeIsolated {
    let app = NSApplication.shared
    let delegate = KeyboardyAppDelegate()
    app.delegate = delegate
    app.run()
}
