#!/usr/bin/env bash
# Integración aislada: no red, no audio real, no claves y sin tocar la cola del usuario.
set -euo pipefail
ORIGEN=${1:?Ruta al script descargar}
RAIZ=$(mktemp -d /tmp/sm-descargar-test.XXXXXXXX)
limpiar() { case "$RAIZ" in /tmp/sm-descargar-test.*) rm -rf -- "$RAIZ" ;; esac; }
trap limpiar EXIT
python3 - "$ORIGEN" "$RAIZ" <<'PY'
from pathlib import Path
import sys
origen, raiz = Path(sys.argv[1]), Path(sys.argv[2])
s = origen.read_text().replace('\r\n', '\n').rsplit('case "${1:-}" in', 1)[0]
s = s.replace('BASE=/home/admin123/spacemusic', 'BASE=' + str(raiz / 'base'))
s = s.replace('DATOS=$HOME/.config/spacemusic', 'DATOS=' + str(raiz / 'datos'))
(raiz / 'funciones').write_text(s)
PY
# shellcheck disable=SC1090
source "$RAIZ/funciones"
mantener() { :; }
mkdir -p "$ENV"
printf '#!/bin/sh\nexit 2\n' > "$ENV/python"
chmod +x "$ENV/python"
spotify() { echo 'ERROR: no debe llamar a la API de Spotify'; return 99; }
URL=https://open.spotify.com/album/AAAAAAAAAAAAAAAAAAAAAA
codigo=0
procesar "$URL" > "$RAIZ/registro" 2>&1 || codigo=$?
test "$codigo" = 2
grep -Fxq "$URL" "$FALLIDAS"
! grep -q 'Todo descargado\|no debe llamar' "$RAIZ/registro"
echo 'OK: álbum fallido conservado, sin falso éxito ni llamada a spotdl'

encolar() { return 1; }
codigo=0
reintentar > "$RAIZ/reintento" 2>&1 || codigo=$?
test "$codigo" = 1
grep -Fxq "$URL" "$FALLIDAS"
echo 'OK: error al encolar no pierde las fallidas'

procesar() { return 2; }
printf '%s\n' "$URL" > "$COLA/prueba.txt"
trabajar > "$RAIZ/trabajador" 2>&1
test -f "$HECHAS/prueba.txt"
python3 - "$DATOS/resultados/prueba.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
assert d == {'estado': 'parcial', 'codigo': 2}, d
PY
echo 'OK: trabajo parcial conserva su estado real'
