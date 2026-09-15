// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RapiDrop",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(
            name: "RapiDrop",
            targets: ["RapiDrop"]
        )
    ],
    targets: [
        .executableTarget(
            name: "RapiDrop",
            path: "Sources",
            linkerSettings: [
                .unsafeFlags([
                    "-Xlinker", "-sectcreate",
                    "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist",
                    "-Xlinker", "Info.plist"
                ])
            ]
        ),
        .testTarget(
            name: "RapiDropTests",
            dependencies: ["RapiDrop"],
            path: "Tests"
        )
    ]
)
