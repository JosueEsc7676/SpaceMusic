import Foundation

/// Palabra con su tiempo (LRC mejorado: `<00:12.34>palabra`).
struct PalabraLetra: Equatable {
    let tiempo: Double
    let texto: String
}

struct LineaLetra: Identifiable, Equatable {
    let id: Int
    let tiempo: Double
    let texto: String
    /// Hasta cuándo dura (empieza la siguiente línea).
    var fin: Double = 0
    /// Tiempos por palabra si la letra los trae; si no, la línea se ilumina entera.
    var palabras: [PalabraLetra] = []
    /// Pausa larga sin letra (se muestra como "• • •").
    var interludio: Bool { texto.isEmpty }
}

/// Lector equivalente a `/js/lrc.js` de la web: varias marcas por línea, `[offset:]`, tiempos por palabra,
/// créditos que se ocultan e interludios en las pausas largas.
enum LectorLRC {
    private static let marca = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]"#)
    private static let etiqueta = try! NSRegularExpression(pattern: #"\[[^\]]*\]"#)
    private static let palabra = try! NSRegularExpression(pattern: #"<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)"#)
    private static let marcaPalabra = try! NSRegularExpression(pattern: #"<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"#)
    private static let desfase = try! NSRegularExpression(pattern: #"\[offset:\s*([+-]?\d+)\s*\]"#, options: .caseInsensitive)
    private static let credito = try! NSRegularExpression(
        pattern: #"^\s*(作词|作曲|编曲|制作人|作詞|編曲|製作人|词|曲|监制|混音|母带|和声|吉他|贝斯|鼓|录音|出品|发行|OP|SP|lyrics?( by)?|written by|writer|composed by|composer|music by|producers?|produced by|arranged by|arranger|mixed by|mastered by|letra|m[uú]sica|compositor(es)?|productor(es)?|arreglos?|(mixing |mastering |recording )?engineer|remix(ed by)?|remezcla)\s*[:：]"#,
        options: .caseInsensitive)

    private static func segundos(_ ns: NSString, _ m: NSTextCheckingResult, _ a: Int, _ b: Int, _ c: Int) -> Double {
        let min = Double(ns.substring(with: m.range(at: a))) ?? 0
        let seg = Double(ns.substring(with: m.range(at: b))) ?? 0
        var frac = 0.0
        if m.range(at: c).location != NSNotFound {
            let f = ns.substring(with: m.range(at: c))
            frac = (Double(f) ?? 0) / pow(10, Double(f.count))
        }
        return min * 60 + seg + frac
    }

    /// Convierte "[01:23.45] texto" en líneas con tiempo.
    static func leer(_ lrc: String) -> [LineaLetra] {
        let todo = lrc as NSString
        var offset = 0.0
        if let m = desfase.firstMatch(in: lrc, range: NSRange(location: 0, length: todo.length)) {
            offset = (Double(todo.substring(with: m.range(at: 1))) ?? 0) / 1000
        }
        var crudas: [(Double, String, [PalabraLetra])] = []
        for fila in lrc.components(separatedBy: .newlines) {
            let ns = fila as NSString
            let rango = NSRange(location: 0, length: ns.length)
            let marcas = marca.matches(in: fila, range: rango)
            guard !marcas.isEmpty else { continue }
            let cuerpo = etiqueta.stringByReplacingMatches(in: fila, range: rango, withTemplate: "")
            let nc = cuerpo as NSString
            let encontradas = palabra.matches(in: cuerpo, range: NSRange(location: 0, length: nc.length))
            let palabras: [PalabraLetra] = encontradas.count >= 2
                ? encontradas.map { PalabraLetra(tiempo: segundos(nc, $0, 1, 2, 3) - offset, texto: nc.substring(with: $0.range(at: 4))) }.filter { !$0.texto.isEmpty }
                : []
            let texto = marcaPalabra.stringByReplacingMatches(in: cuerpo, range: NSRange(location: 0, length: nc.length), withTemplate: "")
                .components(separatedBy: .whitespaces).filter { !$0.isEmpty }.joined(separator: " ")
            if credito.firstMatch(in: texto, range: NSRange(location: 0, length: (texto as NSString).length)) != nil { continue }
            for m in marcas { crudas.append((segundos(ns, m, 1, 2, 3) - offset, texto, palabras)) }
        }
        crudas.sort { $0.0 < $1.0 }

        var salida: [(Double, String, [PalabraLetra])] = []
        func agregar(_ l: (Double, String, [PalabraLetra])) {
            if l.1.isEmpty, let previa = salida.last, previa.1.isEmpty { return }
            salida.append(l)
        }
        if let primera = crudas.first, primera.0 > 5 { agregar((0, "", [])) }
        for (i, l) in crudas.enumerated() {
            let siguiente = i + 1 < crudas.count ? crudas[i + 1].0 : l.0 + 6
            if l.1.isEmpty { agregar((l.0, "", [])); continue }
            agregar(l)
            // Pausa larga sin letra: puntos de interludio, como en la web.
            let duracion = min(siguiente - l.0, 3.5 + Double(l.1.count) * 0.09)
            if siguiente - l.0 > 9, siguiente - (l.0 + duracion) > 4 { agregar((l.0 + duracion, "", [])) }
        }
        return salida.enumerated().map { i, l in
            LineaLetra(id: i, tiempo: l.0, texto: l.1, fin: i + 1 < salida.count ? salida[i + 1].0 : l.0 + 8, palabras: l.2)
        }
    }

    /// Inicio y fin de cada palabra para iluminarla como Apple Music.
    /// Con LRC mejorado usa los tiempos reales; si la letra solo trae la línea, reparte su duración según el largo
    /// de cada palabra (unas 13 letras por segundo, sin pasar del comienzo de la siguiente línea).
    static func tiemposPalabras(_ l: LineaLetra) -> [(texto: String, inicio: Double, fin: Double)] {
        if !l.palabras.isEmpty {
            return l.palabras.enumerated().map { i, p in
                let fin = i + 1 < l.palabras.count ? l.palabras[i + 1].tiempo : min(l.fin, p.tiempo + max(0.4, Double(p.texto.count) * 0.08))
                return (p.texto, p.tiempo, max(fin, p.tiempo + 0.05))
            }
        }
        let partes = l.texto.split(separator: " ").map(String.init)
        guard !partes.isEmpty else { return [] }
        let letras = max(1, partes.reduce(0) { $0 + $1.count })
        let disponible = max(0.4, l.fin - l.tiempo - 0.15)
        let duracion = min(disponible, max(0.6, 0.35 + Double(letras) * 0.075))
        var acumulado = 0
        return partes.enumerated().map { i, parte in
            let inicio = l.tiempo + duracion * Double(acumulado) / Double(letras)
            acumulado += parte.count
            let fin = l.tiempo + duracion * Double(acumulado) / Double(letras)
            return (i + 1 < partes.count ? parte + " " : parte, inicio, fin)
        }
    }

    /// Línea activa en el segundo dado (búsqueda binaria).
    static func indice(_ lineas: [LineaLetra], en t: Double) -> Int? {
        var bajo = 0, alto = lineas.count - 1, encontrada: Int?
        while bajo <= alto {
            let medio = (bajo + alto) / 2
            if lineas[medio].tiempo <= t { encontrada = medio; bajo = medio + 1 } else { alto = medio - 1 }
        }
        return encontrada
    }
}
