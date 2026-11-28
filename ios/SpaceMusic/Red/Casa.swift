import Foundation

/// Solo administradores: conexión directa al servidor dentro de la red de casa (Wi-Fi), sin pasar por internet.
/// - Con la sesión abierta se guardan las direcciones locales que da el servidor (`/api/admin/conexion` → `local`).
/// - Al abrir la app se prueba si alguna responde (1,5 s) como este mismo servidor; si responde, se ofrece
///   conectarse directo y, si se eligió «Siempre», se conecta solo.
/// - Si la conexión de casa deja de responder (saliste de casa), `API.traerConRespaldo` vuelve a la principal.
enum Casa {
    private static let claveLista = "casaDirecciones"
    private static let claveAuto = "casaAuto"
    nonisolated(unsafe) private static var preguntada = false
    nonisolated(unsafe) private static var revisando = false

    /// ¿Se está usando la dirección de casa?
    static var activa: Bool { Sesion.compartida.servidor.hasPrefix("http://") }

    private static func valida(_ u: String) -> Bool {
        // Solo direcciones IP privadas (ATS las permite sin HTTPS con NSAllowsLocalNetworking)
        u.range(of: #"^http://(192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}):\d{2,5}$"#,
                options: .regularExpression) != nil
    }

    /// Guarda las direcciones de casa (con la sesión de administrador, por la dirección en uso) y revisa.
    static func revisar() async {
        guard Sesion.compartida.esAdmin, !activa, !revisando, UserDefaults.standard.string(forKey: claveAuto) != "no" else { return }
        revisando = true
        defer { revisando = false }
        struct C: Decodable { let local: [String]? }
        if let c: C = try? await API.get("/api/admin/conexion", tiempo: 8) {
            let l = (c.local ?? []).filter(valida)
            UserDefaults.standard.set(l, forKey: claveLista)
        }
        let lista = UserDefaults.standard.stringArray(forKey: claveLista) ?? []
        guard let encontrada = await primeraQueResponde(lista) else { return }
        if UserDefaults.standard.string(forKey: claveAuto) == "si" {
            await conectar(encontrada)
            return
        }
        guard !preguntada else { return }
        preguntada = true
        await MainActor.run {
            Avisos.compartido.mostrar("Estás en casa: conéctate directo al servidor (más rápido)",
                                      accion: (titulo: "Siempre", hacer: {
                UserDefaults.standard.set("si", forKey: claveAuto)
                Task { await conectar(encontrada) }
            }))
        }
    }

    private static func primeraQueResponde(_ lista: [String]) async -> String? {
        struct R: Decodable { let principal: String? }
        for u in lista {
            guard let url = URL(string: u + "/api/servidor/respaldo") else { continue }
            var r = URLRequest(url: url)
            r.timeoutInterval = 1.5
            guard let (datos, resp) = try? await URLSession.shared.data(for: r),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  let x = try? JSONDecoder().decode(R.self, from: datos),
                  x.principal?.lowercased() == Sesion.servidorPorDefecto.lowercased() else { continue }
            return u
        }
        return nil
    }

    private static func conectar(_ base: String) async {
        await MainActor.run {
            Sesion.compartida.servidor = base
            Avisos.compartido.mostrar("Conectado directo al servidor de casa")
        }
    }
}
