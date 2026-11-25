import XCTest
@testable import ModelosSpaceMusic
final class SecuenciaPortadaTests: XCTestCase {
    let lineas = [LineaLetra(id: 0, tiempo: 2, texto: "Uno", fin: 4),
                  LineaLetra(id: 1, tiempo: 7, texto: "Dos tres", fin: 10)]
    func testTodosLosVersosEnUnaSolaLineaDeTiempoSinCambiarArtwork() {
        XCTAssertEqual(SecuenciaPortada.tiempos(lineas, desde: 0, hasta: 12, palabras: false), [0,2,4,7,10,12])
        XCTAssertEqual(SecuenciaPortada.linea(lineas, en: 2)?.texto, "Uno")
        XCTAssertNil(SecuenciaPortada.linea(lineas, en: 5))
        XCTAssertEqual(SecuenciaPortada.linea(lineas, en: 8)?.texto, "Dos tres")
    }
    func testRecorteDesdePosicionActualYPalabrasOrdenadas() {
        let marcas = SecuenciaPortada.tiempos(lineas, desde: 8, hasta: 12, palabras: true)
        XCTAssertEqual(marcas.first, 0)
        XCTAssertEqual(marcas.last, 4)
        XCTAssertEqual(marcas, Array(Set(marcas)).sorted())
        XCTAssertTrue(marcas.allSatisfy { $0 >= 0 && $0 <= 4 })
    }
}
