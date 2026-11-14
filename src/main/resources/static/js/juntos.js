// SyncPlay (antes «Escuchar juntos»), en vivo: quien abre la sala (anfitrión) pone la música y todos escuchan la misma
// canción en el mismo segundo. Cualquiera en la sala suma canciones a la cola y reacciona.
// Los cambios llegan al instante por la conexión en vivo (eventos "juntos", "juntos-reaccion", "juntos-fin").
import { api, session } from './api.js';
import { esc, bus, coverImg, cover, debounce } from './util.js';
import { icon } from './icons.js';
import { player } from './player.js';
import { setHero, setTitle } from './router.js';
import { toast, errorToast, emptyState, modal } from './ui.js';
import { avatarPersona } from './views/people.js';
import { enlaceSala, textoInvitacion } from './syncplay-enlace.js';

// ═══════════════════ Motor de sincronía ═══════════════════

const motor = { sala: null, anfitrion: false, ultimoEnviado: null, agregadas: new Set(), revisar: null };
const CTX_OYENTE = (id) => ({ type: 'juntos-oyente', label: 'SyncPlay', route: '#/juntos/' + id });

/** Sala activa (para el panel de Dispositivos). */
export const salaActiva = () => (motor.sala ? { id: motor.sala, anfitrion: motor.anfitrion } : null);

/** Crea tu sala (o devuelve la que ya tienes abierta). Si estás escuchando en la sala de otra persona, primero hay que salir. */
export async function crearSala() {
  if (motor.sala && !motor.anfitrion) throw new Error('Sal de la sala actual antes de iniciar otra.');
  const d = await api('/api/juntos', { method: 'POST', body: {} });
  activar(d);
  return d;
}

/** Invita con la API de salas y deja en el chat un mensaje real con el enlace (ambas cuentas lo pueden abrir). */
export async function invitarPorMensaje(username) {
  const sala = await crearSala();
  const d = await api('/api/juntos', { method: 'POST', body: { username } });
  if (d.id !== sala.id || !enlaceSala(d.id)) throw new Error('No se pudo preparar el enlace de SyncPlay.');
  const mensaje = await api('/api/mensajes/' + encodeURIComponent(username), { method: 'POST', body: { texto: textoInvitacion(d.id) } });
  bus.emit('dm:mensaje', { con: username, mensaje });
  return { sala: d, mensaje };
}

/** Comparte el enlace de la sala (hoja de compartir del sistema o, si no hay, se copia). */
export async function compartirEnlace(id) {
  const url = enlaceSala(id);
  if (!url) return;
  if (window.SpaceMusicApp?.compartir) { window.SpaceMusicApp.compartir('Escucha conmigo en SyncPlay 🎧\n' + url); return; }
  if (navigator.share) {
    try { await navigator.share({ title: 'SyncPlay', text: 'Escucha conmigo en SyncPlay 🎧', url }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  try { await navigator.clipboard.writeText(url); toast('Enlace copiado'); }
  catch { await modal({ title: 'Enlace de la sala', body: `<input class="input" readonly value="${esc(url)}" style="width:100%" onfocus="this.select()">` }); }
}

function activar(d) {
  if (!d?.dentro) return;
  const nueva = motor.sala !== d.id;
  if (nueva) { motor.ultimoEnviado = null; motor.agregadas.clear(); }
  motor.sala = d.id;
  motor.anfitrion = !!d.soyAnfitrion;
  document.documentElement.classList.add('en-sala');
  clearInterval(motor.revisar);
  // El invitado corrige cada 20 s la diferencia con el anfitrión (por si se perdió un aviso)
  if (!motor.anfitrion) motor.revisar = setInterval(() => { if (motor.sala) api('/api/juntos/' + motor.sala).then(seguir).catch(() => { }); }, 20000);
  if (motor.anfitrion && nueva) enviarEstado(true);
}

function desactivar(motivo) {
  motor.sala = null;
  motor.anfitrion = false;
  motor.agregadas.clear();
  clearInterval(motor.revisar);
  document.documentElement.classList.remove('en-sala');
  if (motivo) toast(motivo, { ms: 5000 });
}

/** Invitado: poner la misma canción en el mismo segundo que el anfitrión. */
function seguir(d) {
  if (!d || motor.anfitrion || d.id !== motor.sala || !d.dentro) return;
  const recibido = performance.now();
  const objetivo = () => d.segundo + (d.sonando ? (performance.now() - recibido) / 1000 : 0) + 0.15;
  if (!d.cancion) { if (player.playing) player.pause(); return; }
  if (player.current?.id !== d.cancion.id) {
    player.play([d.cancion], 0, CTX_OYENTE(d.id));
    player.audio.addEventListener('loadedmetadata', () => {
      player.seek(objetivo());
      if (!d.sonando) player.pause();
    }, { once: true });
    return;
  }
  if (Math.abs(player.audio.currentTime - objetivo()) > 2) player.seek(objetivo());
  if (d.sonando && !player.playing) player.resume();
  else if (!d.sonando && player.playing) player.pause();
}

/** Anfitrión: suma a su propia cola lo que otros agregaron en la sala. */
function tomarCola(d) {
  if (!motor.anfitrion || d.id !== motor.sala) return;
  const nuevas = (d.cola || []).map((x) => x.cancion).filter((c) => c && !motor.agregadas.has(c.id));
  if (!nuevas.length) return;
  nuevas.forEach((c) => motor.agregadas.add(c.id));
  if (!player.current) player.play(nuevas, 0, { type: 'juntos', label: 'SyncPlay', route: '#/juntos/' + d.id });
  else player.addToQueue(nuevas);
}

/** Anfitrión: avisa qué suena y en qué segundo (al cambiar de canción, pausar, seguir o adelantar). */
function enviarEstado(forzar = false) {
  if (!motor.anfitrion || !motor.sala) return;
  const e = { cancionId: player.current?.id ?? null, segundo: player.audio.currentTime || 0, sonando: !!player.playing, t: performance.now() };
  const u = motor.ultimoEnviado;
  if (!forzar && u && u.cancionId === e.cancionId && u.sonando === e.sonando) {
    // Mismo estado: solo si se adelantó o atrasó (más de 3 s de diferencia con lo esperado)
    const esperado = u.segundo + (u.sonando ? (e.t - u.t) / 1000 : 0);
    if (Math.abs(esperado - e.segundo) < 3) return;
  }
  motor.ultimoEnviado = e;
  api(`/api/juntos/${motor.sala}/estado`, { method: 'POST', body: { cancionId: e.cancionId, segundo: e.segundo, sonando: e.sonando } })
    .catch((err) => { if (err.status === 404) desactivar('La sala terminó'); });
}
// El tiempo avanza varias veces por segundo: como mucho un intento cada 0,7 s (enviarEstado descarta lo repetido)
let ultimoIntento = 0, pendiente = null;
const enviarPronto = () => {
  if (!motor.anfitrion) return;
  const espera = 700 - (Date.now() - ultimoIntento);
  if (espera > 0) { clearTimeout(pendiente); pendiente = setTimeout(enviarPronto, espera); return; }
  ultimoIntento = Date.now();
  enviarEstado();
};
bus.on('player:song', () => enviarPronto());
bus.on('player:state', () => enviarPronto());
bus.on('player:time', () => enviarPronto());

bus.on('juntos', (d) => {
  if (d.id === motor.sala) { if (!d.dentro) desactivar(); else { seguir(d); tomarCola(d); } }
  bus.emit('juntos:vista', d);
});
bus.on('juntos-fin', (d) => {
  if (d.sala === motor.sala) desactivar(d.motivo || 'La sala terminó');
  bus.emit('juntos:vista-fin', d);
});
bus.on('juntos-reaccion', (d) => {
  if (d.sala !== motor.sala) return;
  flotar(d.emoji);
  if (!d.mia) toast(`${d.nombre} ${d.emoji}`, { ms: 1800 });
});

/** Emoji que sube por la pantalla. */
function flotar(emoji) {
  const n = document.createElement('span');
  n.className = 'juntos-flota';
  n.textContent = emoji;
  n.style.left = (12 + Math.random() * 76) + 'vw';
  document.body.appendChild(n);
  setTimeout(() => n.remove(), 2600);
}

/** Al abrir la app: si estabas en una sala, se retoma. */
export async function retomarSala() {
  const salas = await api('/api/juntos').catch(() => []);
  const d = salas.find((s) => s.dentro);
  if (d) { activar(d); seguir(d); tomarCola(d); }
}

// ═══════════════════ Pantallas ═══════════════════

const personaHtml = (p, extra = '') => `<span class="juntos-persona" title="${esc(p.nombre)}">${avatarPersona(p)}<span class="ellipsis">${esc(p.nombre)}</span>${extra}</span>`;

/** Elegir a un amigo e invitarlo (crea la sala si hace falta). */
export const invitarAmigo = () => invitar(null);

async function invitar(salaId) {
  // Amigos (se siguen los dos): a ellos se les puede escribir y les llega el enlace por mensaje
  const personas = await api('/api/mensajes/amigos').catch(() => []);
  let elegido = null;
  await modal({
    title: 'Invitar a SyncPlay',
    body: `<input class="input" data-q placeholder="Buscar por nombre o @usuario" aria-label="Buscar amigo" style="width:100%;margin-bottom:10px">
      <div class="ded-personas" data-l role="listbox"></div>
      <p class="muted" style="font-size:13px">Le llega un mensaje con el enlace de la sala y una notificación.</p>`,
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

async function agregarCancion(salaId) {
  await modal({
    title: 'Sumar a la cola',
    body: `<input class="input" data-q placeholder="Buscar canción o artista" aria-label="Buscar canción" style="width:100%;margin-bottom:10px">
      <div class="juntos-resultados" data-r><p class="muted">Escribe para buscar en el servidor.</p></div>`,
    actions: [{ label: 'Listo', value: null }],
    onOpen: (m) => {
      const buscar = debounce(async (q) => {
        if (q.trim().length < 2) return;
        const r = await api('/api/canciones/buscar?limite=15&q=' + encodeURIComponent(q)).catch(() => []);
        m.querySelector('[data-r]').innerHTML = r.length ? r.map((c) => `<button class="juntos-res" data-id="${c.id}">${coverImg(c.portadaUrl, 44)}
          <span class="ellipsis"><b>${esc(c.nombre)}</b><br><span class="muted">${esc(c.artista)}</span></span>${icon('plus', 'sm')}</button>`).join('') : '<p class="muted">Sin resultados.</p>';
        m.querySelectorAll('[data-id]').forEach((b) => b.onclick = async () => {
          try { await api(`/api/juntos/${salaId}/cola`, { method: 'POST', body: { cancionId: Number(b.dataset.id) } }); b.disabled = true; b.classList.add('hecho'); toast('Sumada a la cola'); } catch (e) { errorToast(e); }
        });
      }, 300);
      m.querySelector('[data-q]').addEventListener('input', (e) => buscar(e.target.value));
      setTimeout(() => m.querySelector('[data-q]').focus(), 50);
    },
  });
}

function pintarSala(el, d) {
  const c = d.cancion;
  setHero(null);
  el.innerHTML = `<section class="juntos">
    <div class="juntos-fondo" ${c ? `style="background-image:url('${esc(cover(c.portadaUrl, 640))}')"` : ''}></div>
    <header class="juntos-cab"><span class="sop-chip warn juntos-vivo">● En vivo</span>
      <h1>${d.soyAnfitrion ? 'Tu sala' : `Sala de ${esc(d.anfitrion?.nombre || '')}`}</h1>
      <p class="muted">${d.soyAnfitrion ? 'Lo que suene en tu reproductor lo escuchan todos, en el mismo segundo.' : `${esc(d.anfitrion?.nombre || '')} pone la música; tú la escuchas al mismo tiempo.`}</p></header>
    <div class="juntos-ahora">${c ? `${coverImg(c.portadaUrl, 320, c.nombre, 'juntos-portada')}
      <div><span class="muted">${d.sonando ? 'Sonando ahora' : 'En pausa'}</span><h2>${esc(c.nombre)}</h2><p class="muted">${esc(c.artista)}</p></div>`
      : `<div class="juntos-vacia">${icon('note')}<p>${d.soyAnfitrion ? 'Pon cualquier canción y empezará a sonar para todos.' : 'Esperando a que el anfitrión ponga música…'}</p></div>`}</div>
    ${d.dentro ? `<div class="juntos-reacciones">${(d.reacciones || []).map((e) => `<button data-emoji="${e}" aria-label="Reaccionar ${e}">${e}</button>`).join('')}</div>` : ''}
    <div class="row wrap juntos-acc">
      ${!d.dentro ? `<button class="btn primary" data-unirse>${icon('headphones', 'sm')} Unirme y escuchar</button>` : ''}
      ${d.dentro ? `<button class="btn" data-sumar>${icon('plus', 'sm')} Sumar canción</button>` : ''}
      ${d.soyAnfitrion ? `<button class="btn" data-invitar>${icon('send', 'sm')} Invitar a un amigo</button>` : ''}
      ${d.dentro ? `<button class="btn" data-compartir>${icon('share', 'sm')} Compartir enlace</button>` : ''}
      <button class="btn" data-salir>${d.soyAnfitrion ? 'Cerrar la sala' : d.dentro ? 'Salir' : 'No, gracias'}</button>
    </div>
    <h3 class="juntos-h">En la sala (${d.personas.length})</h3>
    <div class="juntos-personas">${d.personas.map((p) => personaHtml(p, p.username === d.anfitrion?.username ? '<span class="sop-chip gray">Pone la música</span>' : '')).join('')}
      ${d.invitados.map((p) => personaHtml(p, '<span class="sop-chip gray">Invitado</span>')).join('')}</div>
    <h3 class="juntos-h">A continuación</h3>
    ${d.cola.length ? `<div class="juntos-cola">${d.cola.map((x) => `<div class="juntos-res">${coverImg(x.cancion.portadaUrl, 44)}
        <span class="ellipsis"><b>${esc(x.cancion.nombre)}</b><br><span class="muted">${esc(x.cancion.artista)} · la sumó ${esc(x.de?.nombre || '')}</span></span>
        ${d.soyAnfitrion || x.de?.username === session.user?.username ? `<button class="icon-btn" data-quitar="${x.cancion.id}" aria-label="Quitar">${icon('close', 'sm')}</button>` : ''}</div>`).join('')}</div>`
      : `<p class="muted">${d.dentro ? 'Nadie sumó canciones todavía. Toca «Sumar canción».' : 'Únete para sumar canciones.'}</p>`}
  </section>`;
  const id = d.id;
  el.querySelector('[data-unirse]')?.addEventListener('click', async () => {
    try { const r = await api(`/api/juntos/${id}/unirse`, { method: 'POST' }); activar(r); seguir(r); pintarSala(el, r); } catch (e) { errorToast(e); }
  });
  el.querySelector('[data-sumar]')?.addEventListener('click', () => agregarCancion(id));
  el.querySelector('[data-invitar]')?.addEventListener('click', () => invitar(id));
  el.querySelector('[data-compartir]')?.addEventListener('click', () => compartirEnlace(id));
  el.querySelector('[data-salir]')?.addEventListener('click', async () => {
    try { await api(`/api/juntos/${id}/salir`, { method: 'POST' }); } catch { }
    if (motor.sala === id) desactivar();
    location.hash = '#/juntos';
  });
  el.querySelectorAll('[data-emoji]').forEach((b) => b.onclick = () => api(`/api/juntos/${id}/reaccion`, { method: 'POST', body: { emoji: b.dataset.emoji } }).catch(errorToast));
  el.querySelectorAll('[data-quitar]').forEach((b) => b.onclick = () => api(`/api/juntos/${id}/cola/${b.dataset.quitar}`, { method: 'DELETE' }).catch(errorToast));
}

export async function juntosView(el, params, _q, isStale) {
  setTitle('SyncPlay');
  if (params.id) {
    let d;
    try { d = await api('/api/juntos/' + params.id); } catch (e) { el.innerHTML = emptyState('users', 'Esta sala ya terminó', 'Las salas se cierran cuando el anfitrión sale o tras 30 minutos sin actividad.', '<a class="btn" href="#/juntos">Volver</a>'); return; }
    if (isStale?.()) return;
    if (d.dentro) activar(d);
    pintarSala(el, d);
    // Mientras la pantalla está abierta, se repinta con cada cambio de la sala
    const alCambiar = (x) => { if (x.id === d.id && document.body.contains(el)) pintarSala(el, x); };
    const alTerminar = (x) => { if (x.sala === d.id && document.body.contains(el)) el.innerHTML = emptyState('users', 'La sala terminó', esc(x.motivo || ''), '<a class="btn" href="#/juntos">Volver</a>'); };
    const quitar = [bus.on('juntos:vista', alCambiar), bus.on('juntos:vista-fin', alTerminar)];
    // Al salir de la pantalla se dejan de escuchar los cambios
    const fuera = () => { if (!location.hash.startsWith('#/juntos/' + d.id)) { quitar.forEach((f) => f()); window.removeEventListener('hashchange', fuera); } };
    window.addEventListener('hashchange', fuera);
    return;
  }
  const salas = await api('/api/juntos').catch(() => []);
  if (isStale?.()) return;
  el.innerHTML = `<h1 class="page-title">SyncPlay</h1>
    <p class="muted" style="max-width:640px">Abre una sala e invita a quien te sigue: escucharán la misma canción en el mismo segundo,
      podrán sumar canciones a la cola y reaccionar en vivo. Tú pones la música.</p>
    <div class="row wrap" style="gap:10px;margin:16px 0 24px"><button class="btn primary" data-abrir>${icon('headphones', 'sm')} Iniciar SyncPlay</button>
      <button class="btn" data-invitar-amigo>${icon('send', 'sm')} Invitar a un amigo</button></div>
    ${salas.length ? `<h3 class="juntos-h">Tus salas e invitaciones</h3><div class="juntos-lista">${salas.map((s) => `<a class="juntos-item" href="#/juntos/${s.id}">
        ${s.cancion ? coverImg(s.cancion.portadaUrl, 64) : `<span class="juntos-item-ico">${icon('users')}</span>`}
        <span class="ellipsis"><b>${s.soyAnfitrion ? 'Tu sala' : 'Sala de ' + esc(s.anfitrion?.nombre || '')}</b><br>
        <span class="muted">${s.dentro ? `${s.personas.length} en la sala` : 'Te invitaron'}${s.cancion ? ' · ' + esc(s.cancion.nombre) : ''}</span></span>
        ${!s.dentro ? '<span class="sop-chip warn">Invitación</span>' : ''}</a>`).join('')}</div>` : ''}`;
  el.querySelector('[data-abrir]').onclick = async () => {
    try { const d = await crearSala(); location.hash = '#/juntos/' + d.id; } catch (e) { errorToast(e); }
  };
  el.querySelector('[data-invitar-amigo]').onclick = () => invitar(null);
}
