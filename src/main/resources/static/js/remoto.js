// Controlar otro dispositivo con la misma cuenta (como «Spotify Connect»).
// Cada app abierta (web, Android, iPhone) informa al servidor qué suena; desde aquí se ven los demás
// dispositivos y se les manda órdenes: reproducir/pausar, anterior/siguiente, adelantar, volumen,
// «escuchar aquí» (traer su música a este dispositivo) y «pasar a ese dispositivo».
// Las órdenes llegan por la conexión en vivo (evento «control-orden») y cada dispositivo ejecuta solo las suyas.
import { api, session } from './api.js';
import { bus, esc, fmtTime, coverImg } from './util.js';
import { icon } from './icons.js';
import { player } from './player.js';
import { toast, errorToast, modal, promptDialog } from './ui.js';

const app = window.SpaceMusicApp;
const CLAVE_ID = 'sm_dispositivo';
const CLAVE_NOMBRE = 'sm_dispositivo_nombre';
const leer = (k) => { try { return localStorage.getItem(k); } catch { return null; } };
const guardar = (k, v) => { try { localStorage.setItem(k, v); } catch { } };

/** Identificador fijo de este dispositivo (no tiene datos personales). */
export function miDispositivo() {
  let id = leer(CLAVE_ID);
  if (!id || !/^[A-Za-z0-9_-]{8,64}$/.test(id)) {
    const r = crypto.getRandomValues(new Uint8Array(12));
    id = 'd' + Array.from(r, (b) => b.toString(16).padStart(2, '0')).join('');
    guardar(CLAVE_ID, id);
  }
  return id;
}

function nombrePorDefecto() {
  const ua = navigator.userAgent;
  if (app) return 'App Android';
  if (/iPhone/.test(ua)) return 'iPhone (navegador)';
  if (/iPad/.test(ua)) return 'iPad (navegador)';
  if (/Android/.test(ua)) return 'Android (navegador)';
  const nav = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Navegador';
  const so = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : '';
  return so ? `${nav} en ${so}` : nav;
}
export const miNombre = () => leer(CLAVE_NOMBRE) || nombrePorDefecto();
const plataforma = () => (app ? 'android' : 'web');

// ═══════════════════ Informar el estado de este dispositivo ═══════════════════

let ultimoEnvio = 0, pendiente = null, ultimo = null;
function estado() {
  return {
    dispositivo: miDispositivo(), nombre: miNombre(), plataforma: plataforma(),
    cancionId: player.current?.id ?? null, segundo: player.audio.currentTime || 0,
    duracion: player.duration() || 0, sonando: !!player.playing, volumen: player.volume,
  };
}

function informar(forzar = false) {
  if (!session.token) return;
  const espera = 1000 - (Date.now() - ultimoEnvio);
  if (!forzar && espera > 0) { clearTimeout(pendiente); pendiente = setTimeout(() => informar(true), espera); return; }
  ultimoEnvio = Date.now();
  const e = estado();
  ultimo = { ...e, t: performance.now() };
  api('/api/control/presencia', { method: 'POST', body: e }).catch(() => { });
}

// Al cambiar de canción, pausar, seguir o cambiar el volumen se avisa enseguida;
// el tiempo solo cuando se adelantó o atrasó (no en cada avance normal)
bus.on('player:song', () => informar());
bus.on('player:state', () => informar());
bus.on('player:volume', () => informar());
bus.on('player:time', () => {
  if (!ultimo || ultimo.cancionId !== (player.current?.id ?? null)) return;
  const esperado = ultimo.segundo + (ultimo.sonando ? (performance.now() - ultimo.t) / 1000 : 0);
  if (Math.abs(esperado - (player.audio.currentTime || 0)) > 3) informar();
});

/** Al cerrar la página deja de aparecer al instante (si no, a los 75 s). */
function despedirse() {
  if (!session.token) return;
  try {
    fetch('/api/control/presencia/' + miDispositivo(), { method: 'DELETE', keepalive: true, headers: { Authorization: 'Bearer ' + session.token } });
  } catch { }
}

// ═══════════════════ Recibir órdenes ═══════════════════

let dispositivos = [];
const nombreDe = (id) => dispositivos.find((d) => d.id === id)?.nombre || 'otro dispositivo';

function ponerCanciones(o) {
  if (!o.canciones?.length) return;
  player.play(o.canciones, o.indice || 0, { type: 'remoto', label: 'Desde ' + nombreDe(o.de) });
  if (o.segundo > 1) player.audio.addEventListener('loadedmetadata', () => player.seek(o.segundo), { once: true });
}

function ejecutar(o) {
  if (!o || o.para !== miDispositivo()) return;
  switch (o.accion) {
    case 'play': player.current ? player.resume() : null; break;
    case 'pause': player.pause(); break;
    case 'toggle': player.toggle(); break;
    case 'next': player.next(); break;
    case 'prev': player.prev(); break;
    case 'seek': player.seek(Number(o.segundo) || 0); break;
    case 'volumen': player.setVolume(Number(o.volumen)); break;
    case 'reproducir':
    case 'transferir':
      ponerCanciones(o);
      toast(`Sonando aquí por orden de ${nombreDe(o.de)}`, { ms: 3500 });
      break;
    default: return;
  }
  setTimeout(() => informar(true), 400);
}

bus.on('control-orden', ejecutar);
bus.on('control-dispositivos', (d) => {
  dispositivos = d?.dispositivos || [];
  marcarBoton();
  bus.emit('remoto:lista', dispositivos);
});

/** Botón «Dispositivos»: un punto cuando suena música en otro de tus dispositivos. */
function marcarBoton() {
  const yo = miDispositivo();
  const otro = dispositivos.find((d) => d.id !== yo && d.sonando);
  document.querySelectorAll('[data-dispositivos], [data-np-disp]').forEach((b) => {
    b.classList.toggle('disp-remoto-activo', !!otro);
    b.title = otro ? `Sonando en ${otro.nombre}` : 'Dispositivos y SyncPlay';
  });
}

export async function cargarDispositivos() {
  dispositivos = await api('/api/control/dispositivos?yo=' + encodeURIComponent(miDispositivo())).catch(() => dispositivos);
  marcarBoton();
  return dispositivos;
}

export const otrosDispositivos = () => dispositivos.filter((d) => d.id !== miDispositivo());

/** Envía una orden a otro dispositivo de la cuenta. */
export function ordenar(id, accion, datos = {}) {
  return api(`/api/control/${encodeURIComponent(id)}/orden`, { method: 'POST', body: { accion, de: miDispositivo(), ...datos } });
}

/** Trae a este dispositivo lo que suena en otro (y lo pausa allá). */
export async function escucharAqui(d) {
  if (!d.cancion) return;
  player.play([d.cancion], 0, { type: 'remoto', label: 'Desde ' + d.nombre });
  const seg = Number(d.segundo) || 0;
  if (seg > 1) player.audio.addEventListener('loadedmetadata', () => player.seek(seg), { once: true });
  if (d.sonando) await ordenar(d.id, 'pause').catch(() => { });
}

/** Pasa lo que suena aquí (con lo que sigue en la cola) a otro dispositivo y lo pausa aquí. */
export async function pasarA(d) {
  if (!player.current) { toast('No hay nada sonando en este dispositivo'); return; }
  const lista = player.items.slice(player.index, player.index + 100);
  await ordenar(d.id, 'transferir', { cancionIds: lista.map((c) => c.id), indice: 0, segundo: player.audio.currentTime || 0 });
  player.pause();
  toast('Ahora suena en ' + d.nombre);
}

// ═══════════════════ Pantalla «Controlar» ═══════════════════

const iconoDe = (d) => (d.plataforma === 'ios' ? 'speaker' : d.plataforma === 'android' ? 'speaker' : 'devices');

/** Filas de los otros dispositivos para el panel «Dispositivos». */
export function htmlOtros(lista) {
  if (!lista.length) {
    return `<p class="muted disp-nota">Abre SpaceMusic con tu cuenta en otro teléfono, tablet o computadora y podrás controlarlo desde aquí.</p>`;
  }
  return `<div class="remoto-lista">${lista.map((d) => `<button class="remoto-fila" data-remoto="${esc(d.id)}">
      <span class="remoto-ico ${d.sonando ? 'sonando' : ''}">${icon(iconoDe(d))}</span>
      <span class="ellipsis"><b>${esc(d.nombre)}</b><small class="muted ellipsis">${d.cancion ? `${d.sonando ? 'Sonando' : 'En pausa'}: ${esc(d.cancion.nombre)} · ${esc(d.cancion.artista)}` : 'Sin música'}</small></span>
      ${icon('right', 'sm')}</button>`).join('')}</div>`;
}

/** Control remoto de un dispositivo: portada, tiempo, botones y volumen; se actualiza en vivo. */
export async function controlar(id) {
  let d = dispositivos.find((x) => x.id === id);
  if (!d) { await cargarDispositivos(); d = dispositivos.find((x) => x.id === id); }
  if (!d) { toast('Ese dispositivo ya no está conectado'); return; }
  let quitar = null, reloj = null;
  await modal({
    title: 'Controlar ' + d.nombre,
    body: '<div class="remoto" data-remoto-box></div>',
    actions: [{ label: 'Listo', value: null }],
    onOpen: (m, cerrar) => {
      const box = m.querySelector('[data-remoto-box]');
      let recibido = performance.now();
      const segundoAhora = () => Math.min(d.duracion || Infinity, (Number(d.segundo) || 0) + (d.sonando ? (performance.now() - recibido) / 1000 : 0));
      const pintar = () => {
        const c = d.cancion;
        box.innerHTML = `${c ? `<div class="remoto-cancion">${coverImg(c.portadaUrl, 320, c.nombre, 'remoto-portada')}
            <div class="ellipsis"><b class="ellipsis">${esc(c.nombre)}</b><span class="muted ellipsis">${esc(c.artista)}</span></div></div>
          <input type="range" class="remoto-seek" data-seek min="0" max="${Math.max(1, Math.round(d.duracion || 0))}" step="1" value="${Math.round(segundoAhora())}" aria-label="Posición">
          <div class="remoto-tiempos"><span data-cur>${fmtTime(segundoAhora())}</span><span>${fmtTime(d.duracion || 0)}</span></div>`
          : `<p class="muted">No suena nada en ${esc(d.nombre)}. Puedes pasarle tu música.</p>`}
          <div class="remoto-ctrls">
            <button class="np-cb" data-o="prev" aria-label="Anterior" ${c ? '' : 'disabled'}>${icon('rew')}</button>
            <button class="np-cb big" data-o="toggle" aria-label="${d.sonando ? 'Pausar' : 'Reproducir'}" ${c ? '' : 'disabled'}>${icon(d.sonando ? 'pause' : 'play')}</button>
            <button class="np-cb" data-o="next" aria-label="Siguiente" ${c ? '' : 'disabled'}>${icon('fwd')}</button>
          </div>
          <label class="remoto-vol">${icon('volMin', 'sm')}<input type="range" data-vol min="0" max="100" value="${Math.round((d.volumen ?? 1) * 100)}" aria-label="Volumen de ${esc(d.nombre)}">${icon('volMax', 'sm')}</label>
          <div class="row wrap remoto-acc">
            ${c ? `<button class="btn sm" data-aqui>${icon('download', 'sm')} Escuchar aquí</button>` : ''}
            ${player.current ? `<button class="btn sm primary" data-pasar>${icon('send', 'sm')} Pasar mi música a ${esc(d.nombre)}</button>` : ''}
          </div>`;
        box.querySelector('[data-vol]').addEventListener('change', (e) => ordenar(d.id, 'volumen', { volumen: e.target.value / 100 }).catch(errorToast));
        box.querySelector('[data-seek]')?.addEventListener('change', (e) => ordenar(d.id, 'seek', { segundo: Number(e.target.value) }).catch(errorToast));
      };
      box.addEventListener('click', async (e) => {
        const b = e.target.closest('button');
        if (!b) return;
        try {
          if (b.dataset.o) {
            await ordenar(d.id, b.dataset.o);
            if (b.dataset.o === 'toggle') { d.sonando = !d.sonando; d.segundo = segundoAhora(); recibido = performance.now(); pintar(); }
          } else if (b.matches('[data-aqui]')) { await escucharAqui(d); cerrar(null); }
          else if (b.matches('[data-pasar]')) { await pasarA(d); cerrar(null); }
        } catch (err) { errorToast(err); }
      });
      // Cambios en vivo del otro dispositivo
      quitar = bus.on('remoto:lista', (l) => {
        const x = l.find((y) => y.id === id);
        if (!x) { box.innerHTML = `<p class="muted">${esc(d.nombre)} se desconectó.</p>`; return; }
        d = x; recibido = performance.now(); pintar();
      });
      reloj = setInterval(() => { const c = box.querySelector('[data-cur]'); const s = box.querySelector('[data-seek]'); if (c && document.activeElement !== s) { c.textContent = fmtTime(segundoAhora()); if (s) s.value = Math.round(segundoAhora()); } }, 1000);
      pintar();
    },
  });
  quitar?.();
  clearInterval(reloj);
}

/** Cambiar el nombre con el que este dispositivo aparece en los demás. */
export async function renombrar() {
  const v = await promptDialog('Nombre de este dispositivo', { label: 'Así lo verás en tus otros dispositivos', value: miNombre(), optional: true });
  if (v == null) return;
  const n = v.trim().slice(0, 40);
  if (n) guardar(CLAVE_NOMBRE, n); else { try { localStorage.removeItem(CLAVE_NOMBRE); } catch { } }
  informar(true);
}

// ═══════════════════ Arranque ═══════════════════

export function iniciarRemoto() {
  if (!session.token) return;
  informar(true);
  cargarDispositivos();
  // Latido cada 30 s (el servidor quita a quien no informa en 75 s)
  setInterval(() => { if (!document.hidden || player.playing || app) informar(true); }, 30000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { informar(true); cargarDispositivos(); } });
  window.addEventListener('pagehide', despedirse);
}
