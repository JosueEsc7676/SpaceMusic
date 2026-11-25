import ActivityKit
import SwiftUI
import WidgetKit

/// Solo cambia texto en esta tarjeta; nunca sustituye la portada del reproductor del sistema.
@main
struct LetraBloqueoWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LetraActividad.self) { contexto in
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: contexto.state.pausada ? "pause.fill" : "music.note")
                    Text(contexto.state.cancion).font(.caption.weight(.semibold)).lineLimit(1)
                    Spacer()
                    Text("SpaceMusic").font(.caption2).foregroundStyle(.secondary)
                }
                Text(contexto.state.verso.isEmpty ? "Esperando el prÃ³ximo versoâ€¦" : contexto.state.verso)
                    .font(.system(size: 19, weight: .semibold, design: .rounded))
                    .lineLimit(4).contentTransition(.opacity)
                if !contexto.state.siguiente.isEmpty {
                    Text(contexto.state.siguiente).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
                if contexto.isStale { Text("Actualización pendiente de iOS").font(.caption2).foregroundStyle(.secondary) }
                Text(contexto.state.artista).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(16)
            .activityBackgroundTint(.black.opacity(0.82))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { contexto in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) {
                    Text(contexto.state.verso.isEmpty ? contexto.state.cancion : contexto.state.verso)
                        .font(.headline).lineLimit(3).contentTransition(.opacity)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(contexto.state.artista).font(.caption).lineLimit(1)
                }
            } compactLeading: {
                Image(systemName: "music.note")
            } compactTrailing: {
                Image(systemName: contexto.state.pausada ? "pause.fill" : "waveform")
            } minimal: {
                Image(systemName: "music.note")
            }
        }
    }
}
