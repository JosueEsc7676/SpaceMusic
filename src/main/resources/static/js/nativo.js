// Puente con la app de Android (SpaceMusic para teléfonos).
// La app nativa muestra los controles en la notificación y la pantalla de bloqueo,
// y mantiene la música sonando en segundo plano. Aquí se le envía el estado del reproductor
// y se reciben sus órdenes (reproducir, pausar, siguiente…).
import { bus, cover } from './util.js';
import { player } from './player.js';
import { np, queueDrawer } from './nowplaying.js';

const app = window.SpaceMusicApp;

/** Equipos modestos: menos desenfoques y animaciones de fondo para que todo vaya fluido. */
export function modoLigero() {
  const poca = (navigator.deviceMemory && navigator.deviceMemory <= 3) || (navigator.hardwareConcurrency && navigator.hardwareConcurrency <= 4);
  if (poca) document.documentElement.classList.add('lite');
}

export function iniciarNativo() {
  if (!app) return;
  document.documentElement.classList.add('in-app');

  let ultimo = '';
  const enviar = () => {
    const s = player.current;
    if (!s) { app.detener(); ultimo = ''; return; }
    const portada = s.portadaUrl ? new URL(cover(s.portadaUrl, 640), location.origin).href : '';
    const datos = [player.playing, s.nombre, s.artista, s.album && s.album !== 'Single' ? s.album : '', portada,
      Math.round(player.duration()), Math.round(player.audio.currentTime || 0)];
    const clave = JSON.stringify(datos);
    if (clave === ultimo) return;
    ultimo = clave;
    app.estado(...datos);
  };
  bus.on('player:song', enviar);
  bus.on('player:state', enviar);
  player.audio.addEventListener('seeked', enviar);
  player.audio.addEventListener('loadedmetadata', enviar);
  player.audio.addEventListener('play', enviar);
  player.audio.addEventListener('pause', enviar);

  // Órdenes desde la notificación, la pantalla de bloqueo o los auriculares
  window.smRemote = (accion, valor) => {
    if (accion === 'play') player.resume();
    else if (accion === 'pause') player.pause();
    else if (accion === 'toggle') player.toggle();
    else if (accion === 'next') player.next();
    else if (accion === 'prev') player.prev();
    else if (accion === 'seek') player.seek(Number(valor) || 0);
    setTimeout(enviar, 300);
  };

  // Botón Atrás de Android: cierra lo que esté abierto o vuelve a la pantalla anterior
  window.smBack = () => {
    const menu = document.querySelector('.menu, .backdrop');
    if (menu) { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); return true; }
    if (np.isOpen) { np.close(); return true; }
    if (queueDrawer.isOpen) { queueDrawer.close(); return true; }
    if (location.hash && location.hash !== '#/' && history.length > 1) { history.back(); return true; }
    return false; // en el inicio: la app pasa a segundo plano (la música sigue)
  };
}
