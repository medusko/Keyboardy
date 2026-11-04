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
}

private final class DecodedImagePayloadBox: NSObject {
    let payload: DecodedImagePayload
    init(_ payload: DecodedImagePayload) {
        self.payload = payload
    }
}

final class RemoteImageCache: @unchecked Sendable {
    static let shared = RemoteImageCache()
    private let dataCache = NSCache<NSString, NSData>()
    private let payloadCache = NSCache<NSString, DecodedImagePayloadBox>()
    private let lock = NSLock()
    private var inFlightTasks: [String: Task<Data?, Never>] = [:]

    private init() {
        dataCache.countLimit = 250
        dataCache.totalCostLimit = 180 * 1024 * 1024 // 180 MB
        payloadCache.countLimit = 120
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
            payloadCache.setObject(DecodedImagePayloadBox(decoded), forKey: url as NSString)
            return decoded
        }
        return nil
    }

    func storeDecodedPayload(_ payload: DecodedImagePayload, for url: String) {
        payloadCache.setObject(DecodedImagePayloadBox(payload), forKey: url as NSString)
    }

    /// Deduplicated download + decode so progressive grid updates never cancel an in-flight GIF download.
    func fetchAndDecode(urlStr: String, timeout: TimeInterval) async -> DecodedImagePayload? {
        if let existing = decodedPayload(for: urlStr) {
            return existing
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
        lock.withLock {
            inFlightTasks.removeValue(forKey: urlStr)
        }

        guard let data = fetchedData, data.count < 20 * 1024 * 1024 else {
            return nil
        }
        store(data, for: urlStr)

        let decoded = await Task.detached(priority: .userInitiated) {
            Self.decodePayload(from: data)
        }.value

        if let decoded = decoded {
            storeDecodedPayload(decoded, for: urlStr)
        }
        return decoded
    }

    private static func performNetworkFetch(urlStr: String, timeout: TimeInterval) async -> Data? {
        guard urlStr.hasPrefix("http"), let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = timeout
        req.setValue(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            forHTTPHeaderField: "User-Agent"
        )
        req.setValue("image/gif,image/webp,image/apng,image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await URLSession.shared.data(for: req)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                return nil
            }
            return data
        } catch {
            return nil
        }
    }

    /// Decodes either a single-frame image or a multi-frame animated GIF / WebP using ImageIO (`CGImageSource`).
    static func decodePayload(from data: Data) -> DecodedImagePayload? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }
        let totalCount = CGImageSourceGetCount(source)
        guard totalCount > 0 else { return nil }

        if totalCount == 1 {
            guard let cgImg = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
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

/// CoreAnimation layer-hosted container view that displays the FULL image (`contentsGravity = .resizeAspect`)
/// and plays multi-frame animated GIFs/WebPs in a continuous deterministic loop via a `RunLoop.main` `.common` timer.
final class AspectFitImageContainerView: NSView {
    private let imageLayer = CALayer()
    private var currentFrames: [CGImage] = []
    private var currentDelays: [TimeInterval] = []
    private var currentFrameIndex: Int = 0
    private var frameTimer: Timer?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.masksToBounds = true

        imageLayer.contentsGravity = .resizeAspect
        imageLayer.masksToBounds = true
        imageLayer.actions = [
            "contents": NSNull(),
            "bounds": NSNull(),
            "position": NSNull()
        ]
        layer?.addSublayer(imageLayer)
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

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        imageLayer.frame = bounds.insetBy(dx: 4, dy: 4)
        CATransaction.commit()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window == nil {
            stopFrameTimer()
        } else if currentFrames.count > 1 && frameTimer == nil {
            scheduleNextFrameTick()
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
        imageLayer.contents = nil
        CATransaction.commit()
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
        imageLayer.contents = first
        CATransaction.commit()

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
            self.imageLayer.contents = self.currentFrames[self.currentFrameIndex]
            CATransaction.commit()
            self.scheduleNextFrameTick()
        }
        RunLoop.main.add(timer, forMode: .common)
        self.frameTimer = timer
    }
}

struct AnimatedRemoteImageView: NSViewRepresentable {
    let item: ImageResult

    func makeNSView(context: Context) -> AspectFitImageContainerView {
        let container = AspectFitImageContainerView()
        context.coordinator.containerView = container
        context.coordinator.load(item: item)
        return container
    }

    func updateNSView(_ nsView: AspectFitImageContainerView, context: Context) {
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

            // 2. Otherwise show cached thumbnail immediately while full image/GIF downloads
            var displayedAnimatedAlready = false
            if let cachedThumbPayload = RemoteImageCache.shared.decodedPayload(for: thumbUrl) {
                containerView?.applyDecodedPayload(cachedThumbPayload)
                displayedAnimatedAlready = cachedThumbPayload.isAnimated
            }

            // 3. Download thumbnail (fast preview) and full URL (for GIF animation or high-res fallback) in parallel
            loadTask = Task { [weak self] in
                async let fullPayloadTask: DecodedImagePayload? = (fullUrl != thumbUrl)
                    ? RemoteImageCache.shared.fetchAndDecode(urlStr: fullUrl, timeout: 7.5)
                    : nil

                if RemoteImageCache.shared.decodedPayload(for: thumbUrl) == nil {
                    if let thumbPayload = await RemoteImageCache.shared.fetchAndDecode(urlStr: thumbUrl, timeout: 4.5) {
                        if !Task.isCancelled,
                           let self = self,
                           self.currentFullUrl == fullUrl {
                            self.containerView?.applyDecodedPayload(thumbPayload)
                            displayedAnimatedAlready = thumbPayload.isAnimated
                        }
                    }
                }

                if let fullPayload = await fullPayloadTask {
                    guard !Task.isCancelled,
                          let self = self,
                          self.currentFullUrl == fullUrl else { return }
                    if fullPayload.isAnimated || !displayedAnimatedAlready {
                        self.containerView?.applyDecodedPayload(fullPayload)
                    }
                }
            }
        }
    }
}
