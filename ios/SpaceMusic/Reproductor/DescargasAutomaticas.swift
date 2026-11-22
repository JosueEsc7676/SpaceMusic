import UIKit

/// Descargas inteligentes: con Wi-Fi y el iPhone cargando, una vez al día se guardan para escuchar sin conexión
/// tus canciones más escuchadas, el Mix del día y lo nuevo de tus artistas (como la app de Android).
@MainActor
enum DescargasAutomaticas {
    private static let clave = "descargasAutomaticas"
    private static let ultima = "descargasAutomaticasUltima"
    static let maximo = 60

    static var activas: Bool {
        get { UserDefaults.standard.object(forKey: clave) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: clave) }
    }

    private static var corriendo = false

    /// Revisa si toca descargar (como mucho una vez cada 20 horas).
    static func revisar(forzar: Bool = false) async {
        guard activas, !corriendo, Sesion.compartida.conectado else { return }
        if !forzar, Date().timeIntervalSince1970 - UserDefaults.standard.double(forKey: ultima) < 20 * 3600 { return }
        UIDevice.current.isBatteryMonitoringEnabled = true
        let bateria = UIDevice.current.batteryState
        guard bateria == .charging || bateria == .full, CalidadAudio.compartida.conWifi else { return }
        corriendo = true
        defer { corriendo = false }
        async let i: Inicio? = try? API.get("/api/inicio", tiempo: 20)
        async let m: [Cancion]? = try? API.get("/api/mixes/diario?cantidad=25", tiempo: 20)
        let (inicio, mix) = await (i, m)
        let top = inicio?.tusFavoritas ?? []
        let misArtistas = Set(top.map { $0.artista.lowercased() })
        let nuevas = (inicio?.recienAgregadas ?? []).filter { misArtistas.contains($0.artista.lowercased()) }
        var vistos = Set<Int>()
        let lista = (top + (mix ?? []) + nuevas).filter { vistos.insert($0.id).inserted && !Descargas.compartido.descargada($0.id) }
        let elegidas = Array(lista.prefix(maximo))
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: ultima)
        guard !elegidas.isEmpty else { return }
        Descargas.compartido.descargar(elegidas)
        Avisos.compartido.mostrar("Guardando \(elegidas.count) canciones para escuchar sin conexión")
    }
}
