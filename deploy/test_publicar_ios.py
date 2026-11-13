import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("publicar_ios", Path(__file__).with_name("publicar-ios.py"))
modulo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(modulo)


class PublicacionIOSTests(unittest.TestCase):
    def preparar(self, carpeta, codigo=25):
        carpeta.mkdir()
        with zipfile.ZipFile(carpeta / "SpaceMusic.ipa", "w") as z:
            z.writestr("Payload/SpaceMusic.app/Info.plist", plistlib.dumps({
                "CFBundleVersion": str(codigo), "CFBundleIdentifier": "com.bpm.spacemusic.ios"}))
        version = {"code": codigo, "sha256": hashlib.sha256((carpeta / "SpaceMusic.ipa").read_bytes()).hexdigest()}
        (carpeta / "version-ios.json").write_text(json.dumps(version), encoding="utf-8")
        for nombre in ["ios.html", "ios-update.js", "ios-update.css"]:
            (carpeta / nombre).write_text("prueba", encoding="utf-8")

    def test_publica_solo_ipa_que_corresponde_y_conserva_respaldo(self):
        with tempfile.TemporaryDirectory() as d:
            raiz = Path(d)
            staging = raiz / "nueva"
            self.preparar(staging)
            (raiz / "version-ios.json").write_text('{"code":24}', encoding="utf-8")
            modulo.publicar(raiz, staging)
            self.assertEqual(json.loads((raiz / "version-ios.json").read_text())["code"], 25)
            self.assertEqual(json.loads((raiz / "version-ios.json.anterior").read_text())["code"], 24)

    def test_ipa_alterado_no_se_publica(self):
        with tempfile.TemporaryDirectory() as d:
            raiz = Path(d); staging = raiz / "nueva"
            self.preparar(staging)
            with (staging / "SpaceMusic.ipa").open("ab") as f: f.write(b"alterado")
            with self.assertRaises(ValueError): modulo.publicar(raiz, staging)
            self.assertFalse((raiz / "version-ios.json").exists())

    def test_rechaza_numero_de_compilacion_incorrecto(self):
        with tempfile.TemporaryDirectory() as d:
            raiz = Path(d); staging = raiz / "nueva"
            self.preparar(staging)
            p = staging / "version-ios.json"
            v = json.loads(p.read_text()); v["code"] = 26
            p.write_text(json.dumps(v), encoding="utf-8")
            with self.assertRaises(ValueError): modulo.publicar(raiz, staging)

    def test_no_permite_retroceder_ni_publicar_fuera_de_app(self):
        with tempfile.TemporaryDirectory() as d:
            raiz = Path(d); staging = raiz / "nueva"
            self.preparar(staging)
            (raiz / "version-ios.json").write_text('{"code":26}', encoding="utf-8")
            with self.assertRaises(ValueError): modulo.publicar(raiz, staging)
            with self.assertRaises(ValueError): modulo.publicar(staging, raiz)


if __name__ == "__main__": unittest.main()
