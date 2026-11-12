#!/usr/bin/env python3
"""
Proveedor de búsqueda y metadatos de YouTube Music para SpaceMusic.

Usa ytmusicapi (biblioteca NO oficial: no es una API de Google; puede dejar de funcionar si
YouTube cambia). Solo consulta datos públicos sin iniciar sesión; no descarga nada.

Entrada: un JSON por la entrada estándar (nunca por argumentos):
  {"accion": "buscar", "consulta": "...", "filtro": "songs|albums|artists", "limite": 20}
  {"accion": "relacionadas", "videoId": "...", "limite": 25}
  {"accion": "artista", "browseId": "..."}
  {"accion": "album", "browseId": "..."}
Salida: un JSON por la salida estándar: {"ok": true, "resultados": [...]} o {"ok": false, "error": "..."}
"""
import json
import re
import sys

VIDEO = re.compile(r"^[A-Za-z0-9_-]{11}$")
BROWSE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
FILTROS = {"songs", "albums", "artists"}


def salir(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False))
    sys.stdout.flush()
    sys.exit(0)


def duracion(item):
    if isinstance(item.get("duration_seconds"), int):
        return item["duration_seconds"]
    d = item.get("duration") or item.get("length")
    if not d or not isinstance(d, str):
        return None
    partes = [int(p) for p in d.split(":") if p.isdigit()]
    seg = 0
    for p in partes:
        seg = seg * 60 + p
    return seg or None


def portada(item):
    th = item.get("thumbnails") or item.get("thumbnail") or []
    if isinstance(th, dict):
        th = th.get("thumbnails") or []
    if not th:
        return None
    # La más pequeña que mida al menos 120 px (no se cargan imágenes completas en las listas)
    th = sorted(th, key=lambda t: t.get("width") or 0)
    for t in th:
        if (t.get("width") or 0) >= 120:
            return t.get("url")
    return th[-1].get("url")


def artistas(item):
    return [a.get("name") for a in (item.get("artists") or []) if a.get("name")]


def cancion(item, tipo="cancion"):
    vid = item.get("videoId")
    if not vid or not VIDEO.match(vid):
        return None
    album = item.get("album") or {}
    if isinstance(album, list):
        album = album[0] if album else {}
    return {
        "tipo": tipo,
        "videoId": vid,
        "titulo": item.get("title"),
        "artistas": artistas(item),
        "album": album.get("name") if isinstance(album, dict) else None,
        "duracion": duracion(item),
        "portada": portada(item),
        "explicita": bool(item.get("isExplicit")),
    }


def main():
    try:
        datos = json.loads(sys.stdin.read() or "{}")
    except Exception:
        salir({"ok": False, "error": "entrada no válida"})
    try:
        from ytmusicapi import YTMusic
    except Exception:
        salir({"ok": False, "error": "ytmusicapi no está instalado"})

    yt = YTMusic()  # en español la biblioteca no reconoce los tipos de resultado
    accion = datos.get("accion")
    limite = max(1, min(int(datos.get("limite") or 20), 50))

    if accion == "buscar":
        consulta = str(datos.get("consulta") or "").strip()[:150]
        filtro = datos.get("filtro") if datos.get("filtro") in FILTROS else "songs"
        if len(consulta) < 2:
            salir({"ok": False, "error": "consulta demasiado corta"})
        res = yt.search(consulta, filter=filtro, limit=limite)
        salida = []
        for it in res:
            if filtro == "songs":
                c = cancion(it)
                if c:
                    salida.append(c)
            elif filtro == "albums":
                bid = it.get("browseId")
                if bid and BROWSE.match(bid):
                    salida.append({"tipo": "album", "browseId": bid, "titulo": it.get("title"),
                                   "artistas": artistas(it), "anio": it.get("year"), "portada": portada(it)})
            elif filtro == "artists":
                bid = it.get("browseId")
                if bid and BROWSE.match(bid):
                    salida.append({"tipo": "artista", "browseId": bid, "titulo": it.get("artist") or it.get("title"),
                                   "portada": portada(it)})
        salir({"ok": True, "resultados": salida[:limite]})

    if accion == "relacionadas":
        vid = str(datos.get("videoId") or "")
        if not VIDEO.match(vid):
            salir({"ok": False, "error": "identificador no válido"})
        w = yt.get_watch_playlist(videoId=vid, limit=limite)
        salida = [c for c in (cancion(t) for t in (w.get("tracks") or [])) if c and c["videoId"] != vid]
        salir({"ok": True, "resultados": salida[:limite]})

    if accion == "album":
        bid = str(datos.get("browseId") or "")
        if not BROWSE.match(bid):
            salir({"ok": False, "error": "identificador no válido"})
        a = yt.get_album(bid)
        pl = a.get("audioPlaylistId")
        canciones = []
        for t in a.get("tracks") or []:
            c = cancion(t)
            if c:
                if not c.get("album"):
                    c["album"] = a.get("title")
                if not c.get("portada"):
                    c["portada"] = portada(a)
                canciones.append(c)
        salir({"ok": True, "resultados": canciones, "album": {"titulo": a.get("title"), "artistas": artistas(a),
               "anio": a.get("year"), "listaId": pl if pl and BROWSE.match(pl) else None, "portada": portada(a)}})

    if accion == "artista":
        bid = str(datos.get("browseId") or "")
        if not BROWSE.match(bid):
            salir({"ok": False, "error": "identificador no válido"})
        a = yt.get_artist(bid)
        canciones = [c for c in (cancion(t) for t in ((a.get("songs") or {}).get("results") or [])) if c]
        relacionados = [{"browseId": r.get("browseId"), "titulo": r.get("title"), "portada": portada(r)}
                        for r in ((a.get("related") or {}).get("results") or []) if r.get("browseId")]
        salir({"ok": True, "resultados": canciones, "relacionados": relacionados[:15],
               "artista": {"titulo": a.get("name"), "portada": portada(a)}})

    salir({"ok": False, "error": "acción desconocida"})


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:  # nunca se devuelve la traza completa
        salir({"ok": False, "error": type(e).__name__ + ": " + str(e)[:200]})
