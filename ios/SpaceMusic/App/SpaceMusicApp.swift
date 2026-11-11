import SwiftUI
import UserNotifications

@main
struct SpaceMusicApp: App {
    @UIApplicationDelegateAdaptor(Delegado.self) private var delegado
    @Environment(\.scenePhase) private var fase

    var body: some Scene {
        WindowGroup {
            Raiz()
                .preferredColorScheme(.dark)
                .tint(Tema.celeste)
        }
        .onChange(of: fase) { _, nueva in
            Eventos.compartido.appActiva = nueva == .active
            if nueva == .active {
                Eventos.compartido.conectar()
                Task { await Eventos.compartido.actualizarResumen() }
                Task { await Biblioteca.compartida.renovarSesionSiHaceFalta() }
            } else if nueva == .background, !Reproductor.compartido.sonando {
                // Sin música, iOS suspende la app: se cierra la conexión en vivo
                Eventos.compartido.desconectar()
            }
        }
    }
}

/// Abre la pantalla correcta al tocar una notificación.
final class Delegado: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let ruta = response.notification.request.content.userInfo["ruta"] as? String
        Task { @MainActor in
            if let ruta { Navegacion.compartida.abrir(ruta: ruta) }
            completionHandler()
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

/// Muestra la entrada o la app según haya sesión.
struct Raiz: View {
    @State private var sesion = Sesion.compartida

    var body: some View {
        Group {
            if sesion.conectado {
                Principal()
                    .transition(.opacity)
            } else {
                Entrada()
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: sesion.conectado)
        .background(Tema.fondo.ignoresSafeArea())
    }
}
