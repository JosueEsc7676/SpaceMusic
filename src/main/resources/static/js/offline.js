// Modo sin conexión: guarda en el dispositivo lo que escuchas, sin descargas manuales.
// El service worker (sw.js) sirve luego esas canciones, portadas y datos cuando no hay red.
import { api, audioUrl, deUsuario } from './api.js';
import { bus, cover, servidor } from './util.js';
import { icon } from './icons.js';
import { player } from './player.js';

const SEGUNDOS_PARA_GUARDAR = 25; // una canción escuchada este tiempo se guarda (y la siguiente de la cola)
let pedidas = new Set();
let programada = null;

const ahorroDatos = () => !!navigator.connection?.saveData;
const CLAVE = 'sm_descargadas';

// ── Descargas a mano (permanentes) ──
function leer() { try { localStorage.removeItem(CLAVE); return JSON.parse(localStorage.getItem(deUsuario(CLAVE)) || '{}'); } catch { return {}; } }
function escribir(m) { try { localStorage.setItem(deUsuario(CLAVE), JSON.stringify(m)); } catch { } }

/** Canciones descargadas en este dispositivo (las más recientes primero). */
export function descargadas() {
  return Object.values(leer()).sort((a, b) => (b._t || 0) - (a._t || 0));
}
export const estaDescargada = (id) => !!leer()[id];

/**
 * Descarga canciones para escucharlas sin conexión (con su portada y su letra).
 * En la app de Android además guarda el MP3 en la carpeta Música/SpaceMusic del teléfono.
 * suelta: descargada a mano desde el menú de la canción (no por una playlist o álbum).
 */
export function descargar(songs, { suelta = false } = {}) {
  // Una sola copia, dentro de la app (como Spotify): sin archivos sueltos ni notificaciones de Android
  const w = sw();
  const m = leer();
  const nuevas = [];
  for (const s of songs) {
    const ya = m[s.id];
    if (!ya) {
      if (w) w.postMessage({ tipo: 'descargar-audio', id: s.id, url: location.origin + audioUrl(s.id) });
      progreso.set(Number(s.id), 0);
      nuevas.push(s);
    }
    m[s.id] = { ...s, _t: ya?._t || Date.now(), _suelta: !!(ya?._suelta || suelta) };
  }
  escribir(m);
  extras(nuevas);
  nuevas.forEach((s) => refrescarMarca(s.id));
  bus.emit('descargas');
  return !!w;
}

// ── Marca de descarga en cada canción: círculo de progreso mientras baja, ícono cuando ya está ──
export const progreso = new Map();
export function marcaDescarga(id) {
  id = Number(id);
  if (!leer()[id]) return '';
  if (disponibles.has(id)) return `<span class="dl-mark dl-ok" title="Descargada">${icon('download', 'sm')}</span>`;
  const p = Math.round((progreso.get(id) || 0) * 100);
  return `<span class="dl-mark dl-prog" data-dl="${id}" style="--p:${p}" title="Descargando… ${p}%"></span>`;
}
function refrescarMarca(id) {
  document.querySelectorAll(`[data-dl-slot="${id}"]`).forEach((el) => { el.innerHTML = marcaDescarga(id); });
}
function refrescarMarcas() {
  document.querySelectorAll('[data-dl-slot]').forEach((el) => { el.innerHTML = marcaDescarga(el.dataset.dlSlot); });
}

/** Portadas y letra de las canciones descargadas: así se ven y se leen igual sin conexión. */
let colaExtras = Promise.resolve();
function extras(songs) {
  for (const s of songs) {
    colaExtras = colaExtras.then(async () => {
      if (s.portadaUrl) for (const t of [64, 160, 300, 640]) { const i = new Image(); i.src = cover(s.portadaUrl, t); }
      // La letra (también la sincronizada) queda guardada por el service worker
      if (s.tieneLetra || s.letraSincronizada) await api(`/api/letras/${s.id}`, { timeout: 15000 }).catch(() => { });
    });
  }
}

export function quitarDescarga(id) {
  const m = leer();
  delete m[id];
  escribir(m);
  sw()?.postMessage({ tipo: 'quitar-descarga', id });
  disponibles.delete(Number(id));
  bus.emit('descargas');
}

// ── Colecciones descargadas (playlists, álbumes y Me gusta), como en Spotify ──
// Se guarda la colección completa (nombre, portada, orden) y sus canciones. Sin conexión cada
// una se abre tal cual, solo con lo suyo, y la biblioteca muestra solo lo descargado.
const CLAVE_COL = 'sm_colecciones';
function leerCol() { try { return JSON.parse(localStorage.getItem(deUsuario(CLAVE_COL)) || '{}'); } catch { return {}; } }
function escribirCol(m) { try { localStorage.setItem(deUsuario(CLAVE_COL), JSON.stringify(m)); } catch { } }

/** clave: 'playlist:12', 'album:Nombre' o 'favoritos'. */
export const coleccion = (clave) => leerCol()[clave] || null;
export const estaColeccion = (clave) => !!leerCol()[clave];
export const colecciones = () => Object.entries(leerCol()).map(([clave, c]) => ({ clave, ...c })).sort((a, b) => (b._t || 0) - (a._t || 0));

/** Descarga una colección completa. datos: la respuesta de la API (para mostrarla sin conexión). */
export function descargarColeccion(clave, tipo, datos, songs) {
  const m = leerCol();
  m[clave] = { tipo, datos: { ...datos, canciones: undefined }, canciones: songs, _t: m[clave]?._t || Date.now() };
  escribirCol(m);
  descargar(songs, { suelta: false });
  bus.emit('descargas');
}

/** Al abrir una colección descargada con conexión: se baja lo nuevo y se quita lo que ya no está. */
export function sincronizarColeccion(clave, datos, songs) {
  const m = leerCol();
  const c = m[clave];
  if (!c) return;
  const antes = new Set((c.canciones || []).map((s) => s.id));
  const ahora = new Set(songs.map((s) => s.id));
  m[clave] = { ...c, datos: { ...datos, canciones: undefined }, canciones: songs };
  escribirCol(m);
  const lista = leer();
  const faltan = songs.filter((s) => !antes.has(s.id) || !lista[s.id]);
  if (faltan.length) descargar(faltan, { suelta: false });
  liberar([...antes].filter((id) => !ahora.has(id)));
}

/** Quita la descarga de una colección (las canciones que siguen en otra descarga se conservan). */
export function quitarColeccion(clave) {
  const m = leerCol();
  const c = m[clave];
  if (!c) return;
  delete m[clave];
  escribirCol(m);
  liberar((c.canciones || []).map((s) => s.id));
  bus.emit('descargas');
}

/** Borra del dispositivo las canciones que ya no pertenecen a ninguna descarga. */
function liberar(ids) {
  if (!ids.length) return;
  const enUso = new Set(Object.values(leerCol()).flatMap((c) => (c.canciones || []).map((s) => s.id)));
  const m = leer();
  for (const id of ids) if (!enUso.has(id) && m[id] && !m[id]._suelta) quitarDescarga(id);
}

// ── ¿Hay conexión? ──
// Sin conexión: la app muestra solo lo descargado y salta lo que no se puede escuchar.
let sinRed = typeof navigator !== 'undefined' && navigator.onLine === false;
export const sinConexion = () => sinRed;
// Si no responde el servidor pero sí hay internet, es que SpaceMusic está en mantenimiento
// (se prueba cargando una imagen pequeña de otro sitio; como mucho una vez cada 30 s)
let ultimaPrueba = 0, ultimoInternet = null, avisoMantenimiento = 0;
function hayInternet() {
  return new Promise((res) => {
    const img = new Image();
    const fin = (ok) => { clearTimeout(t); img.onload = img.onerror = null; res(ok); };
    const t = setTimeout(() => fin(false), 5000);
    img.onload = () => fin(true);
    img.onerror = () => fin(false);
    img.referrerPolicy = 'no-referrer';
    img.src = 'https://www.google.com/favicon.ico?_=' + Date.now();
  });
}
async function revisarMantenimiento() {
  if (navigator.onLine === false) { marcarMantenimiento(false); return; }
  // El servidor avisó que se iba a reiniciar hace poco
  if (Date.now() - avisoMantenimiento < 10 * 60000) { marcarMantenimiento(true); return; }
  // Como mucho una prueba cada 30 s: entre tanto se usa el último resultado
  if (Date.now() - ultimaPrueba < 30000) { if (ultimoInternet !== null) marcarMantenimiento(ultimoInternet); return; }
  ultimaPrueba = Date.now();
  ultimoInternet = await hayInternet();
  if (sinRed) marcarMantenimiento(ultimoInternet);
}
function marcarMantenimiento(si) {
  if (servidor.mantenimiento === si) return;
  servidor.mantenimiento = si;
  document.documentElement.classList.toggle('mantenimiento', si);
  bus.emit('conexion', !sinRed);
}
bus.on('servidor:mantenimiento', () => { avisoMantenimiento = Date.now(); });

function ponerRed(hay) {
  const antes = sinRed;
  sinRed = !hay;
  if (sinRed) revisarMantenimiento(); else { marcarMantenimiento(false); avisoMantenimiento = 0; }
  document.documentElement.classList.toggle('sin-red', sinRed);
  if (antes !== sinRed) { if (sinRed) actualizarDisponibles(); bus.emit('conexion', !sinRed); }
}
// Con la pantalla apagada o la app en segundo plano, iPhone corta las peticiones de la página
// (no las del audio). Un fallo así no significa "sin conexión": si se tomara como tal, al terminar
// la canción no se pasaría a la siguiente y el reproductor se cerraría.
bus.on('red', (hay) => {
  if (!hay && document.hidden && navigator.onLine !== false) return;
  ponerRed(hay && navigator.onLine !== false);
});
window.addEventListener('offline', () => ponerRed(false));
window.addEventListener('online', () => ponerRed(true));
document.documentElement.classList.toggle('sin-red', sinRed);

/** Canciones que se pueden escuchar sin conexión (descargadas o guardadas al escucharlas). */
const disponibles = new Set();
// Solo lo que el service worker confirma que tiene completo (una descarga en curso todavía no cuenta)
export const disponible = (id) => disponibles.has(Number(id));
export function actualizarDisponibles() {
  const w = sw();
  if (!w) return Promise.resolve();
  return new Promise((res) => {
    const t = setTimeout(res, 2000);
    navigator.serviceWorker.addEventListener('message', function oir(e) {
      if (e.data?.tipo !== 'ids-audio') return;
      navigator.serviceWorker.removeEventListener('message', oir);
      clearTimeout(t);
      disponibles.clear();
      (e.data.ids || []).forEach((id) => disponibles.add(id));
      refrescarMarcas();
      bus.emit('disponibles');
      res();
    });
    w.postMessage({ tipo: 'ids-audio' });
  });
}

function sw() {
  return navigator.serviceWorker?.controller || null;
}

/** Pide al service worker guardar la canción completa en segundo plano. */
export function guardarCancion(id) {
  const w = sw();
  if (!w || !id || pedidas.has(id) || ahorroDatos()) return;
  pedidas.add(id);
  w.postMessage({ tipo: 'guardar-audio', url: location.origin + audioUrl(id) });
}

/** Cuántas canciones hay guardadas en este dispositivo. */
export function cancionesGuardadas() {
  return new Promise((res) => {
    const w = sw();
    if (!w) return res(0);
    const t = setTimeout(() => res(0), 1500);
    navigator.serviceWorker.addEventListener('message', function oir(e) {
      if (e.data?.tipo !== 'estado-offline') return;
      navigator.serviceWorker.removeEventListener('message', oir);
      clearTimeout(t);
      res(e.data.canciones || 0);
    });
    w.postMessage({ tipo: 'estado-offline' });
  });
}

// ── Precarga de la siguiente canción ──
// A los pocos segundos de empezar una canción se baja la siguiente (y su portada),
// así al terminar la actual la siguiente empieza al instante.
const SEGUNDOS_PRECARGA = 8;
let precargada = null;
let audioPrecarga = null;

function precargarSiguiente() {
  const sig = player.items?.[player.index + 1];
  if (!sig || precargada === sig.id) return;
  precargada = sig.id;
  if (sig.portadaUrl) { const img = new Image(); img.decoding = 'async'; img.src = cover(sig.portadaUrl, 640); }
  if (sw() && !ahorroDatos()) { guardarCancion(sig.id); return; }
  // Sin service worker (o con ahorro de datos): el navegador adelanta solo el inicio de la canción
  try {
    audioPrecarga ??= new Audio();
    audioPrecarga.preload = ahorroDatos() ? 'metadata' : 'auto';
    audioPrecarga.muted = true;
    audioPrecarga.src = audioUrl(sig.id);
    audioPrecarga.load();
  } catch { }
}

export function iniciarOffline({ toast }) {
  let listas = 0, fallidas = 0, avisoT;
  navigator.serviceWorker?.addEventListener('message', (e) => {
    if (e.data?.tipo === 'progreso') {
      const id = Number(e.data.id);
      progreso.set(id, e.data.pct);
      document.querySelectorAll(`.dl-prog[data-dl="${id}"]`).forEach((el) => { el.style.setProperty('--p', Math.round(e.data.pct * 100)); });
      return;
    }
    if (e.data?.tipo !== 'descarga') return;
    if (e.data.ok) { listas++; disponibles.add(Number(e.data.id)); progreso.delete(Number(e.data.id)); refrescarMarca(e.data.id); bus.emit('disponibles'); } else { fallidas++; progreso.delete(Number(e.data.id)); refrescarMarca(e.data.id); } // queda pendiente: se reintenta al abrir con conexión
    clearTimeout(avisoT);
    // Un solo aviso al terminar todo lo que estaba en cola (no uno por canción)
    if (progreso.size) return;
    avisoT = setTimeout(() => {
      toast(fallidas
        ? `${listas} descargadas · ${fallidas} no se pudieron descargar`
        : listas === 1 ? 'Descargada · disponible sin conexión' : `${listas} canciones descargadas · disponibles sin conexión`, { error: fallidas > 0 });
      listas = fallidas = 0;
    }, 1200);
  });
  // Sin conexión el reproductor salta lo que no está en el dispositivo
  player.disponible = (song) => !sinRed || disponible(song.id);
  player.tieneCopia = (id) => disponible(id);
  // Las descargas sueltas de versiones anteriores (fuera de toda playlist/álbum/Me gusta) se liberan una vez:
  // ahora cada descarga vive dentro de su colección, como en Spotify
  try {
    const marca = deUsuario('sm_desc_v3');
    if (!localStorage.getItem(marca)) {
      const enUso = new Set(Object.values(leerCol()).flatMap((c) => (c.canciones || []).map((x) => x.id)));
      for (const id of Object.keys(leer()).map(Number)) if (!enUso.has(id)) quitarDescarga(id);
      localStorage.setItem(marca, '1');
    }
  } catch { }
  // Al abrir con conexión: las descargas que quedaron a medias (app cerrada, sin señal…) se retoman
  navigator.serviceWorker?.ready.then(async () => {
    await actualizarDisponibles();
    if (sinRed || !navigator.onLine) return;
    const w = sw();
    const sinLista = Object.values(leerCol()).flatMap((c) => c.canciones || []).filter((x) => !leer()[x.id]);
    if (sinLista.length) descargar(sinLista, { suelta: false }); // se habían perdido (versiones anteriores)
    const faltan = Object.keys(leer()).map(Number).filter((id) => !disponibles.has(id) && !progreso.has(id));
    if (w) for (const id of faltan) { progreso.set(id, 0); w.postMessage({ tipo: 'descargar-audio', id, url: location.origin + audioUrl(id) }); }
    if (faltan.length) refrescarMarcas();
  }).catch(() => { });
  bus.on('descargas', () => { if (sinRed) actualizarDisponibles(); });
  // "Me gusta" descargado: lo que marques después también se descarga (y lo que quites se libera)
  bus.on('favs', async (ev) => {
    if (!ev || !estaColeccion('favoritos')) return;
    const c = coleccion('favoritos');
    if (ev.fav) {
      const s = await api('/api/canciones/' + ev.id).catch(() => null);
      if (!s) return;
      const m = leerCol();
      m.favoritos = { ...c, canciones: [s, ...(c.canciones || []).filter((x) => x.id !== s.id)] };
      escribirCol(m);
      descargar([s], { suelta: false });
    } else {
      const m = leerCol();
      m.favoritos = { ...c, canciones: (c.canciones || []).filter((x) => x.id !== ev.id) };
      escribirCol(m);
      liberar([ev.id]);
      bus.emit('descargas');
    }
  });
  bus.on('player:song', () => { programada = player.current?.id ?? null; precargada = null; });
  // La canción que suena no es la elegida (copia vieja o equivocada en el dispositivo): se borra
  // esa copia y se vuelve a pedir al servidor
  bus.on('audio:distinto', ({ id, reintentar }) => {
    const w = sw();
    if (!w) return;
    let listo = false;
    const oir = (e) => {
      if (e.data?.tipo !== 'audio-revisado' || e.data.id !== id) return;
      listo = true;
      navigator.serviceWorker.removeEventListener('message', oir);
      disponibles.delete(Number(id));
      reintentar?.();
    };
    navigator.serviceWorker.addEventListener('message', oir);
    w.postMessage({ tipo: 'audio-equivocado', id, url: location.origin + audioUrl(id) });
    setTimeout(() => { if (!listo) navigator.serviceWorker.removeEventListener('message', oir); }, 5000);
  });
  // Si la cola cambia (radio, "reproducir a continuación"…), se vuelve a precargar la siguiente
  bus.on('player:queue', () => { precargada = null; });
  player.audio.addEventListener('timeupdate', () => {
    const t = player.audio.currentTime;
    if (t >= SEGUNDOS_PRECARGA) precargarSiguiente();
    if (programada == null || t < SEGUNDOS_PARA_GUARDAR) return;
    const actual = programada;
    programada = null;
    guardarCancion(actual);
  });
  window.addEventListener('offline', () => toast('Sin conexión · sigues escuchando lo guardado en este dispositivo', { ms: 5000 }));
  window.addEventListener('online', () => toast('Conexión recuperada'));
}
