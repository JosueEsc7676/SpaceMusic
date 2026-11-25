import ActivityKit

struct LetraActividad: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var cancion: String
        var artista: String
        var verso: String
        var siguiente: String
        var pausada: Bool
    }
    let sesion: String
}

@MainActor enum LetraActividadService {
    static var cerradas = false
    static func finalizarAnteriores() {
        guard !cerradas else { return }
        cerradas = true
        for anterior in Activity<LetraActividad>.activities {
            Task { await anterior.end(nil, dismissalPolicy: .immediate) }
        }
    }
}
