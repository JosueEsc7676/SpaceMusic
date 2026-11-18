// SyncPlay: escuchar juntos en vivo, con el control compartido.
// El servidor guarda el estado (canción, segundo con su hora, pausa, cola y versión) y todos lo siguen:
//  - Cualquier participante reproduce, pausa, cambia de canción (anterior/siguiente o una nueva) y mueve la posición.
//    Dentro de SyncPlay, los botones del reproductor de la app controlan para todos.
//  - Sincronía fina: la posición se calcula con el reloj del servidor (el mismo del control entre dispositivos);
//    los desfases pequeños se corrigen ajustando un poco la velocidad (sin saltos) y los grandes con un salto.
//  - El servidor pasa de canción solo al terminar cada una.
// Los avisos llegan al instante por la conexión en vivo (eventos «juntos» y «juntos-fin»).
import { api, session } from './api.js';
import { esc, bus, coverImg, cover, debounce, fmtTime } from './util.js';
import { icon } from './icons.js';
import { player } from './player.js';
import { setHero, setTitle } from './router.js';
import { toast, errorToast, emptyState, modal } from './ui.js';
import { avatarPersona } from './views/people.js';
import { enlaceSala, textoInvitacion } from './syncplay-enlace.js';
import { ahoraServidor } from './remoto.js';
import { crearAjuste, decidir } from './syncplay-ajuste.js';

// ═══════════════════ Motor de sincronía ═══════════════════

const motor = { sala: null, anfitrion: false, estado: null, version: -1, reloj: null, revisar: null, ajuste: crearAjuste(), aplicada: -1 };
const CTX = (id) => ({ type: 'juntos-oyente', label: 'SyncPlay', route: '#/juntos/' + id });
const original = {};
let aplicando = false;

/** SyncPlay activo (para el panel de Dispositivos). */
export const salaActiva = () => (motor.sala ? { id: motor.sala, anfitrion: motor.anfitrion } : null);

/** Posición del SyncPlay ahora mismo según el servidor. */
function objetivo(e = motor.estado) {
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
async function control(accion, datos = {}) {
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

function recibir(d) {
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

function activar(d) {
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

function desactivar(motivo) {
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

/** Comparte el enlace (hoja de compartir del sistema o, si no hay, se copia). */
export async function compartirEnlace(id) {
  const url = enlaceSala(id);
  if (!url) return;
  if (window.SpaceMusicApp?.compartir) { window.SpaceMusicApp.compartir('Escucha conmigo en SyncPlay\n' + url); return; }
  if (navigator.share) {
    try { await navigator.share({ title: 'SyncPlay', text: 'Escucha conmigo en SyncPlay', url }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  try { await navigator.clipboard.writeText(url); toast('Enlace copiado'); }
  catch { await modal({ title: 'Enlace de SyncPlay', body: `<input class="input" readonly value="${esc(url)}" style="width:100%" onfocus="this.select()">` }); }
}

/** Al abrir la app: si estabas en un SyncPlay, se retoma. */
export async function retomarSala() {
  const salas = await api('/api/juntos').catch(() => []);
  const d = salas.find((s) => s.dentro);
  if (d) activar(d);
}

// ═══════════════════ Pantallas ═══════════════════

const personaHtml = (p, extra = '') => `<span class="juntos-persona" title="${esc(p.nombre)}">${avatarPersona(p)}<span class="ellipsis">${esc(p.nombre)}</span>${extra}</span>`;

/** Elegir a un amigo e invitarlo (crea el SyncPlay si hace falta). */
export const invitarAmigo = () => invitar(null);

async function invitar(salaId) {
  const personas = await api('/api/mensajes/amigos').catch(() => []);
  let elegido = null;
  await modal({
    title: 'Invitar a SyncPlay',
    body: `<input class="input" data-q placeholder="Buscar por nombre o @usuario" aria-label="Buscar amigo" style="width:100%;margin-bottom:10px">
      <div class="ded-personas" data-l role="listbox"></div>
      <p class="muted" style="font-size:13px">Le llega un mensaje con el enlace de SyncPlay y una notificación.</p>`,
    actions: [{ label: 'Cancelar', value: null }, { label: 'Enviar invitación', value: 'ok', primary: true }],
    onOpen: (m) => {
      const pintar = (q = '') => {
        const f = q.toLowerCase().replace(/^@/, '');
        const l = personas.filter((p) => !f || (p.username + ' ' + p.nombre).toLowerCase().includes(f));
        m.querySelector('[data-l]').innerHTML = l.length ? l.map((p) => `<button class="ded-persona ${elegido === p.username ? 'sel' : ''}" data-u="${esc(p.username)}" role="option" aria-selected="${elegido === p.username}">
          ${avatarPersona(p)}<span class="ded-persona-txt"><b class="ellipsis">${esc(p.nombre)}</b><span class="muted ellipsis">@${esc(p.username)}</span></span></button>`).join('')
          : `<p class="muted">${personas.length ? 'Nadie coincide.' : 'Todavía no tienes amigos (personas que se siguen mutuamente).'}</p>`;
        m.querySelectorAll('[data-u]').forEach((b) => b.onclick = () => { elegido = b.dataset.u; pintar(m.querySelector('[data-q]').value); });
      };
      m.querySelector('[data-q]').addEventListener('input', (e) => pintar(e.target.value));
      pintar();
    },
    onAction: async (v) => {
      if (v !== 'ok') return;
      if (!elegido) { toast('Elige a un amigo'); return false; }
      const { sala } = await invitarPorMensaje(elegido);
      toast(`Invitación enviada a @${elegido}`);
      if (!salaId) location.hash = '#/juntos/' + sala.id;
    },
  }).catch(errorToast);
}

/** Buscar canciones: «Poner ahora» (suena para todos) o sumarla a la cola. */
async function agregarCancion(salaId) {
  await modal({
    title: 'Elegir canción',
    body: `<input class="input" data-q placeholder="Buscar canción o artista" aria-label="Buscar canción" style="width:100%;margin-bottom:10px">
      <div class="juntos-resultados" data-r><p class="muted">Escribe para buscar en el servidor.</p></div>`,
    actions: [{ label: 'Listo', value: null }],
    onOpen: (m) => {
      const buscar = debounce(async (q) => {
        if (q.trim().length < 2) return;
        const r = await api('/api/canciones/buscar?limite=15&q=' + encodeURIComponent(q)).catch(() => []);
        m.querySelector('[data-r]').innerHTML = r.length ? r.map((c) => `<div class="juntos-res">${coverImg(c.portadaUrl, 44)}
          <span class="ellipsis"><b>${esc(c.nombre)}</b><br><span class="muted">${esc(c.artista)}</span></span>
          <button class="btn sm" data-poner="${c.id}">${icon('play', 'sm')} Poner</button>
          <button class="icon-btn" data-sumar="${c.id}" aria-label="Sumar a la cola">${icon('plus', 'sm')}</button></div>`).join('') : '<p class="muted">Sin resultados.</p>';
        m.querySelectorAll('[data-poner]').forEach((b) => b.onclick = async () => {
          try { await api(`/api/juntos/${salaId}/control`, { method: 'POST', body: { accion: 'poner', cancionId: Number(b.dataset.poner) } }).then(recibir); toast('Suena para todos'); } catch (e) { errorToast(e); }
        });
        m.querySelectorAll('[data-sumar]').forEach((b) => b.onclick = async () => {
          try { await api(`/api/juntos/${salaId}/cola`, { method: 'POST', body: { cancionId: Number(b.dataset.sumar) } }); b.disabled = true; toast('Sumada a la cola'); } catch (e) { errorToast(e); }
        });
      }, 300);
      m.querySelector('[data-q]').addEventListener('input', (e) => buscar(e.target.value));
      setTimeout(() => m.querySelector('[data-q]').focus(), 50);
    },
  });
}

function pintarSala(el, d) {
  const c = d.cancion;
  const duracion = c?.duracionSegundos || 0;
  setHero(null);
  el.innerHTML = `<section class="juntos">
    <div class="juntos-fondo" ${c ? `style="background-image:url('${esc(cover(c.portadaUrl, 640))}')"` : ''}></div>
    <header class="juntos-cab"><span class="sop-chip warn juntos-vivo">En vivo</span>
      <h1>${d.soyAnfitrion ? 'Tu SyncPlay' : `SyncPlay de ${esc(d.anfitrion?.nombre || '')}`}</h1>
      <p class="muted">Todos controlan la música: reproducir, pausar, cambiar de canción y mover la posición. Suena igual para todos, en el mismo segundo.</p></header>
    <div class="juntos-ahora">${c ? `${coverImg(c.portadaUrl, 320, c.nombre, 'juntos-portada')}
      <div class="juntos-ahora-txt"><span class="muted">${d.sonando ? 'Sonando ahora' : 'En pausa'}</span><h2>${esc(c.nombre)}</h2><p class="muted">${esc(c.artista)}</p></div>`
      : `<div class="juntos-vacia">${icon('note')}<p>${d.dentro ? 'Elige una canción y empezará a sonar para todos.' : 'Únete para escuchar y controlar la música.'}</p></div>`}</div>
    ${d.dentro && c ? `<div class="juntos-control">
      <input type="range" class="remoto-seek" data-pos min="0" max="${Math.max(1, Math.round(duracion))}" step="1" value="${Math.round(objetivo(d))}" aria-label="Posición">
      <div class="remoto-tiempos"><span data-cur>${fmtTime(objetivo(d))}</span><span>${fmtTime(duracion)}</span></div>
      <div class="remoto-ctrls">
        <button class="np-cb" data-c="prev" aria-label="Anterior">${icon('rew')}</button>
        <button class="np-cb big" data-c="toggle" aria-label="${d.sonando ? 'Pausar' : 'Reproducir'}">${icon(d.sonando ? 'pause' : 'play')}</button>
        <button class="np-cb" data-c="next" aria-label="Siguiente">${icon('fwd')}</button>
      </div></div>` : ''}
    <div class="row wrap juntos-acc">
      ${!d.dentro ? `<button class="btn primary" data-unirse>${icon('headphones', 'sm')} Unirme a SyncPlay</button>` : ''}
      ${d.dentro ? `<button class="btn" data-sumar>${icon('search', 'sm')} Elegir canción</button>` : ''}
      ${d.soyAnfitrion ? `<button class="btn" data-invitar>${icon('send', 'sm')} Invitar a un amigo</button>` : ''}
      ${d.dentro ? `<button class="btn" data-compartir>${icon('share', 'sm')} Compartir enlace</button>` : ''}
      <button class="btn" data-salir>${d.soyAnfitrion ? 'Terminar SyncPlay' : d.dentro ? 'Salir' : 'No, gracias'}</button>
    </div>
    <h3 class="juntos-h">Escuchando (${d.personas.length})</h3>
    <div class="juntos-personas">${d.personas.map((p) => personaHtml(p, p.username === d.anfitrion?.username ? '<span class="sop-chip gray">Lo inició</span>' : '')).join('')}
      ${d.invitados.map((p) => personaHtml(p, '<span class="sop-chip gray">Invitado</span>')).join('')}</div>
    <h3 class="juntos-h">A continuación</h3>
    ${d.cola.length ? `<div class="juntos-cola">${d.cola.map((x) => `<div class="juntos-res">${coverImg(x.cancion.portadaUrl, 44)}
        <span class="ellipsis"><b>${esc(x.cancion.nombre)}</b><br><span class="muted">${esc(x.cancion.artista)} · la sumó ${esc(x.de?.nombre || '')}</span></span>
        ${d.dentro ? `<button class="btn sm" data-poner="${x.cancion.id}">${icon('play', 'sm')} Poner</button>
        <button class="icon-btn" data-quitar="${x.cancion.id}" aria-label="Quitar">${icon('close', 'sm')}</button>` : ''}</div>`).join('')}</div>`
      : `<p class="muted">${d.dentro ? 'La cola está vacía. Toca «Elegir canción» para sumar o poner canciones.' : 'Únete para sumar canciones.'}</p>`}
  </section>`;
  const id = d.id;
  el.querySelector('[data-unirse]')?.addEventListener('click', async () => {
    try { const r = await api(`/api/juntos/${id}/unirse`, { method: 'POST' }); activar(r); pintarSala(el, r); } catch (e) { errorToast(e); }
  });
  el.querySelector('[data-sumar]')?.addEventListener('click', () => agregarCancion(id));
  el.querySelector('[data-invitar]')?.addEventListener('click', () => invitar(id));
  el.querySelector('[data-compartir]')?.addEventListener('click', () => compartirEnlace(id));
  el.querySelector('[data-salir]')?.addEventListener('click', async () => {
    try { await api(`/api/juntos/${id}/salir`, { method: 'POST' }); } catch { }
    if (motor.sala === id) desactivar();
    location.hash = '#/juntos';
  });
  el.querySelectorAll('[data-c]').forEach((b) => b.onclick = () => {
    const a = b.dataset.c;
    if (a === 'toggle') control(motor.estado?.sonando ? 'pause' : 'play');
    else if (a === 'next') control('next', { desde: motor.estado?.cancion?.id ?? null });
    else control(a);
  });
  el.querySelector('[data-pos]')?.addEventListener('change', (e) => control('seek', { segundo: Number(e.target.value) }));
  el.querySelectorAll('[data-poner]').forEach((b) => b.onclick = () => control('poner', { cancionId: Number(b.dataset.poner) }));
  el.querySelectorAll('[data-quitar]').forEach((b) => b.onclick = () => api(`/api/juntos/${id}/cola/${b.dataset.quitar}`, { method: 'DELETE' }).catch(errorToast));
}

export async function juntosView(el, params, _q, isStale) {
  setTitle('SyncPlay');
  if (params.id) {
    let d;
    try { d = await api('/api/juntos/' + params.id); } catch (e) { el.innerHTML = emptyState('users', 'Este SyncPlay ya terminó', 'Termina cuando quien lo inició lo cierra o tras 30 minutos sin actividad.', '<a class="btn" href="#/juntos">Volver</a>'); return; }
    if (isStale?.()) return;
    if (d.dentro) activar(d);
    pintarSala(el, d);
    const alCambiar = (x) => { if (x.id === d.id && document.body.contains(el) && !document.activeElement?.matches?.('[data-pos]')) pintarSala(el, x); };
    const alTerminar = (x) => { if (x.sala === d.id && document.body.contains(el)) el.innerHTML = emptyState('users', 'Este SyncPlay terminó', esc(x.motivo || ''), '<a class="btn" href="#/juntos">Volver</a>'); };
    const quitar = [bus.on('juntos:vista', alCambiar), bus.on('juntos:vista-fin', alTerminar)];
    // Tiempo en pantalla
    const reloj = setInterval(() => {
      const cur = el.querySelector('[data-cur]'), pos = el.querySelector('[data-pos]');
      if (cur && motor.sala === d.id && document.activeElement !== pos) { const t = objetivo(); cur.textContent = fmtTime(t); if (pos) pos.value = Math.round(t); }
    }, 500);
    const fuera = () => { if (!location.hash.startsWith('#/juntos/' + d.id)) { quitar.forEach((f) => f()); clearInterval(reloj); window.removeEventListener('hashchange', fuera); } };
    window.addEventListener('hashchange', fuera);
    return;
  }
  const salas = await api('/api/juntos').catch(() => []);
  if (isStale?.()) return;
  el.innerHTML = `<h1 class="page-title">SyncPlay</h1>
    <p class="muted" style="max-width:640px">Inicia SyncPlay e invita a tus amigos: escucharán la misma canción en el mismo segundo
      y todos podrán reproducir, pausar, cambiar de canción y mover la posición.</p>
    <div class="row wrap" style="gap:10px;margin:16px 0 24px"><button class="btn primary" data-abrir>${icon('headphones', 'sm')} Iniciar SyncPlay</button>
      <button class="btn" data-invitar-amigo>${icon('send', 'sm')} Invitar a un amigo</button></div>
    ${salas.length ? `<h3 class="juntos-h">Tus SyncPlay e invitaciones</h3><div class="juntos-lista">${salas.map((s) => `<a class="juntos-item" href="#/juntos/${s.id}">
        ${s.cancion ? coverImg(s.cancion.portadaUrl, 64) : `<span class="juntos-item-ico">${icon('users')}</span>`}
        <span class="ellipsis"><b>${s.soyAnfitrion ? 'Tu SyncPlay' : 'SyncPlay de ' + esc(s.anfitrion?.nombre || '')}</b><br>
        <span class="muted">${s.dentro ? `${s.personas.length} escuchando` : 'Te invitaron'}${s.cancion ? ' · ' + esc(s.cancion.nombre) : ''}</span></span>
        ${!s.dentro ? '<span class="sop-chip warn">Invitación</span>' : ''}</a>`).join('')}</div>` : ''}`;
  el.querySelector('[data-abrir]').onclick = async () => {
    try { const d = await crearSala(); location.hash = '#/juntos/' + d.id; } catch (e) { errorToast(e); }
  };
  el.querySelector('[data-invitar-amigo]').onclick = () => invitar(null);
}
