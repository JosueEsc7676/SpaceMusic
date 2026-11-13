import Foundation

/// Un enlace de sala enviado en un mensaje solo abre salas del servidor de la cuenta actual.
enum InvitacionSyncPlay {
    static func enlace(sala: Int, servidor: String) -> URL? {
        guard sala > 0, var partes = URLComponents(string: servidor), partes.host != nil else { return nil }
        partes.path = "/"; partes.query = nil; partes.fragment = "/juntos/\(sala)"
        return partes.url
    }

    static func sala(en texto: String, servidor: String) -> Int? {
        guard let base = URLComponents(string: servidor), let host = base.host else { return nil }
        let patron = #"https?://[^\s<>\"']+"#
        guard let rx = try? NSRegularExpression(pattern: patron) else { return nil }
        let ns = texto as NSString
        for m in rx.matches(in: texto, range: NSRange(location: 0, length: ns.length)) {
            let candidato = ns.substring(with: m.range).trimmingCharacters(in: CharacterSet(charactersIn: ".,;!)"))
            guard let u = URLComponents(string: candidato), u.host?.lowercased() == host.lowercased(),
                  u.scheme == base.scheme, u.port == base.port, u.user == nil, u.password == nil,
                  u.path == "/" || u.path.isEmpty, let fragmento = u.fragment else { continue }
            let partes = fragmento.split(separator: "/")
            guard partes.count == 2, partes[0] == "juntos", let id = Int(partes[1]), id > 0 else { continue }
            return id
        }
        return nil
    }
}
