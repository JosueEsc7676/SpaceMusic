import SwiftUI

// Experiencias de la web 3.x (experiencias.js): rutas musicales, descubrir con control,
// «El punto donde coincidimos» y preparar escucha sin internet. Todo con canciones del servidor.

/// Cabecera común de estas pantallas.
struct CabeceraExperiencia: View {
    let icono: String
    let titulo: String
    let texto: String
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icono).font(.title2.weight(.semibold)).foregroundStyle(.black)
                .frame(width: 48, height: 48)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tema.aurora))
            VStack(alignment: .leading, spacing: 4) {
                Text(titulo).font(.system(.title2, design: .rounded).weight(.heavy))
                Text(texto).font(.subheadline).foregroundStyle(Tema.secundario)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Accesos de Inicio a las experiencias (como `exp-accesos` de la web).
struct AccesosExperiencias: View {
    private let accesos: [(Destino, String, String, String)] = [
        (.rutas, "safari", "Rutas musicales", "De tranquilo a con energía, o al revés"),
        (.descubrir, "sparkles", "Descubrir", "Familiar, cercano o sorpréndeme"),
        (.coincidimos, "person.2.fill", "Coincidimos", "Tus gustos y los de alguien más"),
        (.preparar, "arrow.down.circle.fill", "Sin internet", "Prepara música para el camino"),
    ]
    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            ForEach(accesos, id: \.2) { a in
                NavigationLink(value: a.0) {
                    VStack(alignment: .leading, spacing: 6) {
                        Image(systemName: a.1).font(.title3).foregroundStyle(Tema.lila)
                        Text(a.2).font(.subheadline.weight(.bold)).foregroundStyle(Tema.texto)
                        Text(a.3).font(.caption).foregroundStyle(Tema.secundario).lineLimit(2).multilineTextAlignment(.leading)
                    }
                    .frame(maxWidth: .infinity, minHeight: 96, alignment: .topLeading)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tema.tarjeta))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal)
    }
}

// MARK: - Rutas musicales

struct RutasVista: View {
    enum Energia: String, CaseIterable { case calma, media, energia
        var nombre: String { switch self { case .calma: "Tranquilo"; case .media: "Medio"; case .energia: "Con energía" } }
    }
    @State private var minutos: Double = 25
    @State private var desde: Energia = .calma
    @State private var hasta: Energia = .energia
    @State private var sorpresa: Double = 0.2
    @State private var inicioId: Int?
    @State private var ruta: RutaMusical?
    @State private var cargando = false
    @State private var r = Reproductor.compartido

    private var textoSorpresa: String { sorpresa < 0.15 ? "nada" : sorpresa < 0.45 ? "un poco" : sorpresa < 0.75 ? "bastante" : "mucha" }

    var body: some View {
        List {
            Section {
                CabeceraExperiencia(icono: "safari", titulo: "Llévame de aquí hasta allá",
                                    texto: "Elige cuánto dura, cómo empieza y adónde quieres llegar. Se arma con tus canciones y va cambiando poco a poco.")
                    .listRowBackground(Color.clear)
            }
            Section {
                VStack(alignment: .leading) {
                    Text("Duración: \(Int(minutos)) min").font(.subheadline.weight(.semibold))
                    Slider(value: $minutos, in: 10...90, step: 5).tint(Tema.celeste)
                }
                Picker("Empieza", selection: $desde) { ForEach(Energia.allCases, id: \.self) { Text($0.nombre) } }
                Picker("Llega a", selection: $hasta) { ForEach(Energia.allCases, id: \.self) { Text($0.nombre) } }
                VStack(alignment: .leading) {
                    Text("Sorpresa: \(textoSorpresa)").font(.subheadline.weight(.semibold))
                    Slider(value: $sorpresa, in: 0...1, step: 0.1).tint(Tema.celeste)
                }
                if let c = r.actual {
                    Toggle("Empezar con «\(c.nombre)»", isOn: Binding(get: { inicioId == c.id }, set: { inicioId = $0 ? c.id : nil }))
                        .tint(Tema.celeste)
                }
                Button { Task { await pedir(ajuste: nil) } } label: {
                    HStack { Label("Armar ruta", systemImage: "sparkles"); Spacer(); if cargando { ProgressView() } }
                }
                .disabled(cargando)
            } footer: {
                Text("La energía de cada canción se estima por su sonido (volumen y dinámica). No detectamos emociones.")
            }
            if let ruta {
                if !ruta.disponible {
                    Section { EstadoVista(icono: "clock", titulo: "Aún no está lista", texto: ruta.nota) }
                } else {
                    Section {
                        Text(ruta.explicacion).font(.subheadline).foregroundStyle(Tema.secundario)
                        CurvaEnergia(valores: ruta.energias).frame(height: 70)
                        Button { r.reproducir(ruta.canciones, contexto: "ruta") } label: { Label("Escuchar", systemImage: "play.fill") }
                        Menu {
                            Button("Más calma") { Task { await pedir(ajuste: "calma") } }
                            Button("Más energía") { Task { await pedir(ajuste: "energia") } }
                            Button("Más sorpresa") { Task { await pedir(ajuste: "sorpresa") } }
                            Button("Mantener ambiente") { Task { await pedir(ajuste: "mantener") } }
                        } label: { Label("Ajustar desde lo que suena", systemImage: "slider.horizontal.3") }
                    } header: { Text("Tu ruta · \(ruta.minutos) min") }
                    Section {
                        ForEach(Array(ruta.canciones.enumerated()), id: \.offset) { i, c in
                            FilaCancion(cancion: c) { r.reproducir(ruta.canciones, desde: i, contexto: "ruta") }
                        }
                    }
                }
            }
        }
        .navigationTitle("Rutas musicales")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Ajustar conserva lo ya escuchado (hasta la canción que suena) y rehace el resto, como la web.
    private func pedir(ajuste: String?) async {
        cargando = true
        defer { cargando = false }
        var cuerpo: [String: Any] = ["minutos": Int(minutos), "desde": desde.rawValue, "hasta": hasta.rawValue, "sorpresa": sorpresa]
        if let inicioId { cuerpo["inicioId"] = inicioId }
        let sonando = r.contexto == "ruta" ? r.indice : -1
        if let ajuste, let actual = ruta {
            cuerpo["ajuste"] = ajuste
            cuerpo["conservar"] = actual.canciones.prefix(max(1, sonando + 1)).map(\.id)
        }
        do {
            let nueva: RutaMusical = try await API.enviar("POST", "/api/rutas", cuerpo)
            ruta = nueva
            if ajuste != nil, sonando >= 0 {
                r.limpiarSiguientes()
                r.agregarAlFinal(Array(nueva.canciones.dropFirst(sonando + 1)))
                Avisos.compartido.mostrar("Ruta ajustada desde la canción actual")
            }
        } catch { Avisos.compartido.error(error) }
    }
}

/// Barras con la energía de cada canción de la ruta.
struct CurvaEnergia: View {
    let valores: [Double]
    var body: some View {
        GeometryReader { g in
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(Array(valores.enumerated()), id: \.offset) { _, v in
                    RoundedRectangle(cornerRadius: 2).fill(Tema.aurora)
                        .frame(height: max(4, g.size.height * (0.1 + 0.9 * min(max(v, 0), 1))))
                }
            }
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .accessibilityLabel("Energía de cada canción")
    }
}

// MARK: - Descubrir

struct DescubrirVista: View {
    enum Modo: String, CaseIterable { case familiar, cercano, sorpresa
        var titulo: String { switch self { case .familiar: "Familiar"; case .cercano: "Cercano"; case .sorpresa: "Sorpréndeme" } }
        var sub: String { switch self { case .familiar: "Más de tus artistas"; case .cercano: "Tus estilos, otros artistas"; case .sorpresa: "Fuera de lo habitual" } }
    }
    @State private var modo: Modo = .cercano
    @State private var lista: [Descubrimiento]?
    @State private var descartes: [EstiloDescartado] = []
    @State private var error: String?
    @State private var confirmarEstilo: Descubrimiento?

    var body: some View {
        List {
            Section {
                CabeceraExperiencia(icono: "sparkles", titulo: "Descubrir",
                                    texto: "Canciones del servidor que aún no escuchas. Tú decides cuánto te alejas de lo de siempre.")
                Picker("Modo", selection: $modo) { ForEach(Modo.allCases, id: \.self) { Text($0.titulo) } }
                    .pickerStyle(.segmented)
                Text(modo.sub).font(.caption).foregroundStyle(Tema.secundario)
            }
            .listRowBackground(Color.clear)
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            if let lista {
                if lista.isEmpty {
                    EstadoVista(icono: "safari", titulo: "Ya escuchaste todo por aquí", texto: "Prueba otro modo o vuelve cuando lleguen canciones nuevas.")
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        Button { Reproductor.compartido.reproducir(lista.map(\.cancion), contexto: "descubrir") } label: {
                            Label("Escuchar todo (\(lista.count))", systemImage: "play.fill")
                        }
                        ForEach(Array(lista.enumerated()), id: \.element.id) { i, d in
                            VStack(alignment: .leading, spacing: 6) {
                                FilaCancion(cancion: d.cancion) { Reproductor.compartido.reproducir(lista.map(\.cancion), desde: i, contexto: "descubrir") }
                                Label(d.razon, systemImage: "info.circle").font(.caption).foregroundStyle(Tema.secundario)
                            }
                            .swipeActions(edge: .trailing) {
                                Button("Hoy no") { Task { await descartar(d, tipo: "hoy") } }.tint(.gray)
                                Button("Menos \(d.estiloNombre)") { confirmarEstilo = d }.tint(Tema.peligro)
                            }
                        }
                    } footer: { Text("Desliza una canción a la izquierda: «Hoy no» la esconde unos días; «Menos…» muestra menos de ese estilo.") }
                }
            } else if error == nil {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            }
            if !descartes.isEmpty {
                Section("Estilos que dijiste que no te gustan") {
                    ForEach(descartes) { e in
                        HStack { Text(e.estilo); Spacer()
                            Button("Quitar") { Task { try? await API.hacer("DELETE", "/api/descubrir/descartes/\(e.id)"); await cargarDescartes() } }
                        }
                    }
                }
            }
        }
        .navigationTitle("Descubrir")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: modo) { await cargar() }
        .task { await cargarDescartes() }
        .refreshable { await cargar() }
        .confirmationDialog("¿No te gusta este estilo?", isPresented: Binding(get: { confirmarEstilo != nil }, set: { if !$0 { confirmarEstilo = nil } }),
                            titleVisibility: .visible, presenting: confirmarEstilo) { d in
            Button("Mostrar menos \(d.estiloNombre)") { Task { await descartar(d, tipo: "estilo") } }
        } message: { d in Text("Te mostraremos menos \(d.estiloNombre) durante dos meses. Puedes deshacerlo abajo.") }
    }

    private func cargar() async {
        lista = nil; error = nil
        do { lista = try await API.get("/api/descubrir?modo=\(modo.rawValue)&cantidad=30") }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func cargarDescartes() async { descartes = (try? await API.get("/api/descubrir/estilos-descartados")) ?? [] }
    private func descartar(_ d: Descubrimiento, tipo: String) async {
        do {
            try await API.hacer("POST", "/api/descubrir/descartar", ["cancionId": d.cancion.id, "tipo": tipo])
            lista?.removeAll { $0.id == d.id }
            Avisos.compartido.mostrar(tipo == "hoy" ? "Listo, no te la mostraremos estos días" : "Entendido: menos de ese estilo por un tiempo")
            if tipo == "estilo" { await cargarDescartes() }
        } catch { Avisos.compartido.error(error) }
    }
}

// MARK: - El punto donde coincidimos

struct CoincidimosVista: View {
    @State private var sesiones: [SesionCoincidimos]?
    @State private var invitando = false
    @State private var usuario = ""

    var body: some View {
        List {
            Section {
                CabeceraExperiencia(icono: "person.2.fill", titulo: "El punto donde coincidimos",
                                    texto: "Invita a alguien que sigues o que te sigue. Si acepta, cada quien elige sus canciones y descubren lo que comparten.")
                    .listRowBackground(Color.clear)
                Button { invitando = true } label: { Label("Invitar a alguien", systemImage: "plus") }
            } footer: { Text("No hay escucha sincronizada: cada quien escucha en su dispositivo.") }
            if let sesiones {
                if sesiones.isEmpty {
                    EstadoVista(icono: "person.2", titulo: "Todavía no tienes sesiones", texto: "Las sesiones solo existen si las dos personas aceptan.")
                        .listRowBackground(Color.clear)
                } else {
                    Section("Tus sesiones") {
                        ForEach(sesiones) { s in
                            NavigationLink(value: Destino.sesionCoincidimos(s.id)) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(s.conmigo?.nombre ?? "Sesión").font(.body.weight(.semibold))
                                    Text(s.estado == "INVITADA" ? (s.meInvitaron ? "Te invitó · toca para responder" : "Esperando que acepte") : "Activa")
                                        .font(.caption).foregroundStyle(Tema.secundario)
                                }
                            }
                        }
                    }
                }
            } else { ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear) }
        }
        .navigationTitle("Coincidimos")
        .navigationBarTitleDisplayMode(.inline)
        .task { await cargar() }
        .refreshable { await cargar() }
        .alert("Invitar a coincidir", isPresented: $invitando) {
            TextField("@usuario", text: $usuario).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Invitar") { Task { await invitar() } }
            Button("Cancelar", role: .cancel) {}
        } message: { Text("Alguien que sigues o que te sigue.") }
    }

    private func cargar() async { sesiones = (try? await API.get("/api/coincidimos")) ?? [] }
    private func invitar() async {
        let u = usuario.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "@", with: "")
        guard !u.isEmpty else { return }
        do {
            let r: RespuestaId = try await API.enviar("POST", "/api/coincidimos", ["username": u])
            usuario = ""
            Avisos.compartido.mostrar("Invitación enviada. Esperando que acepte.")
            Navegacion.compartida.ir(.sesionCoincidimos(r.id))
        } catch { Avisos.compartido.error(error) }
    }
}

struct SesionCoincidimosVista: View {
    let id: Int
    @State private var s: SesionCoincidimos?
    @State private var error: String?
    @State private var aportando = false
    @State private var confirmarCierre = false

    var body: some View {
        List {
            if let error { EstadoVista(icono: "lock", titulo: "No puedes ver esta sesión", texto: error).listRowBackground(Color.clear) }
            if let s {
                let nombre = s.conmigo?.nombre ?? "esa persona"
                if s.estado == "INVITADA" {
                    Section {
                        CabeceraExperiencia(icono: "person.2.fill", titulo: "El punto donde coincidimos",
                                            texto: s.meInvitaron ? "\(nombre) te invitó. Si aceptas, verá tu selección y los puentes entre los dos (no tu historial)." : "Esperando que \(nombre) acepte.")
                            .listRowBackground(Color.clear)
                        if s.meInvitaron {
                            Button("Acepto participar") { Task { await responder(true) } }
                            Button("No, gracias", role: .destructive) { Task { await responder(false) } }
                        }
                    }
                } else if s.estado != "ACTIVA" {
                    EstadoVista(icono: "person.2", titulo: "Esta sesión terminó").listRowBackground(Color.clear)
                } else {
                    Section {
                        CabeceraExperiencia(icono: "person.2.fill", titulo: "Tú y \(nombre)",
                                            texto: s.estilosEnComun.isEmpty ? "Elijan sus canciones para encontrar dónde coinciden" : "Coinciden en " + s.estilosEnComun.joined(separator: ", "))
                            .listRowBackground(Color.clear)
                        Button { aportando = true } label: { Label("Aportar una canción (\(s.miAporte.count)/5)", systemImage: "plus") }
                            .disabled(s.miAporte.count >= 5)
                        Button("Terminar sesión", role: .destructive) { confirmarCierre = true }
                    }
                    bloque("Lo que comparten", "Les gusta a los dos", s.compartidas, s)
                    bloque("Puentes", "Canciones que ninguno ha escuchado y están cerca de los dos", s.puentes, s)
                    bloque("Tu aporte", "Lo que elegiste para esta sesión", s.miAporte, s)
                    bloque("Su aporte", "Lo que eligió \(nombre)", s.suAporte, s)
                    if s.compartidas.isEmpty && s.puentes.isEmpty && s.miAporte.isEmpty && s.suAporte.isEmpty {
                        EstadoVista(icono: "music.note", titulo: "Empiecen aportando canciones", texto: "Cada quien puede elegir hasta 5.").listRowBackground(Color.clear)
                    }
                }
            } else if error == nil { ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear) }
        }
        .navigationTitle("Coincidimos")
        .navigationBarTitleDisplayMode(.inline)
        .task { await cargar() }
        .refreshable { await cargar() }
        .sheet(isPresented: $aportando) {
            ElegirCancionHoja(titulo: "Aportar una canción") { c in
                try await API.hacer("POST", "/api/coincidimos/\(id)/aportes", ["cancionId": c.id])
                Avisos.compartido.mostrar("Aportada")
                await cargar()
            }
        }
        .confirmationDialog("¿Terminar la sesión?", isPresented: $confirmarCierre, titleVisibility: .visible) {
            Button("Terminar", role: .destructive) { Task { try? await API.hacer("POST", "/api/coincidimos/\(id)/cerrar"); Navegacion.compartida.ir(.coincidimos) } }
        } message: { Text("La sesión se cierra para los dos.") }
    }

    @ViewBuilder
    private func bloque(_ titulo: String, _ sub: String, _ lista: [Cancion], _ s: SesionCoincidimos) -> some View {
        if !lista.isEmpty {
            Section {
                ForEach(Array(lista.enumerated()), id: \.offset) { i, c in
                    VStack(alignment: .leading, spacing: 8) {
                        FilaCancion(cancion: c) { Reproductor.compartido.reproducir(lista, desde: i, contexto: "coincidimos") }
                        HStack(spacing: 6) {
                            ForEach([("REPRESENTA", "Me representa"), ("SORPRENDIO", "Me sorprendió"), ("NO_ES_PARA_MI", "No era para mí")], id: \.0) { r in
                                let activo = s.misReacciones[c.id] == r.0
                                Button(r.1) { Task { await reaccionar(c.id, r.0) } }
                                    .font(.caption.weight(.semibold))
                                    .padding(.horizontal, 9).padding(.vertical, 5)
                                    .foregroundStyle(activo ? Color.black : Tema.texto)
                                    .background(Capsule().fill(activo ? Color.white : Tema.tarjeta))
                                    .buttonStyle(.plain)
                                    .accessibilityAddTraits(activo ? .isSelected : [])
                            }
                        }
                    }
                }
            } header: {
                HStack {
                    Text(titulo)
                    Spacer()
                    Button { Reproductor.compartido.reproducir(lista, contexto: "coincidimos") } label: { Image(systemName: "play.fill") }
                        .accessibilityLabel("Escuchar \(titulo)")
                }
            } footer: { Text(sub) }
        }
    }

    private func cargar() async {
        do { s = try await API.get("/api/coincidimos/\(id)"); error = nil }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func responder(_ acepta: Bool) async {
        do {
            try await API.hacer("POST", "/api/coincidimos/\(id)/respuesta", ["acepta": acepta])
            if acepta { await cargar() } else { Navegacion.compartida.ir(.coincidimos) }
        } catch { Avisos.compartido.error(error) }
    }
    private func reaccionar(_ cancion: Int, _ reaccion: String) async {
        do { try await API.hacer("POST", "/api/coincidimos/\(id)/reacciones", ["cancionId": cancion, "reaccion": reaccion]); await cargar() }
        catch { Avisos.compartido.error(error) }
    }
}

/// Buscar una canción del servidor (para aportar o dedicar).
struct ElegirCancionHoja: View {
    let titulo: String
    let alElegir: (Cancion) async throws -> Void
    @Environment(\.dismiss) private var cerrar
    @State private var texto = ""
    @State private var resultados: [Cancion] = []

    var body: some View {
        NavigationStack {
            List(resultados) { c in
                Button {
                    Task {
                        do { try await alElegir(c); cerrar() } catch { Avisos.compartido.error(error) }
                    }
                } label: {
                    HStack(spacing: 12) {
                        Portada(ruta: c.portadaUrl, tamano: 44, radio: 6)
                        VStack(alignment: .leading) {
                            Text(c.nombre).lineLimit(1)
                            Text(c.artista).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            .overlay { if resultados.isEmpty { Text(texto.count < 2 ? "Busca una canción" : "Sin resultados").foregroundStyle(Tema.secundario) } }
            .searchable(text: $texto, placement: .navigationBarDrawer(displayMode: .always), prompt: "Busca una canción")
            .task(id: texto) {
                let q = texto.trimmingCharacters(in: .whitespaces)
                guard q.count >= 2 else { resultados = []; return }
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled, let r: Busqueda = try? await API.get("/api/buscar?q=\(API.codificar(q))") else { return }
                resultados = Array(r.canciones.prefix(8))
            }
            .navigationTitle(titulo)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { cerrar() } } }
        }
    }
}

// MARK: - Preparar escucha sin internet

struct PrepararVista: View {
    @AppStorage("preparadaIds") private var preparadaGuardada = ""
    @State private var minutos: Double = 120
    @State private var maxMb: Double = 1000
    @State private var favoritas: Double = 70
    @State private var seleccion: SeleccionPreparada?
    @State private var cargando = false
    @State private var descargas = Descargas.compartido

    private var preparada: [Int] { preparadaGuardada.split(separator: ",").compactMap { Int($0) } }
    private var libre: Int64? {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        return (try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
    }
    private func horas(_ m: Int) -> String { m >= 60 ? "\(m / 60) h" + (m % 60 > 0 ? " \(m % 60) min" : "") : "\(m) min" }
    private func mb(_ b: Int64) -> String { String(format: b > 10 * 1_048_576 ? "%.0f MB" : "%.1f MB", Double(b) / 1_048_576) }

    var body: some View {
        List {
            Section {
                CabeceraExperiencia(icono: "arrow.down.circle.fill", titulo: "Preparar escucha sin internet",
                                    texto: "Elige cuánto tiempo y espacio. Revisa la selección, cambia lo que quieras y luego descárgala.")
                    .listRowBackground(Color.clear)
            }
            if !preparada.isEmpty {
                let listas = preparada.filter { descargas.descargada($0) }.count
                Section("Tu selección en este iPhone") {
                    ProgressView(value: Double(listas), total: Double(max(1, preparada.count))) {
                        Text(listas == preparada.count ? "Descargada: \(listas) canciones" : "Descargando: \(listas) de \(preparada.count)")
                    }
                    .tint(Tema.celeste)
                    Button { Reproductor.compartido.reproducir(descargas.canciones.filter { preparada.contains($0.id) }, contexto: "preparada") } label: {
                        Label("Escuchar", systemImage: "play.fill")
                    }
                    Button("Olvidar esta selección", role: .destructive) { preparadaGuardada = "" }
                }
            }
            Section {
                VStack(alignment: .leading) { Text("Duración: \(horas(Int(minutos)))"); Slider(value: $minutos, in: 30...600, step: 30).tint(Tema.celeste) }
                VStack(alignment: .leading) { Text("Espacio máximo: \(Int(maxMb)) MB"); Slider(value: $maxMb, in: 100...5000, step: 100).tint(Tema.celeste) }
                VStack(alignment: .leading) {
                    Text("Favoritas \(Int(favoritas)) % · descubrimientos \(100 - Int(favoritas)) %")
                    Slider(value: $favoritas, in: 0...100, step: 10).tint(Tema.celeste)
                }
                Button { Task { await proponer() } } label: {
                    HStack { Label("Proponer selección", systemImage: "sparkles"); Spacer(); if cargando { ProgressView() } }
                }
                .disabled(cargando)
            } footer: {
                if let libre { Text("Espacio disponible en este iPhone: \(mb(libre)).") }
            }
            if let sel = seleccion {
                let total = sel.canciones.reduce(Int64(0)) { $0 + $1.bytes }
                let min = sel.canciones.reduce(0) { $0 + $1.cancion.duracionSegundos } / 60
                let noCabe = libre.map { total > $0 } ?? false
                Section {
                    if let n = sel.nota { Text(n).font(.subheadline).foregroundStyle(Tema.secundario) }
                    if noCabe { Label("No cabe en el espacio disponible del iPhone.", systemImage: "exclamationmark.triangle").foregroundStyle(Tema.peligro) }
                    Button { descargar(sel) } label: { Label("Descargar", systemImage: "arrow.down.circle") }.disabled(noCabe)
                    ForEach(Array(sel.canciones.enumerated()), id: \.offset) { i, x in
                        HStack(spacing: 10) {
                            Portada(ruta: x.cancion.portadaUrl, tamano: 44, radio: 6)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(x.cancion.nombre).lineLimit(1)
                                Text("\(x.cancion.artista) · \(Formato.duracion(x.cancion.duracionSegundos)) · \(mb(x.bytes))")
                                    .font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                            }
                            Spacer(minLength: 4)
                            Text(x.esFavorita ? "Favorita" : "Nueva").font(.caption2.weight(.bold))
                                .padding(.horizontal, 6).padding(.vertical, 3)
                                .background(Capsule().fill(x.esFavorita ? Tema.celeste.opacity(0.2) : Tema.tarjeta))
                        }
                        .swipeActions { Button("Cambiar") { Task { await cambiar(i, sel) } }.tint(Tema.lila) }
                    }
                } header: {
                    Text("Selección · \(sel.canciones.count) canciones · \(horas(min)) · \(mb(total))")
                } footer: { Text("Todavía no se descargó nada. Desliza una canción para cambiarla.") }
            }
        }
        .navigationTitle("Sin internet")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func proponer() async {
        cargando = true
        defer { cargando = false }
        do { seleccion = try await API.enviar("POST", "/api/sin-internet/preparar", ["minutos": Int(minutos), "maxMb": Int(maxMb), "favoritas": Int(favoritas)]) }
        catch { Avisos.compartido.error(error) }
    }
    private func cambiar(_ i: Int, _ sel: SeleccionPreparada) async {
        let total = sel.canciones.reduce(Int64(0)) { $0 + $1.bytes }
        let x = sel.canciones[i]
        do {
            let nuevo: ItemPreparado = try await API.enviar("POST", "/api/sin-internet/sustituir", [
                "tipo": x.tipo, "excluir": sel.canciones.map(\.cancion.id), "maxBytes": Int64(maxMb) * 1_048_576 - total + x.bytes,
            ])
            seleccion?.canciones[i] = nuevo
        } catch { Avisos.compartido.error(error) }
    }
    private func descargar(_ sel: SeleccionPreparada) {
        let canciones = sel.canciones.map(\.cancion)
        preparadaGuardada = canciones.map { String($0.id) }.joined(separator: ",")
        descargas.descargar(canciones)
        Avisos.compartido.mostrar("Descargando la selección: puedes seguir usando la app")
        seleccion = nil
    }
}
