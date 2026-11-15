// Service worker de SpaceMusic. El servidor reemplaza __VERSION__ y __SHELL__ al arrancar.
//
// Cachés:
//   - CACHE (versión):  archivos de la interfaz. Cada versión nueva copia de la anterior lo que no cambió
//                       (misma huella) y descarga solo lo nuevo.
//   - sm-api:           respuestas de la API (red primero; sin conexión se usa la última copia).
//   - sm-portadas:      portadas (la URL lleva la huella de la imagen: nunca cambian).
//   - sm-audio:         canciones guardadas en el dispositivo para escuchar sin conexión.
//   - sm-inicio:        solo el principio de la siguiente canción (datos móviles o poca memoria): empieza al instante
//                       y el resto llega de la red mientras suena.
const CACHE = '__VERSION__';
const SHELL = [__SHELL__]; // [ruta, huella del contenido]
const API = 'sm-api';
const PORTADAS = 'sm-portadas';
const AUDIO = 'sm-audio';
const DESCARGAS = 'sm-descargas'; // descargadas a mano: nunca se borran solas
const MAX_CANCIONES = 150;   // canciones guardadas como máximo (se borran las más antiguas)
const MAX_PORTADAS = 1500;
const ESPERA_RED_MS = 4000;  // pasado este tiempo sin respuesta, se usa la copia guardada
const INICIO = 'sm-inicio';
const MAX_INICIOS = 20;
const PERMANENTES = [API, PORTADAS, AUDIO, DESCARGAS, INICIO];

// ── Prioridad: la canción que suena va primero ──
// Al cambiar de canción se cancelan las descargas automáticas en curso (guardar y precargar) y las que
// esperan en la cola se saltan: así no le quitan ancho de banda a lo que el usuario quiere oír ya.
// Las descargas hechas a mano esperan a que la canción nueva arranque (como mucho unos segundos).
let generacion = 0;
const enVuelo = new Set();   // AbortController de las descargas automáticas
let prioridadHasta = 0;
function darPrioridad(ms = 8000) {
  generacion++;
  for (const c of enVuelo) c.abort();
  enVuelo.clear();
  prioridadHasta = Date.now() + ms;
}
async function esperarPrioridad() {
  while (Date.now() < prioridadHasta) await new Promise((r) => setTimeout(r, Math.min(500, prioridadHasta - Date.now())));
}

// ── Índice de lo guardado: si una canción no está en el dispositivo, su audio va directo a la red ──
// (sin pasar por el service worker: empieza antes y el navegador usa su propia caché por rangos)
let indice = null;
let cargandoIndice = null;
function recargarIndice() {
  cargandoIndice = (async () => {
    const s = new Set();
    for (const nombre of [AUDIO, DESCARGAS, INICIO]) for (const r of await (await caches.open(nombre)).keys()) s.add(new URL(r.url).pathname);
    indice = s;
  })().catch(() => { indice = null; }).finally(() => { cargandoIndice = null; });
  return cargandoIndice;
}

self.addEventListener('install', (e) => {
  e.waitUntil(instalar());
});

// Copia lo que no cambió desde la versión anterior (misma huella) y descarga solo lo nuevo
async function instalar() {
  const c = await caches.open(CACHE);
  const anteriores = [];
  for (const k of await caches.keys()) if (k !== CACHE && k.startsWith('sm-') && !PERMANENTES.includes(k)) anteriores.push(await caches.open(k));
  await Promise.all(SHELL.map(async ([u, h]) => {
    try {
      for (const vieja of anteriores) {
        const r = await vieja.match(u, { ignoreVary: true });
        if (r && r.headers.get('X-SM-Huella') === h) { await c.put(u, r); return; }
      }
      const r = await fetch(new Request(u, { cache: 'reload' }));
      if (!r.ok) return;
      const cab = new Headers(r.headers);
      cab.set('X-SM-Huella', h);
      await c.put(u, new Response(await r.blob(), { status: r.status, statusText: r.statusText, headers: cab }));
    } catch { /* si uno falla, el resto igual queda guardado y la app abre sin internet */ }
  }));
}

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((ks) => Promise.all(ks.filter((k) => k !== CACHE && !PERMANENTES.includes(k)).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// ── Mensajes de la app ──
self.addEventListener('message', (e) => {
  const d = e.data;
  if (d === 'activar') { self.skipWaiting(); return; }
  if (d?.tipo === 'guardar-audio') { const g = generacion; e.waitUntil(enCola(() => (g === generacion ? guardarAudio(d.url, d.clave) : null))); }
  if (d?.tipo === 'precargar-inicio') { const g = generacion; e.waitUntil(enCola(() => (g === generacion ? guardarInicio(d.url, d.bytes) : null))); }
  // Empezó otra canción: lo de fondo se aparta (hasta: ms que la canción nueva tiene preferencia)
  if (d?.tipo === 'prioridad') darPrioridad(d.hasta);
  if (d?.tipo === 'prioridad-fin') prioridadHasta = 0;
  // Al cerrar sesión se borran los datos de la cuenta; las canciones descargadas se conservan
  // (cada cuenta solo ve su propia lista de descargas)
  if (d?.tipo === 'limpiar-usuario') e.waitUntil(caches.delete(API));
  if (d?.tipo === 'descargar-audio') e.waitUntil(enCola(() => descargar(d.url, d.id)).then((ok) => avisar({ tipo: 'descarga', id: d.id, ok })));
  if (d?.tipo === 'quitar-descarga') e.waitUntil(caches.open(DESCARGAS).then((c) => c.delete(`/offline/audio/${d.id}`)).then(recargarIndice));
  // La copia guardada no coincide con la canción (otra duración): se borra y, si era descarga, se vuelve a bajar
  if (d?.tipo === 'audio-equivocado') e.waitUntil((async () => {
    const clave = `/offline/audio/${d.id}`;
    await (await caches.open(AUDIO)).delete(clave);
    const eraDescarga = await (await caches.open(DESCARGAS)).delete(clave);
    e.source?.postMessage({ tipo: 'audio-revisado', id: d.id });
    await (await caches.open(INICIO)).delete(clave);
    await recargarIndice();
    if (eraDescarga && d.url) await enCola(() => descargar(d.url, d.id));
  })());
  if (d?.tipo === 'estado-offline') e.waitUntil(contarAudio().then((n) => e.source?.postMessage({ tipo: 'estado-offline', canciones: n })));
  // Qué canciones se pueden escuchar sin conexión (descargadas y guardadas al escuchar)
  if (d?.tipo === 'ids-audio') e.waitUntil(idsAudio().then((ids) => e.source?.postMessage({ tipo: 'ids-audio', ids })));
});

// La clave de una canción no incluye el token (?t=), que cambia con cada sesión
const claveAudio = (url) => {
  const m = /\/api\/canciones\/(\d+)\/audio/.exec(url);
  return m ? `/offline/audio/${m[1]}` : null;
};

// Las descargas en segundo plano van de una en una: así no ocupan las conexiones que necesita
// la app (con HTTP/1.1 el navegador solo abre 6 a la vez con el servidor)
let cola = Promise.resolve();
function enCola(tarea) {
  const conTurno = () => esperarPrioridad().then(tarea);
  const p = cola.then(conTurno, conTurno);
  cola = p.catch(() => { });
  return p;
}

async function guardarAudio(url, clave) {
  clave = clave || claveAudio(url);
  if (!clave) return;
  const c = await caches.open(AUDIO);
  if (await c.match(clave)) return;
  const ctrl = new AbortController();
  enVuelo.add(ctrl);
  try {
    const r = await fetch(url, { credentials: 'same-origin', signal: ctrl.signal });
    if (!r.ok || r.status !== 200) return;
    // Se lee entera antes de guardar: si se cancela a medias no queda una copia incompleta
    const tipo = r.headers.get('Content-Type') || 'audio/mpeg';
    const blob = await r.blob();
    const total = Number(r.headers.get('Content-Length')) || 0;
    if (total && blob.size < total) return;
    await c.put(clave, new Response(blob, { headers: { 'Content-Type': tipo, 'Content-Length': String(blob.size) } }));
    await (await caches.open(INICIO)).delete(clave); // ya está entera
    await recortar(c, MAX_CANCIONES);
    await recargarIndice();
  } catch { /* sin conexión o cancelada (empezó otra canción): se intentará otra vez */ }
  finally { enVuelo.delete(ctrl); }
}

/** Solo el principio de la canción (petición por rangos), para que la siguiente empiece sin esperar a la red. */
async function guardarInicio(url, bytes = 640 * 1024) {
  const clave = claveAudio(url);
  if (!clave) return;
  const ligera = /[?&]calidad=ligera/.test(url);
  const c = await caches.open(INICIO);
  const previa = await c.match(clave);
  if (previa && previa.headers.get('X-SM-Ligera') === String(ligera)) return;
  if (await (await caches.open(AUDIO)).match(clave) || await (await caches.open(DESCARGAS)).match(clave)) return;
  const ctrl = new AbortController();
  enVuelo.add(ctrl);
  try {
    const r = await fetch(url, { credentials: 'same-origin', signal: ctrl.signal, headers: { Range: `bytes=0-${Math.max(65536, bytes) - 1}` } });
    const rango = /bytes 0-(\d+)\/(\d+)/.exec(r.headers.get('Content-Range') || '');
    if (r.status !== 206 || !rango) return; // el servidor no da rangos: no se guarda nada
    const blob = await r.blob();
    if (blob.size !== Number(rango[1]) + 1) return;
    await c.put(clave, new Response(blob, { headers: {
      'Content-Type': r.headers.get('Content-Type') || 'audio/mpeg', 'X-SM-Total': rango[2], 'X-SM-Ligera': String(ligera),
      'X-SM-Fecha': String(Date.now()), 'X-SM-Validador': r.headers.get('ETag') || r.headers.get('Last-Modified') || '' } }));
    await recortar(c, MAX_INICIOS);
    await recargarIndice();
  } catch { /* cancelada o sin conexión */ }
  finally { enVuelo.delete(ctrl); }
}

/** El principio guardado responde a la primera petición (bytes=0-); el reproductor pide el resto a la red. */
async function inicioGuardado(req, clave) {
  const range = req.headers.get('range') || 'bytes=0-';
  const pedido = /^bytes=0-(\d*)$/.exec(range);
  if (!pedido) return null;
  const r = await (await caches.open(INICIO)).match(clave);
  if (!r) return null;
  const ligera = new URL(req.url).searchParams.get('calidad') === 'ligera';
  // De otra calidad o de hace más de un día (el archivo pudo cambiar): no se usa
  if (r.headers.get('X-SM-Ligera') !== String(ligera) || Date.now() - Number(r.headers.get('X-SM-Fecha') || 0) > 86400000) return null;
  if (req.headers.has('if-range') && req.headers.get('if-range') !== r.headers.get('X-SM-Validador')) return null;
  const total = Number(r.headers.get('X-SM-Total'));
  const blob = await r.blob();
  if (!total || !blob.size) return null;
  const fin = pedido[1] ? Math.min(Number(pedido[1]), blob.size - 1) : blob.size - 1;
  if (!Number.isSafeInteger(fin) || fin < 0) return null;
  return new Response(blob.slice(0, fin + 1), { status: 206, headers: {
    'Content-Type': r.headers.get('Content-Type') || 'audio/mpeg',
    'Content-Range': `bytes 0-${fin}/${total}`,
    'Content-Length': String(fin + 1),
    'Accept-Ranges': 'bytes',
  } });
}

async function avisar(m) {
  for (const c of await self.clients.matchAll()) c.postMessage(m);
}

/** Descarga permanente: la canción completa queda en el dispositivo hasta que se quite. */
async function descargar(url, id) {
  const clave = `/offline/audio/${id}`;
  const c = await caches.open(DESCARGAS);
  if (await c.match(clave)) return true;
  // Si ya estaba guardada automáticamente, se pasa a descargas sin volver a bajarla
  const auto = await (await caches.open(AUDIO)).match(clave);
  if (auto) { await c.put(clave, auto); await recargarIndice(); return true; }
  for (let intento = 1; intento <= 3; intento++) {
    try {
      const r = await fetch(url, { credentials: 'same-origin' });
      if (!r.ok || r.status !== 200 || !r.body) throw new Error('respuesta');
      // Se lee por partes para avisar el progreso (círculo en la canción) y comprobar que llegó completa
      const total = Number(r.headers.get('Content-Length')) || 0;
      const lector = r.body.getReader();
      const partes = [];
      let n = 0, ultimo = 0;
      for (;;) {
        const { done, value } = await lector.read();
        if (done) break;
        partes.push(value);
        n += value.length;
        if (total && Date.now() - ultimo > 350) { ultimo = Date.now(); avisar({ tipo: 'progreso', id, pct: n / total }); }
      }
      if (!n || (total && n < total)) throw new Error('incompleta');
      const tipo = r.headers.get('Content-Type') || 'audio/mpeg';
      const blob = new Blob(partes, { type: tipo });
      await c.put(clave, new Response(blob, { headers: { 'Content-Type': tipo, 'Content-Length': String(blob.size) } }));
      await (await caches.open(INICIO)).delete(clave);
      await recargarIndice();
      return true;
    } catch { /* se reintenta */ }
    await new Promise((res) => setTimeout(res, intento * 2000));
  }
  return false;
}

async function recortar(c, max) {
  const ks = await c.keys();
  for (let i = 0; i < ks.length - max; i++) await c.delete(ks[i]);
}

async function idsAudio() {
  const ids = new Set();
  for (const nombre of [AUDIO, DESCARGAS]) {
    for (const r of await (await caches.open(nombre)).keys()) {
      const m = /\/offline\/audio\/(\d+)/.exec(r.url);
      if (m) ids.add(Number(m[1]));
    }
  }
  return [...ids];
}

/** Copia guardada marcada como tal: así la app sabe que está sin conexión. */
function comoCopia(r, motivo = 'sin-red') {
  const h = new Headers(r.headers);
  h.set('X-SM-Copia', motivo); // 'sin-red': no hubo conexión · 'lenta': la red tardó (sigue habiendo conexión)
  return new Response(r.body, { status: r.status, statusText: r.statusText, headers: h });
}

async function contarAudio() {
  return (await (await caches.open(AUDIO)).keys()).length;
}

/** Respuesta parcial (206) a partir de la canción guardada, para poder adelantar sin conexión. */
async function audioGuardado(req, clave) {
  const r = (await (await caches.open(DESCARGAS)).match(clave)) || (await (await caches.open(AUDIO)).match(clave));
  if (!r) return null;
  const range = req.headers.get('range');
  if (!range) return r;
  // No leer la canción entera antes de responder: CacheStorage entrega un flujo.
  // Las copias antiguas sin tamaño se migran al leerlas, conservando compatibilidad offline.
  const declarado = r.headers.get('Content-Length');
  if (declarado !== null && Number.isSafeInteger(Number(declarado)) && Number(declarado) >= 0 && r.body) {
    return rangoGuardado(r, range, Number(declarado));
  }
  const blob = await r.blob();
  return rangoGuardado(new Response(blob, { headers: r.headers }), range, blob.size);
}

/** Un rango del flujo guardado: entrega el primer bloque sin esperar al final del archivo. */
function rangoGuardado(r, range, total) {
  const m = /^bytes=(\d*)-(\d*)$/.exec(range);
  if (!m || (!m[1] && !m[2])) return null; // rangos múltiples: los resuelve el servidor
  let inicio = m[1] ? Number(m[1]) : Math.max(0, total - Number(m[2]));
  let fin = m[1] && m[2] ? Math.min(Number(m[2]), total - 1) : total - 1;
  if (!Number.isSafeInteger(inicio) || !Number.isSafeInteger(fin) || inicio > fin || inicio >= total) {
    r.body?.cancel().catch(() => {});
    return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${total}` } });
  }
  const lector = r.body.getReader();
  let posicion = 0;
  const flujo = new ReadableStream({
    async pull(c) {
      try {
        for (;;) {
          const { done, value } = await lector.read();
          if (done) { c.error(new Error('Copia de audio incompleta')); return; }
          const desde = Math.max(0, inicio - posicion);
          const hasta = Math.min(value.byteLength, fin + 1 - posicion);
          posicion += value.byteLength;
          if (hasta > desde) c.enqueue(value.subarray(desde, hasta));
          if (posicion > fin) { c.close(); await lector.cancel(); return; }
          if (hasta > desde) return;
        }
      } catch (e) { c.error(e); }
    },
    cancel(motivo) { return lector.cancel(motivo); },
  });
  return new Response(flujo, {
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
  const respaldo = new Promise((res) => setTimeout(() => c.match(req, { ignoreVary: true }).then((g) => g && res(comoCopia(g, 'lenta'))), ESPERA_RED_MS));
  try {
    return await Promise.race([red, respaldo]);
  } catch {
    const g = await c.match(req, { ignoreVary: true });
    if (g) return comoCopia(g);
    return new Response(JSON.stringify({ error: 'Sin conexión', offline: true }),
      { status: 503, headers: { 'Content-Type': 'application/json' } });
  }
}

async function portada(req) {
  const c = await caches.open(PORTADAS);
  const g = await c.match(req, { ignoreVary: true });
  if (g) return g;
  const r = await fetch(req);
  if (r.ok && new URL(req.url).searchParams.has('v')) {
    c.put(req, r.clone()).then(() => recortar(c, MAX_PORTADAS));
  }
  return r;
}

// Datos de la API que conviene tener sin conexión (solo lectura)
const API_OFFLINE = /^\/api\/(inicio|buscar|albumes|artistas|generos|mixes|playlists|letras|canciones\/\d+$|canciones\/(recientes|top|favoritos)|usuario\/favoritos|usuario\/guardados|historial|biblioteca)/;

// CacheStorage puede quedar ocupado por escrituras/limpiezas del dispositivo.
// En línea nunca retener el streaming detrás de ese trabajo; sin red se espera la copia.
async function audioConPrioridad(req, clave) {
  let vencio = false;
  const copia = audioGuardado(req, clave).then(r => r || inicioGuardado(req, clave)).then(r => {
    if (vencio) { r?.body?.cancel().catch(() => {}); return null; }
    return r;
  });
  if (self.navigator.onLine === false) return (await copia) || fetch(req);
  let reloj;
  try {
    const r = await Promise.race([
      copia,
      new Promise(resolve => { reloj = setTimeout(() => { vencio = true; resolve(null); }, 150); }),
    ]);
    return r || fetch(req);
  } catch {
    return fetch(req);
  } finally {
    clearTimeout(reloj);
  }
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return;

  if (url.pathname.startsWith('/api/')) {
    const clave = claveAudio(url.pathname);
    if (clave) {
      if (!indice && !cargandoIndice) recargarIndice();
      // No está en el dispositivo y hay red: directo al servidor, sin pasar por aquí (empieza antes)
      if (indice && !indice.has(clave) && self.navigator.onLine !== false) return;
      // Canción guardada → se sirve del dispositivo; si solo está el principio, ese trozo; si no, de la red
      e.respondWith(audioConPrioridad(req, clave));
      return;
    }
    if (url.pathname.startsWith('/api/portada/')) { e.respondWith(portada(req)); return; }
    if (API_OFFLINE.test(url.pathname)) { e.respondWith(redPrimero(req)); return; }
    return;
  }

  if (req.mode === 'navigate' && (url.pathname === '/' || url.pathname === '/index.html')) {
    e.respondWith(caches.match('/index.html', { ignoreVary: true }).then((r) => r || fetch(req)));
    return;
  }
  // Archivos de la interfaz: primero la copia guardada (instantáneo)
  // ignoreVary: el servidor responde con "Vary: Origin" y los módulos JS se piden con Origin;
  // sin esto, sin internet no se encontraba el código guardado y la app quedaba en negro
  e.respondWith(caches.match(req, { ignoreSearch: true, ignoreVary: true }).then((r) => r || fetch(req)));
});
