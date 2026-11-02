// Service worker de SpaceMusic. El servidor reemplaza __VERSION__ y __SHELL__ al arrancar.
//
// Cachés:
//   - CACHE (versión):  archivos de la interfaz. Se renuevan completos con cada versión.
//   - sm-api:           respuestas de la API (red primero; sin conexión se usa la última copia).
//   - sm-portadas:      portadas (la URL lleva la huella de la imagen: nunca cambian).
//   - sm-audio:         canciones guardadas en el dispositivo para escuchar sin conexión.
const CACHE = '__VERSION__';
const SHELL = [__SHELL__];
const API = 'sm-api';
const PORTADAS = 'sm-portadas';
const AUDIO = 'sm-audio';
const MAX_CANCIONES = 150;   // canciones guardadas como máximo (se borran las más antiguas)
const MAX_PORTADAS = 1500;
const ESPERA_RED_MS = 4000;  // pasado este tiempo sin respuesta, se usa la copia guardada
const PERMANENTES = [API, PORTADAS, AUDIO];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: 'reload' })))));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((ks) => Promise.all(ks.filter((k) => k !== CACHE && !PERMANENTES.includes(k)).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// ── Mensajes de la app ──
self.addEventListener('message', (e) => {
  const d = e.data;
  if (d === 'activar') { self.skipWaiting(); return; }
  if (d?.tipo === 'guardar-audio') e.waitUntil(guardarAudio(d.url, d.clave));
  if (d?.tipo === 'limpiar-usuario') e.waitUntil(Promise.all([caches.delete(API), caches.delete(AUDIO)]));
  if (d?.tipo === 'estado-offline') e.waitUntil(contarAudio().then((n) => e.source?.postMessage({ tipo: 'estado-offline', canciones: n })));
});

// La clave de una canción no incluye el token (?t=), que cambia con cada sesión
const claveAudio = (url) => {
  const m = /\/api\/canciones\/(\d+)\/audio/.exec(url);
  return m ? `/offline/audio/${m[1]}` : null;
};

async function guardarAudio(url, clave) {
  clave = clave || claveAudio(url);
  if (!clave) return;
  const c = await caches.open(AUDIO);
  if (await c.match(clave)) return;
  try {
    const r = await fetch(url, { credentials: 'same-origin' });
    if (!r.ok || r.status !== 200) return;
    await c.put(clave, r);
    await recortar(c, MAX_CANCIONES);
  } catch { /* sin conexión: se intentará otra vez */ }
}

async function recortar(c, max) {
  const ks = await c.keys();
  for (let i = 0; i < ks.length - max; i++) await c.delete(ks[i]);
}

async function contarAudio() {
  return (await (await caches.open(AUDIO)).keys()).length;
}

/** Respuesta parcial (206) a partir de la canción guardada, para poder adelantar sin conexión. */
async function audioGuardado(req, clave) {
  const r = await (await caches.open(AUDIO)).match(clave);
  if (!r) return null;
  const range = req.headers.get('range');
  if (!range) return r;
  const blob = await r.blob();
  const m = /bytes=(\d*)-(\d*)/.exec(range) || [];
  const total = blob.size;
  let inicio = m[1] ? Number(m[1]) : 0;
  let fin = m[2] ? Number(m[2]) : total - 1;
  if (!m[1] && m[2]) { inicio = Math.max(0, total - Number(m[2])); fin = total - 1; }
  fin = Math.min(fin, total - 1);
  if (inicio > fin) return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${total}` } });
  return new Response(blob.slice(inicio, fin + 1), {
    status: 206,
    headers: {
      'Content-Type': r.headers.get('Content-Type') || 'audio/mpeg',
      'Content-Range': `bytes ${inicio}-${fin}/${total}`,
      'Content-Length': String(fin - inicio + 1),
      'Accept-Ranges': 'bytes',
    },
  });
}

/** Red primero; si tarda demasiado o no hay conexión, la última copia guardada. */
async function redPrimero(req) {
  const c = await caches.open(API);
  const red = fetch(req).then((r) => {
    if (r.ok) c.put(req, r.clone());
    return r;
  });
  const respaldo = new Promise((res) => setTimeout(() => c.match(req).then((g) => g && res(g)), ESPERA_RED_MS));
  try {
    return await Promise.race([red, respaldo]);
  } catch {
    const g = await c.match(req);
    if (g) return g;
    return new Response(JSON.stringify({ error: 'Sin conexión', offline: true }),
      { status: 503, headers: { 'Content-Type': 'application/json' } });
  }
}

async function portada(req) {
  const c = await caches.open(PORTADAS);
  const g = await c.match(req);
  if (g) return g;
  const r = await fetch(req);
  if (r.ok && new URL(req.url).searchParams.has('v')) {
    c.put(req, r.clone()).then(() => recortar(c, MAX_PORTADAS));
  }
  return r;
}

// Datos de la API que conviene tener sin conexión (solo lectura)
const API_OFFLINE = /^\/api\/(inicio|buscar|albumes|artistas|generos|mixes|playlists|letras|canciones\/\d+$|canciones\/(recientes|top|favoritos)|usuario\/favoritos|historial|biblioteca)/;

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return;

  if (url.pathname.startsWith('/api/')) {
    const clave = claveAudio(url.pathname);
    if (clave) {
      // Canción guardada → se sirve del dispositivo; si no, de la red como siempre
      e.respondWith(audioGuardado(req, clave).then((r) => r || fetch(req)));
      return;
    }
    if (url.pathname.startsWith('/api/portada/')) { e.respondWith(portada(req)); return; }
    if (API_OFFLINE.test(url.pathname)) { e.respondWith(redPrimero(req)); return; }
    return;
  }

  if (req.mode === 'navigate' && (url.pathname === '/' || url.pathname === '/index.html')) {
    e.respondWith(caches.match('/index.html').then((r) => r || fetch(req)));
    return;
  }
  // Archivos de la interfaz: primero la copia guardada (instantáneo)
  e.respondWith(caches.match(req, { ignoreSearch: true }).then((r) => r || fetch(req)));
});
