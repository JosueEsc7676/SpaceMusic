import BackgroundTasks
import Foundation
import UserNotifications

/// Revisión periódica con la app cerrada (Actualización en segundo plano de iOS):
/// busca mensajes y avisos nuevos y versiones nuevas de la app, y los muestra como notificaciones del iPhone.
///
/// Límite de iOS: el sistema decide cuándo ejecutarla (normalmente cada 15 minutos o más, según el uso
/// y la batería). Avisos al instante con la app cerrada necesitarían notificaciones push de Apple (APNs),
/// que exigen una cuenta de desarrollador de pago y cambios en el servidor.
enum SegundoPlano {
    static let identificador = "com.bpm.spacemusic.ios.revisar"

    /// Se llama una sola vez al arrancar la app (antes de que termine `didFinishLaunching`).
    static func registrar() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identificador, using: nil) { tarea in
            guard let tarea = tarea as? BGAppRefreshTask else { tarea.setTaskCompleted(success: false); return }
            manejar(tarea)
        }
    }

    /// Pide a iOS la próxima revisión (al pasar la app a segundo plano y tras cada revisión).
    static func programar() {
        let pedido = BGAppRefreshTaskRequest(identifier: identificador)
        pedido.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(pedido)
    }

    private static func manejar(_ tarea: BGAppRefreshTask) {
        programar()
        let trabajo = Task { @MainActor in
            if Sesion.compartida.conectado {
                await Eventos.compartido.actualizarResumen()
            }
            await Actualizaciones.compartida.revisar(avisar: true)
            tarea.setTaskCompleted(success: true)
        }
        tarea.expirationHandler = { trabajo.cancel() }
    }
}
