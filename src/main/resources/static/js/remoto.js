// Controlar la reproducción entre tus dispositivos, con el modelo de «Spotify Connect»:
//  - Conexión permanente (WebSocket /api/control/ws, como el «dealer» de Spotify) por la que llegan el estado
//    de la cuenta («cluster»: dispositivos, cuál está activo, versión) y las órdenes, y salen el estado y las confirmaciones.
//  - Cada orden lleva un identificador y el dispositivo destino la confirma («ack»): aquí se sabe si se aplicó.
//  - Un solo dispositivo suena a la vez: si este empieza a sonar, el servidor pausa el que sonaba.
//  - Reloj sincronizado con el servidor (ping/pong): la posición de los otros dispositivos se calcula exacta.
// Si el WebSocket no está disponible se usa la vía anterior (HTTP + eventos en vivo), sin perder funciones.
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

/** Identificador fijo de este dispositivo (aleatorio, sin datos personales). */
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

// ═══════════════════ Reloj del servidor ═══════════════════

// Diferencia entre la hora del servidor y la de este equipo (se toma la medición con menor demora, como NTP)
let desfase = 0, mejorDemora = Infinity;
export const ahoraServidor = () => Date.now() + desfase;

/** Segundo en que va otro dispositivo ahora mismo (el estado trae el segundo en el instante «ts» del servidor). */
export function posicion(d) {
  const s = Number(d.segundo) || 0;
  const t = d.sonando && d.ts ? s + Math.max(0, ahoraServidor() - d.ts) / 1000 : s;
  return d.duracion ? Math.min(t, d.duracion) : t;
}

// ═══════════════════ Conexión permanente (WebSocket) ═══════════════════

let ws = null, abierto = false, espera = 1000, reconectar = null, latido = null;
const enVivo = () => abierto && ws?.readyState === 1;
/** ¿Hay conexión permanente con el servidor? (si no, se usa la vía anterior) */
export const conectadoEnVivo = () => enVivo();

function conectar() {
  if (!session.token || !('WebSocket' in window)) return;
  if (ws && (ws.readyState === 0 || ws.readyState === 1)) return;
  clearTimeout(reconectar);
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/control/ws?t=${encodeURIComponent(session.token)}`;
  try { ws = new WebSocket(url); } catch { programarReconexion(); return; }
  ws.onopen = () => {
    abierto = true;
    espera = 1000;
    ws.send(JSON.stringify({ tipo: 'hola', ...estado() }));
    ping();
    clearInterval(latido);
    latido = setInterval(ping, 25000); // mantiene viva la conexión a través de proxys y mide el reloj
  };
  ws.onmessage = (e) => { try { recibir(JSON.parse(e.data)); } catch { } };
  ws.onclose = () => {
    abierto = false;
    clearInterval(latido);
    // Órdenes que esperaban confirmación por esta conexión: fallan ya (no esperan los 5 s)
    for (const [ref, p] of porRef) { p.rechazar(new Error('Se perdió la conexión')); porRef.delete(ref); }
    programarReconexion();
  };
  ws.onerror = () => { };
}

function programarReconexion() {
  if (!session.token) return;
  clearTimeout(reconectar);
  reconectar = setTimeout(conectar, espera);
  espera = Math.min(espera * 2, 30000);
}

function ping() { if (enVivo()) ws.send(JSON.stringify({ tipo: 'ping', t: Date.now() })); }

function enviar(m) {
  if (!enVivo()) return false;
  try { ws.send(JSON.stringify(m)); return true; } catch { return false; }
}

// ═══════════════════ Estado de este dispositivo ═══════════════════

let seq = Date.now(), ultimoEnvio = 0, pendiente = null, ultimo = null;
function estado() {
  return {
    dispositivo: miDispositivo(), nombre: miNombre(), plataforma: plataforma(),
    cancionId: player.current?.id ?? null, segundo: player.audio.currentTime || 0,
    duracion: player.duration() || 0, sonando: !!player.playing, volumen: player.volume,
    cola: player.items.slice(player.index + 1, player.index + 11).map((c) => c.id),
  };
}

function informar(forzar = false) {
  if (!session.token) return;
  const espera_ = 700 - (Date.now() - ultimoEnvio);
  if (!forzar && espera_ > 0) { clearTimeout(pendiente); pendiente = setTimeout(() => informar(true), espera_); return; }
  ultimoEnvio = Date.now();
  const e = estado();
  ultimo = { ...e, t: performance.now() };
  if (enviar({ tipo: 'estado', seq: ++seq, ...e })) return;
  api('/api/control/presencia', { method: 'POST', body: e }).catch(() => { });
}

// Cambios que se avisan enseguida; el tiempo solo cuando se adelantó o atrasó
bus.on('player:song', () => informar());
bus.on('player:state', () => informar());
bus.on('player:volume', () => informar());
bus.on('player:queue', () => informar());
bus.on('player:time', () => {
  if (!ultimo || ultimo.cancionId !== (player.current?.id ?? null)) return;
  const esperado = ultimo.segundo + (ultimo.sonando ? (performance.now() - ultimo.t) / 1000 : 0);
  if (Math.abs(esperado - (player.audio.currentTime || 0)) > 2) informar();
});

function despedirse() {
  if (!session.token) return;
  try {
    fetch('/api/control/presencia/' + miDispositivo(), { method: 'DELETE', keepalive: true, headers: { Authorization: 'Bearer ' + session.token } });
  } catch { }
}

// ═══════════════════ Mensajes del servidor ═══════════════════

let dispositivos = [], version = -1, activo = null;
const nombreDe = (id) => (id === 'servidor' ? 'otro dispositivo' : dispositivos.find((d) => d.id === id)?.nombre || 'otro dispositivo');

function aplicarCluster(c) {
  if (c.version != null && c.version < version) return; // llegó tarde: ya hay uno más nuevo
  if (c.version != null) version = c.version;
  if (c.activo !== undefined) activo = c.activo;
  dispositivos = c.dispositivos || [];
  marcarBoton();
  bus.emit('remoto:lista', dispositivos);
}

function recibir(m) {
  switch (m.tipo) {
    case 'hola':
    case 'pong': {
      if (m.tipo === 'pong' && m.t) {
        const demora = Date.now() - m.t;
        if (demora <= mejorDemora * 1.5 || demora < 150) { mejorDemora = Math.min(mejorDemora, demora); desfase = m.servidorMs - (m.t + demora / 2); }
      } else if (m.servidorMs && mejorDemora === Infinity) desfase = m.servidorMs - Date.now();
      break;
    }
    case 'cluster': aplicarCluster(m); break;
    case 'orden': ejecutar(m, true); break;
    case 'orden-enviada': { const p = porRef.get(m.ref); if (p) { porRef.delete(m.ref); porId.set(m.id, p); } break; }
    case 'ack': confirmar(m); break;
    default: break;
  }
}

// ═══════════════════ Ejecutar las órdenes que llegan ═══════════════════

function ponerCanciones(o) {
  if (!o.canciones?.length) return;
  player.play(o.canciones, o.indice || 0, { type: 'remoto', label: 'Desde ' + nombreDe(o.de) });
  if (o.segundo > 1) player.audio.addEventListener('loadedmetadata', () => player.seek(o.segundo), { once: true });
}

/** Ejecuta una orden para este dispositivo y la confirma (por WebSocket; las de la vía anterior no se confirman). */
function ejecutar(o, porWs) {
  if (!o || o.para !== miDispositivo()) return;
  let ok = true, error = null;
  try {
    switch (o.accion) {
      case 'play': if (player.current) player.resume(); else { ok = false; error = 'No hay nada para reproducir'; } break;
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
      default: ok = false; error = 'Orden desconocida';
    }
  } catch (e) { ok = false; error = e?.message || 'No se pudo'; }
  if (porWs && o.id) enviar({ tipo: 'ack', id: o.id, ok, error });
  setTimeout(() => informar(true), 300);
}

// Vía anterior (sin WebSocket): órdenes, lista y confirmaciones por los eventos en vivo
bus.on('control-orden', (o) => { if (!enVivo()) ejecutar(o, false); });
bus.on('control-dispositivos', (d) => { if (!enVivo()) aplicarCluster({ dispositivos: d?.dispositivos || [], version: d?.version }); });
bus.on('control-ack', (a) => confirmar(a));

// ═══════════════════ Mandar órdenes ═══════════════════

const porRef = new Map(), porId = new Map();

function confirmar(a) {
  const p = (a.id && porId.get(a.id)) || (a.ref && porRef.get(a.ref));
  if (!p) return;
  porId.delete(a.id); porRef.delete(a.ref);
  clearTimeout(p.reloj);
  if (a.ok === false) p.rechazar(new Error(a.error || 'El dispositivo no pudo hacerlo'));
  else p.resolver({ legado: !!a.legado });
}

/**
 * Envía una orden a otro dispositivo de la cuenta y espera su confirmación (máximo 5 s).
 * id «activo» = el dispositivo que está sonando.
 */
export function ordenar(id, accion, datos = {}) {
  return new Promise((resolver, rechazar) => {
    const ref = Math.random().toString(36).slice(2, 10);
    const p = { resolver, rechazar };
    p.reloj = setTimeout(() => { porRef.delete(ref); for (const [k, v] of porId) if (v === p) porId.delete(k); rechazar(new Error('El dispositivo no respondió')); }, 5000);
    if (enviar({ tipo: 'orden', ref, para: id, accion, ...datos })) { porRef.set(ref, p); return; }
    // Sin WebSocket: por HTTP; la confirmación llega por los eventos en vivo («control-ack»)
    api(`/api/control/${encodeURIComponent(id)}/orden`, { method: 'POST', body: { accion, de: miDispositivo(), ...datos } })
      .then((r) => { if (r?.id) porId.set(r.id, p); else { clearTimeout(p.reloj); resolver({ legado: true }); } })
      .catch((e) => { clearTimeout(p.reloj); rechazar(e); });
  });
}

export async function cargarDispositivos() {
  if (enVivo()) { enviar({ tipo: 'cluster' }); return dispositivos; }
  const c = await api('/api/control/cluster').catch(() => null);
  if (c) aplicarCluster(c);
  return dispositivos;
}

export const otrosDispositivos = () => dispositivos.filter((d) => d.id !== miDispositivo());
export const dispositivoActivo = () => activo;

/** Trae a este dispositivo lo que suena en otro (desde el mismo segundo) y lo pausa allá. */
export async function escucharAqui(d) {
  if (!d.cancion) return;
  const lista = [d.cancion, ...(d.cola || [])];
  const seg = posicion(d);
  player.play(lista, 0, { type: 'remoto', label: 'Desde ' + d.nombre });
  if (seg > 1) player.audio.addEventListener('loadedmetadata', () => player.seek(seg), { once: true });
  // Al empezar a sonar aquí, el servidor pausa el otro (un solo dispositivo activo); por si acaso se pide también
  if (d.sonando) await ordenar(d.id, 'pause').catch(() => { });
}

/** Pasa lo que suena aquí (con lo que sigue) a otro dispositivo; este se pausa cuando el otro confirma. */
export async function pasarA(d) {
  if (!player.current) { toast('No hay nada sonando en este dispositivo'); return; }
  const lista = player.items.slice(player.index, player.index + 100);
  await ordenar(d.id, 'transferir', { cancionIds: lista.map((c) => c.id), indice: 0, segundo: player.audio.currentTime || 0 });
  player.pause();
  toast('Ahora suena en ' + d.nombre);
}

// ═══════════════════ Pantallas ═══════════════════

/** Botón «Dispositivos»: un punto cuando suena música en otro de tus dispositivos. */
function marcarBoton() {
  const yo = miDispositivo();
  const otro = dispositivos.find((d) => d.id !== yo && d.sonando);
  document.querySelectorAll('[data-dispositivos], [data-np-disp]').forEach((b) => {
    b.classList.toggle('disp-remoto-activo', !!otro);
    b.title = otro ? `Sonando en ${otro.nombre}` : 'Dispositivos y SyncPlay';
  });
}

const iconoDe = (d) => (d.plataforma === 'ios' || d.plataforma === 'android' ? 'speaker' : 'devices');

/** Filas de los otros dispositivos para el panel «Dispositivos». */
export function htmlOtros(lista) {
  if (!lista.length) {
    return `<p class="muted disp-nota">Abre SpaceMusic con tu cuenta en otro teléfono, tablet o computadora y podrás controlarlo desde aquí.</p>`;
  }
  return `<div class="remoto-lista">${lista.map((d) => `<button class="remoto-fila" data-remoto="${esc(d.id)}">
      <span class="remoto-ico ${d.sonando ? 'sonando' : ''}">${icon(iconoDe(d))}</span>
      <span class="ellipsis"><b>${esc(d.nombre)}${d.activo ? ' <span class="remoto-activo">Activo</span>' : ''}</b><small class="muted ellipsis">${d.cancion ? `${d.sonando ? 'Sonando' : 'En pausa'}: ${esc(d.cancion.nombre)} · ${esc(d.cancion.artista)}` : 'Sin música'}</small></span>
      ${icon('right', 'sm')}</button>`).join('')}</div>`;
}

/** Control de un dispositivo: portada, tiempo, botones, volumen y lo que sigue; se actualiza en vivo. */
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
      let ocupado = false;
      const pintar = () => {
        const c = d.cancion;
        box.innerHTML = `${c ? `<div class="remoto-cancion">${coverImg(c.portadaUrl, 320, c.nombre, 'remoto-portada')}
            <div class="ellipsis"><b class="ellipsis">${esc(c.nombre)}</b><span class="muted ellipsis">${esc(c.artista)}</span></div></div>
          <input type="range" class="remoto-seek" data-seek min="0" max="${Math.max(1, Math.round(d.duracion || 0))}" step="1" value="${Math.round(posicion(d))}" aria-label="Posición">
          <div class="remoto-tiempos"><span data-cur>${fmtTime(posicion(d))}</span><span>${fmtTime(d.duracion || 0)}</span></div>`
          : `<p class="muted">No suena nada en ${esc(d.nombre)}. Puedes pasarle tu música.</p>`}
          <div class="remoto-ctrls">
            <button class="np-cb" data-o="prev" aria-label="Anterior" ${c ? '' : 'disabled'}>${icon('rew')}</button>
            <button class="np-cb big" data-o="toggle" aria-label="${d.sonando ? 'Pausar' : 'Reproducir'}" ${c ? '' : 'disabled'}>${icon(d.sonando ? 'pause' : 'play')}</button>
            <button class="np-cb" data-o="next" aria-label="Siguiente" ${c ? '' : 'disabled'}>${icon('fwd')}</button>
          </div>
          <label class="remoto-vol">${icon('volMin', 'sm')}<input type="range" data-vol min="0" max="100" value="${Math.round((d.volumen ?? 1) * 100)}" aria-label="Volumen de ${esc(d.nombre)}">${icon('volMax', 'sm')}</label>
          <p class="muted remoto-estado" data-estado aria-live="polite"></p>
          <div class="row wrap remoto-acc">
            ${c ? `<button class="btn sm" data-aqui>${icon('download', 'sm')} Escuchar aquí</button>` : ''}
            ${player.current ? `<button class="btn sm primary" data-pasar>${icon('send', 'sm')} Pasar mi música a ${esc(d.nombre)}</button>` : ''}
          </div>
          ${d.cola?.length ? `<h4 class="disp-h">A continuación en ${esc(d.nombre)}</h4><div class="remoto-cola">${d.cola.slice(0, 5).map((x) => `<div class="ellipsis"><b>${esc(x.nombre)}</b> <span class="muted">· ${esc(x.artista)}</span></div>`).join('')}</div>` : ''}`;
        box.querySelector('[data-vol]').addEventListener('change', (e) => mandar('volumen', { volumen: e.target.value / 100 }));
        box.querySelector('[data-seek]')?.addEventListener('change', (e) => mandar('seek', { segundo: Number(e.target.value) }));
      };
      /** Envía la orden y muestra si se confirmó (como en Spotify: el botón espera la respuesta del dispositivo). */
      const mandar = async (accion, datos) => {
        if (ocupado) return;
        ocupado = true;
        const est = box.querySelector('[data-estado]');
        box.classList.add('esperando');
        try {
          const r = await ordenar(d.id, accion, datos);
          if (est) est.textContent = r.legado ? 'Enviado' : '';
        } catch (err) {
          if (est) est.textContent = err.message;
          errorToast(err);
        } finally { ocupado = false; box.classList.remove('esperando'); }
      };
      box.addEventListener('click', async (e) => {
        const b = e.target.closest('button');
        if (!b) return;
        if (b.dataset.o) { mandar(b.dataset.o); return; }
        try {
          if (b.matches('[data-aqui]')) { await escucharAqui(d); cerrar(null); }
          else if (b.matches('[data-pasar]')) { await pasarA(d); cerrar(null); }
        } catch (err) { errorToast(err); }
      });
      // El cluster nuevo llega en vivo: se repinta con el estado confirmado
      quitar = bus.on('remoto:lista', (l) => {
        const x = l.find((y) => y.id === id);
        if (!x) { box.innerHTML = `<p class="muted">${esc(d.nombre)} se desconectó.</p>`; return; }
        if (document.activeElement?.closest?.('[data-remoto-box] input')) { d = x; return; } // no mover el control que se está arrastrando
        d = x; pintar();
      });
      reloj = setInterval(() => {
        const c = box.querySelector('[data-cur]'); const s = box.querySelector('[data-seek]');
        if (c && document.activeElement !== s) { const p = posicion(d); c.textContent = fmtTime(p); if (s) s.value = Math.round(p); }
      }, 500);
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
  conectar();
  informar(true);
  cargarDispositivos();
  // Respaldo: sin WebSocket se informa por HTTP cada 30 s (el servidor quita a quien no informa en 75 s)
  setInterval(() => { if (!enVivo() && (!document.hidden || player.playing || app)) informar(true); }, 30000);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return;
    if (!enVivo()) { espera = 1000; conectar(); }
    informar(true);
  });
  window.addEventListener('online', () => { espera = 1000; conectar(); });
  window.addEventListener('pagehide', despedirse);
}
