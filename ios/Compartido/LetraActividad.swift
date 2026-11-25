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
