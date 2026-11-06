// Modo sin conexión: guarda en el dispositivo lo que escuchas, sin descargas manuales.
// El service worker (sw.js) sirve luego esas canciones, portadas y datos cuando no hay red.
import { audioUrl, downloadUrl, deUsuario } from './api.js';
import { bus, cover } from './util.js';
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
 * Descarga canciones para escucharlas sin conexión. En la app de Android además
 * guarda el MP3 en la carpeta Música/SpaceMusic del teléfono.
 */
export function descargar(songs, { archivo = true } = {}) {
  const w = sw();
  const m = leer();
  const app = window.SpaceMusicApp;
  for (const s of songs) {
    if (w) w.postMessage({ tipo: 'descargar-audio', id: s.id, url: location.origin + audioUrl(s.id) });
    m[s.id] = { ...s, _t: Date.now() };
    if (archivo && app?.guardarArchivo) {
      app.guardarArchivo(location.origin + downloadUrl(s.id), `${s.artista} - ${s.nombre}.mp3`.replace(/[\\/:*?"<>|]/g, '_'));
    }
  }
  escribir(m);
  bus.emit('descargas');
  return !!w;
}

export function quitarDescarga(id) {
  const m = leer();
  delete m[id];
  escribir(m);
  sw()?.postMessage({ tipo: 'quitar-descarga', id });
  bus.emit('descargas');
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
    if (e.data?.tipo !== 'descarga') return;
    if (e.data.ok) listas++; else { fallidas++; const m = leer(); delete m[e.data.id]; escribir(m); bus.emit('descargas'); }
    clearTimeout(avisoT);
    avisoT = setTimeout(() => {
      toast(fallidas
        ? `${listas} descargadas · ${fallidas} no se pudieron descargar`
        : listas === 1 ? 'Descargada · disponible sin conexión' : `${listas} canciones descargadas · disponibles sin conexión`, { error: fallidas > 0 });
      listas = fallidas = 0;
    }, 1200);
  });
  bus.on('player:song', () => { programada = player.current?.id ?? null; precargada = null; });
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
