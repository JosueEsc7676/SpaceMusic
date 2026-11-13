import SwiftUI

enum Pestana: Hashable {
    case inicio, buscar, biblioteca, mensajes, perfil
}

/// Pantallas a las que se puede entrar desde cualquier lugar.
enum Destino: Hashable {
    case album(String)
    case artista(String)
    case playlist(Int)
    case genero(String)
    case mix(id: String, titulo: String)
    case perfil(String)
    case chat(String)
    case personas(username: String, seguidores: Bool)
    case favoritos
    case historial
    case descargas
    case estadisticas
    case novedades
    case notificaciones
    case cuenta
    case admin
    case subir
    case soporte, generosAdmin, bibliotecaAdmin, resumenAdmin
    case usuarioAdmin(Int)
    case albumes
    case artistas
    // Funciones de la web 3.x
    case juntos
    case salaJuntos(Int)
    case rutas
    case descubrir
    case coincidimos
    case sesionCoincidimos(Int)
    case dedicatorias
    case dedicatoria(Int)
    case preparar
    case resumen
}

/// Pestaña elegida y pantallas abiertas en cada pestaña.
@MainActor
@Observable
final class Navegacion {
    static let compartida = Navegacion()

    var pestana: Pestana = .inicio
    var rutas: [Pestana: NavigationPath] = [:]
    var reproductorAbierto = false
    /// Hoja de la versión nueva de la app (aviso de actualización).
    var verActualizacion = false

    func ruta(_ p: Pestana) -> Binding<NavigationPath> {
        Binding(get: { self.rutas[p] ?? NavigationPath() }, set: { self.rutas[p] = $0 })
    }

    func reiniciar() {
        pestana = .inicio
        rutas = [:]
        reproductorAbierto = false
    }

    func ir(_ d: Destino) {
        reproductorAbierto = false
        var r = rutas[pestana] ?? NavigationPath()
        r.append(d)
        rutas[pestana] = r
    }

    /// Abre una ruta de la web, por ejemplo "#/u/ana" o "#/playlist/3".
    func abrir(ruta: String) {
        if let u = URL(string: ruta), ["/app/SpaceMusic.ipa", "/app/ios.html"].contains(u.path),
           u.host == nil || u.host == URL(string: Sesion.compartida.servidor)?.host {
            abrir(ruta: "#/actualizacion")
            return
        }
        let limpia = ruta.replacingOccurrences(of: "#", with: "")
        let partes = limpia.split(separator: "?").first.map { $0.split(separator: "/").map(String.init) } ?? []
        let valor = partes.count > 1 ? (partes[1].removingPercentEncoding ?? partes[1]) : nil
        switch partes.first {
        case "u": if let v = valor { ir(.perfil(v)) }
        case "playlist": if let v = valor, let id = Int(v) { ir(.playlist(id)) }
        case "album": if let v = valor { ir(.album(v)) }
        case "artista": if let v = valor { ir(.artista(v)) }
        case "genero": if let v = valor { ir(.genero(v)) }
        case "mensajes":
            pestana = .mensajes
            rutas[.mensajes] = NavigationPath()
            if let v = valor { ir(.chat(v)) }
        case "actualizacion":
            reproductorAbierto = false
            let hojas = Hojas.compartidas
            hojas.agregarAPlaylist = nil; hojas.enviarCancion = nil; hojas.editarCancion = nil
            hojas.dedicar = nil; hojas.reportar = nil; hojas.gustos = false
            // Deja terminar el cierre del reproductor/hojas antes de presentar la descarga.
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(450))
                verActualizacion = true
            }
        case "novedades": ir(.novedades)
        case "perfil": pestana = .perfil
        case "estadisticas": ir(.estadisticas)
        case "favoritos": ir(.favoritos)
        case "mix": if let v = valor { ir(.mix(id: v, titulo: "Mix")) }
        case "descargas": ir(.descargas)
        case "historial": ir(.historial)
        case "albumes": ir(.albumes)
        case "artistas": ir(.artistas)
        case "biblioteca": pestana = .biblioteca
        case "buscar": pestana = .buscar
        case "inicio": pestana = .inicio
        case "subir": if Sesion.compartida.puedeEditar { ir(.subir) }
        case "despertador":
            Task { await Despertador.compartido.sonar() }
        case "juntos": if let v = valor, let id = Int(v) { ir(.salaJuntos(id)) } else { ir(.juntos) }
        case "rutas": ir(.rutas)
        case "descubrir": ir(.descubrir)
        case "coincidimos": if let v = valor, let id = Int(v) { ir(.sesionCoincidimos(id)) } else { ir(.coincidimos) }
        case "dedicatorias": if let v = valor, let id = Int(v) { ir(.dedicatoria(id)) } else { ir(.dedicatorias) }
        case "preparar": ir(.preparar)
        case "resumen": ir(.resumen)
        case "admin":
            guard Sesion.compartida.esAdmin else { return }
            let consulta = URLComponents(string: "https://local/" + limpia)?.queryItems
            switch consulta?.first(where: { $0.name == "tab" })?.value {
            case "soporte": ir(.soporte)
            case "generos": ir(.generosAdmin)
            case "biblioteca": ir(.bibliotecaAdmin)
            default: ir(.admin)
            }
        default: break
        }
    }
}

/// Construye la pantalla de cada destino.
struct VistaDestino: View {
    let destino: Destino

    var body: some View {
        Group {
            switch destino {
            case .album(let n): AlbumVista(nombre: n)
            case .artista(let n): ArtistaVista(nombre: n)
            case .playlist(let id): PlaylistVista(id: id)
            case .genero(let n): GeneroVista(nombre: n)
            case .mix(let id, let t): MixVista(id: id, titulo: t)
            case .perfil(let u): PerfilVista(username: u)
            case .chat(let u): ChatVista(username: u)
            case .personas(let u, let s): PersonasVista(username: u, seguidores: s)
            case .favoritos: FavoritosVista()
            case .historial: HistorialVista()
            case .descargas: DescargasVista()
            case .estadisticas: EstadisticasVista()
            case .novedades: NovedadesVista()
            case .notificaciones: NotificacionesVista()
            case .cuenta: CuentaVista()
            case .admin: AdminVista()
            case .subir: SubirVista()
            case .soporte: SoporteVista()
            case .generosAdmin: GenerosAdminVista()
            case .bibliotecaAdmin: BibliotecaAdminVista()
            case .resumenAdmin: ResumenAdminVista()
            case .usuarioAdmin(let id): UsuarioAdminVista(id: id)
            case .albumes: TodosAlbumesVista()
            case .artistas: TodosArtistasVista()
            case .juntos: JuntosVista()
            case .salaJuntos(let id): JuntosVista(id: id)
            case .rutas: RutasVista()
            case .descubrir: DescubrirVista()
            case .coincidimos: CoincidimosVista()
            case .sesionCoincidimos(let id): SesionCoincidimosVista(id: id)
            case .dedicatorias: DedicatoriasVista()
            case .dedicatoria(let id): DedicatoriaVista(id: id)
            case .preparar: PrepararVista()
            case .resumen: ResumenVista()
            }
        }
    }
}

extension View {
    func destinos() -> some View {
        // .editor: el botón Atrás muestra solo la flecha (antes salía, por ejemplo, «‹ Buenos días»).
        navigationDestination(for: Destino.self) { VistaDestino(destino: $0).toolbarRole(.editor) }
    }
}
