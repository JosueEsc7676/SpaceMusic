import AVFoundation
import SwiftUI

/// El selector público de iOS descubre y cambia las salidas. Solo la ruta activa se muestra aquí;
/// la lista completa de iPhone, Bluetooth y AirPlay la administra el sistema.
struct DispositivosVista: View {
    @Environment(\.dismiss) private var cerrar
    @State private var salidas = AVAudioSession.sharedInstance().currentRoute.outputs
    @State private var sync = EscuchaJuntos.compartida
    @State private var control = ControlRemoto.compartido
    @State private var r = Reproductor.compartido
    private var enEste: Bool { r.remoto == nil }
    @State private var renombrando = false
    @State private var nombreNuevo = ""
    var body: some View {
        NavigationStack {
            List {
                // Como Spotify Connect: se elige dónde suena y se sigue usando el mismo reproductor
                Section {
                    Button { Task { await control.elegirEsteIPhone(); cerrar() } } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "iphone")
                                .font(.title3).frame(width: 40, height: 40)
                                .foregroundStyle(enEste ? Color.black : Color.white)
                                .background(RoundedRectangle(cornerRadius: 12).fill(enEste ? Color.white : Color.white.opacity(0.1)))
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Este iPhone").font(.headline)
                                Text(salidas.first?.portName ?? control.nombre).font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                            }
                            Spacer(minLength: 0)
                            if enEste { Image(systemName: "checkmark").font(.body.weight(.semibold)) }
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    ForEach(control.otros) { d in
                        Button { Task { await control.elegir(d); cerrar() } } label: {
                            FilaDispositivoRemoto(d: d, elegido: r.remoto?.id == d.id)
                        }
                        .buttonStyle(.plain)
                    }
                    if control.otros.isEmpty {
                        Text("Abre SpaceMusic con tu cuenta en otro teléfono, tablet o computadora y aparecerá aquí.")
                            .font(.caption).foregroundStyle(Tema.secundario)
                    }
                } header: {
                    HStack {
                        Text("Reproducir en")
                        Spacer()
                        Button("Este: \(control.nombre)") { nombreNuevo = control.nombre; renombrando = true }
                            .font(.caption).textCase(nil)
                            .accessibilityLabel("Cambiar el nombre de este dispositivo")
                    }
                } footer: {
                    Text("Al elegir otro dispositivo la música suena allá y la controlas con el mismo reproductor.")
                }
                if enEste {
                    Section("Salida de audio de este iPhone") {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Audífonos, Bluetooth y AirPlay").font(.body.weight(.semibold))
                                Text(salidas.map(\.portName).joined(separator: ", ")).font(.caption).foregroundStyle(Tema.secundario)
                            }
                            Spacer()
                            SelectorSalida().frame(width: 52, height: 52)
                                .accessibilityLabel("Mostrar dispositivos de audio disponibles")
                        }
                    }
                }
                Section {
                    NavigationLink { JuntosVista() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "person.2.wave.2.fill").foregroundStyle(Tema.celeste)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("SyncPlay").font(.headline)
                                Text(sync.sala == nil ? "Inicia SyncPlay o únete con tus amigos" : "Tu SyncPlay está activo").font(.caption).foregroundStyle(Tema.secundario)
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
