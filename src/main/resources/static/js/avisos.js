// Notificaciones y mensajes nuevos: contadores, panel de avisos y avisos del sistema
// (notificación del navegador o de Android). Se consulta el servidor cada 20 s.
import { api, deUsuario, session } from './api.js';
import { $, $$, esc, timeAgo, bus, store } from './util.js';
import { icon } from './icons.js';
import { toast } from './ui.js';
import { avatarPersona } from './views/people.js';

const CADA_MS = 20_000;
let resumen = { mensajes: 0, notificaciones: 0 };

function pintarContadores() {
  $$('[data-badge-msg]').forEach((b) => { b.textContent = resumen.mensajes > 9 ? '9+' : resumen.mensajes; b.classList.toggle('hidden', !resumen.mensajes); });
  $$('[data-badge-notif]').forEach((b) => { b.textContent = resumen.notificaciones > 9 ? '9+' : resumen.notificaciones; b.classList.toggle('hidden', !resumen.notificaciones); });
}

/** Aviso del sistema: en la app de Android, notificación nativa; en el navegador, si se dio permiso. */
function avisoSistema(titulo, texto, ruta) {
  const app = window.SpaceMusicApp;
  if (app?.notificar) { try { app.notificar(titulo, texto, ruta || ''); } catch { } return; }
  if (!document.hidden || !('Notification' in window) || Notification.permission !== 'granted') return;
  try {
    const n = new Notification(titulo, { body: texto, icon: '/img/icon-192.png', tag: 'sm-' + (ruta || titulo) });
    n.onclick = () => { window.focus(); if (ruta) location.hash = ruta; n.close(); };
  } catch { }
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
}

/** Panel de notificaciones (campana). */
async function abrirPanel(anchor) {
  cerrarPanel();
  const panel = document.createElement('div');
  panel.className = 'notif-panel';
  panel.innerHTML = `<header><b>Notificaciones</b></header><div class="notif-list"><div class="muted" style="padding:16px">Cargando…</div></div>
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
  const lista = await api('/api/notificaciones').catch(() => []);
  const box = panel.querySelector('.notif-list');
  box.innerHTML = lista.length ? lista.map((n) => `<a class="notif ${n.leida ? '' : 'unread'}" href="${esc(n.ruta || '#/')}">
      ${n.de ? avatarPersona(n.de) : `<span class="p-avatar ini">${icon('bell', 'sm')}</span>`}
      <span><p>${esc(n.texto)}</p><small>${esc(timeAgo(n.fecha))}</small></span></a>`).join('')
    : `<div class="notif-empty">${icon('bell')}<span>No tienes notificaciones</span></div>`;
  box.addEventListener('click', (e) => { if (e.target.closest('a')) cerrarPanel(); });
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
    const texto = d.mensaje.cancion && d.mensaje.texto === '🎵' ? 'Te compartió una canción' : d.mensaje.texto;
    toast(`${d.nombre}: ${texto}`, { ms: 6000, action: { label: 'Ver', fn: () => { location.hash = ruta; } } });
    avisoSistema(d.nombre, texto, ruta);
  });
  fuente.addEventListener('visto', (e) => { try { bus.emit('dm:visto', JSON.parse(e.data)); } catch { } });
  fuente.addEventListener('notificacion', () => consultar());
}

export function iniciarAvisos() {
  conectarEnVivo();
  document.addEventListener('visibilitychange', () => {
    // Pestaña oculta: se cierra la conexión en vivo (cada pestaña abierta ocupaba una conexión
    // con el servidor y las demás peticiones esperaban). Al volver se reabre y se revisa lo nuevo.
    // En la app de Android se mantiene, para que lleguen los avisos con la app en segundo plano.
    if (document.hidden) {
      if (!window.SpaceMusicApp) { try { fuente?.close(); } catch { } fuente = null; }
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
