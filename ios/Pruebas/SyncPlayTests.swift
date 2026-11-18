import XCTest
@testable import ModelosSpaceMusic

final class SyncPlayTests: XCTestCase {
    let servidor = "https://music.example"
    func testInvitacionEnMensajeAbreLaSalaCorrecta() {
        let enlace = InvitacionSyncPlay.enlace(sala: 42, servidor: servidor)!
        XCTAssertEqual(InvitacionSyncPlay.sala(en: "Escuchemos juntos en SyncPlay: \(enlace.absoluteString)", servidor: servidor), 42)
        XCTAssertNil(InvitacionSyncPlay.enlace(sala: 0, servidor: servidor))
    }
    func testNoAbreOtraCuentaServidorNiRutaParecida() {
        for texto in ["https://music.example.evil/#/juntos/2", "https://evil.example/#/juntos/2", "https://u:p@music.example/#/juntos/2", "https://music.example/#/juntos/2/otra", "https://music.example/#/juntos/-2", "https://music.example:8888/#/juntos/2", "http://music.example/#/juntos/2"] {
            XCTAssertNil(InvitacionSyncPlay.sala(en: texto, servidor: servidor), texto)
        }
    }
    func testMensajesNormalesNoSeConviertenEnInvitaciones() {
        XCTAssertNil(InvitacionSyncPlay.sala(en: "¿Qué estás escuchando?", servidor: servidor))
        XCTAssertEqual(InvitacionSyncPlay.sala(en: "Ven: https://music.example/#/juntos/18.", servidor: servidor), 18)
    }

    // MARK: Sincronía con conexión lenta (cada salto tarda en volver a sonar)

    private func simular(demora: Double, inicio: Double = -3, decidir: (Double, Double) -> AjusteSincronia.Orden) -> (saltos: Int, desfase: Double) {
        var objetivo = 0.0, local = inicio, silencioHasta = -1.0, velocidad = 1.0, saltos = 0
        for paso in 0..<600 {
            let t = Double(paso) / 10
            objetivo += 0.1
            if t < silencioHasta { continue }
            local += 0.1 * velocidad
            guard paso % 5 == 0 else { continue }
            let o = decidir(local - objetivo, t)
            velocidad = Double(o.velocidad)
            if let s = o.saltar { saltos += 1; local = objetivo + s; silencioHasta = t + demora }
        }
        return (saltos, abs(local - objetivo))
    }

    func testAntesSaltabaEnBucleConConexionLenta() {
        let r = simular(demora: 1.5) { d, _ in abs(d) > 0.6 ? .init(velocidad: 1, saltar: 0.05) : .init(velocidad: 1) }
        XCTAssertGreaterThan(r.saltos, 15)
    }

    func testAhoraPocosSaltosYQuedaSincronizado() {
        for demora in [0.2, 0.8, 1.5, 3] {
            var a = AjusteSincronia()
            let r = simular(demora: demora) { d, t in a.decidir(desfase: d, ahora: t) }
            XCTAssertLessThanOrEqual(r.saltos, 3, "demora \(demora)")
            XCTAssertLessThan(r.desfase, 0.1, "demora \(demora)")
        }
    }

    func testDesfasePequenoSoloConVelocidad() {
        var a = AjusteSincronia()
        let r = simular(demora: 1, inicio: -0.5) { d, t in a.decidir(desfase: d, ahora: t) }
        XCTAssertEqual(r.saltos, 0)
        XCTAssertLessThan(r.desfase, 0.05)
    }

    func testSinBuferNoAcelera() {
        var a = AjusteSincronia()
        XCTAssertEqual(a.decidir(desfase: -0.3, ahora: 0, puedeAcelerar: false).velocidad, 1)
        XCTAssertLessThan(a.decidir(desfase: 0.3, ahora: 0.5, puedeAcelerar: false).velocidad, 1)
    }

    func testEstadoNuevoSaltaAunqueEsteEsperando() {
        var a = AjusteSincronia()
        XCTAssertNotNil(a.decidir(desfase: -2, ahora: 0).saltar)
        XCTAssertNil(a.decidir(desfase: -2, ahora: 1).saltar)
        XCTAssertNotNil(a.decidir(desfase: 30, ahora: 1.2, forzar: true).saltar)
    }
}
