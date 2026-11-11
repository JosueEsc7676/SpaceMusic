import Foundation

struct ErrorAPI: LocalizedError {
    let mensaje: String
    let estado: Int
    var errorDescription: String? { mensaje }
}

/// Respuesta que no interesa leer.
struct Vacio: Decodable {}

/// Archivo para subir en un formulario.
struct ArchivoSubida {
    let campo: String
    let nombre: String
    let tipo: String
    let datos: Data
}

/// Comunicación con el servidor de SpaceMusic.
enum API {
    static let sesionURL: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 25
        c.waitsForConnectivity = false
        c.urlCache = URLCache(memoryCapacity: 20 << 20, diskCapacity: 200 << 20)
        return URLSession(configuration: c)
    }()

    static let decodificador = JSONDecoder()

    /// URL completa: acepta rutas como "/api/..." o direcciones http completas.
    static func url(_ ruta: String) -> URL? {
        if ruta.hasPrefix("http://") || ruta.hasPrefix("https://") { return URL(string: ruta) }
        let base = Sesion.compartida.servidor.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return URL(string: base + (ruta.hasPrefix("/") ? ruta : "/" + ruta))
    }

    /// URL del audio con el token (el reproductor no envía cabeceras).
    static func audioURL(_ id: Int) -> URL? {
        url("/api/canciones/\(id)/audio?t=\(codificar(Sesion.compartida.token ?? ""))")
    }

    static func descargaURL(_ id: Int) -> URL? {
        url("/api/canciones/\(id)/descargar?t=\(codificar(Sesion.compartida.token ?? ""))")
    }

    static func codificar(_ s: String) -> String {
        var permitidos = CharacterSet.urlQueryAllowed
        permitidos.remove(charactersIn: "&=+?/#")
        return s.addingPercentEncoding(withAllowedCharacters: permitidos) ?? s
    }

    /// Codifica un trozo de ruta, por ejemplo el nombre de un artista.
    static func segmento(_ s: String) -> String {
        var permitidos = CharacterSet.urlPathAllowed
        permitidos.remove(charactersIn: "/?#")
        return s.addingPercentEncoding(withAllowedCharacters: permitidos) ?? s
    }

    static func peticion(_ ruta: String, metodo: String = "GET", tiempo: TimeInterval? = nil) throws -> URLRequest {
        guard let u = url(ruta) else { throw ErrorAPI(mensaje: "Dirección del servidor no válida", estado: 0) }
        var r = URLRequest(url: u)
        r.httpMethod = metodo
        if let t = tiempo { r.timeoutInterval = t }
        if let token = Sesion.compartida.token { r.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        return r
    }

    // MARK: Peticiones

    static func get<T: Decodable>(_ ruta: String, tiempo: TimeInterval? = nil) async throws -> T {
        try await ejecutar(try peticion(ruta, tiempo: tiempo))
    }

    /// POST/PUT/DELETE con cuerpo JSON opcional.
    static func enviar<T: Decodable>(_ metodo: String, _ ruta: String, _ cuerpo: [String: Any]? = nil) async throws -> T {
        var r = try peticion(ruta, metodo: metodo)
        if let cuerpo {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try JSONSerialization.data(withJSONObject: cuerpo)
        }
        return try await ejecutar(r)
    }

    /// Igual que enviar pero sin leer la respuesta.
    static func hacer(_ metodo: String, _ ruta: String, _ cuerpo: [String: Any]? = nil) async throws {
        let _: Vacio = try await enviar(metodo, ruta, cuerpo)
    }

    /// Formulario con archivos (fotos, canciones).
    static func subir<T: Decodable>(_ ruta: String, metodo: String = "POST", archivos: [ArchivoSubida],
                                    campos: [String: String] = [:]) async throws -> T {
        var r = try peticion(ruta, metodo: metodo, tiempo: 600)
        let limite = "SM-\(UUID().uuidString)"
        r.setValue("multipart/form-data; boundary=\(limite)", forHTTPHeaderField: "Content-Type")
        var cuerpo = Data()
        func texto(_ s: String) { cuerpo.append(Data(s.utf8)) }
        for (k, v) in campos {
            texto("--\(limite)\r\nContent-Disposition: form-data; name=\"\(k)\"\r\n\r\n\(v)\r\n")
        }
        for a in archivos {
            let nombre = a.nombre.replacingOccurrences(of: "\"", with: "")
            texto("--\(limite)\r\nContent-Disposition: form-data; name=\"\(a.campo)\"; filename=\"\(nombre)\"\r\n")
            texto("Content-Type: \(a.tipo)\r\n\r\n")
            cuerpo.append(a.datos)
            texto("\r\n")
        }
        texto("--\(limite)--\r\n")
        let (datos, resp) = try await traer { try await sesionURL.upload(for: r, from: cuerpo) }
        return try interpretar(datos, resp, ruta: ruta)
    }

    private static func ejecutar<T: Decodable>(_ r: URLRequest) async throws -> T {
        let (datos, resp) = try await traer { try await sesionURL.data(for: r) }
        return try interpretar(datos, resp, ruta: r.url?.path ?? "")
    }

    private static func traer(_ f: () async throws -> (Data, URLResponse)) async throws -> (Data, URLResponse) {
        do {
            return try await f()
        } catch let e as URLError where e.code == .timedOut {
            throw ErrorAPI(mensaje: "La conexión está lenta. Inténtalo de nuevo.", estado: 0)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ErrorAPI(mensaje: "No hay conexión con el servidor", estado: 0)
        }
    }

    private static func interpretar<T: Decodable>(_ datos: Data, _ resp: URLResponse, ruta: String) throws -> T {
        let estado = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if estado == 401 && !ruta.hasPrefix("/api/auth/login") {
            Task { @MainActor in Sesion.compartida.cerrar(motivo: "Tu sesión expiró. Vuelve a entrar.") }
            throw ErrorAPI(mensaje: "Tu sesión expiró", estado: 401)
        }
        if !(200..<300).contains(estado) {
            var msg: String?
            if let json = try? JSONSerialization.jsonObject(with: datos) as? [String: Any] {
                msg = json["error"] as? String ?? json["message"] as? String
            }
            let porDefecto: String
            switch estado {
            case 403: porDefecto = "No tienes permiso para esto"
            case 404: porDefecto = "No encontrado"
            default: porDefecto = "Error del servidor (\(estado))"
            }
            throw ErrorAPI(mensaje: msg ?? porDefecto, estado: estado)
        }
        if T.self == Vacio.self { return Vacio() as! T }
        let cuerpo = datos.isEmpty ? Data("{}".utf8) : datos
        do {
            return try decodificador.decode(T.self, from: cuerpo)
        } catch {
            #if DEBUG
            print("No se pudo leer \(ruta): \(error)")
            #endif
            throw ErrorAPI(mensaje: "Respuesta inesperada del servidor", estado: estado)
        }
    }
}
