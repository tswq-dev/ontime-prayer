// swift-tools-version:5.5
// Swift Package Manager: .package(url: "https://github.com/tswq-dev/ontime-prayer", from: "1.0.0"), then `import OnTimePrayer`.
import PackageDescription

let package = Package(
    name: "OnTimePrayer",
    products: [.library(name: "OnTimePrayer", targets: ["OnTimePrayer"])],
    targets: [.target(name: "OnTimePrayer", path: "ports/swift", exclude: ["Verify"])]
)
