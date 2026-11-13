import XCTest
@testable import ModelosSpaceMusic

final class Contratos35Tests: XCTestCase {
    func testDedicatoriaProgramadaConservaMilisegundos() throws {
        let datos = Data(#"{"id":12,"programadaPara":1800000000000,"segundo":18,"segundoFin":34,"letra":"Fragmento","completa":false}"#.utf8)
        let d = try JSONDecoder().decode(Dedicatoria.self, from: datos)
        XCTAssertEqual(d.programadaPara, 1800000000000)
        XCTAssertEqual(d.segundoFin, 34)
        XCTAssertEqual(d.lineas, ["Fragmento"])
    }
    func testVersionAnteriorNoExigeCamposNuevos() throws {
        let d = try JSONDecoder().decode(Dedicatoria.self, from: Data(#"{"id":1}"#.utf8))
        XCTAssertNil(d.programadaPara)
        let r = try JSONDecoder().decode(ResumenEscucha.self, from: Data(#"{"minutos":15}"#.utf8))
        XCTAssertEqual(r.minutos, 15)
        XCTAssertNil(r.cambioPorcentaje)
        XCTAssertNil(r.descubrimiento)
    }
    func testResumenNuevoLeeComparacionYDescubrimiento() throws {
        let r = try JSONDecoder().decode(ResumenEscucha.self, from: Data(#"{"cambioPorcentaje":-22.5,"descubrimiento":{"nombre":"Artista","minutos":46}}"#.utf8))
        XCTAssertEqual(r.cambioPorcentaje, -22.5)
        XCTAssertEqual(r.descubrimiento?.nombre, "Artista")
        XCTAssertEqual(r.descubrimiento?.minutos, 46)
    }
}
