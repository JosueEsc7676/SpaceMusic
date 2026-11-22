import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('catalogo', Path(__file__).with_name('spotify-sin-api.py'))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
URL = 'https://open.spotify.com/album/' + 'A' * 22


def pagina(entidad):
    return '<script id="__NEXT_DATA__">' + json.dumps({'props': {'pageProps': {'state': {'data': {'entity': entidad}}}}}) + '</script>'


class CatalogoTest(unittest.TestCase):
    def test_album_conserva_orden_album_artista_duracion(self):
        tracks = [{'title': 'Canción ' + str(i), 'subtitle': 'Artista', 'duration': 180000,
                   'uri': 'spotify:track:' + str(i).zfill(22)} for i in range(1, 13)]
        r = mod.leer_catalogo(URL, pagina({'name': 'Álbum', 'subtitle': 'Artista', 'trackCount': 12, 'trackList': tracks}))
        self.assertEqual(len(r), 12)
        self.assertEqual(r[-1]['numero'], 12)
        self.assertEqual(r[0]['album'], 'Álbum')
        self.assertEqual(r[0]['duracion'], 180)

    def test_no_acepta_listas_incompletas_o_sin_metadatos(self):
        for entidad in [{'trackCount': 12, 'trackList': [{'title': 'Una', 'subtitle': 'Artista'}]},
                        {'trackList': [{'title': 'Sin artista'}]}, {}]:
            with self.assertRaises(mod.CatalogoError):
                mod.leer_catalogo(URL, pagina(entidad))

    def test_playlist_no_se_confunde_con_album(self):
        r = mod.leer_catalogo(URL.replace('album/', 'playlist/'), pagina({'name': 'Lista', 'trackList': [{'title': 'Una', 'subtitle': 'Artista', 'duration': 180000}]}))
        self.assertIsNone(r[0]['album'])
        self.assertIsNone(r[0]['numero'])

    def test_hosts_ids_y_argumentos_peligrosos_rechazados(self):
        for url in ['http://open.spotify.com/album/' + 'A'*22, URL.replace('open.spotify.com', 'open.spotify.com.evil.test'),
                    URL.replace('album/', 'album/../'), URL.replace('album/', 'artist/'), '--output /etc/passwd']:
            with self.assertRaises(mod.CatalogoError): mod.identificar(url)
        self.assertEqual(mod.identificar(URL + '?si=publico')[0], 'album')

    def test_solo_coincidencia_unica_artista_titulo_duracion_version(self):
        pista = dict(titulo='Tema', artista='Artista', duracion=180)
        buena = dict(videoId='a'*11, title='Tema', artists=[dict(name='Artista')], duration='3:00')
        self.assertEqual(mod.coincidencia(pista, [buena]), 'a'*11)
        for cambio in [dict(title='Tema (Live)'), dict(title='Tema (Remix)'), dict(artists=[dict(name='Otro')]), dict(duration='4:00')]:
            self.assertIsNone(mod.coincidencia(pista, [{**buena, **cambio}]))
        self.assertIsNone(mod.coincidencia(pista, [buena, {**buena, 'videoId': 'b'*11}]))
        self.assertIsNone(mod.coincidencia({**pista, 'duracion': None}, [buena]))

    def test_integridad_no_acepta_fragmentos(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'audio.m4a'; p.write_bytes(b'x'*2048)
            with patch.object(mod.subprocess, 'run') as run:
                run.return_value.stdout = json.dumps({'streams': [{'codec_name': 'aac'}], 'format': {'duration': '30'}}).encode()
                self.assertFalse(mod.comprobar(p, 180))
                run.return_value.stdout = json.dumps({'streams': [{'codec_name': 'aac'}], 'format': {'duration': '180'}}).encode()
                self.assertTrue(mod.comprobar(p, 180))

    def test_checkpoint_atomico_y_componentes_no_salen_de_destino(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'progreso.json'
            mod.guardar_json(p, {'id': {'estado': 'pendiente'}})
            self.assertEqual(json.loads(p.read_text())['id']['estado'], 'pendiente')
            self.assertFalse(p.with_suffix('.tmp').exists())
        self.assertNotIn('/', mod.componente('../../Artista'))
        self.assertNotEqual(mod.componente('..'), '..')

    def test_no_confunde_version_limpia_ni_omite_invitados(self):
        p = dict(titulo='Tema (feat. Invitado)', artista='Artista, Invitado', duracion=180, explicita=True)
        c = dict(videoId='a'*11, title='Tema (feat. Invitado)', artists=[dict(name='Artista')], duration='3:00', isExplicit=True)
        self.assertEqual(mod.coincidencia(p, [c]), 'a'*11)
        self.assertIsNone(mod.coincidencia(p, [{**c, 'isExplicit': False}]))
        self.assertIsNone(mod.coincidencia(p, [{**c, 'title': 'Tema (feat. Otro)'}]))
        self.assertIsNone(mod.coincidencia(p, [{**c, 'title': 'Tema (feat. Invitado) Live'}]))

    def test_descarga_comprobada_se_reanuda_sin_repetir_y_fallo_no_se_publica(self):
        pista = dict(id='prueba', titulo='Tema', artista='Artista', album='Album', artistaAlbum='Artista', numero=1, duracion=180)
        candidato = dict(videoId='a'*11, title='Tema', artists=[dict(name='Artista')], duration='3:00')
        buscador = types.SimpleNamespace(search=lambda *a, **kw: [candidato])
        class Tags(dict):
            def __init__(self, *a): super().__init__()
            def save(self): pass
        fake_mp4 = types.SimpleNamespace(MP4=Tags)
        with tempfile.TemporaryDirectory() as d, patch.dict('sys.modules', {'fcntl': types.SimpleNamespace(flock=lambda *a: None, LOCK_EX=1), 'mutagen': types.ModuleType('mutagen'), 'mutagen.mp4': fake_mp4}), patch.object(mod, 'catalogo', return_value=[pista]), patch.object(mod, 'crear_buscador', return_value=buscador), patch.object(mod.time, 'sleep'):
            destino = Path(d)/'musica'; destino.mkdir(); datos = Path(d)/'datos'
            def descargar(cmd, **kw):
                patron = cmd[cmd.index('-o')+1]
                Path(patron.replace('%(ext)s', 'm4a')).write_bytes(b'a'*2048)
                return types.SimpleNamespace(returncode=0)
            with patch.object(mod.subprocess, 'run', side_effect=descargar) as proceso, patch.object(mod, 'comprobar', side_effect=lambda p, dur, **kw: p.is_file() and p.stat().st_size >= 1024):
                self.assertEqual(mod.bajar(URL, destino, datos), 0)
                self.assertEqual(proceso.call_count, 1)
                self.assertEqual(mod.bajar(URL, destino, datos), 0)
                self.assertEqual(proceso.call_count, 1)
                estado = json.loads((datos/'spotify-catalogo-progreso.json').read_text())
                self.assertEqual(estado['prueba']['estado'], 'completada')
                self.assertTrue((destino/'Artista'/'Album'/'01 - Tema.m4a').is_file())
            pista['id'] = 'fallida'; pista['titulo'] = 'Otra'
            buscador.search = lambda *a, **kw: [{**candidato, 'title': 'Otra'}]
            with patch.object(mod.subprocess, 'run', return_value=types.SimpleNamespace(returncode=1)), patch.object(mod, 'comprobar', return_value=False):
                self.assertEqual(mod.bajar(URL, destino, datos), 2)
                self.assertFalse((destino/'Artista'/'Album'/'01 - Otra.m4a').exists())
                estado = json.loads((datos/'spotify-catalogo-progreso.json').read_text())
                self.assertEqual(estado['fallida']['estado'], 'pendiente')


if __name__ == '__main__':
    unittest.main()
