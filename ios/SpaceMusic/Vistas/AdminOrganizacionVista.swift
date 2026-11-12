import SwiftUI

extension ValorAdmin {
    var objeto: [String: ValorAdmin] { if case .objeto(let d) = self { return d }; return [:] }
    var numero: Double? {
        switch self {
        case .numero(let n): return n
        case .texto(let s): return Double(s)
        default: return nil
        }
    }
    var verdadero: Bool { if case .booleano(let b) = self { return b }; return (numero ?? 0) != 0 }
    var esNulo: Bool { if case .nulo = self { return true }; return false }
    /// Texto o vacío (sin la raya «—»).
    var cadena: String { if case .texto(let s) = self { return s }; return esNulo ? "" : texto }
    /// Fecha ISO legible («12/11/26 18:40»).
    var fechaCorta: String {
        guard let d = Formato.fecha(cadena) else { return "—" }
        return d.formatted(date: .numeric, time: .shortened)
    }
}

/// Etiqueta de estado con color.
struct Chip: View {
    let texto: String
    var color: Color = Tema.secundario
    var body: some View {
        Text(texto).font(.caption2.weight(.bold)).foregroundStyle(color)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(Capsule().fill(color.opacity(0.15)))
            .fixedSize()
    }
}

/// Administración → Organización (organizacion-admin.js): organizador automático, catálogo, IA local (Ollama),
/// cambios de datos (aplicar, rechazar, revertir), vínculos con YouTube Music, etiquetas de la IA, proveedores,
/// programación y rendimiento del servidor.
struct OrganizacionAdminVista: View {
    @State private var r: ValorAdmin?
    @State private var error: String?
    @State private var cambios: ValorAdmin?
    @State private var vinculos: [ValorAdmin] = []
    @State private var etiquetas: [ValorAdmin] = []
    @State private var estadoCambios = "PENDIENTE"
    @State private var campo = ""
    @State private var pagina = 0
    @State private var cambiandoModelo = false
    @State private var modelo = ""
    @State private var confirmarCancelar = false

    private let campos: [(String, String)] = [("", "Todos"), ("letra", "Letra"), ("portada", "Portada"), ("anio", "Año"), ("nombre", "Título"),
                                               ("duplicado", "Posible duplicado"), ("album", "Álbum"), ("artista", "Artista"), ("genero", "Género")]
    private let ordenAjustes = ["org.activo", "org.horaDiaria", "org.minutoDiario", "org.diaSemanal", "org.horaSemanal", "org.lote",
                                "org.maxMinutos", "org.cpuMax", "org.pausaSiReproduce", "org.iaActiva", "org.iaHilos"]

    var body: some View {
        List {
            if let error { Section { Text(error).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } } }
            if let r {
                trabajo(r)
                catalogo(r)
                ia(r)
                seccionCambios
                seccionVinculos
                seccionEtiquetas
                proveedores(r)
                ajustes(r)
                rendimiento(r)
            } else if error == nil { ProgressView().frame(maxWidth: .infinity) }
        }
        .navigationTitle("Organización")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await cargar(); await cargarListas() }
        .task {
            await cargarListas()
            while !Task.isCancelled {
                await cargar()
                let activo = r?["corriendo"].verdadero == true || r?["evaluando"].verdadero == true
                try? await Task.sleep(for: .seconds(activo ? 5 : 30))
            }
        }
        .task(id: "\(estadoCambios)-\(campo)-\(pagina)") { await cargarCambios() }
        .alert("Modelo de Ollama", isPresented: $cambiandoModelo) {
            TextField("qwen3:1.7b", text: $modelo).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Guardar") { Task { await accion("/api/admin/organizacion/modelo", "PUT", ["nombre": modelo], "Modelo guardado: evalúalo antes de encender la IA") } }
            Button("Cancelar", role: .cancel) {}
        } message: { Text("Nombre del modelo ya instalado.") }
        .confirmationDialog("¿Cancelar el trabajo?", isPresented: $confirmarCancelar, titleVisibility: .visible) {
            Button("Cancelar trabajo", role: .destructive) { Task { await accion("/api/admin/organizacion/cancelar", "POST", nil, "Cancelado") } }
        } message: { Text("Lo hecho hasta ahora se conserva.") }
    }

    // MARK: Secciones

    private func estadoTrabajo(_ e: String) -> (String, Color) {
        switch e {
        case "EN_CURSO": ("En curso", .orange); case "PAUSADO": ("En pausa", Tema.secundario); case "TERMINADO": ("Terminado", .green)
        case "CANCELADO": ("Cancelado", Tema.secundario); case "FALLIDO": ("Falló", Tema.peligro); default: (e, Tema.secundario)
        }
    }

    private func trabajo(_ r: ValorAdmin) -> some View {
        let t = r["actual"]
        let hay = !t.esNulo
        let (txt, color) = hay ? estadoTrabajo(t["estado"].cadena) : ("Sin trabajos en curso", .green)
        let etapa = t["etapa"].entero
        let avance = (try? JSONSerialization.jsonObject(with: Data(t["avance"].cadena.utf8))) as? [String: Any] ?? [:]
        return Section {
            HStack { Chip(texto: txt, color: color); if hay { Text(t["mensaje"].cadena).font(.subheadline.weight(.semibold)) } }
            if !r["esperandoPor"].cadena.isEmpty { Text("Esperando: \(r["esperandoPor"].cadena)").font(.caption).foregroundStyle(Tema.secundario) }
            if hay {
                ForEach(Array(r["etapas"].lista.enumerated()), id: \.offset) { i, e in
                    HStack {
                        Image(systemName: i < etapa ? "checkmark.circle.fill" : i == etapa ? "circle.dotted" : "circle")
                            .foregroundStyle(i < etapa ? .green : i == etapa ? Tema.celeste : Tema.secundario)
                        Text(e["nombre"].cadena)
                        if let v = avance[e["clave"].cadena] { Text("· \(String(describing: v))").foregroundStyle(Tema.secundario) }
                    }
                    .font(.subheadline)
                }
            }
            Text("Próxima diaria: \(r["proximas"]["diaria"].fechaCorta) · semanal: \(r["proximas"]["semanal"].fechaCorta)")
                .font(.caption).foregroundStyle(Tema.secundario)
            if let ult = r["ultimos"].lista.first(where: { !["EN_CURSO", "PAUSADO"].contains($0["estado"].cadena) }) {
                let e2 = estadoTrabajo(ult["estado"].cadena)
                HStack { Text("Último:").font(.caption); Chip(texto: e2.0, color: e2.1); Text("\(ult["tipo"].cadena) · \(ult["mensaje"].cadena)").font(.caption).foregroundStyle(Tema.secundario) }
            }
            if !hay { Button("Ejecutar ahora") { Task { await accion("/api/admin/organizacion/iniciar", "POST", nil, "Organizador en marcha") } } }
            if t["estado"].cadena == "EN_CURSO" { Button("Pausar") { Task { await accion("/api/admin/organizacion/pausar", "POST", nil, "En pausa: el avance queda guardado") } } }
            if t["estado"].cadena == "PAUSADO" { Button("Reanudar") { Task { await accion("/api/admin/organizacion/reanudar", "POST", nil, "Reanudado") } } }
            if hay { Button("Cancelar trabajo", role: .destructive) { confirmarCancelar = true } }
        } header: { Text("Organizador automático") }
    }

    private func catalogo(_ r: ValorAdmin) -> some View {
        let v = r["vinculos"], m = r["metadatos"], a = r["audio"]
        let cifras: [(String, String)] = [
            (v["VERIFICADA"].texto, "vinculadas a YouTube Music"), (v["AMBIGUA"].texto, "vínculos por revisar"),
            (v["pendientes"].texto, "sin revisar todavía"), (v["relaciones"].texto, "relaciones para recomendar"),
            (m["sinLetra"].texto, "sin letra"), (m["sinPortada"].texto, "sin portada"), (m["PENDIENTE"].texto, "cambios pendientes"),
            ("\(a["analizadas"].texto) / \(a["total"].texto)", "con sonido analizado"),
        ]
        return Section {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 12) {
                ForEach(cifras, id: \.1) { c in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(c.0 == "—" ? "0" : c.0).font(.title3.weight(.heavy)).monospacedDigit()
                        Text(c.1).font(.caption).foregroundStyle(Tema.secundario)
                    }
                }
            }
            .padding(.vertical, 4)
        } header: { Text("Catálogo") } footer: {
            Text("Datos verificados de proveedores, características calculadas del audio (\(a["metodo"].esNulo ? "ffmpeg" : a["metodo"].cadena)) y etiquetas inferidas por IA se guardan por separado.")
        }
    }

    private func ia(_ r: ValorAdmin) -> some View {
        let i = r["ia"], ev = r["evaluacion"]
        let activa = (r["ajustes"]["org.iaActiva"]["valor"].numero ?? 0) >= 1
        return Section {
            HStack {
                Chip(texto: i["disponible"].verdadero ? "Ollama \(i["version"].cadena)" : "Ollama no responde", color: i["disponible"].verdadero ? .green : Tema.peligro)
                Chip(texto: activa ? "Etiquetas IA encendidas" : "Etiquetas IA apagadas", color: activa ? .orange : Tema.secundario)
            }
            LabeledContent("Modelo", value: r["modelo"].cadena)
            let cargados = i["cargados"].lista.map { "\($0["nombre"].cadena) (\($0["memoriaMb"].texto) MB)" }
            LabeledContent("En memoria", value: cargados.isEmpty ? "ninguno" : cargados.joined(separator: ", "))
            let instalados = i["modelos"].lista.map { "\($0["nombre"].cadena) (\($0["tamanoMb"].texto) MB)" }
            Text("Instalados: " + (instalados.isEmpty ? "—" : instalados.joined(separator: ", "))).font(.caption).foregroundStyle(Tema.secundario)
            if !ev.esNulo {
                HStack {
                    Text("Evaluación \(ev["fecha"].cadena): \(Int(((ev["precision"].numero ?? 0) * 100).rounded())) % de aciertos").font(.subheadline)
                    Chip(texto: ev["apta"].verdadero ? "Apta" : "No apta (80 %)", color: ev["apta"].verdadero ? .green : Tema.peligro)
                }
            } else {
                Text("El modelo todavía no se ha evaluado.").foregroundStyle(Tema.secundario)
            }
            if r["evaluando"].verdadero { Label("Evaluando… (unas 20 canciones; tarda varios minutos)", systemImage: "clock").font(.caption) }
            ForEach(Array(r["evaluacionDetalle"]["errores"].lista.enumerated()), id: \.offset) { _, e in
                Text(e.cadena).font(.caption2).foregroundStyle(Tema.peligro)
            }
            Button("Evaluar modelo") { Task { await accion("/api/admin/organizacion/evaluar", "POST", nil, "Evaluación en marcha") } }
                .disabled(r["evaluando"].verdadero || r["corriendo"].verdadero)
            Button("Cambiar modelo") { modelo = r["modelo"].cadena; cambiandoModelo = true }
        } header: { Text("IA local (Ollama)") } footer: {
            Text("Un modelo de texto no escucha canciones: solo propone etiquetas a partir del título y el artista, y siempre quedan para revisión.")
        }
    }

    private var seccionCambios: some View {
        Section {
            Picker("Estado", selection: $estadoCambios) {
                Text("Pendientes").tag("PENDIENTE"); Text("Aplicados").tag("APLICADO"); Text("Revertidos").tag("REVERTIDO"); Text("Rechazados").tag("RECHAZADO")
            }
            Picker("Tipo", selection: $campo) { ForEach(campos, id: \.0) { Text($0.1).tag($0.0) } }
            let lista = cambios?["contenido"].lista ?? []
            if lista.isEmpty { Text("Nada por aquí").foregroundStyle(Tema.secundario) }
            ForEach(Array(lista.enumerated()), id: \.offset) { _, c in filaCambio(c) }
            let paginas = cambios?["paginas"].entero ?? 1
            if paginas > 1 {
                HStack {
                    Button { pagina -= 1 } label: { Image(systemName: "chevron.left") }.disabled(pagina == 0)
                    Spacer(); Text("\(pagina + 1) / \(paginas)").font(.caption); Spacer()
                    Button { pagina += 1 } label: { Image(systemName: "chevron.right") }.disabled(pagina + 1 >= paginas)
                }
                .buttonStyle(.borderless)
            }
        } header: { Text("Cambios de datos") } footer: {
            Text("Solo se completan datos que faltan; lo corregido a mano nunca se toca. Todo lo aplicado se puede revertir.")
        }
        .onChange(of: estadoCambios) { _, _ in pagina = 0 }
        .onChange(of: campo) { _, _ in pagina = 0 }
    }

    private func filaCambio(_ c: ValorAdmin) -> some View {
        let s = c["cancion"]
        let nombreCampo = campos.first { $0.0 == c["campo"].cadena }?.1 ?? c["campo"].cadena
        let id = c["id"].entero
        let estado = c["estado"].cadena
        return VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                Portada(ruta: s["portadaUrl"].esNulo ? nil : s["portadaUrl"].cadena, tamano: 44, radio: 6)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(nombreCampo) · " + (s.esNulo ? "Canción #\(id)" : "\(s["nombre"].cadena) – \(s["artista"].cadena)")).font(.subheadline.weight(.semibold)).lineLimit(2)
                    Text("\(c["fuente"].cadena) · \(c["metodo"].cadena) · \(Formato.hace(c["fecha"].cadena))").font(.caption).foregroundStyle(Tema.secundario)
                }
            }
            if !c["detalle"].cadena.isEmpty { Text(c["detalle"].cadena).font(.caption).foregroundStyle(Tema.secundario) }
            if c["imagen"].verdadero { ImagenPropuesta(id: id).frame(width: 120, height: 120) }
            if !c["nuevo"].cadena.isEmpty, c["campo"].cadena != "duplicado" {
                DisclosureGroup(c["anterior"].cadena.isEmpty ? "Ver propuesta" : "Ver antes y después") {
                    if !c["anterior"].cadena.isEmpty { Text("Antes: \(c["anterior"].cadena)").font(.caption.monospaced()) }
                    Text(c["nuevo"].cadena).font(.caption.monospaced())
                }
                .font(.caption)
            }
            HStack {
                if estado == "PENDIENTE" {
                    Button(c["campo"].cadena == "duplicado" ? "Confirmar" : "Aplicar") { Task { await resolverCambio(id, "aplicar") } }
                    Button("Rechazar", role: .destructive) { Task { await resolverCambio(id, "rechazar") } }
                } else if estado == "APLICADO", c["fuente"].cadena != "manual" {
                    Button("Revertir") { Task { await resolverCambio(id, "revertir") } }
                }
            }
            .buttonStyle(.borderless)
            .font(.subheadline.weight(.semibold))
        }
        .padding(.vertical, 2)
    }

    private var seccionVinculos: some View {
        Section {
            if vinculos.isEmpty { Text("No hay vínculos por revisar.").foregroundStyle(Tema.secundario) }
            ForEach(Array(vinculos.enumerated()), id: \.offset) { _, v in
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(v["cancion"]["nombre"].cadena) – \(v["cancion"]["artista"].cadena)").font(.subheadline.weight(.semibold))
                    Text(v["detalle"].cadena).font(.caption).foregroundStyle(Tema.secundario)
                    HStack {
                        Button("Es la misma") { Task { await accion("/api/admin/organizacion/vinculos/\(v["id"].entero)/confirmar", "POST", nil, "Guardado"); await cargarListas() } }
                        Button("No es", role: .destructive) { Task { await accion("/api/admin/organizacion/vinculos/\(v["id"].entero)/descartar", "POST", nil, "Guardado"); await cargarListas() } }
                    }
                    .buttonStyle(.borderless)
                }
            }
        } header: { Text("Vínculos con YouTube Music por revisar") } footer: {
            Text("Casi coinciden (artista, título, versión o duración no del todo iguales). Mientras no se confirmen, no se usan para recomendar.")
        }
    }

    private var seccionEtiquetas: some View {
        Section {
            if etiquetas.isEmpty { Text("No hay etiquetas propuestas. La IA solo propone cuando está encendida y aprobó la evaluación.").foregroundStyle(Tema.secundario) }
            ForEach(Array(etiquetas.enumerated()), id: \.offset) { _, e in
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(e["cancion"]["nombre"].cadena) – \(e["cancion"]["artista"].cadena)").font(.subheadline.weight(.semibold))
                    Text("\(e["tipo"].cadena == "estilo" ? "Estilo" : "Para"): \(e["valor"].cadena) · inferido por \(e["modelo"].cadena)").font(.caption).foregroundStyle(Tema.secundario)
                    HStack {
                        Button("Aceptar") { Task { await accion("/api/admin/organizacion/etiquetas/\(e["id"].entero)/aceptar", "POST", nil, "Guardado"); await cargarListas() } }
                        Button("Rechazar", role: .destructive) { Task { await accion("/api/admin/organizacion/etiquetas/\(e["id"].entero)/rechazar", "POST", nil, "Guardado"); await cargarListas() } }
                    }
                    .buttonStyle(.borderless)
                }
            }
        } header: { Text("Etiquetas propuestas por la IA") }
    }

    private func proveedores(_ r: ValorAdmin) -> some View {
        Section {
            if r["proveedores"].lista.isEmpty { Text("Todavía no hay datos de proveedores.").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["proveedores"].lista.enumerated()), id: \.offset) { _, p in
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(p["nombre"].cadena).font(.subheadline.weight(.semibold))
                        Spacer()
                        if !p["pausadoHasta"].esNulo { Chip(texto: "En pausa", color: Tema.peligro) }
                        else if !p["ultimoError"].cadena.isEmpty { Chip(texto: "Último intento falló", color: .orange) }
                        else if p["llamadas"].entero > 0 { Chip(texto: "Funciona", color: .green) }
                        else { Chip(texto: "Sin uso aún") }
                    }
                    Text(p["descripcion"].cadena).font(.caption).foregroundStyle(Tema.secundario)
                    Text("\(p["llamadas"].texto) llamadas · \(p["fallos"].texto) fallos").font(.caption2).foregroundStyle(Tema.secundario)
                    if !p["ultimoError"].cadena.isEmpty { Text("\(p["ultimoError"].cadena) · \(p["ultimoFallo"].fechaCorta)").font(.caption2).foregroundStyle(Tema.peligro) }
                }
            }
        } header: { Text("Proveedores") } footer: { Text("Todos son gratuitos. YouTube Music se consulta con ytmusicapi, una biblioteca no oficial.") }
    }

    private func ajustes(_ r: ValorAdmin) -> some View {
        let aj = r["ajustes"]
        return Section {
            ForEach(ordenAjustes.filter { !aj[$0].esNulo }, id: \.self) { k in
                AjusteNumerico(descripcion: aj[k]["descripcion"].cadena, valor: Int(aj[k]["valor"].numero ?? 0),
                               minimo: Int(aj[k]["min"].numero ?? 0), maximo: Int(aj[k]["max"].numero ?? 100)) { v in
                    Task { await accion("/api/admin/organizacion/ajustes", "PUT", ["clave": k, "valor": String(v)], "Ajuste guardado") }
                }
            }
        } header: { Text("Programación y límites") }
    }

    private func rendimiento(_ r: ValorAdmin) -> some View {
        Section {
            Text("Canciones sonando ahora: \(r["audiosActivos"].texto)").font(.subheadline)
            if r["rendimiento"].lista.isEmpty { Text("Aún no hay suficientes peticiones medidas.").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["rendimiento"].lista.enumerated()), id: \.offset) { _, x in
                VStack(alignment: .leading, spacing: 2) {
                    Text(x["ruta"].cadena).font(.caption.monospaced()).lineLimit(1)
                    Text("\(x["peticiones"].texto) peticiones · servidor \(x["appP50"].texto)/\(x["appP95"].texto) ms · base \(x["dbP50"].texto)/\(x["dbP95"].texto) ms · \(x["consultasMedia"].texto) consultas")
                        .font(.caption2).foregroundStyle(Tema.secundario)
                }
            }
        } header: { Text("Rendimiento del servidor") } footer: { Text("Tiempo de las últimas peticiones de cada ruta (p50 / p95).") }
    }

    // MARK: Datos

    private func cargar() async {
        do { r = try await API.get("/api/admin/organizacion"); error = nil }
        catch { if !Task.isCancelled, r == nil { self.error = error.localizedDescription } }
    }
    private func cargarCambios() async {
        cambios = try? await API.get("/api/admin/organizacion/cambios?estado=\(estadoCambios)&campo=\(campo)&pagina=\(pagina)")
    }
    private func cargarListas() async {
        vinculos = ((try? await API.get("/api/admin/organizacion/vinculos")) as ValorAdmin?)?.lista ?? []
        etiquetas = ((try? await API.get("/api/admin/organizacion/etiquetas")) as ValorAdmin?)?["contenido"].lista ?? []
    }
    private func accion(_ ruta: String, _ metodo: String, _ cuerpo: [String: Any]?, _ ok: String) async {
        do { try await API.hacer(metodo, ruta, cuerpo); Avisos.compartido.mostrar(ok); await cargar() }
        catch { Avisos.compartido.error(error) }
    }
    private func resolverCambio(_ id: Int, _ a: String) async {
        do {
            let r2: ValorAdmin = try await API.enviar("POST", "/api/admin/organizacion/cambios/\(id)/\(a)")
            Avisos.compartido.mostrar(r2["estado"].cadena == "RECHAZADO" && a == "aplicar" ? "No se aplicó: \(r2["detalle"].cadena)" : "Hecho")
            await cargarCambios()
        } catch { Avisos.compartido.error(error) }
    }
}

/// Campo numérico de un ajuste con sus límites; se guarda al terminar de editar.
struct AjusteNumerico: View {
    let descripcion: String
    @State var valor: Int
    let minimo: Int
    let maximo: Int
    let guardar: (Int) -> Void

    var body: some View {
        Stepper(value: $valor, in: minimo...max(minimo, maximo)) {
            VStack(alignment: .leading, spacing: 2) {
                Text(descripcion).font(.subheadline)
                Text("\(valor)").font(.caption.monospacedDigit()).foregroundStyle(Tema.celeste)
            }
        }
        .onChange(of: valor) { _, v in guardar(v) }
    }
}

/// Portada propuesta por el organizador: la imagen pide el permiso del administrador.
struct ImagenPropuesta: View {
    let id: Int
    @State private var imagen: UIImage?
    var body: some View {
        Group {
            if let imagen { Image(uiImage: imagen).resizable().scaledToFit() } else { Color.white.opacity(0.06) }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task {
            guard let pedido = try? API.peticion("/api/admin/organizacion/cambios/\(id)/imagen"),
                  let resultado = try? await API.sesionURL.data(for: pedido) else { return }
            imagen = UIImage(data: resultado.0)
        }
        .accessibilityLabel("Portada propuesta")
    }
}
