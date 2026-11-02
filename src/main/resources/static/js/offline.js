// Modo sin conexión: guarda en el dispositivo lo que escuchas, sin descargas manuales.
// El service worker (sw.js) sirve luego esas canciones, portadas y datos cuando no hay red.
import { audioUrl } from './api.js';
import { bus } from './util.js';
import { player } from './player.js';

const SEGUNDOS_PARA_GUARDAR = 25; // una canción escuchada este tiempo se guarda (y la siguiente de la cola)
let pedidas = new Set();
let programada = null;

const ahorroDatos = () => !!navigator.connection?.saveData;

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

export function iniciarOffline({ toast }) {
  bus.on('player:song', () => { programada = player.current?.id ?? null; });
  player.audio.addEventListener('timeupdate', () => {
    if (programada == null || player.audio.currentTime < SEGUNDOS_PARA_GUARDAR) return;
    const actual = programada;
    programada = null;
    guardarCancion(actual);
    const sig = player.items?.[player.index + 1];
    if (sig) guardarCancion(sig.id); // así el cambio de canción es instantáneo, incluso sin red
  });
  window.addEventListener('offline', () => toast('Sin conexión · sigues escuchando lo guardado en este dispositivo', { ms: 5000 }));
  window.addEventListener('online', () => toast('Conexión recuperada'));
}
