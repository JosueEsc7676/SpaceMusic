import Foundation

/// Dirección de respaldo (Cloudflare Tunnel) para cuando la principal (Tailscale Funnel) no responde.
/// - Mientras la principal funciona, se guarda la dirección de respaldo que entrega el servidor.
/// - Si la principal falla, se prueba la guardada; si no responde, se lee la última publicada en ntfy.sh.
/// - Mientras se usa el respaldo, cada 2 minutos se revisa si volvió la principal y se regresa a ella.
enum Respaldo {
    /// Tema privado de ntfy donde el servidor publica la dirección de respaldo.
    static let tema = "spacemusic-lbBZngG2Udw6qCsGIYu32w"
    private static let clave = "respaldoServidor"
    private static let claveModo = "modoConexion"

    /// «tailscale» o «cloudflare»: la dirección que eligió un administrador para todas las apps.
    static var modoCloudflare: Bool { UserDefaults.standard.string(forKey: claveModo) == "cloudflare" }
    nonisolated(unsafe) private static var buscando: Task<String?, Never>?
    nonisolated(unsafe) private static var vigilando = false

    static var usando: Bool { Sesion.compartida.servidor != Sesion.servidorPorDefecto }

    private static func valida(_ u: String) -> Bool {
        u.range(of: "^https://[a-z0-9-]{3,80}[.]trycloudflare[.]com$", options: .regularExpression) != nil
    }

    /// ¿Responde este servidor? (petición corta, sin la sesión)
    static func responde(_ base: String) async -> Bool {
        guard let u = URL(string: base + "/api/auth/estado") else { return false }
        var r = URLRequest(url: u)
        r.timeoutInterval = 6
        guard let (_, resp) = try? await URLSession.shared.data(for: r) else { return false }
        return (resp as? HTTPURLResponse)?.statusCode == 200
    }

    /// Guarda la dirección de respaldo y el modo que entrega el servidor (por la dirección en uso) y obedece el modo.
    static func recordar() async {
        guard let u = URL(string: Sesion.compartida.servidor + "/api/servidor/respaldo") else { return }
        var r = URLRequest(url: u)
        r.timeoutInterval = 8
        struct R: Decodable { let url: String?; let modo: String? }
        guard let (datos, _) = try? await URLSession.shared.data(for: r),
              let x = try? JSONDecoder().decode(R.self, from: datos) else { return }
        if let url = x.url, valida(url) { UserDefaults.standard.set(url, forKey: clave) }
        UserDefaults.standard.set(x.modo == "cloudflare" ? "cloudflare" : "tailscale", forKey: claveModo)
        if modoCloudflare, !usando {
            _ = await activar()
        } else if !modoCloudflare, usando, !Casa.activa, await responde(Sesion.servidorPorDefecto) {
            await MainActor.run { Sesion.compartida.servidor = Sesion.servidorPorDefecto }
        }
    }

    /// Busca una dirección de respaldo que responda y la pone en uso. Devuelve la nueva base (o nil).
    static func activar() async -> String? {
        if let t = buscando { return await t.value }
        let t = Task<String?, Never> {
            var candidatas: [String] = []
            if let g = UserDefaults.standard.string(forKey: clave), valida(g) { candidatas.append(g) }
            if let u = URL(string: "https://ntfy.sh/\(tema)/json?poll=1&since=24h"),
               let (datos, _) = try? await URLSession.shared.data(from: u),
               let texto = String(data: datos, encoding: .utf8) {
                struct M: Decodable { let message: String? }
                struct J: Decodable { let url: String?; let modo: String? }
                // Antes se publicaba solo la dirección; ahora {"url": …, "modo": …}
                var ultima: String?
                for m in texto.split(separator: "\n").compactMap({ try? JSONDecoder().decode(M.self, from: Data($0.utf8)).message }) {
                    if m.hasPrefix("{"), let j = try? JSONDecoder().decode(J.self, from: Data(m.utf8)) {
                        if let u = j.url, valida(u) { ultima = u }
                        UserDefaults.standard.set(j.modo == "cloudflare" ? "cloudflare" : "tailscale", forKey: claveModo)
                    } else if valida(m) { ultima = m }
                }
                if let ultima, !candidatas.contains(ultima) { candidatas.insert(ultima, at: 0) }
            }
            for c in candidatas where await responde(c) {
                UserDefaults.standard.set(c, forKey: clave)
                await MainActor.run {
                    Sesion.compartida.servidor = c
                    Avisos.compartido.mostrar("Conectado por la dirección de respaldo")
                }
                vigilarPrincipal()
                return c
            }
            return nil
        }
        buscando = t
        let r = await t.value
        buscando = nil
        return r
    }

    /// Mientras se usa el respaldo: regresa a la principal en cuanto vuelva a responder.
    private static func vigilarPrincipal() {
        guard !vigilando else { return }
        vigilando = true
        Task.detached {
            while usando && !Casa.activa {
                try? await Task.sleep(for: .seconds(120))
                if !modoCloudflare, await responde(Sesion.servidorPorDefecto) {
                    await MainActor.run { Sesion.compartida.servidor = Sesion.servidorPorDefecto }
                    break
                }
            }
            vigilando = false
        }
    }

    /// Misma petición, pero hacia la dirección que está en uso ahora.
    static func redirigir(_ r: URLRequest) -> URLRequest {
        guard let u = r.url, var c = URLComponents(url: u, resolvingAgainstBaseURL: false),
              let base = URLComponents(string: Sesion.compartida.servidor) else { return r }
        c.scheme = base.scheme
        c.host = base.host
        c.port = base.port
        var nueva = r
        nueva.url = c.url
        return nueva
    }
}
