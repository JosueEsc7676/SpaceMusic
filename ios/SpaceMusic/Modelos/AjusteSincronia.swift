import Foundation

/// Corrección de la sincronía de SyncPlay (la misma lógica que `js/syncplay-ajuste.js` de la web).
///
/// Antes, si el reproductor iba 0,6 s atrasado saltaba a la posición del SyncPlay; con una conexión lenta el salto
/// tardaba en volver a sonar, quedaba otra vez atrasado y volvía a saltar: el audio se cortaba a cada rato.
/// Ahora:
/// - al saltar se apunta un poco más adelante (`adelanto`), aprendido midiendo cuánto tardó de verdad en volver a sonar;
/// - después de un salto se espera antes de volver a corregir; si los saltos se repiten, se espera cada vez más;
/// - los desfases medianos se corrigen solo con la velocidad (0,95–1,05), que no corta el audio.
public struct AjusteSincronia {
    public struct Orden: Equatable {
        public var velocidad: Float
        /// Ir a (posición del SyncPlay + saltar).
        public var saltar: Double?
    }

    public static let salto = 0.8
    static let saltoForzado = 0.5
    static let empezar = 0.06, parar = 0.02

    public private(set) var adelanto: Double
    public private(set) var esperaHasta: Double = 0
    private var saltos: [Double] = []
    private var corrigiendo = false
    private var midiendo = false

    public init(adelanto: Double = 0.3) { self.adelanto = adelanto }

    /// Tras cargar una canción y saltar a su posición: no corregir enseguida y medir cuánto tardó.
    public mutating func trasCargar(ahora: Double) {
        midiendo = true
        esperaHasta = ahora + 2.5
    }

    /// `desfase` = posición local − posición del SyncPlay (positivo: adelantados). `ahora` en segundos.
    /// Solo debe llamarse mientras suena de verdad (no mientras carga).
    public mutating func decidir(desfase: Double, ahora: Double, forzar: Bool = false, puedeAcelerar: Bool = true) -> Orden {
        let a = abs(desfase)
        if ahora < esperaHasta, !(forzar && a > Self.saltoForzado) { return Orden(velocidad: 1) }
        if midiendo {
            // Primera medida tras un salto: lo que quedó de desfase es lo que tardó en volver a sonar
            midiendo = false
            if a < 4 { adelanto = min(3, max(0, adelanto - desfase * 0.8)) }
        }
        if a > Self.salto || (forzar && a > Self.saltoForzado) {
            saltos = saltos.filter { ahora - $0 < 60 }
            saltos.append(ahora)
            esperaHasta = ahora + min(15, 2.5 * Double(saltos.count))
            midiendo = true
            corrigiendo = false
            return Orden(velocidad: 1, saltar: adelanto)
        }
        if (corrigiendo ? a < Self.parar : a > Self.empezar) { corrigiendo.toggle() }
        guard corrigiendo else { return Orden(velocidad: 1) }
        var v = min(1.05, max(0.95, 1 - desfase * 0.6))
        if v > 1, !puedeAcelerar { v = 1 }
        return Orden(velocidad: Float((v * 200).rounded() / 200))
    }
}
