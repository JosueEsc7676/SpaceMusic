// Mensajes entre amigos (solo si se siguen los dos)
import { api, deUsuario } from '../api.js';
import { esc, timeAgo, coverImg, bus, store } from '../util.js';
import { icon } from '../icons.js';
import { setHero, setTitle } from '../router.js';
import { emptyState, errorToast, registerList, toast } from '../ui.js';
import { player } from '../player.js';
import { avatarPersona } from './people.js';

let sondeo = null;

// Con datos móviles una petición puede quedarse colgada: se corta a los pocos segundos
// y se reintenta (3 intentos, cada vez con más paciencia).
async function conReintento(path, opts = {}) {
  let error;
  for (const espera of [8000, 12000, 20000]) {
    try { return await api(path, { ...opts, timeout: espera }); } catch (e) {
      error = e;
      if (e.status && e.status !== 0 && e.status < 500) throw e; // error real (no de red): no reintentar
      await new Promise((r) => setTimeout(r, 600));
    }
  }
  throw error;
}
const claveChat = (u) => deUsuario('sm_chat_' + u);
const hora = (iso) => { try { return new Date(iso).toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' }); } catch { return ''; } };

function filaConversacion(c, activa) {
  const u = c.usuario, m = c.ultimo;
  return `<a class="conv ${activa ? 'active' : ''} ${c.noLeidos ? 'unread' : ''}" href="#/mensajes/${encodeURIComponent(u.username)}">
    ${avatarPersona(u)}
    <span class="conv-tt"><b class="ellipsis">${esc(u.nombre)}</b>
      <span class="ellipsis">${m.mio ? 'Tú: ' : ''}${m.cancion ? '🎵 ' + esc(m.cancion.nombre) : esc(m.texto)}</span></span>
    <span class="conv-end"><small>${esc(timeAgo(m.fecha))}</small>${c.noLeidos ? `<i class="conv-badge">${c.noLeidos}</i>` : ''}</span></a>`;
}

function burbuja(m) {
  let cancion = '';
  if (m.cancion) {
    const lid = registerList([m.cancion], { type: 'mensajes', label: 'Compartida en mensajes' });
    cancion = `<div class="msg-song" data-act="play-list" data-list="${lid}" data-i="0">${coverImg(m.cancion.portadaUrl, 160)}
      <span><b class="ellipsis">${esc(m.cancion.nombre)}</b><small class="ellipsis">${esc(m.cancion.artista)}</small></span>${icon('play', 'sm')}</div>`;
  }
  const texto = m.cancion && m.texto === '🎵' ? '' : `<p>${esc(m.texto)}</p>`;
  return `<div class="bubble ${m.mio ? 'mine' : ''}" data-id="${m.id}">${cancion}${texto}<time>${esc(hora(m.fecha))}${m.mio ? (m.leido ? ' · Visto' : '') : ''}</time></div>`;
}

function htmlLista(convs, amigos, username) {
  const conConv = new Set(convs.map((c) => c.usuario.username));
  const nuevos = amigos.filter((a) => !conConv.has(a.username));

  const lista = convs.length || nuevos.length ? `
      ${convs.map((c) => filaConversacion(c, c.usuario.username === username)).join('')}
      ${nuevos.length ? `<div class="conv-label">Amigos</div>${nuevos.map((u) => `<a class="conv ${u.username === username ? 'active' : ''}" href="#/mensajes/${encodeURIComponent(u.username)}">${avatarPersona(u)}
        <span class="conv-tt"><b class="ellipsis">${esc(u.nombre)}</b><span>Escríbele</span></span></a>`).join('')}` : ''}`
    : `<div class="conv-empty">${icon('users')}<b>Aún no tienes amigos</b><span>Sigue a alguien y, cuando te siga también, podrán escribirse.</span><a class="btn sm" href="#/buscar?q=&tab=personas">Buscar personas</a></div>`;
  return lista;
}

let quitarEscuchas = [];

export async function mensajesView(el, params, _q, isStale) {
  clearInterval(sondeo);
  quitarEscuchas.forEach((f) => f());
  quitarEscuchas = [];
  const username = params.username || null;
  setTitle('Mensajes');
  setHero(null);
  const cargar = () => Promise.all([conReintento('/api/mensajes').catch(() => ultimaLista?.[0] || []),
    conReintento('/api/mensajes/amigos').catch(() => ultimaLista?.[1] || [])]);
  pantallaChat(!!username);

  // Se muestra al instante la última lista conocida; la actual llega en paralelo con el chat
  el.innerHTML = `<div class="dm ${username ? 'has-chat' : ''}">
    <aside class="dm-list"><header><h1 class="page-title">Mensajes</h1></header><div class="dm-scroll" data-lista>${ultimaLista ? htmlLista(...ultimaLista, username) : '<div class="dm-loading">Cargando…</div>'}</div></aside>
    <section class="dm-chat" data-chat>${username ? '<div class="dm-loading">Cargando…</div>'
      : emptyState('send', 'Tus mensajes', 'Elige una conversación. Solo puedes escribir a tus amigos: personas que se siguen mutuamente.')}</section></div>`;
  const pintarLista = async () => {
    const datos = await cargar();
    ultimaLista = datos;
    const box = el.querySelector('[data-lista]');
    if (box && !isStale()) box.innerHTML = htmlLista(...datos, username);
  };
  const tareas = [pintarLista()];

  // La lista de conversaciones se actualiza sola cuando llega o se envía un mensaje
  let pendiente = null;
  quitarEscuchas.push(bus.on('dm:mensaje', () => {
    clearTimeout(pendiente);
    pendiente = setTimeout(async () => {
      const box = el.querySelector('[data-lista]');
      if (!box?.isConnected) return;
      await pintarLista();
    }, 400);
  }));
  if (username) tareas.push(abrirChat(el.querySelector('[data-chat]'), username, isStale));
  // No se espera a la red: la pantalla aparece al instante y la lista y el chat se llenan al llegar
  // (con datos móviles esperar ambas cosas hacía que "Mensajes" pareciera no abrir nunca).
  Promise.all(tareas).catch(() => { });
}

let ultimaLista = null;

// ── Celular: el chat ocupa toda la pantalla y se ajusta al teclado ──
// Sin reproductor ni menú abajo, y con la altura del espacio visible (el teclado de iPhone
// achica la pantalla visible pero no la página, por eso se usa visualViewport).
function ajustarAlTeclado() {
  const vv = window.visualViewport;
  const r = document.documentElement.style;
  r.setProperty('--vvh', (vv ? vv.height : innerHeight) + 'px');
  r.setProperty('--vvt', (vv ? vv.offsetTop : 0) + 'px');
  const msgs = document.querySelector('.dm-msgs');
  if (msgs && document.activeElement?.matches?.('[data-text]')) msgs.scrollTop = msgs.scrollHeight;
}
let escuchandoTeclado = false;
function pantallaChat(activa) {
  document.body.classList.toggle('chat-abierto', activa);
  if (!escuchandoTeclado) {
    escuchandoTeclado = true;
    window.visualViewport?.addEventListener('resize', ajustarAlTeclado);
    window.visualViewport?.addEventListener('scroll', ajustarAlTeclado);
    window.addEventListener('hashchange', () => { if (!location.hash.startsWith('#/mensajes/')) document.body.classList.remove('chat-abierto'); });
  }
  ajustarAlTeclado();
}

async function abrirChat(box, username, isStale) {
  // Se muestra al instante la última copia guardada de la conversación (si la hay)
  const guardada = store.get(claveChat(username), null);
  let d;
  if (guardada?.usuario) {
    d = guardada;
    pintarChat(box, d, isStale, true);
  }
  try {
    d = await conReintento('/api/mensajes/' + encodeURIComponent(username));
  } catch (e) {
    if (guardada?.usuario) { toast('Sin conexión: mostrando los últimos mensajes guardados'); return; }
    box.innerHTML = `${emptyState('user', 'No se pudo abrir la conversación', e.message)}
      <div style="text-align:center;margin-top:-20px"><button class="btn" data-reintentar>${icon('refresh', 'sm')} Reintentar</button></div>`;
    box.querySelector('[data-reintentar]')?.addEventListener('click', () => {
      box.innerHTML = '<div class="dm-loading">Cargando…</div>';
      abrirChat(box, username, isStale);
    });
    return;
  }
  if (isStale()) return;
  try { store.set(claveChat(username), { ...d, mensajes: d.mensajes.slice(-60) }); } catch { }
  pintarChat(box, d, isStale, false);
}

function pintarChat(box, d, isStale, copia) {
  if (isStale()) return;
  // Al repintar (copia guardada → datos nuevos) se quitan las escuchas anteriores
  box._fin?.();
  const mias = [];
  box._fin = () => mias.splice(0).forEach((f) => f());
  const escucha = (f) => { mias.push(f); quitarEscuchas.push(f); };
  const u = d.usuario;
  if (!copia) bus.emit('mensajes:leidos');
  box.innerHTML = `<header class="dm-head"><a class="round-btn dm-back" href="#/mensajes" aria-label="Volver">${icon('left')}</a>
      <a class="dm-who" href="#/u/${encodeURIComponent(u.username)}">${avatarPersona(u)}<span><b>${esc(u.nombre)}</b><small>@${esc(u.username)}${d.puedeEscribir ? ' · Amigos' : ''}</small></span></a></header>
    <div class="dm-msgs" data-msgs>${d.mensajes.length ? d.mensajes.map(burbuja).join('')
      : `<div class="dm-hello">${avatarPersona(u, 'xl')}<b>${esc(u.nombre)}</b><span>Salúdale o compártele una canción.</span></div>`}</div>
    ${d.puedeEscribir ? `<form class="dm-send" data-form>
        <button type="button" class="icon-btn" data-share title="Compartir lo que estás escuchando" aria-label="Compartir canción">${icon('note')}</button>
        <input data-text maxlength="1000" placeholder="Escribe un mensaje" autocomplete="off" enterkeyhint="send">
        <button class="send-btn" aria-label="Enviar">${icon('send')}</button></form>`
      : `<div class="dm-locked">${icon('lock', 'sm')} Para escribirse tienen que seguirse los dos.</div>`}`;
  const msgs = box.querySelector('[data-msgs]');
  const bajar = () => { msgs.scrollTop = msgs.scrollHeight; };
  bajar();
  // La pantalla puede mostrarse un instante después: bajar otra vez al último mensaje
  requestAnimationFrame(() => requestAnimationFrame(bajar));
  setTimeout(bajar, 350);
  let ultimo = d.mensajes.at(-1)?.id || 0;

  const pintar = (nuevos) => {
    nuevos = nuevos.filter((m) => m.id > ultimo);
    if (!nuevos.length) return;
    msgs.querySelector('.dm-hello')?.remove();
    const abajo = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < 80;
    msgs.insertAdjacentHTML('beforeend', nuevos.map(burbuja).join(''));
    ultimo = nuevos.at(-1).id;
    if (abajo || nuevos.some((m) => m.mio)) bajar();
  };

  const enviar = async (texto, cancionId) => {
    try {
      const m = await api('/api/mensajes/' + encodeURIComponent(u.username), { method: 'POST', body: { texto, cancionId } });
      pintar([m]);
    } catch (err) { errorToast(err); }
  };
  const form = box.querySelector('[data-form]');
  form?.querySelector('[data-text]')?.addEventListener('focus', () => setTimeout(() => { ajustarAlTeclado(); bajar(); }, 250));
  form?.addEventListener('submit', (e) => {
    e.preventDefault();
    const inp = form.querySelector('[data-text]');
    const t = inp.value.trim();
    if (!t) return;
    inp.value = '';
    enviar(t);
  });
  box.querySelector('[data-share]')?.addEventListener('click', () => {
    if (!player.current) return toast('Pon una canción para compartirla');
    enviar('', player.current.id);
  });

  // En vivo: los mensajes de esta conversación aparecen al instante, sin recargar
  escucha(bus.on('dm:mensaje', (d) => {
    if (!box.isConnected || d.con !== u.username || d.mensaje.id <= ultimo) return;
    pintar([d.mensaje]);
    if (!d.mensaje.mio) api('/api/mensajes/' + encodeURIComponent(u.username) + '/leidos', { method: 'POST' }).then(() => bus.emit('mensajes:leidos')).catch(() => { });
  }));
  escucha(bus.on('dm:visto', (d) => {
    if (!box.isConnected || d.por !== u.username) return;
    msgs.querySelectorAll('.bubble.mine time').forEach((t) => { if (!t.textContent.includes('Visto')) t.textContent += ' · Visto'; });
  }));

  // Respaldo por si la conexión en vivo se corta (cada 15 s)
  clearInterval(sondeo);
  sondeo = setInterval(async () => {
    if (!box.isConnected) { clearInterval(sondeo); return; }
    if (document.hidden) return;
    try {
      const r = await api('/api/mensajes/' + encodeURIComponent(u.username), { timeout: 12000 });
      const nuevos = r.mensajes.filter((m) => m.id > ultimo);
      if (nuevos.length) { pintar(nuevos); bus.emit('mensajes:leidos'); }
      // Actualizar "Visto" de los míos
      r.mensajes.filter((m) => m.mio && m.leido).forEach((m) => {
        const t = msgs.querySelector(`.bubble[data-id="${m.id}"] time`);
        if (t && !t.textContent.includes('Visto')) t.textContent += ' · Visto';
      });
    } catch { /* sin conexión */ }
  }, 15000);
}
