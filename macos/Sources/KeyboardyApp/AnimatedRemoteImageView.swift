import AppKit
import KeyboardyCore
import SwiftUI

final class RemoteImageCache {
    static let shared = RemoteImageCache()
    private let cache = NSCache<NSString, NSData>()

    private init() {
        cache.countLimit = 180
        cache.totalCostLimit = 120 * 1024 * 1024 // 120 MB
    }

    func data(for url: String) -> Data? {
        cache.object(forKey: url as NSString) as Data?
    }

    func store(_ data: Data, for url: String) {
        cache.setObject(data as NSData, forKey: url as NSString, cost: data.count)
    }
}

/// Custom container NSView that enforces zero intrinsic content size and resizes its child NSImageView
/// strictly to fit inside the tile bounds, so the FULL image is always shown (aspect-fit) without cropping.
final class AspectFitImageContainerView: NSView {
    let imageView = NSImageView()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.masksToBounds = true

        imageView.imageScaling = .scaleProportionallyUpOrDown
        imageView.imageAlignment = .alignCenter
        imageView.animates = true
        imageView.canDrawSubviewsIntoLayer = true
        imageView.setContentCompressionResistancePriority(.init(1), for: .horizontal)
        imageView.setContentCompressionResistancePriority(.init(1), for: .vertical)
        imageView.setContentHuggingPriority(.init(1), for: .horizontal)
        imageView.setContentHuggingPriority(.init(1), for: .vertical)
        addSubview(imageView)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: NSView.noIntrinsicMetric)
    }

    override func layout() {
        super.layout()
        // Inset by 4pt so the full image is clearly visible from edge to edge inside the tile
        imageView.frame = bounds.insetBy(dx: 4, dy: 4)
    }
}

struct AnimatedRemoteImageView: NSViewRepresentable {
    let item: ImageResult

    func makeNSView(context: Context) -> AspectFitImageContainerView {
        let container = AspectFitImageContainerView()
        context.coordinator.imageView = container.imageView
        context.coordinator.load(item: item)
        return container
    }

    func updateNSView(_ nsView: AspectFitImageContainerView, context: Context) {
        context.coordinator.imageView = nsView.imageView
        context.coordinator.load(item: item)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    @MainActor
    final class Coordinator {
        weak var imageView: NSImageView?
        private var currentFullUrl: String?
        private var currentThumbUrl: String?
        private var loadTask: Task<Void, Never>?

        func load(item: ImageResult) {
            guard currentFullUrl != item.fullImageUrl || currentThumbUrl != item.thumbnailUrl else { return }
            currentFullUrl = item.fullImageUrl
            currentThumbUrl = item.thumbnailUrl
            loadTask?.cancel()
            imageView?.image = nil

            let thumbUrl = item.thumbnailUrl
            let fullUrl = item.fullImageUrl

            if let cachedFull = RemoteImageCache.shared.data(for: fullUrl),
               let img = NSImage(data: cachedFull) {
                imageView?.image = img
                imageView?.animates = true
                return
            }

            if let cachedThumb = RemoteImageCache.shared.data(for: thumbUrl),
               let img = NSImage(data: cachedThumb) {
                imageView?.image = img
                imageView?.animates = true
            }

            loadTask = Task { [weak self] in
                // 1. Load fast thumbnail first
                if RemoteImageCache.shared.data(for: thumbUrl) == nil,
                   let thumbData = await Self.fetchData(urlStr: thumbUrl) {
                    RemoteImageCache.shared.store(thumbData, for: thumbUrl)
                    guard !Task.isCancelled else { return }
                    if let self = self, self.currentFullUrl == item.fullImageUrl, let img = NSImage(data: thumbData) {
                        self.imageView?.image = img
                        self.imageView?.animates = true
                    }
                }

                // 2. If it's an animated GIF and fullUrl differs from thumbUrl, upgrade to the animated GIF preview
                if item.isAnimatedGif && fullUrl != thumbUrl {
                    guard !Task.isCancelled else { return }
                    if let gifData = await Self.fetchData(urlStr: fullUrl),
                       gifData.count < 12 * 1024 * 1024 {
                        RemoteImageCache.shared.store(gifData, for: fullUrl)
                        guard !Task.isCancelled else { return }
                        if let self = self, self.currentFullUrl == item.fullImageUrl, let animImg = NSImage(data: gifData) {
                            self.imageView?.image = animImg
                            self.imageView?.animates = true
                        }
                    }
                }
            }
        }

        private nonisolated static func fetchData(urlStr: String) async -> Data? {
            guard urlStr.hasPrefix("http"), let url = URL(string: urlStr) else { return nil }
            var req = URLRequest(url: url)
            req.timeoutInterval = 4.0
            req.setValue(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                forHTTPHeaderField: "User-Agent"
            )
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
