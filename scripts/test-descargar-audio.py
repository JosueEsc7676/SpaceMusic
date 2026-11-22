#!/usr/bin/env python3
"""Prueba Linux aislada con audio generado: sin descargar canciones ni tocar la biblioteca."""
from importlib.machinery import SourceFileLoader
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

mod = SourceFileLoader('catalogo', sys.argv[1]).load_module()
from mutagen.mp4 import MP4

with tempfile.TemporaryDirectory(prefix='sm-descarga-audio-test-') as d:
    raiz = Path(d)
    fuente = raiz / 'prueba.m4a'
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
                    '-c:a', 'aac', str(fuente)], check=True, timeout=15)
    pista = dict(id='prueba-tecnica', titulo='Prueba técnica', artista='Prueba', artistaAlbum='Prueba',
                 album='Pruebas aisladas', numero=1, duracion=2)
    candidato = dict(videoId='a'*11, title=pista['titulo'], artists=[dict(name='Prueba')], duration='0:02')
    real = subprocess.run
    llamadas = []
    def ejecutar(cmd, **kw):
        if str(cmd[0]).endswith('yt-dlp'):
            llamadas.append(cmd)
            destino = Path(cmd[cmd.index('-o')+1].replace('%(ext)s', 'm4a'))
            shutil.copyfile(fuente, destino)
            return SimpleNamespace(returncode=0)
        return real(cmd, **kw)
    with patch.object(mod, 'catalogo', return_value=[pista]), patch.object(mod, 'crear_buscador', return_value=SimpleNamespace(search=lambda *a, **kw: [candidato])), patch.object(mod.subprocess, 'run', side_effect=ejecutar), patch.object(mod.time, 'sleep'):
        destino, datos = raiz/'biblioteca', raiz/'estado'
        destino.mkdir()
        assert mod.bajar('https://open.spotify.com/album/'+'A'*22, destino, datos) == 0
        final = destino/'Prueba'/'Pruebas aisladas'/'01 - Prueba técnica.m4a'
        assert mod.comprobar(final, 2, profunda=True)
        tags = MP4(final)
        assert tags['\xa9alb'] == ['Pruebas aisladas'] and tags['aART'] == ['Prueba']
        assert tags['trkn'] == [(1, 1)]
        assert mod.bajar('https://open.spotify.com/album/'+'A'*22, destino, datos) == 0
        assert len(llamadas) == 1
    print('OK: audio generado, decodificación completa, etiquetas reales y reanudación sin otra descarga')
