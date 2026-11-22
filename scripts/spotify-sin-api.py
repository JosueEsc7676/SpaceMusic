#!/usr/bin/env python3
"""Catálogo público de Spotify → coincidencias verificables en el proveedor existente.

No obtiene audio de Spotify, no inicia sesión ni evita restricciones de acceso.
Los casos incompletos o ambiguos permanecen pendientes; no se inventan canciones.
"""
import argparse
import contextlib
import hashlib
import html.parser
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import unicodedata
import urllib.parse
import urllib.request


class CatalogoError(Exception):
    pass


class DatosPublicos(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.activo = False
        self.partes = []

    def handle_starttag(self, tag, attrs):
        self.activo = tag == 'script' and dict(attrs).get('id') == '__NEXT_DATA__'

    def handle_endtag(self, tag):
        if tag == 'script':
            self.activo = False

    def handle_data(self, data):
        if self.activo:
            self.partes.append(data)


def identificar(url):
    u = urllib.parse.urlsplit(url)
    m = re.fullmatch(r'/(?:intl-[a-z]{2}/)?(?:embed/)?(track|album|playlist)/([A-Za-z0-9]{22})/?', u.path)
    if u.scheme != 'https' or u.netloc != 'open.spotify.com' or not m or u.fragment:
        raise CatalogoError('Enlace de Spotify no válido: usa canción, álbum o playlist pública')
    return m.groups()


def segundos(valor):
    if isinstance(valor, str) and re.fullmatch(r'\d{1,3}:\d{2}', valor):
        a, b = valor.split(':')
        return int(a) * 60 + int(b)
    if isinstance(valor, (int, float)) and valor > 0:
        return valor / 1000 if valor > 10000 else valor
    return None


def leer_catalogo(url, pagina):
    tipo, identificador = identificar(url)
    lector = DatosPublicos()
    lector.feed(pagina)
    try:
        entidad = json.loads(''.join(lector.partes))['props']['pageProps']['state']['data']['entity']
    except (ValueError, KeyError, TypeError):
        raise CatalogoError('No se pudo leer el catálogo público; no se marcará como descargado') from None
    pistas = entidad.get('trackList') or ([entidad] if tipo == 'track' else [])
    total = entidad.get('trackCount', entidad.get('totalTracks', len(pistas)))
    if not isinstance(pistas, list) or not pistas or len(pistas) > 1000:
        raise CatalogoError('El enlace no muestra canciones públicas disponibles')
    if isinstance(total, int) and total > len(pistas):
        raise CatalogoError('El proveedor entregó una lista incompleta; queda pendiente')
    resultado = []
    for i, pista in enumerate(pistas, 1):
        titulo = pista.get('title') or pista.get('name')
        artista = pista.get('subtitle') or entidad.get('subtitle')
        if not isinstance(titulo, str) or not isinstance(artista, str) or not titulo.strip() or not artista.strip():
            raise CatalogoError('Faltan título o artista; se requiere revisión')
        uri = pista.get('uri') or ''
        m = re.fullmatch(r'spotify:track:([A-Za-z0-9]{22})', uri)
        clave = m[1] if m else (identificador if tipo == 'track' else f'{identificador}:{i}:' + hashlib.sha256((titulo + artista).encode()).hexdigest()[:16])
        resultado.append(dict(id=clave, titulo=titulo.strip(), artista=artista.strip(),
                              album=entidad.get('name') if tipo == 'album' else None,
                              artistaAlbum=entidad.get('subtitle') if tipo == 'album' else None,
                              numero=i if tipo == 'album' else None,
                              explicita=pista.get('isExplicit'),
                              duracion=segundos(pista.get('duration') or pista.get('durationMs'))))
    return resultado


def catalogo(url):
    tipo, identificador = identificar(url)
    req = urllib.request.Request(f'https://open.spotify.com/embed/{tipo}/{identificador}',
                                 headers={'User-Agent': 'SpaceMusic/3.19 (catalogo publico)'})
    with urllib.request.urlopen(req, timeout=20) as r:
        if urllib.parse.urlsplit(r.geturl()).hostname != 'open.spotify.com':
            raise CatalogoError('Redirección inesperada del proveedor')
        pagina = r.read(3_000_001)
    if len(pagina) > 3_000_000:
        raise CatalogoError('Respuesta de catálogo demasiado grande')
    return leer_catalogo(url, pagina.decode('utf-8', 'replace'))


def normal(texto):
    texto = ''.join(c for c in unicodedata.normalize('NFKD', texto.lower()) if not unicodedata.combining(c))
    texto = re.sub(r'\((?:official (?:audio|video)|lyrics?|letra)\)', '', texto)
    texto = re.split(r'\s+(?:feat\.?|ft\.?|featuring)\s+', texto)[0]
    return re.sub(r'[^a-z0-9]+', ' ', texto).strip()


def version(texto):
    texto = ''.join(c for c in unicodedata.normalize('NFKD', texto.lower()) if not unicodedata.combining(c))
    return set(re.findall(r'\b(live|vivo|remix|cover|acoustic|acustico|instrumental|sped|slowed|karaoke|edit)\b', texto))


def coincidencia(pista, candidatos):
    aceptados = {}
    for c in candidatos:
        video = c.get('videoId', '')
        artistas = [normal(a.get('name', '')) for a in c.get('artists', [])]
        duracion = segundos(c.get('duration')) or c.get('duration_seconds')
        if not re.fullmatch(r'[A-Za-z0-9_-]{11}', video) or normal(c.get('title', '')) != normal(pista['titulo']):
            continue
        if version(c.get('title', '')) != version(pista['titulo']):
            continue
        esperados = {normal(a) for a in re.split(r',\s*|\s+&\s+|\s+feat\.?\s+', pista['artista']) if normal(a)}
        presentes = set(artistas)
        invitados = re.search(r'(?i)(?:feat\.?|ft\.?|featuring)\s+([^\)\]]+)', c.get('title', ''))
        if invitados:
            presentes.update(normal(a) for a in re.split(r',\s*|\s+&\s+', invitados[1]))
        banda_exacta = len(artistas) == 1 and artistas[0] == normal(pista['artista']) and not invitados
        if not banda_exacta and esperados != presentes:
            continue
        if isinstance(pista.get('explicita'), bool) and pista['explicita'] != c.get('isExplicit'):
            continue
        if not pista.get('duracion') or not duracion or abs(pista['duracion'] - duracion) > 10:
            continue
        aceptados[video] = c
    if pista.get('album'):
        mismo_album = {k: c for k, c in aceptados.items() if normal((c.get('album') or {}).get('name', '')) == normal(pista['album'])}
        if mismo_album:
            aceptados = mismo_album
    # Dos grabaciones distintas igualmente compatibles requieren revisión.
    return next(iter(aceptados)) if len(aceptados) == 1 else None


def guardar_json(ruta, datos):
    tmp = ruta.with_suffix('.tmp')
    with tmp.open('w', encoding='utf-8') as f:
        json.dump(datos, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, ruta)


def componente(texto):
    return re.sub(r'[\\/:*?"<>|\x00-\x1f]', '_', texto).strip(' .')[:100] or 'Sin nombre'


def huella(ruta):
    h = hashlib.sha256()
    with ruta.open('rb') as f:
        for bloque in iter(lambda: f.read(1024 * 1024), b''):
            h.update(bloque)
    return h.hexdigest()


def comprobar(ruta, esperada, profunda=False):
    if not ruta.is_file() or ruta.stat().st_size < 1024:
        return False
    try:
        r = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries',
                            'stream=codec_name:format=duration', '-of', 'json', str(ruta)],
                           capture_output=True, timeout=20, check=True)
        d = json.loads(r.stdout)
        valida = bool(d.get('streams')) and abs(float(d['format']['duration']) - esperada) <= 10
        if valida and profunda:
            revision = subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-xerror', '-threads', '1', '-i', str(ruta),
                                       '-map', '0:a:0', '-f', 'null', '-'], stdout=subprocess.DEVNULL,
                                      stderr=subprocess.PIPE, timeout=60)
            return revision.returncode == 0
        return valida
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        return False


def crear_buscador():
    import requests
    from ytmusicapi import YTMusic
    class SesionAcotada(requests.Session):
        def request(self, *args, **kwargs):
            kwargs.setdefault('timeout', (5, 15))
            return super().request(*args, **kwargs)
    return YTMusic(requests_session=SesionAcotada())


def bajar(url, destino, datos):
    import fcntl  # servidor Linux: comparte el bloqueo incluso entre procesos separados
    from mutagen.mp4 import MP4
    destino, datos = destino.resolve(), datos.resolve()
    datos.mkdir(parents=True, exist_ok=True)
    with (datos / '.spotify-catalogo.lock').open('w') as bloqueo:
        fcntl.flock(bloqueo, fcntl.LOCK_EX)
        archivo = datos / 'spotify-catalogo-progreso.json'
        estado = json.loads(archivo.read_text(encoding='utf-8')) if archivo.exists() else {}
        pistas = catalogo(url)
        ytm = crear_buscador()
        pendientes = 0
        for i, p in enumerate(pistas, 1):
            aviso = datos / 'audio-activo'
            while aviso.exists():
                try:
                    if time.time() - aviso.stat().st_mtime > 8:
                        break
                except FileNotFoundError:
                    break
                time.sleep(2)
            print(f"  Canción {i}/{len(pistas)}: {p['artista']} — {p['titulo']}", flush=True)
            anterior = estado.get(p['id'], {})
            ruta = Path(anterior.get('archivo', '')).resolve()
            if p.get('duracion') and ruta.is_relative_to(destino) and comprobar(ruta, p['duracion']) and anterior.get('sha256') == huella(ruta):
                anterior['estado'] = 'completada'; guardar_json(archivo, estado)
                print('  [OK] Ya descargada y comprobada', flush=True)
                continue
            estado[p['id']] = {'estado': 'pendiente', 'titulo': p['titulo'], 'artista': p['artista']}
            guardar_json(archivo, estado)
            try:
                if not p.get('duracion'):
                    raise CatalogoError('Falta duración verificable: requiere revisión')
                resultados = ytm.search(p['artista'] + ' ' + p['titulo'], filter='songs', limit=8)
                time.sleep(0.8)
                video = coincidencia(p, resultados)
                if not video:
                    raise CatalogoError('Sin coincidencia única de título, artista, versión y duración')
                carpeta = destino / componente(p.get('artistaAlbum') or p['artista']) / componente(p.get('album') or 'Canciones')
                if not carpeta.resolve().is_relative_to(destino):
                    raise CatalogoError('Ruta de destino fuera de la biblioteca')
                carpeta.mkdir(parents=True, exist_ok=True)
                nombre = (f"{p['numero']:02d} - " if p.get('numero') else '') + componente(p['titulo']) + '.m4a'
                final = carpeta / nombre
                if final.exists():
                    raise CatalogoError('Existe un archivo sin historial verificable; no se sobrescribe')
                etapa = datos / 'spotify-parciales' / hashlib.sha256(p['id'].encode()).hexdigest()
                etapa.mkdir(parents=True, exist_ok=True)
                if shutil.disk_usage(etapa).free < 400 * 1024 * 1024:
                    raise CatalogoError('Espacio insuficiente; se conserva el progreso')
                with contextlib.nullcontext(str(etapa)) as temporal:
                    parcial = Path(temporal) / 'audio.m4a'
                    ejecutable = str(Path(os.sys.executable).with_name('yt-dlp'))
                    cmd = [ejecutable, '--no-playlist', '--continue', '--retries', '2', '--fragment-retries', '2',
                           '--socket-timeout', '20', '--concurrent-fragments', '1', '--max-filesize', '300M',
                           '-f', 'bestaudio[ext=m4a]/bestaudio', '-x', '--audio-format', 'm4a',
                           '-o', str(Path(temporal) / 'audio.%(ext)s'), 'https://music.youtube.com/watch?v=' + video]
                    resultado = subprocess.run(cmd, capture_output=True, timeout=240)
                    if resultado.returncode != 0 or not comprobar(parcial, p['duracion'], profunda=True):
                        raise CatalogoError('El proveedor no entregó audio completo; queda pendiente, sin evitar restricciones')
                    tags = MP4(parcial)
                    tags['\xa9nam'] = [p['titulo']]; tags['\xa9ART'] = [p['artista']]
                    if p.get('album'): tags['\xa9alb'] = [p['album']]
                    if p.get('artistaAlbum'): tags['aART'] = [p['artistaAlbum']]
                    if p.get('numero'): tags['trkn'] = [(p['numero'], len(pistas))]
                    tags.save()
                    firma = huella(parcial)
                    estado[p['id']] = {'estado': 'verificada', 'archivo': str(final), 'sha256': firma, 'videoId': video}
                    guardar_json(archivo, estado)
                    with tempfile.NamedTemporaryFile(prefix='.sm-', suffix='.part', dir=carpeta, delete=False) as salida:
                        preparado = Path(salida.name)
                        with parcial.open('rb') as entrada: shutil.copyfileobj(entrada, salida)
                        salida.flush(); os.fsync(salida.fileno())
                    try: os.replace(preparado, final)
                    finally: preparado.unlink(missing_ok=True)
                    parcial.unlink(missing_ok=True)
                estado[p['id']] = {'estado': 'completada', 'archivo': str(final), 'videoId': video,
                                   'fecha': int(time.time()), 'sha256': huella(final)}
                print('  [OK] Descargada, etiquetada y comprobada', flush=True)
            except Exception as e:
                pendientes += 1
                estado[p['id']]['estado'] = 'pendiente'
                estado[p['id']]['motivo'] = str(e)[:250] if isinstance(e, CatalogoError) else type(e).__name__
                print('  Pendiente: ' + estado[p['id']]['motivo'], flush=True)
                if not isinstance(e, CatalogoError) and any(c in str(e).lower() for c in ['429', '403', 'authentication']):
                    pendientes += len(pistas) - i
                    guardar_json(archivo, estado)
                    print('  El proveedor limita el acceso. Se conserva lo pendiente; no se insiste.', flush=True)
                    break
            guardar_json(archivo, estado)
        print(f'  Resultado: {len(pistas) - pendientes}/{len(pistas)} comprobadas; {pendientes} pendientes', flush=True)
        return 2 if pendientes else 0


def main():
    for salida in (os.sys.stdout, os.sys.stderr):
        if hasattr(salida, 'reconfigure'): salida.reconfigure(errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('accion', choices=['plan', 'bajar'])
    parser.add_argument('url')
    parser.add_argument('destino', nargs='?', type=Path)
    parser.add_argument('datos', nargs='?', type=Path)
    a = parser.parse_args()
    try:
        if a.accion == 'plan':
            print(json.dumps(catalogo(a.url), ensure_ascii=False))
            return 0
        if a.destino is None or a.datos is None:
            parser.error('bajar requiere destino y datos')
        return bajar(a.url, a.destino, a.datos)
    except Exception as e:
        print('Catálogo pendiente: ' + (str(e) if isinstance(e, CatalogoError) else type(e).__name__), file=os.sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
