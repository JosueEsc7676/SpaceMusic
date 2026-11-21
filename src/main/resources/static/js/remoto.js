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
// Avisos por el bus (la web y la TV los muestran a su manera); así este módulo también sirve en la TV sin cargar ui.js
const toast = (msg, o = {}) => bus.emit('toast', { msg, ...o });
const errorToast = (e) => bus.emit('toast', { msg: e?.message || 'No se pudo completar', error: true });

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

/** App de TV (Android TV / Google TV): se presenta como «TV» con el modelo del equipo. */
const tv = () => !!window.SM_TV;
function nombrePorDefecto() {
  const ua = navigator.userAgent;
  if (tv()) {
    let modelo = '';
    try { modelo = window.SpaceMusicTV?.dispositivo?.() || ''; } catch { }
    return !modelo ? 'TV' : /tv/i.test(modelo) ? modelo : 'TV · ' + modelo;
  }
  if (app) return 'App Android';
  if (/iPhone/.test(ua)) return 'iPhone (navegador)';
  if (/iPad/.test(ua)) return 'iPad (navegador)';
  if (/Android/.test(ua)) return 'Android (navegador)';
  const nav = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Navegador';
  const so = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : '';
  return so ? `${nav} en ${so}` : nav;
}
export const miNombre = () => leer(CLAVE_NOMBRE) || nombrePorDefecto();
const plataforma = () => (tv() ? 'tv' : app ? 'android' : 'web');

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
/** En un SyncPlay la música la decide SyncPlay: este dispositivo no compite por ser «el activo» de la cuenta. */
const enSyncPlay = () => document.documentElement.classList.contains('en-sala');

// Hora del último inicio hecho por la persona (o por una orden de otro dispositivo). El servidor da el turno de
// «dispositivo activo» solo ante un inicio nuevo: el paso automático a la siguiente canción no cuenta.
let inicioUsuario = 0;
function marcarInicios() {
  if (player.__inicios) return;
  player.__inicios = true;
  for (const k of ['play', 'resume', 'toggle', 'next', 'prev']) {
    const orig = player[k].bind(player);
    player[k] = function (...args) {
      const automatico = k === 'next' && args[0] === true;
      const reanuda = k === 'toggle' ? !player.playing : true;
      if (!automatico && reanuda) inicioUsuario = Date.now();
      return orig(...args);
    };
  }
}
function estado() {
  return {
    dispositivo: miDispositivo(), nombre: miNombre(), plataforma: plataforma(),
    cancionId: player.current?.id ?? null, segundo: player.audio.currentTime || 0,
    duracion: player.duration() || 0, sonando: !!player.playing && !enSyncPlay(), volumen: player.volume, inicio: inicioUsuario,
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
  if (ignorado) {
    const d = dispositivos.find((x) => x.id === ignorado);
    if (!d) ignorado = null;
    else { if (d.sonando && !ignoradoSonaba) ignorado = null; else ignoradoSonaba = !!d.sonando; }
  }
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
  if (o.segundo > 1) player.audio.addEventListener('loadedmetadata', () => aqui(() => player.seek(o.segundo)), { once: true });
}

/** Ejecuta una orden para este dispositivo y la confirma (por WebSocket; las de la vía anterior no se confirman). */
let quitarGuardaPausa = null;

function ejecutar(o, porWs) {
  if (!o || o.para !== miDispositivo()) return;
  if (o.accion === 'reproducir' || o.accion === 'transferir') {
    // Traen la música a este dispositivo: se deja de controlar el otro
    const d = controlandoA();
    elegido = null;
    if (d) ignorar(d.id);
  }
  aquiMismo = true;
  try { ejecutarAqui(o, porWs); } finally { aquiMismo = false; }
}

function ejecutarAqui(o, porWs) {
  if (o.accion !== 'pause') quitarGuardaPausa?.(); // otra orden (reproducir, siguiente…) anula la protección de la pausa
  // La pausa automática del servidor (otro dispositivo empezó a sonar) no aplica dentro de SyncPlay
  if (o.de === 'servidor' && enSyncPlay()) { if (porWs && o.id) enviar({ tipo: 'ack', id: o.id, ok: true }); return; }
  let ok = true, error = null;
  try {
    switch (o.accion) {
      case 'play': if (player.current) player.resume(); else { ok = false; error = 'No hay nada para reproducir'; } break;
      case 'pause': {
        // Si la canción todavía estaba cargando, al terminar arrancaría sola y anularía la pausa: la pausa gana
        const cargando = player.audio.readyState < 3 && !!player.current;
        player._pausaRemota = Date.now(); // el avance automático de los próximos segundos no la anula
        player.pause();
        if (cargando) {
          const otraVez = () => player.pause();
          player.audio.addEventListener('playing', otraVez, { once: true });
          quitarGuardaPausa = () => { player.audio.removeEventListener('playing', otraVez); quitarGuardaPausa = null; };
          setTimeout(() => quitarGuardaPausa?.(), 2500);
        }
        break;
      }
      case 'toggle': player.toggle(); break;
      case 'next': player.next(); break;
      case 'prev': player.prev(); break;
      case 'seek': player.seek(Number(o.segundo) || 0); break;
      case 'volumen': player.setVolume(Number(o.volumen)); break;
      case 'cola':
        // «A continuación» cambiada desde otro dispositivo (la canción actual sigue igual)
        player.clearUpcoming();
        if (o.canciones?.length) player.addToQueue(o.canciones);
        break;
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
  elegido = null;
  ignorar(d.id);
  const lista = [d.cancion, ...(d.cola || [])];
  const seg = posicion(d);
  aqui(() => player.play(lista, 0, { type: 'remoto', label: 'Desde ' + d.nombre }));
  if (seg > 1) player.audio.addEventListener('loadedmetadata', () => aqui(() => player.seek(seg)), { once: true });
  pintarBarra();
  // Al empezar a sonar aquí, el servidor pausa el otro (un solo dispositivo activo); por si acaso se pide también
  if (d.sonando) await ordenar(d.id, 'pause').catch(() => { });
}

/** Pasa lo que suena aquí (con lo que sigue) a otro dispositivo; este se pausa cuando el otro confirma. */
export async function pasarA(d) {
  if (!player.current) { toast('No hay nada sonando en este dispositivo'); return; }
  const lista = player.items.slice(player.index, player.index + 100);
  await ordenar(d.id, 'transferir', { cancionIds: lista.map((c) => c.id), indice: 0, segundo: player.audio.currentTime || 0 });
  aqui(() => player.pause());
  elegido = d.id;
  ignorado = null;
  toast('Ahora suena en ' + d.nombre);
  pintarBarra();
}

/**
 * «Dispositivos» → otro dispositivo (como Spotify Connect): si aquí suena algo pasa allá; si no, se controla
 * lo que tiene. Desde ese momento el reproductor de siempre (barra, botones, canciones que toques) actúa allá.
 */
export async function elegirDispositivo(id) {
  const d = otrosDispositivos().find((x) => x.id === id);
  if (!d) { toast('Ese dispositivo ya no está conectado'); return; }
  if (player.current && (player.playing || !d.cancion)) { await pasarA(d); return; }
  elegido = d.id;
  ignorado = null;
  pintarBarra();
  toast('Controlando ' + d.nombre);
}

/** «Dispositivos» → este dispositivo: la música vuelve aquí (desde el mismo segundo si allá sonaba). */
export async function elegirEste() {
  const d = controlandoA();
  elegido = null;
  if (!d) return;
  ignorar(d.id);
  if (d.sonando) await escucharAqui(d);
  pintarBarra();
}

// ── Mientras se controla otro dispositivo, los controles de siempre le mandan las órdenes ──

let elegido = null, ignorado = null, ignoradoSonaba = true, aquiMismo = false;
/**
 * Se dejó de controlar ese dispositivo a propósito: no se vuelve a tomar solo hasta que vuelva a empezar a sonar
 * (pasa de pausa a sonando), por ejemplo porque alguien le dio play allá.
 */
function ignorar(id) { ignorado = id; ignoradoSonaba = true; }
/** Ejecuta algo en el reproductor de este dispositivo aunque se esté controlando otro. */
function aqui(fn) { const antes = aquiMismo; aquiMismo = true; try { return fn(); } finally { aquiMismo = antes; } }

function interceptarControles() {
  if (player.__remoto) return;
  player.__remoto = true;
  const o = {};
  for (const k of ['play', 'toggle', 'resume', 'pause', 'next', 'prev', 'seek', 'setVolume', 'playNext', 'addToQueue']) o[k] = player[k].bind(player);
  const destino = () => (aquiMismo ? null : controlandoA());
  const mandar = (d, accion, datos) => ordenar(d.id, accion, datos).catch(errorToast).finally(() => setTimeout(pintarBarra, 300));
  player.play = (songs, start = 0, context = null, opts = {}) => {
    const d = destino();
    if (!d || context?.type === 'juntos-oyente') return o.play(songs, start, context, opts);
    const lista = (songs || []).slice(start, start + 100);
    if (lista.length) mandar(d, 'reproducir', { cancionIds: lista.map((c) => c.id), indice: 0, segundo: 0 });
  };
  player.toggle = () => { const d = destino(); return d ? mandar(d, d.sonando ? 'pause' : 'play') : o.toggle(); };
  player.resume = () => { const d = destino(); return d ? mandar(d, 'play') : o.resume(); };
  player.pause = () => { const d = destino(); return d ? mandar(d, 'pause') : o.pause(); };
  player.next = (auto) => { const d = destino(); return d && !auto ? mandar(d, 'next') : o.next(auto); };
  player.prev = () => { const d = destino(); return d ? mandar(d, 'prev') : o.prev(); };
  player.seek = (t) => { const d = destino(); return d ? mandar(d, 'seek', { segundo: Number(t) || 0 }) : o.seek(t); };
  player.setVolume = (v) => { const d = destino(); return d ? mandar(d, 'volumen', { volumen: Math.max(0, Math.min(1, Number(v))) }) : o.setVolume(v); };
  // «Reproducir a continuación» / «Agregar a la cola» van a la cola del otro dispositivo
  player.playNext = (songs) => {
    const d = destino();
    if (!d) return o.playNext(songs);
    const ids = [...(songs || []).map((c) => c.id), ...(d.cola || []).map((c) => c.id)];
    mandar(d, 'cola', { cancionIds: [...new Set(ids)] }).then(() => toast('Agregada a la cola de ' + d.nombre));
  };
  player.addToQueue = (songs) => {
    const d = destino();
    if (!d) return o.addToQueue(songs);
    const ids = [...(d.cola || []).map((c) => c.id), ...(songs || []).map((c) => c.id)];
    mandar(d, 'cola', { cancionIds: [...new Set(ids)] }).then(() => toast('Agregada a la cola de ' + d.nombre));
  };
}

// ═══════════════════ Pantallas ═══════════════════

// ═══════════════════ Barra «Escuchando en…» (como Spotify Connect) ═══════════════════
// Cuando la música suena en otro de tus dispositivos y aquí no, la barra del reproductor de este dispositivo pasa a
// controlar aquel: muestra su canción y su tiempo, y reproducir/pausar, anterior/siguiente y la barra de tiempo
// le mandan las órdenes. Funciona igual en cualquier dirección (teléfono ↔ laptop ↔ tablet).

let barra = null, relojBarra = null;

/** El dispositivo que se está controlando desde la barra (o null si suena aquí o en ninguno). */
export function controlandoA() {
  if (enSyncPlay()) return null;
  const yo = miDispositivo();
  if (elegido) {
    const d = dispositivos.find((x) => x.id === elegido && x.id !== yo);
    if (d) return d;
    elegido = null;
  }
  // En la TV lo que se elige suena en la TV (los otros se controlan desde su pantalla de Dispositivos)
  if (player.playing || tv()) return null;
  const act = dispositivos.find((d) => d.id === activo && d.id !== yo && d.id !== ignorado)
    || dispositivos.find((d) => d.id !== yo && d.sonando && d.id !== ignorado);
  return act && act.cancion ? act : null;
}

function crearBarra() {
  const pb = document.getElementById('playerBar');
  if (!pb || barra) return;
  barra = document.createElement('div');
  barra.className = 'barra-remota';
  barra.innerHTML = `<button class="br-info" data-br-abrir aria-label="Controlar el otro dispositivo">
      <span class="br-cover" data-br-cover></span>
      <span class="br-tt"><b class="ellipsis" data-br-nombre></b><small class="ellipsis" data-br-artista></small>
        <small class="br-donde ellipsis">${icon('devices', 'sm')} <span data-br-donde></span></small></span></button>
    <div class="br-ctrls">
      <button class="icon-btn hide-m" data-br="prev" aria-label="Anterior">${icon('prev')}</button>
      <button class="br-play" data-br="toggle" aria-label="Reproducir o pausar"></button>
      <button class="icon-btn" data-br="next" aria-label="Siguiente">${icon('next')}</button>
    </div>
    <label class="br-vol hide-m">${icon('volMax', 'sm')}<input type="range" data-br-vol min="0" max="100" value="100" aria-label="Volumen del otro dispositivo"></label>
    <input type="range" class="br-seek" data-br-seek min="0" max="1" step="1" value="0" aria-label="Posición en el otro dispositivo">`;
  pb.prepend(barra);
  barra.addEventListener('click', async (e) => {
    const d = controlandoA();
    if (!d) return;
    const b = e.target.closest('[data-br], [data-br-abrir]');
    if (!b) return;
    if (b.matches('[data-br-abrir]')) { import('./dispositivos.js').then((m) => m.abrirDispositivos()); return; }
    try { await ordenar(d.id, b.dataset.br); } catch (err) { errorToast(err); }
  });
  barra.querySelector('[data-br-vol]').addEventListener('change', (e) => {
    const d = controlandoA();
    if (d) ordenar(d.id, 'volumen', { volumen: Number(e.target.value) / 100 }).catch(errorToast);
  });
  barra.querySelector('[data-br-seek]').addEventListener('change', (e) => {
    const d = controlandoA();
    if (d) ordenar(d.id, 'seek', { segundo: Number(e.target.value) }).catch(errorToast);
  });
}

/** Pinta la barra con el dispositivo activo (o la oculta y vuelve el reproductor normal). */
function pintarBarra() {
  const pb = document.getElementById('playerBar');
  if (!pb) return;
  const d = controlandoA();
  // Dentro de un SyncPlay manda SyncPlay: la barra normal sigue igual
  const enSyncPlay = document.documentElement.classList.contains('en-sala');
  if (!d || enSyncPlay) {
    pb.classList.remove('remoto');
    if (barra) barra.hidden = true;
    clearInterval(relojBarra); relojBarra = null;
    return;
  }
  crearBarra();
  barra.hidden = false;
  pb.classList.add('remoto');
  const c = d.cancion;
  barra.querySelector('[data-br-cover]').innerHTML = coverImg(c.portadaUrl, 96);
  barra.querySelector('[data-br-nombre]').textContent = c.nombre;
  barra.querySelector('[data-br-artista]').textContent = c.artista;
  barra.querySelector('[data-br-donde]').textContent = `${d.sonando ? 'Escuchando' : 'En pausa'} en ${d.nombre}`;
  const play = barra.querySelector('[data-br="toggle"]');
  play.innerHTML = icon(d.sonando ? 'pause' : 'play');
  play.setAttribute('aria-label', d.sonando ? 'Pausar en ' + d.nombre : 'Reproducir en ' + d.nombre);
  const v = barra.querySelector('[data-br-vol]');
  if (document.activeElement !== v) v.value = Math.round((d.volumen ?? 1) * 100);
  const s = barra.querySelector('[data-br-seek]');
  s.max = Math.max(1, Math.round(d.duracion || 0));
  if (document.activeElement !== s) s.value = Math.round(posicion(d));
  if (!relojBarra) relojBarra = setInterval(() => {
    const x = controlandoA();
    if (!x) { pintarBarra(); return; }
    if (document.activeElement !== s) s.value = Math.round(posicion(x));
  }, 1000);
}

bus.on('remoto:lista', () => pintarBarra());
bus.on('player:state', () => pintarBarra());
bus.on('player:song', () => pintarBarra());

/** Botón «Dispositivos»: un punto cuando suena música en otro de tus dispositivos. */
function marcarBoton() {
  const yo = miDispositivo();
  const otro = dispositivos.find((d) => d.id !== yo && d.sonando);
  document.querySelectorAll('[data-dispositivos], [data-np-disp]').forEach((b) => {
    b.classList.toggle('disp-remoto-activo', !!otro);
    b.title = otro ? `Sonando en ${otro.nombre}` : 'Dispositivos y SyncPlay';
  });
}

const iconoDe = (d) => (d.plataforma === 'tv' ? 'tv' : d.plataforma === 'ios' || d.plataforma === 'android' ? 'speaker' : 'devices');

/**
 * «Reproducir en» del panel «Dispositivos» (como Spotify Connect): este dispositivo y los demás de la cuenta.
 * Al tocar uno la música suena allá y se sigue controlando con el reproductor de siempre.
 */
export function htmlOtros(lista) {
  const destino = controlandoA();
  const fila = (id, nombre, ico, detalle, sel) => `<button class="remoto-fila ${sel ? 'sel' : ''}" data-remoto="${esc(id)}" aria-pressed="${sel}">
      <span class="remoto-ico ${sel ? 'sonando' : ''}">${icon(ico)}</span>
      <span class="ellipsis"><b>${esc(nombre)}</b><small class="muted ellipsis">${detalle}</small></span>
      ${sel ? icon('check', 'sm') : ''}</button>`;
  const este = fila('este', 'Este dispositivo · ' + miNombre(), 'devices',
    destino ? 'Toca para que la música vuelva a sonar aquí' : (player.current ? esc(player.current.nombre) : 'Aquí'), !destino);
  const otros = lista.map((d) => fila(d.id, d.nombre, iconoDe(d),
    d.cancion ? `${d.sonando ? 'Sonando' : 'En pausa'}: ${esc(d.cancion.nombre)} · ${esc(d.cancion.artista)}` : 'Sin música', destino?.id === d.id)).join('');
  return `<div class="remoto-lista">${este}${otros}</div>${lista.length ? '' : `<p class="muted disp-nota">Abre SpaceMusic con tu cuenta en otro teléfono, tablet o computadora y aparecerá aquí.</p>`}`;
}

/** Cambiar el nombre con el que este dispositivo aparece en los demás. */
export async function renombrar() {
  const { promptDialog } = await import('./ui.js');
  const v = await promptDialog('Nombre de este dispositivo', { label: 'Así lo verás en tus otros dispositivos', value: miNombre(), optional: true });
  if (v == null) return;
  const n = v.trim().slice(0, 40);
  if (n) guardar(CLAVE_NOMBRE, n); else { try { localStorage.removeItem(CLAVE_NOMBRE); } catch { } }
  informar(true);
}

// ═══════════════════ Arranque ═══════════════════

export function iniciarRemoto() {
  if (!session.token) return;
  marcarInicios();
  interceptarControles();
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
