#!/usr/bin/env bash
# Despliega ~/spacemusic/spacemusic.jar.new con reversión automática.
# Uso (en el servidor): bash desplegar-con-reversion.sh <etiqueta>
#  1. Guarda el jar actual como spacemusic.jar.antes-<etiqueta>
#  2. Pone el nuevo y reinicia solo el servicio spacemusic
#  3. Si en 180 s no arranca sano, vuelve al anterior y lo reinicia
set -u
cd "$HOME/spacemusic"
ETQ="${1:-$(date +%Y%m%d-%H%M%S)}"
[ -f spacemusic.jar.new ] || { echo "Falta spacemusic.jar.new"; exit 1; }
cp -p spacemusic.jar "spacemusic.jar.antes-$ETQ"
mv spacemusic.jar.new spacemusic.jar
DESDE=$(date '+%Y-%m-%d %H:%M:%S')
sudo -n systemctl restart spacemusic
sano=0
for i in $(seq 1 180); do
  # (sin "| grep -q": con pipefail daba falso negativo; se guarda el registro y se busca en él)
  registro=$(journalctl -u spacemusic --since "$DESDE" --no-pager 2>/dev/null)
  case "$registro" in *"APPLICATION FAILED TO START"*) break ;; esac
  codigo=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/api/auth/estado)
  case "$registro" in
    *"Started BackendApplication"*) if [ "$codigo" != "000" ] && [ "${codigo:0:1}" != "5" ]; then sano=1; break; fi ;;
  esac
  sleep 1
done
if [ "$sano" = 1 ]; then
  echo "OK: nueva versión en marcha (respaldo: spacemusic.jar.antes-$ETQ, API $codigo)"
  exit 0
fi
echo "FALLÓ: se vuelve a la versión anterior"
journalctl -u spacemusic --since "$DESDE" --no-pager | grep -E "ERROR|Caused by|FAILED" | head -15
cp -p "spacemusic.jar.antes-$ETQ" spacemusic.jar
sudo -n systemctl restart spacemusic
exit 2
