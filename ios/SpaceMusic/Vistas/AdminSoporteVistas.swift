import Charts
import SwiftUI

// Administración → Soporte técnico (soporte.js de la web): resumen, monitor, diagnóstico, incidencias, biblioteca,
// base de datos, usuarios y sesiones, alertas, seguridad y auditoría, mantenimiento e informes.

enum SeccionSoporte: String, CaseIterable, Identifiable {
    case resumen, monitor, diagnostico, incidencias, biblioteca, basedatos, sesiones, alertas, seguridad, mantenimiento, informes
    var id: String { rawValue }
    var titulo: String {
        switch self {
        case .resumen: "Resumen"; case .monitor: "Monitor"; case .diagnostico: "Diagnóstico"; case .incidencias: "Incidencias"
        case .biblioteca: "Biblioteca"; case .basedatos: "Base de datos"; case .sesiones: "Usuarios y sesiones"; case .alertas: "Alertas"
        case .seguridad: "Seguridad y auditoría"; case .mantenimiento: "Mantenimiento"; case .informes: "Informes"
        }
    }
    var icono: String {
        switch self {
        case .resumen: "chart.bar"; case .monitor: "waveform.path.ecg"; case .diagnostico: "checkmark.seal"; case .incidencias: "exclamationmark.bubble"
        case .biblioteca: "opticaldisc"; case .basedatos: "cylinder.split.1x2"; case .sesiones: "person.2"; case .alertas: "bell"
        case .seguridad: "lock.shield"; case .mantenimiento: "wrench.and.screwdriver"; case .informes: "doc.text.magnifyingglass"
        }
    }
}

/// Lista de secciones del soporte técnico.
struct SoporteTecnicoVista: View {
    var body: some View {
        List(SeccionSoporte.allCases) { s in
            NavigationLink { SeccionSoporteVista(seccion: s) } label: { Label(s.titulo, systemImage: s.icono) }
        }
        .navigationTitle("Soporte técnico")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Estado con color, con los mismos nombres de la web (operativo, degradado, fallida…).
func colorEstadoSoporte(_ e: String) -> Color {
    switch e.lowercased() {
    case "operativo", "ok", "correcta", "resuelta", "resuelto", "terminado": return .green
    case "degradado", "advertencia", "warn", "en_investigacion", "reconocida", "en_curso", "nueva": return .orange
    case "caido", "fallida", "error", "no_disponible", "activa", "denegada", "bad": return Tema.peligro
    default: return Tema.secundario
    }
}
private func nombreEstado(_ e: String) -> String {
    let d = ["NUEVA": "Nueva", "EN_INVESTIGACION": "En investigación", "RESUELTA": "Resuelta", "DESCARTADA": "Descartada",
             "ACTIVA": "Activa", "RECONOCIDA": "Reconocida", "OK": "Correcto", "ERROR": "Error", "DENEGADA": "Denegada",
             "EN_CURSO": "En curso", "operativo": "Operativo", "degradado": "Degradado", "caido": "Caído", "no_comprobado": "Sin comprobar",
             "correcta": "Correcta", "advertencia": "Advertencia", "fallida": "Fallida", "no_disponible": "No disponible"]
    return d[e] ?? e.replacingOccurrences(of: "_", with: " ").capitalized
}

struct SeccionSoporteVista: View {
    let seccion: SeccionSoporte
    @State private var datos: ValorAdmin?
    @State private var extra: ValorAdmin?
    @State private var error: String?
    @State private var filtro = ""
    @State private var busqueda = ""
    @State private var pagina = 0
    @State private var horas = 6
    @State private var dias = 7
    @State private var detalle: ValorAdmin?
    @State private var nota = ""
    @State private var ejecutando: Int?
    @State private var archivo: URL?
    @State private var trabajando = false

    var body: some View {
        List {
            if let error { Section { Text(error).foregroundStyle(Tema.peligro); Button("Reintentar") { Task { await cargar() } } } }
            if let d = datos { contenido(d) } else if error == nil { ProgressView().frame(maxWidth: .infinity) }
        }
        .navigationTitle(seccion.titulo)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await cargar() }
        .task(id: "\(filtro)|\(busqueda)|\(pagina)|\(horas)|\(dias)") { await cargar() }
        .task {
            // El resumen se actualiza solo cada 20 s; el monitor cada minuto (como la web).
            guard seccion == .resumen || seccion == .monitor else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(seccion == .resumen ? 20 : 60))
                await cargar()
            }
        }
        .sheet(isPresented: Binding(get: { detalle != nil }, set: { if !$0 { detalle = nil } })) {
            if let d = detalle { DetalleIncidenciaHoja(inc: d) { await cargar() } }
        }
        .sheet(isPresented: Binding(get: { archivo != nil }, set: { if !$0 { archivo = nil } })) {
            if let archivo {
                VStack(spacing: 16) {
                    Image(systemName: "doc.fill").font(.largeTitle).foregroundStyle(Tema.celeste)
                    Text(archivo.lastPathComponent).font(.headline)
                    ShareLink(item: archivo) { Label("Guardar o compartir", systemImage: "square.and.arrow.up") }.buttonStyle(BotonPrincipal())
                }
                .padding()
                .presentationDetents([.height(240)])
            }
        }
    }

    // MARK: Contenido por sección

    @ViewBuilder
    private func contenido(_ r: ValorAdmin) -> some View {
        switch seccion {
        case .resumen: resumen(r)
        case .monitor: monitor(r)
        case .diagnostico: diagnostico(r)
        case .incidencias: incidencias(r)
        case .biblioteca: bibliotecaRevision(r)
        case .basedatos: baseDatos(r)
        case .sesiones: sesiones(r)
        case .alertas: alertas(r)
        case .seguridad: auditoria(r)
        case .mantenimiento: mantenimiento(r)
        case .informes: informes(r)
        }
    }

    private func fila(_ k: String, _ v: String) -> some View { LabeledContent(k, value: v.isEmpty ? "—" : v) }
    private func chip(_ e: String) -> some View { Chip(texto: nombreEstado(e), color: colorEstadoSoporte(e)) }

    @ViewBuilder
    private func resumen(_ r: ValorAdmin) -> some View {
        Section {
            HStack { chip(r["estado"].cadena); Text("Estado general").font(.headline); Spacer(); Text(r["actualizado"].fechaCorta).font(.caption).foregroundStyle(Tema.secundario) }
            ForEach([("aplicacion", "Aplicación"), ("api", "API"), ("baseDatos", "Base de datos"), ("almacenamiento", "Almacenamiento de canciones"), ("tareas", "Tareas en segundo plano")], id: \.0) { k in
                let c = r["componentes"][k.0]
                VStack(alignment: .leading, spacing: 2) {
                    HStack { chip(c["estado"].cadena); Text(k.1).font(.subheadline.weight(.semibold)) }
                    if !c["detalle"].cadena.isEmpty { Text(c["detalle"].cadena).font(.caption).foregroundStyle(Tema.secundario) }
                }
            }
        }
        let m = r["ultimoMinuto"]
        Section("Métricas") {
            fila("Tiempo activo", Formato.duracionLarga(r["activoSegundos"].entero))
            fila("Peticiones", "\(r["totalPeticiones"].texto)" + (m.esNulo ? "" : " · \(m["solicitudes"].texto) el último minuto"))
            fila("Errores HTTP", "\(r["total5xx"].texto) 5xx · \(r["total4xx"].texto) 4xx")
            if !m.esNulo { fila("Latencia", "\(m["latenciaMediaMs"].texto) ms · p95 \(m["latenciaP95Ms"].texto) ms") }
            fila("Memoria (Java)", "\(r["memoriaUsadaMb"].texto) de \(r["memoriaMaxMb"].texto) MB")
            fila("CPU", "\(r["cpuSistema"].texto) % · SpaceMusic \(r["cpuProceso"].texto) % · \(r["procesadores"].texto) núcleos")
            fila("Disco", "\(r["discoLibreGb"].texto) GB libres de \(r["discoTotalGb"].texto) GB")
            fila("En vivo", "\(r["conexionesEnVivo"].texto) conexiones · \(r["sesionesActivas"].texto) cuentas activas")
        }
        Section("Incidencias y alertas") {
            HStack { ForEach(["NUEVA", "EN_INVESTIGACION", "RESUELTA", "DESCARTADA"], id: \.self) { e in VStack { chip(e); Text(r["incidencias"][e].texto == "—" ? "0" : r["incidencias"][e].texto).font(.headline) }.frame(maxWidth: .infinity) } }
            HStack { ForEach(["ACTIVA", "RECONOCIDA", "RESUELTA"], id: \.self) { e in VStack { chip(e); Text(r["alertas"][e].texto == "—" ? "0" : r["alertas"][e].texto).font(.headline) }.frame(maxWidth: .infinity) } }
        }
        let u = r["ultimoDiagnostico"]
        Section("Último diagnóstico") {
            if u.esNulo { Text("Aún no se ejecutó ninguno.").foregroundStyle(Tema.secundario) } else {
                HStack { chip(u["estado"].cadena); Text("\(u["inicio"].fechaCorta) · @\(u["usuario"].cadena)").font(.caption) }
                Text("\(u["correctas"].texto) correctas · \(u["advertencias"].texto) advertencias · \(u["fallidas"].texto) fallidas").font(.subheadline)
            }
        }
        Section("Entorno") {
            fila("Java", r["version"]["java"].cadena)
            fila("Spring Boot", r["version"]["springBoot"].cadena)
            fila("Sistema", r["version"]["sistema"].cadena)
            fila("Conexiones a la base", r["pool"].esNulo ? "" : "\(r["pool"]["activas"].texto) activas · \(r["pool"]["libres"].texto) libres")
        }
    }

    @ViewBuilder
    private func monitor(_ h: ValorAdmin) -> some View {
        Section {
            Picker("Período", selection: $horas) {
                Text("1 h").tag(1); Text("6 h").tag(6); Text("24 h").tag(24); Text("7 días").tag(168); Text("30 días").tag(720)
            }
            .pickerStyle(.segmented)
            if h.lista.isEmpty { Text("El monitor guarda una muestra por minuto: las gráficas aparecen cuando haya datos.").font(.caption).foregroundStyle(Tema.secundario) }
        }
        let series: [(String, String, Color)] = [
            ("memoriaUsadaMb", "Memoria usada (MB)", Tema.lila), ("cpuSistema", "CPU del sistema (%)", .cyan), ("latenciaMediaMs", "Latencia media (ms)", .green),
            ("latenciaP95Ms", "Latencia p95 (ms)", .teal), ("solicitudes", "Peticiones por minuto", Tema.lila), ("errores5xx", "Errores del servidor (5xx)", Tema.peligro),
            ("dbLatenciaMs", "Respuesta de la base de datos (ms)", .orange), ("discoLibreGb", "Disco libre (GB)", .gray),
            ("conexionesEnVivo", "Conexiones en vivo", .purple), ("fallosAudio", "Fallos de reproducción", .orange),
        ]
        ForEach(series, id: \.0) { s in
            Section(s.1) { GraficaSoporte(puntos: h.lista.map { ($0["fecha"].cadena, $0[s.0].numero) }, color: s.2) }
        }
    }

    @ViewBuilder
    private func diagnostico(_ pruebas: ValorAdmin) -> some View {
        Section {
            Button { Task { await diagnosticar(nil) } } label: { Label("Ejecutar diagnóstico completo", systemImage: "checkmark.seal") }
                .disabled(ejecutando != nil)
            if ejecutando != nil { ProgressView("Ejecutando pruebas…") }
        } footer: { Text("Pruebas controladas: solo leen, no cambian datos de nadie. Cada una tiene 15 s como máximo.") }
        Section("Pruebas") {
            ForEach(Array(pruebas.lista.enumerated()), id: \.offset) { _, p in
                let res = extra?["resultados"].lista.first { $0["id"].cadena == p["id"].cadena }
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(p["nombre"].cadena).font(.subheadline.weight(.semibold))
                        Spacer()
                        chip(res?["estado"].cadena ?? "no_comprobado")
                    }
                    Text(p["descripcion"].cadena).font(.caption).foregroundStyle(Tema.secundario)
                    if let res {
                        Text("\(res["resultado"].cadena) · \(res["duracionMs"].texto) ms").font(.caption)
                        if !res["recomendacion"].cadena.isEmpty { Label(res["recomendacion"].cadena, systemImage: "info.circle").font(.caption).foregroundStyle(.orange) }
                    }
                    Button("Ejecutar") { Task { await diagnosticar(p["id"].cadena) } }.font(.caption).buttonStyle(.borderless).disabled(ejecutando != nil)
                }
            }
        }
    }

    @ViewBuilder
    private func incidencias(_ r: ValorAdmin) -> some View {
        Section {
            TextField("Buscar en mensajes, origen o ruta", text: $busqueda).autocorrectionDisabled()
            Picker("Estado", selection: $filtro) {
                Text("Todo estado").tag(""); Text("Nuevas").tag("NUEVA"); Text("En investigación").tag("EN_INVESTIGACION"); Text("Resueltas").tag("RESUELTA"); Text("Descartadas").tag("DESCARTADA")
            }
        } footer: { Text("\(r["total"].texto) incidencias · los errores iguales se agrupan y se cuentan sus repeticiones") }
        Section {
            if r["contenido"].lista.isEmpty { Text("No hay incidencias con estos filtros").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["contenido"].lista.enumerated()), id: \.offset) { _, i in
                Button { Task { detalle = try? await API.get("/api/admin/soporte/incidencias/\(i["id"].entero)") } } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Chip(texto: i["severidad"].cadena == "ERROR" ? "Error" : "Advertencia", color: i["severidad"].cadena == "ERROR" ? Tema.peligro : .orange)
                            chip(i["estado"].cadena)
                            if i["reaparecio"].verdadero { Chip(texto: "reapareció", color: Tema.peligro) }
                            Spacer()
                            Text("×\(i["repeticiones"].texto)").font(.caption.weight(.bold))
                        }
                        Text(i["mensaje"].cadena).font(.subheadline).lineLimit(3)
                        Text("#\(i["id"].texto) · \(i["categoria"].cadena) · \(i["endpoint"].cadena) · \(Formato.hace(i["ultima"].cadena))")
                            .font(.caption2).foregroundStyle(Tema.secundario).lineLimit(2)
                    }
                }
                .buttonStyle(.plain)
            }
            paginador(r["paginas"].entero)
        }
    }

    @ViewBuilder
    private func bibliotecaRevision(_ r: ValorAdmin) -> some View {
        let p = r["progreso"]
        Section {
            Button { Task { await post("/api/admin/soporte/biblioteca/revisar", "Revisando la biblioteca…") } } label: { Label("Revisar ahora", systemImage: "arrow.clockwise") }
                .disabled(p["enCurso"].verdadero)
            if p["enCurso"].verdadero { ProgressView(value: p["pct"].numero ?? 0, total: 100) { Text(p["fase"].cadena) } }
            if !r["ultima"].esNulo {
                HStack { chip(r["ultima"]["estado"].cadena); Text("Última: \(r["ultima"]["inicio"].fechaCorta) por @\(r["ultima"]["usuario"].cadena)").font(.caption) }
            } else { Text("Aún no se revisó la biblioteca.").foregroundStyle(Tema.secundario) }
        } footer: { Text("Solo lee: no borra ni cambia canciones, portadas, letras ni datos.") }
        let u = r["ultima"]["resultado"]
        if !u.esNulo {
            if !u["error"].cadena.isEmpty { Section { Text(u["error"].cadena).foregroundStyle(Tema.peligro) } }
            Section("Resumen") {
                fila("Canciones", u["totalCanciones"].texto)
                fila("Sin archivo", u["sinArchivo"]["total"].texto)
                fila("Archivos sin registro", u["sinRegistro"]["total"].texto)
                fila("Sin género", u["sinGenero"]["total"].texto)
            }
            ForEach([("sinArchivo", "Canciones sin archivo"), ("sinRegistro", "Archivos sin registro"), ("metadatosIncompletos", "Datos incompletos"),
                     ("sinPortada", "Sin portada"), ("sinLetra", "Sin letra"), ("duplicados", "Posibles duplicados"), ("duracionesRaras", "Duraciones raras")], id: \.0) { b in
                let d = u[b.0]
                if !d.esNulo {
                    Section {
                        DisclosureGroup("\(b.1) (\(d["total"].texto))") {
                            ForEach(Array(d["lista"].lista.enumerated()), id: \.offset) { _, x in
                                if case .texto(let s) = x { Text(s).font(.caption.monospaced()) } else {
                                    VStack(alignment: .leading) {
                                        Text("\(x["nombre"].cadena) – \(x["artista"].cadena)").font(.caption.weight(.semibold))
                                        Text(x["ids"].lista.isEmpty ? "#\(x["id"].texto) \(x["archivo"].cadena)\(x["motivo"].cadena)" : x["ids"].lista.map { "#\($0.texto)" }.joined(separator: ", "))
                                            .font(.caption2).foregroundStyle(Tema.secundario)
                                    }
                                }
                            }
                            if !d["nota"].cadena.isEmpty { Text(d["nota"].cadena).font(.caption2).foregroundStyle(Tema.secundario) }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func baseDatos(_ r: ValorAdmin) -> some View {
        Section {
            HStack { chip(r["conectada"].verdadero ? "operativo" : "no_disponible"); Text(r["motor"].cadena).font(.headline) }
            fila("Latencia ahora", r["latenciaMs"].esNulo ? "" : "\(r["latenciaMs"].texto) ms")
            if case .objeto = r["latencia24h"] { fila("Latencia 24 h", "\(r["latencia24h"]["media"].texto) ms · máx. \(r["latencia24h"]["maxima"].texto) ms") }
            fila("Minutos sin conexión (24 h)", r["minutosSinConexion24h"].texto)
            fila("Errores de datos (7 días)", r["erroresDeDatos"].texto)
        }
        if case .objeto = r["pool"] {
            Section("Conexiones (pool)") {
                fila("Activas", r["pool"]["activas"].texto); fila("Libres", r["pool"]["libres"].texto)
                fila("Total · máximo", "\(r["pool"]["total"].texto) · \(r["pool"]["maximo"].texto)"); fila("Esperando", r["pool"]["esperando"].texto)
            }
        }
        Section("Esquema") { fila("Sistema", r["esquema"]["sistema"].cadena); Text(r["esquema"]["migraciones"].cadena).font(.caption).foregroundStyle(Tema.secundario) }
        Section("Consultas lentas (umbral \(r["umbralLentaMs"].texto) ms)") {
            if r["consultasLentas"].lista.isEmpty { Text("Ninguna consulta lenta desde que arrancó el servidor").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["consultasLentas"].lista.enumerated()), id: \.offset) { _, c in
                VStack(alignment: .leading) { Text(c["fecha"].fechaCorta).font(.caption2); Text(c["consulta"].cadena).font(.caption2.monospaced()).lineLimit(4) }
            }
        }
        Section("Espacio usado") {
            if case .objeto = r["almacenamiento"] {
                fila("Total", "\(r["almacenamiento"]["totalMb"].texto) MB")
                ForEach(Array(r["almacenamiento"]["tablas"].lista.enumerated()), id: \.offset) { _, t in
                    let nombre = t["tabla"].cadena.isEmpty ? t["TABLA"].cadena : t["tabla"].cadena
                    let filas = t["filas"].esNulo ? t["FILAS"].texto : t["filas"].texto
                    let mb = t["mb"].esNulo ? t["MB"].texto : t["mb"].texto
                    LabeledContent(nombre, value: "\(filas) filas · \(mb) MB").font(.caption)
                }
            } else { Text(r["almacenamiento"].cadena).foregroundStyle(Tema.secundario) }
        }
        let integ = extra ?? r["integridad"]
        Section {
            Button("Comprobar integridad") { Task { await integridad() } }.disabled(trabajando)
            if integ.esNulo { Text("Aún no se comprobó.").foregroundStyle(Tema.secundario) } else {
                Text("Comprobado \(integ["fecha"].fechaCorta) · \(integ["nota"].cadena)").font(.caption).foregroundStyle(Tema.secundario)
                ForEach(Array(integ["pruebas"].lista.enumerated()), id: \.offset) { _, p in
                    HStack { Text(p["prueba"].cadena).font(.caption); Spacer(); chip(p["estado"].cadena) }
                }
            }
        } header: { Text("Integridad (solo lectura)") }
    }

    @ViewBuilder
    private func sesiones(_ r: ValorAdmin) -> some View {
        Section {
            ForEach(r["clientes"].objeto.sorted(by: { $0.key < $1.key }), id: \.key) { c in fila(c.key, c.value.texto) }
            if r["clientes"].objeto.isEmpty { Text("Nadie activo ahora").foregroundStyle(Tema.secundario) }
        } header: { Text("\(r["activas"].lista.count) sesiones activas · clientes") } footer: { Text(r["nota"].cadena) }
        Section("Actividad reciente") {
            ForEach(Array(r["activas"].lista.enumerated()), id: \.offset) { _, a in
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(a["nombre"].cadena) · @\(a["username"].cadena)").font(.subheadline.weight(.semibold))
                    Text("\(a["cliente"].cadena) \(a["version"].cadena) · \(Formato.hace(a["ultimaActividad"].cadena)) · \(a["redes"].lista.map(\.cadena).joined(separator: ", "))")
                        .font(.caption).foregroundStyle(Tema.secundario)
                }
                .swipeActions {
                    if a["id"].entero != Sesion.compartida.usuario?.id {
                        Button("Cerrar sesiones", role: .destructive) { Task { await post("/api/admin/usuarios/\(a["id"].entero)/cerrar-sesiones", "Sesiones cerradas") } }
                    }
                }
            }
        }
        if !r["sospechosas"].lista.isEmpty {
            Section("Actividad para revisar") {
                ForEach(Array(r["sospechosas"].lista.enumerated()), id: \.offset) { _, s in
                    VStack(alignment: .leading) {
                        Text(s["motivo"].cadena).font(.subheadline).foregroundStyle(Tema.peligro)
                        Text((s["usuarioId"].esNulo ? s["ip"].cadena : "#\(s["usuarioId"].texto)") + " · " + Formato.hace(s["desde"].cadena)).font(.caption)
                    }
                }
            }
        }
        Section("Accesos denegados (401/403)") {
            if r["fallosAcceso"].lista.isEmpty { Text("Ninguno desde que arrancó el servidor").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["fallosAcceso"].lista.prefix(40).enumerated()), id: \.offset) { _, f in
                Text("\(f["fecha"].fechaCorta) · \(f["estado"].texto) · \(f["endpoint"].cadena) · \(f["cliente"].cadena) · \(f["ip"].cadena)").font(.caption2)
            }
        }
        Section("Inicios de sesión fallidos") {
            if r["fallosLogin"].lista.isEmpty { Text("Ninguno desde que arrancó el servidor").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["fallosLogin"].lista.prefix(40).enumerated()), id: \.offset) { _, f in
                Text("\(f["fecha"].fechaCorta) · \(f["usuario"].cadena) · \(f["ip"].cadena)").font(.caption2)
            }
        }
    }

    @ViewBuilder
    private func alertas(_ r: ValorAdmin) -> some View {
        let res = r["resumen"]
        Section {
            Picker("Estado", selection: $filtro) { Text("Activas").tag(""); Text("Reconocidas").tag("RECONOCIDA"); Text("Resueltas").tag("RESUELTA"); Text("Todas").tag("TODAS") }
                .pickerStyle(.segmented)
            Text("Activas \(res["ACTIVA"].texto) · reconocidas \(res["RECONOCIDA"].texto) · resueltas \(res["RESUELTA"].texto) · nuevas en 24 h \(res["ultimas24h"].texto)")
                .font(.caption)
            Text("Avisos: campana \(res["canales"]["campana"].cadena) · correo \(res["canales"]["correo"].cadena)").font(.caption).foregroundStyle(Tema.secundario)
        }
        Section {
            if r["contenido"].lista.isEmpty { Text("Sin alertas en este estado").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["contenido"].lista.enumerated()), id: \.offset) { _, a in
                VStack(alignment: .leading, spacing: 3) {
                    HStack { chip(a["estado"].cadena); Text(a["tipo"].cadena).font(.caption.weight(.semibold)); Spacer(); Text("×\(a["ocurrencias"].texto)").font(.caption) }
                    Text(a["mensaje"].cadena).font(.subheadline)
                    Text("Creada \(a["creada"].fechaCorta) · última \(Formato.hace(a["actualizada"].cadena))" + (a["reconocidaPor"].cadena.isEmpty ? "" : " · reconocida por @\(a["reconocidaPor"].cadena)"))
                        .font(.caption2).foregroundStyle(Tema.secundario)
                }
                .swipeActions {
                    if a["estado"].cadena == "ACTIVA" { Button("Reconocer") { Task { await post("/api/admin/soporte/alertas/\(a["id"].entero)/reconocer", "Alerta reconocida") } }.tint(.orange) }
                    if a["estado"].cadena != "RESUELTA" { Button("Resolver") { Task { await post("/api/admin/soporte/alertas/\(a["id"].entero)/resolver", "Alerta resuelta") } }.tint(.green) }
                }
            }
        } footer: { Text("Desliza una alerta para reconocerla o resolverla.") }
        if let cfg = extra {
            Section {
                ForEach(cfg.objeto.sorted(by: { $0.key < $1.key }), id: \.key) { par in
                    let k = par.key, c = par.value
                    AjusteDecimal(descripcion: c["descripcion"].cadena, valor: c["valor"].numero ?? 0, minimo: c["min"].numero ?? 0, maximo: c["max"].numero ?? 1000,
                                  cambiadoPor: c["actualizadaPor"].cadena) { v in
                        Task { await guardarConfig(k, v) }
                    }
                }
            } header: { Text("Umbrales y retención") } footer: {
                Text("Las alertas se revisan cada minuto con las métricas reales. Una misma condición no se repite antes de «\(cfg["alerta.repetirMinutos"]["valor"].texto)» minutos.")
            }
        }
    }

    @ViewBuilder
    private func auditoria(_ r: ValorAdmin) -> some View {
        Section("Medidas de seguridad activas") {
            ForEach(Array(r["medidas"].lista.enumerated()), id: \.offset) { _, m in Label(m.cadena, systemImage: "checkmark").font(.caption) }
        }
        Section {
            TextField("Buscar acción, detalle o cuenta", text: $busqueda).autocorrectionDisabled()
            Picker("Resultado", selection: $filtro) { Text("Todo resultado").tag(""); Text("Correcto").tag("OK"); Text("Error").tag("ERROR"); Text("Denegada").tag("DENEGADA") }
        } header: { Text("Auditoría administrativa") } footer: { Text("\(r["total"].texto) registros · solo lectura") }
        Section {
            ForEach(Array(r["contenido"].lista.enumerated()), id: \.offset) { _, a in
                VStack(alignment: .leading, spacing: 2) {
                    HStack { Text(a["accion"].cadena).font(.caption.monospaced().weight(.semibold)); Spacer(); chip(a["resultado"].cadena) }
                    if !a["detalle"].cadena.isEmpty { Text(a["detalle"].cadena).font(.caption) }
                    Text("\(a["fecha"].fechaCorta) · @\(a["usuario"].cadena) · \(a["ip"].cadena)").font(.caption2).foregroundStyle(Tema.secundario)
                }
            }
            paginador(r["paginas"].entero)
        }
    }

    @ViewBuilder
    private func mantenimiento(_ r: ValorAdmin) -> some View {
        Section("Tareas en segundo plano") {
            ForEach(Array(r["tareas"].lista.enumerated()), id: \.offset) { _, t in
                VStack(alignment: .leading, spacing: 2) {
                    HStack { Text(t["nombre"].cadena).font(.subheadline.weight(.semibold)); Spacer(); chip(t["estado"].cadena == "en curso" ? "EN_CURSO" : "en espera") }
                    Text(t["detalle"].cadena).font(.caption).foregroundStyle(Tema.secundario)
                }
            }
        }
        Section {
            Button("Volver a escanear la biblioteca") { Task { await post("/api/admin/biblioteca/escanear", "Escaneo iniciado") } }
            Button("Recargar biblioteca en memoria") { Task { await post("/api/admin/soporte/mantenimiento/cache?cual=biblioteca", "Caché vaciada") } }
            Button("Vaciar mezclas y recomendaciones") { Task { await post("/api/admin/soporte/mantenimiento/cache?cual=recomendaciones", "Caché vaciada") } }
            Button("Vaciar géneros de artistas") { Task { await post("/api/admin/soporte/mantenimiento/cache?cual=generos", "Caché vaciada") } }
        } header: { Text("Biblioteca y cachés") } footer: { Text("Las cachés se vacían sin riesgo y se recalculan solas.") }
        Section("Carpetas") {
            ForEach(Array(r["directorios"].lista.enumerated()), id: \.offset) { _, d in
                VStack(alignment: .leading, spacing: 2) {
                    Text(d["nombre"].cadena).font(.subheadline.weight(.semibold))
                    Text(d["carpeta"].cadena).font(.caption2.monospaced()).foregroundStyle(Tema.secundario)
                    Text("Existe \(d["existe"].texto) · lectura \(d["lectura"].texto) · escritura \(d["escritura"].texto)" + (d["libreGb"].esNulo ? "" : " · \(d["libreGb"].texto) GB libres"))
                        .font(.caption2)
                }
            }
        }
        let resp = r["respaldos"]
        Section {
            Button(resp["enCurso"].verdadero ? "Creando…" : "Crear copia ahora") { Task { await post("/api/admin/soporte/respaldos", "Creando la copia de seguridad…") } }
                .disabled(resp["enCurso"].verdadero)
            Text("\(resp["programacion"].cadena) · se conservan las últimas \(resp["conservar"].texto) · \(resp["carpeta"].cadena)").font(.caption).foregroundStyle(Tema.secundario)
            if !resp["ultimo"].esNulo {
                let u = resp["ultimo"]
                HStack { chip(u["resultado"].cadena); Text("Última: \(u["inicio"].fechaCorta) (\(u["motivo"].cadena)) · \(u["tamanoMb"].texto) MB · \(u["duracionSeg"].texto) s").font(.caption) }
                if !u["error"].cadena.isEmpty { Text(u["error"].cadena).font(.caption).foregroundStyle(Tema.peligro) }
            }
            ForEach(Array(resp["copias"].lista.enumerated()), id: \.offset) { _, c in
                LabeledContent(c["archivo"].cadena, value: "\(c["tamanoMb"].texto) MB · \(c["fecha"].fechaCorta)").font(.caption)
            }
            DisclosureGroup("Cómo restaurar una copia") {
                Text(resp["restaurar"].cadena).font(.caption)
                Text("La restauración no se hace desde la app porque reemplaza todos los datos.").font(.caption2).foregroundStyle(Tema.secundario)
            }
        } header: { Text("Copias de seguridad de la base de datos") }
        let v = r["versiones"]
        Section("Versiones") {
            fila("Web", v["web"].cadena)
            fila("App de Android", v["apkMovil"]["nombre"].esNulo ? v["apkMovil"].cadena : "\(v["apkMovil"]["nombre"].cadena) (código \(v["apkMovil"]["code"].texto))")
            fila("App de TV", v["apkTv"]["nombre"].esNulo ? v["apkTv"].cadena : "\(v["apkTv"]["nombre"].cadena) (código \(v["apkTv"]["code"].texto))")
            fila("App de iPhone", Actualizaciones.compartida.versionActual)
            fila("Java · Spring Boot", "\(v["plataforma"]["java"].cadena) · \(v["plataforma"]["springBoot"].cadena)")
        }
        Section {
            Button { Task { await descargar("/api/admin/soporte/exportar", "spacemusic-diagnostico.json") } } label: { Label("Exportar diagnóstico (JSON)", systemImage: "square.and.arrow.down") }
        } footer: { Text("Estado, incidencias recientes (sin trazas), alertas, diagnósticos y ajustes. Sin correos, tokens ni contraseñas.") }
    }

    @ViewBuilder
    private func informes(_ r: ValorAdmin) -> some View {
        Section {
            Picker("Período", selection: $dias) { Text("Hoy").tag(1); Text("7 días").tag(7); Text("30 días").tag(30); Text("90 días").tag(90) }.pickerStyle(.segmented)
            Text("Del \(r["desde"].cadena) al \(r["hasta"].cadena) · agrupado por \(r["agrupacion"].cadena)").font(.caption).foregroundStyle(Tema.secundario)
            HStack {
                ForEach([("incidencias", "Incidencias"), ("metricas", "Métricas"), ("endpoints", "Rutas")], id: \.0) { t in
                    Button("CSV \(t.1)") { Task { await descargar("/api/admin/soporte/informes/csv?tipo=\(t.0)&dias=\(dias)", "spacemusic-\(t.0).csv") } }
                        .font(.caption).buttonStyle(.bordered)
                }
            }
        }
        Section("Resumen") {
            fila("Disponibilidad", r["disponibilidad"]["porcentaje"].esNulo ? "" : "\(r["disponibilidad"]["porcentaje"].texto) %")
            let ie = r["incidenciasPorEstado"]
            fila("Incidencias pendientes", String(ie["NUEVA"].entero + ie["EN_INVESTIGACION"].entero))
            fila("Resueltas · descartadas", "\(ie["RESUELTA"].entero) · \(ie["DESCARTADA"].entero)")
            fila("Tiempo de resolución", r["tiempoResolucion"]["horasPromedio"].esNulo ? r["tiempoResolucion"]["nota"].cadena : "\(r["tiempoResolucion"]["horasPromedio"].texto) h")
        }
        Section("Incidencias por día") {
            GraficaSoporte(puntos: r["incidenciasPorDia"].lista.map { ($0["fecha"].cadena.isEmpty ? $0["periodo"].cadena : $0["fecha"].cadena, $0["cuenta"].numero ?? $0["total"].numero) },
                           color: .orange, barras: true)
        }
        ForEach([("latenciaMs", "Latencia media (ms)", Color.green), ("memoriaMb", "Memoria usada (MB)", Tema.lila), ("discoLibreGb", "Disco libre (GB)", Color.gray),
                 ("disponibilidad", "Disponibilidad (%)", Color.teal), ("fallosAudio", "Fallos de reproducción", Color.orange)], id: \.0) { s in
            Section(s.1) { GraficaSoporte(puntos: r["serie"].lista.map { ($0["t"].cadena, $0[s.0].numero) }, color: s.2) }
        }
        Section("Errores por módulo") {
            if r["erroresPorModulo"].lista.isEmpty { Text("Sin errores en el período").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["erroresPorModulo"].lista.enumerated()), id: \.offset) { _, m in fila(m["modulo"].cadena, m["ocurrencias"].texto) }
        }
        Section("Rutas con más errores") {
            if r["endpointsConMasErrores"].lista.isEmpty { Text("Sin errores en el período").foregroundStyle(Tema.secundario) }
            ForEach(Array(r["endpointsConMasErrores"].lista.enumerated()), id: \.offset) { _, e in
                LabeledContent(e["endpoint"].cadena, value: "\(e["estado"].texto) · ×\(e["cuenta"].texto)").font(.caption)
            }
        }
        let c = r["comparacion"]
        Section("Comparación con el período anterior") {
            ForEach([("incidencias", "Incidencias"), ("errores5xx", "Errores del servidor"), ("fallosAudio", "Fallos de reproducción"),
                     ("solicitudes", "Peticiones"), ("latenciaMediaMs", "Latencia media (ms)"), ("disponibilidad", "Disponibilidad (%)")], id: \.0) { k in
                LabeledContent(k.1, value: "\(c["actual"][k.0].texto) · antes \(c["anterior"][k.0].texto)")
            }
        }
    }

    @ViewBuilder
    private func paginador(_ paginas: Int) -> some View {
        if paginas > 1 {
            HStack {
                Button { pagina -= 1 } label: { Image(systemName: "chevron.left") }.disabled(pagina == 0)
                Spacer(); Text("Página \(pagina + 1) de \(paginas)").font(.caption); Spacer()
                Button { pagina += 1 } label: { Image(systemName: "chevron.right") }.disabled(pagina + 1 >= paginas)
            }
            .buttonStyle(.borderless)
        }
    }

    // MARK: Datos y acciones

    private func cargar() async {
        do {
            switch seccion {
            case .resumen: datos = try await API.get("/api/admin/soporte/resumen")
            case .monitor: datos = try await API.get("/api/admin/soporte/monitor/historial?horas=\(horas)")
            case .diagnostico:
                datos = try await API.get("/api/admin/soporte/diagnosticos/pruebas")
            case .incidencias:
                var q = "pagina=\(pagina)&tam=25&orden=fecha"
                if !filtro.isEmpty { q += "&estado=\(filtro)" }
                if !busqueda.isEmpty { q += "&q=\(API.codificar(busqueda))" }
                datos = try await API.get("/api/admin/soporte/incidencias?\(q)")
            case .biblioteca: datos = try await API.get("/api/admin/soporte/biblioteca/revision")
            case .basedatos: datos = try await API.get("/api/admin/soporte/basedatos")
            case .sesiones: datos = try await API.get("/api/admin/soporte/sesiones")
            case .alertas:
                let estado = filtro.isEmpty ? "&estado=ACTIVA" : filtro == "TODAS" ? "" : "&estado=\(filtro)"
                datos = try await API.get("/api/admin/soporte/alertas?tam=50\(estado)")
                extra = try? await API.get("/api/admin/soporte/config")
            case .seguridad:
                var q = "pagina=\(pagina)&tam=30"
                if !filtro.isEmpty { q += "&resultado=\(filtro)" }
                if !busqueda.isEmpty { q += "&q=\(API.codificar(busqueda))" }
                datos = try await API.get("/api/admin/soporte/auditoria?\(q)")
            case .mantenimiento: datos = try await API.get("/api/admin/soporte/mantenimiento")
            case .informes: datos = try await API.get("/api/admin/soporte/informes?dias=\(dias)")
            }
            error = nil
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }

    private func post(_ ruta: String, _ ok: String) async {
        trabajando = true
        defer { trabajando = false }
        do { try await API.hacer("POST", ruta); Avisos.compartido.mostrar(ok); await cargar() }
        catch { Avisos.compartido.error(error) }
    }

    /// Diagnóstico completo o de una prueba: se sigue el avance cada 1,2 s como la web.
    private func diagnosticar(_ prueba: String?) async {
        do {
            let r: ValorAdmin = try await API.enviar("POST", "/api/admin/soporte/diagnosticos" + (prueba.map { "?prueba=\(API.codificar($0))" } ?? ""))
            let id = r["id"].entero
            ejecutando = id
            defer { ejecutando = nil }
            while !Task.isCancelled {
                if let e: ValorAdmin = try? await API.get("/api/admin/soporte/diagnosticos/\(id)") {
                    extra = e
                    if e["estado"].cadena != "EN_CURSO" {
                        let res = e["resultados"].lista
                        let n = { (s: String) in res.filter { $0["estado"].cadena == s }.count }
                        Avisos.compartido.mostrar("Diagnóstico: \(n("correcta")) correctas, \(n("advertencia")) advertencias, \(n("fallida")) fallidas")
                        return
                    }
                }
                try? await Task.sleep(for: .milliseconds(1200))
            }
        } catch { Avisos.compartido.error(error) }
    }

    private func integridad() async {
        trabajando = true
        defer { trabajando = false }
        do { extra = try await API.enviar("POST", "/api/admin/soporte/basedatos/integridad"); Avisos.compartido.mostrar("Integridad comprobada") }
        catch { Avisos.compartido.error(error) }
    }

    private func guardarConfig(_ clave: String, _ valor: Double) async {
        do { try await API.hacer("PUT", "/api/admin/soporte/config", ["clave": clave, "valor": String(valor)]); Avisos.compartido.mostrar("Ajuste guardado") }
        catch { Avisos.compartido.error(error); await cargar() }
    }

    /// Descarga con el permiso de administrador y abre «Compartir» para guardarlo (JSON o CSV).
    private func descargar(_ ruta: String, _ nombre: String) async {
        do {
            let pedido = try API.peticion(ruta)
            let resultado = try await API.sesionURL.data(for: pedido)
            guard (resultado.1 as? HTTPURLResponse)?.statusCode == 200 else { throw ErrorAPI(mensaje: "No se pudo descargar", estado: 0) }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(nombre)
            try resultado.0.write(to: url, options: .atomic)
            archivo = url
        } catch { Avisos.compartido.error(error) }
    }
}

/// Detalle de una incidencia: datos, traza, notas y cambio de estado.
struct DetalleIncidenciaHoja: View {
    let inc: ValorAdmin
    let alCambiar: () async -> Void
    @Environment(\.dismiss) private var cerrar
    @State private var nota = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Estado", value: nombreEstado(inc["estado"].cadena) + (inc["reaparecio"].verdadero ? " · reapareció" : ""))
                    LabeledContent("Severidad", value: inc["severidad"].cadena)
                    LabeledContent("Módulo · origen", value: "\(inc["categoria"].cadena) · \(inc["origen"].cadena)")
                    LabeledContent("Tipo", value: inc["tipo"].cadena)
                    LabeledContent("Ruta", value: inc["endpoint"].cadena)
                    LabeledContent("Id. de petición", value: inc["requestId"].cadena)
                    LabeledContent("Repeticiones", value: inc["repeticiones"].texto)
                    LabeledContent("Primera · última", value: "\(inc["primera"].fechaCorta) · \(inc["ultima"].fechaCorta)")
                }
                Section("Mensaje") { Text(inc["mensaje"].cadena).font(.caption.monospaced()).textSelection(.enabled) }
                if !inc["traza"].cadena.isEmpty {
                    Section { DisclosureGroup("Traza técnica") { Text(inc["traza"].cadena).font(.caption2.monospaced()).textSelection(.enabled) } }
                }
                Section("Notas de investigación") {
                    if !inc["notas"].cadena.isEmpty { Text(inc["notas"].cadena).font(.caption) }
                    TextField("Agregar una nota (opcional al cambiar el estado)", text: $nota, axis: .vertical).lineLimit(2...5)
                    Button("Guardar nota") { Task { await enviar("POST", "notas", ["nota": nota]) } }.disabled(nota.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                Section {
                    ForEach([("EN_INVESTIGACION", "En investigación"), ("RESUELTA", "Marcar resuelta"), ("DESCARTADA", "Descartar"), ("NUEVA", "Reabrir")], id: \.0) { e in
                        if inc["estado"].cadena != e.0 { Button(e.1) { Task { await enviar("PUT", "estado", ["estado": e.0, "nota": nota]) } } }
                    }
                } footer: { Text("Cambiar el estado es solo seguimiento: no modifica el registro técnico ni repara nada.") }
            }
            .navigationTitle("Incidencia #\(inc["id"].texto)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Cerrar") { cerrar() } } }
        }
    }

    private func enviar(_ metodo: String, _ parte: String, _ cuerpo: [String: Any]) async {
        do {
            try await API.hacer(metodo, "/api/admin/soporte/incidencias/\(inc["id"].entero)/\(parte)", cuerpo)
            Avisos.compartido.mostrar(parte == "notas" ? "Nota guardada" : "Estado actualizado")
            await alCambiar()
            cerrar()
        } catch { Avisos.compartido.error(error) }
    }
}

/// Gráfica de una serie del monitor o de los informes (línea o barras).
struct GraficaSoporte: View {
    let puntos: [(String, Double?)]
    var color: Color = Tema.celeste
    var barras = false

    private var validos: [(Int, Double)] { puntos.enumerated().compactMap { i, p in p.1.map { (i, $0) } } }

    var body: some View {
        if validos.isEmpty {
            Text("Sin datos todavía").font(.caption).foregroundStyle(Tema.secundario)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Chart(validos, id: \.0) { p in
                    if barras { BarMark(x: .value("Momento", p.0), y: .value("Valor", p.1)).foregroundStyle(color) }
                    else { LineMark(x: .value("Momento", p.0), y: .value("Valor", p.1)).foregroundStyle(color).interpolationMethod(.monotone) }
                }
                .chartXAxis(.hidden)
                .frame(height: 120)
                HStack {
                    Text(puntos.first?.0.prefix(16) ?? "").font(.caption2)
                    Spacer()
                    Text("último: " + String(format: "%.1f", validos.last?.1 ?? 0)).font(.caption2.weight(.semibold))
                }
                .foregroundStyle(Tema.secundario)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Gráfica, último valor \(String(format: "%.1f", validos.last?.1 ?? 0))")
        }
    }
}

/// Ajuste numérico con decimales (umbrales de alertas); se guarda al pulsar «Guardar».
struct AjusteDecimal: View {
    let descripcion: String
    @State var valor: Double
    let minimo: Double
    let maximo: Double
    let cambiadoPor: String
    let guardar: (Double) -> Void
    @State private var original: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(descripcion).font(.subheadline)
            HStack {
                TextField("Valor", value: $valor, format: .number).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).frame(maxWidth: 120)
                Text("(\(minimo.formatted()) – \(maximo.formatted()))").font(.caption2).foregroundStyle(Tema.secundario)
                Spacer()
                if original != nil, original != valor {
                    Button("Guardar") { let v = min(max(valor, minimo), maximo); valor = v; original = v; guardar(v) }.buttonStyle(.borderless)
                }
            }
            if !cambiadoPor.isEmpty { Text("Cambiado por @\(cambiadoPor)").font(.caption2).foregroundStyle(Tema.secundario) }
        }
        .onAppear { if original == nil { original = valor } }
    }
}
