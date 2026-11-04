import Foundation

public enum KeyboardyVersion {
    public static let versionName = "1.0.1-beta.3"
    public static let buildNumber = "4"
    public static let releaseDate = "2026-11-03"

    public static var displayString: String {
        "v\(versionName)"
    }

    public static var detailedDisplayString: String {
        "v\(versionName) (Build \(buildNumber))"
    }

    public static let recentImprovements: [(title: String, detail: String)] = [
        (
            "🖱️ 1-Click Image Click Auto-Paste Fixed",
            "Wired direct onSelect handler from image preview layers to selection flow, eliminating swallowed mouse clicks."
        ),
        (
            "⌨️ Hardware-Accurate ⌘V Key Dispatch",
            "Synthesizes Command key-down, V key-down, 35ms hold, V key-up, and Command key-up posted directly to target application PID and system HID tap."
        ),
        (
            "📋 Multi-Format Pasteboard Payloads",
            "Populates NSPasteboard with public.file-url, NSFilenamesPboardType, image/gif, com.compuserve.gif, image/png, and public.tiff for universal browser & desktop app compatibility."
        ),
        (
            "🧪 Interactive Insertion Test Studio",
            "Included a live web testing studio (test_site/index.html) with real-time pasteboard event logs and instant GIF/image verification."
        ),
        (
            "🔐 macOS Accessibility Permission Onboarding",
            "Automatic detection, prompt, and direct System Settings navigation for Accessibility permission required for 1-click Auto-Paste into chat apps."
        ),
        (
            "🖱️ Reliable 1-Click Auto-Paste & Native Drag",
            "Resolved gesture interception by cleanly decoupling click events into native SwiftUI Button actions and drag operations into NSItemProvider drops."
        ),
        (
            "⚡ Fast Tenor GIF Integration",
            "Added high-speed Tenor GIF search with ~60ms preview loading via lightweight AAAAM animated streams."
        ),
        (
            "🔄 Continuous Looping GIFs",
            "Re-engineered the grid layer compositor with dedicated RunLoop frame-stepping so animated GIFs play smoothly in an infinite loop."
        ),
        (
            "🎯 Accurate 45+ Image Results",
            "Switched to a persistent WebKit session with anti-bot verification, eliminating Google/Bing CAPTCHA blocks and returning 45+ genuine results."
        ),
        (
            "🚀 1-Step Direct Auto-Paste",
            "Restores target chat app focus automatically and synthesizes ⌘V with 25ms key hold without overwriting your clipboard."
        ),
        (
            "🖱️ Native Drag-and-Drop",
            "Drag any image or animated GIF directly from the search grid into Messages, Slack, Discord, Chrome, or Finder."
        ),
        (
            "🌓 Dark/Light Mode Dynamic Icon",
            "Automatically switches to a crisp White Icon in Dark Mode and Black Icon in Light Mode (zero outer border)."
        ),
        (
            "⌨️ ⌘A Text Editing Shortcuts",
            "Added full native Edit menu support so ⌘A (Select All), ⌘C, ⌘V, ⌘X, and ⌘Z work natively in the search bar."
        ),
        (
            "🧹 Instant Search Cache Clearing",
            "Searching a new term immediately clears old results and cancels in-flight tasks so previous images never linger."
        )
    ]
}
