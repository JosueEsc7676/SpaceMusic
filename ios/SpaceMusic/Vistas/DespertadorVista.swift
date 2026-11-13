import SwiftUI

struct DespertadorVista: View {
    @State private var alarma = Despertador.compartido
    @State private var biblioteca = Biblioteca.compartida
    @State private var hora = Date().addingTimeInterval(8 * 3600)
    @State private var fuente = "favoritos"
    @State private var minutos = 2
    @State private var aleatorio = false
    @State private var error: String?
    var body: some View {
        Form {
            Section {
                DatePicker("Hora", selection: $hora, displayedComponents: .hourAndMinute)
                Picker("Música", selection: $fuente) {
                    Text("Me gusta").tag("favoritos")
                    ForEach(biblioteca.playlists) { Text($0.nombre).tag(String($0.id)) }
                }
                Stepper("Subir volumen en \(minutos) min", value: $minutos, in: 1...10)
                Toggle("Aleatorio", isOn: $aleatorio)
                Button("Programar") { Task { await programar() } }
            } footer: {
                Text("Si la app permanece activa, la música comienza a la hora elegida. Con la app suspendida o cerrada, iOS muestra una notificación: tócala para iniciar la música.")
            }
            if let p = alarma.plan {
                Section("Programado") {
                    Text(p.fecha, style: .date); Text(p.fecha, style: .time)
                    Button("Cancelar despertador", role: .destructive) { alarma.cancelar() }
                }
            }
            if let error { Text(error).foregroundStyle(Tema.peligro) }
        }
        .navigationTitle("Despertador")
    }
    private func programar() async {
        guard let cuenta = Sesion.compartida.usuario?.id else { return }
        let partes = Calendar.current.dateComponents([.hour, .minute], from: hora)
        let fecha = Calendar.current.nextDate(after: Date(), matching: partes, matchingPolicy: .nextTime) ?? hora
        do {
            try await alarma.programar(.init(fecha: fecha, fuente: fuente, subida: minutos, aleatorio: aleatorio, cuenta: cuenta, servidor: Sesion.compartida.servidor))
            error = nil
        } catch { self.error = error.localizedDescription }
    }
}
