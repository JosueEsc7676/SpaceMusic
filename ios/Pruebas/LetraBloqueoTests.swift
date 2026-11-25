import XCTest
@testable import ModelosSpaceMusic

final class LetraBloqueoTests: XCTestCase {
    func testBotonAgrandarFuncionaConLetraYOriginal() {
        XCTAssertTrue(PoliticaLetraBloqueo.usarVideo(pantallaCompleta: true, conLetra: true))
        XCTAssertFalse(PoliticaLetraBloqueo.usarVideo(pantallaCompleta: false, conLetra: true))
        XCTAssertTrue(PoliticaLetraBloqueo.usarVideo(pantallaCompleta: true, conLetra: false))
        XCTAssertFalse(PoliticaLetraBloqueo.usarVideo(pantallaCompleta: false, conLetra: false))
    }
    func testUnaPalabraLimitadaSeReintentaSinPerderla() {
        XCTAssertFalse(PoliticaLetraBloqueo.actualizar(palabras: 2, mostradas: 1, iluminar: true, vaiven: false, ahora: 1.1, ultimo: 1))
        XCTAssertTrue(PoliticaLetraBloqueo.actualizar(palabras: 2, mostradas: 1, iluminar: true, vaiven: false, ahora: 1.5, ultimo: 1))
        XCTAssertFalse(PoliticaLetraBloqueo.actualizar(palabras: 2, mostradas: 2, iluminar: true, vaiven: false, ahora: 2, ultimo: 1.5))
    }
    func testVaivenLimitadoYSaltoAtras() {
        XCTAssertFalse(PoliticaLetraBloqueo.actualizar(palabras: 2, mostradas: 2, iluminar: false, vaiven: true, ahora: 1.2, ultimo: 1))
        XCTAssertTrue(PoliticaLetraBloqueo.actualizar(palabras: 2, mostradas: 2, iluminar: false, vaiven: true, ahora: 1.6, ultimo: 1))
        XCTAssertTrue(PoliticaLetraBloqueo.actualizar(palabras: 1, mostradas: 8, iluminar: true, vaiven: false, ahora: 3, ultimo: 1))
    }
    func testPortadaNoTieneVersoTiempoNiPausaEnSuIdentidad() {
        let clave = PoliticaLetraBloqueo.clavePortada(cancion: 7, fuente: "cover.jpg?v=2", estilo: "oscuro")
        for _ in 0..<100 {
            XCTAssertEqual(clave, PoliticaLetraBloqueo.clavePortada(cancion: 7, fuente: "cover.jpg?v=2", estilo: "oscuro"))
        }
        XCTAssertNotEqual(clave, PoliticaLetraBloqueo.clavePortada(cancion: 8, fuente: "cover.jpg?v=2", estilo: "oscuro"))
        XCTAssertNotEqual(clave, PoliticaLetraBloqueo.clavePortada(cancion: 7, fuente: "cover.jpg?v=3", estilo: "oscuro"))
    }
    func testTarjetaAcotaPayloadSinRomperUnicode() {
        let texto = String(repeating: "🎵á", count: 1000)
        let reducido = PoliticaLetraBloqueo.textoActividad(texto, bytes: 900)
        XCTAssertLessThanOrEqual(reducido.utf8.count, 900)
        XCTAssertFalse(reducido.contains("�"))
        XCTAssertEqual(PoliticaLetraBloqueo.textoActividad(texto, bytes: 0), "")
        XCTAssertEqual(PoliticaLetraBloqueo.textoActividad("Verso", bytes: 900), "Verso")
    }
}
