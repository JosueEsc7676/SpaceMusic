#!/usr/bin/env bash
# Deja el servidor solo con lo que necesita SpaceMusic. Uso:  sudo bash ~/spacemusic/optimizar-servidor.sh
# No borra nada: solo apaga y desactiva. Para volver atrás:  sudo bash ~/spacemusic/optimizar-servidor.sh --revertir
#
# Se QUEDAN: spacemusic, mysql, bgutil-pot (descargas de YouTube), tailscaled (dirección pública),
# ssh, smbd (carpeta «musica»), red (NetworkManager, wpa_supplicant, networkd, resolved), cron, chrony,
# registros, ufw, thermald (temperatura del portátil) y las actualizaciones de seguridad.
set -u
[ "$(id -u)" = 0 ] || { echo "Ejecuta con sudo: sudo bash $0"; exit 1; }

# Otros proyectos (elegidos por el dueño del servidor)
PROYECTOS="buentoque.service ia-personal.service ia-personal-backup.timer ollama.service
docker.service docker.socket containerd.service"

# Servicios del sistema que este equipo no usa
SOBRANTES="ModemManager.service fwupd.service fwupd-refresh.timer udisks2.service upower.service
multipathd.service multipathd.socket open-iscsi.service iscsid.socket open-vm-tools.service vgauth.service
apport.service apport-autoreport.timer kdump-tools.service motd-news.timer update-notifier-download.timer
update-notifier-motd.timer ubuntu-fan.service gpu-manager.service compartir-internet.service dnsmasq.service
nmbd.service ua-timer.timer"

UNIDADES="$PROYECTOS $SOBRANTES"

existe() { systemctl list-unit-files --no-legend "$1" 2>/dev/null | grep -q .; }

if [ "${1:-}" = "--revertir" ]; then
  systemctl unmask ModemManager.service udisks2.service >/dev/null 2>&1
  for u in $UNIDADES; do existe "$u" && systemctl enable --now "$u" >/dev/null 2>&1 && echo "encendido  $u"; done
  rm -f /etc/cloud/cloud-init.disabled
  echo "Listo: todo vuelve a como estaba (los contenedores de Android: cd ~/android-test && docker compose up -d)."
  exit 0
fi

antes=$(free -m | awk '/Mem:/{print $3}')
for u in $UNIDADES; do
  if existe "$u"; then
    systemctl disable --now "$u" >/dev/null 2>&1 && echo "apagado    $u" || echo "no se pudo $u"
  fi
done
# ModemManager y udisks2 se vuelven a despertar si alguien los pide: se bloquean del todo
systemctl mask ModemManager.service udisks2.service >/dev/null 2>&1
# cloud-init solo sirve para servidores en la nube
touch /etc/cloud/cloud-init.disabled
systemctl reset-failed >/dev/null 2>&1

sleep 2
despues=$(free -m | awk '/Mem:/{print $3}')
echo
echo "Memoria en uso: ${antes} MB -> ${despues} MB"
for s in spacemusic mysql bgutil-pot tailscaled ssh smbd; do
  printf '%-12s %s\n' "$s" "$(systemctl is-active $s)"
done
codigo=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/api/auth/estado)
echo "SpaceMusic responde: HTTP $codigo"
