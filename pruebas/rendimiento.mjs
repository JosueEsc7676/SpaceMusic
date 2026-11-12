// Mediciones reproducibles de SpaceMusic: p50/p95 por operación, separando red, servidor y base de datos
// (cabecera Server-Timing que pone el servidor) y el tiempo hasta el primer byte de audio.
//
//   SM_USUARIO=... SM_CLAVE=... node pruebas/rendimiento.mjs [http://localhost:8090] [repeticiones]
//
// Escribe un resumen en consola y un JSON en pruebas/resultados/ para comparar antes y después.
import { mkdirSync, writeFileSync } from 'node:fs';

const BASE = (process.argv[2] || 'http://localhost:8090').replace(/\/$/, '');
const N = Number(process.argv[3] || 25);
const { SM_USUARIO, SM_CLAVE } = process.env;
if (!SM_USUARIO || !SM_CLAVE) { console.error('Faltan SM_USUARIO y SM_CLAVE'); process.exit(1); }

const login = await (await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ usuario: SM_USUARIO, password: SM_CLAVE }) })).json();
if (!login.token) { console.error('No se pudo iniciar sesión'); process.exit(1); }
const H = { Authorization: 'Bearer ' + login.token };

const pct = (l, p) => { const s = [...l].sort((a, b) => a - b); return s.length ? s[Math.min(s.length - 1, Math.ceil(s.length * p) - 1)] : null; };
const r1 = (x) => (x == null ? null : Math.round(x * 10) / 10);
function servidor(h) {
  const st = h.get('server-timing') || '';
  const app = /app;dur=([\d.]+)/.exec(st), db = /db;dur=([\d.]+)/.exec(st), n = /desc="(\d+) consultas"/.exec(st);
  return { app: app ? +app[1] : null, db: db ? +db[1] : null, consultas: n ? +n[1] : null };
}

async function medir(nombre, ruta, { rango = null, veces = N } = {}) {
  const total = [], app = [], db = [], red = [], consultas = [];
  for (let i = 0; i < veces; i++) {
    const t0 = performance.now();
    const r = await fetch(BASE + ruta, { headers: rango ? { ...H, Range: rango } : H });
    // Para audio basta el primer trozo (tiempo hasta empezar a sonar)
    if (rango) { const rd = r.body.getReader(); await rd.read(); await rd.cancel(); } else await r.arrayBuffer();
    const t = performance.now() - t0;
    const s = servidor(r.headers);
    total.push(t);
    if (s.app != null) { app.push(s.app); red.push(Math.max(0, t - s.app)); }
    if (s.db != null) db.push(s.db);
    if (s.consultas != null) consultas.push(s.consultas);
    if (i === 0 && !r.ok && r.status !== 206) console.warn(`  ${nombre}: HTTP ${r.status}`);
  }
  const fila = {
    operacion: nombre, n: veces,
    totalP50: r1(pct(total, 0.5)), totalP95: r1(pct(total, 0.95)),
    servidorP50: r1(pct(app, 0.5)), servidorP95: r1(pct(app, 0.95)),
    bdP50: r1(pct(db, 0.5)), bdP95: r1(pct(db, 0.95)),
    redP50: r1(pct(red, 0.5)),
    consultas: consultas.length ? r1(consultas.reduce((a, b) => a + b, 0) / consultas.length) : null,
  };
  console.log(`${nombre.padEnd(28)} total ${fila.totalP50}/${fila.totalP95} ms · servidor ${fila.servidorP50}/${fila.servidorP95} · BD ${fila.bdP50}/${fila.bdP95} (${fila.consultas} consultas) · red ${fila.redP50}`);
  return fila;
}

// Datos reales del servidor para elegir qué pedir (siempre los mismos: primeras canciones/artistas)
const generos = await (await fetch(BASE + '/api/generos', { headers: H })).json();
const busq = await (await fetch(BASE + '/api/buscar?q=a', { headers: H })).json();
const canciones = (busq.canciones || []).slice(0, 5);
const artista = busq.artistas?.[0]?.nombre || canciones[0]?.artista;

console.log(`Midiendo ${BASE} (${N} repeticiones; p50/p95)`);
const filas = [];
filas.push(await medir('Inicio (/api/inicio)', '/api/inicio'));
filas.push(await medir('Búsqueda "a"', '/api/buscar?q=a'));
filas.push(await medir('Búsqueda "amor"', '/api/buscar?q=amor'));
filas.push(await medir('Explorar (/api/generos)', '/api/generos'));
if (generos[0]) filas.push(await medir('Página de género', '/api/generos/' + encodeURIComponent(generos[0].nombre)));
if (artista) filas.push(await medir('Página de artista', '/api/artistas/' + encodeURIComponent(artista)));
filas.push(await medir('Mix del día', '/api/mixes/diario?cantidad=30'));
if (canciones[0]) filas.push(await medir('Radio', `/api/radio/${canciones[0].id}?cantidad=20`));
// Comienzo de audio y cambio de pista: primer trozo de cada canción
for (const [i, c] of canciones.entries()) filas.push(await medir(`Audio: primer byte #${i + 1}`, `/api/canciones/${c.id}/audio`, { rango: 'bytes=0-65535', veces: Math.min(N, 10) }));

mkdirSync('pruebas/resultados', { recursive: true });
const archivo = `pruebas/resultados/rendimiento-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
writeFileSync(archivo, JSON.stringify({ base: BASE, fecha: new Date().toISOString(), repeticiones: N, filas }, null, 2));
console.log('Guardado en', archivo);
