#!/usr/bin/env bash
# MySQL para SpaceMusic: más memoria para la biblioteca y solo accesible desde el propio servidor.
# Uso:  sudo bash ~/spacemusic/optimizar-mysql.sh            (aplicar)
#       sudo bash ~/spacemusic/optimizar-mysql.sh --revertir (volver a como estaba)
#
# - innodb_buffer_pool_size 1G: la biblioteca, el historial y las búsquedas quedan en memoria
#   (el servidor tiene 7 GB y, tras apagar los otros servicios, le sobran unos 5).
# - bind-address 127.0.0.1: SpaceMusic se conecta por localhost; nadie de la red necesita la base de datos.
set -u
[ "$(id -u)" = 0 ] || { echo "Ejecuta con sudo: sudo bash $0"; exit 1; }
CONF=/etc/mysql/mysql.conf.d/zz-spacemusic.cnf

if [ "${1:-}" = "--revertir" ]; then
  rm -f "$CONF"
  systemctl restart mysql && echo "MySQL vuelve a su configuración anterior."
  exit 0
fi

cat > "$CONF" <<'EOF'
# SpaceMusic (deploy/optimizar-mysql.sh). Se carga después de mysqld.cnf y tiene prioridad.
[mysqld]
innodb_buffer_pool_size = 1G
innodb_log_buffer_size  = 32M
tmp_table_size          = 64M
max_heap_table_size     = 64M
bind-address            = 127.0.0.1
mysqlx-bind-address     = 127.0.0.1
EOF

if ! systemctl restart mysql; then
  echo "MySQL no arrancó con la configuración nueva: se deshace."
  rm -f "$CONF"; systemctl restart mysql; exit 1
fi
sleep 8
echo "Memoria para datos: $(mysql -NBe 'SELECT @@innodb_buffer_pool_size/1024/1024' 2>/dev/null) MB"
echo "Escucha en:"; ss -tln | grep -E ':3306|:33060' | awk '{print "  " $4}'
# SpaceMusic vuelve a conectarse sola; se comprueba igual
for i in $(seq 1 12); do
  c=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/api/auth/estado); [ "$c" = 200 ] && break; sleep 5
done
echo "SpaceMusic responde: HTTP $c"
