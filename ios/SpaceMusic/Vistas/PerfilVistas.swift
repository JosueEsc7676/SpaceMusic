import Charts
import PhotosUI
import SwiftUI

/// Pestaña "Tú": tu perfil, notificaciones, estadísticas, ajustes y administración.
struct MiPerfilVista: View {
    @State private var sesion = Sesion.compartida
    @State private var eventos = Eventos.compartido
    @State private var reproductor = Reproductor.compartido
    @State private var confirmarSalir = false
    @State private var dispositivos = false

    var body: some View {
        List {
            if let u = sesion.usuario {
                Section {
                    NavigationLink(value: Destino.perfil(u.username)) {
                        HStack(spacing: 14) {
                            Avatar(ruta: u.fotoPerfil, nombre: u.nombre, tamano: 62)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(u.nombre).font(.title3.weight(.bold))
                                Text("Ver tu perfil").font(.subheadline).foregroundStyle(Tema.secundario)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            Section {
                NavigationLink(value: Destino.notificaciones) {
                    HStack {
                        Label("Notificaciones", systemImage: "bell")
                        Spacer()
                        if eventos.notificacionesSinLeer > 0 {
                            Text("\(eventos.notificacionesSinLeer)").font(.caption.weight(.bold))
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Capsule().fill(Tema.rosa)).foregroundStyle(.black)
                        }
                    }
                }
                NavigationLink(value: Destino.estadisticas) { Label("Tus estadísticas", systemImage: "chart.bar") }
                NavigationLink(value: Destino.resumen) { Label("Tu resumen", systemImage: "gift") }
                NavigationLink(value: Destino.novedades) { Label("Novedades", systemImage: "sparkles") }
            }
            Section("Tu música") {
                NavigationLink(value: Destino.descargas) { Label("Descargas", systemImage: "arrow.down.circle") }
                Button { Hojas.compartidas.gustosInicial = false; Hojas.compartidas.gustos = true } label: {
                    Label("Tus gustos", systemImage: "heart.circle")
                }
                .foregroundStyle(Tema.texto)
                NavigationLink { DespertadorVista() } label: { Label("Despertador", systemImage: "alarm") }
                NavigationLink(value: Destino.preparar) { Label("Preparar sin internet", systemImage: "airplane") }
            }
            Section("Descubre") {
                NavigationLink(value: Destino.descubrir) { Label("Descubrir", systemImage: "wand.and.stars") }
                NavigationLink(value: Destino.rutas) { Label("Rutas musicales", systemImage: "safari") }
                NavigationLink(value: Destino.coincidimos) { Label("Coincidimos", systemImage: "person.2") }
                NavigationLink(value: Destino.dedicatorias) { Label("Dedicatorias", systemImage: "heart.text.square") }
            }
            Section("Ajustes") {
                Toggle(isOn: $reproductor.radioAutomatica) {
                    Label("Al terminar, seguir con canciones parecidas", systemImage: "dot.radiowaves.left.and.right")
                }
                .tint(Tema.celeste)
                NavigationLink { AjustesAppVista() } label: { Label("Reproducción, audio y diseño", systemImage: "slider.horizontal.3") }
                NavigationLink { EstiloBloqueoVista() } label: { Label("Pantalla bloqueada", systemImage: "lock.iphone") }
                Button { dispositivos = true } label: { Label("Dispositivos y SyncPlay", systemImage: "airplayaudio") }
                    .foregroundStyle(Tema.texto)
                NavigationLink(value: Destino.cuenta) { Label("Cuenta y privacidad", systemImage: "person.badge.key") }
            }
            if sesion.puedeEditar || sesion.esAdmin {
                Section("Administración") {
                    if sesion.puedeEditar {
                        NavigationLink(value: Destino.subir) { Label("Subir canciones", systemImage: "square.and.arrow.up") }
                    }
                    if sesion.esAdmin {
                        NavigationLink(value: Destino.admin) { Label("Panel de administración", systemImage: "gearshape.2") }
                        NavigationLink { SoporteTecnicoVista() } label: { Label("Soporte técnico", systemImage: "stethoscope") }
                    }
                }
            }
            Section {
                Button { Navegacion.compartida.verActualizacion = true } label: {
                    LabeledContent { Text(Actualizaciones.compartida.versionActual) } label: { Label("Versión de la app", systemImage: "info.circle") }
                }
                .foregroundStyle(Tema.texto)
                Button(role: .destructive) { confirmarSalir = true } label: { Label("Cerrar sesión", systemImage: "rectangle.portrait.and.arrow.right") }
            } footer: {
                Text("SpaceMusic para iPhone \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · \(sesion.servidor.replacingOccurrences(of: "https://", with: ""))")
            }
        }
        .navigationTitle("Tú")
        .sheet(isPresented: $dispositivos) { DispositivosVista() }
        .confirmationDialog("¿Cerrar sesión?", isPresented: $confirmarSalir, titleVisibility: .visible) {
            Button("Cerrar sesión", role: .destructive) {
                Biblioteca.compartida.limpiar()
                sesion.cerrar()
            }
        } message: { Text("Las canciones descargadas en este iPhone se borrarán.") }
    }
}

/// Perfil de cualquier persona (o el propio).
struct PerfilVista: View {
    let username: String
    @State private var perfil: Perfil?
    @State private var error: String?
    @State private var foto: PhotosPickerItem?
    @State private var eligiendo = false
    /// true si la foto elegida es para la portada, false para la foto de perfil.
    @State private var paraPortada = false
    @State private var ajustandoPortada = false
    @State private var posicionPortada = 50.0
    @State private var guardandoPortada = false
    @State private var editandoNombre = false
    @State private var nombre = ""

    var body: some View {
        Group {
            if let p = perfil {
                contenido(p)
            } else if let error {
                EstadoVista(icono: "person.crop.circle.badge.exclamationmark", titulo: "No se pudo abrir el perfil", texto: error) {
                    Task { await cargar() }
                }
            } else {
                Cargando()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { if perfil == nil { await cargar() } }
        .photosPicker(isPresented: $eligiendo, selection: $foto, matching: .images)
        .onChange(of: foto) { _, i in if let i { Task { await subir(i, portada: paraPortada) } } }
        .sheet(isPresented: $ajustandoPortada) {
            NavigationStack {
                VStack(spacing: 24) {
                    ImagenRemota(ruta: perfil?.banner, icono: "photo", posicionVertical: CGFloat(posicionPortada / 100))
                        .frame(height: 170).clipped()
                    Text("Elige qué parte de la foto se muestra").font(.headline)
                    Slider(value: $posicionPortada, in: 0...100).accessibilityLabel("Posición vertical de la portada")
                    if let error { Text(error).foregroundStyle(Tema.peligro) }
                    Spacer()
                }
                .padding().navigationTitle("Ajustar portada").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { ajustandoPortada = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Guardar") {
                            Task {
                                guard !guardandoPortada else { return }; guardandoPortada = true
                                defer { guardandoPortada = false }
                                do {
                                    try await API.hacer("PUT", "/api/perfiles/yo/banner/posicion", ["pos": Int(posicionPortada.rounded())])
                                    await cargar(); ajustandoPortada = false
                                } catch { self.error = error.localizedDescription }
                            }
                        }.disabled(guardandoPortada)
                    }
                }
            }.presentationDetents([.medium, .large])
        }
        .alert("Tu nombre", isPresented: $editandoNombre) {
            TextField("Nombre", text: $nombre)
            Button("Guardar") { Task { await guardarNombre() } }
            Button("Cancelar", role: .cancel) {}
        }
    }

    private func cargar() async {
        do {
            perfil = try await API.get("/api/perfiles/\(API.segmento(username))")
            error = nil
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func contenido(_ p: Perfil) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                cabecera(p)
                if let a = p.actividad, let c = a.cancion {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(a.ahora ? "Escuchando ahora" : "Escuchó hace poco").titulo()
                        FilaCancion(cancion: c, detalle: a.ahora ? c.artista : "\(c.artista) · \(Formato.hace(a.hace))") {
                            Reproductor.compartido.reproducirSuelta(c, contexto: "perfil")
                        }
                    }
                    .padding(.horizontal)
                }
                if let comp = p.compatibilidad, !p.esYo {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Compatibilidad musical").titulo()
                        HStack(alignment: .firstTextBaseline) {
                            Text("\(Int(comp.rounded()))%").font(.system(size: 40, weight: .heavy, design: .rounded))
                                .foregroundStyle(Tema.aurora)
                            if !p.artistasEnComun.isEmpty {
                                Text("En común: " + p.artistasEnComun.prefix(4).joined(separator: ", "))
                                    .font(.subheadline).foregroundStyle(Tema.secundario)
                            }
                        }
                    }
                    .padding(.horizontal)
                }
                if !p.vibras.isEmpty {
                    // Vibras: insignias compactas en dos columnas (ícono de color, nombre y una línea)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Vibras").titulo()
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                            ForEach(Array(p.vibras.enumerated()), id: \.offset) { _, v in TarjetaVibra(vibra: v) }
                        }
                    }
                    .padding(.horizontal)
                }
                if !p.topCanciones.isEmpty {
                    let cs = p.topCanciones.compactMap(\.cancion)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Canciones más escuchadas").titulo().padding(.horizontal)
                        ListaCanciones(canciones: cs, contexto: "perfil", numerada: true)
                    }
                }
                if !p.topArtistas.isEmpty {
                    Carrusel(titulo: "Artistas favoritos") {
                        ForEach(Array(p.topArtistas.enumerated()), id: \.offset) { _, a in
                            NavigationLink(value: Destino.artista(a.artista)) {
                                VStack(spacing: 6) {
                                    ImagenRemota(ruta: a.portadaUrl, icono: "music.mic", redonda: true).frame(width: 100, height: 100)
                                    Text(a.artista).font(.caption.weight(.semibold)).lineLimit(1)
                                }
                                .frame(width: 100)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if !p.topGeneros.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Géneros").titulo()
                        GraficaGeneros(generos: p.topGeneros)
                    }
                    .padding(.horizontal)
                }
                if !p.playlists.isEmpty {
                    Carrusel(titulo: "Playlists públicas") { ForEach(p.playlists) { TarjetaPlaylist(playlist: $0) } }
                }
            }
            .padding(.bottom, 24)
        }
        .refreshable { await cargar() }
    }

    private func cabecera(_ p: Perfil) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ZStack(alignment: .bottomLeading) {
                Group {
                    if let b = p.banner, !b.isEmpty {
                        ImagenRemota(ruta: b, icono: "photo", posicionVertical: CGFloat(p.bannerPos / 100))
                    } else {
                        LinearGradient(colors: [Tema.lila.opacity(0.6), Tema.celeste.opacity(0.4), Tema.rosa.opacity(0.5)],
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                    }
                }
                .frame(height: 170)
                .frame(maxWidth: .infinity)
                .clipped()
                .overlay(alignment: .topTrailing) {
                    if p.esYo {
                        Menu {
                            Button { paraPortada = true; eligiendo = true } label: { Label("Cambiar portada", systemImage: "photo") }
                            if p.banner != nil {
                                Button("Ajustar posición") { posicionPortada = p.bannerPos; ajustandoPortada = true }
                                Button(role: .destructive) { Task { await quitarPortada() } } label: {
                                    Label("Quitar portada", systemImage: "trash")
                                }
                            }
                        } label: {
                            Image(systemName: "camera.fill").padding(10).background(Circle().fill(.black.opacity(0.5)))
                        }
                        .padding(10)
                    }
                }
                Avatar(ruta: p.mini.fotoPerfil, nombre: p.mini.nombre, tamano: 96)
                    .overlay(Circle().stroke(Color.black, lineWidth: 4))
                    .overlay(alignment: .bottomTrailing) {
                        if p.esYo {
                            Button { paraPortada = false; eligiendo = true } label: {
                                Image(systemName: "camera.fill").font(.caption).padding(7)
                                    .background(Circle().fill(Color.white)).foregroundStyle(.black)
                            }
                        }
                    }
                    .offset(x: 16, y: 48)
            }
            .padding(.bottom, 44)

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(p.mini.nombre).font(.system(.title, design: .rounded).weight(.heavy))
                    if p.esYo {
                        Button {
                            nombre = p.mini.nombre
                            editandoNombre = true
                        } label: { Image(systemName: "pencil").foregroundStyle(Tema.secundario) }
                    }
                }
                Text("@\(p.mini.username)" + (p.mini.meSigue && !p.esYo ? " · Te sigue" : ""))
                    .font(.subheadline).foregroundStyle(Tema.secundario)
                if let b = p.biografia, !b.isEmpty { Text(b).font(.subheadline) }
                HStack(spacing: 18) {
                    NavigationLink(value: Destino.personas(username: p.mini.username, seguidores: true)) {
                        Text("**\(p.seguidores)** seguidores")
                    }
                    NavigationLink(value: Destino.personas(username: p.mini.username, seguidores: false)) {
                        Text("**\(p.siguiendo)** siguiendo")
                    }
                }
                .font(.subheadline)
                .foregroundStyle(Tema.texto)
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
            .padding(.horizontal)

            if !p.esYo {
                HStack(spacing: 10) {
                    Button { Task { await seguir(p) } } label: {
                        Text(p.mini.yoSigo ? "Siguiendo" : (p.mini.meSigue ? "Seguir también" : "Seguir"))
                    }
                    .buttonStyle(p.mini.yoSigo ? AnyButtonStyle(BotonSecundario()) : AnyButtonStyle(BotonPrincipal()))
                    if p.mini.amigos {
                        NavigationLink(value: Destino.chat(p.mini.username)) {
                            Label("Mensaje", systemImage: "bubble.left")
                        }
                        .buttonStyle(BotonSecundario())
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private func seguir(_ p: Perfil) async {
        do {
            try await API.hacer(p.mini.yoSigo ? "DELETE" : "POST", "/api/perfiles/\(API.segmento(p.mini.username))/seguir")
            await cargar()
        } catch {
            Avisos.compartido.error(error)
        }
    }

    private func subir(_ item: PhotosPickerItem, portada esPortada: Bool) async {
        guard let datos = try? await item.loadTransferable(type: Data.self),
              let jpg = Fotos.jpeg(datos, maximo: esPortada ? 1800 : 600) else { return }
        do {
            let archivo = ArchivoSubida(campo: "file", nombre: esPortada ? "portada.jpg" : "foto.jpg", tipo: "image/jpeg", datos: jpg)
            let r: RespuestaOK = try await API.subir(esPortada ? "/api/perfiles/yo/banner" : "/api/auth/me/foto", archivos: [archivo])
            if !esPortada, let f = r.fotoPerfil, var u = Sesion.compartida.usuario {
                u.fotoPerfil = f
                Sesion.compartida.ponerUsuario(u)
            }
            await cargar()
        } catch {
            Avisos.compartido.error(error)
        }
        foto = nil
    }

    private func quitarPortada() async {
        do {
            try await API.hacer("DELETE", "/api/perfiles/yo/banner")
            await cargar()
        } catch {
            Avisos.compartido.error(error)
        }
    }

    private func guardarNombre() async {
        do {
            let r: RespuestaOK = try await API.enviar("PUT", "/api/auth/me", ["nombreDisplay": nombre.trimmingCharacters(in: .whitespaces)])
            if let u = r.usuario { Sesion.compartida.ponerUsuario(u) }
            await cargar()
        } catch {
            Avisos.compartido.error(error)
        }
    }
}

/// Permite usar dos estilos de botón según el estado.
struct AnyButtonStyle: ButtonStyle {
    private let crear: (Configuration) -> AnyView
    init<S: ButtonStyle>(_ s: S) { crear = { AnyView(s.makeBody(configuration: $0)) } }
    func makeBody(configuration: Configuration) -> some View { crear(configuration) }
}

/// Seguidores o seguidos de una persona.
struct PersonasVista: View {
    let username: String
    let seguidores: Bool

    var body: some View {
        Cargador(cargar: { () async throws -> [PerfilMini] in
            try await API.get("/api/perfiles/\(API.segmento(username))/\(seguidores ? "seguidores" : "siguiendo")")
        }) { lista, recargar in
            List {
                if lista.isEmpty {
                    Text(seguidores ? "Nadie todavía" : "No sigue a nadie todavía").foregroundStyle(Tema.secundario)
                }
                ForEach(lista) { FilaPersona(persona: $0) }
            }
            .listStyle(.plain)
            .refreshable { await recargar() }
        }
        .navigationTitle(seguidores ? "Seguidores" : "Siguiendo")
    }
}

/// Una vibra del perfil: insignia compacta con su ícono en un círculo de color, el nombre y una línea.
struct TarjetaVibra: View {
    let vibra: Perfil.Vibra

    /// El servidor manda el nombre del ícono de la web; aquí se usa el símbolo equivalente del iPhone.
    static func simbolo(_ icono: String?) -> String {
        switch icono ?? "" {
        case "fire": "flame.fill"
        case "mic": "music.mic"
        case "star": "star.fill"
        case "wave": "waveform"
        case "moon": "moon.stars.fill"
        case "sparkles": "sparkles"
        case "heart": "heart.fill"
        case "sun": "sun.max.fill"
        case "disc": "opticaldisc.fill"
        case "compass": "safari.fill"
        case "clock": "flame"
        default: "music.note"
        }
    }

    var body: some View {
        let (r, g, b) = Tema.colorGenero(vibra.nombre)
        let color = Color(red: r, green: g, blue: b)
        HStack(spacing: 10) {
            Image(systemName: TarjetaVibra.simbolo(vibra.icono))
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 38, height: 38)
                .background(Circle().fill(LinearGradient(colors: [color, color.opacity(0.6)], startPoint: .topLeading, endPoint: .bottomTrailing)))
            VStack(alignment: .leading, spacing: 2) {
                Text(vibra.nombre).font(.subheadline.weight(.bold)).lineLimit(1)
                Text(vibra.descripcion).font(.caption2).foregroundStyle(Tema.secundario).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(color.opacity(0.14)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(color.opacity(0.25)))
        .accessibilityElement(children: .combine)
    }
}

/// Géneros del perfil, fácil de leer: el favorito arriba y cada género con su barra y porcentaje.
/// Se omiten las etiquetas genéricas («Music», «Desconocido»…) y los porcentajes se reparten entre los que quedan.
struct GraficaGeneros: View {
    let generos: [Perfil.TopGenero]

    private static let genericos: Set<String> = ["music", "música", "musica", "desconocido", "unknown", "other", "otros", "otro", "varios", ""]

    private var limpios: [(nombre: String, porcentaje: Double)] {
        let validos = generos.filter { !GraficaGeneros.genericos.contains($0.genero.trimmingCharacters(in: .whitespaces).lowercased()) && $0.porcentaje > 0 }
        let suma = validos.reduce(0) { $0 + $1.porcentaje }
        guard suma > 0 else { return [] }
        return validos.prefix(5).map { ($0.genero, $0.porcentaje / suma * 100) }
    }

    private func color(_ nombre: String) -> Color {
        let (r, g, b) = Tema.colorGenero(nombre)
        return Color(red: r, green: g, blue: b)
    }

    var body: some View {
        let lista = limpios
        VStack(alignment: .leading, spacing: 14) {
            if let primero = lista.first {
                HStack(spacing: 12) {
                    Image(systemName: "crown.fill").font(.title3).foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(color(primero.nombre)))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Tu género favorito").font(.caption).foregroundStyle(Tema.secundario)
                        Text(primero.nombre).font(.title3.weight(.bold)).lineLimit(1)
                    }
                    Spacer()
                    Text("\(Int(primero.porcentaje.rounded()))%").font(.title3.weight(.heavy).monospacedDigit())
                        .foregroundStyle(color(primero.nombre))
                }
            }
            ForEach(Array(lista.enumerated()), id: \.offset) { i, g in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("\(i + 1). \(g.nombre)").font(.subheadline.weight(.semibold)).lineLimit(1)
                        Spacer()
                        Text("\(Int(g.porcentaje.rounded()))%").font(.subheadline.monospacedDigit()).foregroundStyle(Tema.secundario)
                    }
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.white.opacity(0.08))
                            Capsule().fill(LinearGradient(colors: [color(g.nombre), color(g.nombre).opacity(0.65)], startPoint: .leading, endPoint: .trailing))
                                .frame(width: max(8, geo.size.width * g.porcentaje / 100))
                        }
                    }
                    .frame(height: 8)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tema.tarjeta))
    }
}
