// Mensajes entre amigos (solo si se siguen los dos)
import { api, deUsuario, session } from '../api.js';
import { esc, timeAgo, coverImg, bus, store } from '../util.js';
import { icon } from '../icons.js';
import { setHero, setTitle } from '../router.js';
import { emptyState, errorToast, registerList, toast, showMenu, confirmDialog } from '../ui.js';
import { salaDeInvitacion } from '../syncplay-enlace.js';
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
      <span class="ellipsis">${m.mio ? 'Tú: ' : ''}${m.borrado ? 'Mensaje eliminado' : m.cancion ? '🎵 ' + esc(m.cancion.nombre) : salaDeInvitacion(m.texto) ? 'Invitación a SyncPlay' : esc(m.texto)}</span></span>
    <span class="conv-end"><small>${esc(timeAgo(m.fecha))}</small>${c.noLeidos ? `<i class="conv-badge">${c.noLeidos}</i>` : ''}</span></a>`;
}

/** Dirección del archivo del mensaje (foto o audio) con el permiso de la sesión. */
const urlArchivo = (m) => `${m.adjuntoUrl}?t=${encodeURIComponent(session.token || '')}`;
const fmtDur = (s) => `${Math.floor((s || 0) / 60)}:${String(Math.round(s || 0) % 60).padStart(2, '0')}`;

/** Nota de voz: botón de reproducir, barra de avance (se puede tocar para saltar) y duración. */
function notaDeVoz(url, duracion) {
  return `<div class="voz" data-voz="${esc(url)}" data-dur="${Number(duracion) || 0}">
    <button type="button" class="voz-play" aria-label="Reproducir mensaje de voz">${icon('play', 'sm')}</button>
    <span class="voz-barra" data-voz-barra><i></i></span><small data-voz-t>${fmtDur(duracion)}</small></div>`;
}

/** Un solo audio para todas las notas de voz de la pantalla (al tocar otra, la anterior se detiene). */
const vozAudio = new Audio();
let vozActual = null;
function pintarVoz(el, sonando) {
  if (!el) return;
  el.classList.toggle('sonando', sonando);
  const b = el.querySelector('.voz-play');
  b.innerHTML = icon(sonando ? 'pause' : 'play', 'sm');
  b.setAttribute('aria-label', sonando ? 'Pausar' : 'Reproducir mensaje de voz');
}
vozAudio.addEventListener('timeupdate', () => {
  if (!vozActual?.isConnected) return;
  const total = vozAudio.duration && isFinite(vozAudio.duration) ? vozAudio.duration : Number(vozActual.dataset.dur) || 0;
  vozActual.querySelector('.voz-barra i').style.width = total ? `${Math.min(100, (vozAudio.currentTime / total) * 100)}%` : '0';
  vozActual.querySelector('[data-voz-t]').textContent = fmtDur(vozAudio.currentTime);
});
vozAudio.addEventListener('ended', () => {
  if (!vozActual) return;
  pintarVoz(vozActual, false);
  vozActual.querySelector('.voz-barra i').style.width = '0';
  vozActual.querySelector('[data-voz-t]').textContent = fmtDur(Number(vozActual.dataset.dur));
});
vozAudio.addEventListener('pause', () => pintarVoz(vozActual, false));
vozAudio.addEventListener('play', () => pintarVoz(vozActual, true));
vozAudio.addEventListener('error', () => { if (vozActual) { pintarVoz(vozActual, false); toast('No se pudo reproducir el audio', { error: true }); } });

/** Toques en una nota de voz: reproducir o pausar, o saltar tocando la barra. */
function tocarVoz(e) {
  const el = e.target.closest('[data-voz]');
  if (!el) return false;
  const barra = e.target.closest('[data-voz-barra]');
  if (vozActual !== el) {
    if (vozActual) { pintarVoz(vozActual, false); vozActual.querySelector('.voz-barra i').style.width = '0'; }
    vozActual = el;
    vozAudio.src = el.dataset.voz;
  }
  if (barra) {
    const r = barra.getBoundingClientRect();
    const total = vozAudio.duration && isFinite(vozAudio.duration) ? vozAudio.duration : Number(el.dataset.dur) || 0;
    if (total) { vozAudio.currentTime = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * total; }
    if (vozAudio.paused) vozAudio.play().catch(() => { });
    return true;
  }
  if (vozAudio.paused) vozAudio.play().catch(() => { }); else vozAudio.pause();
  return true;
}

function burbuja(m) {
  const opc = `<button class="msg-opc" data-opc="${m.id}" data-mio="${m.mio ? 1 : 0}" aria-label="Opciones del mensaje">${icon('more', 'sm')}</button>`;
  if (m.borrado) {
    return `<div class="bubble ${m.mio ? 'mine' : ''} borrado" data-id="${m.id}"><p>${icon('close', 'sm')} Mensaje eliminado</p><time>${esc(hora(m.fecha))}</time></div>`;
  }
  // Foto o mensaje de voz (cifrados en el servidor; solo los dos de la conversación pueden abrirlos)
  let archivo = '';
  if (m.tipo === 'IMAGEN') {
    archivo = `<a class="msg-foto" href="${esc(urlArchivo(m))}" target="_blank" rel="noopener"><img src="${esc(urlArchivo(m))}" alt="Foto" loading="lazy"></a>`;
  } else if (m.tipo === 'AUDIO') {
    archivo = notaDeVoz(urlArchivo(m), m.duracion);
  }
  const textoArchivo = m.tipo && (m.texto === '📷 Foto' || m.texto === '🎤 Mensaje de voz');
  let cancion = '';
  if (m.cancion) {
    const lid = registerList([m.cancion], { type: 'mensajes', label: 'Compartida en mensajes' });
    cancion = `<div class="msg-song" data-act="play-list" data-list="${lid}" data-i="0">${coverImg(m.cancion.portadaUrl, 160)}
      <span><b class="ellipsis">${esc(m.cancion.nombre)}</b><small class="ellipsis">${esc(m.cancion.artista)}</small></span>${icon('play', 'sm')}</div>`;
  }
  // Invitación a SyncPlay: tarjeta para abrirlo y unirse (solo enlaces de este servidor; el servidor comprueba el acceso)
  const sala = salaDeInvitacion(m.texto);
  const texto = sala ? `<a class="msg-sync" href="#/juntos/${sala}" aria-label="Abrir SyncPlay y unirme">
      <b>${icon('headphones')} SyncPlay</b><span>${m.mio ? 'Invitaste a escuchar juntos' : 'Te invitaron a escuchar juntos'}</span>
      <span class="ir">Abrir SyncPlay y unirme ${icon('right', 'sm')}</span></a>`
    : (m.cancion && m.texto === '🎵') || textoArchivo ? '' : `<p>${esc(m.texto)}</p>`;
  const soloFoto = m.tipo === 'IMAGEN' && !texto && !cancion;
  return `<div class="bubble ${m.mio ? 'mine' : ''} ${sala ? 'sync' : ''} ${archivo ? 'con-archivo' : ''} ${soloFoto ? 'solo-foto' : ''} ${m.tipo === 'AUDIO' ? 'con-voz' : ''}" data-id="${m.id}">${opc}${archivo}${cancion}${texto}<time>${esc(hora(m.fecha))}${m.mio ? (m.leido ? ' · Visto' : '') : ''}</time></div>`;
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
    <aside class="dm-list"><header><h1 class="page-title">Mensajes</h1></header>
      <nav class="dm-accesos" aria-label="Escuchar y compartir con amigos">
        <a class="dm-acceso" href="#/juntos" style="--c:#8fd0ff">${icon('headphones')}SyncPlay</a>
        <a class="dm-acceso" href="#/coincidimos" style="--c:#b49cff">${icon('users')}Coincidimos</a>
        <a class="dm-acceso" href="#/dedicatorias" style="--c:#ff8fb8">${icon('heart')}Dedicatorias</a></nav>
      <div class="dm-scroll" data-lista>${ultimaLista ? htmlLista(...ultimaLista, username) : '<div class="dm-loading">Cargando…</div>'}</div></aside>
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
      <a class="dm-who" href="#/u/${encodeURIComponent(u.username)}">${avatarPersona(u)}<span><b>${esc(u.nombre)}</b><small>@${esc(u.username)}${d.puedeEscribir ? ' · Amigos' : ''}</small></span></a>
      <span class="dm-head-acc"><button class="icon-btn" data-syncplay aria-label="SyncPlay con este amigo" title="SyncPlay">${icon('headphones')}</button>
        <button class="icon-btn" data-chat-opc aria-label="Más opciones" title="Más opciones">${icon('more')}</button></span></header>
    <div class="dm-msgs" data-msgs>${d.mensajes.length ? d.mensajes.map(burbuja).join('')
      : `<div class="dm-hello">${avatarPersona(u, 'xl')}<b>${esc(u.nombre)}</b><span>Salúdale o compártele una canción.</span></div>`}</div>
    ${d.puedeEscribir ? `<form class="dm-send" data-form>
        <button type="button" class="icon-btn" data-mas title="Compartir música y conectar" aria-label="Compartir música y conectar">${icon('plus')}</button>
        <input data-text maxlength="1000" placeholder="Escribe un mensaje" autocomplete="off" enterkeyhint="send">
        <button type="button" class="icon-btn dm-mic" data-mic aria-label="Grabar mensaje de voz" title="Mantén para grabar un mensaje de voz">${icon('mic')}</button>
        <button class="send-btn" aria-label="Enviar">${icon('send')}</button>
        <input type="file" data-foto accept="image/*" hidden></form>`
      : `<div class="dm-locked">${icon('lock', 'sm')} ${d.bloqueado ? 'Bloqueaste a esta persona. Desbloquéala desde el menú ⋯ para volver a escribirle.' : 'Para escribirse tienen que seguirse los dos.'}</div>`}`;
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
  /** Foto o audio: se sube y el servidor lo guarda cifrado. */
  const enviarArchivo = async (blob, nombre, duracion) => {
    const fd = new FormData();
    fd.append('file', blob, nombre);
    if (duracion) fd.append('duracion', String(Math.round(duracion)));
    try {
      const m = await api('/api/mensajes/' + encodeURIComponent(u.username) + '/archivo', { method: 'POST', form: fd, timeout: 60000 });
      pintar([m]);
    } catch (err) { errorToast(err); }
  };
  const form = box.querySelector('[data-form]');
  const entradaFoto = form?.querySelector('[data-foto]');
  entradaFoto?.addEventListener('change', () => {
    const f = entradaFoto.files[0];
    entradaFoto.value = '';
    if (!f) return;
    if (f.size > 12 * 1024 * 1024) { toast('La foto no puede pasar de 12 MB', { error: true }); return; }
    enviarArchivo(f, f.name || 'foto.jpg');
  });
  // Mensaje de voz: tocar para empezar a grabar y otra vez para enviar (máximo 5 minutos)
  const mic = form?.querySelector('[data-mic]');
  let grabador = null, inicioGrab = 0, tope = null;
  mic?.addEventListener('click', async () => {
    if (grabador) { grabador.stop(); return; }
    if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) { toast('Este navegador no puede grabar audio'); return; }
    let flujo;
    try { flujo = await navigator.mediaDevices.getUserMedia({ audio: true }); } catch { toast('Permite el micrófono para grabar mensajes de voz', { error: true }); return; }
    const tipo = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus'].find((t) => MediaRecorder.isTypeSupported?.(t)) || '';
    const partes = [];
    grabador = new MediaRecorder(flujo, tipo ? { mimeType: tipo } : undefined);
    grabador.ondataavailable = (e) => { if (e.data.size) partes.push(e.data); };
    grabador.onstop = () => {
      flujo.getTracks().forEach((t) => t.stop());
      clearTimeout(tope);
      const dur = (Date.now() - inicioGrab) / 1000;
      mic.classList.remove('grabando');
      mic.setAttribute('aria-label', 'Grabar mensaje de voz');
      const blob = new Blob(partes, { type: grabador.mimeType || 'audio/webm' });
      grabador = null;
      if (dur < 0.8 || !blob.size) { toast('Mensaje de voz muy corto'); return; }
      revisarAntesDeEnviar(blob, dur);
    };
    inicioGrab = Date.now();
    grabador.start();
    mic.classList.add('grabando');
    mic.setAttribute('aria-label', 'Detener la grabación');
    toast('Grabando… toca el micrófono otra vez para terminar', { ms: 2500 });
    tope = setTimeout(() => grabador?.stop(), 5 * 60 * 1000);
  });
  /** Vista previa de la nota de voz: escucharla, descartarla o enviarla. */
  const revisarAntesDeEnviar = (blob, dur) => {
    const url = URL.createObjectURL(blob);
    const previa = document.createElement('div');
    previa.className = 'dm-previa-voz';
    previa.innerHTML = `<button type="button" class="icon-btn" data-descartar aria-label="Descartar">${icon('trash')}</button>
      ${notaDeVoz(url, dur)}
      <button type="button" class="send-btn" data-enviar-voz aria-label="Enviar mensaje de voz">${icon('send')}</button>`;
    form.style.display = 'none';
    form.after(previa);
    const cerrar = () => {
      if (vozActual && previa.contains(vozActual)) { vozAudio.pause(); vozActual = null; }
      previa.remove();
      form.style.display = '';
      URL.revokeObjectURL(url);
    };
    previa.addEventListener('click', (e) => {
      if (tocarVoz(e)) return;
      if (e.target.closest('[data-descartar]')) cerrar();
      else if (e.target.closest('[data-enviar-voz]')) {
        const nombre = blob.type.includes('mp4') ? 'voz.m4a' : blob.type.includes('ogg') ? 'voz.ogg' : 'voz.webm';
        cerrar();
        enviarArchivo(blob, nombre, dur);
      }
    });
  };
  // Opciones de cada mensaje: eliminar (el tuyo para los dos; el recibido, solo para ti)
  msgs.addEventListener('click', (e) => {
    if (tocarVoz(e)) return;
    const b = e.target.closest('[data-opc]');
    if (!b) return;
    const id = Number(b.dataset.opc), mio = b.dataset.mio === '1';
    showMenu(b, [
      { label: mio ? 'Eliminar para todos' : 'Eliminar para mí', icon: 'trash', danger: true, fn: async () => {
        if (mio && !(await confirmDialog('El mensaje se eliminará también para la otra persona.', { title: 'Eliminar mensaje', ok: 'Eliminar', danger: true }))) return;
        try { await api('/api/mensajes/item/' + id, { method: 'DELETE' }); quitarOMarcar(id, mio); } catch (err) { errorToast(err); }
      } },
    ]);
  });
  const quitarOMarcar = (id, paraTodos) => {
    const b = msgs.querySelector(`.bubble[data-id="${id}"]`);
    if (!b) return;
    if (paraTodos) b.outerHTML = burbuja({ id, mio: b.classList.contains('mine'), borrado: true, fecha: new Date().toISOString() });
    else b.remove();
  };
  escucha(bus.on('dm:borrado', (x) => {
    const b = box.isConnected && msgs.querySelector(`.bubble[data-id="${x.id}"]`);
    if (b && !b.classList.contains('borrado')) quitarOMarcar(x.id, true);
  }));
  // Menú del chat: bloquear o desbloquear
  box.querySelector('[data-chat-opc]')?.addEventListener('click', (e) => {
    showMenu(e.currentTarget, [
      { label: 'Ver perfil', icon: 'user', fn: () => { location.hash = '#/u/' + encodeURIComponent(u.username); } },
      d.bloqueado
        ? { label: 'Desbloquear', icon: 'lock', fn: async () => {
          try { await api('/api/mensajes/bloqueos', { method: 'POST', body: { username: u.username, bloquear: false } }); toast('Desbloqueaste a ' + u.nombre); abrirChat(box, u.username, isStale); } catch (err) { errorToast(err); }
        } }
        : { label: 'Bloquear', icon: 'lock', danger: true, fn: async () => {
          if (!(await confirmDialog(`${u.nombre} ya no podrá escribirte y tú tampoco a esta persona. No se le avisa.`, { title: 'Bloquear', ok: 'Bloquear', danger: true }))) return;
          try { await api('/api/mensajes/bloqueos', { method: 'POST', body: { username: u.username, bloquear: true } }); toast('Bloqueaste a ' + u.nombre); abrirChat(box, u.username, isStale); } catch (err) { errorToast(err); }
        } },
    ]);
  });
  form?.querySelector('[data-text]')?.addEventListener('focus', () => setTimeout(() => { ajustarAlTeclado(); bajar(); }, 250));
  form?.addEventListener('submit', (e) => {
    e.preventDefault();
    const inp = form.querySelector('[data-text]');
    const t = inp.value.trim();
    if (!t) return;
    inp.value = '';
    enviar(t);
  });
  // «+»: compartir la canción que suena, dedicarla a esta persona o iniciar Coincidimos con ella
  box.querySelector('[data-mas]')?.addEventListener('click', (e) => {
    const c = player.current;
    showMenu(e.currentTarget, [
      { label: 'Enviar una foto', icon: 'image', fn: () => entradaFoto?.click() },
      c ? { label: 'Enviar la canción que suena', icon: 'note', fn: () => enviar('', c.id) } : null,
      c ? { label: 'Dedicar esta canción', icon: 'heart', fn: () => import('../dedicatorias.js').then((m) => m.dedicarCancion(c, { para: u.username })).catch(errorToast) } : null,
      c ? null : { title: 'Pon una canción para enviarla o dedicarla' },
      { label: 'Iniciar Coincidimos', icon: 'users', fn: async () => {
        try { const r = await api('/api/coincidimos', { method: 'POST', body: { username: u.username } }); location.hash = '#/coincidimos/' + r.id; } catch (err) { errorToast(err); }
      } },
      { label: 'Mis dedicatorias', icon: 'gift', fn: () => { location.hash = '#/dedicatorias'; } },
    ]);
  });
  // Auriculares: iniciar SyncPlay con este amigo (envía un mensaje real con el enlace) o ver mis salas
  box.querySelector('[data-syncplay]')?.addEventListener('click', (e) => {
    let enviando = false;
    showMenu(e.currentTarget, [
      { label: 'Iniciar SyncPlay', icon: 'headphones', disabled: !d.puedeEscribir, fn: async () => {
        if (enviando) return;
        enviando = true;
        try {
          const j = await import('../juntos.js');
          const { mensaje } = await j.invitarPorMensaje(u.username);
          pintar([mensaje]);
          toast('Invitación a SyncPlay enviada');
        } catch (err) { errorToast(err); } finally { enviando = false; }
      } },
      { label: 'Mis SyncPlay', icon: 'list', fn: () => { location.hash = '#/juntos'; } },
    ]);
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
