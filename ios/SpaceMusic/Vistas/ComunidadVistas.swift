import SwiftUI

// comunidad.js de la web 3.1: reportar un problema, elegir gustos y «Tu año / tu mes en SpaceMusic».

// MARK: - Reportar un problema

struct ReportarHoja: View {
    let cancion: Cancion
    @Environment(\.dismiss) private var cerrar
    @State private var motivo = "audio"
    @State private var detalle = ""
    @State private var enviando = false
    private let motivos = [("audio", "No suena o se corta"), ("letra", "La letra está mal o desfasada"), ("datos", "Nombre, artista o álbum incorrecto"),
                           ("portada", "La portada no corresponde"), ("duplicada", "Está repetida"), ("otro", "Otro problema")]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        Portada(ruta: cancion.portadaUrl, tamano: 48, radio: 6)
                        VStack(alignment: .leading) {
                            Text(cancion.nombre).lineLimit(1)
                            Text(cancion.artista).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                        }
                    }
                }
                Section("¿Qué pasa?") {
                    Picker("Motivo", selection: $motivo) { ForEach(motivos, id: \.0) { Text($0.1).tag($0.0) } }
                        .pickerStyle(.inline).labelsHidden()
                }
                Section {
                    TextField("Por ejemplo: se corta en el minuto 2", text: $detalle, axis: .vertical).lineLimit(2...5)
                } header: { Text("Cuéntanos más (opcional)") } footer: {
                    Text("El administrador lo revisará y te avisaremos cuando esté arreglado.")
                }
            }
            .navigationTitle("Reportar un problema")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { cerrar() } }
                ToolbarItem(placement: .confirmationAction) { Button("Enviar") { Task { await enviar() } }.disabled(enviando) }
            }
        }
    }

    private func enviar() async {
        enviando = true
        defer { enviando = false }
        do {
            let r: RespuestaId = try await API.enviar("POST", "/api/reportes", [
                "cancionId": cancion.id, "motivo": motivo, "detalle": String(detalle.prefix(500)), "cliente": "iphone",
            ])
            Avisos.compartido.mostrar(r.repetido ? "Ya habías reportado esto: lo estamos revisando" : "Gracias, enviamos tu reporte")
            cerrar()
        } catch { Avisos.compartido.error(error) }
    }
}

// MARK: - Gustos

struct GustosHoja: View {
    var inicial = false
    @Environment(\.dismiss) private var cerrar
    @State private var opciones: OpcionesGustos?
    @State private var generos: Set<String> = []
    @State private var artistas: Set<String> = []
    @State private var buscar = ""
    @State private var guardando = false

    var body: some View {
        NavigationStack {
            ScrollView {
                if let op = opciones {
                    VStack(alignment: .leading, spacing: 18) {
                        Text((inicial ? "¡Bienvenido a SpaceMusic! " : "") + "Elige lo que te gusta y armaremos tu inicio y tus mixes con eso. Puedes cambiarlo cuando quieras en Tú → Ajustes.")
                            .font(.subheadline).foregroundStyle(Tema.secundario)
                        Text("Géneros").titulo()
                        Flujo(espacio: 8) {
                            ForEach(op.generos, id: \.nombre) { g in
                                let activo = generos.contains(g.nombre)
                                Button { alternar(&generos, g.nombre) } label: {
                                    Text(g.nombre).font(.subheadline.weight(.semibold))
                                        .padding(.horizontal, 14).padding(.vertical, 8)
                                        .foregroundStyle(activo ? Color.black : Tema.texto)
                                        .background(Capsule().fill(activo ? Color.white : Tema.tarjeta))
                                }
                                .buttonStyle(.plain)
                                .accessibilityAddTraits(activo ? .isSelected : [])
                            }
                        }
                        Text("Artistas").titulo()
                        TextField("Buscar artista", text: $buscar)
                            .textFieldStyle(.roundedBorder).autocorrectionDisabled()
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 12)], spacing: 14) {
                            ForEach(op.artistas.filter { buscar.isEmpty || $0.nombre.localizedCaseInsensitiveContains(buscar) }, id: \.nombre) { a in
                                let activo = artistas.contains(a.nombre)
                                Button { alternar(&artistas, a.nombre) } label: {
                                    VStack(spacing: 6) {
                                        ImagenRemota(ruta: a.portadaUrl, icono: "music.mic", redonda: true)
                                            .frame(width: 84, height: 84)
                                            .overlay(alignment: .bottomTrailing) {
                                                if activo {
                                                    Image(systemName: "checkmark.circle.fill").font(.title2)
                                                        .foregroundStyle(.black, Tema.celeste)
                                                }
                                            }
                                            .opacity(activo ? 1 : 0.75)
                                        Text(a.nombre).font(.caption.weight(.semibold)).lineLimit(1)
                                    }
                                }
                                .buttonStyle(.plain)
                                .accessibilityAddTraits(activo ? .isSelected : [])
                            }
                        }
                    }
                    .padding()
                } else { Cargando() }
            }
            .navigationTitle(inicial ? "¿Qué música te gusta?" : "Tus gustos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(inicial ? "Omitir" : "Cancelar") {
                        // La primera vez, «Omitir» también cuenta como hecho (no se vuelve a preguntar).
                        if inicial { Task { try? await API.hacer("PUT", "/api/gustos", ["generos": [String](), "artistas": [String]()]) } }
                        cerrar()
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Listo") { Task { await guardar() } }.disabled(guardando || opciones == nil) }
            }
            .task { await cargar() }
        }
    }

    private func alternar(_ conjunto: inout Set<String>, _ v: String) {
        if conjunto.contains(v) { conjunto.remove(v) } else { conjunto.insert(v) }
    }
    private func cargar() async {
        opciones = try? await API.get("/api/gustos/opciones")
        if let g: MisGustos = try? await API.get("/api/gustos") { generos = Set(g.generos); artistas = Set(g.artistas) }
    }
    private func guardar() async {
        guardando = true
        defer { guardando = false }
        do {
            try await API.hacer("PUT", "/api/gustos", ["generos": Array(generos), "artistas": Array(artistas)])
            Avisos.compartido.mostrar("¡Listo! Tu inicio se armará con lo que te gusta")
            cerrar()
        } catch { Avisos.compartido.error(error) }
    }

    /// A quien creó su cuenta hace menos de 30 días y no eligió sus gustos se le pregunta una vez (como la web).
    @MainActor
    static func debePreguntar() async -> Bool {
        guard let u = Sesion.compartida.usuario else { return false }
        let clave = "gustosPreguntado:\(Sesion.compartida.servidor)|\(u.id)"
        guard !UserDefaults.standard.bool(forKey: clave) else { return false }
        guard let reg = Formato.fecha(u.fechaRegistro), Date().timeIntervalSince(reg) < 30 * 86400 else { return false }
        guard let g: MisGustos = try? await API.get("/api/gustos") else { return false }
        UserDefaults.standard.set(true, forKey: clave)
        return !g.hecho
    }
}

// MARK: - Tu resumen

struct ResumenVista: View {
    @State private var anio = Calendar.current.component(.year, from: Date())
    @State private var mes: Int? = nil
    @State private var anios: [Int] = [Calendar.current.component(.year, from: Date())]
    @State private var r: ResumenEscucha?
    @State private var error: String?
    @State private var tarjeta: UIImage?
    private let meses = ["Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"]
    private var mesesValidos: [Int] {
        let hoy = Date(), cal = Calendar.current
        return anio == cal.component(.year, from: hoy) ? Array(1...cal.component(.month, from: hoy)) : Array(1...12)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Picker("Año", selection: $anio) { ForEach(anios, id: \.self) { Text(String($0)).tag($0) } }
                    Picker("Período", selection: $mes) {
                        Text("Todo el año").tag(Int?.none)
                        ForEach(mesesValidos, id: \.self) { m in Text(meses[m - 1]).tag(Int?.some(m)) }
                    }
                    Spacer()
                }
                .onChange(of: anio) { _, _ in mes = nil }
                if let error { EstadoVista(icono: "wifi.exclamationmark", titulo: "No se pudo cargar", texto: error) }
                if let r {
                    Text(r.titulo).font(.system(.largeTitle, design: .rounded).weight(.heavy))
                    if !r.suficiente {
                        EstadoVista(icono: "gift", titulo: "Aún no hay resumen", texto: r.nota ?? "Escucha más música en este período.")
                    } else {
                        if r.enCurso { Text("El período sigue en curso: los números se actualizan mientras escuchas.").font(.caption).foregroundStyle(Tema.secundario) }
                        contenido(r)
                    }
                } else if error == nil { Cargando() }
            }
            .padding()
        }
        .navigationTitle("Tu resumen")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(anio)-\(mes ?? 0)") { await cargar() }
        .task { if let p: PeriodosResumen = try? await API.get("/api/resumen/periodos"), !p.anios.isEmpty { anios = p.anios } }
    }

    @ViewBuilder
    private func contenido(_ r: ResumenEscucha) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            dato("Minutos escuchados", Formato.numero(r.minutos), "\(r.reproducciones) reproducciones · \(r.diasActivos) días con música")
            dato("Canciones distintas", Formato.numero(r.cancionesDistintas), nil)
            dato("Artistas", Formato.numero(r.artistasDistintos), r.estilo)
            dato("Mejor racha", r.racha == 1 ? "1 día" : "\(r.racha) días", "seguidos escuchando")
            dato("Tu momento", r.momento, "sobre todo los \(r.diaFavorito)" + (r.mesMasActivo.map { " · tu mes: \($0)" } ?? ""))
        }
        if let cambio = r.cambioPorcentaje {
            dato("Comparado con el período anterior", String(format: "%+.0f %%", cambio), nil)
        }
        if let a = r.descubrimiento {
            NavigationLink(value: Destino.artista(a.nombre)) {
                Label("Tu descubrimiento: " + a.nombre, systemImage: "sparkles")
            }
        }
        if !r.topArtistas.isEmpty {
            Text("Tus artistas").titulo()
            ForEach(Array(r.topArtistas.enumerated()), id: \.offset) { i, a in
                NavigationLink(value: Destino.artista(a.nombre)) {
                    HStack(spacing: 12) {
                        Text("\(i + 1)").font(.title3.weight(.heavy)).foregroundStyle(Tema.secundario).frame(width: 24)
                        ImagenRemota(ruta: a.portadaUrl, icono: "music.mic", redonda: true).frame(width: 52, height: 52)
                        VStack(alignment: .leading) {
                            Text(a.nombre).font(.body.weight(.semibold)).lineLimit(1)
                            Text(a.minutos == 1 ? "1 minuto" : "\(a.minutos) minutos").font(.caption).foregroundStyle(Tema.secundario)
                        }
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
            }
        }
        if !r.topCanciones.isEmpty {
            Text("Tus canciones").titulo()
            ForEach(Array(r.topCanciones.enumerated()), id: \.offset) { i, c in
                FilaCancion(cancion: c, numero: i + 1) { Reproductor.compartido.reproducir(r.topCanciones, desde: i, contexto: "resumen") }
            }
        }
        if !r.topGeneros.isEmpty {
            Text("Tus géneros").titulo()
            ForEach(Array(r.topGeneros.enumerated()), id: \.offset) { _, g in
                HStack(spacing: 10) {
                    Text(g.nombre).font(.subheadline).frame(width: 110, alignment: .leading).lineLimit(1)
                    GeometryReader { geo in
                        Capsule().fill(Tema.aurora).frame(width: max(6, geo.size.width * g.porcentaje / 100))
                    }
                    .frame(height: 8)
                    Text("\(Int(g.porcentaje.rounded()))%").font(.caption.weight(.bold)).monospacedDigit()
                }
            }
        }
        if r.serie.count > 1 {
            Text(mes == nil ? "Minutos por mes" : "Minutos por día").titulo()
            let maximo = max(1, r.serie.map(\.minutos).max() ?? 1)
            HStack(alignment: .bottom, spacing: 3) {
                ForEach(Array(r.serie.enumerated()), id: \.offset) { _, p in
                    VStack(spacing: 4) {
                        RoundedRectangle(cornerRadius: 3).fill(Tema.aurora)
                            .frame(height: max(3, 110 * CGFloat(p.minutos) / CGFloat(maximo)))
                        Text(etiqueta(p.periodo)).font(.system(size: 9)).foregroundStyle(Tema.secundario).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(p.periodo): \(p.minutos) minutos")
                }
            }
            .frame(height: 130, alignment: .bottom)
        }
        if let p = r.primeraCancion {
            Text("La primera canción del período fue **\(p.nombre)** de \(p.artista).").font(.subheadline).foregroundStyle(Tema.secundario)
        }
        HStack(spacing: 10) {
            if !r.topCanciones.isEmpty {
                Button { Reproductor.compartido.reproducir(r.topCanciones, contexto: "resumen") } label: { Label("Escuchar tus favoritas", systemImage: "play.fill") }
                    .buttonStyle(BotonPrincipal())
            }
            if let tarjeta {
                ShareLink(item: Image(uiImage: tarjeta), preview: SharePreview(r.titulo, image: Image(uiImage: tarjeta))) {
                    Label("Compartir tarjeta", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(BotonSecundario())
            }
        }
    }

    private func dato(_ k: String, _ v: String, _ sub: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(k).font(.caption.weight(.bold)).foregroundStyle(Tema.secundario).textCase(.uppercase)
            Text(v).font(.system(.title, design: .rounded).weight(.heavy)).minimumScaleFactor(0.6).lineLimit(2)
            if let sub { Text(sub).font(.caption).foregroundStyle(Tema.secundario).lineLimit(3) }
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 110, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tema.tarjeta))
    }

    private func etiqueta(_ periodo: String) -> String {
        let partes = periodo.split(separator: "-")
        if mes == nil, partes.count >= 2, let m = Int(partes[1]), (1...12).contains(m) { return String(meses[m - 1].prefix(3)) }
        if partes.count >= 3, let d = Int(partes[2]) { return String(d) }
        return periodo
    }

    private func cargar() async {
        r = nil; error = nil; tarjeta = nil
        do {
            let nuevo: ResumenEscucha = try await API.get("/api/resumen?anio=\(anio)" + (mes.map { "&mes=\($0)" } ?? ""))
            r = nuevo
            if nuevo.suficiente { tarjeta = TarjetaResumen.imagen(nuevo, usuario: Sesion.compartida.usuario?.username ?? "") }
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

/// Tarjeta 1080×1350 con lo principal del resumen (como `compartir` de comunidad.js).
enum TarjetaResumen {
    @MainActor
    static func imagen(_ r: ResumenEscucha, usuario: String) -> UIImage? {
        let vista = VStack(alignment: .leading, spacing: 0) {
            Text("SpaceMusic").font(.system(size: 13, weight: .semibold)).foregroundStyle(.white.opacity(0.7)).padding(.top, 30)
            Text(r.titulo).font(.system(size: 21, weight: .bold)).foregroundStyle(.white).lineLimit(1).padding(.top, 8)
            Text(Formato.numero(r.minutos)).font(.system(size: 50, weight: .heavy)).foregroundStyle(.white).padding(.top, 22)
            Text("minutos escuchados").font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.75))
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Artistas").font(.system(size: 12, weight: .semibold)).foregroundStyle(.white.opacity(0.6))
                    ForEach(Array(r.topArtistas.prefix(5).enumerated()), id: \.offset) { i, a in
                        Text("\(i + 1). \(a.nombre)").font(.system(size: 13, weight: .bold)).foregroundStyle(.white).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Canciones").font(.system(size: 12, weight: .semibold)).foregroundStyle(.white.opacity(0.6))
                    ForEach(Array(r.topCanciones.prefix(5).enumerated()), id: \.offset) { i, c in
                        Text("\(i + 1). \(c.nombre)").font(.system(size: 13, weight: .bold)).foregroundStyle(.white).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.top, 34)
            Spacer(minLength: 12)
            Group {
                Text("\(r.cancionesDistintas) canciones · \(r.artistasDistintos) artistas · racha de \(r.racha) días")
                if let g = r.topGeneros.first { Text("Género favorito: \(g.nombre)") }
                Text("Escuchas \(r.momento) · \(r.estilo)")
            }
            .font(.system(size: 11, weight: .medium)).foregroundStyle(.white.opacity(0.8)).lineLimit(1).padding(.bottom, 4)
            Text("@\(usuario)").font(.system(size: 11, weight: .medium)).foregroundStyle(.white.opacity(0.55)).padding(.top, 18).padding(.bottom, 26)
        }
        .padding(.horizontal, 27)
        .frame(width: 360, height: 450, alignment: .topLeading)
        .background(LinearGradient(colors: [Color(red: 0.23, green: 0.05, blue: 0.09), Color(red: 0.07, green: 0.04, blue: 0.06), .black],
                                   startPoint: .topLeading, endPoint: .bottomTrailing))
        let render = ImageRenderer(content: vista)
        render.scale = 3
        return render.uiImage
    }
}
