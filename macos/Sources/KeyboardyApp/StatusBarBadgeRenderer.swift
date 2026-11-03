import AppKit
import KeyboardyCore

enum StatusBarBadgeRenderer {

    /// Generates a crisp Retina menu bar image for the selected `StatusBarBadgeStyle`.
    static func makeStatusBarImage(style: StatusBarBadgeStyle) -> NSImage {
        switch style {
        case .keyboardyAppIcon:
            return drawKeyboardyAppIcon()
        case .gifBluePill:
            return drawPillBadge(text: "GiF", fillColor: NSColor(calibratedRed: 0.08, green: 0.51, blue: 0.94, alpha: 1.0), drawBullseyeDot: true)
        case .gifMonochrome:
            let img = drawMonochromePill(text: "GiF")
            img.isTemplate = true
            return img
        case .kbBluePill:
            return drawPillBadge(text: "KB", fillColor: NSColor(calibratedRed: 0.18, green: 0.42, blue: 0.85, alpha: 1.0), drawBullseyeDot: false)
        }
    }

    /// Loads the selected Android Keyboardy App Icon (`StatusBarIcon.png` / `AppIcon.icns`) and renders it as a crisp 20x20 macOS Menu Bar icon.
    private static func drawKeyboardyAppIcon() -> NSImage {
        let sourceImage: NSImage? = {
            if let resourceURL = Bundle.main.url(forResource: "StatusBarIcon", withExtension: "png"),
               let img = NSImage(contentsOf: resourceURL) {
                return img
            }
            if let icnsURL = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
               let img = NSImage(contentsOf: icnsURL) {
                return img
            }
            return NSApp?.applicationIconImage
        }()

        let targetSize = NSSize(width: 20, height: 20)
        guard let rawIcon = sourceImage else {
            return drawPillBadge(text: "KB", fillColor: NSColor(calibratedRed: 0.18, green: 0.42, blue: 0.85, alpha: 1.0), drawBullseyeDot: false)
        }

        let rendered = NSImage(size: targetSize, flipped: false) { rect in
            NSGraphicsContext.current?.imageInterpolation = .high
            let clipPath = NSBezierPath(roundedRect: rect.insetBy(dx: 0.5, dy: 0.5), xRadius: 4.5, yRadius: 4.5)
            clipPath.addClip()
            rawIcon.draw(
                in: rect,
                from: NSRect(origin: .zero, size: rawIcon.size),
                operation: .sourceOver,
                fraction: 1.0
            )
            return true
        }
        rendered.isTemplate = false
        return rendered
    }

    /// Draws the bright blue "GiF" badge (complete with the stylized bullseye dot over the 'i').
    private static func drawPillBadge(text: String, fillColor: NSColor, drawBullseyeDot: Bool) -> NSImage {
        let size = NSSize(width: 34, height: 18)
        let image = NSImage(size: size, flipped: false) { rect in
            let pillRect = rect.insetBy(dx: 0.5, dy: 0.5)
            let path = NSBezierPath(roundedRect: pillRect, xRadius: 3.5, yRadius: 3.5)
            fillColor.setFill()
            path.fill()

            let font = NSFont.systemFont(ofSize: 12.5, weight: .heavy)
            let paragraph = NSMutableParagraphStyle()
            paragraph.alignment = .center

            let attrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: NSColor.white,
                .paragraphStyle: paragraph,
                .kern: 0.3
            ]

            let displayStr = drawBullseyeDot ? "GıF" : text
            let attrStr = NSAttributedString(string: displayStr, attributes: attrs)
            let strSize = attrStr.size()
            let textRect = NSRect(
                x: (rect.width - strSize.width) / 2.0,
                y: (rect.height - strSize.height) / 2.0 - 0.5,
                width: strSize.width,
                height: strSize.height
            )
            attrStr.draw(in: textRect)

            if drawBullseyeDot {
                let centerX = rect.midX + 0.8
                let centerY = rect.height - 4.3
                let outerRing = NSBezierPath(
                    ovalIn: NSRect(x: centerX - 2.2, y: centerY - 2.2, width: 4.4, height: 4.4)
                )
                outerRing.lineWidth = 1.0
                NSColor.white.setStroke()
                outerRing.stroke()

                let innerDot = NSBezierPath(
                    ovalIn: NSRect(x: centerX - 0.8, y: centerY - 0.8, width: 1.6, height: 1.6)
                )
                NSColor.white.setFill()
                innerDot.fill()
            }

            return true
        }
        image.isTemplate = false
        return image
    }

    private static func drawMonochromePill(text: String) -> NSImage {
        let size = NSSize(width: 32, height: 18)
        let image = NSImage(size: size, flipped: false) { rect in
            let pillRect = rect.insetBy(dx: 1.0, dy: 1.5)
            let path = NSBezierPath(roundedRect: pillRect, xRadius: 4.0, yRadius: 4.0)
            path.lineWidth = 1.4
            NSColor.black.setStroke()
            path.stroke()

            let font = NSFont.systemFont(ofSize: 10.5, weight: .bold)
            let paragraph = NSMutableParagraphStyle()
            paragraph.alignment = .center
            let attrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: NSColor.black,
                .paragraphStyle: paragraph
            ]
            let attrStr = NSAttributedString(string: text, attributes: attrs)
            let strSize = attrStr.size()
            let textRect = NSRect(
                x: (rect.width - strSize.width) / 2.0,
                y: (rect.height - strSize.height) / 2.0,
                width: strSize.width,
                height: strSize.height
            )
            attrStr.draw(in: textRect)
            return true
        }
        image.isTemplate = true
        return image
    }
}
