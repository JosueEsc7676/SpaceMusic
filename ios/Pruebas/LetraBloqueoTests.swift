import XCTest
@testable import ModelosSpaceMusic

final class LetraBloqueoTests: XCTestCase {
    func testLetraNuncaReemplazaVideoNiEnPantallaCompleta() {
        XCTAssertFalse(PoliticaLetraBloqueo.usarVideo(pantallaCompleta: true, conLetra: true))
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
}
