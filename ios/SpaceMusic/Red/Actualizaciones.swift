import Foundation
import Observation
import UIKit
import UserNotifications

/// Actualizaciones de la app de iPhone, como el aviso de la app de Android.
///
/// Cada compilación correcta de GitHub Actions (rama app-ios) publica en la versión «ios-latest» del repositorio
/// el `SpaceMusic.ipa` y un `version-ios.json` con su número. La app lo consulta al abrirse y en segundo plano;
/// si hay uno más nuevo lo avisa y ofrece instalarlo.
///
/// Un iPhone no puede instalar un .ipa por sí solo: hace falta firmarlo con tu Apple ID. Con **SideStore** o
/// **AltStore** en el teléfono basta un toque («Instalar»); sin ellos se descarga el .ipa para Sideloadly.
@MainActor
@Observable
final class Actualizaciones {
    static let compartida = Actualizaciones()

    struct Version: Decodable, Equatable {
        var code: Int
        var nombre: String
        var ipa: String
        var notas: String?
        var fecha: String?
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            code = c.entero("code") ?? 0
            nombre = c.texto("nombre") ?? ""
            ipa = c.texto("ipa") ?? ""
            notas = c.texto("notas")
            fecha = c.texto("fecha")
        }
    }

    static let fuente = URL(string: "https://github.com/JosueEsc7676/SpaceMusic/releases/download/ios-latest/version-ios.json")!

    private(set) var disponible: Version?
    private(set) var revisando = false
    private(set) var ultimoError: String?

    /// Número de compilación de esta app (lo pone GitHub Actions; 1 en compilaciones locales).
    var compilacionActual: Int { Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0 }
    var versionActual: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        return "\(v) (\(compilacionActual))"
    }

    func revisar(avisar: Bool) async {
        guard !revisando else { return }
        revisando = true
        defer { revisando = false }
        var pedido = URLRequest(url: Self.fuente, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        pedido.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (datos, respuesta) = try await URLSession.shared.data(for: pedido)
            guard (respuesta as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            let v = try JSONDecoder().decode(Version.self, from: datos)
            ultimoError = nil
            guard v.code > compilacionActual, URL(string: v.ipa) != nil else { disponible = nil; return }
            disponible = v
            let clave = "actualizacionAvisada"
            if avisar, UserDefaults.standard.integer(forKey: clave) != v.code {
                UserDefaults.standard.set(v.code, forKey: clave)
                let c = UNMutableNotificationContent()
                c.title = "Nueva versión de SpaceMusic"
                c.body = "La versión \(v.nombre) está lista para instalar." + (v.notas.map { " \($0)" } ?? "")
                c.sound = .default
                c.userInfo = ["ruta": "#/actualizacion"]
                try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "actualizacion-\(v.code)", content: c, trigger: nil))
            }
        } catch {
            ultimoError = "No se pudo comprobar si hay versión nueva"
        }
    }

    enum Instalador: String { case sidestore, altstore }

    /// Abre SideStore o AltStore con el enlace del .ipa. Devuelve false si esa app no está instalada.
    func instalar(con instalador: Instalador) async -> Bool {
        guard let v = disponible, let ipa = URL(string: v.ipa),
              let url = URL(string: "\(instalador.rawValue)://install?url=\(ipa.absoluteString.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")")
        else { return false }
        return await UIApplication.shared.open(url)
    }

    /// Descarga el .ipa en Safari (para instalarlo con Sideloadly desde la computadora).
    func descargar() {
        guard let v = disponible, let ipa = URL(string: v.ipa) else { return }
        UIApplication.shared.open(ipa)
    }
}
