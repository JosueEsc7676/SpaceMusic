// SpaceMusic TV — interfaz para Android TV y televisores (control remoto)
import './fuentes.js';
window.SM_TV = true;

import { api, session } from './api.js';
import { esc, cover, fmtTime, fmtLong, plural, dominantColor, bus, store, albumHref } from './util.js';
import { icon } from './icons.js';
import { player, prefs, fx } from './player.js';
import { parseLrc } from './lrc.js';
import { pintarCuerpo, ocultarSplash } from './splash.js';
import { qrSvg } from './qr.js';
import { iniciarRemoto, cargarDispositivos, otrosDispositivos, miDispositivo, miNombre, ordenar, posicion, escucharAqui, pasarA } from './remoto.js';
import { motor, objetivo, control, activar as activarSala, crearSala, retomarSala, salaActiva, unirseConCodigoTexto, salirDeSala } from './juntos-motor.js';

const nativo = window.SpaceMusicTV || null; // puente con la APK (si existe)

// Tipografías de la letra en la TV: «Órbita» (Bricolage Grotesque), cursiva (Dancing Script) y elegante (Playfair)
if (navigator.onLine !== false && !document.querySelector('link[data-fuentes-tv]')) {
  const l = document.createElement('link');
  l.rel = 'stylesheet';
  l.href = 'https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,600;12..96,800&family=Dancing+Script:wght@600;700&family=Playfair+Display:ital,wght@1,600;1,700&display=swap';
  l.dataset.fuentesTv = '';
  document.head.appendChild(l);
}

// ═══════════════════ Estilo del reproductor y de la letra (se guarda en esta TV) ═══════════════════
// reproductor: «clasico» (el de siempre) u «orbita» (dinámico, con los colores de la portada)
// fuente: normal | cursiva | elegante · tam: 70–160 (%) · orden: ordenada | desordenada
// mostrar: lineas (varias) | verso (solo el que suena) | flotante (el que suena, en distintas partes de la pantalla)
// color: portada | blanco | lima | rosa | celeste | ambar | lila · sep: junta | normal | separada
// alinear: centro | izquierda · brillo: si | no
const ESTILO_DEF = { reproductor: 'clasico', fuente: 'normal', tam: 100, orden: 'ordenada',
  mostrar: 'lineas', color: 'portada', sep: 'normal', alinear: 'centro', brillo: 'si', traduccion: 'no', karaoke: 'no' };
const COLORES_LETRA = { blanco: '#ffffff', lima: '#d7ff4f', rosa: '#ff6fb5', celeste: '#7cd3ff', ambar: '#ffc24a', lila: '#b9a5ff' };
const SEPARACION = { junta: 0.45, normal: 1, separada: 1.9 };
let estilo = { ...ESTILO_DEF, ...(store.get('sm_tv_estilo', {}) || {}) };
function aplicarEstilo() {
  const np = $('#np');
  if (!np) return;
  np.classList.toggle('orbita', estilo.reproductor === 'orbita');
  np.classList.toggle('desorden', estilo.orden === 'desordenada');
  np.classList.toggle('centro', estilo.alinear === 'centro');
  np.classList.toggle('sin-brillo', estilo.brillo === 'no');
  np.dataset.fuente = estilo.fuente;
  np.dataset.mostrar = estilo.mostrar;
  np.style.setProperty('--letra-escala', String(estilo.tam / 100));
  np.style.setProperty('--letra-sep', String(SEPARACION[estilo.sep] ?? 1));
  if (COLORES_LETRA[estilo.color]) np.style.setProperty('--letra-color', COLORES_LETRA[estilo.color]);
  else np.style.removeProperty('--letra-color');
}
function guardarEstilo() { store.set('sm_tv_estilo', estilo); aplicarEstilo(); }
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
const fallo = (e) => toast(e?.message || 'No se pudo completar');

// ═══════════════════ Navegación con el control (flechas) ═══════════════════

let focused = null;

function capa() {
  if ($('#ajustes.open')) return $('#ajustes');
  if ($('.np.open')) return $('.np');
  return document;
}

function visibles() {
  // getClientRects en lugar de offsetParent: los elementos fijos (el mini reproductor) también cuentan
  return $$('.f', capa()).filter((e) => e.getClientRects().length > 0 && !e.disabled);
}

// ── Desplazamiento suave propio ──
// Se calcula siempre hacia una posición final fija (no desde la posición a mitad de animación),
// así pulsar las flechas rápido no provoca saltos ni deja la pantalla trabada.
const animaciones = new WeakMap();
function desplazar(box, eje, destino) {
  const prop = eje === 'x' ? 'scrollLeft' : 'scrollTop';
  const max = eje === 'x' ? box.scrollWidth - box.clientWidth : box.scrollHeight - box.clientHeight;
  destino = Math.round(Math.max(0, Math.min(max, destino)));
  const clave = animaciones.get(box) || {};
  animaciones.set(box, clave);
  const a = clave[eje] || (clave[eje] = { raf: 0, destino: box[prop] });
  if (Math.abs(destino - box[prop]) < 2) { box[prop] = destino; a.destino = destino; return; }
  a.destino = destino;
  cancelAnimationFrame(a.raf);
  const desde = box[prop], inicio = performance.now(), dur = 220;
  const paso = (now) => {
    const t = Math.min(1, (now - inicio) / dur);
    const e = 1 - Math.pow(1 - t, 3);
    box[prop] = desde + (a.destino - desde) * e;
    if (t < 1) a.raf = requestAnimationFrame(paso);
  };
  a.raf = requestAnimationFrame(paso);
}
// Posición final pedida (si hay una animación en curso, se parte de ella)
const destinoDe = (box, eje) => animaciones.get(box)?.[eje]?.destino ?? (eje === 'x' ? box.scrollLeft : box.scrollTop);

function enfocar(el, { scroll = true } = {}) {
  if (!el) return;
  const panel = $('#ajustes.open');
  if (panel && !panel.contains(el)) return;
  const npAbierto = $('.np.open');
  if (!panel && npAbierto && !npAbierto.contains(el)) return;
  focused?.classList.remove('focused');
  focused = el;
  el.classList.add('focused');
  if (panel) { el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); return; }
  const enRail = !!el.closest('.rail');
  $('.rail')?.classList.toggle('open', enRail);
  if (!scroll || enRail) return;
  const shelf = el.closest('.shelf');
  if (shelf) {
    const sr = shelf.getBoundingClientRect(), r = el.getBoundingClientRect();
    const base = destinoDe(shelf, 'x') - shelf.scrollLeft; // corrige la animación en curso
    const izq = r.left - sr.left + base, der = r.right - sr.left + base, margen = sr.width * 0.08;
    const actual = destinoDe(shelf, 'x');
    if (izq < margen) desplazar(shelf, 'x', actual + izq - margen);
    else if (der > sr.width - margen) desplazar(shelf, 'x', actual + der - sr.width + margen);
  }
  const screen = el.closest('.screen');
  if (screen) {
    const vr = screen.getBoundingClientRect();
    // En listas largas se sigue a la canción; en filas de tarjetas se muestra la fila con su título
    const fila = el.closest('.track') ? el : (el.closest('.row, .head, .actions, .search-box') || el);
    const objetivo = fila.getBoundingClientRect().height > vr.height * 0.6 ? el : fila;
    const r = objetivo.getBoundingClientRect();
    const actual = destinoDe(screen, 'y');
    const top = r.top - vr.top + screen.scrollTop, bottom = r.bottom - vr.top + screen.scrollTop;
    const arriba = vr.height * 0.16, abajo = vr.height * 0.82;
    if (el === $('.f', screen)) desplazar(screen, 'y', 0);
    else if (top - actual < arriba) desplazar(screen, 'y', top - arriba);
    else if (bottom - actual > abajo) desplazar(screen, 'y', bottom - abajo);
  }
}

/** Atajo para listas: arriba/abajo en canciones e izquierda/derecha en tarjetas van al vecino directo. */
function vecino(dir) {
  if (!focused) return null;
  if ((dir === 'up' || dir === 'down') && focused.classList.contains('track')) {
    const n = dir === 'down' ? focused.nextElementSibling : focused.previousElementSibling;
    return n?.classList.contains('track') ? n : null;
  }
  if ((dir === 'left' || dir === 'right') && focused.parentElement?.classList.contains('shelf')) {
    const n = dir === 'right' ? focused.nextElementSibling : focused.previousElementSibling;
    if (n?.classList.contains('f')) return n;
    // Última tarjeta de la fila + → : al mini reproductor (si hay música)
    if (dir === 'right' && !$('.np.open')) return $('#mini:not(.hidden)');
    return null;
  }
  return null;
}

/** Busca el elemento más cercano en la dirección pulsada. */
function mover(dir) {
  const v = vecino(dir);
  if (v) { enfocar(v); return; }
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
    // Tarjetas escondidas a los lados de una fila no cuentan (a esas se llega recorriendo la fila)
    if (b.left >= innerWidth - 2 || b.right <= 2) continue;
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
  // Al final de una fila (→) o de la pantalla (↓) se llega al mini reproductor
  else if ((dir === 'down' || dir === 'right' || dir === 'up') && !$('.np.open') && !focused.closest('.rail') && $('#mini:not(.hidden)')) enfocar($('#mini'));
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
  if ((k === 'ArrowLeft' || k === 'ArrowRight') && focused?.dataset.c === 'seek') {
    e.preventDefault(); saltar(k === 'ArrowRight' ? 1 : -1); return;
  }
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
  if ($('#ajustes.open')) { cerrarAjustes(); return true; }
  if ($('.np.solo')) { soloLetra(false); return true; }
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
  ['dispositivos', 'devices', 'Dispositivos'],
  ['syncplay', 'headphones', 'SyncPlay'],
  ['fiesta', 'sparkles', 'Modo fiesta'],
];

function shell() {
  pintarCuerpo(`
    <div class="tv">
      <nav class="rail">
        <div class="logo"><img src="/img/icon.svg" alt=""><span>SpaceMusic</span></div>
        <button class="nav f hidden" data-go="np" id="nav-np">${icon('wave')}<span>Reproduciendo</span></button>
        ${SECCIONES.map(([k, ic, l]) => `<button class="nav f" data-go="${k}">${icon(ic)}<span>${l}</span></button>`).join('')}
        ${nativo?.openSettings ? `<button class="nav f user" data-go="servidor">${icon('sliders')}<span>Servidor</span></button>` : ''}
        <button class="nav f ${nativo?.openSettings ? '' : 'user'}" data-go="salir">${icon('logout')}<span>Cerrar sesión</span></button>
        ${nativo?.cerrarApp ? `<button class="nav f" data-go="cerrar">${icon('power')}<span>Cerrar app</span></button>` : ''}
      </nav>
      <main class="main"><div class="screen" id="screen"></div></main>
    </div>
    <button class="mini f hidden" id="mini"></button>
    <div class="np" id="np"></div>
    <div class="ajustes" id="ajustes"></div>`);
  aplicarEstilo();
  // Botones del reproductor (los dos estilos usan los mismos data-c)
  $('#np').addEventListener('click', (e) => {
    const c = e.target.closest('[data-c]')?.dataset.c;
    if (c === 'play') player.toggle();
    else if (c === 'prev') player.prev();
    else if (c === 'next') player.next();
    else if (c === 'shuffle') { player.toggleShuffle(); toast(player.shuffle ? 'Aleatorio activado' : 'Aleatorio desactivado'); }
    else if (c === 'repeat') { player.cycleRepeat(); toast(player.repeat === 'off' ? 'Repetición desactivada' : player.repeat === 'one' ? 'Repetir esta canción' : 'Repetir todo'); }
    else if (c === 'fav') alternarFav();
    else if (c === 'estilo') abrirAjustes();
    else if (c === 'solo') soloLetra(true);
  });
  $('#ajustes').addEventListener('click', (e) => {
    const b = e.target.closest('[data-aj]');
    if (b) cambiarAjuste(b.dataset.aj);
  });
  $('.rail').addEventListener('click', (e) => {
    const b = e.target.closest('[data-go]');
    if (!b) return;
    if (b.dataset.go === 'salir') {
      try { fetch('/api/control/presencia/' + encodeURIComponent(miDispositivo()), { method: 'DELETE', keepalive: true, headers: { Authorization: 'Bearer ' + session.token } }); } catch { }
      session.clear(); location.reload(); return;
    }
    if (b.dataset.go === 'np') { abrirNP(); return; }
    if (b.dataset.go === 'servidor') { nativo.openSettings(); return; }
    if (b.dataset.go === 'cerrar') { player.pause(); nativo.cerrarApp(); return; }
    navegar(b.dataset.go);
  });
  $('#mini').addEventListener('click', abrirNP);
}

function navegar(seccion) {
  pila.length = 0;
  vistaActual(null);
  $$('.nav').forEach((n) => n.classList.toggle('active', n.dataset.go === seccion));
  const vistas = { inicio: vistaInicio, buscar: vistaBuscar, megusta: vistaMeGusta, playlists: vistaPlaylists,
    artistas: vistaArtistas, albumes: vistaAlbumes, historial: vistaHistorial, dispositivos: vistaDispositivos, syncplay: vistaSyncPlay, fiesta: vistaFiesta };
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
const tAlbum = (a) => `<button class="card f" data-album="${esc(a.clave || a.nombre)}"><div class="art">${img(a.portadaUrl)}</div>
  <div class="t ellipsis">${esc(a.nombre)}</div><div class="s ellipsis">${esc(a.artista)}</div></button>`;
const tArtista = (a) => `<button class="card round f" data-artista="${esc(a.nombre)}"><div class="art">${img(a.portadaUrl)}</div>
  <div class="t ellipsis">${esc(a.nombre)}</div><div class="s">${plural(a.totalCanciones, 'canción', 'canciones')}</div></button>`;
const tPlaylist = (p) => `<button class="card f" data-playlist="${p.id}"><div class="art">${p.portadaUrl ? img(p.portadaUrl) : mosaic(p.portadas)}</div>
  <div class="t ellipsis">${esc(p.nombre)}</div><div class="s">${plural(p.cancionesCount, 'canción', 'canciones')}</div></button>`;
const tMix = (m) => `<button class="card mix f" data-mix="${esc(m.id)}"><div class="art">${mosaic(m.portadas)}<div class="label">${esc(m.titulo)}</div></div>
  <div class="s ellipsis">${esc((m.artistas || []).join(', '))}</div></button>`;
const COLORES = ['#6c5ce7', '#3a7bd5', '#c2547a', '#d07a4a', '#3c9d9b', '#8e5bd1', '#b8863b', '#4d6fd8', '#a34fa0', '#5c8a6a'];
const tGenero = (g, i) => `<button class="genre f" data-genero="${esc(g.nombre)}" style="--g:${COLORES[i % COLORES.length]}">${esc(g.nombre)}</button>`;

// Clics (Enter) sobre tarjetas
document.addEventListener('click', async (e) => {
  const mp = e.target.closest('[data-mixplay]');
  if (mp) {
    const songs = await api(`/api/mixes/${encodeURIComponent(mp.dataset.mixplay)}?cantidad=40`).catch(() => []);
    if (songs.length) { player.play(songs, 0, { type: 'mix', label: 'Mezcla' }); abrirNP(); }
    return;
  }
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
  const m = (d.mixes || [])[0];
  const destacado = m ? `<section class="spot row">
      ${m.portadas?.[0] ? `<div class="spot-bg" style="background-image:url('${esc(cover(m.portadas[0], 300))}')"></div>` : ''}
      <div class="spot-body"><div class="eyebrow">Hecho para ti</div><h2 class="spot-t">${esc(m.titulo)}</h2>
        <p class="spot-s ellipsis">${esc((m.artistas || []).join(', '))}</p>
        <div class="actions"><button class="btn primary f" data-mixplay="${esc(m.id)}">${icon('play')} Reproducir</button>
          <button class="btn f" data-mix="${esc(m.id)}">Ver mezcla</button></div></div>
      <div class="spot-art">${mosaic(m.portadas)}</div></section>` : '';
  await pantalla(`<div class="eyebrow">${esc(new Date().toLocaleDateString('es', { weekday: 'long', day: 'numeric', month: 'long' }))}</div>
    <h1 class="title">${saludo()}${nombre ? ', ' + esc(nombre) : ''}</h1>
    ${destacado}
    ${fila('Tus mezclas', (d.mixes || []).slice(1).map(tMix).join(''))}
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
  coleccion({ kind: 'Playlist', titulo: 'Me gusta', arte: `<div class="ph art-liked">${icon('heartFill')}</div>`,
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
    songs: a.canciones, ctx: { type: 'album', label: a.nombre, route: albumHref(a.nombre, a.artista) }, colorDe: a.portadaUrl, pista: true });
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

// ═══════════════════ Dispositivos (como Spotify Connect) ═══════════════════

let enPantalla = null; // pantalla especial abierta: 'dispositivos', 'dispositivo:<id>' o 'syncplay'
const vistaActual = (v) => { enPantalla = v; };
const deviceIcon = (d) => (d.plataforma === 'tv' ? 'tv' : d.plataforma === 'ios' || d.plataforma === 'android' ? 'phone' : 'devices');

async function vistaDispositivos() {
  vistaActual('dispositivos');
  const lista = await cargarDispositivos().catch(() => []);
  if (enPantalla !== 'dispositivos') return;
  const otros = otrosDispositivos();
  const tarjeta = (d) => `<button class="dev f ${d.sonando ? 'sonando' : ''}" data-dev="${esc(d.id)}">
      <div class="dev-art">${d.cancion ? img(d.cancion.portadaUrl, 300) : `<div class="ph">${icon(deviceIcon(d))}</div>`}<span class="dev-ico">${icon(deviceIcon(d))}</span></div>
      <div class="t ellipsis">${esc(d.nombre)}</div>
      <div class="s ellipsis">${d.cancion ? `${d.sonando ? 'Sonando' : 'En pausa'} · ${esc(d.cancion.nombre)}` : 'Sin música'}</div></button>`;
  await pantalla(`<h1 class="title">Dispositivos</h1>
    <p class="sub">Esta TV aparece como <b>${esc(miNombre())}</b> en tu teléfono, computadora o tablet: desde ahí puedes ponerle música, pausarla, cambiar de canción o el volumen.</p>
    ${otros.length ? `<section class="row"><h2>Tus otros dispositivos</h2><div class="shelf">${otros.map(tarjeta).join('')}</div></section>`
      : `<p class="empty">Abre SpaceMusic con tu cuenta en el teléfono o la computadora y aparecerá aquí.</p>`}
    <div class="actions"><button class="btn f" data-dev-recargar>${icon('refresh')} Actualizar</button></div>`, { foco: true });
  $('#screen [data-dev-recargar]')?.addEventListener('click', () => vistaDispositivos());
  $$('#screen [data-dev]').forEach((b) => b.addEventListener('click', () => ir(vistaDispositivo, b.dataset.dev)));
  return lista;
}

async function vistaDispositivo(id) {
  vistaActual('dispositivo:' + id);
  await cargarDispositivos().catch(() => []);
  const d = otrosDispositivos().find((x) => x.id === id);
  if (!d) { toast('Ese dispositivo ya no está conectado'); atras(); return; }
  const c = d.cancion;
  await pantalla(`<div class="head"><div class="cover">${c ? img(c.portadaUrl, 640) : `<div class="ph">${icon(deviceIcon(d))}</div>`}</div>
      <div style="min-width:0"><div class="kind">${icon(deviceIcon(d))} ${esc(d.nombre)}</div>
        <h1 class="ellipsis">${c ? esc(c.nombre) : 'Sin música'}</h1>
        <div class="meta">${c ? `${esc(c.artista)} · ${d.sonando ? 'Sonando' : 'En pausa'} · <span data-dev-t>${fmtTime(posicion(d))}</span> / ${fmtTime(c.duracionSegundos)}` : 'Pon música desde aquí o desde ese dispositivo.'}
          · Volumen <span data-dev-vol>${Math.round((d.volumen ?? 1) * 100)}</span>%</div></div></div>
    <div class="actions dev-ctrls">
      ${c ? `<button class="btn f" data-o="prev">${icon('rew')}</button>
      <button class="btn primary f" data-o="${d.sonando ? 'pause' : 'play'}">${icon(d.sonando ? 'pause' : 'play')} ${d.sonando ? 'Pausar' : 'Reproducir'}</button>
      <button class="btn f" data-o="next">${icon('fwd')}</button>
      <button class="btn f" data-o="vol-">${icon('volumeLow')} −</button>
      <button class="btn f" data-o="vol+">${icon('volume')} +</button>` : ''}
    </div>
    <div class="actions">
      ${c ? `<button class="btn f" data-o="aqui">${icon('tv')} Escuchar en esta TV</button>` : ''}
      ${player.current ? `<button class="btn f" data-o="pasar">${icon('devices')} Pasar la música de la TV</button>` : ''}
    </div>`);
  const acciones = $('#screen');
  acciones.querySelectorAll('[data-o]').forEach((b) => b.addEventListener('click', async () => {
    const o = b.dataset.o;
    try {
      if (o === 'aqui') { await escucharAqui(d); abrirNP(); return; }
      if (o === 'pasar') { await pasarA(d); setTimeout(() => vistaDispositivo(id), 900); return; }
      if (o === 'vol-' || o === 'vol+') {
        const v = Math.max(0, Math.min(1, (d.volumen ?? 1) + (o === 'vol+' ? 0.1 : -0.1)));
        d.volumen = v;
        await ordenar(d.id, 'volumen', { volumen: v });
        $('[data-dev-vol]') && ($('[data-dev-vol]').textContent = Math.round(v * 100));
        return;
      }
      await ordenar(d.id, o);
      setTimeout(() => { if (enPantalla === 'dispositivo:' + id) { const k = focused?.dataset.o; vistaDispositivo(id).then(() => k && enfocar($(`[data-o="${k}"]`) || $(`[data-o="${k === 'play' ? 'pause' : 'play'}"]`))); } }, 700);
    } catch (e) { fallo(e); }
  }));
}

// La lista cambia (alguien empezó a sonar, se conectó otro dispositivo…): se actualiza la pantalla abierta
let pintandoDisp = 0;
bus.on('remoto:lista', () => {
  if (enPantalla !== 'dispositivos' || Date.now() - pintandoDisp < 1500 || $('.np.open')) return;
  pintandoDisp = Date.now();
  const k = focused?.dataset.dev;
  vistaDispositivos().then(() => k && enfocar($(`[data-dev="${k}"]`)));
});
setInterval(() => {
  if (!enPantalla?.startsWith('dispositivo:')) return;
  const d = otrosDispositivos().find((x) => 'dispositivo:' + x.id === enPantalla);
  const t = $('[data-dev-t]');
  if (d && t) t.textContent = fmtTime(posicion(d));
}, 1000);

// ═══════════════════ SyncPlay en la TV ═══════════════════

async function vistaSyncPlay() {
  vistaActual('syncplay');
  const activa = salaActiva();
  if (activa) return pintarSala(motor.estado || await api('/api/juntos/' + activa.id).catch(() => null));
  const salas = await api('/api/juntos').catch(() => []);
  if (enPantalla !== 'syncplay') return;
  const invit = salas.filter((s) => !s.dentro);
  await pantalla(`<h1 class="title">SyncPlay</h1>
    <p class="sub">Escuchen la misma canción en el mismo segundo en la TV, los teléfonos y las computadoras. Todos pueden reproducir, pausar y cambiar de canción.</p>
    <div class="actions"><button class="btn primary f" data-sp="crear">${icon('headphones')} Iniciar SyncPlay en la TV</button>
      <button class="btn f" data-sp="codigo">${icon('users')} Unirme con código</button></div>
    <form class="search-box f hidden" id="spf">${icon('users')}<input id="spc" maxlength="7" placeholder="Código de 6 letras y números" autocomplete="off"></form>
    ${invit.length || salas.some((s) => s.dentro) ? `<section class="row"><h2>Invitaciones y SyncPlay de tus amigos</h2><div class="shelf">${salas.map((s) => `<button class="card f" data-sala="${s.id}">
      <div class="art">${s.cancion ? img(s.cancion.portadaUrl) : `<div class="ph">${icon('users')}</div>`}</div>
      <div class="t ellipsis">${s.soyAnfitrion ? 'Tu SyncPlay' : 'SyncPlay de ' + esc(s.anfitrion?.nombre || '')}</div>
      <div class="s ellipsis">${s.dentro ? `${s.personas.length} escuchando` : 'Te invitaron'}${s.cancion ? ' · ' + esc(s.cancion.nombre) : ''}</div></button>`).join('')}</div></section>` : ''}`);
  const s = $('#screen');
  s.querySelector('[data-sp="crear"]').addEventListener('click', async () => {
    try {
      const sonaba = player.current, cola = player.items.slice(player.index + 1, player.index + 21).map((x) => x.id);
      const d = await crearSala();
      toast('SyncPlay iniciado');
      // Lo que sonaba en la TV pasa a sonar para todos
      if (sonaba && !d.cancion) await control('poner', { cancionId: sonaba.id, cola });
      pintarSala(motor.estado || d);
    } catch (e) { fallo(e); }
  });
  s.querySelector('[data-sp="codigo"]').addEventListener('click', () => {
    const f = $('#spf');
    f.classList.remove('hidden');
    enfocar(f);
    $('#spc').focus();
  });
  $('#spf').addEventListener('click', () => $('#spc').focus());
  $('#spf').addEventListener('submit', async (e) => {
    e.preventDefault();
    const codigo = $('#spc').value.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
    if (codigo.length !== 6) { toast('El código tiene 6 letras y números'); return; }
    try { const d = await unirseConCodigoTexto(codigo); toast('Te uniste al SyncPlay de ' + (d.anfitrion?.nombre || 'tu amigo')); pintarSala(d); } catch (err) { fallo(err); }
  });
  s.querySelectorAll('[data-sala]').forEach((b) => b.addEventListener('click', async () => {
    try {
      let d = await api('/api/juntos/' + b.dataset.sala);
      if (!d.dentro) d = await api(`/api/juntos/${d.id}/unirse`, { method: 'POST' });
      activarSala(d);
      pintarSala(d);
    } catch (e) { fallo(e); }
  }));
}

async function pintarSala(d) {
  if (!d) { vistaActual(null); return vistaSyncPlay(); }
  vistaActual('syncplay');
  const c = d.cancion;
  const enlace = `${location.origin}/#/juntos/${d.id}`;
  const antes = focused?.dataset.sp;
  await pantalla(`<div class="sala">
    <div class="sala-izq">
      <div class="eyebrow">${icon('headphones')} SyncPlay en vivo</div>
      <h1 class="title">${d.soyAnfitrion ? 'Tu SyncPlay' : 'SyncPlay de ' + esc(d.anfitrion?.nombre || '')}</h1>
      ${c ? `<div class="sala-ahora">${img(c.portadaUrl, 300)}<span style="min-width:0"><div class="t ellipsis">${esc(c.nombre)}</div>
        <div class="s ellipsis">${esc(c.artista)} · ${d.sonando ? 'Sonando' : 'En pausa'} · <span data-sp-t>${fmtTime(objetivo(d))}</span></div></span></div>`
        : '<p class="sub">Elige una canción en Buscar o en tu biblioteca: sonará para todos.</p>'}
      <div class="actions">
        ${c ? `<button class="btn f" data-sp="prev">${icon('rew')}</button>
        <button class="btn primary f" data-sp="toggle">${icon(d.sonando ? 'pause' : 'play')} ${d.sonando ? 'Pausar' : 'Reproducir'}</button>
        <button class="btn f" data-sp="next">${icon('fwd')}</button>` : ''}
        <button class="btn f" data-sp="elegir">${icon('search')} Elegir canción</button>
        <button class="btn f" data-sp="salir">${icon('logout')} ${d.soyAnfitrion ? 'Terminar' : 'Salir'}</button>
      </div>
      <h2 class="sala-h">Escuchando (${d.personas.length})</h2>
      <div class="sala-personas">${d.personas.map((p) => `<span class="chip">${esc(p.nombre)}${p.username === d.anfitrion?.username ? ' · lo inició' : ''}</span>`).join('')}</div>
      ${d.cola?.length ? `<h2 class="sala-h">A continuación</h2><div class="sala-cola">${d.cola.slice(0, 4).map((x) => `<span class="ellipsis">${esc(x.cancion.nombre)} · ${esc(x.cancion.artista)}</span>`).join('')}</div>` : ''}
    </div>
    ${d.codigo ? `<div class="sala-der"><div class="qr">${qrSvg(enlace)}</div>
      <div class="sala-codigo"><small>Únete con el código</small><b>${esc(d.codigo.slice(0, 3))} ${esc(d.codigo.slice(3))}</b></div>
      <p class="sub">Escanéalo con el teléfono o escribe el código en SyncPlay → «Unirme con código».</p></div>` : ''}
  </div>`);
  if (antes) enfocar($(`[data-sp="${antes}"]`) || $('.screen .f'), { scroll: false });
  $$('#screen [data-sp]').forEach((b) => b.addEventListener('click', async () => {
    const a = b.dataset.sp;
    if (a === 'elegir') { navegar('buscar'); toast('Lo que elijas sonará para todos en el SyncPlay'); return; }
    if (a === 'salir') { await salirDeSala(d.id); toast(d.soyAnfitrion ? 'SyncPlay terminado' : 'Saliste del SyncPlay'); vistaSyncPlay(); return; }
    if (a === 'toggle') control(motor.estado?.sonando ? 'pause' : 'play');
    else if (a === 'next') control('next', { desde: motor.estado?.cancion?.id ?? null });
    else if (a === 'prev') control('prev');
  }));
}

bus.on('juntos:vista', (d) => { if (enPantalla === 'syncplay' && d.id === motor.sala && !$('.np.open')) pintarSala(d); });
bus.on('juntos:vista-fin', (d) => { if (enPantalla === 'syncplay') { toast(d.motivo || 'El SyncPlay terminó'); vistaSyncPlay(); } });
setInterval(() => { const t = $('[data-sp-t]'); if (t && enPantalla === 'syncplay' && motor.estado) t.textContent = fmtTime(objetivo()); }, 1000);

// ═══════════════════ Mini reproductor ═══════════════════

function pintarMini() {
  const s = player.current;
  const m = $('#mini');
  if (!m) return;
  m.classList.toggle('hidden', !s);
  $('#nav-np')?.classList.toggle('hidden', !s);
  if (!s) return;
  m.classList.toggle('paused', !player.playing);
  m.innerHTML = `${img(s.portadaUrl, 160)}<span style="min-width:0;text-align:left"><div class="t ellipsis">${esc(s.nombre)}</div><div class="s ellipsis">${esc(s.artista)}</div></span>
    <span class="eq"><i></i><i></i><i></i></span><span class="bar"><i></i></span><span class="mini-ok">OK · abrir</span>`;
}
// Adelantar/retroceder con la barra: cada pulsación seguida salta un poco más (10 s, 10 s, 20 s, 30 s…)
let saltoT = 0, saltoN = 0;
function saltar(sentido) {
  const ahora = performance.now();
  saltoN = ahora - saltoT < 450 ? saltoN + 1 : 0;
  saltoT = ahora;
  const paso = saltoN < 2 ? 10 : saltoN < 6 ? 20 : 30;
  player.seekBy(sentido * paso);
  pintarBarra();
  const tip = $('#np .np-prog .tip');
  if (tip) { tip.textContent = (sentido > 0 ? '+' : '−') + paso + ' s'; tip.classList.remove('show'); void tip.offsetWidth; tip.classList.add('show'); }
}

function pintarBarra() {
  const a = player.audio;
  const p = a.duration ? (a.currentTime / a.duration) * 100 : 0;
  const b = $('#mini .bar i');
  if (b) b.style.width = p + '%';
  if ($('.np.open')) {
    const dur0 = player.duration();
    const p2 = dur0 ? Math.min(100, (a.currentTime / dur0) * 100) : 0;
    $('#np .rail2 i').style.width = p2 + '%';
    const knob = $('#np .np-prog .knob');
    if (knob) knob.style.left = p2 + '%';
    $('#np [data-cur]').textContent = fmtTime(a.currentTime);
    const dur = player.duration();
    $('#np [data-dur]').textContent = '-' + fmtTime(Math.max(0, dur - a.currentTime));
  }
}

// ═══════════════════ Reproduciendo ahora ═══════════════════

let lineas = [], activa = -2, raf = 0, idleT;

function despertar() {
  const np = $('#np');
  if (!np) return;
  np.classList.remove('idle');
  clearTimeout(idleT);
  if (np.classList.contains('open')) idleT = setTimeout(() => { if (!$('#ajustes.open')) np.classList.add('idle'); }, 8000);
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
    const antes = focused && np.contains(focused) ? focused.dataset.c : null;
    np.innerHTML = estilo.reproductor === 'orbita' ? htmlOrbita(s) : `<div class="np-bg" style="background-image:url('${esc(cover(s.portadaUrl, 300))}')"></div><div class="np-tint"></div>
      <div class="np-left">
        <div class="np-art">${img(s.portadaUrl, 640)}</div>
        <div class="np-head">
          <div class="tt"><div class="np-name">${esc(s.nombre)}</div><div class="np-artist">${esc(s.artista)}${s.album && s.album !== 'Single' ? ' — ' + esc(s.album) : ''}</div></div>
        </div>
        <button class="np-prog f" data-c="seek" aria-label="Adelantar o retroceder"><div class="rail2"><i></i><b class="knob"></b></div>
          <div class="times"><span data-cur>0:00</span><span class="seek-hint">◀ ▶ para adelantar</span><span data-dur>0:00</span></div><span class="tip"></span></button>
        <div class="np-ctrls">
          <button class="cb f" data-c="prev" aria-label="Anterior">${icon('rew')}</button>
          <button class="cb big f" data-c="play" aria-label="Reproducir o pausar">${icon(player.playing ? 'pause' : 'play')}</button>
          <button class="cb f" data-c="next" aria-label="Siguiente">${icon('fwd')}</button>
        </div>
        <div class="np-bottom">
          <button class="cb sm f" data-c="shuffle" aria-label="Aleatorio">${icon('shuffle')}</button>
          <button class="cb sm f" data-c="repeat" aria-label="Repetir">${icon('repeat')}</button>
          <button class="cb sm f" data-c="fav" aria-label="Me gusta">${icon('heart')}</button>
          <button class="cb sm f" data-c="estilo" aria-label="Estilo del reproductor y de la letra">${icon('sliders')}</button>
          <button class="cb sm f" data-c="solo" aria-label="Solo letra en pantalla completa">${icon('expand')}</button>
          <div class="np-up" id="up"></div>
        </div>
      </div>
      <div class="np-lyrics" id="lyr"><div class="none">Cargando letra…</div></div>`;
    aplicarEstilo();
    dominantColor(s.portadaUrl).then((c) => { np.style.setProperty('--np', c.join(',')); colores(np, c); });
    cargarLetra(s);
    estadoFav();
    if (np.classList.contains('open')) enfocar($(`[data-c="${antes || 'play'}"]`, np), { scroll: false });
  }
  np.classList.toggle('paused', !player.playing);
  const pb = np.querySelector('[data-c="play"]');
  if (pb) pb.innerHTML = icon(player.playing ? 'pause' : 'play');
  np.querySelector('[data-c="shuffle"]')?.classList.toggle('on', player.shuffle);
  const rp = np.querySelector('[data-c="repeat"]');
  if (rp) { rp.classList.toggle('on', player.repeat !== 'off'); rp.innerHTML = icon('repeat') + (player.repeat === 'one' ? '<span class="rep1">1</span>' : ''); }
  const cont = np.querySelector('[data-ob-cont]');
  if (cont) cont.textContent = `${dos(player.index + 1)} / ${dos(player.items.length)}`;
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
      : `<p class="l" data-i="${i}" style="--dx:${desorden(i).x}%;--rot:${desorden(i).r}deg">${l.words ? l.words.map((w) => `<span class="w">${esc(w.w)}</span>`).join('') : esc(l.text)}</p>`).join('')}</div>
      <div class="verso" data-verso></div>`;
    sintonia('sincronizada', lineas.filter((l) => !l.interlude).length);
    actualizarLetra(true);
    traducirLetra(s);
  } else if (d.letra) {
    box.innerHTML = `<div class="plain">${d.letra.split('\n').filter((x) => x.trim()).map((x) => `<p>${esc(x)}</p>`).join('')}</div>`;
    sintonia('texto');
  } else {
    box.innerHTML = `<div class="none">${icon('mic')}<br>No hay letra para esta canción</div>`;
    sintonia('sin');
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
    // Línea actual: al centro (clásico y solo letra) o en el tercio de arriba (Órbita, como en la referencia)
    const np = $('#np');
    const alto = np?.classList.contains('solo') ? 0.5 : np?.classList.contains('orbita') ? 0.42 : 0.5;
    if (a) inner.style.transform = `translateY(${inner.parentElement.clientHeight * alto - (a.offsetTop + a.offsetHeight / 2)}px)`;
    pintarVerso(i);
    const nl = $('[data-ob-linea]');
    if (nl) {
      const total = lineas.filter((x) => !x.interlude).length;
      const n = lineas.slice(0, Math.max(0, i) + 1).filter((x) => !x.interlude).length;
      nl.textContent = `${dos(Math.max(1, n))} / ${dos(total)}`;
    }
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

// ═══════════════════ Órbita: reproductor dinámico con los colores de la portada ═══════════════════

const dos = (n) => String(Math.max(0, n || 0)).padStart(2, '0');

/** Desplazamiento fijo de cada línea en el modo «desordenada» (siempre el mismo para la misma línea). */
function desorden(i) {
  const azar = (n) => { const v = Math.sin(n * 12.9898 + 78.233) * 43758.5453; return v - Math.floor(v); };
  // Alterna izquierda/derecha para que nunca queden en diagonal, con algo de azar en cada una
  const lado = i % 2 === 0 ? azar(i) * 0.45 : 0.5 + azar(i) * 0.5;
  const x = lado * 36;                              // 0–36 % hacia la derecha
  const r = (azar(i + 101) - 0.5) * 10;             // −5° a 5°
  return { x: x.toFixed(1), r: r.toFixed(1) };
}

/** RGB → HSL (0–360, 0–1, 0–1). */
function hsl([r, g, b]) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min, s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  const h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, s, l];
}

/** Colores de la portada (de más a menos presentes), agrupados por tono. */
const paletas = new Map();
function paleta(url) {
  if (!url) return Promise.resolve([]);
  if (paletas.has(url)) return Promise.resolve(paletas.get(url));
  return new Promise((listo) => {
    const im = new Image();
    im.onload = () => {
      try {
        const c = document.createElement('canvas');
        c.width = c.height = 32;
        const x = c.getContext('2d', { willReadFrequently: true });
        x.drawImage(im, 0, 0, 32, 32);
        const d = x.getImageData(0, 0, 32, 32).data;
        const grupos = new Map();
        for (let i = 0; i < d.length; i += 4) {
          const rgb = [d[i], d[i + 1], d[i + 2]], [h, s, l] = hsl(rgb);
          if (l < 0.07 || l > 0.96) continue;
          const k = s < 0.16 ? 'gris' : String(Math.round(h / 24) % 15);
          const g = grupos.get(k) || { n: 0, r: 0, g: 0, b: 0 };
          g.n++; g.r += rgb[0]; g.g += rgb[1]; g.b += rgb[2];
          grupos.set(k, g);
        }
        const l = [...grupos.values()].sort((a, b) => b.n - a.n).map((g) => ({ rgb: [g.r / g.n, g.g / g.n, g.b / g.n], n: g.n }));
        paletas.set(url, l);
        listo(l);
      } catch { listo([]); }
    };
    im.onerror = () => listo([]);
    im.src = cover(url, 64);
  });
}

/**
 * Órbita toma los colores de la portada: el fondo es el color principal (oscurecido para que la letra se lea),
 * los resplandores son el primero y el segundo color, y el acento es el más vivo, aclarado.
 */
async function colores(np, rgb) {
  const s0 = player.current;
  const lista = await paleta(s0?.portadaUrl);
  if (player.current !== s0) return;
  const colorPrincipal = lista.find((c) => hsl(c.rgb)[1] >= 0.16) || lista[0] || { rgb };
  const segundo = lista.find((c) => c !== colorPrincipal && hsl(c.rgb)[1] >= 0.16 && Math.abs(hsl(c.rgb)[0] - hsl(colorPrincipal.rgb)[0]) > 25) || colorPrincipal;
  const vivo = [...lista].sort((a, b) => hsl(b.rgb)[1] * Math.sqrt(b.n) - hsl(a.rgb)[1] * Math.sqrt(a.n))[0] || colorPrincipal;
  const [h1, s1] = hsl(colorPrincipal.rgb), [h2, s2] = hsl(segundo.rgb);
  let [hv, sv] = hsl(vivo.rgb);
  const gris = s1 < 0.16;
  if (sv < 0.16) { hv = 75; sv = 0.9; } // portada en blanco y negro: acento lima
  const p = (h, s, l) => `hsl(${h.toFixed(0)} ${Math.round(s * 100)}% ${Math.round(l * 100)}%)`;
  np.style.setProperty('--ob-base', gris ? '#0c0c10' : p(h1, Math.min(0.55, s1), 0.11));
  np.style.setProperty('--ob-base2', gris ? '#08080b' : p(h1, Math.min(0.45, s1), 0.06));
  np.style.setProperty('--ob-glow1', gris ? '#24242c' : p(h1, Math.max(0.5, Math.min(0.85, s1)), 0.36));
  np.style.setProperty('--ob-glow2', gris ? '#1a1a22' : p(h2, Math.max(0.45, Math.min(0.85, s2)), 0.32));
  np.style.setProperty('--ob-acento', p(hv, Math.max(0.75, sv), 0.68));
}

function htmlOrbita(s) {
  // Etiqueta: el género (si no es uno genérico como «Music»), si no el álbum
  const generico = /^(music|música|musica|other|otros?|desconocido|unknown)$/i.test((s.genero || '').trim());
  const etiqueta = (!generico && s.genero) || (s.album && s.album !== 'Single' ? s.album : 'Ahora suena');
  const meta = [s.artista, s.album && s.album !== 'Single' ? s.album : null].filter(Boolean).map(esc).join(' · ');
  return `<div class="ob-fondo"></div>
    <header class="ob-top"><div class="ob-logo"><img src="/img/icon.svg" alt="">SpaceMusic<i>.</i></div>
      <div class="ob-estado"><span class="ob-punto"></span><span data-ob-modo>MODO LETRA ACTIVADO</span><span class="ob-cont" data-ob-cont>01 / 01</span></div></header>
    <section class="ob-izq">
      <div class="ob-eyebrow"><i></i>EN TU FRECUENCIA</div>
      <div class="ob-arte"><div class="ob-sombra"></div><div class="ob-carta">${img(s.portadaUrl, 640)}<span class="ob-sticker">${esc(etiqueta)} ✳</span></div></div>
      <h1 class="ob-nombre">${esc(s.nombre)}</h1>
      <div class="ob-meta"><span class="ellipsis">${meta}</span><span>N.º ${dos(player.index + 1)}</span></div>
    </section>
    <section class="ob-der">
      <div class="ob-cab"><b>LA LETRA, A SU MANERA</b><span class="ob-sinto" data-ob-sinto><i></i>EN SINTONÍA</span></div>
      <div class="np-lyrics" id="lyr"><div class="none">Cargando letra…</div></div>
      <div class="ob-pie"><span data-ob-fuente>↗ LETRA SINCRONIZADA</span><span class="ob-linea" data-ob-linea>00 / 00</span></div>
    </section>
    <footer class="ob-barra">
      <div class="ob-mini">${img(s.portadaUrl, 160)}<span style="min-width:0"><b class="ellipsis">${esc(s.nombre)}</b><small class="ellipsis">${esc(s.artista)}</small></span></div>
      <div class="ob-centro">
        <div class="ob-ctrls">
          <button class="cb f" data-c="prev" aria-label="Anterior">${icon('prev')}</button>
          <button class="cb big f" data-c="play" aria-label="Reproducir o pausar">${icon(player.playing ? 'pause' : 'play')}</button>
          <button class="cb f" data-c="next" aria-label="Siguiente">${icon('next')}</button>
        </div>
        <button class="np-prog f" data-c="seek" aria-label="Adelantar o retroceder"><div class="rail2"><i></i><b class="knob"></b></div>
          <div class="times"><span data-cur>0:00</span><span class="seek-hint">◀ ▶ para adelantar</span><span data-dur>0:00</span></div><span class="tip"></span></button>
      </div>
      <div class="ob-extra">
        <button class="cb sm f" data-c="shuffle" aria-label="Aleatorio">${icon('shuffle')}</button>
        <button class="cb sm f" data-c="repeat" aria-label="Repetir">${icon('repeat')}</button>
        <button class="cb sm f" data-c="fav" aria-label="Me gusta">${icon('heart')}</button>
        <button class="cb sm f" data-c="estilo" aria-label="Estilo">${icon('sliders')}</button>
        <button class="cb sm f" data-c="solo" aria-label="Solo letra">${icon('expand')}</button>
      </div>
    </footer>`;
}

/** Estado de la letra en Órbita: «en sintonía» (sincronizada), solo texto o sin letra. */
function sintonia(tipo, lineasN = 0) {
  const st = $('[data-ob-sinto]'), f = $('[data-ob-fuente]'), m = $('[data-ob-modo]');
  if (st) { st.classList.toggle('off', tipo !== 'sincronizada'); st.lastChild.textContent = tipo === 'sincronizada' ? 'EN SINTONÍA' : tipo === 'texto' ? 'SOLO TEXTO' : 'SIN LETRA'; }
  if (f) f.textContent = tipo === 'sincronizada' ? `↗ LETRA SINCRONIZADA · ${lineasN} LÍNEAS` : tipo === 'texto' ? '↗ LETRA SIN TIEMPOS' : '↗ ESTA CANCIÓN NO TIENE LETRA TODAVÍA';
  if (m) m.textContent = tipo === 'sin' ? 'MODO MÚSICA' : 'MODO LETRA ACTIVADO';
}

// ═══════════════════ Verso suelto: solo el que suena, quieto o en distintas partes de la pantalla ═══════════════════

// Lugares del verso flotante (siempre derecho y legible): [izquierda %, arriba %, alineación]
const LUGARES = [[6, 30, 'left'], [50, 46, 'center'], [94, 34, 'right'], [8, 62, 'left'], [50, 66, 'center'], [92, 58, 'right'], [26, 40, 'left'], [74, 52, 'right']];
let ultimoLugar = -1;
function pintarVerso(i) {
  const v = $('#lyr [data-verso]');
  if (!v || estilo.mostrar === 'lineas') return;
  const l = lineas[i];
  const texto = !l ? (lineas[0]?.text ? '♪' : '') : l.interlude ? '♪ ♪ ♪' : l.text;
  v.classList.remove('entra');
  void v.offsetWidth; // reinicia la animación de entrada
  const tr = l && !l.interlude ? traduccionDe(l.text) : '';
  v.innerHTML = esc(texto) + (tr ? `<small class="tr">${esc(tr)}</small>` : '');
  if (estilo.mostrar === 'flotante') {
    // Un lugar distinto al anterior en cada verso (el mismo verso cae siempre en el mismo sitio)
    let k = Math.abs(Math.floor(Math.sin((i + 3) * 91.7) * 1000)) % LUGARES.length;
    if (k === ultimoLugar) k = (k + 3) % LUGARES.length;
    ultimoLugar = k;
    const [x, y, a] = LUGARES[k];
    v.style.left = x + '%';
    v.style.top = y + '%';
    v.style.textAlign = a;
    v.style.setProperty('--tx', a === 'center' ? '-50%' : a === 'right' ? '-100%' : '0');
  } else {
    v.style.left = ''; v.style.top = ''; v.style.textAlign = ''; v.style.removeProperty('--tx');
  }
  v.classList.add('entra');
}

// ═══════════════════ Solo letra (pantalla completa, sin controles) ═══════════════════

function soloLetra(on) {
  const np = $('#np');
  if (!np) return;
  cerrarAjustes(false);
  np.classList.toggle('solo', on);
  if (on) {
    enfocar($('[data-c="play"]', np), { scroll: false }); // OK sigue pausando / reanudando
    toast('Solo letra · OK pausa · Atrás para salir');
  } else {
    enfocar($('[data-c="solo"]', np) || $('[data-c="play"]', np), { scroll: false });
    despertar();
  }
  activa = -2;
  setTimeout(() => actualizarLetra(true), 450);
}

// ═══════════════════ Panel de estilo ═══════════════════

function abrirAjustes() {
  const p = $('#ajustes');
  const op = (clave, valor, texto) => `<button class="aj-op f ${estilo[clave] === valor ? 'sel' : ''}" data-aj="${clave}:${valor}">${texto}</button>`;
  const muestra = (c, txt) => op('color', c, `<i class="aj-color" style="background:${c === 'portada' ? 'conic-gradient(#ff6fb5,#ffc24a,#d7ff4f,#7cd3ff,#b9a5ff,#ff6fb5)' : COLORES_LETRA[c]}"></i>${txt}`);
  p.innerHTML = `<div class="aj-caja">
    <h2>Estilo</h2>
    <div class="aj-fila"><span>Reproductor</span>${op('reproductor', 'clasico', 'Clásico')}${op('reproductor', 'orbita', 'Órbita · dinámico')}</div>
    <div class="aj-fila"><span>Mostrar</span>${op('mostrar', 'lineas', 'Varias líneas')}${op('mostrar', 'verso', 'Solo el verso que suena')}${op('mostrar', 'flotante', 'Verso flotante')}</div>
    <div class="aj-fila"><span>Letra</span>${op('fuente', 'normal', 'Normal')}${op('fuente', 'cursiva', '<i class="aj-cursiva">Cursiva</i>')}${op('fuente', 'elegante', '<i class="aj-elegante">Elegante</i>')}</div>
    <div class="aj-fila aj-colores"><span>Color</span>${muestra('portada', 'De la portada')}${muestra('blanco', 'Blanco')}${muestra('lima', 'Lima')}${muestra('rosa', 'Rosa')}${muestra('celeste', 'Celeste')}${muestra('ambar', 'Ámbar')}${muestra('lila', 'Lila')}</div>
    <div class="aj-fila"><span>Tamaño</span><button class="aj-op f" data-aj="tam:-">A−</button><b class="aj-tam">${estilo.tam}%</b><button class="aj-op f" data-aj="tam:+">A+</button></div>
    <div class="aj-fila"><span>Separación</span>${op('sep', 'junta', 'Más junta')}${op('sep', 'normal', 'Normal')}${op('sep', 'separada', 'Más separada')}</div>
    <div class="aj-fila"><span>Orden</span>${op('orden', 'ordenada', 'Ordenada')}${op('orden', 'desordenada', 'Desordenada')}</div>
    <div class="aj-fila"><span>Alineación</span>${op('alinear', 'centro', 'Al centro')}${op('alinear', 'izquierda', 'A la izquierda')}</div>
    <div class="aj-fila"><span>Resplandor</span>${op('brillo', 'si', 'Con brillo')}${op('brillo', 'no', 'Sin brillo')}</div>
    <div class="aj-fila"><span>Traducción</span>${op('traduccion', 'no', 'Sin traducir')}${op('traduccion', 'es', 'Al español')}${op('traduccion', 'en', 'Al inglés')}</div>
    <div class="aj-fila"><span>Karaoke</span>${op('karaoke', 'no', 'Con voz')}${op('karaoke', 'si', 'Baja la voz')}</div>
    <div class="aj-fila aj-fin"><button class="aj-op f grande" data-aj="solo">${icon('expand')} Solo letra en pantalla completa</button><button class="aj-op f" data-aj="cerrar">Listo</button></div>
    <p class="aj-nota">La letra sigue sincronizada en todos los estilos. Se guarda en esta TV.</p></div>`;
  p.classList.add('open');
  enfocar($('.aj-op.sel', p) || $('.aj-op', p), { scroll: false });
}

function cerrarAjustes(volver = true) {
  const p = $('#ajustes');
  if (!p?.classList.contains('open')) return;
  p.classList.remove('open');
  if (volver) enfocar($('#np [data-c="estilo"]') || $('#np [data-c="play"]'), { scroll: false });
}

function cambiarAjuste(v) {
  if (v === 'cerrar') { cerrarAjustes(); return; }
  if (v === 'solo') { soloLetra(true); return; }
  const [clave, valor] = v.split(':');
  const antes = focused?.dataset.aj;
  if (clave === 'tam') estilo.tam = Math.max(70, Math.min(160, estilo.tam + (valor === '+' ? 10 : -10)));
  else estilo[clave] = valor;
  const otroReproductor = clave === 'reproductor';
  guardarEstilo();
  if (clave === 'traduccion' && player.current) traducirLetra(player.current);
  if (clave === 'karaoke') aplicarKaraoke(true);
  if (otroReproductor) pintarNP(true);
  activa = -2;
  ultimoLugar = -1;
  setTimeout(() => actualizarLetra(true), 60);
  abrirAjustes();
  if (antes) enfocar($(`[data-aj="${antes}"]`, $('#ajustes')), { scroll: false });
}

// ═══════════════════ Letra traducida (debajo de cada línea) ═══════════════════

let traduccion = new Map(); // texto original → traducido
function traduccionDe(texto) { return traduccion.get(String(texto || '').trim()) || ''; }
async function traducirLetra(s) {
  traduccion = new Map();
  $$('#lyr .l .tr').forEach((n) => n.remove());
  if (estilo.traduccion === 'no' || !s) { pintarVerso(activa); return; }
  let d;
  try { d = await api(`/api/letras/${s.id}/traduccion?a=${estilo.traduccion}`); } catch (e) { toast(e.message || 'No se pudo traducir la letra'); return; }
  if (player.current?.id !== s.id) return;
  if (d.mismoIdioma) { toast('La letra ya está en ese idioma'); return; }
  (d.originales || []).forEach((o, i) => { const t = d.lineas?.[i]; if (o && t && t !== o) traduccion.set(String(o).trim(), t); });
  $$('#lyr .l').forEach((n) => {
    const l = lineas[+n.dataset.i];
    const t = l && traduccionDe(l.text);
    if (t) n.insertAdjacentHTML('beforeend', `<span class="tr">${esc(t)}</span>`);
  });
  activa = -2;
  actualizarLetra(true);
}

// ═══════════════════ Karaoke (baja la voz) ═══════════════════

function aplicarKaraoke(avisar = false) {
  const on = estilo.karaoke === 'si';
  if (!on && !fx.ctx) return;
  const ok = fx.karaoke(on);
  if (avisar) toast(!ok ? 'El karaoke no está disponible en esta TV' : on ? 'Karaoke: la voz baja (funciona mejor con canciones en estéreo)' : 'Karaoke desactivado');
}

// ═══════════════════ Modo fiesta: tus invitados proponen y votan desde su teléfono ═══════════════════

const fiesta = { activa: false, estado: null, pendiente: null, sondeo: 0 };

async function vistaFiesta() {
  vistaActual('fiesta');
  if (!fiesta.activa) {
    await api('/api/fiesta').then((e) => { fiesta.activa = true; fiesta.estado = e; }).catch(() => { });
  }
  if (!fiesta.activa) {
    await pantalla(`<h1 class="title">Modo fiesta</h1>
      <p class="sub">Tus invitados escanean un código con su teléfono (sin cuenta ni app), buscan canciones del servidor, las proponen y votan.
        La más votada suena después de la actual.</p>
      <div class="actions"><button class="btn primary f" data-fi="abrir">${icon('sparkles')} Empezar la fiesta</button></div>`);
    $('#screen [data-fi="abrir"]').addEventListener('click', async () => {
      try { fiesta.estado = await api('/api/fiesta', { method: 'POST' }); fiesta.activa = true; sondearFiesta(); pintarFiesta(); } catch (e) { fallo(e); }
    });
    return;
  }
  sondearFiesta();
  pintarFiesta();
}

async function pintarFiesta() {
  if (enPantalla !== 'fiesta' || !fiesta.estado || $('.np.open')) return;
  const e = fiesta.estado;
  const enlace = `${location.origin}/fiesta.html#${e.token}`;
  const antes = focused?.dataset.fi;
  const props = e.propuestas || [];
  await pantalla(`<div class="sala">
    <div class="sala-izq">
      <div class="eyebrow">${icon('sparkles')} Modo fiesta</div>
      <h1 class="title">La fiesta de ${esc(e.anfitrion || '')}</h1>
      ${player.current ? `<div class="sala-ahora">${img(player.current.portadaUrl, 300)}<span style="min-width:0"><div class="t ellipsis">${esc(player.current.nombre)}</div>
        <div class="s ellipsis">${esc(player.current.artista)} · sonando</div></span></div>` : '<p class="sub">Cuando propongan canciones, la más votada empieza a sonar.</p>'}
      <h2 class="sala-h">Lo más votado (${props.length})</h2>
      <div class="fiesta-lista">${props.slice(0, 6).map((p, i) => `<div class="fiesta-fila ${i === 0 ? 'primera' : ''}">
        <span class="fiesta-n">${i + 1}</span>${img(p.cancion.portadaUrl, 120)}
        <span style="min-width:0"><b class="ellipsis">${esc(p.cancion.nombre)}</b><small class="ellipsis">${esc(p.cancion.artista)} · la propuso ${esc(p.de)}</small></span>
        <span class="fiesta-votos">♥ ${p.votos}</span></div>`).join('') || '<p class="sub">Todavía nadie propone canciones.</p>'}</div>
      <div class="actions" style="margin-top:1.4rem"><button class="btn f" data-fi="cerrar">${icon('close')} Terminar la fiesta</button></div>
    </div>
    <div class="sala-der"><div class="qr">${qrSvg(enlace)}</div>
      <div class="sala-codigo"><small>Escanea y propone canciones</small><b>${esc(location.host)}</b></div>
      <p class="sub">Sin cuenta ni app: se abre en el navegador del teléfono.</p></div>
  </div>`, { foco: true });
  if (antes) enfocar($(`[data-fi="${antes}"]`), { scroll: false });
  $('#screen [data-fi="cerrar"]')?.addEventListener('click', async () => {
    try { await api('/api/fiesta', { method: 'DELETE' }); } catch { }
    fiesta.activa = false; fiesta.estado = null; fiesta.pendiente = null;
    toast('Fiesta terminada');
    vistaFiesta();
  });
}

/** Cada 3 s: estado de la fiesta y, si hace falta, pone la más votada a continuación. */
async function sondearFiesta() {
  clearTimeout(fiesta.sondeo);
  if (!fiesta.activa) return;
  try {
    fiesta.estado = await api('/api/fiesta');
    await ponerLaMasVotada();
    const firma = JSON.stringify((fiesta.estado.propuestas || []).map((p) => [p.id, p.votos]));
    if (enPantalla === 'fiesta' && firma !== fiesta.firma) { fiesta.firma = firma; pintarFiesta(); }
  } catch (e) {
    if (e?.status === 404) { fiesta.activa = false; fiesta.estado = null; return; }
  }
  fiesta.sondeo = setTimeout(sondearFiesta, 3000);
}

async function ponerLaMasVotada() {
  // La que ya se puso a continuación salió de la cola (alguien cambió de canción): se puede poner otra
  if (fiesta.pendiente && !player.items.slice(player.index).some((x) => x.id === fiesta.pendiente)) fiesta.pendiente = null;
  const top = fiesta.estado?.propuestas?.[0];
  if (!top || fiesta.pendiente) return;
  const r = await api(`/api/fiesta/tomar/${top.id}`, { method: 'POST' });
  const c = r.cancion;
  if (!player.current || (!player.playing && player.audio.ended)) player.play([c], 0, { type: 'fiesta', label: 'Modo fiesta' });
  else if (player.current?.id !== c.id && player.items[player.index + 1]?.id !== c.id) player.playNext([c]); // sin repetirla
  fiesta.pendiente = c.id;
  toast(`A continuación: ${c.nombre} · la propuso ${r.propuestaPor}`);
}

bus.on('player:song', () => {
  if (!fiesta.activa) return;
  if (player.current?.id === fiesta.pendiente) fiesta.pendiente = null;
  api('/api/fiesta/sonando', { method: 'POST', body: { cancionId: player.current?.id ?? null } }).catch(() => { });
});

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

let vinculo = null, sondeo = 0;

/** Nombre de esta TV para el teléfono («TV · modelo»). */
function nombreTv() {
  try { const m = nativo?.dispositivo?.(); if (m) return /tv/i.test(m) ? m : 'TV · ' + m; } catch { }
  return 'TV';
}

/** Pantalla de entrada: QR + código (con el teléfono) o, si se prefiere, usuario y contraseña con el control. */
function login(msg = '') {
  pintarCuerpo(`<div class="login vinc">
    <div class="vinc-izq">
      <h1><img src="/img/logo-animado.svg" alt="">SpaceMusic</h1>
      <p class="vinc-titulo">Inicia sesión con tu teléfono</p>
      <ol class="vinc-pasos">
        <li>${icon('qr')}<span>Escanea el código con la cámara del teléfono <small>(se abre SpaceMusic; si no tienes la sesión abierta, entra primero)</small></span></li>
        <li>${icon('phone')}<span>O en SpaceMusic del teléfono o la computadora ve a <b>${esc(location.host)}/#/tv</b> y escribe el código</span></li>
        <li>${icon('wifi')}<span>Si tu teléfono está en el mismo wifi que la TV, al abrir SpaceMusic te aparece «Iniciar sesión en la TV»</span></li>
      </ol>
      <div class="err" id="le">${esc(msg)}</div>
      <div class="vinc-acc"><button class="btn f" id="lotro">${icon('keyboard')} Usar usuario y contraseña</button>
        <button class="btn f" id="lnuevo">${icon('refresh')} Código nuevo</button></div>
    </div>
    <div class="vinc-der"><div class="qr" id="lqr"><div class="loading">Preparando…</div></div>
      <div class="vinc-codigo" id="lcod"></div><div class="vinc-estado" id="lest">Esperando a que lo apruebes en tu teléfono…</div></div>
    <form id="lf" class="hidden">
      <h1><img src="/img/logo-animado.svg" alt="">SpaceMusic</h1><p>Inicia sesión con tu cuenta</p>
      <label>Usuario o correo</label><input class="f" id="lu" autocomplete="username">
      <label>Contraseña</label><input class="f" id="lp" type="password" autocomplete="current-password">
      <div class="err" id="le2"></div>
      <button class="btn primary f" type="submit">Entrar</button>
      <button class="btn f" type="button" id="lvolver">${icon('qr')} Volver al código QR</button></form></div>`);
  ocultarSplash();
  pedirVinculo();
  enfocar($('#lotro'), { scroll: false });
  $('#lnuevo').addEventListener('click', () => pedirVinculo());
  $('#lotro').addEventListener('click', () => {
    $('.vinc-izq').classList.add('hidden'); $('.vinc-der').classList.add('hidden'); $('#lf').classList.remove('hidden');
    enfocar($('#lu'), { scroll: false });
  });
  $('#lvolver').addEventListener('click', () => {
    $('#lf').classList.add('hidden'); $('.vinc-izq').classList.remove('hidden'); $('.vinc-der').classList.remove('hidden');
    enfocar($('#lotro'), { scroll: false });
  });
  $('#lf').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('#le').textContent = '';
    try {
      const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ usuario: $('#lu').value.trim(), password: $('#lp').value }) }).then((x) => x.json());
      if (r.error) { $('#le2').textContent = r.error; return; }
      session.save(r.token, r.usuario);
      clearTimeout(sondeo);
      iniciar();
    } catch { $('#le2').textContent = 'No hay conexión con el servidor'; }
  });
}

/** Pide un código al servidor, dibuja el QR y espera la aprobación desde el teléfono. */
async function pedirVinculo() {
  clearTimeout(sondeo);
  try {
    vinculo = await fetch('/api/auth/tv/vinculo', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nombre: nombreTv() }) }).then((x) => x.json());
    if (!vinculo?.codigo) throw new Error(vinculo?.error || 'Sin código');
  } catch (e) {
    $('#lqr') && ($('#lqr').innerHTML = `<div class="loading">No hay conexión con el servidor</div>`);
    sondeo = setTimeout(pedirVinculo, 8000);
    return;
  }
  const enlace = `${location.origin}/#/tv/${vinculo.codigo}`;
  $('#lqr').innerHTML = qrSvg(enlace);
  $('#lcod').innerHTML = `<small>Código</small><b>${esc(vinculo.codigo.slice(0, 3))} ${esc(vinculo.codigo.slice(3))}</b>`;
  $('#lest').textContent = 'Esperando a que lo apruebes en tu teléfono…';
  esperarAprobacion(vinculo);
}

function esperarAprobacion(v) {
  clearTimeout(sondeo);
  sondeo = setTimeout(async () => {
    if (vinculo !== v || !$('#lqr')) return;
    let r;
    try { r = await fetch('/api/auth/tv/vinculo/estado?secreto=' + encodeURIComponent(v.secreto)).then((x) => x.json()); } catch { r = { estado: 'esperando' }; }
    if (vinculo !== v) return;
    if (r.estado === 'aprobado' && r.token) {
      $('#lest').textContent = `¡Listo! Entrando como ${r.usuario?.nombreDisplay || r.usuario?.username || ''}…`;
      session.save(r.token, r.usuario);
      setTimeout(iniciar, 600);
      return;
    }
    if (r.estado === 'vencido') { pedirVinculo(); return; }
    esperarAprobacion(v);
  }, 2000);
}

// ═══════════════════ Eventos en vivo (SyncPlay y control desde otros dispositivos) ═══════════════════

let fuente = null;
function conectarEventos() {
  if (!('EventSource' in window) || !session.token) return;
  try { fuente?.close(); } catch { }
  fuente = new EventSource('/api/eventos?t=' + encodeURIComponent(session.token));
  for (const ev of ['juntos', 'juntos-reaccion', 'juntos-fin', 'control-orden', 'control-dispositivos', 'control-ack']) {
    fuente.addEventListener(ev, (e) => { try { bus.emit(ev, JSON.parse(e.data)); } catch { } });
  }
  fuente.addEventListener('letra', e => { try { if (JSON.parse(e.data).id === player.current?.id) cargarLetra(player.current); } catch {} });
  fuente.addEventListener('hola', () => { if (player.current) cargarLetra(player.current); });
  fuente.onerror = () => {
    // El navegador reintenta solo; si la sesión venció, se vuelve a la pantalla de entrada
    if (fuente.readyState === EventSource.CLOSED) setTimeout(conectarEventos, 5000);
  };
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
  ocultarSplash();
  // La TV aparece en «Dispositivos» de tus otros equipos y recibe sus órdenes; también entra a SyncPlay
  conectarEventos();
  iniciarRemoto();
  retomarSala().catch(() => { });
  api('/api/fiesta').then((e) => { fiesta.activa = true; fiesta.estado = e; sondearFiesta(); }).catch(() => { });
  if (estilo.karaoke === 'si') player.audio.addEventListener('playing', () => aplicarKaraoke(), { once: true });
  api('/api/auth/refresh', { method: 'POST' }).then((r) => r?.token && session.save(r.token, r.usuario)).catch(() => { });
}

iniciar().catch((e) => { pintarCuerpo(`<div class="loading">No se pudo iniciar: ${esc(e.message)}</div>`); ocultarSplash(); });
