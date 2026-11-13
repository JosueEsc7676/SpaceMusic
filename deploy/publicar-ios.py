#!/usr/bin/env python3
"""Publica un IPA ya compilado sin reiniciar Java ni reemplazar la API.

Subir primero los cinco archivos a una subcarpeta de app/ y ejecutar en el servidor:
python3 publicar-ios.py --carpeta ~/spacemusic/app --staging ~/spacemusic/app/ios-nueva
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import zipfile


def verificar(carpeta):
    version = json.loads((carpeta / "version-ios.json").read_text(encoding="utf-8"))
    ipa = carpeta / "SpaceMusic.ipa"
    with ipa.open("rb") as archivo:
        hash_ipa = hashlib.sha256()
        for bloque in iter(lambda: archivo.read(1024 * 1024), b""):
            hash_ipa.update(bloque)
        sha = hash_ipa.hexdigest()
    if sha != version["sha256"]:
        raise ValueError("La huella del IPA no coincide con la versión")
    with zipfile.ZipFile(ipa) as paquete:
        datos = plistlib.loads(paquete.read("Payload/SpaceMusic.app/Info.plist"))
        if str(datos["CFBundleVersion"]) != str(version["code"]):
            raise ValueError("El número del IPA no coincide con el manifiesto")
        if datos["CFBundleIdentifier"] != "com.bpm.spacemusic.ios":
            raise ValueError("El IPA pertenece a otra aplicación")
        if paquete.testzip() is not None:
            raise ValueError("El IPA está dañado")
    return version


def publicar(destino, staging):
    destino, staging = destino.resolve(strict=True), staging.resolve(strict=True)
    if staging.parent != destino or staging == destino:
        raise ValueError("La carpeta de preparación debe estar directamente dentro de app/")
    version = verificar(staging)
    anterior = destino / "version-ios.json"
    if anterior.exists():
        previa = json.loads(anterior.read_text(encoding="utf-8"))
        if int(previa["code"]) > int(version["code"]):
            raise ValueError("No se permite sustituir una versión más reciente por una antigua")
    nombres = ["SpaceMusic.ipa", "ios-update.css", "ios-update.js", "ios.html", "version-ios.json"]
    for nombre in nombres:
        origen = staging / nombre
        if origen.is_symlink() or not origen.is_file():
            raise ValueError("Falta un archivo regular: " + nombre)
    # Enlace por número: el manifiesto nunca apunta a un IPA que cambia durante la descarga.
    versionado = destino / ("SpaceMusic-" + str(int(version["code"])) + ".ipa")
    if versionado.is_symlink():
        raise ValueError("No se sobrescriben enlaces simbólicos")
    if versionado.exists():
        with versionado.open("rb") as f:
            existente = hashlib.sha256()
            for bloque in iter(lambda: f.read(1024 * 1024), b""): existente.update(bloque)
        if existente.hexdigest() != version["sha256"]:
            raise ValueError("Ese número ya fue publicado con otro archivo")
    else:
        shutil.copy2(staging / "SpaceMusic.ipa", staging / "versionado.ipa")
        os.replace(staging / "versionado.ipa", versionado)
        versionado.chmod(0o644)
    # El manifiesto se publica al final: nunca anuncia un IPA que todavía no está disponible.
    for nombre in nombres:
        actual = destino / nombre
        if actual.is_symlink():
            raise ValueError("No se sobrescriben enlaces simbólicos")
        if actual.exists():
            respaldo = destino / (nombre + ".anterior")
            if respaldo.is_symlink():
                raise ValueError("El respaldo no puede ser un enlace simbólico")
            shutil.copy2(actual, respaldo)
        os.replace(staging / nombre, actual)
        actual.chmod(0o644)
    print(json.dumps({"publicado": version["code"], "commit": version.get("commit"), "sha256": version["sha256"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--carpeta", type=Path, required=True)
    parser.add_argument("--staging", type=Path, required=True)
    args = parser.parse_args()
    publicar(args.carpeta, args.staging)
