import SwiftUI

struct InicioVista: View {
    @State private var inicio: Inicio?
    @State private var amigos: [Actividad] = []
    @State private var error: String?
    @State private var eventos = Eventos.compartido
    @State private var sesion = Sesion.compartida
    @State private var conexion = Conexion.compartida
    @State private var actualizaciones = Actualizaciones.compartida

    var body: some View {
        ScrollView {
            if let inicio {
                contenido(inicio)
            } else if let error {
                EstadoVista(icono: "wifi.exclamationmark", titulo: "No se pudo cargar", texto: error) { Task { await cargar() } }
            } else {
                Cargando()
            }
        }
        .refreshable { await cargar() }
        .navigationTitle(saludo)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: Destino.notificaciones) {
                    Image(systemName: eventos.notificacionesSinLeer > 0 ? "bell.badge.fill" : "bell")
                        .symbolRenderingMode(.multicolor)
                }
            }
        }
        .task { if inicio == nil { await cargar() } }
        .onChange(of: conexion.disponible) { _, conectada in if conectada { Task { await cargar() } } }
    }

    private var saludo: String {
        let h = Calendar.current.component(.hour, from: Date())
        let base = h < 12 ? "Buenos días" : (h < 19 ? "Buenas tardes" : "Buenas noches")
        return base
    }

    private func cargar() async {
        do {
            async let a: [Actividad]? = try? API.get("/api/amigos/actividad")
            let i: Inicio = try await API.get("/api/inicio", tiempo: 20)
            inicio = i
            error = nil
            amigos = await a ?? []
        } catch is CancellationError {
        } catch {
            if inicio == nil { self.error = error.localizedDescription }
        }
    }

    /// Mezcla del día (`/api/shuffle/diario`) y aleatorio de toda la biblioteca (`/api/shuffle/cola`).
    private var mezclasRapidas: some View {
        HStack(spacing: 10) {
            botonMezcla("Mezcla del día", icono: "sun.max.fill", colores: [Tema.lila, Tema.rosa], ruta: "/api/shuffle/diario?cantidad=40", contexto: "mix")
            botonMezcla("Aleatorio de todo", icono: "shuffle", colores: [Tema.celeste, Tema.lila], ruta: "/api/shuffle/cola?cantidad=50", contexto: "otro")
        }
        .padding(.horizontal)
    }

    private func botonMezcla(_ titulo: String, icono: String, colores: [Color], ruta: String, contexto: String) -> some View {
        Button {
            Task {
                do {
                    let canciones: [Cancion] = try await API.get(ruta)
                    if canciones.isEmpty { Avisos.compartido.mostrar("Todavía no hay canciones para esta mezcla") }
                    else { Reproductor.compartido.reproducir(canciones, contexto: contexto) }
                } catch { Avisos.compartido.error(error) }
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: icono).font(.headline).foregroundStyle(.black)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(LinearGradient(colors: colores, startPoint: .topLeading, endPoint: .bottomTrailing)))
                Text(titulo).font(.subheadline.weight(.semibold)).lineLimit(2).minimumScaleFactor(0.85)
                Spacer(minLength: 0)
            }
            .padding(10)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tema.tarjeta))
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func contenido(_ i: Inicio) -> some View {
        LazyVStack(alignment: .leading, spacing: 30) {
            if let v = actualizaciones.disponible {
                Button { Navegacion.compartida.verActualizacion = true } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "arrow.down.app.fill").font(.title2).foregroundStyle(Tema.celeste)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Nueva versión de la app").font(.subheadline.weight(.bold))
                            Text("Versión \(v.nombre) lista para instalar").font(.caption).foregroundStyle(Tema.secundario)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(Tema.secundario)
                    }
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tema.celeste.opacity(0.12)))
                }
                .buttonStyle(.plain)
                .padding(.horizontal)
            }
            if let m = i.mixes.first { Destacado(mix: m) }
            if !i.recientes.isEmpty { accesosRapidos(Array(i.recientes.prefix(6))) }
            mezclasRapidas

            if !amigos.isEmpty {
                Carrusel(titulo: "Lo que escuchan tus amigos") {
                    ForEach(Array(amigos.enumerated()), id: \.offset) { _, a in TarjetaAmigo(actividad: a) }
                }
            }
            if !i.mixes.isEmpty {
                Carrusel(titulo: "Hecho para ti") { ForEach(i.mixes) { TarjetaMix(mix: $0) } }
            }
            if !i.tusFavoritas.isEmpty {
                Carrusel(titulo: "Lo que más escuchas") {
                    ForEach(i.tusFavoritas) { TarjetaCancion(cancion: $0, lista: i.tusFavoritas) }
                }
            }
            if !i.tendencias.isEmpty {
                // Lista numerada como la web («Lo más escuchado por todos»).
                VStack(alignment: .leading, spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Lo más escuchado por todos").font(.caption.weight(.bold)).foregroundStyle(Tema.secundario).textCase(.uppercase)
                        Text("Tendencias de la semana").titulo()
                    }
                    .padding(.horizontal)
                    Ranking(canciones: Array(i.tendencias.prefix(8)))
                }
            }
            if !i.recienAgregadas.isEmpty {
                Carrusel(titulo: "Recién agregadas") {
                    ForEach(i.recienAgregadas) { TarjetaCancion(cancion: $0, lista: i.recienAgregadas) }
                }
            }
            if !i.albumesRecientes.isEmpty {
                Carrusel(titulo: "Álbumes", destino: .albumes) { ForEach(i.albumesRecientes) { TarjetaAlbum(album: $0) } }
            }
            if !i.artistas.isEmpty {
                Carrusel(titulo: "Artistas", destino: .artistas) { ForEach(i.artistas) { TarjetaArtista(artista: $0) } }
            }
            if !i.playlistsPublicas.isEmpty {
                Carrusel(titulo: "Playlists de la comunidad") {
                    ForEach(i.playlistsPublicas) { TarjetaPlaylist(playlist: $0) }
                }
            }
            if !i.top.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Lo más escuchado").titulo().padding(.horizontal)
                    ListaCanciones(canciones: Array(i.top.prefix(10)), contexto: "inicio", numerada: false, enOrden: false)
                }
            }
            if !i.generos.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Géneros").titulo().padding(.horizontal)
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                        ForEach(i.generos.prefix(8)) { TarjetaGenero(genero: $0) }
                    }
                    .padding(.horizontal)
                }
            }
            Text("\(i.totalCanciones) canciones en el servidor")
                .font(.caption).foregroundStyle(Tema.secundario)
                .frame(maxWidth: .infinity)
        }
        .padding(.vertical, 12)
    }

    /// Cuadrícula de 2 columnas con lo último que sonó.
    private func accesosRapidos(_ cs: [Cancion]) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(cs) { c in
                Button { Reproductor.compartido.reproducirSuelta(c, contexto: "inicio") } label: {
                    HStack(spacing: 10) {
                        Portada(ruta: c.portadaUrl, tamano: 52, radio: 0)
                        Text(c.nombre).font(.caption.weight(.semibold)).lineLimit(2).multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                    .background(Tema.tarjeta)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                }
                .buttonStyle(.plain)
                .contextMenu { OpcionesCancion(cancion: c) }
            }
        }
        .padding(.horizontal)
    }
}

/// Tarjeta de "Lo que escuchan tus amigos".
struct TarjetaAmigo: View {
    let actividad: Actividad

    var body: some View {
        HStack(spacing: 12) {
            if let u = actividad.usuario {
                NavigationLink(value: Destino.perfil(u.username)) {
                    ZStack(alignment: .bottomTrailing) {
                        Avatar(ruta: u.fotoPerfil, nombre: u.nombre, tamano: 46)
                        if actividad.ahora {
                            Circle().fill(Color.green).frame(width: 12, height: 12)
                                .overlay(Circle().stroke(Color.black, lineWidth: 2))
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            if let c = actividad.cancion {
                Button { Reproductor.compartido.reproducirSuelta(c, contexto: "amigos") } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(actividad.usuario?.nombre ?? "").font(.caption.weight(.semibold)).foregroundStyle(Tema.secundario)
                            Text(c.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text(actividad.ahora ? "Escuchando ahora · \(c.artista)" : "\(Formato.hace(actividad.hace)) · \(c.artista)")
                                .font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                        }
                        Portada(ruta: c.portadaUrl, tamano: 44, radio: 6)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(12)
        .frame(width: 290, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tema.tarjeta))
    }
}

/// Gran tarjeta destacada con la primera mezcla del día (el «spotlight» de la web).
struct Destacado: View {
    let mix: Mix
    @State private var cargando = false

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            ImagenRemota(ruta: mix.portadas.first, icono: "sparkles")
                .blur(radius: 40)
                .scaleEffect(1.4)
                .opacity(0.75)
            LinearGradient(colors: [.black.opacity(0.75), .black.opacity(0.25)], startPoint: .bottomLeading, endPoint: .topTrailing)
            HStack(alignment: .bottom, spacing: 14) {
                VStack(alignment: .leading, spacing: 8) {
                    Label("Hecho para ti", systemImage: "sparkles").font(.caption.weight(.bold)).foregroundStyle(Tema.lila)
                    Text(mix.titulo).font(.system(.title, design: .rounded).weight(.heavy)).lineLimit(2).minimumScaleFactor(0.8)
                    Text(mix.subtitulo ?? mix.artistas.joined(separator: ", "))
                        .font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(2)
                    HStack(spacing: 10) {
                        Button { Task { await reproducir() } } label: {
                            Label(cargando ? "Cargando…" : "Reproducir", systemImage: "play.fill")
                                .lineLimit(1).fixedSize()
                        }
                        .buttonStyle(BotonPrincipal())
                        .disabled(cargando)
                        NavigationLink(value: Destino.mix(id: mix.id, titulo: mix.titulo)) {
                            Text("Ver mezcla").lineLimit(1).fixedSize()
                        }
                        .buttonStyle(BotonSecundario())
                    }
                    .padding(.top, 4)
                }
                // Los botones nunca se parten en sílabas: si no cabe, la portada se achica.
                .layoutPriority(1)
                Spacer(minLength: 0)
                Mosaico(rutas: mix.portadas, radio: 12)
                    .frame(minWidth: 0, maxWidth: 96)
                    .frame(height: 96, alignment: .trailing)
                    .rotationEffect(.degrees(6))
                    .shadow(color: .black.opacity(0.5), radius: 14, y: 8)
                    .accessibilityHidden(true)
            }
            .padding(18)
        }
        .frame(minHeight: 220)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.white.opacity(0.08)))
        .padding(.horizontal)
    }

    private func reproducir() async {
        cargando = true
        defer { cargando = false }
        do {
            let cs: [Cancion] = try await API.get("/api/mixes/\(API.segmento(mix.id))?cantidad=50")
            if !cs.isEmpty { Reproductor.compartido.reproducir(cs, contexto: "mix") }
        } catch { Avisos.compartido.error(error) }
    }
}

/// Lista numerada (tendencias) como la de la web.
struct Ranking: View {
    let canciones: [Cancion]
    @State private var reproductor = Reproductor.compartido

    var body: some View {
        VStack(spacing: 4) {
            ForEach(Array(canciones.enumerated()), id: \.element.id) { i, c in
                Button { Reproductor.compartido.reproducir(canciones, desde: i, contexto: "tendencias") } label: {
                    HStack(spacing: 12) {
                        Text("\(i + 1)")
                            .font(.system(.title2, design: .rounded).weight(.heavy))
                            .foregroundStyle(i < 3 ? AnyShapeStyle(Tema.aurora) : AnyShapeStyle(Color.white.opacity(0.45)))
                            .frame(width: 30)
                        Portada(ruta: c.portadaUrl, tamano: 46, radio: 8)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(c.nombre).font(.body.weight(.semibold)).lineLimit(1)
                                .foregroundStyle(reproductor.actual?.id == c.id ? Tema.celeste : Tema.texto)
                            Text(c.artista).font(.subheadline).foregroundStyle(Tema.secundario).lineLimit(1)
                        }
                        Spacer(minLength: 4)
                        Text(Formato.duracion(c.duracionSegundos)).font(.caption).monospacedDigit().foregroundStyle(Tema.secundario)
                    }
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .contextMenu { OpcionesCancion(cancion: c) }
            }
        }
        .padding(.horizontal)
    }
}
