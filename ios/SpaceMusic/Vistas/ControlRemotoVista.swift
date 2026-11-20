import SwiftUI

/// Ícono de cada tipo de dispositivo.
enum IconoDispositivo {
    static func de(_ plataforma: String) -> String {
        switch plataforma {
        case "ios": return "iphone"
        case "android": return "smartphone"
        default: return "laptopcomputer"
        }
    }
}

/// Fila de un dispositivo en «Dispositivos». Al tocarlo la música pasa a sonar allá y se controla con el mismo
/// reproductor de siempre (como Spotify Connect): ya no se abre una pantalla aparte.
struct FilaDispositivoRemoto: View {
    let d: DispositivoRemoto
    let elegido: Bool
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: IconoDispositivo.de(d.plataforma))
                .font(.title3)
                .frame(width: 40, height: 40)
                .foregroundStyle(elegido ? Color.black : Color.white)
                .background(RoundedRectangle(cornerRadius: 12).fill(elegido ? Color.white : Color.white.opacity(0.1)))
            VStack(alignment: .leading, spacing: 3) {
                Text(d.nombre).font(.headline).lineLimit(1)
                Text(d.cancion.map { "\(d.sonando ? "Sonando" : "En pausa"): \($0.nombre) · \($0.artista)" } ?? "Sin música")
                    .font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
            }
            Spacer(minLength: 0)
            if elegido { Image(systemName: "checkmark").font(.body.weight(.semibold)) }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(elegido ? .isSelected : [])
    }
}
