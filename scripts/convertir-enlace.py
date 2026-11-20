"""Convierte un enlace de Apple Music, Deezer, Tidal o Amazon Music en búsquedas «Artista - Canción».

Imprime una búsqueda por línea (un álbum o una playlist de Deezer dan varias). El script `descargar` las baja con
spotdl, que trae los datos completos, la portada y la letra. Solo usa servicios públicos sin clave:
API de iTunes (Apple Music), API de Deezer y el título de la página (Tidal, Amazon Music).
"""
import html
import json
import re
import sys
import urllib.parse
import urllib.request

AGENTE = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) SpaceMusic"}


def traer(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENTE), timeout=15) as r:
        return r.read().decode("utf-8", "replace")


def apple(url):
    q = urllib.parse.urlparse(url)
    i = urllib.parse.parse_qs(q.query).get("i", [None])[0]
    ids = re.findall(r"/(\d{6,})", q.path)
    if i:  # una canción dentro de un álbum
        d = json.loads(traer(f"https://itunes.apple.com/lookup?id={i}"))
        return [f'{x["artistName"]} - {x["trackName"]}' for x in d.get("results", []) if x.get("trackName")]
    if not ids:
        return []
    d = json.loads(traer(f"https://itunes.apple.com/lookup?id={ids[-1]}&entity=song&limit=200"))
    return [f'{x["artistName"]} - {x["trackName"]}' for x in d.get("results", []) if x.get("wrapperType") == "track"]


def deezer(url):
    if "page.link" in url:  # enlace corto: se sigue la redirección
        with urllib.request.urlopen(urllib.request.Request(url, headers=AGENTE), timeout=15) as r:
            url = r.geturl()
    m = re.search(r"/(track|album|playlist)/(\d+)", url)
    if not m:
        return []
    tipo, n = m.groups()
    d = json.loads(traer(f"https://api.deezer.com/{tipo}/{n}"))
    if tipo == "track":
        return [f'{d["artist"]["name"]} - {d["title"]}'] if d.get("title") else []
    pistas = d.get("tracks", {}).get("data", [])
    return [f'{x["artist"]["name"]} - {x["title"]}' for x in pistas if x.get("title")]


def por_titulo(url):
    """Tidal y Amazon Music: el título de la página trae «Canción - Artista» o «Canción by Artista»."""
    pagina = traer(url)
    m = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]+)"', pagina) or re.search(r"<title>([^<]+)</title>", pagina)
    if not m:
        return []
    t = html.unescape(m.group(1)).strip()
    t = re.sub(r"\s*[|·]\s*(TIDAL|Amazon Music).*$", "", t, flags=re.I)
    m2 = re.match(r"(.+?)\s+(?:by|de|-|–)\s+(.+)", t)
    return [f"{m2.group(2).strip()} - {m2.group(1).strip()}"] if m2 else [t]


def main():
    url = sys.argv[1]
    host = urllib.parse.urlparse(url).netloc.lower()
    try:
        if "music.apple.com" in host:
            r = apple(url)
        elif "deezer" in host:
            r = deezer(url)
        elif "tidal.com" in host or "music.amazon." in host:
            r = por_titulo(url)
        else:
            r = []
    except Exception as e:  # sin conexión o enlace raro: el script lo anota como fallido
        print(f"error: {e}", file=sys.stderr)
        return 1
    for b in r[:200]:
        print(b.replace("\n", " ").strip())
    return 0 if r else 1


if __name__ == "__main__":
    sys.exit(main())
