// Motor de SyncPlay (sin pantallas): sincronía, órdenes y estado. Lo usan la web (juntos.js) y la TV (tv.js).
// Los avisos salen por el bus («toast»): cada interfaz los muestra a su manera.
import { api, session } from './api.js';
import { bus } from './util.js';
import { player } from './player.js';
import { enlaceSala, textoInvitacion } from './syncplay-enlace.js';
import { ahoraServidor } from './remoto.js';
import { crearAjuste, decidir } from './syncplay-ajuste.js';

const toast = (msg, o = {}) => bus.emit('toast', { msg, ...o });
const errorToast = (e) => bus.emit('toast', { msg: e?.message || 'No se pudo completar', error: true });

// ═══════════════════ Motor de sincronía ═══════════════════

export const motor = { sala: null, anfitrion: false, estado: null, version: -1, reloj: null, revisar: null, ajuste: crearAjuste(), aplicada: -1 };
const CTX = (id) => ({ type: 'juntos-oyente', label: 'SyncPlay', route: '#/juntos/' + id });
const original = {};
let aplicando = false;

/** SyncPlay activo (para el panel de Dispositivos). */
export const salaActiva = () => (motor.sala ? { id: motor.sala, anfitrion: motor.anfitrion } : null);

/** Posición del SyncPlay ahora mismo según el servidor. */
export function objetivo(e = motor.estado) {
  if (!e) return 0;
  const s = Number(e.segundo) || 0;
  const t = e.sonando && e.ts ? s + Math.max(0, ahoraServidor() - e.ts) / 1000 : s;
  const d = e.cancion?.duracionSegundos || 0;
  return d ? Math.min(t, d) : t;
}

// ── Mientras estás en un SyncPlay, los controles del reproductor actúan sobre el SyncPlay ──

function interceptar() {
  if (original.play) return;
  for (const k of ['play', 'toggle', 'resume', 'pause', 'next', 'prev', 'seek']) original[k] = player[k].bind(player);
  player.play = (songs, start = 0, context = null, opts = {}) => {
    if (aplicando || context?.type === 'juntos-oyente') return original.play(songs, start, context, opts);
    const c = songs?.[start];
    if (!c) return;
    control('poner', { cancionId: c.id, cola: songs.slice(start + 1, start + 21).map((x) => x.id) });
  };
  player.toggle = () => (aplicando ? original.toggle() : control(motor.estado?.sonando ? 'pause' : 'play'));
  player.resume = () => (aplicando ? original.resume() : control('play'));
  player.pause = () => (aplicando ? original.pause() : control('pause'));
  player.next = (auto) => (aplicando ? original.next(auto) : control('next', { desde: motor.estado?.cancion?.id ?? null }));
  player.prev = () => (aplicando ? original.prev() : control('prev'));
  player.seek = (t) => (aplicando ? original.seek(t) : control('seek', { segundo: t }));
}

function soltar() {
  for (const k of Object.keys(original)) { player[k] = original[k]; delete original[k]; }
  try { player.audio.playbackRate = 1; } catch { }
}

/** Ejecuta acciones del reproductor sin que vuelvan como órdenes para el SyncPlay. */
function sinEco(fn) { aplicando = true; try { fn(); } finally { aplicando = false; } }

/** Envía una orden al SyncPlay. Pausa, reproducir y posición se aplican aquí al instante (el servidor lo confirma). */
export async function control(accion, datos = {}) {
  if (!motor.sala) return;
  const e = motor.estado;
  if (e && (accion === 'pause' || accion === 'play' || accion === 'seek')) {
    const s = accion === 'seek' ? Number(datos.segundo) || 0 : objetivo(e);
    motor.estado = { ...e, segundo: s, ts: ahoraServidor(), sonando: accion === 'seek' ? e.sonando : accion === 'play' };
    aplicarAudio();
  }
  try {
    const d = await api(`/api/juntos/${motor.sala}/control`, { method: 'POST', body: { accion, ...datos } });
    recibir(d);
  } catch (err) {
    if (err.status === 404) desactivar('Este SyncPlay terminó'); else errorToast(err);
  }
}

/** Pone este reproductor en el estado del SyncPlay (canción, pausa y posición). */
function aplicarAudio() {
  const e = motor.estado;
  if (!e) return;
  if (!e.cancion) { if (player.playing) sinEco(() => original.pause()); return; }
  if (player.current?.id !== e.cancion.id) {
    sinEco(() => original.play([e.cancion, ...(e.cola || []).map((x) => x.cancion)], 0, CTX(motor.sala)));
    player.audio.addEventListener('loadedmetadata', () => {
      // Se apunta un poco adelante (lo que tarda en empezar a sonar) y se mide para aprenderlo
      const st = motor.ajuste;
      st.midiendo = true; st.esperaHasta = Date.now() + 2500;
      sinEco(() => original.seek(objetivo() + (motor.estado?.sonando ? st.adelanto : 0)));
      if (!motor.estado?.sonando) sinEco(() => original.pause());
    }, { once: true });
    return;
  }
  if (e.sonando && !player.playing) sinEco(() => original.resume());
  else if (!e.sonando && player.playing) sinEco(() => original.pause());
  // Un estado nuevo (alguien movió o reanudó) se sigue enseguida; el resto lo corrige afinar() sin saltar en bucle
  const nuevo = e.version == null || e.version !== motor.aplicada;
  motor.aplicada = e.version ?? -1;
  const desfase = (player.audio.currentTime || 0) - objetivo();
  if (!e.sonando) { if (Math.abs(desfase) > 0.3) sinEco(() => original.seek(objetivo())); return; }
  if (!nuevo || player.audio.readyState < 2) return;
  const r = decidir(motor.ajuste, desfase, Date.now(), { forzar: true });
  if (r.saltar != null) sinEco(() => original.seek(objetivo() + r.saltar));
}

/** Corrección fina cada medio segundo: velocidad 0,95–1,05 para desfases pequeños; salto si es grande. */
function afinar() {
  const e = motor.estado, a = player.audio;
  if (!e?.sonando || !e.cancion || player.current?.id !== e.cancion.id || !player.playing || a.readyState < 2) {
    if (a.playbackRate !== 1) a.playbackRate = 1;
    return;
  }
  const desfase = (a.currentTime || 0) - objetivo(); // positivo: vamos adelantados
  const r = decidir(motor.ajuste, desfase, Date.now());
  if (a.playbackRate !== r.velocidad) a.playbackRate = r.velocidad;
  if (r.saltar != null) sinEco(() => original.seek(objetivo() + r.saltar));
}

export function recibir(d) {
  if (!d || d.id !== motor.sala) return;
  if (!d.dentro) { desactivar(); return; }
  if (d.version != null && d.version < motor.version) return; // llegó tarde: ya hay uno más nuevo
  if (d.version != null) motor.version = d.version;
  const antes = motor.estado;
  motor.estado = d;
  motor.anfitrion = !!d.soyAnfitrion;
  aplicarAudio();
  // Aviso de lo que hizo otra persona (sin repetir lo que hice yo)
  const u = d.ultimaAccion;
  if (u && u.username && u.username !== session.user?.username && antes && (antes.version ?? -1) < d.version) {
    const que = { play: 'reanudó', pause: 'pausó', seek: 'movió la canción', next: 'pasó a la siguiente', prev: 'volvió a la anterior', poner: 'puso otra canción' }[u.accion];
    if (que) toast(`${u.nombre} ${que}`, { ms: 1800 });
  }
}

export function activar(d) {
  if (!d?.dentro) return;
  if (motor.sala !== d.id) { motor.version = -1; motor.estado = null; motor.ajuste = crearAjuste(); motor.aplicada = -1; }
  motor.sala = d.id;
  motor.anfitrion = !!d.soyAnfitrion;
  document.documentElement.classList.add('en-sala');
  interceptar();
  clearInterval(motor.reloj);
  motor.reloj = setInterval(afinar, 500);
  // Respaldo: cada 15 s se pide el estado por si se perdió un aviso
  clearInterval(motor.revisar);
  motor.revisar = setInterval(() => { if (motor.sala) api('/api/juntos/' + motor.sala).then(recibir).catch(() => { }); }, 15000);
  recibir(d);
}

export function desactivar(motivo) {
  motor.sala = null;
  motor.anfitrion = false;
  motor.estado = null;
  motor.version = -1;
  clearInterval(motor.reloj);
  clearInterval(motor.revisar);
  soltar();
  document.documentElement.classList.remove('en-sala');
  if (motivo) toast(motivo, { ms: 5000 });
}

bus.on('juntos', (d) => {
  if (d.id === motor.sala) recibir(d);
  bus.emit('juntos:vista', d);
});
bus.on('juntos-fin', (d) => {
  if (d.sala === motor.sala) desactivar(d.motivo || 'Este SyncPlay terminó');
  bus.emit('juntos:vista-fin', d);
});

/** Crea tu SyncPlay (o devuelve el que ya tienes). Si estás en el de otra persona, primero hay que salir. */
export async function crearSala() {
  if (motor.sala && !motor.anfitrion) throw new Error('Sal del SyncPlay actual antes de iniciar otro.');
  const d = await api('/api/juntos', { method: 'POST', body: {} });
  activar(d);
  return d;
}

/** Invita con la API y deja en el chat un mensaje real con el enlace (ambas cuentas lo pueden abrir). */
export async function invitarPorMensaje(username) {
  const sala = await crearSala();
  const d = await api('/api/juntos', { method: 'POST', body: { username } });
  if (d.id !== sala.id || !enlaceSala(d.id)) throw new Error('No se pudo preparar el enlace de SyncPlay.');
  const mensaje = await api('/api/mensajes/' + encodeURIComponent(username), { method: 'POST', body: { texto: textoInvitacion(d.id) } });
  bus.emit('dm:mensaje', { con: username, mensaje });
  return { sala: d, mensaje };
}

/** Al abrir la app: si estabas en un SyncPlay, se retoma. */
export async function retomarSala() {
  const salas = await api('/api/juntos').catch(() => []);
  const d = salas.find((s) => s.dentro);
  if (d) activar(d);
}

/** Unirse con el código de 6 letras y números (como Spotify Jam). */
export async function unirseConCodigoTexto(codigo) {
  const d = await api('/api/juntos/codigo', { method: 'POST', body: { codigo } });
  activar(d);
  return d;
}

/** Salir del SyncPlay (o terminarlo si lo iniciaste tú). */
export async function salirDeSala(id = motor.sala) {
  if (!id) return;
  try { await api(`/api/juntos/${id}/salir`, { method: 'POST' }); } catch { }
  if (motor.sala === id) desactivar();
}
