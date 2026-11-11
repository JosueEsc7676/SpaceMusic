import SwiftUI

struct InicioVista: View {
    @State private var inicio: Inicio?
    @State private var amigos: [Actividad] = []
    @State private var error: String?
    @State private var eventos = Eventos.compartido
    @State private var sesion = Sesion.compartida

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

    @ViewBuilder
    private func contenido(_ i: Inicio) -> some View {
        LazyVStack(alignment: .leading, spacing: 30) {
            if !i.recientes.isEmpty { accesosRapidos(Array(i.recientes.prefix(6))) }

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
                Carrusel(titulo: "Tendencias esta semana") {
                    ForEach(i.tendencias) { TarjetaCancion(cancion: $0, lista: i.tendencias) }
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
