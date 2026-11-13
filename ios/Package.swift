// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ModelosSpaceMusic",
    platforms: [.macOS(.v13), .iOS(.v17)],
    products: [.library(name: "ModelosSpaceMusic", targets: ["ModelosSpaceMusic"])],
    targets: [
        .target(name: "ModelosSpaceMusic", path: "SpaceMusic/Modelos", exclude: ["Biblioteca.swift", "CancionesEditadas.swift"], sources: ["Modelos.swift", "LectorLRC.swift", "ExperienciasModelos.swift", "InvitacionSyncPlay.swift"]),
        .testTarget(name: "ModelosSpaceMusicTests", dependencies: ["ModelosSpaceMusic"], path: "Pruebas")
    ]
)
