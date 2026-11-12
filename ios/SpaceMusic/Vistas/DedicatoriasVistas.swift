import SwiftUI

// Dedicatorias (dedicatorias.js de la web 3.4): «una canción para este momento».
// Dedicar en tres pasos (para quién, qué parte de la letra, mensaje) con una tarjeta para compartir.

struct DedicatoriasVista: View {
    @State private var lista: ListaDedicatorias?
    @State private var error: String?
    @State private var bloqueando = false
    @State private var usuario = ""

    var body: some View {
        List {
            Section {
                CabeceraExperiencia(icono: "heart.text.square.fill", titulo: "Dedicatorias",
                                    texto: "Canciones para un momento. Dedica desde el menú de cualquier canción (… → Dedicar esta canción).")
                    .listRowBackground(Color.clear)
            }
            if let error { Section { Text(error).foregroundStyle(Tema.peligro) } }
            if let l = lista {
                let guardadas = l.recibidas.filter { $0.guardada }.compactMap(\.cancion)
                if !guardadas.isEmpty {
                    Section {
                        Button { Reproductor.compartido.reproducir(guardadas, contexto: "dedicadas") } label: { Label("Escuchar todas", systemImage: "play.fill") }
                        ForEach(Array(guardadas.enumerated()), id: \.offset) { i, c in
                            FilaCancion(cancion: c) { Reproductor.compartido.reproducir(guardadas, desde: i, contexto: "dedicadas") }
                        }
                    } header: { Text("Canciones que me dedicaron") }
                }
                Section("Recibidas") {
                    if l.recibidas.isEmpty { Text("Aún no te han dedicado canciones.").foregroundStyle(Tema.secundario) }
                    ForEach(l.recibidas) { FilaDedicatoria(d: $0) }
                }
                Section("Enviadas") {
                    if l.enviadas.isEmpty { Text("Todavía no dedicas ninguna.").foregroundStyle(Tema.secundario) }
                    ForEach(l.enviadas) { FilaDedicatoria(d: $0) }
                }
                Section {
                    ForEach(l.bloqueadas, id: \.self) { u in
                        HStack { Text("@\(u)"); Spacer()
                            Button("Volver a recibir") { Task { await bloquear(u, false) } }.font(.subheadline)
                        }
                    }
                    Button("Dejar de recibir de alguien") { bloqueando = true }
                } header: { Text("Privacidad") } footer: {
                    Text("Solo pueden dedicarte canciones las personas que tú sigues. Puedes dejar de recibir de alguien cuando quieras.")
                }
            } else if error == nil { ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear) }
        }
        .navigationTitle("Dedicatorias")
        .navigationBarTitleDisplayMode(.inline)
        .task { await cargar() }
        .refreshable { await cargar() }
        .alert("Dejar de recibir dedicatorias", isPresented: $bloqueando) {
            TextField("@usuario", text: $usuario).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Bloquear", role: .destructive) { Task { await bloquear(usuario, true); usuario = "" } }
            Button("Cancelar", role: .cancel) {}
        }
    }

    private func cargar() async {
        do { lista = try await API.get("/api/dedicatorias"); error = nil }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func bloquear(_ u: String, _ b: Bool) async {
        let limpio = u.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "@", with: "")
        guard !limpio.isEmpty else { return }
        do { try await API.hacer("POST", "/api/dedicatorias/bloqueos", ["username": limpio, "bloquear": b]); await cargar() }
        catch { Avisos.compartido.error(error) }
    }
}

struct FilaDedicatoria: View {
    let d: Dedicatoria
    var body: some View {
        NavigationLink(value: Destino.dedicatoria(d.id)) {
            HStack(spacing: 12) {
                Portada(ruta: d.cancion?.portadaUrl, tamano: 52, radio: 8)
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.cancion?.nombre ?? "Canción eliminada").font(.body.weight(.semibold)).lineLimit(1)
                    Text("\(d.recibida ? "De" : "Para") \(d.persona?.nombre ?? "") · \(Formato.hace(d.fecha))")
                        .font(.caption).foregroundStyle(Tema.secundario).lineLimit(1)
                    if let primera = d.lineas.first {
                        Text("“\(primera)”").font(.caption.italic()).lineLimit(1)
                    } else if d.completa {
                        Text("La canción completa").font(.caption.italic())
                    } else if let m = d.mensaje, !m.isEmpty {
                        Text("“\(m)”").font(.caption.italic()).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                if d.recibida && !d.vista {
                    Text("Nueva").font(.caption2.weight(.bold)).foregroundStyle(.black)
                        .padding(.horizontal, 7).padding(.vertical, 3).background(Capsule().fill(Tema.celeste))
                } else if d.respuesta != nil {
                    Text(d.recibida ? "Respondiste" : "Respondió").font(.caption2.weight(.bold)).foregroundStyle(Tema.secundario)
                }
            }
        }
    }
}

struct DedicatoriaVista: View {
    let id: Int
    @State private var d: Dedicatoria?
    @State private var error: String?
    @State private var respuesta = ""
    @State private var tarjeta: UIImage?
    @State private var sesion = Sesion.compartida

    var body: some View {
        ScrollView {
            if let error { EstadoVista(icono: "lock", titulo: "No puedes ver esta dedicatoria", texto: error) }
            if let d, let c = d.cancion {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 12) {
                        Avatar(ruta: d.persona?.fotoPerfil, nombre: d.persona?.nombre ?? "?", tamano: 52)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Formato.hace(d.fecha)).font(.caption).foregroundStyle(Tema.secundario)
                            Text(d.recibida ? "\(d.persona?.nombre ?? "") te dedicó" : "Dedicaste a \(d.persona?.nombre ?? "")")
                                .font(.system(.title2, design: .rounded).weight(.heavy))
                        }
                    }
                    HStack(spacing: 14) {
                        Portada(ruta: c.portadaUrl, tamano: 110, radio: 12)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(c.nombre).font(.title3.weight(.bold)).lineLimit(2)
                            Text(c.artista).foregroundStyle(Tema.secundario)
                            if d.completa {
                                Text(d.recibida ? "Te dedicaron esta canción completa" : "La canción completa").font(.caption.weight(.bold)).foregroundStyle(Tema.celeste)
                            } else if !d.lineas.isEmpty, let fin = d.segundoFin {
                                Text("\(Formato.duracion(d.segundo)) – \(Formato.duracion(fin))").font(.caption).foregroundStyle(Tema.secundario)
                            } else if d.segundo > 0 {
                                Text("Desde el \(Formato.duracion(d.segundo))").font(.caption).foregroundStyle(Tema.secundario)
                            }
                        }
                    }
                    if !d.lineas.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            ForEach(Array(d.lineas.enumerated()), id: \.offset) { _, l in Text(l).font(.title3.weight(.bold)) }
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tema.tarjeta))
                    }
                    if let m = d.mensaje, !m.isEmpty {
                        Text(m).font(.body.italic()).padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 14).fill(Tema.lila.opacity(0.12)))
                    }
                    HStack(spacing: 10) {
                        Button { escuchar(d, c) } label: {
                            Label(!d.lineas.isEmpty ? "Escuchar el fragmento" : d.completa ? "Escuchar completa" : d.segundo > 0 ? "Escuchar desde ese momento" : "Escuchar",
                                  systemImage: "play.fill").lineLimit(1)
                        }
                        .buttonStyle(BotonPrincipal())
                        if d.recibida {
                            Button { Task { await guardar(d) } } label: {
                                Image(systemName: d.guardada ? "heart.fill" : "heart").font(.title3).foregroundStyle(d.guardada ? Tema.rosa : Tema.texto)
                                    .frame(width: 44, height: 44)
                            }
                            .accessibilityLabel(d.guardada ? "Guardada" : "Guardar")
                        }
                        if let tarjeta {
                            ShareLink(item: Image(uiImage: tarjeta), preview: SharePreview("Dedicatoria", image: Image(uiImage: tarjeta))) {
                                Image(systemName: "square.and.arrow.up").font(.title3).frame(width: 44, height: 44)
                            }
                            .accessibilityLabel("Compartir tarjeta")
                        }
                    }
                    if let tarjeta {
                        Image(uiImage: tarjeta).resizable().scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    if let r = d.respuesta {
                        Text("\(d.recibida ? "Tu respuesta" : "Respondió"): \(r)").padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 14).fill(Tema.tarjeta))
                    } else if d.recibida {
                        VStack(alignment: .trailing, spacing: 8) {
                            TextField("Responder a \(d.persona?.nombre ?? "")…", text: $respuesta, axis: .vertical)
                                .lineLimit(2...5).padding(12)
                                .background(RoundedRectangle(cornerRadius: 12).fill(Tema.tarjeta))
                            Button("Responder") { Task { await responder(d) } }
                                .buttonStyle(BotonSecundario()).disabled(respuesta.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                    } else {
                        Text("Todavía no responde.").foregroundStyle(Tema.secundario)
                    }
                }
                .padding()
            } else if d != nil {
                EstadoVista(icono: "music.note", titulo: "La canción ya no está en el servidor")
            } else if error == nil { Cargando() }
        }
        .navigationTitle("Dedicatoria")
        .navigationBarTitleDisplayMode(.inline)
        .task { await cargar() }
    }

    private func cargar() async {
        do {
            let nueva: Dedicatoria = try await API.get("/api/dedicatorias/\(id)")
            d = nueva; error = nil
            if let c = nueva.cancion {
                let yo = sesion.usuario?.username
                tarjeta = await TarjetaDedicatoria.imagen(cancion: c, lineas: nueva.lineas, completa: nueva.completa,
                                                          de: nueva.recibida ? nueva.persona?.username : yo,
                                                          para: nueva.recibida ? yo : nueva.persona?.username, mensaje: nueva.mensaje)
            }
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func escuchar(_ d: Dedicatoria, _ c: Cancion) {
        Reproductor.compartido.reproducir([c], contexto: "dedicatoria")
        let desde = d.completa ? 0 : d.segundo
        if desde > 0 { Reproductor.compartido.buscar(Double(desde)) }
    }
    private func guardar(_ d: Dedicatoria) async {
        do { try await API.hacer("POST", "/api/dedicatorias/\(d.id)/guardar", ["guardar": !d.guardada]); await cargar() }
        catch { Avisos.compartido.error(error) }
    }
    private func responder(_ d: Dedicatoria) async {
        do {
            try await API.hacer("POST", "/api/dedicatorias/\(d.id)/respuesta", ["texto": respuesta])
            respuesta = ""
            Avisos.compartido.mostrar("Respuesta enviada")
            await cargar()
        } catch { Avisos.compartido.error(error) }
    }
}

/// Dedicar en tres pasos, como la web: para quién, qué parte y mensaje (con la tarjeta de vista previa).
struct DedicarHoja: View {
    let cancion: Cancion
    @Environment(\.dismiss) private var cerrar
    @State private var paso = 1
    @State private var personas: [PersonaBreve] = []
    @State private var bloques: [[BloquesLetra.Linea]] = []
    @State private var buscar = ""
    @State private var para: String?
    @State private var completa = false
    @State private var desde: Int?
    @State private var hasta: Int?
    @State private var mensaje = ""
    @State private var previa: UIImage?
    @State private var cargando = true
    @State private var enviando = false

    private var elegidas: [BloquesLetra.Linea] {
        guard let desde, !completa else { return [] }
        return bloques[desde...(hasta ?? desde)].flatMap { $0 }
    }
    private var personasVisibles: [PersonaBreve] {
        let f = buscar.trimmingCharacters(in: .whitespaces).lowercased().replacingOccurrences(of: "@", with: "")
        return personas.filter { f.isEmpty || ($0.username + " " + $0.nombre).lowercased().contains(f) }
    }
    private var puedeSeguir: Bool { paso == 1 ? para != nil : paso == 2 ? (completa || desde != nil) : true }

    var body: some View {
        NavigationStack {
            Group {
                if cargando { Cargando() } else {
                    switch paso {
                    case 1: pasoPersona
                    case 2: pasoParte
                    default: pasoMensaje
                    }
                }
            }
            .safeAreaInset(edge: .top) {
                HStack(spacing: 12) {
                    Portada(ruta: cancion.portadaUrl, tamano: 44, radio: 6)
                    VStack(alignment: .leading) {
                        Text(cancion.nombre).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text(["Para quién", "Qué parte", "Mensaje"][paso - 1] + " · paso \(paso) de 3").font(.caption).foregroundStyle(Tema.secundario)
                    }
                    Spacer()
                }
                .padding(.horizontal).padding(.vertical, 8)
                .background(.bar)
            }
            .navigationTitle("Dedicar esta canción")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(paso == 1 ? "Cancelar" : "Atrás") { if paso == 1 { cerrar() } else { paso -= 1 } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(paso == 3 ? "Enviar" : "Siguiente") {
                        if paso < 3 { paso += 1; if paso == 3 { Task { await generarPrevia() } } } else { Task { await enviar() } }
                    }
                    .disabled(!puedeSeguir || enviando)
                }
            }
            .task { await preparar() }
        }
    }

    private var pasoPersona: some View {
        List {
            ForEach(personasVisibles, id: \.username) { p in
                Button { para = p.username } label: {
                    HStack(spacing: 12) {
                        Avatar(ruta: p.fotoPerfil, nombre: p.nombre, tamano: 40)
                        VStack(alignment: .leading) {
                            Text(p.nombre).lineLimit(1)
                            Text("@\(p.username)").font(.caption).foregroundStyle(Tema.secundario)
                        }
                        Spacer()
                        if p.mutuo { Text("Se siguen").font(.caption2.weight(.bold)).foregroundStyle(Tema.celeste) }
                        if para == p.username { Image(systemName: "checkmark").foregroundStyle(Tema.celeste) }
                    }
                }
                .buttonStyle(.plain)
            }
            if personasVisibles.isEmpty {
                Text(personas.isEmpty ? "Todavía nadie te sigue. Solo puedes dedicar canciones a personas que te siguen." : "Nadie coincide con la búsqueda.")
                    .foregroundStyle(Tema.secundario)
            }
        }
        .searchable(text: $buscar, prompt: "Buscar por nombre o usuario")
    }

    private var pasoParte: some View {
        List {
            Button { completa.toggle(); if completa { desde = nil; hasta = nil } } label: {
                HStack {
                    VStack(alignment: .leading) {
                        Text("Toda la canción").font(.body.weight(.semibold))
                        Text("Verá «Te dedicaron esta canción completa»").font(.caption).foregroundStyle(Tema.secundario)
                    }
                    Spacer()
                    if completa { Image(systemName: "checkmark.circle.fill").foregroundStyle(Tema.celeste) }
                }
            }
            .buttonStyle(.plain)
            if bloques.isEmpty {
                Text("Esta canción no tiene letra: se dedicará completa.").foregroundStyle(Tema.secundario)
            } else {
                Section {
                    ForEach(Array(bloques.enumerated()), id: \.offset) { i, b in
                        let sel = !completa && desde != nil && i >= desde! && i <= (hasta ?? desde!)
                        Button { tocarBloque(i) } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                if let t = b.first?.t { Text(Formato.duracion(Int(t))).font(.caption2).foregroundStyle(Tema.secundario) }
                                ForEach(Array(b.enumerated()), id: \.offset) { _, l in Text(l.texto).font(.subheadline) }
                            }
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 12).fill(sel ? Tema.celeste.opacity(0.22) : Tema.tarjeta))
                        }
                        .buttonStyle(.plain)
                        .listRowSeparator(.hidden)
                        .accessibilityAddTraits(sel ? .isSelected : [])
                    }
                } header: { Text("O toca un bloque de la letra; toca otro para elegir hasta dónde llega (máximo 4).") }
            }
        }
    }

    private var pasoMensaje: some View {
        Form {
            Section("Mensaje (opcional)") {
                TextField("Por qué esta canción, por qué este momento", text: $mensaje, axis: .vertical)
                    .lineLimit(2...6)
                    .onChange(of: mensaje) { _, _ in Task { await generarPrevia() } }
            }
            Section {
                if let previa { Image(uiImage: previa).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 12)) }
                else { ProgressView() }
            } header: { Text("Tarjeta") } footer: { Text("Se envía solo cuando pulsas Enviar.") }
        }
    }

    private func tocarBloque(_ i: Int) {
        completa = false
        if desde == nil || hasta != nil || i < desde! { desde = i; hasta = nil }
        else if i == desde { desde = nil }
        else if i - desde! > 3 { Avisos.compartido.mostrar("Elige hasta 4 bloques seguidos") }
        else { hasta = i }
    }

    private func preparar() async {
        personas = (try? await API.get("/api/dedicatorias/personas")) ?? []
        let letra: LetraCancion? = try? await API.get("/api/canciones/\(cancion.id)/letra")
        bloques = BloquesLetra.dividir(letra: letra?.letra ?? "", lrc: letra?.letraSincronizada ?? "")
        if bloques.isEmpty { completa = true }
        cargando = false
    }

    private func generarPrevia() async {
        previa = await TarjetaDedicatoria.imagen(cancion: cancion, lineas: elegidas.map(\.texto), completa: completa,
                                                 de: Sesion.compartida.usuario?.username, para: para,
                                                 mensaje: mensaje.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private func enviar() async {
        guard let para else { return }
        enviando = true
        defer { enviando = false }
        let r = Reproductor.compartido
        let enCurso = r.actual?.id == cancion.id ? Int(r.tiempo) : 0
        let ls = elegidas
        var cuerpo: [String: Any] = [
            "username": para, "cancionId": cancion.id, "mensaje": mensaje, "completa": completa,
            "lineas": completa ? [String]() : ls.map(\.texto),
            "segundo": completa ? 0 : (ls.first?.t.map { Int($0) } ?? enCurso),
        ]
        if !completa, let fin = ls.last?.fin { cuerpo["segundoFin"] = Int(fin.rounded(.up)) }
        do {
            try await API.hacer("POST", "/api/dedicatorias", cuerpo)
            Avisos.compartido.mostrar("Dedicatoria enviada a @\(para)")
            cerrar()
        } catch { Avisos.compartido.error(error) }
    }
}

/// Tarjeta 1080×1350 con la portada, la canción, el artista y la letra dedicada (como `tarjetaDedicatoria` de la web).
enum TarjetaDedicatoria {
    @MainActor
    static func imagen(cancion: Cancion, lineas: [String], completa: Bool, de: String?, para: String?, mensaje: String?) async -> UIImage? {
        var portada: UIImage?
        if let ruta = cancion.portadaUrl, !ruta.isEmpty {
            portada = await CacheImagenes.compartida.imagen(CacheImagenes.rutaReducida(ruta, pixeles: 640))
        }
        let vista = Lienzo(cancion: cancion, lineas: lineas, completa: completa, de: de, para: para, mensaje: mensaje, portada: portada)
        let r = ImageRenderer(content: vista)
        r.scale = 3
        return r.uiImage
    }

    private struct Lienzo: View {
        let cancion: Cancion; let lineas: [String]; let completa: Bool; let de: String?; let para: String?; let mensaje: String?; let portada: UIImage?
        var body: some View {
            ZStack(alignment: .topLeading) {
                Color(red: 0.043, green: 0.043, blue: 0.055)
                if let portada {
                    Image(uiImage: portada).resizable().scaledToFill().frame(width: 360, height: 450).blur(radius: 24).opacity(0.85).clipped()
                }
                LinearGradient(colors: [.black.opacity(0.25), .black.opacity(0.55), .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top, spacing: 14) {
                        Group {
                            if let portada { Image(uiImage: portada).resizable().scaledToFill() } else { Color.white.opacity(0.1) }
                        }
                        .frame(width: 100, height: 100).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(para.map { "Para @\($0)" } ?? "Una canción para ti").font(.system(size: 11, weight: .semibold)).foregroundStyle(.white.opacity(0.7))
                            Text(cancion.nombre).font(.system(size: 19, weight: .heavy)).foregroundStyle(.white).lineLimit(2)
                            Text(cancion.artista).font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                        }
                    }
                    .padding(.top, 36)
                    Spacer(minLength: 24)
                    if completa || lineas.isEmpty {
                        Text(completa ? "Te dedico esta canción completa" : "Te dedico esta canción")
                            .font(.system(size: 22, weight: .heavy)).foregroundStyle(.white)
                    } else {
                        Text("“").font(.system(size: 48, weight: .heavy)).foregroundStyle(.white.opacity(0.35)).frame(height: 30, alignment: .top)
                        Text(lineas.prefix(11).joined(separator: "\n"))
                            .font(.system(size: lineas.joined(separator: " ").count > 220 ? 15 : lineas.joined(separator: " ").count > 120 ? 17 : 20, weight: .bold))
                            .foregroundStyle(.white).lineSpacing(3)
                    }
                    if let mensaje, !mensaje.isEmpty {
                        Text(mensaje).font(.system(size: 12, weight: .medium).italic()).foregroundStyle(.white.opacity(0.75)).lineLimit(2).padding(.top, 14)
                    }
                    Spacer(minLength: 16)
                    Text("\(de.map { "De @\($0) · " } ?? "")SpaceMusic").font(.system(size: 11, weight: .semibold)).foregroundStyle(.white.opacity(0.6))
                        .padding(.bottom, 24)
                }
                .padding(.horizontal, 27)
            }
            .frame(width: 360, height: 450)
            .environment(\.colorScheme, .dark)
        }
    }
}
