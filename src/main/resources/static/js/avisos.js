// Notificaciones y mensajes nuevos: contadores, panel de avisos y avisos del sistema
// (notificación del navegador o de Android). Se consulta el servidor cada 20 s.
import { api, deUsuario, session } from './api.js';
import { $, $$, esc, timeAgo, bus, store } from './util.js';
import { icon } from './icons.js';
import { toast } from './ui.js';
import { avatarPersona } from './views/people.js';
import { salaDeInvitacion } from './syncplay-enlace.js';

const CADA_MS = 20_000;
let resumen = { mensajes: 0, notificaciones: 0 };

function pintarContadores() {
  $$('[data-badge-msg]').forEach((b) => { b.textContent = resumen.mensajes > 9 ? '9+' : resumen.mensajes; b.classList.toggle('hidden', !resumen.mensajes); });
  $$('[data-badge-notif]').forEach((b) => { b.textContent = resumen.notificaciones > 9 ? '9+' : resumen.notificaciones; b.classList.toggle('hidden', !resumen.notificaciones); });
}

/** Aviso del sistema: en la app de Android, notificación nativa; en el navegador, si se dio permiso. */
export function avisoSistema(titulo, texto, ruta, foto) {
  const app = window.SpaceMusicApp;
  // Con la foto de perfil de quien escribe (apps 2.6+); las anteriores, sin foto
  const fotoAbs = foto ? new URL(foto, location.origin).href : '';
  if (app?.notificarConFoto && fotoAbs) { try { app.notificarConFoto(titulo, texto, ruta || '', fotoAbs); } catch { } return; }
  if (app?.notificar) { try { app.notificar(titulo, texto, ruta || ''); } catch { } return; }
  if (!document.hidden || !('Notification' in window) || Notification.permission !== 'granted') return;
  try {
    const n = new Notification(titulo, { body: texto, icon: fotoAbs || '/img/icon-192.png', tag: 'sm-' + (ruta || titulo) });
    n.onclick = () => { window.focus(); if (ruta) location.hash = ruta; n.close(); };
  } catch { }
}

// Lista de notificaciones guardada en el dispositivo: el panel se abre al instante con ella
// y se actualiza en segundo plano (con datos móviles la lista podía tardar en llegar).
const claveLista = () => deUsuario('sm_notifs');
let trayendo = null;
function traerLista() {
  if (trayendo) return trayendo;
  trayendo = (async () => {
    for (const espera of [8000, 15000]) {
      try {
        const l = await api('/api/notificaciones', { timeout: espera });
        try { store.set(claveLista(), l.slice(0, 60)); } catch { }
        return l;
      } catch { /* se reintenta */ }
    }
    return null;
  })().finally(() => { trayendo = null; });
  return trayendo;
}

async function consultar() {
  let r;
  try { r = await api('/api/notificaciones/resumen'); } catch { return; }
  const vistos = store.get(deUsuario('sm_avisos_vistos'), { m: 0, n: 0 });
  const enChat = (u) => location.hash.startsWith('#/mensajes/' + encodeURIComponent(u));
  if (r.ultimoMensaje && r.ultimoMensaje.id > vistos.m) {
    vistos.m = r.ultimoMensaje.id;
    if (!enChat(r.ultimoMensaje.username)) {
      const ruta = '#/mensajes/' + encodeURIComponent(r.ultimoMensaje.username);
      toast(`${r.ultimoMensaje.de}: ${r.ultimoMensaje.texto}`, { ms: 6000, action: { label: 'Ver', fn: () => { location.hash = ruta; } } });
      avisoSistema(r.ultimoMensaje.de, r.ultimoMensaje.texto, ruta);
    }
  }
  if (r.ultimaNotificacion && r.ultimaNotificacion.id > vistos.n) {
    vistos.n = r.ultimaNotificacion.id;
    toast(r.ultimaNotificacion.texto, { ms: 6000, action: r.ultimaNotificacion.ruta ? { label: 'Ver', fn: () => { location.hash = r.ultimaNotificacion.ruta; } } : undefined });
    avisoSistema('SpaceMusic', r.ultimaNotificacion.texto, r.ultimaNotificacion.ruta);
  }
  store.set(deUsuario('sm_avisos_vistos'), vistos);
  resumen = r;
  pintarContadores();
  // Si hay algo nuevo (o aún no hay copia), se trae la lista ya, para que el panel abra al instante
  const copia = store.get(claveLista(), null);
  if (!copia || (r.ultimaNotificacion && copia[0]?.id !== r.ultimaNotificacion.id)) traerLista();
}

/** Panel de notificaciones (campana). */
const ICONO = { ACTUALIZACION: 'sparkles', AVISO: 'bell', SUBIDA: 'upload', ALERTA: 'shield', REPORTE: 'flag', COINCIDIMOS: 'users', DEDICATORIA: 'heart', CANCIONES: 'note', PLAYLIST: 'library', COLABORADOR: 'star', SEGUIDOR: 'user', AMIGO: 'users' };

function filaNotif(n) {
  const fig = n.de ? avatarPersona(n.de) : `<span class="notif-ico t-${esc(n.tipo)}">${icon(ICONO[n.tipo] || 'bell', 'sm')}</span>`;
  return `<div class="notif ${n.leida ? '' : 'unread'}" data-id="${n.id}">
    <a class="notif-main" href="${esc(n.ruta || '#/')}">${fig}<span><p>${esc(n.texto)}</p><small>${esc(timeAgo(n.fecha))}</small></span></a>
    <button class="icon-btn notif-x" data-borrar="${n.id}" aria-label="Borrar notificación" title="Borrar">${icon('close', 'sm')}</button></div>`;
}

async function abrirPanel(anchor) {
  cerrarPanel();
  const panel = document.createElement('div');
  panel.className = 'notif-panel';
  panel.innerHTML = `<header><b>Notificaciones</b><button class="btn ghost sm" data-vaciar>${icon('trash', 'sm')} Vaciar</button></header>
    <div class="notif-list"><div class="muted" style="padding:16px">Cargando…</div></div>
    ${'Notification' in window && !window.SpaceMusicApp && Notification.permission === 'default'
      ? `<button class="btn sm block" data-permiso>${icon('bell', 'sm')} Avisarme también con notificaciones del navegador</button>` : ''}`;
  document.body.appendChild(panel);
  const r = anchor.getBoundingClientRect();
  panel.style.top = r.bottom + 8 + 'px';
  panel.style.right = Math.max(8, innerWidth - r.right) + 'px';
  setTimeout(() => document.addEventListener('pointerdown', fuera, true), 0);
  panel.querySelector('[data-permiso]')?.addEventListener('click', async (e) => {
    const p = await Notification.requestPermission().catch(() => 'denied');
    e.target.remove();
    toast(p === 'granted' ? 'Listo: te avisaremos aunque tengas otra pestaña abierta' : 'No se activaron las notificaciones del navegador');
  });
  const box = panel.querySelector('.notif-list');
  const vacio = `<div class="notif-empty">${icon('bell')}<b>Todo al día</b><span>Aquí verás las actualizaciones de SpaceMusic, canciones nuevas, playlists de quienes sigues y nuevos seguidores.</span></div>`;
  const pintar = (lista) => {
    const nuevas = lista.filter((n) => !n.leida), vistas = lista.filter((n) => n.leida);
    box.innerHTML = lista.length
      ? (nuevas.length ? `<div class="notif-sec">Nuevas</div>${nuevas.map(filaNotif).join('')}` : '')
        + (vistas.length ? `<div class="notif-sec">Anteriores</div>${vistas.map(filaNotif).join('')}` : '')
      : vacio;
    panel.querySelector('[data-vaciar]').classList.toggle('hidden', !lista.length);
  };
  // Primero la copia guardada (al instante); luego la del servidor
  const copia = store.get(claveLista(), null);
  if (copia) pintar(copia);
  traerLista().then((l) => {
    if (!panel.isConnected) return;
    if (l) pintar(l);
    else if (!copia) box.innerHTML = `<div class="notif-empty">${icon('info')}<b>No se pudieron cargar</b><span>Revisa tu conexión e inténtalo otra vez.</span></div>`;
    // Ya se vieron: la copia queda marcada como leída
    if (l) try { store.set(claveLista(), l.slice(0, 60).map((n) => ({ ...n, leida: true }))); } catch { }
  });
  box.addEventListener('click', async (e) => {
    const x = e.target.closest('[data-borrar]');
    if (x) {
      e.preventDefault();
      x.closest('.notif')?.remove();
      api('/api/notificaciones/' + x.dataset.borrar, { method: 'DELETE' }).catch(() => { });
      try { store.set(claveLista(), (store.get(claveLista(), []) || []).filter((n) => String(n.id) !== x.dataset.borrar)); } catch { }
      if (!box.querySelector('.notif')) { box.innerHTML = vacio; panel.querySelector('[data-vaciar]').classList.add('hidden'); }
      return;
    }
    if (e.target.closest('a')) cerrarPanel();
  });
  panel.querySelector('[data-vaciar]').addEventListener('click', async () => {
    try {
      await api('/api/notificaciones', { method: 'DELETE' });
      try { store.set(claveLista(), []); } catch { }
      box.innerHTML = vacio;
      panel.querySelector('[data-vaciar]').classList.add('hidden');
    } catch (err) { toast('No se pudieron borrar'); }
  });
  if (resumen.notificaciones) {
    api('/api/notificaciones/leidas', { method: 'POST' }).catch(() => { });
    resumen.notificaciones = 0;
    pintarContadores();
  }
}
function fuera(e) { if (!e.target.closest('.notif-panel, [data-notif-btn]')) cerrarPanel(); }
function cerrarPanel() {
  $('.notif-panel')?.remove();
  document.removeEventListener('pointerdown', fuera, true);
}

// ── En vivo: el servidor envía mensajes, "visto" y notificaciones al instante ──
let fuente = null;
function conectarEnVivo() {
  if (!('EventSource' in window) || !session.token) return;
  try { fuente?.close(); } catch { }
  fuente = new EventSource('/api/eventos?t=' + encodeURIComponent(session.token));
  fuente.addEventListener('mensaje', (e) => {
    let d;
    try { d = JSON.parse(e.data); } catch { return; }
    bus.emit('dm:mensaje', d);
    if (d.mensaje.mio) return;
    const ruta = '#/mensajes/' + encodeURIComponent(d.con);
    if (location.hash.startsWith(ruta)) return; // ya lo está viendo
    const vistos = store.get(deUsuario('sm_avisos_vistos'), { m: 0, n: 0 });
    vistos.m = Math.max(vistos.m, d.mensaje.id);
    store.set(deUsuario('sm_avisos_vistos'), vistos);
    resumen.mensajes = (resumen.mensajes || 0) + 1;
    pintarContadores();
    const texto = d.mensaje.cancion && d.mensaje.texto === '🎵' ? 'Te compartió una canción'
      : salaDeInvitacion(d.mensaje.texto) ? 'Te invitó a escuchar juntos en SyncPlay' : d.mensaje.texto;
    toast(`${d.nombre}: ${texto}`, { ms: 6000, action: { label: 'Ver', fn: () => { location.hash = ruta; } } });
    avisoSistema(d.nombre, texto, ruta, d.foto);
  });
  fuente.addEventListener('mensaje-borrado', (e) => { try { bus.emit('dm:borrado', JSON.parse(e.data)); } catch { } });
  // El servidor avisa antes de reiniciarse: si se corta la conexión, se muestra "en mantenimiento"
  fuente.addEventListener('mantenimiento', () => bus.emit('servidor:mantenimiento'));
  fuente.addEventListener('visto', (e) => { try { bus.emit('dm:visto', JSON.parse(e.data)); } catch { } });
  fuente.addEventListener('notificacion', () => consultar());
  fuente.addEventListener('actividad', (e) => { try { bus.emit('amigo:actividad', JSON.parse(e.data)); } catch { } });
  // SyncPlay: estado y cierre
  // Control remoto entre tus dispositivos: órdenes y lista de dispositivos
  for (const ev of ['juntos', 'juntos-reaccion', 'juntos-fin', 'control-orden', 'control-dispositivos', 'control-ack']) {
    fuente.addEventListener(ev, (e) => { try { bus.emit(ev, JSON.parse(e.data)); } catch { } });
  }
}

export function iniciarAvisos() {
  conectarEnVivo();
  document.addEventListener('visibilitychange', () => {
    // Pestaña oculta: se cierra la conexión en vivo (cada pestaña abierta ocupaba una conexión
    // con el servidor y las demás peticiones esperaban). Al volver se reabre y se revisa lo nuevo.
    // En la app de Android se mantiene, para que lleguen los avisos con la app en segundo plano.
    if (document.hidden) {
      // Si está sonando música se mantiene: así este dispositivo sigue recibiendo órdenes de tus otros dispositivos
      const sonando = [...document.querySelectorAll('audio')].some((x) => !x.paused);
      if (!window.SpaceMusicApp && !sonando) { try { fuente?.close(); } catch { } fuente = null; }
    } else if (!fuente || fuente.readyState === 2) conectarEnVivo();
  });
  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-notif-btn]');
    if (!b) return;
    if ($('.notif-panel')) cerrarPanel(); else abrirPanel(b);
  });
  bus.on('mensajes:leidos', () => setTimeout(consultar, 300));
  document.addEventListener('visibilitychange', () => { if (!document.hidden) consultar(); });
  // La app de Android abre una pantalla al tocar una notificación
  window.smAbrir = (ruta) => { if (ruta) location.hash = ruta; };
  consultar();
  setInterval(consultar, CADA_MS);
}
