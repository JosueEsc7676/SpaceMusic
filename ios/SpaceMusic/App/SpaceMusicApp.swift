import SwiftUI
import UserNotifications

@main
struct SpaceMusicApp: App {
    @UIApplicationDelegateAdaptor(Delegado.self) private var delegado
    @Environment(\.scenePhase) private var fase
    @AppStorage("acento") private var acento = Tema.acentoPorDefecto
    /// Animación del logo al abrir la app (solo al arrancar, no al volver de segundo plano).
    @State private var bienvenida = true

    var body: some Scene {
        WindowGroup {
            ZStack {
                Raiz()
                    .tint(Tema.celeste)
                    // Al cambiar el color de la app se redibuja todo con el nuevo acento.
                    .id(acento)
                if bienvenida {
                    Bienvenida { bienvenida = false }
                        .zIndex(1)
                }
            }
            .preferredColorScheme(.dark)
        }
        .onChange(of: fase) { _, nueva in
            if nueva != .active { Reproductor.compartido.guardarEstado() }
            Eventos.compartido.appActiva = nueva == .active
            if nueva == .background { SegundoPlano.programar() }
            if nueva == .active {
                Despertador.compartido.vigilar()
                Task { await Actualizaciones.compartida.revisar(avisar: false) }
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
        SegundoPlano.registrar()
        // Barra de pestañas siempre con fondo: en pantallas como los álbumes iOS la dejaba transparente
        // (apariencia «al borde del desplazamiento») y los íconos quedaban encima de la lista.
        if !Cristal.activo {
            let barra = UITabBarAppearance()
            barra.configureWithDefaultBackground()
            barra.backgroundEffect = UIBlurEffect(style: .systemChromeMaterialDark)
            barra.backgroundColor = UIColor.black.withAlphaComponent(0.55)
            UITabBar.appearance().standardAppearance = barra
            UITabBar.appearance().scrollEdgeAppearance = barra
        }
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
