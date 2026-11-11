import Foundation
import Observation
import Security

/// Cuenta abierta, token y dirección del servidor.
@Observable
final class Sesion {
    static let compartida = Sesion()

    static let servidorPorDefecto = "https://spacemusic.tail8ccfdf.ts.net"

    /// Dirección del servidor, sin "/" al final.
    var servidor: String {
        didSet { UserDefaults.standard.set(servidor, forKey: "servidor") }
    }
    private(set) var token: String?
    private(set) var usuario: Usuario?
    /// Motivo del último cierre de sesión (para mostrarlo en la pantalla de entrada).
    var motivoSalida: String?

    var conectado: Bool { token != nil && usuario != nil }
    var rol: String { usuario?.rol ?? "USER" }
    var esAdmin: Bool { rol == "ADMIN" }
    var puedeEditar: Bool { rol == "ADMIN" || rol == "COLABORADOR" }

    private init() {
        servidor = UserDefaults.standard.string(forKey: "servidor") ?? Sesion.servidorPorDefecto
        token = Llavero.leer("token")
        if let datos = UserDefaults.standard.data(forKey: "usuario") {
            usuario = try? JSONDecoder().decode(Usuario.self, from: datos)
        }
        if token != nil, !tokenVigente() { token = nil }
    }

    func guardar(token nuevo: String?, usuario u: Usuario?) {
        if let nuevo {
            token = nuevo
            Llavero.guardar("token", nuevo)
        }
        if let u { ponerUsuario(u) }
    }

    func ponerUsuario(_ u: Usuario) {
        usuario = u
        if let datos = try? JSONEncoder().encode(u) { UserDefaults.standard.set(datos, forKey: "usuario") }
    }

    /// Cierra la sesión en este iPhone. Las descargas de la cuenta se borran.
    @MainActor
    func cerrar(motivo: String? = nil) {
        Reproductor.compartido.detener()
        Eventos.compartido.desconectar()
        Descargas.compartido.borrarTodo()
        Biblioteca.compartida.limpiar()
        Navegacion.compartida.reiniciar()
        Eventos.compartido.limpiar()
        Llavero.borrar("token")
        UserDefaults.standard.removeObject(forKey: "usuario")
        token = nil
        usuario = nil
        motivoSalida = motivo
    }

    /// true si el token no ha expirado.
    func tokenVigente() -> Bool {
        guard let token else { return false }
        let partes = token.split(separator: ".")
        guard partes.count > 1 else { return false }
        var b64 = String(partes[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let datos = Data(base64Encoded: b64),
              let json = try? JSONSerialization.jsonObject(with: datos) as? [String: Any] else { return false }
        guard let exp = json["exp"] as? Double else { return true }
        return exp > Date().timeIntervalSince1970 + 5
    }

    /// Segundos que le quedan al token (para renovarlo antes de que expire).
    func segundosRestantes() -> Double? {
        guard let token else { return nil }
        let partes = token.split(separator: ".")
        guard partes.count > 1 else { return nil }
        var b64 = String(partes[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let datos = Data(base64Encoded: b64),
              let json = try? JSONSerialization.jsonObject(with: datos) as? [String: Any],
              let exp = json["exp"] as? Double else { return nil }
        return exp - Date().timeIntervalSince1970
    }
}

/// Guarda el token en el llavero del iPhone (no en archivos normales).
enum Llavero {
    private static let servicio = "com.bpm.spacemusic"

    static func guardar(_ clave: String, _ valor: String) {
        borrar(clave)
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: servicio,
            kSecAttrAccount as String: clave,
            kSecValueData as String: Data(valor.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func leer(_ clave: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: servicio,
            kSecAttrAccount as String: clave,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var r: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &r) == errSecSuccess, let d = r as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func borrar(_ clave: String) {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: servicio,
            kSecAttrAccount as String: clave,
        ]
        SecItemDelete(q as CFDictionary)
    }
}
