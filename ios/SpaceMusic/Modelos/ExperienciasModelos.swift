import Foundation

// Modelos de las funciones de la web 3.x: rutas, descubrir, coincidimos, dedicatorias, resumen, gustos,
// preparar sin internet y reportes. Mismo patrón tolerante que Modelos.swift.

/// Respuesta con solo un id (por ejemplo al crear una sesión).
struct RespuestaId: Decodable {
    var id: Int
    var repetido: Bool
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0
        repetido = c.bool("repetido") ?? false
    }
}

/// Persona breve (dedicatorias, coincidimos).
struct PersonaBreve: Decodable, Hashable {
    var id: Int
    var username: String
    var nombre: String
    var fotoPerfil: String?
    var mutuo: Bool
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0
        username = c.texto("username") ?? ""
        nombre = c.texto("nombre") ?? c.texto("nombreDisplay") ?? username
        fotoPerfil = c.texto("fotoPerfil")
        mutuo = c.bool("mutuo") ?? false
    }
}

// MARK: - Rutas musicales

struct RutaMusical: Decodable {
    var disponible: Bool
    var nota: String?
    var minutos: Int
    var explicacion: String
    var energias: [Double]
    var canciones: [Cancion]
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        canciones = c.lista("canciones")
        disponible = c.bool("disponible") ?? !canciones.isEmpty
        nota = c.texto("nota")
        minutos = c.entero("minutos") ?? 0
        explicacion = c.texto("explicacion") ?? ""
        energias = c.lista("energias")
    }
}

// MARK: - Descubrir

struct Descubrimiento: Decodable, Identifiable {
    var id: Int { cancion.id }
    var cancion: Cancion
    var razon: String
    var estiloNombre: String
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        guard let s: Cancion = c.v("cancion") else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Sin canción"))
        }
        cancion = s
        razon = c.texto("razon") ?? ""
        estiloNombre = c.texto("estiloNombre") ?? "este estilo"
    }
}

struct EstiloDescartado: Decodable, Identifiable {
    var id: Int
    var estilo: String
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0
        estilo = c.texto("estilo") ?? ""
    }
}

// MARK: - El punto donde coincidimos

struct SesionCoincidimos: Decodable, Identifiable {
    var id: Int
    var estado: String
    var meInvitaron: Bool
    var conmigo: PersonaBreve?
    var estilosEnComun: [String]
    var compartidas: [Cancion]
    var puentes: [Cancion]
    var miAporte: [Cancion]
    var suAporte: [Cancion]
    /// Mi reacción por id de canción ("REPRESENTA", "SORPRENDIO", "NO_ES_PARA_MI").
    var misReacciones: [Int: String]

    private struct Puente: Decodable {
        var cancion: Cancion?
        init(from decoder: Decoder) throws { cancion = try decoder.campos().v("cancion") }
    }
    private struct Reaccion: Decodable {
        var yo: String?
        init(from decoder: Decoder) throws { yo = try decoder.campos().texto("yo") }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0
        estado = c.texto("estado") ?? ""
        meInvitaron = c.bool("meInvitaron") ?? false
        conmigo = c.v("conmigo")
        estilosEnComun = c.lista("estilosEnComun")
        compartidas = c.lista("compartidas")
        puentes = (c.lista("puentes") as [Puente]).compactMap(\.cancion)
        miAporte = c.lista("miAporte")
        suAporte = c.lista("suAporte")
        var r: [Int: String] = [:]
        if let dic: [String: Reaccion] = c.v("reacciones") {
            for (k, v) in dic { if let id = Int(k), let yo = v.yo { r[id] = yo } }
        }
        misReacciones = r
    }
}

// MARK: - Dedicatorias

struct Dedicatoria: Decodable, Identifiable {
    var id: Int
    var cancion: Cancion?
    var recibida: Bool
    var persona: PersonaBreve?
    var fecha: String?
    var programadaPara: Double?
    var letra: String?
    var completa: Bool
    var mensaje: String?
    var vista: Bool
    var respuesta: String?
    var guardada: Bool
    var segundo: Int
    var segundoFin: Int?
    var lineas: [String] { (letra ?? "").split(separator: "\n", omittingEmptySubsequences: true).map(String.init) }

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        id = c.entero("id") ?? 0
        cancion = c.v("cancion")
        recibida = c.bool("recibida") ?? false
        persona = c.v("persona")
        fecha = c.texto("fecha")
        programadaPara = c.numero("programadaPara")
        letra = c.texto("letra")
        completa = c.bool("completa") ?? false
        mensaje = c.texto("mensaje")
        vista = c.bool("vista") ?? true
        respuesta = c.texto("respuesta")
        guardada = c.bool("guardada") ?? false
        segundo = c.entero("segundo") ?? 0
        segundoFin = c.entero("segundoFin")
    }
}

struct ListaDedicatorias: Decodable {
    var recibidas: [Dedicatoria]
    var enviadas: [Dedicatoria]
    var bloqueadas: [String]
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        recibidas = c.lista("recibidas"); enviadas = c.lista("enviadas"); bloqueadas = c.lista("bloqueadas")
    }
}

/// Letra de una canción tal como la devuelve `/api/canciones/{id}/letra`.
struct LetraCancion: Decodable {
    var letra: String
    var letraSincronizada: String
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        letra = c.texto("letra") ?? ""; letraSincronizada = c.texto("letraSincronizada") ?? ""
    }
}

/// Bloques (estrofas) de la letra para elegir qué parte dedicar, igual que `bloquesDeLetra` de la web:
/// se corta en una línea vacía, en un silencio de más de 6 s o a las 4 líneas.
enum BloquesLetra {
    struct Linea { var t: Double?; var texto: String; var fin: Double? }

    static func dividir(letra: String, lrc: String) -> [[Linea]] {
        var lineas: [Linea] = []
        let limpiar = { (l: String) -> String in
            l.replacingOccurrences(of: #"\[[^\]]*\]|<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"#, with: "", options: .regularExpression)
                .replacingOccurrences(of: #"\s{2,}"#, with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
        }
        if lrc.range(of: #"\[\d{1,3}:\d{2}"#, options: .regularExpression) != nil {
            let patron = try! NSRegularExpression(pattern: #"^\s*\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]\s*(.*)$"#)
            for l in lrc.components(separatedBy: .newlines) {
                let ns = l as NSString
                guard let m = patron.firstMatch(in: l, range: NSRange(location: 0, length: ns.length)) else { continue }
                let min = Double(ns.substring(with: m.range(at: 1))) ?? 0
                let seg = Double(ns.substring(with: m.range(at: 2))) ?? 0
                let frac = m.range(at: 3).location != NSNotFound ? (Double("0." + ns.substring(with: m.range(at: 3))) ?? 0) : 0
                lineas.append(Linea(t: min * 60 + seg + frac, texto: limpiar(ns.substring(with: m.range(at: 4))), fin: nil))
            }
        } else {
            for l in letra.components(separatedBy: .newlines) { lineas.append(Linea(t: nil, texto: limpiar(l), fin: nil)) }
        }
        var bloques: [[Linea]] = [], actual: [Linea] = []
        for l in lineas {
            if l.texto.isEmpty { if !actual.isEmpty { bloques.append(actual) }; actual = []; continue }
            if let prev = actual.last, (l.t != nil && prev.t != nil && l.t! - prev.t! > 6) || actual.count >= 4 {
                bloques.append(actual); actual = []
            }
            actual.append(l)
        }
        if !actual.isEmpty { bloques.append(actual) }
        // El fin de cada línea es el inicio de la siguiente.
        var planas = bloques.flatMap { $0 }
        for i in planas.indices { planas[i].fin = i + 1 < planas.count ? planas[i + 1].t : planas[i].t.map { $0 + 5 } }
        var k = 0
        return bloques.map { b in b.map { _ in defer { k += 1 }; return planas[k] } }
    }
}

// MARK: - Tu resumen (Tu año / tu mes en SpaceMusic)

struct ResumenEscucha: Decodable {
    struct ArtistaTop: Decodable { var nombre: String; var portadaUrl: String?; var minutos: Int
        init(from decoder: Decoder) throws { let c = try decoder.campos(); nombre = c.texto("nombre") ?? ""; portadaUrl = c.texto("portadaUrl"); minutos = c.entero("minutos") ?? 0 }
    }
    struct GeneroTop: Decodable { var nombre: String; var porcentaje: Double
        init(from decoder: Decoder) throws { let c = try decoder.campos(); nombre = c.texto("nombre") ?? ""; porcentaje = c.numero("porcentaje") ?? 0 }
    }
    struct Punto: Decodable { var periodo: String; var minutos: Int
        init(from decoder: Decoder) throws { let c = try decoder.campos(); periodo = c.texto("periodo") ?? ""; minutos = c.entero("minutos") ?? 0 }
    }
    private struct Top: Decodable { var cancion: Cancion?
        init(from decoder: Decoder) throws { cancion = try decoder.campos().v("cancion") }
    }
    var titulo: String
    var suficiente: Bool
    var nota: String?
    var enCurso: Bool
    var minutos: Int
    var reproducciones: Int
    var diasActivos: Int
    var cancionesDistintas: Int
    var artistasDistintos: Int
    var estilo: String
    var racha: Int
    var momento: String
    var diaFavorito: String
    var mesMasActivo: String?
    var topArtistas: [ArtistaTop]
    var topCanciones: [Cancion]
    var topGeneros: [GeneroTop]
    var serie: [Punto]
    var primeraCancion: Cancion?
    var cambioPorcentaje: Double?
    var descubrimiento: ArtistaTop?

    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        titulo = c.texto("titulo") ?? "Tu resumen"
        suficiente = c.bool("suficiente") ?? false
        nota = c.texto("nota")
        enCurso = c.bool("enCurso") ?? false
        minutos = c.entero("minutos") ?? 0
        reproducciones = c.entero("reproducciones") ?? 0
        diasActivos = c.entero("diasActivos") ?? 0
        cancionesDistintas = c.entero("cancionesDistintas") ?? 0
        artistasDistintos = c.entero("artistasDistintos") ?? 0
        estilo = c.texto("estilo") ?? ""
        racha = c.entero("racha") ?? 0
        momento = c.texto("momento") ?? ""
        diaFavorito = c.texto("diaFavorito") ?? ""
        mesMasActivo = c.texto("mesMasActivo")
        topArtistas = c.lista("topArtistas")
        topCanciones = (c.lista("topCanciones") as [Top]).compactMap(\.cancion)
        topGeneros = c.lista("topGeneros")
        serie = c.lista("serie")
        primeraCancion = c.v("primeraCancion")
        cambioPorcentaje = c.numero("cambioPorcentaje")
        descubrimiento = c.v("descubrimiento")
    }
}

struct PeriodosResumen: Decodable {
    var anios: [Int]
    init(from decoder: Decoder) throws { anios = try decoder.campos().lista("anios") }
}

// MARK: - Gustos

struct OpcionesGustos: Decodable {
    struct Opcion: Decodable, Hashable { var nombre: String; var portadaUrl: String?
        init(from decoder: Decoder) throws { let c = try decoder.campos(); nombre = c.texto("nombre") ?? ""; portadaUrl = c.texto("portadaUrl") }
    }
    var generos: [Opcion]
    var artistas: [Opcion]
    init(from decoder: Decoder) throws { let c = try decoder.campos(); generos = c.lista("generos"); artistas = c.lista("artistas") }
}

struct MisGustos: Decodable {
    var generos: [String]
    var artistas: [String]
    var hecho: Bool
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        generos = c.lista("generos"); artistas = c.lista("artistas"); hecho = c.bool("hecho") ?? false
    }
}

// MARK: - Preparar sin internet

struct ItemPreparado: Decodable {
    var cancion: Cancion
    var bytes: Int64
    var tipo: String
    var esFavorita: Bool { tipo == "favorita" }
    init(from decoder: Decoder) throws {
        let c = try decoder.campos()
        guard let s: Cancion = c.v("cancion") else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Sin canción"))
        }
        cancion = s
        bytes = Int64(c.numero("bytes") ?? 0)
        tipo = c.texto("tipo") ?? "favorita"
    }
}

struct SeleccionPreparada: Decodable {
    var canciones: [ItemPreparado]
    var nota: String?
    init(from decoder: Decoder) throws { let c = try decoder.campos(); canciones = c.lista("canciones"); nota = c.texto("nota") }
}
