import Foundation

struct LineaLetra: Identifiable, Equatable {
    let id: Int
    let tiempo: Double
    let texto: String
}

enum LectorLRC {
    /// Convierte "[01:23.45] texto" en líneas con tiempo.
    static func leer(_ lrc: String) -> [LineaLetra] {
        var r: [(Double, String)] = []
        let patron = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]"#)
        for fila in lrc.components(separatedBy: .newlines) {
            let ns = fila as NSString
            let marcas = patron.matches(in: fila, range: NSRange(location: 0, length: ns.length))
            guard !marcas.isEmpty, let ultima = marcas.last else { continue }
            let texto = ns.substring(from: ultima.range.location + ultima.range.length).trimmingCharacters(in: .whitespaces)
            for m in marcas {
                let min = Double(ns.substring(with: m.range(at: 1))) ?? 0
                let seg = Double(ns.substring(with: m.range(at: 2))) ?? 0
                var frac = 0.0
                if m.range(at: 3).location != NSNotFound {
                    let f = ns.substring(with: m.range(at: 3))
                    frac = (Double(f) ?? 0) / pow(10, Double(f.count))
                }
                r.append((min * 60 + seg + frac, texto))
            }
        }
        return r.sorted { $0.0 < $1.0 }.enumerated().map { LineaLetra(id: $0.offset, tiempo: $0.element.0, texto: $0.element.1) }
    }
}
