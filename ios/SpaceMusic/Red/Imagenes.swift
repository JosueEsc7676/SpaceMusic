import SwiftUI
import UIKit

/// Descarga y guarda en memoria las portadas y fotos.
actor CacheImagenes {
    static let compartida = CacheImagenes()

    nonisolated(unsafe) private let memoria = NSCache<NSString, UIImage>()
    private var enCurso: [String: Task<UIImage?, Never>] = [:]

    init() { memoria.countLimit = 400 }

    nonisolated func enMemoria(_ ruta: String) -> UIImage? { memoria.object(forKey: ruta as NSString) }

    func imagen(_ ruta: String) async -> UIImage? {
        if let i = memoria.object(forKey: ruta as NSString) { return i }
        if let t = enCurso[ruta] { return await t.value }
        let t = Task<UIImage?, Never> {
            guard var r = try? API.peticion(ruta) else { return nil }
            r.setValue("image/*", forHTTPHeaderField: "Accept")
            r.cachePolicy = .returnCacheDataElseLoad
            guard let (d, resp) = try? await API.sesionURL.data(for: r),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  let img = UIImage(data: d) else { return nil }
            return img.preparingThumbnail(of: CacheImagenes.tamano(img.size)) ?? img
        }
        enCurso[ruta] = t
        let img = await t.value
        enCurso[ruta] = nil
        if let img { memoria.setObject(img, forKey: ruta as NSString) }
        return img
    }

    /// Reduce imágenes enormes (máximo 900 px) para ahorrar memoria.
    private static func tamano(_ s: CGSize) -> CGSize {
        let m = max(s.width, s.height)
        guard m > 900 else { return s }
        let f = 900 / m
        return CGSize(width: s.width * f, height: s.height * f)
    }
}

/// Imagen del servidor con un fondo mientras carga.
struct ImagenRemota: View {
    let ruta: String?
    var icono = "music.note"
    var redonda = false

    @State private var imagen: UIImage?

    var body: some View {
        ZStack {
            if let imagen {
                Image(uiImage: imagen).resizable().scaledToFill()
            } else {
                LinearGradient(colors: [Color(white: 0.16), Color(white: 0.09)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: icono).font(.title2).foregroundStyle(.white.opacity(0.35))
            }
        }
        .clipped()
        .clipShape(redonda ? AnyShape(Circle()) : AnyShape(Rectangle()))
        .task(id: ruta) {
            guard let ruta, !ruta.isEmpty else { imagen = nil; return }
            if let i = CacheImagenes.compartida.enMemoria(ruta) { imagen = i; return }
            imagen = nil
            let nueva = await CacheImagenes.compartida.imagen(ruta)
            guard !Task.isCancelled else { return }
            imagen = nueva
        }
    }
}

/// Portada cuadrada con esquinas redondeadas.
struct Portada: View {
    let ruta: String?
    var tamano: CGFloat? = nil
    var radio: CGFloat = 8
    var icono = "music.note"

    var body: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { ImagenRemota(ruta: ruta, icono: icono) }
            .frame(width: tamano, height: tamano)
            .clipShape(RoundedRectangle(cornerRadius: radio, style: .continuous))
    }
}

/// Mosaico de hasta 4 portadas (playlists y mixes sin imagen propia).
struct Mosaico: View {
    let rutas: [String]
    var radio: CGFloat = 8

    var body: some View {
        GeometryReader { g in
            let lado = g.size.width / 2
            if rutas.count >= 4 {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        ImagenRemota(ruta: rutas[0]).frame(width: lado, height: lado)
                        ImagenRemota(ruta: rutas[1]).frame(width: lado, height: lado)
                    }
                    HStack(spacing: 0) {
                        ImagenRemota(ruta: rutas[2]).frame(width: lado, height: lado)
                        ImagenRemota(ruta: rutas[3]).frame(width: lado, height: lado)
                    }
                }
            } else {
                ImagenRemota(ruta: rutas.first, icono: "music.note.list").frame(width: g.size.width, height: g.size.width)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: radio, style: .continuous))
    }
}

/// Foto de perfil redonda con la inicial si no hay foto.
struct Avatar: View {
    let ruta: String?
    let nombre: String
    var tamano: CGFloat = 40

    var body: some View {
        ZStack {
            if let ruta, !ruta.isEmpty {
                ImagenRemota(ruta: ruta, icono: "person.fill", redonda: true)
            } else {
                Circle().fill(LinearGradient(colors: [Tema.lila, Tema.celeste], startPoint: .topLeading, endPoint: .bottomTrailing))
                Text(String(nombre.prefix(1)).uppercased())
                    .font(.system(size: tamano * 0.42, weight: .bold, design: .rounded))
                    .foregroundStyle(.black.opacity(0.75))
            }
        }
        .frame(width: tamano, height: tamano)
        .clipShape(Circle())
    }
}
