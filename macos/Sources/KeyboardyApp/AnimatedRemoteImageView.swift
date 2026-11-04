import AppKit
import ImageIO
import KeyboardyCore
import QuartzCore
import SwiftUI

struct DecodedImagePayload {
    let frames: [CGImage]
    let keyTimes: [NSNumber]
    let totalDuration: CFTimeInterval

    var isAnimated: Bool {
        frames.count > 1 && totalDuration > 0
    }
}

final class RemoteImageCache {
    static let shared = RemoteImageCache()
    private let dataCache = NSCache<NSString, NSData>()

    private init() {
        dataCache.countLimit = 200
        dataCache.totalCostLimit = 150 * 1024 * 1024 // 150 MB
    }

    func data(for url: String) -> Data? {
        dataCache.object(forKey: url as NSString) as Data?
    }

    func store(_ data: Data, for url: String) {
        dataCache.setObject(data as NSData, forKey: url as NSString, cost: data.count)
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
            return DecodedImagePayload(frames: [cgImg], keyTimes: [0.0], totalDuration: 0)
        }

        // For very long GIFs (> 72 frames), stride by 2 to keep GPU memory and decode time ultra-fast
        let strideStep = totalCount > 72 ? 2 : 1
        var frames: [CGImage] = []
        var delays: [Double] = []

        for idx in stride(from: 0, to: totalCount, by: strideStep) {
            guard let cgImg = CGImageSourceCreateImageAtIndex(source, idx, nil) else { continue }
            let frameDelay = Self.frameDuration(at: idx, source: source) * Double(strideStep)
            frames.append(cgImg)
            delays.append(frameDelay)
        }

        guard !frames.isEmpty else { return nil }
        if frames.count == 1 {
            return DecodedImagePayload(frames: frames, keyTimes: [0.0], totalDuration: 0)
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
            keyTimes: keyTimes,
            totalDuration: max(totalDuration, 0.1)
        )
    }

    private static func frameDuration(at index: Int, source: CGImageSource) -> Double {
        var delay = 0.08
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
            delay = 0.08
        }
        return delay
    }
}

/// CoreAnimation layer-hosted container view that displays the FULL image (`contentsGravity = .resizeAspect`)
/// and plays multi-frame animated GIFs/WebPs in a continuous hardware-accelerated loop (`CAKeyframeAnimation`).
final class AspectFitImageContainerView: NSView {
    private let imageLayer = CALayer()

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

    func clearImage() {
        imageLayer.removeAnimation(forKey: "gifLoop")
        imageLayer.contents = nil
    }

    func applyDecodedPayload(_ payload: DecodedImagePayload) {
        imageLayer.removeAnimation(forKey: "gifLoop")
        guard let first = payload.frames.first else {
            imageLayer.contents = nil
            return
        }
        imageLayer.contents = first

        if payload.isAnimated {
            let anim = CAKeyframeAnimation(keyPath: "contents")
            anim.values = payload.frames
            anim.keyTimes = payload.keyTimes
            anim.duration = payload.totalDuration
            anim.calculationMode = .discrete
            anim.repeatCount = .infinity
            anim.isRemovedOnCompletion = false
            imageLayer.add(anim, forKey: "gifLoop")
        }
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

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    @MainActor
    final class Coordinator {
        weak var containerView: AspectFitImageContainerView?
        private var currentFullUrl: String?
        private var currentThumbUrl: String?
        private var loadTask: Task<Void, Never>?

        func load(item: ImageResult) {
            guard currentFullUrl != item.fullImageUrl || currentThumbUrl != item.thumbnailUrl else { return }
            currentFullUrl = item.fullImageUrl
            currentThumbUrl = item.thumbnailUrl
            loadTask?.cancel()
            containerView?.clearImage()

            let thumbUrl = item.thumbnailUrl
            let fullUrl = item.fullImageUrl

            // 1. Check if full image/GIF is already cached
            if let cachedFull = RemoteImageCache.shared.data(for: fullUrl),
               let payload = RemoteImageCache.decodePayload(from: cachedFull) {
                containerView?.applyDecodedPayload(payload)
                return
            }

            // 2. Otherwise show cached thumbnail immediately while full image/GIF downloads
            var displayedAnimatedAlready = false
            if let cachedThumb = RemoteImageCache.shared.data(for: thumbUrl),
               let thumbPayload = RemoteImageCache.decodePayload(from: cachedThumb) {
                containerView?.applyDecodedPayload(thumbPayload)
                displayedAnimatedAlready = thumbPayload.isAnimated
            }

            loadTask = Task { [weak self] in
                let shouldFetchFullForAnimation = item.isAnimatedGif ||
                    fullUrl.lowercased().contains(".gif") ||
                    thumbUrl.lowercased().contains(".gif")

                // Start full GIF download in parallel with thumbnail so animations start as fast as possible!
                async let fullDataTask: Data? = (shouldFetchFullForAnimation && fullUrl != thumbUrl)
                    ? Self.fetchData(urlStr: fullUrl, timeout: 6.5)
                    : nil

                if RemoteImageCache.shared.data(for: thumbUrl) == nil {
                    if let thumbData = await Self.fetchData(urlStr: thumbUrl, timeout: 4.0) {
                        RemoteImageCache.shared.store(thumbData, for: thumbUrl)
                        if !Task.isCancelled,
                           let self = self,
                           self.currentFullUrl == fullUrl,
                           let thumbPayload = await Self.decodeOffMainThread(thumbData) {
                            self.containerView?.applyDecodedPayload(thumbPayload)
                            displayedAnimatedAlready = thumbPayload.isAnimated
                        }
                    }
                }

                if let fullData = await fullDataTask, fullData.count < 15 * 1024 * 1024 {
                    RemoteImageCache.shared.store(fullData, for: fullUrl)
                    guard !Task.isCancelled else { return }
                    if let self = self,
                       self.currentFullUrl == fullUrl,
                       let fullPayload = await Self.decodeOffMainThread(fullData) {
                        if fullPayload.isAnimated || !displayedAnimatedAlready {
                            self.containerView?.applyDecodedPayload(fullPayload)
                        }
                    }
                }
            }
        }

        private nonisolated static func decodeOffMainThread(_ data: Data) async -> DecodedImagePayload? {
            RemoteImageCache.decodePayload(from: data)
        }

        private nonisolated static func fetchData(urlStr: String, timeout: TimeInterval) async -> Data? {
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
    }
}
