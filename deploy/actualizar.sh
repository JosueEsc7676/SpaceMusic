#!/usr/bin/env bash
# Copia el .jar nuevo al servidor y reinicia el servicio.
# Uso (desde tu PC, en la carpeta del proyecto):
#   ./deploy/actualizar.sh usuario@ip-del-servidor
set -euo pipefail
DESTINO="${1:?Uso: ./deploy/actualizar.sh usuario@servidor}"
echo "→ Compilando…"
./mvnw -q clean package -DskipTests 2>/dev/null || mvn -q clean package -DskipTests
echo "→ Subiendo target/spacemusic.jar…"
scp target/spacemusic.jar "$DESTINO:/tmp/spacemusic.jar"
echo "→ Reiniciando el servicio…"
ssh -t "$DESTINO" 'sudo mv /tmp/spacemusic.jar /opt/spacemusic/spacemusic.jar && sudo systemctl restart spacemusic && sleep 3 && systemctl --no-pager status spacemusic | head -5'
echo "✓ Listo"
