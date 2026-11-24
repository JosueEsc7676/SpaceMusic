import SwiftUI

/// Diseño Liquid Glass de iOS 26: barra de pestañas de vidrio que se encoge al desplazar, el mini reproductor
/// flotando sobre ella (como Apple Music) y botones y tarjetas de vidrio. En Ajustes se puede volver al diseño
/// clásico; en iPhone con iOS 17 o 18 siempre se usa el clásico.
enum Cristal {
    static let clave = "disenoCristal"

    /// Se lee al abrir la app (la barra de pestañas se configura una sola vez).
    static let activo: Bool = {
        guard #available(iOS 26, *) else { return false }
        return UserDefaults.standard.object(forKey: clave) as? Bool ?? true
    }()

    static var disponible: Bool {
        if #available(iOS 26, *) { return true }
        return false
    }
}

extension View {
    /// Fondo de vidrio líquido (iOS 26) o el relleno de siempre.
    @ViewBuilder
    func fondoCristal<S: Shape>(_ forma: S, relleno: Color = Tema.tarjeta, interactivo: Bool = false) -> some View {
        if #available(iOS 26, *), Cristal.activo {
            self.glassEffect(interactivo ? .regular.interactive() : .regular, in: forma)
        } else {
            self.background(forma.fill(relleno))
        }
    }

    /// Vidrio con un tinte de color (tarjetas destacadas).
    @ViewBuilder
    func cristalTenido<S: Shape>(_ color: Color, en forma: S) -> some View {
        if #available(iOS 26, *), Cristal.activo {
            self.glassEffect(.regular.tint(color.opacity(0.35)).interactive(), in: forma)
        } else {
            self
        }
    }
}

/// Mini reproductor dentro del accesorio de la barra de pestañas (iOS 26). El sistema le pone el vidrio;
/// cuando la barra se encoge al desplazar, se muestra en una sola línea.
@available(iOS 26, *)
struct MiniReproductorCristal: View {
    @State private var r = Reproductor.compartido
    @State private var biblioteca = Biblioteca.compartida
    @State private var dispositivos = false
    @Environment(\.tabViewBottomAccessoryPlacement) private var lugar

    var body: some View {
        if let c = r.actual {
            let compacto = lugar == .inline
            HStack(spacing: compacto ? 6 : 8) {
                Button { Navegacion.compartida.reproductorAbierto = true } label: {
                    HStack(spacing: 10) {
                        Portada(ruta: c.portadaUrl, tamano: compacto ? 26 : 34, radio: compacto ? 5 : 7)
                        VStack(alignment: .leading, spacing: 0) {
                            Text(c.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                            if !compacto {
                                if let d = r.remoto {
                                    Label("En \(d.nombre)", systemImage: "hifispeaker.fill").font(.caption).lineLimit(1)
                                } else {
                                    Text(c.artista).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .contentShape(Rectangle())
                }
                .accessibilityLabel("Abrir reproductor: \(c.nombre)")
                if !compacto {
                    Button { dispositivos = true } label: {
                        Image(systemName: r.remoto != nil ? "hifispeaker.fill" : "airplayaudio").frame(width: 34, height: 36)
                    }
                    .accessibilityLabel("Dispositivos y SyncPlay")
                    Button { biblioteca.alternarFavorito(c) } label: {
                        Image(systemName: biblioteca.esFavorita(c.id) ? "heart.fill" : "heart")
                            .foregroundStyle(biblioteca.esFavorita(c.id) ? Tema.rosa : Tema.texto)
                            .frame(width: 34, height: 36)
                    }
                    .accessibilityLabel(biblioteca.esFavorita(c.id) ? "Quitar de favoritos" : "Agregar a favoritos")
                }
                Button { r.alternar() } label: {
                    Group {
                        if r.esperaVisible && !r.sonando { ProgressView() } else {
                            Image(systemName: r.sonando ? "pause.fill" : "play.fill")
                        }
                    }
                    .font(.title3).frame(width: 36, height: 36)
                    .contentTransition(.symbolEffect(.replace))
                }
                .accessibilityLabel(r.sonando ? "Pausar" : "Reproducir")
                Button { r.siguiente() } label: {
                    Image(systemName: "forward.fill").frame(width: 32, height: 36)
                }
                .accessibilityLabel("Siguiente")
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 12)
            .sheet(isPresented: $dispositivos) { DispositivosVista() }
            .gesture(DragGesture(minimumDistance: 20).onEnded { g in
                if g.translation.width < -60 { r.siguiente() }
                else if g.translation.width > 60 { r.anterior() }
                else if g.translation.height < -30 { Navegacion.compartida.reproductorAbierto = true }
            })
        } else {
            Text("SpaceMusic").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
        }
    }
}

/// Pone el mini reproductor como accesorio de la barra de pestañas.
/// Importante: la estructura de la vista NO debe cambiar al abrir un chat (si cambiaba, iOS rehacía todas las pestañas,
/// el chat se cerraba y se volvía a abrir sin fin y la app se trababa). En iOS 26.1+ se activa o desactiva con `isEnabled`;
/// en 26.0 solo depende de si hay una canción.
@available(iOS 26, *)
struct AccesorioReproductor: ViewModifier {
    let hayCancion: Bool
    let visible: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: hayCancion && visible) { MiniReproductorCristal() }
        } else if hayCancion {
            content.tabViewBottomAccessory { MiniReproductorCristal() }
        } else {
            content
        }
    }
}
