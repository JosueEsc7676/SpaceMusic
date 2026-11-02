// SpaceMusic TV — interfaz para Android TV y televisores (control remoto)
window.SM_TV = true;

import { api, session } from './api.js';
import { esc, cover, fmtTime, fmtLong, plural, dominantColor, bus, store } from './util.js';
import { icon } from './icons.js';
import { player, prefs } from './player.js';
import { parseLrc } from './lrc.js';

const nativo = window.SpaceMusicTV || null; // puente con la APK (si existe)
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

// ═══════════════════ Utilidades de dibujo ═══════════════════

function img(url, size = 300) {
  return url ? `<img src="${esc(cover(url, size))}" alt="" loading="lazy" data-fb>` : `<div class="ph">${icon('note')}</div>`;
}
// Imagen que no carga → marcador con una nota musical
document.addEventListener('error', (e) => {
  const im = e.target;
  if (im?.tagName === 'IMG' && !im.dataset.failed) {
    im.dataset.failed = '1';
    const d = document.createElement('div');
    d.className = 'ph';
    d.innerHTML = icon('note');
    im.replaceWith(d);
  }
}, true);
function mosaic(urls) {
  const u = [...new Set((urls || []).filter(Boolean))];
  if (u.length < 4) return img(u[0]);
  return `<div class="mosaic">${u.slice(0, 4).map((x) => `<img src="${esc(cover(x, 160))}" alt="">`).join('')}</div>`;
}

let toastT;
function toast(msg) {
  let t = $('.toast');
  if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); }
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toastT);
  toastT = setTimeout(() => t.classList.add('hidden'), 2600);
}
bus.on('toast', (t) => toast(t.msg));

// ═══════════════════ Navegación con el control (flechas) ═══════════════════

let focused = null;

function capa() {
  if ($('.np.open')) return $('.np');
  return document;
}

function visibles() {
  return $$('.f', capa()).filter((e) => e.offsetParent !== null && !e.disabled);
}

function enfocar(el, { scroll = true } = {}) {
  if (!el) return;
  focused?.classList.remove('focused');
  focused = el;
  el.classList.add('focused');
  const enRail = !!el.closest('.rail');
  $('.rail')?.classList.toggle('open', enRail);
  if (scroll && !enRail) {
    const shelf = el.closest('.shelf');
    if (shelf) {
      const r = el.getBoundingClientRect(), sr = shelf.getBoundingClientRect();
      if (r.left < sr.left + 40) shelf.scrollLeft -= sr.left + 40 - r.left;
      if (r.right > sr.right - 40) shelf.scrollLeft += r.right - (sr.right - 40);
    }
    const screen = el.closest('.screen');
    if (screen) {
      const r = el.getBoundingClientRect(), vr = screen.getBoundingClientRect();
      const objetivo = el.closest('.row, .head, .actions, .search-box') || el;
      const or = objetivo.getBoundingClientRect();
      if (or.top < vr.top + 80 || r.bottom > vr.bottom - 120) {
        screen.scrollTop += or.top - vr.top - vr.height * 0.18;
      }
    }
  }
}

/** Busca el elemento más cercano en la dirección pulsada. */
function mover(dir) {
  const lista = visibles();
  if (!focused || !lista.includes(focused)) { enfocar(lista.find((e) => !e.closest('.rail')) || lista[0]); return; }
  const a = focused.getBoundingClientRect();
  const enRail = !!focused.closest('.rail');
  let mejor = null, mejorD = Infinity;
  for (const e of lista) {
    if (e === focused) continue;
    const eRail = !!e.closest('.rail');
    // El menú lateral solo se alcanza con "izquierda" desde el borde (ver más abajo)
    if (eRail !== enRail && !(enRail && dir === 'right')) continue;
    const b = e.getBoundingClientRect();
    // Distancias entre bordes: así una fila ancha no "pierde" contra algo lejano pero centrado
    const gapX = Math.max(0, b.left - a.right, a.left - b.right);
    const gapY = Math.max(0, b.top - a.bottom, a.top - b.bottom);
    let principal, lateral;
    if (dir === 'right') { if (b.left < a.right - 4) continue; principal = b.left - a.right; lateral = gapY; }
    else if (dir === 'left') { if (b.right > a.left + 4) continue; principal = a.left - b.right; lateral = gapY; }
    else if (dir === 'down') { if (b.top < a.bottom - 4) continue; principal = b.top - a.bottom; lateral = gapX; }
    else { if (b.bottom > a.top + 4) continue; principal = a.top - b.bottom; lateral = gapX; }
    // Preferir lo alineado; a igualdad, lo más cercano al borde izquierdo de lo actual
    const d = principal + lateral * 3 + Math.abs(b.left - a.left) * 0.05;
    if (d < mejorD) { mejorD = d; mejor = e; }
  }
  if (mejor) enfocar(mejor);
  else if (dir === 'left' && !focused.closest('.rail') && !$('.np.open')) enfocar($('.nav.active') || $('.nav'));
}

function activar() {
  if (!focused) return;
  if (focused.tagName === 'INPUT') { focused.focus(); return; }
  focused.click();
}

document.addEventListener('keydown', (e) => {
  despertar();
  const enInput = document.activeElement?.tagName === 'INPUT';
  const k = e.key;
  if (enInput) {
    // Dentro de un campo: las flechas arriba/abajo salen del campo; Enter envía el formulario
    if (k === 'ArrowDown' || k === 'ArrowUp') { document.activeElement.blur(); mover(k === 'ArrowDown' ? 'down' : 'up'); e.preventDefault(); }
    if (k === 'Enter') { const el = document.activeElement; setTimeout(() => el.blur(), 50); }
    if (k === 'Escape') document.activeElement.blur();
    return;
  }
  const mapa = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  if (mapa[k]) { e.preventDefault(); mover(mapa[k]); return; }
  if (k === 'Enter' || k === ' ') { e.preventDefault(); activar(); return; }
  if (k === 'Escape' || k === 'Backspace' || k === 'GoBack' || k === 'BrowserBack') { e.preventDefault(); atras(); return; }
  if (k === 'MediaPlayPause' || k === 'MediaPlay' || k === 'MediaPause') { e.preventDefault(); player.toggle(); }
  if (k === 'MediaTrackNext' || k === 'MediaFastForward') { e.preventDefault(); player.next(); }
  if (k === 'MediaTrackPrevious' || k === 'MediaRewind') { e.preventDefault(); player.prev(); }
});

// La APK llama a estas funciones con las teclas del control
window.tvBack = () => atras();
window.tvKey = (k) => {
  despertar();
  if (k === 'playpause') player.toggle();
  else if (k === 'next') player.next();
  else if (k === 'prev') player.prev();
  else if (k === 'ff') player.seekBy(15);
  else if (k === 'rew') player.seekBy(-15);
  else if (k === 'menu') abrirNP();
};

// ═══════════════════ Historial de pantallas ═══════════════════

const pila = [];

function ir(fn, ...args) {
  pila.push({ fn, args });
  return fn(...args);
}

/** Atrás: cierra lo que esté abierto o vuelve a la pantalla anterior. Devuelve false si no hay a dónde volver. */
function atras() {
  if ($('.np.open')) { cerrarNP(); return true; }
  if (focused?.closest('.rail')) { enfocar($('.screen .f')); return true; }
  if (pila.length > 1) {
    pila.pop();
    const { fn, args } = pila[pila.length - 1];
    fn(...args);
    return true;
  }
  if (!$('.nav.active')?.dataset.go || $('.nav.active').dataset.go !== 'inicio') { navegar('inicio'); return true; }
  return false;
}

// ═══════════════════ Estructura ═══════════════════

const SECCIONES = [
  ['inicio', 'home', 'Inicio'],
  ['buscar', 'search', 'Buscar'],
  ['megusta', 'heartFill', 'Me gusta'],
  ['playlists', 'library', 'Playlists'],
  ['artistas', 'user', 'Artistas'],
  ['albumes', 'disc', 'Álbumes'],
  ['historial', 'history', 'Recientes'],
];

function shell() {
  document.body.innerHTML = `
    <div class="tv">
      <nav class="rail">
        <div class="logo"><img src="/img/icon.svg" alt=""><span>SpaceMusic</span></div>
        ${SECCIONES.map(([k, ic, l]) => `<button class="nav f" data-go="${k}">${icon(ic)}<span>${l}</span></button>`).join('')}
        ${nativo?.openSettings ? `<button class="nav f user" data-go="servidor">${icon('sliders')}<span>Servidor</span></button>` : ''}
        <button class="nav f ${nativo?.openSettings ? '' : 'user'}" data-go="salir">${icon('logout')}<span>Cerrar sesión</span></button>
      </nav>
      <main class="main"><div class="screen" id="screen"></div></main>
    </div>
    <button class="mini f hidden" id="mini"></button>
    <div class="np" id="np"></div>`;
  $('.rail').addEventListener('click', (e) => {
    const b = e.target.closest('[data-go]');
    if (!b) return;
    if (b.dataset.go === 'salir') { session.clear(); location.reload(); return; }
    if (b.dataset.go === 'servidor') { nativo.openSettings(); return; }
    navegar(b.dataset.go);
  });
  $('#mini').addEventListener('click', abrirNP);
}

function navegar(seccion) {
  pila.length = 0;
  $$('.nav').forEach((n) => n.classList.toggle('active', n.dataset.go === seccion));
  const vistas = { inicio: vistaInicio, buscar: vistaBuscar, megusta: vistaMeGusta, playlists: vistaPlaylists,
    artistas: vistaArtistas, albumes: vistaAlbumes, historial: vistaHistorial };
  ir(vistas[seccion] || vistaInicio);
}

async function pantalla(html, { foco = true, color = null } = {}) {
  const s = $('#screen');
  s.innerHTML = `<div class="hero-glow"></div>${html}`;
  s.scrollTop = 0;
  if (color) document.documentElement.style.setProperty('--np', color.join(','));
  if (foco) enfocar($('.f', s), { scroll: false });
}

const cargando = () => { $('#screen').innerHTML = '<div class="loading">Cargando…</div>'; };

// ═══════════════════ Tarjetas ═══════════════════

const listas = new Map();
let listaSeq = 0;
function guardarLista(songs, ctx) { const id = 'L' + (++listaSeq); listas.set(id, { songs, ctx }); return id; }

const fila = (titulo, items) => (items ? `<section class="row"><h2>${esc(titulo)}</h2><div class="shelf">${items}</div></section>` : '');

function tarjetasCanciones(songs, ctx) {
  if (!songs?.length) return '';
  const id = guardarLista(songs, ctx);
  return songs.map((s, i) => `<button class="card f" data-play="${id}" data-i="${i}"><div class="art">${img(s.portadaUrl)}</div>
    <div class="t ellipsis">${esc(s.nombre)}</div><div class="s ellipsis">${esc(s.artista)}</div></button>`).join('');
}
const tAlbum = (a) => `<button class="card f" data-album="${esc(a.nombre)}"><div class="art">${img(a.portadaUrl)}</div>
  <div class="t ellipsis">${esc(a.nombre)}</div><div class="s ellipsis">${esc(a.artista)}</div></button>`;
const tArtista = (a) => `<button class="card round f" data-artista="${esc(a.nombre)}"><div class="art">${img(a.portadaUrl)}</div>
  <div class="t ellipsis">${esc(a.nombre)}</div><div class="s">${plural(a.totalCanciones, 'canción', 'canciones')}</div></button>`;
const tPlaylist = (p) => `<button class="card f" data-playlist="${p.id}"><div class="art">${p.portadaUrl ? img(p.portadaUrl) : mosaic(p.portadas)}</div>
  <div class="t ellipsis">${esc(p.nombre)}</div><div class="s">${plural(p.cancionesCount, 'canción', 'canciones')}</div></button>`;
const tMix = (m) => `<button class="card mix f" data-mix="${esc(m.id)}"><div class="art">${mosaic(m.portadas)}<div class="label">${esc(m.titulo)}</div></div>
  <div class="s ellipsis">${esc((m.artistas || []).join(', '))}</div></button>`;
const COLORES = ['#e13300', '#1e3264', '#8400e7', '#e8115b', '#148a08', '#bc5900', '#509bf5', '#dc148c', '#477d95', '#27856a'];
const tGenero = (g, i) => `<button class="genre f" data-genero="${esc(g.nombre)}" style="background:${COLORES[i % COLORES.length]}">${esc(g.nombre)}</button>`;

// Clics (Enter) sobre tarjetas
document.addEventListener('click', (e) => {
  const t = e.target.closest('[data-play],[data-album],[data-artista],[data-playlist],[data-mix],[data-genero]');
  if (!t) return;
  if (t.dataset.play) {
    const l = listas.get(t.dataset.play);
    if (l) { player.play(l.songs, +t.dataset.i, l.ctx); abrirNP(); }
  } else if (t.dataset.album) ir(vistaAlbum, t.dataset.album);
  else if (t.dataset.artista) ir(vistaArtista, t.dataset.artista);
  else if (t.dataset.playlist) ir(vistaPlaylist, t.dataset.playlist);
  else if (t.dataset.mix) ir(vistaMix, t.dataset.mix);
  else if (t.dataset.genero) ir(vistaGenero, t.dataset.genero);
});

// ═══════════════════ Vistas ═══════════════════

function saludo() {
  const h = new Date().getHours();
  return h < 6 ? 'Buenas noches' : h < 12 ? 'Buenos días' : h < 19 ? 'Buenas tardes' : 'Buenas noches';
}

async function vistaInicio() {
  cargando();
  const d = await api('/api/inicio');
  const nombre = session.user?.nombreDisplay?.split(' ')[0] || '';
  await pantalla(`<h1 class="title">${saludo()}${nombre ? ', ' + esc(nombre) : ''}</h1>
    ${fila('Hecho para ti', (d.mixes || []).map(tMix).join(''))}
    ${fila('Escuchado recientemente', tarjetasCanciones(d.recientes, { type: 'historial', label: 'Recientes' }))}
    ${fila('Lo que más escuchas', tarjetasCanciones(d.tusFavoritas, { type: 'top', label: 'Lo que más escuchas' }))}
    ${fila('Recién agregadas', tarjetasCanciones(d.recienAgregadas, { type: 'nuevas', label: 'Recién agregadas' }))}
    ${fila('Álbumes', (d.albumesRecientes || []).map(tAlbum).join(''))}
    ${fila('Artistas', (d.artistas || []).map(tArtista).join(''))}
    ${fila('Playlists de la comunidad', (d.playlistsPublicas || []).map(tPlaylist).join(''))}
    ${fila('Géneros', (d.generos || []).map(tGenero).join(''))}
    ${!d.totalCanciones ? '<p class="empty">Todavía no hay música en el servidor.</p>' : ''}`);
}

async function vistaBuscar(q = store.get('sm_tv_q', '')) {
  await pantalla(`<h1 class="title">Buscar</h1>
    <form class="search-box f" id="sf">${icon('search')}<input id="sq" value="${esc(q)}" placeholder="Artistas, canciones o álbumes" autocomplete="off"></form>
    <div id="sr"></div>`);
  const input = $('#sq');
  $('#sf').addEventListener('click', () => input.focus());
  const buscar = async () => {
    const v = input.value.trim();
    store.set('sm_tv_q', v);
    if (pila.length) pila[pila.length - 1].args = [v];
    if (v.length < 2) { $('#sr').innerHTML = ''; return; }
    $('#sr').innerHTML = '<div class="loading" style="height:20vh">Buscando…</div>';
    const r = await api('/api/buscar?q=' + encodeURIComponent(v));
    $('#sr').innerHTML = (fila('Canciones', tarjetasCanciones(r.canciones.slice(0, 30), { type: 'busqueda', label: 'Búsqueda' }))
      + fila('Artistas', r.artistas.map(tArtista).join(''))
      + fila('Álbumes', r.albumes.map(tAlbum).join(''))
      + fila('Playlists', r.playlists.map(tPlaylist).join(''))) || '<p class="empty">Sin resultados.</p>';
  };
  let t;
  input.addEventListener('input', () => { clearTimeout(t); t = setTimeout(buscar, 400); });
  $('#sf').addEventListener('submit', (e) => { e.preventDefault(); buscar(); });
  if (q) buscar();
}

async function vistaMeGusta() {
  cargando();
  const songs = await api('/api/usuario/favoritos');
  coleccion({ kind: 'Playlist', titulo: 'Me gusta', arte: `<div class="ph" style="background:linear-gradient(135deg,#4a1fb8,#8fa7d8);color:#fff">${icon('heartFill')}</div>`,
    meta: plural(songs.length, 'canción', 'canciones'), songs, ctx: { type: 'favoritos', label: 'Me gusta', route: '#/favoritos' }, color: [80, 56, 200] });
}

async function vistaPlaylists() {
  cargando();
  const [mias, publicas] = await Promise.all([api('/api/playlists'), api('/api/playlists/publicas').catch(() => [])]);
  await pantalla(`<h1 class="title">Playlists</h1>
    ${fila('Tus playlists', mias.map(tPlaylist).join('')) || '<p class="empty">Aún no tienes playlists. Créalas desde la web o el celular.</p>'}
    ${fila('De la comunidad', publicas.map(tPlaylist).join(''))}`);
}

async function vistaArtistas() {
  cargando();
  const a = await api('/api/artistas');
  await pantalla(`<h1 class="title">Artistas</h1>${grid(a.map(tArtista))}`);
}

async function vistaAlbumes() {
  cargando();
  const a = await api('/api/albumes');
  await pantalla(`<h1 class="title">Álbumes</h1>${grid(a.map(tAlbum))}`);
}

async function vistaHistorial() {
  cargando();
  const songs = await api('/api/usuario/recientes?limite=60');
  coleccion({ kind: 'Historial', titulo: 'Escuchado recientemente', arte: `<div class="ph">${icon('history')}</div>`,
    meta: plural(songs.length, 'canción', 'canciones'), songs, ctx: { type: 'historial', label: 'Recientes' } });
}

/** Rejilla dividida en filas de 6 para que las flechas funcionen bien. */
function grid(items) {
  if (!items.length) return '<p class="empty">No hay nada aquí todavía.</p>';
  let html = '';
  for (let i = 0; i < items.length; i += 6) html += `<section class="row"><div class="shelf">${items.slice(i, i + 6).join('')}</div></section>`;
  return html;
}

async function vistaAlbum(nombre) {
  cargando();
  const a = await api('/api/albumes/detalle?nombre=' + encodeURIComponent(nombre));
  coleccion({ kind: 'Álbum', titulo: a.nombre, arte: img(a.portadaUrl, 640),
    meta: `${esc(a.artista)}${a.anio ? ' · ' + a.anio : ''} · ${plural(a.totalCanciones, 'canción', 'canciones')} · ${fmtLong(a.duracionTotal)}`,
    songs: a.canciones, ctx: { type: 'album', label: a.nombre, route: '#/album/' + encodeURIComponent(a.nombre) }, colorDe: a.portadaUrl, pista: true });
}

async function vistaArtista(nombre) {
  cargando();
  const a = await api('/api/artistas/' + encodeURIComponent(nombre));
  const todas = [...a.populares, ...a.canciones.filter((c) => !a.populares.some((p) => p.id === c.id))];
  coleccion({ kind: 'Artista', titulo: a.nombre, arte: img(a.portadaUrl, 640), redondo: true,
    meta: `${plural(a.totalCanciones, 'canción', 'canciones')} · ${plural(a.totalAlbumes, 'álbum', 'álbumes')}`,
    songs: todas, ctx: { type: 'artista', label: a.nombre }, colorDe: a.portadaUrl, radio: true,
    extra: fila('Discografía', a.albumes.map(tAlbum).join('')) + fila('Artistas parecidos', a.relacionados.map(tArtista).join('')) });
}

async function vistaPlaylist(id) {
  cargando();
  const p = await api('/api/playlists/' + id);
  coleccion({ kind: 'Playlist', titulo: p.nombre, arte: p.portadaUrl ? img(p.portadaUrl, 640) : mosaic(p.portadas),
    meta: `${p.autor ? 'De ' + esc(p.autor) + ' · ' : ''}${plural(p.cancionesCount, 'canción', 'canciones')} · ${fmtLong(p.duracionTotal)}`,
    songs: p.canciones, ctx: { type: 'playlist', label: p.nombre }, colorDe: p.portadaUrl || p.portadas?.[0] });
}

async function vistaMix(id) {
  cargando();
  const songs = await api(`/api/mixes/${encodeURIComponent(id)}?cantidad=40`);
  const titulo = id === 'diario' ? 'Mix del día' : id === 'descubrir' ? 'Descubrir' : 'Mix de ' + id.split(':').slice(1).join(':');
  coleccion({ kind: 'Mezcla', titulo, arte: mosaic(songs.map((s) => s.portadaUrl)), meta: plural(songs.length, 'canción', 'canciones'),
    songs, ctx: { type: 'mix', label: titulo }, colorDe: songs[0]?.portadaUrl });
}

async function vistaGenero(nombre) {
  cargando();
  const g = await api('/api/generos/' + encodeURIComponent(nombre));
  coleccion({ kind: 'Género', titulo: g.nombre, arte: mosaic(g.canciones.map((s) => s.portadaUrl)), meta: plural(g.canciones.length, 'canción', 'canciones'),
    songs: g.canciones, ctx: { type: 'genero', label: g.nombre }, colorDe: g.canciones[0]?.portadaUrl });
}

/** Pantalla de colección: encabezado, botones y lista de canciones. */
async function coleccion({ kind, titulo, arte, meta, songs, ctx, redondo, colorDe, color, pista, radio, extra = '' }) {
  const id = guardarLista(songs, ctx);
  const cur = player.current?.id;
  const filas = songs.map((s, i) => `<button class="track f ${cur === s.id ? 'playing' : ''}" data-play="${id}" data-i="${i}">
    <span class="n">${pista && s.numeroPista ? s.numeroPista : i + 1}</span>${img(s.portadaUrl, 64)}
    <span style="min-width:0"><div class="name ellipsis">${esc(s.nombre)}</div><div class="artist ellipsis">${esc(s.artista)}</div></span>
    <span class="d">${fmtTime(s.duracionSegundos)}</span></button>`).join('');
  await pantalla(`<div class="head"><div class="cover ${redondo ? 'round' : ''}">${arte}</div>
      <div style="min-width:0"><div class="kind">${esc(kind)}</div><h1 class="ellipsis">${esc(titulo)}</h1><div class="meta">${meta}</div></div></div>
    <div class="actions">
      ${songs.length ? `<button class="btn primary f" data-acc="play">${icon('play')} Reproducir</button>
      <button class="btn f" data-acc="shuffle">${icon('shuffle')} Aleatorio</button>` : ''}
      ${radio && songs.length ? `<button class="btn f" data-acc="radio">${icon('radio')} Radio</button>` : ''}
    </div>
    ${songs.length ? `<section class="row"><div class="tracks">${filas}</div></section>` : '<p class="empty">No hay canciones.</p>'}
    ${extra}`, { color: color || null });
  if (colorDe) dominantColor(colorDe).then((c) => document.documentElement.style.setProperty('--np', c.join(',')));
  $('.actions')?.addEventListener('click', (e) => {
    const a = e.target.closest('[data-acc]')?.dataset.acc;
    if (a === 'play') { player.play(songs, 0, ctx, { shuffle: false }); abrirNP(); }
    if (a === 'shuffle') { player.play(songs, -1, ctx, { shuffle: true }); abrirNP(); }
    if (a === 'radio') { player.startRadio(songs[0]).then(abrirNP); }
  });
}

// ═══════════════════ Mini reproductor ═══════════════════

function pintarMini() {
  const s = player.current;
  const m = $('#mini');
  if (!m) return;
  m.classList.toggle('hidden', !s);
  if (!s) return;
  m.classList.toggle('paused', !player.playing);
  m.innerHTML = `${img(s.portadaUrl, 160)}<span style="min-width:0;text-align:left"><div class="t ellipsis">${esc(s.nombre)}</div><div class="s ellipsis">${esc(s.artista)}</div></span>
    <span class="eq"><i></i><i></i><i></i></span><span class="bar"><i></i></span>`;
}
function pintarBarra() {
  const a = player.audio;
  const p = a.duration ? (a.currentTime / a.duration) * 100 : 0;
  const b = $('#mini .bar i');
  if (b) b.style.width = p + '%';
  if ($('.np.open')) {
    $('#np .rail2 i').style.width = p + '%';
    $('#np [data-cur]').textContent = fmtTime(a.currentTime);
    $('#np [data-dur]').textContent = fmtTime(a.duration || player.current?.duracionSegundos);
  }
}

// ═══════════════════ Reproduciendo ahora ═══════════════════

let lineas = [], activa = -2, raf = 0, idleT;

function despertar() {
  const np = $('#np');
  if (!np) return;
  np.classList.remove('idle');
  clearTimeout(idleT);
  if (np.classList.contains('open')) idleT = setTimeout(() => np.classList.add('idle'), 8000);
}

function abrirNP() {
  if (!player.current) return;
  const np = $('#np');
  np.classList.add('open');
  pintarNP(true);
  enfocar($('[data-c="play"]', np), { scroll: false });
  despertar();
  cancelAnimationFrame(raf);
  const loop = () => { if (!np.classList.contains('open')) return; actualizarLetra(false); raf = requestAnimationFrame(loop); };
  loop();
}

function cerrarNP() {
  $('#np').classList.remove('open');
  cancelAnimationFrame(raf);
  enfocar($('.screen .focused') || $('.screen .f'), { scroll: false });
}

function pintarNP(cambio) {
  const np = $('#np');
  const s = player.current;
  if (!s || !np) return;
  if (cambio || !np.innerHTML) {
    np.innerHTML = `<div class="np-bg" style="background-image:url('${esc(cover(s.portadaUrl, 300))}')"></div>
      <div class="np-left">
        <div class="np-art">${img(s.portadaUrl, 640)}</div>
        <div><div class="np-name">${esc(s.nombre)}</div><div class="np-artist">${esc(s.artista)}${s.album && s.album !== 'Single' ? ' · ' + esc(s.album) : ''}</div></div>
        <div class="np-prog"><div class="rail2"><i></i></div><div class="times"><span data-cur>0:00</span><span data-dur>0:00</span></div></div>
        <div class="np-ctrls">
          <button class="cb f" data-c="shuffle">${icon('shuffle')}</button>
          <button class="cb f" data-c="prev">${icon('prev')}</button>
          <button class="cb big f" data-c="play">${icon(player.playing ? 'pause' : 'play')}</button>
          <button class="cb f" data-c="next">${icon('next')}</button>
          <button class="cb f" data-c="fav">${icon('heart')}</button>
        </div>
      </div>
      <div class="np-lyrics" id="lyr"><div class="none">Cargando letra…</div></div>
      <div class="np-up" id="up"></div>`;
    np.querySelector('.np-ctrls').addEventListener('click', (e) => {
      const c = e.target.closest('[data-c]')?.dataset.c;
      if (c === 'play') player.toggle();
      else if (c === 'prev') player.prev();
      else if (c === 'next') player.next();
      else if (c === 'shuffle') { player.toggleShuffle(); toast(player.shuffle ? 'Aleatorio activado' : 'Aleatorio desactivado'); }
      else if (c === 'fav') alternarFav();
    });
    dominantColor(s.portadaUrl).then((c) => np.style.setProperty('--np', c.join(',')));
    cargarLetra(s);
    estadoFav();
  }
  np.classList.toggle('paused', !player.playing);
  const pb = np.querySelector('[data-c="play"]');
  if (pb) pb.innerHTML = icon(player.playing ? 'pause' : 'play');
  np.querySelector('[data-c="shuffle"]')?.classList.toggle('on', player.shuffle);
  const sig = player.items[player.index + 1];
  const up = $('#up');
  if (up) up.textContent = sig ? `A continuación: ${sig.nombre} · ${sig.artista}` : '';
  pintarBarra();
}

let favs = new Set();
async function cargarFavs() { favs = new Set(await api('/api/usuario/favoritos/ids').catch(() => [])); }
function estadoFav() {
  const b = $('#np [data-c="fav"]');
  if (!b || !player.current) return;
  const f = favs.has(player.current.id);
  b.classList.toggle('on', f);
  b.innerHTML = icon(f ? 'heartFill' : 'heart');
}
async function alternarFav() {
  const id = player.current.id, era = favs.has(id);
  era ? favs.delete(id) : favs.add(id);
  estadoFav();
  try { await api(`/api/usuario/favoritos/${id}`, { method: era ? 'DELETE' : 'POST' }); toast(era ? 'Quitada de Me gusta' : 'Agregada a Me gusta'); }
  catch { era ? favs.add(id) : favs.delete(id); estadoFav(); }
}

async function cargarLetra(s) {
  lineas = []; activa = -2;
  let d = await api(`/api/letras/${s.id}`).catch(() => ({}));
  if (!d.letra && !d.letraSincronizada) {
    const r = await api(`/api/letras/${s.id}/buscar`, { method: 'POST' }).catch(() => null);
    if (r?.encontrada) d = r;
  }
  if (player.current?.id !== s.id) return;
  const box = $('#lyr');
  if (!box) return;
  if (d.letraSincronizada) {
    lineas = parseLrc(d.letraSincronizada);
    box.innerHTML = `<div class="inner">${lineas.map((l, i) => l.interlude
      ? `<div class="dots" data-i="${i}"><i></i><i></i><i></i></div>`
      : `<p class="l" data-i="${i}">${l.words ? l.words.map((w) => `<span class="w">${esc(w.w)}</span>`).join('') : esc(l.text)}</p>`).join('')}</div>`;
    actualizarLetra(true);
  } else if (d.letra) {
    box.innerHTML = `<div class="plain">${d.letra.split('\n').filter((x) => x.trim()).map((x) => `<p>${esc(x)}</p>`).join('')}</div>`;
  } else {
    box.innerHTML = `<div class="none">${icon('mic')}<br>No hay letra para esta canción</div>`;
  }
}

function actualizarLetra(force) {
  if (!lineas.length) return;
  const offsets = store.get('sm_lrc_offset', {});
  const t = player.audio.currentTime + (offsets[player.current?.id] || 0) + 0.12;
  let i = -1;
  for (let k = 0; k < lineas.length; k++) { if (lineas[k].t <= t) i = k; else break; }
  const inner = $('#lyr .inner');
  if (!inner) return;
  if (i !== activa || force) {
    activa = i;
    inner.querySelectorAll('[data-i]').forEach((n) => {
      const k = +n.dataset.i, d = Math.abs(k - i);
      n.classList.toggle('active', k === i);
      if (n.classList.contains('l')) {
        n.style.setProperty('--o', k === i ? 1 : Math.max(0.16, 0.42 - Math.min(d, 6) * 0.04));
        n.style.setProperty('--b', k === i ? '0px' : Math.min(d, 5) * 0.06 + 'rem');
      }
    });
    const a = inner.querySelector(`[data-i="${Math.max(0, i)}"]`);
    if (a) inner.style.transform = `translateY(${-a.offsetTop + inner.parentElement.clientHeight * 0.3 - inner.parentElement.clientHeight * 0.36}px)`;
  }
  const l = lineas[i];
  if (l?.words) {
    const spans = inner.querySelectorAll(`.l[data-i="${i}"] .w`);
    l.words.forEach((w, k) => {
      const fin = l.words[k + 1]?.t ?? Math.min(l.end, w.t + 1.2);
      spans[k]?.style.setProperty('--p', (t <= w.t ? 0 : t >= fin ? 100 : ((t - w.t) / (fin - w.t)) * 100) + '%');
    });
  }
}

// ═══════════════════ Eventos del reproductor ═══════════════════

bus.on('player:song', () => {
  pintarMini();
  if ($('.np.open')) pintarNP(true);
  $$('.track').forEach((t) => {
    const l = listas.get(t.dataset.play);
    t.classList.toggle('playing', l?.songs[+t.dataset.i]?.id === player.current?.id);
  });
});
bus.on('player:state', () => {
  pintarMini();
  if ($('.np.open')) pintarNP(false);
  nativo?.setPlaying?.(player.playing); // mantiene la pantalla encendida mientras suena
});
bus.on('player:mode', () => $('.np.open') && pintarNP(false));
bus.on('player:time', pintarBarra);
bus.on('player:queue', () => $('.np.open') && pintarNP(false));

// ═══════════════════ Inicio de sesión ═══════════════════

function login(msg = '') {
  document.body.innerHTML = `<div class="login"><form id="lf">
    <h1><img src="/img/icon.svg" alt="">SpaceMusic</h1><p>Inicia sesión con tu cuenta</p>
    <label>Usuario o correo</label><input class="f" id="lu" autocomplete="username">
    <label>Contraseña</label><input class="f" id="lp" type="password" autocomplete="current-password">
    <div class="err" id="le">${esc(msg)}</div>
    <button class="btn primary f" type="submit">Entrar</button></form></div>`;
  enfocar($('#lu'), { scroll: false });
  $('#lf').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('#le').textContent = '';
    try {
      const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ usuario: $('#lu').value.trim(), password: $('#lp').value }) }).then((x) => x.json());
      if (r.error) { $('#le').textContent = r.error; return; }
      session.save(r.token, r.usuario);
      iniciar();
    } catch { $('#le').textContent = 'No hay conexión con el servidor'; }
  });
}

// ═══════════════════ Arranque ═══════════════════

async function iniciar() {
  if (!session.valid()) {
    const motivo = new URLSearchParams(location.search).get('motivo');
    login(motivo === 'expirada' ? 'Tu sesión expiró. Vuelve a entrar.' : '');
    return;
  }
  prefs.visualizer = false; // la TV no necesita el visualizador
  shell();
  await cargarFavs();
  player.restore();
  pintarMini();
  navegar('inicio');
  api('/api/auth/refresh', { method: 'POST' }).then((r) => r?.token && session.save(r.token, r.usuario)).catch(() => { });
}

iniciar().catch((e) => { document.body.innerHTML = `<div class="loading">No se pudo iniciar: ${esc(e.message)}</div>`; });
