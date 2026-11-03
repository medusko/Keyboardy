// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "KeyboardyMac",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "Keyboardy", targets: ["KeyboardyApp"]),
        .library(name: "KeyboardyCore", targets: ["KeyboardyCore"])
    ],
    targets: [
        .target(
            name: "KeyboardyCore",
            path: "Sources/KeyboardyCore"
        ),
        .executableTarget(
            name: "KeyboardyApp",
            dependencies: ["KeyboardyCore"],
            path: "Sources/KeyboardyApp"
        ),
        .testTarget(
            name: "KeyboardyCoreTests",
            dependencies: ["KeyboardyCore"],
            path: "Tests/KeyboardyCoreTests"
        )
    ]
)
