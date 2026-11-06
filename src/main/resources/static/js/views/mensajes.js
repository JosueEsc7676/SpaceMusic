// Mensajes entre amigos (solo si se siguen los dos)
import { api } from '../api.js';
import { esc, timeAgo, coverImg, bus } from '../util.js';
import { icon } from '../icons.js';
import { setHero, setTitle } from '../router.js';
import { emptyState, errorToast, registerList, toast } from '../ui.js';
import { player } from '../player.js';
import { avatarPersona } from './people.js';

let sondeo = null;
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

export async function mensajesView(el, params, _q, isStale) {
  clearInterval(sondeo);
  const username = params.username || null;
  setTitle('Mensajes');
  setHero(null);
  const [convs, amigos] = await Promise.all([api('/api/mensajes').catch(() => []), api('/api/mensajes/amigos').catch(() => [])]);
  if (isStale()) return;
  const conConv = new Set(convs.map((c) => c.usuario.username));
  const nuevos = amigos.filter((a) => !conConv.has(a.username));

  const lista = convs.length || nuevos.length ? `
      ${convs.map((c) => filaConversacion(c, c.usuario.username === username)).join('')}
      ${nuevos.length ? `<div class="conv-label">Amigos</div>${nuevos.map((u) => `<a class="conv ${u.username === username ? 'active' : ''}" href="#/mensajes/${encodeURIComponent(u.username)}">${avatarPersona(u)}
        <span class="conv-tt"><b class="ellipsis">${esc(u.nombre)}</b><span>Escríbele</span></span></a>`).join('')}` : ''}`
    : `<div class="conv-empty">${icon('users')}<b>Aún no tienes amigos</b><span>Sigue a alguien y, cuando te siga también, podrán escribirse.</span><a class="btn sm" href="#/buscar?q=&tab=personas">Buscar personas</a></div>`;

  el.innerHTML = `<div class="dm ${username ? 'has-chat' : ''}">
    <aside class="dm-list"><header><div class="eyebrow">Amigos</div><h1 class="page-title">Mensajes</h1></header><div class="dm-scroll">${lista}</div></aside>
    <section class="dm-chat" data-chat>${username ? '<div class="dm-loading">Cargando…</div>'
      : emptyState('send', 'Tus mensajes', 'Elige una conversación. Solo puedes escribir a tus amigos: personas que se siguen mutuamente.')}</section></div>`;
  if (username) await abrirChat(el.querySelector('[data-chat]'), username, isStale);
}

async function abrirChat(box, username, isStale) {
  let d;
  try { d = await api('/api/mensajes/' + encodeURIComponent(username)); } catch (e) {
    box.innerHTML = emptyState('user', 'No se pudo abrir la conversación', e.message);
    return;
  }
  if (isStale()) return;
  const u = d.usuario;
  bus.emit('mensajes:leidos');
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
  let ultimo = d.mensajes.at(-1)?.id || 0;

  const pintar = (nuevos) => {
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

  // Mensajes nuevos mientras la conversación está abierta
  clearInterval(sondeo);
  sondeo = setInterval(async () => {
    if (!box.isConnected) { clearInterval(sondeo); return; }
    if (document.hidden) return;
    try {
      const r = await api('/api/mensajes/' + encodeURIComponent(u.username));
      const nuevos = r.mensajes.filter((m) => m.id > ultimo);
      if (nuevos.length) { pintar(nuevos); bus.emit('mensajes:leidos'); }
      // Actualizar "Visto" de los míos
      r.mensajes.filter((m) => m.mio && m.leido).forEach((m) => {
        const t = msgs.querySelector(`.bubble[data-id="${m.id}"] time`);
        if (t && !t.textContent.includes('Visto')) t.textContent += ' · Visto';
      });
    } catch { /* sin conexión */ }
  }, 4000);
}
