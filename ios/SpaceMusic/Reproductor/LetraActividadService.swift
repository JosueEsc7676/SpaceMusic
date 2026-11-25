import ActivityKit
import Observation
import UIKit

/// Actualizaciones locales, sin APNs ni servidor adicional. iOS puede limitar su frecuencia.
@MainActor @Observable
final class LetraActividadService {
    static let compartida = LetraActividadService()
    private(set) var estado = "La letra aparecerÃƒÂ¡ en una tarjeta independiente de la portada."
    @ObservationIgnored private var actividad: Activity<LetraActividad>?
    @ObservationIgnored private var enviada: LetraActividad.ContentState?
    @ObservationIgnored private var pendiente: LetraActividad.ContentState?
    @ObservationIgnored private var trabajo: Task<Void, Never>?
    @ObservationIgnored private var identificadorTrabajo = UUID()
    @ObservationIgnored private var proximoIntento = 0.0

    private init() { actividad = Activity<LetraActividad>.activities.first }

    func actualizar(cancion: String, artista: String, verso: String?, siguiente: String?, pausada: Bool) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            finalizar()
            estado = "Activa Actividades en vivo para SpaceMusic en Ajustes del iPhone para ver la letra."
            return
        }
        let contenido = LetraActividad.ContentState(
            cancion: PoliticaLetraBloqueo.textoActividad(cancion, bytes: 250),
            artista: PoliticaLetraBloqueo.textoActividad(artista, bytes: 250),
            verso: PoliticaLetraBloqueo.textoActividad(verso ?? "", bytes: 900),
            siguiente: PoliticaLetraBloqueo.textoActividad(siguiente ?? "", bytes: 450), pausada: pausada)
        if actividad?.activityState == .dismissed || actividad?.activityState == .ended {
            actividad = nil
            enviada = nil
        }
        guard contenido != enviada, contenido != pendiente else { return }
        if actividad == nil {
            guard UIApplication.shared.applicationState == .active else {
                estado = "Abre SpaceMusic una vez para activar la tarjeta de letra antes de bloquear el iPhone."
                return
            }
            guard ProcessInfo.processInfo.systemUptime >= proximoIntento else { return }
            do {
                actividad = try Activity.request(attributes: LetraActividad(sesion: UUID().uuidString),
                    content: ActivityContent(state: contenido, staleDate: Date().addingTimeInterval(30)), pushType: nil)
                enviada = contenido
                estado = "Tarjeta de letra activada. iOS controla su frecuencia de actualizaciÃƒÂ³n."
            } catch {
                proximoIntento = ProcessInfo.processInfo.systemUptime + 10
                estado = "No se pudo activar la tarjeta. Revisa Actividades en vivo en Ajustes del iPhone."
            }
            return
        }
        pendiente = contenido
        guard trabajo == nil, let actual = actividad else { return }
        let identificador = UUID()
        identificadorTrabajo = identificador
        trabajo = Task { @MainActor [weak self] in
            guard let self else { return }
            while !Task.isCancelled, self.actividad?.id == actual.id, let nuevo = self.pendiente {
                self.pendiente = nil
                if nuevo == self.enviada { continue }
                await actual.update(ActivityContent(state: nuevo, staleDate: Date().addingTimeInterval(30)))
                guard !Task.isCancelled, self.actividad?.id == actual.id else { break }
                self.enviada = nuevo
            }
            if self.identificadorTrabajo == identificador { self.trabajo = nil }
        }
    }

    func finalizar() {
        estado = "Tarjeta de letra inactiva. Comienza una canción con la app abierta para activarla."
        guard actividad != nil || trabajo != nil || pendiente != nil || enviada != nil else { return }
        trabajo?.cancel(); trabajo = nil
        identificadorTrabajo = UUID()
        pendiente = nil; enviada = nil
        guard let anterior = actividad else { return }
        actividad = nil
        Task { await anterior.end(nil, dismissalPolicy: .immediate) }
    }
}
