#!/usr/bin/env python3
"""Fuentes de respaldo para `descargar` cuando YouTube bloquea o pide iniciar sesión.

  buscar-audio.py nombre <enlace>             «Artista - Canción» de un enlace de YouTube/YouTube Music
                                              (oEmbed: funciona aunque el video pida edad o sesión) o de Spotify.
  buscar-audio.py bajar "Artista - Canción" <carpeta>
                                              Busca en Audius y en Internet Archive (APIs abiertas, sin claves
                                              y sin bloqueos por IP); baja la primera que coincida en artista y
                                              título y le pone título, artista, álbum, año y portada.
Sale con 0 si lo logró (e imprime el resultado) y con 1 si no.
"""
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request

AGENTE = "SpaceMusic/1.0 (+descargas personales)"


def pedir(url, binario=False, tiempo=20):
    r = urllib.request.Request(url, headers={"User-Agent": AGENTE, "Accept": "*/*"})
    with urllib.request.urlopen(r, timeout=tiempo) as resp:
        datos = resp.read()
    return datos if binario else datos.decode("utf-8", "replace")


def normal(t):
    t = unicodedata.normalize("NFKD", t or "").encode("ascii", "ignore").decode().lower()
    t = re.sub(r"[\(\[].*?[\)\]]", " ", t)
    t = re.sub(r"\b(feat|ft|official|video|audio|lyrics?|letra|remaster(ed)?)\b.*", " ", t)
    return re.sub(r"[^a-z0-9]+", " ", t).strip()


def coincide(artista, titulo, art2, tit2):
    a, t, a2, t2 = normal(artista), normal(titulo), normal(art2), normal(tit2)
    if not t or not t2:
        return False
    titulo_ok = t == t2 or t in t2 or t2 in t
    artista_ok = not a or (a2 and (a in a2 or a2 in a or a.split()[0] in a2.split()))
    # Nada de remixes, en vivo ni karaokes si no se pidieron
    extra = re.search(r"\b(remix|live|en vivo|karaoke|cover|instrumental|sped up|slowed)\b", (tit2 or "").lower())
    pedido = re.search(r"\b(remix|live|en vivo|karaoke|cover|instrumental|sped up|slowed)\b", (titulo or "").lower())
    return titulo_ok and artista_ok and not (extra and not pedido)


def partir(nombre):
    if " - " in nombre:
        a, t = nombre.split(" - ", 1)
        return a.strip(), t.strip()
    return "", nombre.strip()


# ---------- Nombre de un enlace ----------
def nombre_de(url):
    host = urllib.parse.urlparse(url).hostname or ""
    if "youtube.com" in host or "youtu.be" in host:
        u = url.replace("music.youtube.com", "www.youtube.com")
        d = json.loads(pedir("https://www.youtube.com/oembed?format=json&url=" + urllib.parse.quote(u, safe="")))
        titulo, autor = d.get("title", ""), re.sub(r"\s*-\s*Topic$", "", d.get("author_name", "")).replace("VEVO", "").strip()
        titulo = re.sub(r"(?i)\s*[\(\[](official[^)\]]*|video[^)\]]*|audio[^)\]]*|letra|lyrics?|visualizer)[\)\]]", "", titulo).strip()
        if " - " in titulo:
            return titulo
        return f"{autor} - {titulo}" if autor else titulo
    if "spotify.com" in host:
        html = pedir(url)
        m = re.search(r"<title>(.*?)</title>", html, re.S)
        if m:
            # «Canción - song and lyrics by Artista | Spotify»
            t = m.group(1)
            m2 = re.match(r"\s*(.+?) - (?:song|canción).*? (?:by|de) (.+?) \| Spotify", t)
            if m2:
                return f"{m2.group(2).split(',')[0].strip()} - {m2.group(1).strip()}"
    return None


# ---------- Etiquetas ----------
def etiquetar(ruta, titulo, artista, album=None, anio=None, portada=None):
    try:
        from mutagen.id3 import APIC, ID3, TALB, TDRC, TIT2, TPE1, ID3NoHeaderError
        try:
            tags = ID3(ruta)
        except ID3NoHeaderError:
            tags = ID3()
        tags.add(TIT2(encoding=3, text=titulo))
        tags.add(TPE1(encoding=3, text=artista))
        if album:
            tags.add(TALB(encoding=3, text=album))
        if anio:
            tags.add(TDRC(encoding=3, text=str(anio)))
        if portada:
            try:
                tags.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Portada", data=pedir(portada, binario=True)))
            except Exception:
                pass
        tags.save(ruta)
    except Exception:
        pass  # sin mutagen o formato distinto: el servidor completa los datos después


def guardar(url, carpeta, artista, titulo, ext):
    limpio = lambda s: re.sub(r'[\\/:*?"<>|]+', " ", s).strip()[:120] or "Desconocido"
    destino_dir = os.path.join(carpeta, limpio(artista))
    os.makedirs(destino_dir, exist_ok=True)
    destino = os.path.join(destino_dir, limpio(titulo) + "." + ext)
    if os.path.exists(destino):
        return destino
    datos = pedir(url, binario=True, tiempo=180)
    if len(datos) < 200_000:
        raise ValueError("archivo demasiado pequeño")
    with open(destino + ".part", "wb") as f:
        f.write(datos)
    os.replace(destino + ".part", destino)
    return destino


# ---------- Audius ----------
def audius(nombre, carpeta):
    artista, titulo = partir(nombre)
    q = urllib.parse.quote(nombre.replace(" - ", " "))
    d = json.loads(pedir(f"https://api.audius.co/v1/tracks/search?query={q}&app_name=spacemusic"))
    for t in d.get("data", [])[:10]:
        if not t.get("is_streamable", True) or t.get("is_delete"):
            continue
        a2, t2 = (t.get("user") or {}).get("name", ""), t.get("title", "")
        if not coincide(artista, titulo, a2, t2):
            continue
        url = f"https://api.audius.co/v1/tracks/{t['id']}/stream?app_name=spacemusic"
        ruta = guardar(url, carpeta, artista or a2, titulo or t2, "mp3")
        arte = (t.get("artwork") or {}).get("1000x1000") or (t.get("artwork") or {}).get("480x480")
        etiquetar(ruta, titulo or t2, artista or a2, portada=arte, anio=(t.get("release_date") or "")[:4] or None)
        return ruta
    return None


# ---------- Internet Archive ----------
def archivo(nombre, carpeta):
    artista, titulo = partir(nombre)
    q = f'title:("{titulo}") AND mediatype:(audio)' + (f' AND creator:("{artista}")' if artista else "")
    url = "https://archive.org/advancedsearch.php?" + urllib.parse.urlencode(
        {"q": q, "fl[]": ["identifier", "title", "creator"], "rows": 5, "output": "json"}, doseq=True)
    d = json.loads(pedir(url))
    for doc in d.get("response", {}).get("docs", []):
        creador = doc.get("creator", "")
        creador = creador[0] if isinstance(creador, list) else creador
        meta = json.loads(pedir(f"https://archive.org/metadata/{doc['identifier']}"))
        for f in meta.get("files", []):
            n = f.get("name", "")
            if not n.lower().endswith((".mp3", ".m4a")):
                continue
            base = os.path.splitext(os.path.basename(n))[0]
            if not coincide(artista, titulo, f.get("artist") or creador, f.get("title") or base):
                continue
            ext = n.rsplit(".", 1)[1].lower()
            ruta = guardar(f"https://archive.org/download/{doc['identifier']}/{urllib.parse.quote(n)}", carpeta,
                           artista or creador, titulo, ext)
            if ext == "mp3":
                etiquetar(ruta, titulo, artista or creador, album=f.get("album"))
            return ruta
    return None


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "nombre":
        try:
            n = nombre_de(sys.argv[2])
        except Exception:
            n = None
        if n:
            print(n)
            return 0
        return 1
    if len(sys.argv) >= 4 and sys.argv[1] == "bajar":
        nombre, carpeta = sys.argv[2], sys.argv[3]
        for fuente, f in (("Audius", audius), ("Internet Archive", archivo)):
            try:
                r = f(nombre, carpeta)
            except Exception:
                r = None
            if r:
                print(f"{fuente}: {r}")
                return 0
        return 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
