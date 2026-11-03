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

struct AnimatedRemoteImageView: NSViewRepresentable {
    let item: ImageResult

    func makeNSView(context: Context) -> NSView {
        let container = NSView()
        container.wantsLayer = true
        container.layer?.masksToBounds = true

        let imageView = NSImageView()
        imageView.imageScaling = .scaleProportionallyUpOrDown
        imageView.animates = true
        imageView.canDrawSubviewsIntoLayer = true
        imageView.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: container.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: container.bottomAnchor)
        ])

        context.coordinator.imageView = imageView
        context.coordinator.load(item: item)
        return container
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.load(item: item)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    @MainActor
    final class Coordinator {
        weak var imageView: NSImageView?
        private var currentItemId: String?
        private var loadTask: Task<Void, Never>?

        func load(item: ImageResult) {
            guard currentItemId != item.id else { return }
            currentItemId = item.id
            loadTask?.cancel()
            imageView?.image = nil

            // For animated GIFs, prefer loading the full GIF URL if it's a direct .gif so it animates in the grid,
            // while immediately showing the fast thumbnail first!
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
                    if let self = self, self.currentItemId == item.id, let img = NSImage(data: thumbData) {
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
                        if let self = self, self.currentItemId == item.id, let animImg = NSImage(data: gifData) {
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
