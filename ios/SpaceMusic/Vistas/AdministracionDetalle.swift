import SwiftUI

/// Respuestas de soporte con campos opcionales y estructuras variables del servidor.
enum ValorAdmin: Decodable {
    case texto(String), numero(Double), booleano(Bool), objeto([String: ValorAdmin]), lista([ValorAdmin]), nulo
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .nulo }
        else if let b = try? c.decode(Bool.self) { self = .booleano(b) }
        else if let n = try? c.decode(Double.self) { self = .numero(n) }
        else if let s = try? c.decode(String.self) { self = .texto(s) }
        else if let a = try? c.decode([ValorAdmin].self) { self = .lista(a) }
        else { self = .objeto((try? c.decode([String: ValorAdmin].self)) ?? [:]) }
    }
    subscript(_ clave: String) -> ValorAdmin { if case .objeto(let d) = self { return d[clave] ?? .nulo }; return .nulo }
    var texto: String {
        switch self {
        case .texto(let s): return s
        case .numero(let n): return n.rounded() == n ? String(format: "%.0f", n) : String(format: "%.1f", n)
        case .booleano(let b): return b ? "Sí" : "No"
        default: return "—"
        }
    }
    var lista: [ValorAdmin] { if case .lista(let a) = self { return a }; return [] }
    var entero: Int { Int(texto) ?? 0 }
}

struct SoporteVista: View {
    @State private var sistema: ValorAdmin?
    @State private var errores: [ValorAdmin] = []
    @State private var error: String?
    @State private var confirmar = false
    @State private var ocupada = false
    var body: some View {
        List {
            if let error { Section { Text(error).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } } }
            if let s = sistema {
                Section("Servidor") {
                    datos(s, [("Versión", "version"), ("Sistema", "sistema"), ("Java", "java"), ("Segundos activo", "activoSegundos"), ("Procesadores", "procesadores"), ("Carga", "carga")])
                }
                Section("Recursos") {
                    datos(s, [("Memoria usada (MB)", "memoriaUsadaMb"), ("Memoria máxima (MB)", "memoriaMaxMb"), ("Disco libre (GB)", "discoLibreGb"), ("Disco total (GB)", "discoTotalGb")])
                }
                Section("Actividad") {
                    datos(s, [("Canciones", "canciones"), ("Usuarios", "usuarios"), ("Playlists", "playlists"), ("Mensajes", "mensajes"), ("Notificaciones", "notificaciones"), ("Escuchas en 24 horas", "reproducciones24h")])
                }
                Section("Conectados ahora") {
                    if s["enLinea"].lista.isEmpty { Text("Nadie conectado ahora").foregroundStyle(Tema.secundario) }
                    ForEach(Array(s["enLinea"].lista.enumerated()), id: \.offset) { _, u in
                        NavigationLink(value: Destino.usuarioAdmin(u["id"].entero)) {
                            LabeledContent(u["nombre"].texto, value: "\(u["dispositivos"].texto) dispositivos")
                        }
                    }
                }
            } else if error == nil { Cargando() }
            Section("Errores y advertencias") {
                if errores.isEmpty { Text("No hay errores registrados").foregroundStyle(Tema.secundario) }
                ForEach(Array(errores.enumerated()), id: \.offset) { _, e in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(e["nivel"].texto).font(.caption.bold()).foregroundStyle(Tema.rosa)
                        Text(e["mensaje"].texto).font(.subheadline).textSelection(.enabled)
                        if e["detalle"].texto != "—" { Text(e["detalle"].texto).font(.caption).textSelection(.enabled) }
                        Text(e["origen"].texto).font(.caption).foregroundStyle(Tema.secundario)
                        Text(e["fecha"].texto).font(.caption).foregroundStyle(Tema.secundario)
                    }
                }
                if !errores.isEmpty { Button("Vaciar registro", role: .destructive) { confirmar = true } }
            }
        }
        .navigationTitle("Soporte")
        .disabled(ocupada)
        .task { await cargar() }
        .refreshable { await cargar() }
        .confirmationDialog("¿Vaciar el registro de errores?", isPresented: $confirmar, titleVisibility: .visible) {
            Button("Vaciar", role: .destructive) {
                Task {
                    ocupada = true; defer { ocupada = false }
                    do { try await API.hacer("DELETE", "/api/admin/errores"); await cargar() }
                    catch { self.error = error.localizedDescription }
                }
            }
        }
    }
    private func datos(_ s: ValorAdmin, _ filas: [(String, String)]) -> some View {
        ForEach(filas, id: \.1) { fila in LabeledContent(fila.0, value: s[fila.1].texto) }
    }
    private func cargar() async {
        do {
            async let s: ValorAdmin = API.get("/api/admin/sistema")
            async let e: [ValorAdmin] = API.get("/api/admin/errores")
            let resultado = try await (s, e)
            sistema = resultado.0; errores = resultado.1; error = nil
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

struct UsuarioAdminVista: View {
    let id: Int
    @State private var usuario: ValorAdmin?
    @State private var error: String?
    @State private var accion: String?
    @State private var ocupada = false
    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } }
            if let u = usuario {
                Section(u["nombreDisplay"].texto) {
                    ForEach([("Usuario", "username"), ("Correo", "email"), ("Rol", "rol"), ("Activa", "activo"), ("Estado", "estadoCuenta"), ("Correo verificado", "emailVerificado"), ("Registro", "fechaRegistro"), ("Último acceso", "ultimoLogin")], id: \.1) { f in
                        LabeledContent(f.0, value: u[f.1].texto)
                    }
                }
                Section("Actividad") {
                    ForEach([("Dispositivos conectados", "dispositivosConectados"), ("Escuchas", "reproducciones"), ("Favoritos", "favoritos"), ("Playlists", "playlists"), ("Seguidores", "seguidores"), ("Siguiendo", "siguiendo"), ("Mensajes sin leer", "mensajesSinLeer"), ("Notificaciones sin leer", "notificacionesSinLeer")], id: \.1) { f in
                        LabeledContent(f.0, value: u[f.1].texto)
                    }
                }
                Section("Últimas canciones") {
                    ForEach(Array(u["ultimasCanciones"].lista.enumerated()), id: \.offset) { _, c in
                        VStack(alignment: .leading) { Text(c["nombre"].texto); Text(c["artista"].texto).font(.caption).foregroundStyle(Tema.secundario) }
                    }
                }
                Section {
                    Button("Enviar correo para cambiar contraseña") { accion = "recuperar" }
                    if id != Sesion.compartida.usuario?.id { Button("Cerrar todas sus sesiones", role: .destructive) { accion = "cerrar-sesiones" } }
                }
            } else if error == nil { Cargando() }
        }
        .disabled(ocupada)
        .navigationTitle("Detalle de usuario")
        .task { await cargar() }.refreshable { await cargar() }
        .confirmationDialog(accion == "recuperar" ? "¿Enviar correo de recuperación?" : "¿Cerrar todas sus sesiones?", isPresented: Binding(get: { accion != nil }, set: { if !$0 { accion = nil } }), titleVisibility: .visible) {
            Button("Confirmar") { if let ruta = accion { Task { await ejecutar(ruta) } } }
        }
    }
    private func cargar() async {
        do { usuario = try await API.get("/api/admin/usuarios/\(id)/detalle"); error = nil }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func ejecutar(_ ruta: String) async {
        guard !ocupada else { return }; ocupada = true; defer { ocupada = false }
        do { try await API.hacer("POST", "/api/admin/usuarios/\(id)/\(ruta)"); Avisos.compartido.mostrar("Listo"); await cargar() }
        catch { self.error = error.localizedDescription }
    }
}

struct GenerosAdminVista: View {
    @State private var resumen: ValorAdmin?
    @State private var artista = ""
    @State private var genero = ""
    @State private var error: String?
    @State private var ocupada = false
    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } }
            if let r = resumen {
                Section("Resumen") {
                    LabeledContent("Canciones", value: r["totalCanciones"].texto)
                    LabeledContent("Con género", value: r["conGenero"].texto)
                    LabeledContent("Artistas sin género", value: r["artistasSinGenero"].texto)
                    Button(r["buscando"].texto == "Sí" ? "Buscar otra vez" : "Buscar géneros en internet") { Task { await buscar() } }
                }
                Section("Por familia") {
                    ForEach(familias(r), id: \.0) { f in LabeledContent(f.0.capitalized, value: "\(f.1) canciones") }
                }
                Section("Géneros") {
                    ForEach(Array(r["generos"].lista.enumerated()), id: \.offset) { _, g in
                        NavigationLink(value: Destino.genero(g["genero"].texto)) {
                            VStack(alignment: .leading) {
                                Text(g["genero"].texto)
                                Text("\(g["canciones"].texto) canciones · \(g["artistas"].texto) artistas").font(.caption).foregroundStyle(Tema.secundario)
                            }
                        }
                    }
                }
                Section("Artistas sin género") {
                    ForEach(Array(r["sinGenero"].lista.enumerated()), id: \.offset) { _, a in
                        Button { artista = a["artista"].texto } label: { LabeledContent(a["artista"].texto, value: a["canciones"].texto) }
                    }
                }
            } else if error == nil { Cargando() }
            Section("Asignar género a un artista") {
                TextField("Artista", text: $artista)
                TextField("Género", text: $genero)
                Button("Guardar para todas sus canciones") { Task { await guardar() } }
                    .disabled(artista.trimmingCharacters(in: .whitespaces).isEmpty || genero.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .disabled(ocupada).navigationTitle("Géneros")
        .task { await cargar() }.refreshable { await cargar() }
    }
    private func familias(_ r: ValorAdmin) -> [(String, Int)] {
        var valores: [String: Int] = [:]
        for g in r["generos"].lista { valores[g["familia"].texto, default: 0] += g["canciones"].entero }
        return valores.map { ($0.key, $0.value) }.sorted { $0.1 > $1.1 }
    }
    private func cargar() async {
        do { resumen = try await API.get("/api/admin/generos"); error = nil }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func buscar() async {
        guard !ocupada else { return }; ocupada = true; defer { ocupada = false }
        do { try await API.hacer("POST", "/api/admin/generos/buscar"); await cargar() }
        catch { self.error = error.localizedDescription }
    }
    private func guardar() async {
        guard !ocupada else { return }; ocupada = true; defer { ocupada = false }
        do {
            try await API.hacer("PUT", "/api/admin/generos/artista", ["artista": artista, "genero": genero])
            Avisos.compartido.mostrar("Género guardado"); await cargar()
        } catch { self.error = error.localizedDescription }
    }
}
