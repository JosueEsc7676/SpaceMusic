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
}
