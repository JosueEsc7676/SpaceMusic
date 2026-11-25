import Foundation

/// Línea de tiempo del vídeo; las marcas son internas al archivo, no cambios de artwork.
enum SecuenciaPortada {
    static func tiempos(_ lineas: [LineaLetra], desde inicio: Double, hasta fin: Double, palabras: Bool) -> [Double] {
        guard inicio.isFinite, fin.isFinite, fin > inicio else { return [0] }
        var marcas: Set<Double> = [0, fin - inicio]
        for l in lineas {
            if l.tiempo > inicio && l.tiempo < fin { marcas.insert(l.tiempo - inicio) }
            if l.fin > inicio && l.fin < fin { marcas.insert(l.fin - inicio) }
            if palabras {
                for p in LectorLRC.tiemposPalabras(l) where p.inicio > inicio && p.inicio < fin {
                    marcas.insert(p.inicio - inicio)
                    if p.inicio + 0.18 < min(l.fin, fin) { marcas.insert(p.inicio + 0.18 - inicio) }
                }
            }
        }
        return marcas.sorted()
    }
    static func linea(_ lineas: [LineaLetra], en segundo: Double) -> LineaLetra? {
        guard let i = LectorLRC.indice(lineas, en: segundo), lineas.indices.contains(i),
              !lineas[i].texto.isEmpty, segundo < lineas[i].fin else { return nil }
        return lineas[i]
    }
}
