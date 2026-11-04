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
        let fallbackPath = "app/src/main/res/drawable-nodpi/ic_app_option_1.png"
        if FileManager.default.fileExists(atPath: fallbackPath),
           let img = NSImage(contentsOfFile: fallbackPath) {
            return img
        }
        return NSApp?.applicationIconImage
    }

    /// Renders the full-color Android Keyboardy App Icon (`StatusBarIcon.png`), cropping inside the white outer corners
    /// so no white border from the source PNG is ever visible.
    private static func drawColorAppIcon() -> NSImage {
        let targetSize = NSSize(width: 20, height: 20)
        guard let rawIcon = loadSourcePngImage() else {
            return drawMonochromeKeyboardyIcon(isWhite: true)
        }

        let rendered = NSImage(size: targetSize, flipped: false) { rect in
            NSGraphicsContext.current?.imageInterpolation = .high
            let innerRect = rect.insetBy(dx: 1.0, dy: 1.0)
            let clipPath = NSBezierPath(roundedRect: innerRect, xRadius: 4.5, yRadius: 4.5)
            clipPath.addClip()

            // Inset source rect by 6% to crop out the opaque white outer margin/corners of ic_app_option_1.png
            let srcW = rawIcon.size.width
            let srcH = rawIcon.size.height
            let cropInsetX = srcW * 0.06
            let cropInsetY = srcH * 0.06
            let srcRect = NSRect(
                x: cropInsetX,
                y: cropInsetY,
                width: srcW - cropInsetX * 2,
                height: srcH - cropInsetY * 2
            )
            rawIcon.draw(
                in: innerRect,
                from: srcRect,
                operation: .sourceOver,
                fraction: 1.0
            )
            return true
        }
        rendered.isTemplate = false
        return rendered
    }

    /// Extracts the Keyboardy hologram picture frame + perspective keyboard from `StatusBarIcon.png`
    /// and renders a pure White or pure Black transparent-background icon with ZERO border.
    private static func drawMonochromeKeyboardyIcon(isWhite: Bool) -> NSImage {
        guard let rawIcon = loadSourcePngImage(),
              let cgSource = rawIcon.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
            return NSImage(size: NSSize(width: 20, height: 20))
        }

        let pxWidth = 64
        let pxHeight = 64
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
            return NSImage(size: NSSize(width: 20, height: 20))
        }

        ctx.interpolationQuality = .high
        // Draw slightly zoomed in (+8%) so the hologram + keyboard fills the 20x20 menu bar slot cleanly
        // and completely excludes the outer white corners of ic_app_option_1.png
        let drawInset: CGFloat = -3.0
        ctx.draw(cgSource, in: CGRect(x: drawInset, y: drawInset, width: CGFloat(pxWidth) - drawInset * 2, height: CGFloat(pxHeight) - drawInset * 2))

        let pixels = dataPtr.bindMemory(to: UInt8.self, capacity: pxWidth * pxHeight * 4)

        for y in 0..<pxHeight {
            for x in 0..<pxWidth {
                let i = y * pxWidth + x
                let offset = i * 4
                let r = Float(pixels[offset])
                let g = Float(pixels[offset + 1])
                let b = Float(pixels[offset + 2])

                // Exclude any outer white background pixel from ic_app_option_1.png
                // (Outer background in ic_app_option_1.png is neutral white/gray where R, G, B are all high and B - R is near 0;
                // the Keyboardy artwork is cyan/blue where B > R, except the central glowing mountain/sun inside x: 14..50, y: 22..52)
                let isInsideCentralHologram = (x >= 14 && x <= 50 && y >= 20 && y <= 54)
                if !isInsideCentralHologram && r > 110 && (b - r) < 25 {
                    pixels[offset] = 0
                    pixels[offset + 1] = 0
                    pixels[offset + 2] = 0
                    pixels[offset + 3] = 0
                    continue
                }

                // Compute foreground alpha from the cyan/blue hologram & keyboard keys
                // Background of the dark squircle has B ~ 15..28. Keys and hologram have B ~ 85..255.
                let cyanSignal = max(b, g) - (isInsideCentralHologram ? 0.0 : r * 0.5)
                let norm = min(max((cyanSignal - 42.0) / 135.0, 0.0), 1.0)
                // Boost contrast so the hologram frame, mountains, sun, and keycaps have crisp solid opacity
                let alpha = norm * norm * (3.0 - 2.0 * norm)

                if alpha < 0.05 {
                    pixels[offset] = 0
                    pixels[offset + 1] = 0
                    pixels[offset + 2] = 0
                    pixels[offset + 3] = 0
                } else {
                    let outAlpha = UInt8(min(max(alpha * 255.0, 0), 255))
                    if isWhite {
                        // Premultiplied pure White (#FFFFFF) on 100% transparent background (zero border!)
                        pixels[offset]     = outAlpha
                        pixels[offset + 1] = outAlpha
                        pixels[offset + 2] = outAlpha
                        pixels[offset + 3] = outAlpha
                    } else {
                        // Premultiplied pure Black (#0D0E12) on 100% transparent background (zero border!)
                        let c = UInt8(Float(outAlpha) * 0.06)
                        pixels[offset]     = c
                        pixels[offset + 1] = c
                        pixels[offset + 2] = c
                        pixels[offset + 3] = outAlpha
                    }
                }
            }
        }

        guard let outCg = ctx.makeImage() else {
            return NSImage(size: NSSize(width: 20, height: 20))
        }

        let targetSize = NSSize(width: 20, height: 20)
        let finalImg = NSImage(size: targetSize, flipped: false) { rect in
            NSGraphicsContext.current?.imageInterpolation = .high
            if let cgCtx = NSGraphicsContext.current?.cgContext {
                cgCtx.draw(outCg, in: rect)
            }
            return true
        }
        finalImg.isTemplate = false
        return finalImg
    }
}
