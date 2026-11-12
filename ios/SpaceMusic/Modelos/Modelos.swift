import Foundation

// Los modelos leen el JSON de forma tolerante: si falta un campo o cambia de tipo,
// se usa un valor por defecto en lugar de fallar toda la pantalla.

struct ClaveJSON: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init(_ s: String) { stringValue = s }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}

extension Decoder {
    func campos() throws -> KeyedDecodingContainer<ClaveJSON> { try container(keyedBy: ClaveJSON.self) }
}

extension KeyedDecodingContainer where Key == ClaveJSON {
    func v<T: Decodable>(_ k: String) -> T? {
        (try? decodeIfPresent(T.self, forKey: ClaveJSON(k))) ?? nil
    }
    func texto(_ k: String) -> String? {
        if let s: String = v(k) { return s }
        if let n: Double = v(k) { return n.rounded() == n ? String(Int(n)) : String(n) }
        return nil
    }
    func entero(_ k: String) -> Int? {
        if let n: Int = v(k) { return n }
        if let d: Double = v(k) { return Int(d) }
        if let s: String = v(k) { return Int(s) }
        return nil
    }
    func numero(_ k: String) -> Double? {
        if let d: Double = v(k) { return d }
        if let s: String = v(k) { return Double(s) }
        return nil
    }
    func bool(_ k: String) -> Bool? { v(k) }
    func lista<T: Decodable>(_ k: String) -> [T] {
        guard var c = try? nestedUnkeyedContainer(forKey: ClaveJSON(k)) else { return [] }
        var r: [T] = []
        while !c.isAtEnd {
            if let x = try? c.decode(T.self) { r.append(x) } else { _ = try? c.decode(Ignorar.self) }
        }
        return r
    }
}

/// Se usa para saltar un elemento que no se pudo leer.
struct Ignorar: Decodable { init(from decoder: Decoder) throws {} }

// MARK: - Música

struct Cancion: Codable, Identifiable, Hashable {
    let id: Int
    var nombre: String
    var artista: String
    var album: String
    var genero: String
    var duracionSegundos: Int
    var reproducciones: Int
    var portadaUrl: String?
    var formato: String?
    var anio: Int?
    var numeroPista: Int?
    var letraSincronizada: Bool
    var tieneLetra: Bool
    var fechaAgregada: String?

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        guard let id = c.entero("id") else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Canción sin id"))
        }
        self.id = id
        nombre = c.texto("nombre") ?? "Sin título"
        artista = c.texto("artista") ?? "Desconocido"
        album = c.texto("album") ?? "Single"
        genero = c.texto("genero") ?? "Desconocido"
        duracionSegundos = c.entero("duracionSegundos") ?? 0
        reproducciones = c.entero("reproducciones") ?? 0
        portadaUrl = c.texto("portadaUrl")
        formato = c.texto("formato")
        anio = c.entero("anio")
        numeroPista = c.entero("numeroPista")
        letraSincronizada = c.bool("letraSincronizada") ?? false
        tieneLetra = c.bool("tieneLetra") ?? false
        fechaAgregada = c.texto("fechaAgregada")
    }

    static func == (a: Cancion, b: Cancion) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }

    var duracionTexto: String { Formato.duracion(duracionSegundos) }
}

struct Album: Decodable, Identifiable, Hashable {
    var id: String { nombre + "|" + artista }
    var nombre: String
    var artista: String
    var anio: Int?
    var totalCanciones: Int
    var duracionTotal: Int
    var reproducciones: Int
    var portadaUrl: String?
    var canciones: [Cancion]

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        nombre = c.texto("nombre") ?? "Álbum"
        artista = c.texto("artista") ?? "Desconocido"
        anio = c.entero("anio")
        totalCanciones = c.entero("totalCanciones") ?? 0
        duracionTotal = c.entero("duracionTotal") ?? 0
        reproducciones = c.entero("reproducciones") ?? 0
        portadaUrl = c.texto("portadaUrl")
        canciones = c.lista("canciones")
    }
    static func == (a: Album, b: Album) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

struct Artista: Decodable, Identifiable, Hashable {
    var id: String { nombre }
    var nombre: String
    var totalCanciones: Int
    var totalAlbumes: Int
    var totalReproducciones: Int
    var portadaUrl: String?

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        nombre = c.texto("nombre") ?? "Desconocido"
        totalCanciones = c.entero("totalCanciones") ?? 0
        totalAlbumes = c.entero("totalAlbumes") ?? 0
        totalReproducciones = c.entero("totalReproducciones") ?? 0
        portadaUrl = c.texto("portadaUrl")
    }
}

struct ArtistaDetalle: Decodable {
    var nombre: String
    var totalCanciones: Int
    var totalAlbumes: Int
    var totalReproducciones: Int
    var portadaUrl: String?
    var populares: [Cancion]
    var albumes: [Album]
    var sencillos: [Cancion]
    var canciones: [Cancion]
    var relacionados: [Artista]

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        nombre = c.texto("nombre") ?? "Artista"
        totalCanciones = c.entero("totalCanciones") ?? 0
        totalAlbumes = c.entero("totalAlbumes") ?? 0
        totalReproducciones = c.entero("totalReproducciones") ?? 0
        portadaUrl = c.texto("portadaUrl")
        populares = c.lista("populares")
        albumes = c.lista("albumes")
        sencillos = c.lista("sencillos")
        canciones = c.lista("canciones")
        relacionados = c.lista("relacionados")
    }
}

struct Genero: Decodable, Identifiable, Hashable {
    var id: String { nombre }
    var nombre: String
    var totalCanciones: Int
    var portadaUrl: String?

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        nombre = c.texto("nombre") ?? "Género"
        totalCanciones = c.entero("totalCanciones") ?? 0
        portadaUrl = c.texto("portadaUrl")
    }
}

struct GeneroDetalle: Decodable {
    /// Sección del género («Lo nuevo», «Clásicos»…), como en la web 3.3.
    struct Seccion: Decodable {
        var titulo: String; var sub: String?; var clave: String; var canciones: [Cancion]
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            titulo = c.texto("titulo") ?? ""; sub = c.texto("sub"); clave = c.texto("clave") ?? titulo; canciones = c.lista("canciones")
        }
    }
    var nombre: String
    var clave: String
    var canciones: [Cancion]
    var total: Int
    var artistas: [Artista]
    var secciones: [Seccion]
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        nombre = c.texto("nombre") ?? ""
        clave = c.texto("clave") ?? nombre
        canciones = c.lista("canciones")
        total = c.entero("total") ?? canciones.count
        artistas = c.lista("artistas")
        secciones = c.lista("secciones")
    }
}

/// Página de «Ver todas» de un género (de 50 en 50).
struct PaginaGenero: Decodable {
    var canciones: [Cancion]; var total: Int; var hayMas: Bool
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        canciones = c.lista("canciones"); total = c.entero("total") ?? canciones.count; hayMas = c.bool("hayMas") ?? false
    }
}

struct Playlist: Decodable, Identifiable, Hashable {
    let id: Int
    var nombre: String
    var descripcion: String?
    var portadaUrl: String?
    var portadas: [String]
    var cancionesCount: Int
    var duracionTotal: Int
    var publica: Bool
    var propia: Bool
    var autor: String?
    var autorUsername: String?
    var canciones: [Cancion]
    /// Personas que pueden agregar y quitar canciones (playlist colaborativa).
    var colaboradores: [PerfilMini] = []

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        guard let id = c.entero("id") else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Playlist sin id"))
        }
        self.id = id
        nombre = c.texto("nombre") ?? "Playlist"
        descripcion = c.texto("descripcion")
        portadaUrl = c.texto("portadaUrl")
        portadas = c.lista("portadas")
        cancionesCount = c.entero("cancionesCount") ?? 0
        duracionTotal = c.entero("duracionTotal") ?? 0
        publica = c.bool("publica") ?? false
        propia = c.bool("propia") ?? false
        autor = c.texto("autor")
        autorUsername = c.texto("autorUsername")
        canciones = c.lista("canciones")
        colaboradores = c.lista("colaboradores")
    }
    static func == (a: Playlist, b: Playlist) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

struct Mix: Decodable, Identifiable, Hashable {
    var id: String
    var titulo: String
    var subtitulo: String?
    var total: Int
    var portadas: [String]
    var artistas: [String]

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.texto("id") ?? UUID().uuidString
        titulo = c.texto("titulo") ?? "Mix"
        subtitulo = c.texto("subtitulo")
        total = c.entero("total") ?? 0
        portadas = c.lista("portadas")
        artistas = c.lista("artistas")
    }
}

struct Letra: Decodable {
    var letra: String
    var letraSincronizada: String
    var fuente: String?
    var encontrada: Bool?
    var instrumental: Bool?
    var message: String?

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        letra = c.texto("letra") ?? ""
        letraSincronizada = c.texto("letraSincronizada") ?? ""
        fuente = c.texto("fuente")
        encontrada = c.bool("encontrada")
        instrumental = c.bool("instrumental")
        message = c.texto("message")
    }
}

// MARK: - Pantallas

struct Inicio: Decodable {
    var totalCanciones: Int
    var recientes: [Cancion]
    var mixes: [Mix]
    var tusFavoritas: [Cancion]
    var tendencias: [Cancion]
    var top: [Cancion]
    var recienAgregadas: [Cancion]
    var albumesRecientes: [Album]
    var artistas: [Artista]
    var generos: [Genero]
    var playlistsPublicas: [Playlist]

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        totalCanciones = c.entero("totalCanciones") ?? 0
        recientes = c.lista("recientes")
        mixes = c.lista("mixes")
        tusFavoritas = c.lista("tusFavoritas")
        tendencias = c.lista("tendencias")
        top = c.lista("top")
        recienAgregadas = c.lista("recienAgregadas")
        albumesRecientes = c.lista("albumesRecientes")
        artistas = c.lista("artistas")
        generos = c.lista("generos")
        playlistsPublicas = c.lista("playlistsPublicas")
    }
}

struct Busqueda: Decodable {
    struct Estilo: Decodable {
        var titulo: String
        var canciones: [Cancion]
        var vacio: Bool
        /// Canciones de ese estilo en el servidor (la lista trae solo una muestra).
        var total: Int?
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            titulo = c.texto("titulo") ?? ""
            canciones = c.lista("canciones")
            vacio = c.bool("vacio") ?? false
            total = c.entero("total")
        }
    }
    var canciones: [Cancion]
    var artistas: [Artista]
    var albumes: [Album]
    var playlists: [Playlist]
    var generos: [Genero]
    var usuarios: [PerfilMini]
    var estilo: Estilo?

    var vacia: Bool {
        canciones.isEmpty && artistas.isEmpty && albumes.isEmpty && playlists.isEmpty
            && generos.isEmpty && usuarios.isEmpty && (estilo?.canciones.isEmpty ?? true)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        canciones = c.lista("canciones")
        artistas = c.lista("artistas")
        albumes = c.lista("albumes")
        playlists = c.lista("playlists")
        generos = c.lista("generos")
        usuarios = c.lista("usuarios")
        estilo = c.v("estilo")
    }
}

struct Guardados: Decodable {
    var albumes: [String]
    var playlists: [Int]
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        albumes = c.lista("albumes")
        playlists = c.lista("playlists")
    }
}

// MARK: - Personas

struct Usuario: Codable, Identifiable {
    let id: Int
    var username: String
    var email: String?
    var nombreDisplay: String?
    var fotoPerfil: String?
    var rol: String
    var solicitudColaborador: String?
    var respuestaAdmin: String?
    var emailPendiente: String?
    // Solo en el panel de administración
    var activo: Bool?
    var emailVerificado: Bool?
    var estadoCuenta: String?
    var motivoSolicitud: String?
    var fechaRegistro: String?
    var ultimoLogin: String?

    var nombre: String { (nombreDisplay?.isEmpty == false ? nombreDisplay : nil) ?? username }

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        guard let id = c.entero("id") else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Usuario sin id"))
        }
        self.id = id
        username = c.texto("username") ?? ""
        email = c.texto("email")
        nombreDisplay = c.texto("nombreDisplay")
        fotoPerfil = c.texto("fotoPerfil")
        rol = c.texto("rol") ?? "USER"
        solicitudColaborador = c.texto("solicitudColaborador")
        respuestaAdmin = c.texto("respuestaAdmin")
        emailPendiente = c.texto("emailPendiente")
        activo = c.bool("activo")
        emailVerificado = c.bool("emailVerificado")
        estadoCuenta = c.texto("estadoCuenta")
        motivoSolicitud = c.texto("motivoSolicitud")
        fechaRegistro = c.texto("fechaRegistro")
        ultimoLogin = c.texto("ultimoLogin")
    }
}

struct PerfilMini: Decodable, Identifiable, Hashable {
    let id: Int
    var username: String
    var nombre: String
    var fotoPerfil: String?
    var yoSigo: Bool
    var meSigue: Bool
    var amigos: Bool

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0
        username = c.texto("username") ?? ""
        nombre = c.texto("nombre") ?? c.texto("nombreDisplay") ?? username
        fotoPerfil = c.texto("fotoPerfil")
        yoSigo = c.bool("yoSigo") ?? false
        meSigue = c.bool("meSigue") ?? false
        amigos = c.bool("amigos") ?? false
    }
    static func == (a: PerfilMini, b: PerfilMini) -> Bool { a.username == b.username }
    func hash(into h: inout Hasher) { h.combine(username) }
}

struct Actividad: Decodable {
    var ahora: Bool
    var cancion: Cancion?
    var hace: String?
    var usuario: PerfilMini?
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        ahora = c.bool("ahora") ?? false
        cancion = c.v("cancion")
        hace = c.texto("hace")
        usuario = c.v("usuario")
    }
}

struct Perfil: Decodable {
    struct TopCancion: Decodable {
        var cancion: Cancion?
        var veces: Int
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            cancion = c.v("cancion")
            veces = c.entero("veces") ?? 0
        }
    }
    struct TopArtista: Decodable {
        var artista: String
        var veces: Int
        var portadaUrl: String?
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            artista = c.texto("artista") ?? c.texto("nombre") ?? ""
            veces = c.entero("veces") ?? 0
            portadaUrl = c.texto("portadaUrl")
        }
    }
    struct TopGenero: Decodable {
        var genero: String
        var porcentaje: Double
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            genero = c.texto("genero") ?? c.texto("nombre") ?? ""
            porcentaje = c.numero("porcentaje") ?? 0
        }
    }
    struct Vibra: Decodable {
        var nombre: String
        var descripcion: String
        var icono: String?
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            nombre = c.texto("nombre") ?? ""
            descripcion = c.texto("descripcion") ?? ""
            icono = c.texto("icono")
        }
    }
    struct Privacidad: Decodable {
        var canciones: Bool
        var generos: Bool
        var vibras: Bool
        var actividad: Bool
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            canciones = c.bool("canciones") ?? true
            generos = c.bool("generos") ?? true
            vibras = c.bool("vibras") ?? true
            actividad = c.bool("actividad") ?? true
        }
    }

    var mini: PerfilMini
    var esYo: Bool
    var biografia: String?
    var banner: String?
    var bannerPos: Double
    var desde: String?
    var seguidores: Int
    var siguiendo: Int
    var playlists: [Playlist]
    var topCanciones: [TopCancion]
    var topArtistas: [TopArtista]
    var topGeneros: [TopGenero]
    var vibras: [Vibra]
    var actividad: Actividad?
    var totalReproducciones: Int?
    var compatibilidad: Double?
    var artistasEnComun: [String]
    var privacidad: Privacidad?

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        mini = try PerfilMini(from: decoder)
        esYo = c.bool("esYo") ?? false
        biografia = c.texto("biografia")
        banner = c.texto("banner")
        bannerPos = c.numero("bannerPos") ?? 50
        desde = c.texto("desde")
        seguidores = c.entero("seguidores") ?? 0
        siguiendo = c.entero("siguiendo") ?? 0
        playlists = c.lista("playlists")
        topCanciones = c.lista("topCanciones")
        topArtistas = c.lista("topArtistas")
        topGeneros = c.lista("topGeneros")
        vibras = c.lista("vibras")
        if vibras.isEmpty, let una: Vibra = c.v("vibras") { vibras = [una] }
        actividad = c.v("actividad")
        totalReproducciones = c.entero("totalReproducciones")
        if let comp = try? c.nestedContainer(keyedBy: ClaveJSON.self, forKey: ClaveJSON("compatibilidad")) {
            compatibilidad = comp.numero("porcentaje")
            artistasEnComun = comp.lista("artistasEnComun")
        } else {
            compatibilidad = nil
            artistasEnComun = []
        }
        privacidad = c.v("privacidad")
    }
}

// MARK: - Mensajes y notificaciones

struct Mensaje: Decodable, Identifiable, Equatable {
    let id: Int
    var mio: Bool
    var texto: String
    var fecha: String?
    var leido: Bool
    var cancion: Cancion?

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? Int.random(in: Int.min..<0)
        mio = c.bool("mio") ?? false
        texto = c.texto("texto") ?? ""
        fecha = c.texto("fecha")
        leido = c.bool("leido") ?? false
        cancion = c.v("cancion")
    }
    static func == (a: Mensaje, b: Mensaje) -> Bool { a.id == b.id && a.leido == b.leido }
}

struct Conversacion: Decodable, Identifiable {
    var id: String { usuario.username }
    var usuario: PerfilMini
    var ultimo: Mensaje?
    var noLeidos: Int
    var puedeEscribir: Bool

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        guard let u: PerfilMini = c.v("usuario") else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Sin usuario"))
        }
        usuario = u
        ultimo = c.v("ultimo")
        noLeidos = c.entero("noLeidos") ?? 0
        puedeEscribir = c.bool("puedeEscribir") ?? false
    }
}

struct Chat: Decodable {
    var usuario: PerfilMini?
    var mensajes: [Mensaje]
    var puedeEscribir: Bool
    var hayMas: Bool
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        usuario = c.v("usuario")
        mensajes = c.lista("mensajes")
        puedeEscribir = c.bool("puedeEscribir") ?? false
        hayMas = c.bool("hayMas") ?? false
    }
}

struct Notificacion: Decodable, Identifiable {
    let id: Int
    var tipo: String
    var texto: String
    var ruta: String?
    var fecha: String?
    var leida: Bool
    var de: PerfilMini?

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0
        tipo = c.texto("tipo") ?? ""
        texto = c.texto("texto") ?? ""
        ruta = c.texto("ruta")
        fecha = c.texto("fecha")
        leida = c.bool("leida") ?? false
        de = c.v("de")
    }
}

struct ResumenAvisos: Decodable {
    /// Lo más reciente, para avisar con una notificación del iPhone (igual que `avisos.js` de la web).
    struct UltimoMensaje: Decodable {
        var id: Int; var username: String; var de: String; var texto: String
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            id = c.entero("id") ?? 0; username = c.texto("username") ?? ""
            de = c.texto("de") ?? username; texto = c.texto("texto") ?? ""
        }
    }
    struct UltimaNotificacion: Decodable {
        var id: Int; var texto: String; var ruta: String?
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            id = c.entero("id") ?? 0; texto = c.texto("texto") ?? ""; ruta = c.texto("ruta")
        }
    }
    var mensajes: Int
    var notificaciones: Int
    var ultimoMensaje: UltimoMensaje?
    var ultimaNotificacion: UltimaNotificacion?
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        mensajes = c.entero("mensajes") ?? 0
        notificaciones = c.entero("notificaciones") ?? 0
        ultimoMensaje = c.v("ultimoMensaje")
        ultimaNotificacion = c.v("ultimaNotificacion")
    }
}

// MARK: - Estadísticas y novedades

struct MisEstadisticas: Decodable {
    var totalReproducciones: Int
    var cancionesDistintas: Int
    var totalFavoritos: Int
    var tiempoEscuchado: String
    var topCanciones: [Perfil.TopCancion]
    var topArtistas: [Perfil.TopArtista]
    var topGeneros: [Perfil.TopGenero]
    var porHora: [Int]
    var rachaDias: Int
    var diasActivos: Int

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        totalReproducciones = c.entero("totalReproducciones") ?? 0
        cancionesDistintas = c.entero("cancionesDistintas") ?? 0
        totalFavoritos = c.entero("totalFavoritos") ?? 0
        tiempoEscuchado = c.texto("tiempoEscuchado") ?? "0 min"
        topCanciones = c.lista("topCanciones")
        topArtistas = c.lista("topArtistas")
        topGeneros = c.lista("topGeneros")
        porHora = c.lista("porHora")
        rachaDias = c.entero("rachaDias") ?? 0
        diasActivos = c.entero("diasActivos") ?? 0
    }
}

struct Novedad: Decodable, Identifiable {
    var id: String { version }
    var version: String
    var titulo: String
    var puntos: [String]
    var anteriores: [Novedad]

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        version = c.texto("version") ?? ""
        titulo = c.texto("titulo") ?? ""
        puntos = c.lista("puntos")
        anteriores = c.lista("anteriores")
    }
}

// MARK: - Administración

struct AdminResumen: Decodable {
    var totalUsuarios: Int
    var admins: Int
    var colaboradores: Int
    var inactivos: Int
    var solicitudesPendientes: Int
    var cuentasPendientes: Int
    var correoConfigurado: Bool
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        totalUsuarios = c.entero("totalUsuarios") ?? 0
        admins = c.entero("admins") ?? 0
        colaboradores = c.entero("colaboradores") ?? 0
        inactivos = c.entero("inactivos") ?? 0
        solicitudesPendientes = c.entero("solicitudesPendientes") ?? 0
        cuentasPendientes = c.entero("cuentasPendientes") ?? 0
        correoConfigurado = c.bool("correoConfigurado") ?? false
    }
}

/// Respuesta con sesión: login y renovar token.
struct RespuestaSesion: Decodable {
    var token: String?
    var usuario: Usuario?
    var message: String?
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        token = c.texto("token")
        usuario = c.v("usuario")
        message = c.texto("message")
    }
}

/// Respuesta genérica {"ok": true, "message": "..."}.
struct RespuestaOK: Decodable {
    var ok: Bool
    var message: String?
    var token: String?
    var usuario: Usuario?
    var fotoPerfil: String?
    var banner: String?
    var favorito: Bool?
    var enviados: Int?
    init(from decoder: Decoder) throws {
        // Algunas acciones responden con algo que no es un objeto: se toma como "ok"
        guard let c = try? decoder.campos() else {
            ok = true
            return
        }
        ok = c.bool("ok") ?? true
        message = c.texto("message")
        token = c.texto("token")
        usuario = c.v("usuario")
        fotoPerfil = c.texto("fotoPerfil")
        banner = c.texto("banner")
        favorito = c.bool("favorito")
        enviados = c.entero("enviados")
    }
}

// MARK: - Formato

enum Formato {
    static func duracion(_ s: Int) -> String {
        let s = max(0, s)
        if s >= 3600 { return String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) }
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    static func duracionLarga(_ s: Int) -> String {
        let h = s / 3600, m = (s % 3600) / 60
        if h > 0 { return "\(h) h \(m) min" }
        return "\(max(m, 1)) min"
    }

    static func canciones(_ n: Int) -> String { n == 1 ? "1 canción" : "\(n) canciones" }
    /// Número con separador de miles («12 345»), como `fmtNum` de la web.
    static func numero(_ n: Int) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale(identifier: "es")
        return f.string(from: NSNumber(value: n)) ?? String(n)
    }

    /// Lee fechas del servidor: "2025-03-01T10:20:30", con o sin zona y milisegundos.
    static func fecha(_ s: String?) -> Date? {
        guard let s, !s.isEmpty else { return nil }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = iso.date(from: s) { return d }
        iso.formatOptions = [.withInternetDateTime]
        if let d = iso.date(from: s) { return d }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        let base = String(s.prefix(19))
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f.date(from: base)
    }

    static func hace(_ s: String?) -> String {
        guard let d = fecha(s) else { return "" }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "es")
        f.unitsStyle = .short
        if abs(d.timeIntervalSinceNow) < 60 { return "ahora" }
        return f.localizedString(for: d, relativeTo: Date())
    }

    static func hora(_ s: String?) -> String {
        guard let d = fecha(s) else { return "" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "es")
        f.dateFormat = Calendar.current.isDateInToday(d) ? "HH:mm" : "d MMM, HH:mm"
        return f.string(from: d)
    }
}
