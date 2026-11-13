import Foundation
import Observation
import UIKit
import UserNotifications
import CryptoKit

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
        var sha256: String?
        init(from decoder: Decoder) throws {
            let c = try decoder.campos()
            code = c.entero("code") ?? 0
            nombre = c.texto("nombre") ?? ""
            ipa = c.texto("ipa") ?? ""
            notas = c.texto("notas")
            fecha = c.texto("fecha")
            sha256 = c.texto("sha256")
        }
    }

    static let fuente = URL(string: "https://github.com/JosueEsc7676/SpaceMusic/releases/download/ios-latest/version-ios.json")!

    private(set) var disponible: Version?
    private(set) var revisando = false
    private(set) var ultimoError: String?
    private(set) var descargando = false
    private(set) var progreso: Double = 0
    private(set) var archivo: URL?
    @ObservationIgnored private var tareaDescarga: Task<Void, Never>?

    /// Solo enlaces HTTPS públicos de distribución; nunca se adjunta el token de la cuenta.
    private func enlaceIPA(_ texto: String) -> URL? {
        guard let u = URL(string: texto), u.scheme == "https", u.host != nil,
              u.user == nil, u.password == nil else { return nil }
        return u
    }

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
        do {
            let v = try await leerVersion()
            ultimoError = nil
            guard v.code > compilacionActual, enlaceIPA(v.ipa) != nil else { disponible = nil; return }
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

    private func leerVersion() async throws -> Version {
        // El servidor distribuye la copia publicada; GitHub queda de respaldo si no responde.
        let fuentes = [API.url("/app/version-ios.json"), Self.fuente].compactMap { $0 }
        var error: Error = URLError(.badServerResponse)
        for fuente in fuentes {
            do {
                var r = URLRequest(url: fuente, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
                r.setValue("application/json", forHTTPHeaderField: "Accept")
                let (datos, respuesta) = try await URLSession.shared.data(for: r)
                guard (respuesta as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                let v = try JSONDecoder().decode(Version.self, from: datos)
                guard v.code > 0, enlaceIPA(v.ipa) != nil else { throw URLError(.cannotParseResponse) }
                return v
            } catch is CancellationError { throw CancellationError() }
            catch let e { error = e }
        }
        throw error
    }

    enum Instalador: String { case sidestore, altstore }

    /// Abre SideStore o AltStore con el enlace del .ipa. Devuelve false si esa app no está instalada.
    func instalar(con instalador: Instalador) async -> Bool {
        guard let v = disponible, let ipa = URL(string: v.ipa),
              let url = URL(string: "\(instalador.rawValue)://install?url=\(ipa.absoluteString.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")")
        else { return false }
        return await UIApplication.shared.open(url)
    }

    /// Descarga dentro de la app y permite guardarla en Archivos o enviarla al instalador.
    func descargar() {
        guard !descargando, let v = disponible, let ipa = enlaceIPA(v.ipa) else { return }
        descargando = true; progreso = 0; ultimoError = nil; archivo = nil
        tareaDescarga = Task {
            defer { descargando = false; tareaDescarga = nil }
            do {
                let delegado = ProgresoIPA { [weak self] fraccion in
                    Task { @MainActor in self?.progreso = fraccion }
                }
                var pedido = URLRequest(url: ipa, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 300)
                pedido.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
                let (temporal, respuesta) = try await URLSession.shared.download(for: pedido, delegate: delegado)
                defer { try? FileManager.default.removeItem(at: temporal) }
                guard let http = respuesta as? HTTPURLResponse, http.statusCode == 200 else {
                    throw ErrorAPI(mensaje: "La actualización no está disponible. Intenta de nuevo en unos minutos.", estado: (respuesta as? HTTPURLResponse)?.statusCode ?? 0)
                }
                // Un error HTML de GitHub o del proxy no debe guardarse como si fuera una app.
                let lector = try FileHandle(forReadingFrom: temporal)
                let firma = try lector.read(upToCount: 4)
                try lector.close()
                guard firma == Data([0x50, 0x4b, 0x03, 0x04]) else {
                    throw ErrorAPI(mensaje: "El servidor no entregó un IPA válido. Vuelve a descargarlo.", estado: 0)
                }
                if let esperada = v.sha256, !esperada.isEmpty {
                    let archivo = try FileHandle(forReadingFrom: temporal)
                    defer { try? archivo.close() }
                    var hash = SHA256()
                    while let bloque = try archivo.read(upToCount: 1024 * 1024), !bloque.isEmpty { hash.update(data: bloque) }
                    let real = hash.finalize().map { String(format: "%02x", $0) }.joined()
                    guard real == esperada.lowercased() else {
                        throw ErrorAPI(mensaje: "La descarga llegó incompleta o cambió de versión. Busca actualizaciones y reintenta.", estado: 0)
                    }
                }
                try Task.checkCancellation()
                let carpeta = FileManager.default.temporaryDirectory.appendingPathComponent("Actualizacion-\(v.code)", isDirectory: true)
                try FileManager.default.createDirectory(at: carpeta, withIntermediateDirectories: true)
                let destino = carpeta.appendingPathComponent("SpaceMusic.ipa")
                if FileManager.default.fileExists(atPath: destino.path) { try FileManager.default.removeItem(at: destino) }
                try FileManager.default.moveItem(at: temporal, to: destino)
                archivo = destino; progreso = 1
            } catch is CancellationError {
            } catch let e as URLError where e.code == .cancelled {
            } catch { ultimoError = error.localizedDescription }
        }
    }

    func cancelarDescarga() { tareaDescarga?.cancel() }
}

private final class ProgresoIPA: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    let actualizar: @Sendable (Double) -> Void
    init(actualizar: @escaping @Sendable (Double) -> Void) { self.actualizar = actualizar }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesExpectedToWrite > 0 { actualizar(min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))) }
    }
}
