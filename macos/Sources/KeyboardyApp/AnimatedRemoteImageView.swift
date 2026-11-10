import AppKit
import ImageIO
import KeyboardyCore
import QuartzCore
import SwiftUI

struct DecodedImagePayload: @unchecked Sendable {
    let frames: [CGImage]
    let frameDelays: [TimeInterval]
    let keyTimes: [NSNumber]
    let totalDuration: CFTimeInterval

    var isAnimated: Bool {
        frames.count > 1 && totalDuration > 0
    }

    var byteCost: Int {
        max(frames.reduce(0) { $0 + ($1.bytesPerRow * $1.height) }, 1)
    }
}

private final class DecodedImagePayloadBox: NSObject {
    let payload: DecodedImagePayload
    init(_ payload: DecodedImagePayload) {
        self.payload = payload
    }
}

final class RemoteImageCache: @unchecked Sendable {
    static let shared = RemoteImageCache()
    private static let maxImageBytes = 25 * 1024 * 1024 // 25 MB
    private let dataCache = NSCache<NSString, NSData>()
    private let payloadCache = NSCache<NSString, DecodedImagePayloadBox>()
    private let lock = NSLock()
    private var inFlightTasks: [String: Task<Data?, Never>] = [:]

    private init() {
        dataCache.countLimit = 250
        dataCache.totalCostLimit = 180 * 1024 * 1024 // 180 MB
        payloadCache.countLimit = 120
        payloadCache.totalCostLimit = 150 * 1024 * 1024 // 150 MB
    }

    func data(for url: String) -> Data? {
        dataCache.object(forKey: url as NSString) as Data?
    }

    func store(_ data: Data, for url: String) {
        dataCache.setObject(data as NSData, forKey: url as NSString, cost: data.count)
    }

    func decodedPayload(for url: String) -> DecodedImagePayload? {
        if let boxed = payloadCache.object(forKey: url as NSString) {
            return boxed.payload
        }
        if let cachedData = data(for: url),
           let decoded = Self.decodePayload(from: cachedData) {
            payloadCache.setObject(DecodedImagePayloadBox(decoded), forKey: url as NSString, cost: decoded.byteCost)
            return decoded
        }
        return nil
    }

    func storeDecodedPayload(_ payload: DecodedImagePayload, for url: String) {
        payloadCache.setObject(DecodedImagePayloadBox(payload), forKey: url as NSString, cost: payload.byteCost)
    }

    /// Lightweight raw-bytes prefetch that deduplicates network fetches and caches raw `Data` without decoding frames.
    func prefetchData(urlStr: String, timeout: TimeInterval) async -> Data? {
        if let existingData = data(for: urlStr) {
            return existingData
        }

        let downloadTask: Task<Data?, Never> = lock.withLock {
            if let active = inFlightTasks[urlStr] {
                return active
            }
            let task = Task<Data?, Never>.detached(priority: .userInitiated) {
                await Self.performNetworkFetch(urlStr: urlStr, timeout: timeout)
            }
            inFlightTasks[urlStr] = task
            return task
        }

        let fetchedData = await downloadTask.value
        _ = lock.withLock {
            inFlightTasks.removeValue(forKey: urlStr)
        }

        guard let data = fetchedData, data.count <= Self.maxImageBytes else {
            return nil
        }
        store(data, for: urlStr)
        return data
    }

    /// Deduplicated download + decode so progressive grid updates never cancel an in-flight GIF download.
    func fetchAndDecode(urlStr: String, timeout: TimeInterval) async -> DecodedImagePayload? {
        if let existing = decodedPayload(for: urlStr) {
            return existing
        }

        guard let data = await prefetchData(urlStr: urlStr, timeout: timeout) else {
            return nil
        }

        let decoded = await Task.detached(priority: .userInitiated) {
            Self.decodePayload(from: data)
        }.value

        if let decoded = decoded {
            storeDecodedPayload(decoded, for: urlStr)
        }
        return decoded
    }

    private static func performNetworkFetch(urlStr: String, timeout: TimeInterval) async -> Data? {
        guard SearchParsers.isSafeHttpUrl(urlStr),
              let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.timeoutInterval = timeout
        req.setValue(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            forHTTPHeaderField: "User-Agent"
        )
        req.setValue("image/gif,image/webp,image/apng,image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        return await SearchParsers.fetchBoundedData(for: req, maxBytes: maxImageBytes)
    }

    /// Decodes either a single-frame image or a multi-frame animated GIF / WebP using ImageIO (`CGImageSource`).
    static func decodePayload(from data: Data) -> DecodedImagePayload? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }
        let totalCount = CGImageSourceGetCount(source)
        guard totalCount > 0 else { return nil }

        if totalCount == 1 {
            let singleThumbOptions: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 512,
                kCGImageSourceCreateThumbnailWithTransform: true
            ]
            guard let cgImg = CGImageSourceCreateThumbnailAtIndex(source, 0, singleThumbOptions as CFDictionary)
                ?? CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
            return DecodedImagePayload(frames: [cgImg], frameDelays: [0.0], keyTimes: [0.0], totalDuration: 0)
        }

        // For very long GIFs (> 72 frames), stride by 2 to keep GPU memory and decode time ultra-fast
        let strideStep = totalCount > 72 ? 2 : 1
        var frames: [CGImage] = []
        var delays: [TimeInterval] = []

        let thumbOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 360,
            kCGImageSourceCreateThumbnailWithTransform: true
        ]

        for idx in stride(from: 0, to: totalCount, by: strideStep) {
            let cgImg = CGImageSourceCreateThumbnailAtIndex(source, idx, thumbOptions as CFDictionary)
                ?? CGImageSourceCreateImageAtIndex(source, idx, nil)
            guard let validImg = cgImg else { continue }
            let frameDelay = Self.frameDuration(at: idx, source: source) * Double(strideStep)
            frames.append(validImg)
            delays.append(frameDelay)
        }

        guard !frames.isEmpty else { return nil }
        if frames.count == 1 {
            return DecodedImagePayload(frames: frames, frameDelays: [0.0], keyTimes: [0.0], totalDuration: 0)
        }

        let totalDuration = delays.reduce(0, +)
        var keyTimes: [NSNumber] = []
        var elapsed: Double = 0.0
        for d in delays {
            keyTimes.append(NSNumber(value: elapsed / max(totalDuration, 0.05)))
            elapsed += d
        }

        return DecodedImagePayload(
            frames: frames,
            frameDelays: delays,
            keyTimes: keyTimes,
            totalDuration: max(totalDuration, 0.1)
        )
    }

    private static func frameDuration(at index: Int, source: CGImageSource) -> Double {
        var delay = 0.07
        guard let props = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] else {
            return delay
        }

        if let gifDict = props[kCGImagePropertyGIFDictionary] as? [CFString: Any] {
            if let unclamped = gifDict[kCGImagePropertyGIFUnclampedDelayTime] as? Double, unclamped > 0.01 {
                delay = unclamped
            } else if let clamped = gifDict[kCGImagePropertyGIFDelayTime] as? Double, clamped > 0.01 {
                delay = clamped
            }
        } else if let webpDict = props[kCGImagePropertyWebPDictionary] as? [CFString: Any] {
            if let unclamped = webpDict[kCGImagePropertyWebPUnclampedDelayTime] as? Double, unclamped > 0.01 {
                delay = unclamped
            } else if let clamped = webpDict[kCGImagePropertyWebPDelayTime] as? Double, clamped > 0.01 {
                delay = clamped
            }
        }

        if delay < 0.025 {
            delay = 0.07
        }
        return delay
    }
}

/// CoreAnimation layer-backed container view (`wantsUpdateLayer = true`) that:
/// 1. Displays the FULL image scaled proportionally (`contentsGravity = .resizeAspect`)
/// 2. Loops multi-frame animated GIFs/WebPs via `RunLoop.main` (`.common` mode) + `needsDisplay = true`
/// 3. Supports both 1-click Auto-Paste AND native macOS Drag-and-Drop (`NSDraggingSource`) to any app or folder!
final class AspectFitImageContainerView: NSView, NSDraggingSource {
    var currentItem: ImageResult?
    var onSelect: (() -> Void)?

    private var currentFrames: [CGImage] = []
    private var currentDelays: [TimeInterval] = []
    private var currentFrameIndex: Int = 0
    private var frameTimer: Timer?
    private var mouseDownPoint: NSPoint?
    private var didStartDrag: Bool = false

    override var wantsUpdateLayer: Bool { true }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.masksToBounds = true
        layer?.contentsGravity = .resizeAspect
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        frameTimer?.invalidate()
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: NSView.noIntrinsicMetric)
    }

    override func updateLayer() {
        guard let layer = self.layer else { return }
        layer.contentsGravity = .resizeAspect
        layer.masksToBounds = true
        if currentFrames.indices.contains(currentFrameIndex) {
            layer.contents = currentFrames[currentFrameIndex]
        } else {
            layer.contents = nil
        }
    }

    private func stopFrameTimer() {
        frameTimer?.invalidate()
        frameTimer = nil
    }

    func clearImage() {
        stopFrameTimer()
        currentFrames = []
        currentDelays = []
        currentFrameIndex = 0
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        self.layer?.contents = nil
        CATransaction.commit()
        self.needsDisplay = true
    }

    func applyDecodedPayload(_ payload: DecodedImagePayload) {
        stopFrameTimer()
        guard let first = payload.frames.first else {
            clearImage()
            return
        }

        currentFrames = payload.frames
        currentDelays = payload.frameDelays
        currentFrameIndex = 0

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        self.layer?.contentsGravity = .resizeAspect
        self.layer?.contents = first
        CATransaction.commit()
        self.needsDisplay = true

        if payload.isAnimated {
            scheduleNextFrameTick()
        }
    }

    private func scheduleNextFrameTick() {
        guard currentFrames.count > 1 else { return }
        let delay = currentDelays.indices.contains(currentFrameIndex)
            ? max(currentDelays[currentFrameIndex], 0.03)
            : 0.07

        let timer = Timer(timeInterval: delay, repeats: false) { [weak self] _ in
            guard let self = self, self.currentFrames.count > 1 else { return }
            self.currentFrameIndex = (self.currentFrameIndex + 1) % self.currentFrames.count
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            self.layer?.contentsGravity = .resizeAspect
            self.layer?.contents = self.currentFrames[self.currentFrameIndex]
            CATransaction.commit()
            self.needsDisplay = true
            self.scheduleNextFrameTick()
        }
        RunLoop.main.add(timer, forMode: .common)
        self.frameTimer = timer
    }

    // MARK: - Click & Native Drag-and-Drop Support (NSDraggingSource)

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool {
        true
    }

    override func mouseDown(with event: NSEvent) {
        mouseDownPoint = convert(event.locationInWindow, from: nil)
        didStartDrag = false

        // Pre-warm full-resolution image/GIF raw bytes in background in case user drags or clicks
        if let item = currentItem,
           item.fullImageUrl != item.thumbnailUrl,
           RemoteImageCache.shared.data(for: item.fullImageUrl) == nil {
            let fullUrl = item.fullImageUrl
            Task.detached(priority: .userInitiated) {
                _ = await RemoteImageCache.shared.prefetchData(urlStr: fullUrl, timeout: 6.0)
            }
        }
    }

    override func mouseDragged(with event: NSEvent) {
        guard !didStartDrag,
              let startPt = mouseDownPoint,
              let item = currentItem else { return }

        let curPt = convert(event.locationInWindow, from: nil)
        let dist = hypot(curPt.x - startPt.x, curPt.y - startPt.y)
        guard dist >= 4.0 else { return }
        didStartDrag = true

        // Grab cached full data or cached thumbnail data immediately
        guard let rawData = RemoteImageCache.shared.data(for: item.fullImageUrl)
            ?? RemoteImageCache.shared.data(for: item.thumbnailUrl) else {
            return
        }

        let prepared = ImageCommitService.prepareImageDataAndExtension(rawData: rawData)
        guard let fileURL = ImageCommitService.saveToSharedTempDirectory(data: prepared.data, ext: prepared.ext) else {
            return
        }

        // Write both file URL and image/GIF data onto the pasteboard item so Finder, Messages, Slack, Discord, and browsers accept the drop!
        let draggingItem = NSDraggingItem(pasteboardWriter: fileURL as NSURL)

        let dragPreviewImage: NSImage
        if currentFrames.indices.contains(currentFrameIndex) {
            let cg = currentFrames[currentFrameIndex]
            dragPreviewImage = NSImage(cgImage: cg, size: bounds.size)
        } else if let img = NSImage(data: prepared.data) {
            dragPreviewImage = img
        } else {
            dragPreviewImage = NSImage(size: bounds.size)
        }

        draggingItem.setDraggingFrame(bounds, contents: dragPreviewImage)
        beginDraggingSession(with: [draggingItem], event: event, source: self)
        KeyboardyPrefs.shared.recordImageInserted()
    }

    override func mouseUp(with event: NSEvent) {
        defer {
            mouseDownPoint = nil
            didStartDrag = false
        }
        guard !didStartDrag, let startPt = mouseDownPoint else { return }
        let endPt = convert(event.locationInWindow, from: nil)
        if bounds.contains(endPt) || hypot(endPt.x - startPt.x, endPt.y - startPt.y) < 8.0 {
            onSelect?()
        }
    }

    func draggingSession(_ session: NSDraggingSession, sourceOperationMaskFor context: NSDraggingContext) -> NSDragOperation {
        .copy
    }
}

struct AnimatedRemoteImageView: NSViewRepresentable {
    let item: ImageResult
    var onSelect: (() -> Void)? = nil

    func makeNSView(context: Context) -> AspectFitImageContainerView {
        let container = AspectFitImageContainerView()
        container.currentItem = item
        container.onSelect = onSelect
        context.coordinator.containerView = container
        context.coordinator.load(item: item)
        return container
    }

    func updateNSView(_ nsView: AspectFitImageContainerView, context: Context) {
        nsView.currentItem = item
        nsView.onSelect = onSelect
        context.coordinator.containerView = nsView
        context.coordinator.load(item: item)
    }

    static func dismantleNSView(_ nsView: AspectFitImageContainerView, coordinator: Coordinator) {
        coordinator.cancel()
        nsView.clearImage()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    @MainActor
    final class Coordinator {
        weak var containerView: AspectFitImageContainerView?
        private var currentFullUrl: String?
        private var currentThumbUrl: String?
        private var loadTask: Task<Void, Never>?

        func cancel() {
            loadTask?.cancel()
            loadTask = nil
            currentFullUrl = nil
            currentThumbUrl = nil
        }

        func load(item: ImageResult) {
            guard currentFullUrl != item.fullImageUrl || currentThumbUrl != item.thumbnailUrl else { return }
            currentFullUrl = item.fullImageUrl
            currentThumbUrl = item.thumbnailUrl
            loadTask?.cancel()
            containerView?.clearImage()

            let thumbUrl = item.thumbnailUrl
            let fullUrl = item.fullImageUrl

            // 1. If full image/GIF is already decoded in memory, display it immediately!
            if let cachedFullPayload = RemoteImageCache.shared.decodedPayload(for: fullUrl) {
                containerView?.applyDecodedPayload(cachedFullPayload)
                return
            }

            // For transparent PNG/WebP results, load fullUrl first so alpha transparency renders cleanly (thumbnails flatten to opaque JPEG).
            if item.isTransparent {
                loadTask = Task { [weak self] in
                    var fullSucceeded = false
                    if let fullPayload = await RemoteImageCache.shared.fetchAndDecode(urlStr: fullUrl, timeout: 6.5) {
                        fullSucceeded = true
                        if !Task.isCancelled, let self = self, self.currentFullUrl == fullUrl {
                            self.containerView?.applyDecodedPayload(fullPayload)
                        }
                    }
                    guard !Task.isCancelled, !fullSucceeded, fullUrl != thumbUrl else { return }
                    if let thumbPayload = await RemoteImageCache.shared.fetchAndDecode(urlStr: thumbUrl, timeout: 4.5) {
                        if !Task.isCancelled, let self = self, self.currentFullUrl == fullUrl {
                            self.containerView?.applyDecodedPayload(thumbPayload)
                        }
                    }
                }
                return
            }

            // 2. If thumbnail is already decoded in memory, display it immediately!
            if let cachedThumbPayload = RemoteImageCache.shared.decodedPayload(for: thumbUrl) {
                containerView?.applyDecodedPayload(cachedThumbPayload)
                // If thumbnail is ALREADY animated (like all Tenor AAAAM GIFs!) or item isn't an animated GIF, we're done!
                if cachedThumbPayload.isAnimated || !item.isAnimatedGif || fullUrl == thumbUrl {
                    return
                }
            }

            // 3. Download thumbnail first; only fetch fullUrl in the grid if needed for GIF animation or fallback!
            loadTask = Task { [weak self] in
                var thumbSucceeded = false
                var thumbIsAnimated = false

                if let existingThumb = RemoteImageCache.shared.decodedPayload(for: thumbUrl) {
                    thumbSucceeded = true
                    thumbIsAnimated = existingThumb.isAnimated
                } else if let thumbPayload = await RemoteImageCache.shared.fetchAndDecode(urlStr: thumbUrl, timeout: 4.5) {
                    thumbSucceeded = true
                    thumbIsAnimated = thumbPayload.isAnimated
                    if !Task.isCancelled,
                       let self = self,
                       self.currentFullUrl == fullUrl {
                        self.containerView?.applyDecodedPayload(thumbPayload)
                    }
                }

                guard !Task.isCancelled else { return }

                // Fetch fullUrl only if:
                // - The item is an animated GIF whose thumbnail was static (e.g. Bing OIP static thumb), OR
                // - The thumbnail download failed completely.
                let needsFullFetch = (item.isAnimatedGif && !thumbIsAnimated && fullUrl != thumbUrl)
                    || (!thumbSucceeded && fullUrl != thumbUrl)

                if needsFullFetch {
                    if let fullPayload = await RemoteImageCache.shared.fetchAndDecode(urlStr: fullUrl, timeout: 7.5) {
                        guard !Task.isCancelled,
                              let self = self,
                              self.currentFullUrl == fullUrl else { return }
                        if fullPayload.isAnimated || !thumbSucceeded {
                            self.containerView?.applyDecodedPayload(fullPayload)
                        }
                    }
                }
            }
        }
    }
}
