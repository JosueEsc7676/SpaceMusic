import AVFoundation
import SwiftUI

/// El selector público de iOS descubre y cambia las salidas. Solo la ruta activa se muestra aquí;
/// la lista completa de iPhone, Bluetooth y AirPlay la administra el sistema.
struct DispositivosVista: View {
    @Environment(\.dismiss) private var cerrar
    @State private var salidas = AVAudioSession.sharedInstance().currentRoute.outputs
    @State private var sync = EscuchaJuntos.compartida
    @State private var control = ControlRemoto.compartido
    @State private var renombrando = false
    @State private var nombreNuevo = ""
    var body: some View {
        NavigationStack {
            List {
                Section("Escuchando en") {
                    ForEach(salidas, id: \.uid) { salida in
                        Label(salida.portName, systemImage: icono(salida.portType))
                            .font(.headline).foregroundStyle(Tema.celeste)
                    }
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Cambiar dispositivo").font(.body.weight(.semibold))
                            Text("iPhone, audífonos, Bluetooth y AirPlay").font(.caption).foregroundStyle(Tema.secundario)
                        }
                        Spacer()
                        SelectorSalida().frame(width: 52, height: 52)
                            .accessibilityLabel("Mostrar dispositivos de audio disponibles")
                    }
                }
                Section {
                    if control.otros.isEmpty {
                        Text("Abre SpaceMusic con tu cuenta en otro teléfono, tablet o computadora y podrás controlarlo desde aquí.")
                            .font(.caption).foregroundStyle(Tema.secundario)
                    }
                    ForEach(control.otros) { d in
                        NavigationLink { ControlRemotoVista(dispositivoId: d.id) } label: { FilaDispositivoRemoto(d: d) }
                    }
                } header: {
                    HStack {
                        Text("Tus otros dispositivos")
                        Spacer()
                        Button("Este: \(control.nombre)") { nombreNuevo = control.nombre; renombrando = true }
                            .font(.caption).textCase(nil)
                            .accessibilityLabel("Cambiar el nombre de este dispositivo")
                    }
                }
                Section {
                    NavigationLink { JuntosVista() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "person.2.wave.2.fill").foregroundStyle(Tema.celeste)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("SyncPlay").font(.headline)
                                Text(sync.sala == nil ? "Crea una sala o únete con tus amigos" : "Tu sala está activa").font(.caption).foregroundStyle(Tema.secundario)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                } header: { Text("Escuchar en compañía") }
            }
            .navigationTitle("Dispositivos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { cerrar() } } }
            .alert("Nombre de este dispositivo", isPresented: $renombrando) {
                TextField("App iPhone", text: $nombreNuevo)
                Button("Guardar") { control.nombre = nombreNuevo }
                Button("Cancelar", role: .cancel) { }
            } message: { Text("Así lo verás en tus otros dispositivos.") }
            .task { await control.cargar() }
            .task {
                for await _ in NotificationCenter.default.notifications(named: AVAudioSession.routeChangeNotification) {
                    guard !Task.isCancelled else { return }
                    salidas = AVAudioSession.sharedInstance().currentRoute.outputs
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
    private func icono(_ tipo: AVAudioSession.Port) -> String {
        switch tipo {
        case .headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE: return "headphones"
        case .builtInSpeaker, .builtInReceiver: return "iphone"
        default: return "hifispeaker.fill"
        }
    }
}
