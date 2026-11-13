#!/usr/bin/env python3
"""Compila solo IosAppsController contra las librerías del servidor actual.

Produce spacemusic.jar.new conservando todos los archivos del JAR original.
No reinicia ni sustituye el servicio; desplegar-con-reversion.sh realiza ese paso.
"""
import argparse
import copy
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile

CLASE = "BOOT-INF/classes/com/bpm/music/controllers/IosAppsController.class"


def preparar(jar, staging):
    jar, staging = jar.resolve(strict=True), staging.resolve(strict=True)
    if staging.parent != jar.parent / "app":
        raise ValueError("La preparación debe quedar dentro de la carpeta app del servicio")
    fuente = staging / "IosAppsController.java"
    if fuente.is_symlink() or not fuente.is_file():
        raise ValueError("Falta el controlador que se va a compilar")
    destino = jar.with_name("spacemusic.jar.new")
    if destino.exists() or destino.is_symlink():
        raise ValueError("Ya existe otro despliegue preparado; no se sobrescribe")
    librerias, clases = staging / "lib-ios", staging / "clases-ios"
    librerias.mkdir(exist_ok=True); clases.mkdir(exist_ok=True)
    with zipfile.ZipFile(jar) as original:
        if CLASE in original.namelist():
            raise ValueError("El controlador ya existe: no se reemplaza una implementación desconocida")
        if any(n.upper().startswith("META-INF/") and n.upper().endswith((".RSA", ".SF", ".DSA")) for n in original.namelist()):
            raise ValueError("No se modifica un JAR firmado")
        for n in original.namelist():
            if n.startswith("BOOT-INF/lib/spring-") and n.endswith(".jar"):
                p = librerias / Path(n).name
                p.write_bytes(original.read(n))
        subprocess.run(["javac", "--release", "21", "-proc:none", "-parameters", "-encoding", "UTF-8",
                        "-classpath", str(librerias / "*"), "-d", str(clases), str(fuente)], check=True)
        compilada = clases / "com/bpm/music/controllers/IosAppsController.class"
        # Creación exclusiva para no pisar un despliegue de otra sesión.
        with destino.open("xb") as salida:
            with zipfile.ZipFile(salida, "w") as nuevo:
                nuevo.comment = original.comment
                for entrada in original.infolist():
                    # ZipFile.writestr modifica header_offset: nunca reutilizar el ZipInfo del original.
                    nuevo.writestr(copy.copy(entrada), original.read(entrada))
                nuevo.writestr(CLASE, compilada.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(destino) as nuevo:
            if set(nuevo.namelist()) != set(original.namelist()) | {CLASE}:
                raise ValueError("El contenido cambió más allá del controlador previsto")
            for nombre in original.namelist():
                if hashlib.sha256(nuevo.read(nombre)).digest() != hashlib.sha256(original.read(nombre)).digest():
                    raise ValueError("Se alteró una entrada original")
        destino.chmod(jar.stat().st_mode & 0o777)
        print("Preparado: una clase añadida; todas las entradas originales permanecen idénticas")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--staging", type=Path, required=True)
    args = parser.parse_args()
    preparar(args.jar, args.staging)
