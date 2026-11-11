import XCTest
@testable import ModelosSpaceMusic

final class ModelosTests: XCTestCase {
    func testCancionConCamposIncompletosYTiposMixtos() throws {
        let json = Data(#"{"id":"42","duracionSegundos":"183","anio":2024}"#.utf8)
        let c = try JSONDecoder().decode(Cancion.self, from: json)
        XCTAssertEqual(c.id, 42)
        XCTAssertEqual(c.duracionSegundos, 183)
        XCTAssertEqual(c.nombre, "Sin título")
        XCTAssertEqual(c.anio, 2024)
        XCTAssertFalse(c.tieneLetra)
    }

    func testCancionSinIdentificadorSeRechaza() {
        XCTAssertThrowsError(try JSONDecoder().decode(Cancion.self, from: Data(#"{"nombre":"Sin id"}"#.utf8)))
    }

    func testListaDescartaElementoInvalidoSinPerderLosSiguientes() throws {
        let json = Data(#"{"id":1,"canciones":[{"id":2},{"nombre":"Inválida"},null,{"id":3}]}"#.utf8)
        let p = try JSONDecoder().decode(Playlist.self, from: json)
        XCTAssertEqual(p.canciones.map(\.id), [2, 3])
    }

    func testColaPuedeGuardarseYRecuperarse() throws {
        let c = try JSONDecoder().decode(Cancion.self, from: Data(#"{"id":9,"nombre":"Prueba","artista":"Artista","duracionSegundos":200}"#.utf8))
        let datos = try JSONEncoder().encode([c])
        let recuperadas = try JSONDecoder().decode([Cancion].self, from: datos)
        XCTAssertEqual(recuperadas.first?.nombre, "Prueba")
        XCTAssertEqual(recuperadas.first?.duracionSegundos, 200)
    }

    func testLRCOrdenaMarcasMultiplesYConservaTextoUnicode() {
        let l = LectorLRC.leer("[ar:Artista]\n[00:03.500] Última 🎵\n[00:01.25][00:02.125] Corazón\nTexto sin tiempo")
        XCTAssertEqual(l.map(\.id), [0, 1, 2])
        XCTAssertEqual(l.map(\.texto), ["Corazón", "Corazón", "Última 🎵"])
        XCTAssertEqual(l[0].tiempo, 1.25, accuracy: 0.001)
        XCTAssertEqual(l[1].tiempo, 2.125, accuracy: 0.001)
        XCTAssertEqual(l[2].tiempo, 3.5, accuracy: 0.001)
    }

    func testLRCSinTiemposNoInventaLineas() {
        XCTAssertTrue(LectorLRC.leer("[ar:Artista]\nLetra sin sincronización").isEmpty)
        XCTAssertTrue(LectorLRC.leer("").isEmpty)
    }
}
