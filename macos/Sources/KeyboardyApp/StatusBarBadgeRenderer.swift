import AppKit
import CoreGraphics
import KeyboardyCore

enum StatusBarBadgeRenderer {

    private static var cachedAppIcon: NSImage?
    private static var cachedWhiteIcon: NSImage?
    private static var cachedBlackIcon: NSImage?

    /// Generates a crisp Retina 20x20 pt menu bar image for the selected `StatusBarBadgeStyle`.
    static func makeStatusBarImage(style: StatusBarBadgeStyle) -> NSImage {
        switch style {
        case .keyboardyAppIcon:
            if let cached = cachedAppIcon { return cached }
            let img = drawColorAppIcon()
            cachedAppIcon = img
            return img

        case .keyboardyWhiteIcon:
            if let cached = cachedWhiteIcon { return cached }
            let img = drawMonochromeKeyboardyIcon(isWhite: true)
            cachedWhiteIcon = img
            return img

        case .keyboardyBlackIcon:
            if let cached = cachedBlackIcon { return cached }
            let img = drawMonochromeKeyboardyIcon(isWhite: false)
            cachedBlackIcon = img
            return img
        }
    }

    private static func loadSourcePngImage() -> NSImage? {
        if let resourceURL = Bundle.main.url(forResource: "StatusBarIcon", withExtension: "png"),
           let img = NSImage(contentsOf: resourceURL) {
            return img
        }
        if let icnsURL = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
           let img = NSImage(contentsOf: icnsURL) {
            return img
        }
        return NSApp?.applicationIconImage
    }

    /// Renders the original full-color Android Keyboardy App Icon (`StatusBarIcon.png`).
    private static func drawColorAppIcon() -> NSImage {
        let targetSize = NSSize(width: 20, height: 20)
        guard let rawIcon = loadSourcePngImage() else {
            return drawVectorKeyboardyIcon(isWhite: true)
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

    /// Produces a crisp White or Black version of the Keyboardy App Icon by converting the source icon's
    /// cyan/blue hologram + keyboard artwork into a high-contrast White or Black monochrome icon
    /// (with vector fallback if the PNG resource is unavailable).
    private static func drawMonochromeKeyboardyIcon(isWhite: Bool) -> NSImage {
        guard let rawIcon = loadSourcePngImage(),
              let cgSource = rawIcon.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
            return drawVectorKeyboardyIcon(isWhite: isWhite)
        }

        let pxWidth = 44
        let pxHeight = 44
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue

        guard let ctx = CGContext(
            data: nil,
            width: pxWidth,
            height: pxHeight,
            bitsPerComponent: 8,
            bytesPerRow: pxWidth * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ), let dataPtr = ctx.data else {
            return drawVectorKeyboardyIcon(isWhite: isWhite)
        }

        ctx.interpolationQuality = .high
        ctx.draw(cgSource, in: CGRect(x: 0, y: 0, width: pxWidth, height: pxHeight))

        let pixels = dataPtr.bindMemory(to: UInt8.self, capacity: pxWidth * pxHeight * 4)

        // Transform the glowing cyan/blue foreground artwork of ic_app_option_1.png into a crisp
        // pure White or pure Black monochrome version on a matching contrast squircle badge.
        for i in 0..<(pxWidth * pxHeight) {
            let offset = i * 4
            let g = Float(pixels[offset + 1])
            let b = Float(pixels[offset + 2])
            let a = Float(pixels[offset + 3]) / 255.0

            if a < 0.08 {
                pixels[offset] = 0
                pixels[offset + 1] = 0
                pixels[offset + 2] = 0
                pixels[offset + 3] = 0
                continue
            }

            // In ic_app_option_1.png, the dark squircle background has B ~ 18..28,
            // while the keyboard keys and hologram picture frame have B ~ 90..255.
            let signal = max(g, b)
            // Normalize foreground intensity with S-curve contrast boost
            let norm = min(max((signal - 38.0) / 155.0, 0.0), 1.0)
            let boosted = norm * norm * (3.0 - 2.0 * norm)

            if isWhite {
                // White Version: Pure white foreground (#FFFFFF) on a sleek dark charcoal squircle (#14171F)
                let bgR: Float = 20.0
                let bgG: Float = 23.0
                let bgB: Float = 31.0
                let outR = bgR + boosted * (255.0 - bgR)
                let outG = bgG + boosted * (255.0 - bgG)
                let outB = bgB + boosted * (255.0 - bgB)

                pixels[offset]     = UInt8(min(max(outR * a, 0), 255))
                pixels[offset + 1] = UInt8(min(max(outG * a, 0), 255))
                pixels[offset + 2] = UInt8(min(max(outB * a, 0), 255))
                pixels[offset + 3] = UInt8(min(max(a * 255.0, 0), 255))
            } else {
                // Black Version: Pure black/charcoal foreground (#12141A) on a crisp white/silver squircle (#F4F6FA)
                let bgR: Float = 244.0
                let bgG: Float = 246.0
                let bgB: Float = 250.0
                let fgR: Float = 16.0
                let fgG: Float = 18.0
                let fgB: Float = 24.0
                let outR = bgR - boosted * (bgR - fgR)
                let outG = bgG - boosted * (bgG - fgG)
                let outB = bgB - boosted * (bgB - fgB)

                pixels[offset]     = UInt8(min(max(outR * a, 0), 255))
                pixels[offset + 1] = UInt8(min(max(outG * a, 0), 0 + 255))
                pixels[offset + 2] = UInt8(min(max(outB * a, 0), 255))
                pixels[offset + 3] = UInt8(min(max(a * 255.0, 0), 255))
            }
        }

        guard let outCg = ctx.makeImage() else {
            return drawVectorKeyboardyIcon(isWhite: isWhite)
        }

        let targetSize = NSSize(width: 20, height: 20)
        let finalImg = NSImage(size: targetSize, flipped: false) { rect in
            NSGraphicsContext.current?.imageInterpolation = .high
            let innerRect = rect.insetBy(dx: 0.5, dy: 0.5)
            let clipPath = NSBezierPath(roundedRect: innerRect, xRadius: 4.5, yRadius: 4.5)
            clipPath.addClip()

            if let cgCtx = NSGraphicsContext.current?.cgContext {
                cgCtx.draw(outCg, in: innerRect)
            }

            if !isWhite {
                let borderPath = NSBezierPath(roundedRect: innerRect, xRadius: 4.5, yRadius: 4.5)
                borderPath.lineWidth = 0.8
                NSColor(calibratedWhite: 0.1, alpha: 0.35).setStroke()
                borderPath.stroke()
            }
            return true
        }
        finalImg.isTemplate = false
        return finalImg
    }

    private static func drawVectorKeyboardyIcon(isWhite: Bool) -> NSImage {
        let size = NSSize(width: 20, height: 20)
        let img = NSImage(size: size, flipped: false) { rect in
            let inner = rect.insetBy(dx: 1.0, dy: 1.0)
            let bg = NSBezierPath(roundedRect: inner, xRadius: 4.5, yRadius: 4.5)
            (isWhite ? NSColor(calibratedWhite: 0.1, alpha: 1.0) : NSColor(calibratedWhite: 0.96, alpha: 1.0)).setFill()
            bg.fill()

            let fg = isWhite ? NSColor.white : NSColor(calibratedWhite: 0.1, alpha: 1.0)
            fg.setStroke()
            fg.setFill()

            let frameRect = NSRect(x: 4.5, y: 8.5, width: 11.0, height: 8.0)
            let framePath = NSBezierPath(roundedRect: frameRect, xRadius: 1.5, yRadius: 1.5)
            framePath.lineWidth = 1.3
            framePath.stroke()

            let kbRect = NSRect(x: 3.0, y: 2.5, width: 14.0, height: 4.2)
            let kbPath = NSBezierPath(roundedRect: kbRect, xRadius: 1.2, yRadius: 1.2)
            kbPath.lineWidth = 1.2
            kbPath.stroke()
            return true
        }
        img.isTemplate = false
        return img
    }
}
