// Punto de entrada de la aplicación
import { api, session, logout } from './api.js';
import { $, $$, esc, bus, debounce, fmtTime, cover, coverImg, dominantColor, isMobile, plural } from './util.js';
import { icon } from './icons.js';
import { lib } from './state.js';
import { player, sleep, prefs } from './player.js';
import { route, startRouter, go, parse, render } from './router.js';
import { np, queueDrawer } from './nowplaying.js';
import {
  toast, errorToast, getList, songMenu, showMenu, modal, refreshPlayingMarks, editPlaylist, playlistArt,
} from './ui.js';
import { homeView } from './views/home.js';
import { searchView } from './views/search.js';
import { libraryView, invalidateLibraryCache } from './views/library.js';
import { albumView, artistView, playlistView, likedView, genreView, mixView, historyView } from './views/collection.js';
import { profileView, statsView, applyAccent, avatarHtml, openEqualizer } from './views/account.js';
import { adminView, uploadView } from './views/admin.js';

if (!session.valid()) {
  logout(session.token ? 'expirada' : '');
  throw new Error('Sin sesión');
}
applyAccent();

// ═══════════════════ Estructura de la página ═══════════════════

document.body.innerHTML = `
<div class="app">
  <nav class="sidebar" aria-label="Navegación principal">
    <div class="side-block">
      <a class="brand" href="#/"><span class="brand-logo">${icon('note')}</span>SpaceMusic</a>
      <a class="nav-link" href="#/" data-nav="/">${icon('home')}Inicio</a>
      <a class="nav-link" href="#/buscar" data-nav="/buscar">${icon('search')}Buscar</a>
      <a class="nav-link" href="#/biblioteca" data-nav="/biblioteca">${icon('library')}Tu biblioteca</a>
      <a class="nav-link" href="#/estadisticas" data-nav="/estadisticas">${icon('chart')}Tus estadísticas</a>
      <a class="nav-link hidden" href="#/subir" data-nav="/subir" data-role="edit">${icon('upload')}Subir canciones</a>
      <a class="nav-link hidden" href="#/admin" data-nav="/admin" data-role="admin">${icon('shield')}Administración<span class="badge warning hidden" data-pending></span></a>
    </div>
    <div class="side-block grow">
      <div class="side-head"><span>Playlists</span><button class="icon-btn" data-new-pl aria-label="Nueva playlist" title="Nueva playlist">${icon('plus')}</button></div>
      <div class="side-list" id="sideList"></div>
    </div>
  </nav>

  <main class="main">
    <header class="topbar" id="topbar">
      <div class="nav-arrows"><button class="round-btn" data-back aria-label="Atrás">${icon('left')}</button><button class="round-btn fwd" data-fwd aria-label="Adelante">${icon('right')}</button></div>
      <div class="top-search" role="search"><span>${icon('search')}</span>
        <input id="searchInput" type="search" placeholder="¿Qué quieres escuchar?" autocomplete="off" aria-label="Buscar">
        <button class="clear hidden" data-clear-search aria-label="Borrar búsqueda">${icon('close', 'sm')}</button></div>
      <div class="topbar-title ellipsis" id="topTitle"></div>
      <div class="spacer"></div>
      <button class="user-btn" data-user-menu aria-label="Tu cuenta"><span data-avatar></span><span class="name ellipsis" data-uname></span></button>
    </header>
    <div class="view" id="view"></div>
  </main>

  <footer class="player no-song" id="playerBar" aria-label="Reproductor">
    <div class="np-mini">
      <div class="cover" data-open-np title="Reproduciendo ahora"><span data-pcover></span><span class="expand">${icon('up')}</span></div>
      <div class="tt"><div class="name ellipsis" data-pname></div><div class="artist ellipsis" data-partist></div></div>
      <button class="icon-btn" data-pfav aria-label="Me gusta">${icon('heart')}</button>
    </div>
    <div class="controls">
      <div class="ctrl-btns">
        <button class="icon-btn hide-m" data-c="shuffle" aria-label="Aleatorio" title="Aleatorio (S)">${icon('shuffle')}</button>
        <button class="icon-btn hide-m" data-c="prev" aria-label="Anterior" title="Anterior">${icon('prev')}</button>
        <button class="main-play" data-c="play" aria-label="Reproducir" title="Reproducir / Pausa (espacio)">${icon('play')}</button>
        <button class="icon-btn hide-m" data-c="next" aria-label="Siguiente" title="Siguiente">${icon('next')}</button>
        <button class="icon-btn hide-m" data-c="repeat" aria-label="Repetir" title="Repetir (R)">${icon('repeat')}</button>
      </div>
      <div class="progress"><span class="time" data-tcur>0:00</span>
        <div class="seek" data-seek="main" role="slider" aria-label="Posición" tabindex="0"><div class="rail"><div class="buf"></div><div class="fill"></div></div><div class="knob"></div><div class="tip"></div></div>
        <span class="time" data-tdur>0:00</span></div>
    </div>
    <div class="extra">
      <span class="sleep-badge hidden" data-sleep-badge></span>
      <button class="icon-btn" data-c="sleep" aria-label="Temporizador" title="Temporizador para dormir">${icon('moon')}</button>
      <button class="icon-btn" data-c="lyrics" aria-label="Letra" title="Letra (L)">${icon('mic')}</button>
      <button class="icon-btn" data-c="queue" aria-label="Cola" title="Cola (Q)">${icon('queue')}</button>
      <div class="volume"><button class="icon-btn" data-c="mute" aria-label="Silenciar" title="Silenciar (M)">${icon('volume')}</button>
        <div class="seek" data-seek="vol" role="slider" aria-label="Volumen" tabindex="0"><div class="rail"><div class="fill"></div></div><div class="knob"></div></div></div>
      <button class="icon-btn" data-c="np" aria-label="Pantalla completa" title="Reproduciendo ahora (F)">${icon('expand')}</button>
    </div>
  </footer>
</div>
<nav class="mobile-nav" aria-label="Navegación">
  <a href="#/" data-nav="/">${icon('home')}Inicio</a>
  <a href="#/buscar" data-nav="/buscar">${icon('search')}Buscar</a>
  <a href="#/biblioteca" data-nav="/biblioteca">${icon('library')}Biblioteca</a>
  <a href="#/perfil" data-nav="/perfil">${icon('user')}Perfil</a>
</nav>
<div class="toasts" id="toasts"></div>`;

// ═══════════════════ Rutas ═══════════════════

route('/', homeView);
route('/buscar', searchView);
route('/biblioteca', libraryView);
route('/favoritos', likedView);
route('/historial', historyView);
route('/album/:nombre', albumView);
route('/artista/:nombre', artistView);
route('/playlist/:id', playlistView);
route('/genero/:nombre', genreView);
route('/mix/:id', mixView);
route('/perfil', profileView);
route('/estadisticas', statsView);
route('/admin', adminView);
route('/subir', uploadView);

// ═══════════════════ Usuario y permisos ═══════════════════

function paintUser(u = session.user) {
  $('[data-avatar]').innerHTML = avatarHtml(u);
  $('[data-uname]').textContent = u?.nombreDisplay || u?.username || '';
  $$('[data-role="edit"]').forEach((e) => e.classList.toggle('hidden', !session.canEdit));
  $$('[data-role="admin"]').forEach((e) => e.classList.toggle('hidden', !session.isAdmin));
}
bus.on('user', paintUser);
paintUser();

$('[data-user-menu]').addEventListener('click', (e) => {
  const u = session.user;
  showMenu(e.currentTarget, [
    { label: 'Perfil y ajustes', icon: 'user', fn: () => go('/perfil') },
    { label: 'Tus estadísticas', icon: 'chart', fn: () => go('/estadisticas') },
    { label: 'Escuchado recientemente', icon: 'history', fn: () => go('/historial') },
    { label: 'Ecualizador', icon: 'sliders', fn: () => openEqualizer() },
    session.canEdit ? { label: 'Subir canciones', icon: 'upload', fn: () => go('/subir') } : null,
    session.isAdmin ? { label: 'Administración', icon: 'shield', fn: () => go('/admin') } : null,
    { label: 'Atajos de teclado', icon: 'keyboard', fn: showShortcuts },
    { sep: true },
    { label: 'Cerrar sesión', icon: 'logout', danger: true, fn: () => logout() },
  ], `<div class="menu-head">${avatarHtml(u)}<div style="min-width:0"><div class="t ellipsis">${esc(u?.nombreDisplay || '')}</div><div class="s ellipsis">${esc(u?.email || '')}</div></div></div>`);
});

// ═══════════════════ Barra lateral ═══════════════════

function paintSidebar() {
  const box = $('#sideList');
  const { path } = parse();
  const ctxRoute = player.context?.route;
  const fav = `<a class="side-item ${path === '/favoritos' ? 'active' : ''} ${ctxRoute === '#/favoritos' ? 'playing' : ''}" href="#/favoritos">
    <div class="thumb" style="background:linear-gradient(135deg,#4a1fb8,#8fa7d8);display:grid;place-items:center">${icon('heartFill')}</div>
    <div class="meta"><div class="t ellipsis">Me gusta</div><div class="s">${plural(lib.favs.size, 'canción', 'canciones')}</div></div></a>`;
  const items = lib.playlists.map((p) => `<a class="side-item ${path === '/playlist/' + p.id ? 'active' : ''} ${ctxRoute === '#/playlist/' + p.id ? 'playing' : ''}" href="#/playlist/${p.id}">
    <div class="thumb" style="overflow:hidden">${playlistArt(p, 160)}</div>
    <div class="meta"><div class="t ellipsis">${esc(p.nombre)}</div><div class="s ellipsis">${p.publica ? 'Pública • ' : ''}${plural(p.cancionesCount, 'canción', 'canciones')}</div></div></a>`).join('');
  box.innerHTML = fav + (items || `<div class="side-empty">Crea tu primera playlist para organizar tu música.<br><button class="btn sm primary" data-new-pl>Crear playlist</button></div>`);
}
bus.on('playlists', paintSidebar);
bus.on('favs', debounce(paintSidebar, 300));
document.addEventListener('click', async (e) => {
  if (!e.target.closest('[data-new-pl]')) return;
  const p = await editPlaylist(null);
  if (p) go('/playlist/' + p.id);
});

function paintNav() {
  const { path } = parse();
  $$('[data-nav]').forEach((a) => {
    const n = a.dataset.nav;
    a.classList.toggle('active', n === '/' ? path === '/' : path.startsWith(n));
  });
  paintSidebar();
}
bus.on('route', ({ path, query }) => {
  paintNav();
  document.body.classList.toggle('on-search', path === '/buscar');
  const inp = $('#searchInput');
  if (path === '/buscar') { if (document.activeElement !== inp) inp.value = query.q || ''; }
  else inp.value = '';
  $('[data-clear-search]').classList.toggle('hidden', !inp.value);
  if (np.isOpen && !isMobile()) np.close();
  if (isMobile() && np.isOpen) np.close();
});

// ═══════════════════ Barra superior ═══════════════════

$('[data-back]').onclick = () => history.back();
$('[data-fwd]').onclick = () => history.forward();
const view = $('#view');
view.addEventListener('scroll', () => {
  const y = view.scrollTop;
  $('#topbar').classList.toggle('solid', y > 30);
  $('#topTitle').classList.toggle('show', y > 260);
}, { passive: true });
bus.on('rendered', () => { $('#topbar').classList.toggle('solid', view.scrollTop > 30); });

const search = $('#searchInput');
const doSearch = debounce(() => {
  const q = search.value.trim();
  const onSearch = parse().path === '/buscar';
  go('/buscar' + (q ? '?q=' + encodeURIComponent(q) : ''), { replace: onSearch });
}, 280);
search.addEventListener('input', () => { $('[data-clear-search]').classList.toggle('hidden', !search.value); doSearch(); });
search.addEventListener('focus', () => { if (parse().path !== '/buscar' && !isMobile()) { /* solo al escribir */ } });
search.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); doSearch(); }
  if (e.key === 'Escape') { search.blur(); }
});
$('[data-clear-search]').onclick = () => { search.value = ''; search.focus(); go('/buscar', { replace: true }); };

// ═══════════════════ Acciones globales (clics en tarjetas y listas) ═══════════════════

async function playCollection(kind, val) {
  try {
    if (kind === 'album') {
      const a = await api('/api/albumes/detalle?nombre=' + encodeURIComponent(val));
      player.play(a.canciones, player.shuffle ? -1 : 0, { type: 'album', label: a.nombre, route: '#/album/' + encodeURIComponent(a.nombre) });
    } else if (kind === 'artist') {
      const a = await api('/api/artistas/' + encodeURIComponent(val));
      const list = [...a.populares, ...a.canciones.filter((c) => !a.populares.some((p) => p.id === c.id))];
      player.play(list, player.shuffle ? -1 : 0, { type: 'artista', label: a.nombre, route: '#/artista/' + encodeURIComponent(a.nombre) });
    } else if (kind === 'playlist') {
      const p = await api('/api/playlists/' + val);
      if (!p.canciones.length) return toast('La playlist está vacía');
      player.play(p.canciones, player.shuffle ? -1 : 0, { type: 'playlist', label: p.nombre, route: '#/playlist/' + p.id });
    } else if (kind === 'mix') {
      const s = await api('/api/mixes/' + encodeURIComponent(val) + '?cantidad=40');
      if (!s.length) return toast('No hay canciones para esta mezcla');
      player.play(s, 0, { type: 'mix', label: s.length ? (val === 'diario' ? 'Mix del día' : val === 'descubrir' ? 'Descubrir' : 'Mix de ' + val.split(':')[1]) : '', route: '#/mix/' + encodeURIComponent(val) });
    } else if (kind === 'liked') {
      const s = await api('/api/usuario/favoritos');
      player.play(s, player.shuffle ? -1 : 0, { type: 'favoritos', label: 'Me gusta', route: '#/favoritos' });
    }
  } catch (err) { errorToast(err); }
}

document.addEventListener('click', (e) => {
  if (e.target.closest('[data-stop]')) return; // enlaces dentro de filas
  const el = e.target.closest('[data-act]');
  if (!el) return;
  const act = el.dataset.act;
  if (act === 'play-list') {
    if (el.closest('.tracks')?._suppressClick) return;
    if (e.target.closest('[data-drag]')) return;
    if (el.tagName === 'A' || el.closest('a.card')) e.preventDefault();
    e.stopPropagation();
    const l = getList(el.dataset.list);
    if (!l) return;
    const i = +el.dataset.i;
    const s = l.songs[i];
    if (player.current?.id === s.id && player.context?.route === l.context?.route) { player.toggle(); return; }
    player.play(l.songs, i, l.context);
    return;
  }
  e.preventDefault();
  e.stopPropagation();
  if (act === 'fav') lib.toggleFav(el.dataset.id).catch(errorToast);
  else if (act === 'song-menu') {
    const l = getList(el.dataset.list);
    if (l) songMenu(el, l.songs[+el.dataset.i], { playlistId: el.dataset.pl ? +el.dataset.pl : null });
  }
  else if (act === 'play-album') playCollection('album', el.dataset.name);
  else if (act === 'play-artist') playCollection('artist', el.dataset.name);
  else if (act === 'play-playlist') playCollection('playlist', el.dataset.id);
  else if (act === 'play-mix') playCollection('mix', el.dataset.id);
  else if (act === 'play-liked') playCollection('liked');
}, true);

// Clic derecho sobre una canción → menú
document.addEventListener('contextmenu', (e) => {
  const row = e.target.closest('.track[data-list], .card[data-list]');
  if (!row) return;
  const l = getList(row.dataset.list);
  if (!l) return;
  e.preventDefault();
  const plBtn = row.querySelector('[data-pl]');
  songMenu(e, l.songs[+row.dataset.i], { playlistId: plBtn ? +plBtn.dataset.pl : null });
});

// Enter sobre una fila enfocada
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter') return;
  const row = e.target.closest?.('.track[data-act="play-list"]');
  if (row) { e.preventDefault(); row.click(); }
});

// ═══════════════════ Reproductor inferior ═══════════════════

const bar = $('#playerBar');

function paintSong() {
  const s = player.current;
  bar.classList.toggle('no-song', !s);
  if (!s) return;
  $('[data-pcover]').innerHTML = coverImg(s.portadaUrl, 160, '');
  $('[data-pname]').innerHTML = s.album && s.album !== 'Single'
    ? `<a href="#/album/${encodeURIComponent(s.album)}">${esc(s.nombre)}</a>` : esc(s.nombre);
  $('[data-partist]').innerHTML = `<a href="#/artista/${encodeURIComponent(s.artista)}">${esc(s.artista)}</a>`;
  paintFav();
  dominantColor(s.portadaUrl).then((c) => bar.style.setProperty('--player-color', `rgb(${c.map((x) => Math.round(x * 0.55)).join(',')})`));
  refreshPlayingMarks();
  paintSidebar();
}

function paintFav() {
  const s = player.current;
  if (!s) return;
  const f = lib.isFav(s.id);
  const b = $('[data-pfav]');
  b.classList.toggle('liked', f);
  b.innerHTML = icon(f ? 'heartFill' : 'heart');
  b.setAttribute('aria-label', f ? 'Quitar de Me gusta' : 'Me gusta');
}

function paintState() {
  const b = $('[data-c="play"]');
  b.innerHTML = player.buffering && player.playing ? '<span class="spinner" style="width:18px;height:18px;border-color:rgba(0,0,0,.25);border-top-color:#000"></span>' : icon(player.playing ? 'pause' : 'play');
  b.setAttribute('aria-label', player.playing ? 'Pausa' : 'Reproducir');
  refreshPlayingMarks();
  if (!player.current) document.title = 'SpaceMusic';
}

function paintMode() {
  $('[data-c="shuffle"]').classList.toggle('active', player.shuffle);
  const r = $('[data-c="repeat"]');
  r.classList.toggle('active', player.repeat !== 'off');
  r.innerHTML = icon('repeat') + (player.repeat === 'one' ? '<span class="repeat-one">1</span>' : '');
  r.title = { off: 'Repetir', all: 'Repetir todo', one: 'Repetir una' }[player.repeat];
}

function setSeek(el, frac, buf) {
  frac = Math.max(0, Math.min(1, frac || 0));
  el.querySelector('.fill').style.width = frac * 100 + '%';
  el.querySelector('.knob').style.left = frac * 100 + '%';
  if (buf !== undefined) { const b = el.querySelector('.buf'); if (b) b.style.width = buf * 100 + '%'; }
}

let dragging = null;
function paintTime() {
  const a = player.audio;
  const dur = isFinite(a.duration) ? a.duration : player.current?.duracionSegundos || 0;
  if (dragging !== 'main') {
    $('[data-tcur]').textContent = fmtTime(a.currentTime);
    setSeek($('[data-seek="main"]'), dur ? a.currentTime / dur : 0);
  }
  $('[data-tdur]').textContent = fmtTime(dur);
  const nps = $('[data-seek="np"]');
  if (nps && np.isOpen && dragging !== 'np') {
    setSeek(nps, dur ? a.currentTime / dur : 0);
    $('[data-np-cur]').textContent = fmtTime(a.currentTime);
    $('[data-np-dur]').textContent = fmtTime(dur);
  }
}

function paintBuffer() {
  const a = player.audio;
  if (!a.buffered.length || !isFinite(a.duration)) return;
  const end = a.buffered.end(a.buffered.length - 1) / a.duration;
  $$('[data-seek="main"] .buf, [data-seek="np"] .buf').forEach((b) => { b.style.width = end * 100 + '%'; });
}

function paintVolume() {
  const v = player.muted ? 0 : player.volume;
  setSeek($('[data-seek="vol"]'), v);
  $('[data-c="mute"]').innerHTML = icon(v === 0 ? 'mute' : v < 0.5 ? 'volumeLow' : 'volume');
}

/** Barras deslizables (progreso y volumen) con ratón o dedo. */
document.addEventListener('pointerdown', (e) => {
  const sk = e.target.closest('[data-seek]');
  if (!sk) return;
  const kind = sk.dataset.seek;
  if (kind !== 'vol' && !player.current) return;
  e.preventDefault();
  const rect = () => sk.getBoundingClientRect();
  const frac = (x) => { const r = rect(); return Math.max(0, Math.min(1, (x - r.left) / r.width)); };
  dragging = kind;
  sk.classList.add('dragging');
  const dur = () => (isFinite(player.audio.duration) ? player.audio.duration : player.current?.duracionSegundos || 0);
  const move = (ev) => {
    const f = frac(ev.clientX);
    setSeek(sk, f);
    if (kind === 'vol') player.setVolume(f);
    else if (kind === 'main') $('[data-tcur]').textContent = fmtTime(f * dur());
    else $('[data-np-cur]').textContent = fmtTime(f * dur());
  };
  const up = (ev) => {
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', up);
    sk.classList.remove('dragging');
    if (kind !== 'vol') player.seek(frac(ev.clientX) * dur());
    dragging = null;
  };
  move(e);
  document.addEventListener('pointermove', move);
  document.addEventListener('pointerup', up);
});
// Vista previa del tiempo al pasar el ratón
document.addEventListener('pointermove', (e) => {
  const sk = e.target.closest?.('[data-seek="main"], [data-seek="np"]');
  if (!sk || !player.current) return;
  const r = sk.getBoundingClientRect();
  const f = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
  const tip = sk.querySelector('.tip');
  const dur = isFinite(player.audio.duration) ? player.audio.duration : player.current.duracionSegundos;
  tip.textContent = fmtTime(f * dur);
  tip.style.left = f * 100 + '%';
});
$('[data-seek="vol"]').addEventListener('wheel', (e) => { e.preventDefault(); player.setVolume(player.volume - Math.sign(e.deltaY) * 0.05); }, { passive: false });
$('[data-seek="main"]').addEventListener('keydown', (e) => {
  if (e.key === 'ArrowRight') { e.preventDefault(); e.stopPropagation(); player.seekBy(5); }
  if (e.key === 'ArrowLeft') { e.preventDefault(); e.stopPropagation(); player.seekBy(-5); }
});

bar.addEventListener('click', (e) => {
  // En el móvil, tocar el mini reproductor (salvo sus botones) abre la pantalla completa
  if (isMobile() && !e.target.closest('button, [data-seek]')) { e.preventDefault(); np.open(); return; }
  const c = e.target.closest('[data-c]')?.dataset.c;
  if (e.target.closest('[data-open-np]') || (isMobile() && !c && !e.target.closest('a, button, [data-seek]'))) { np.open(); return; }
  if (e.target.closest('[data-pfav]') && player.current) { lib.toggleFav(player.current.id).catch(errorToast); return; }
  if (!c) return;
  if (c === 'play') player.toggle();
  else if (c === 'prev') player.prev();
  else if (c === 'next') player.next();
  else if (c === 'shuffle') { player.toggleShuffle(); toast(player.shuffle ? 'Aleatorio activado' : 'Aleatorio desactivado', { ms: 1500 }); }
  else if (c === 'repeat') { player.cycleRepeat(); toast({ off: 'Repetir desactivado', all: 'Repetir todo', one: 'Repetir esta canción' }[player.repeat], { ms: 1500 }); }
  else if (c === 'mute') player.toggleMute();
  else if (c === 'queue') queueDrawer.toggle();
  else if (c === 'lyrics') np.toggle('letra');
  else if (c === 'np') np.toggle();
  else if (c === 'sleep') sleepMenu(e.target.closest('[data-c]'));
});

function sleepMenu(anchor) {
  const opt = (m, l) => ({ label: l, icon: 'clock', fn: () => { sleep.set(m); toast(m === 'end' ? 'Se detendrá al terminar la canción' : `Se detendrá en ${l.toLowerCase()}`); } });
  showMenu(anchor, [
    { title: sleep.active ? 'Temporizador activo' : 'Detener la música en…' },
    opt(5, '5 minutos'), opt(15, '15 minutos'), opt(30, '30 minutos'), opt(45, '45 minutos'), opt(60, '1 hora'), opt(90, '1 hora y media'),
    opt('end', 'Al terminar la canción'),
    sleep.active ? { sep: true } : null,
    sleep.active ? { label: 'Desactivar temporizador', icon: 'close', danger: true, fn: () => { sleep.stop(); toast('Temporizador desactivado'); } } : null,
  ]);
}
bus.on('sleep', () => {
  const b = $('[data-sleep-badge]');
  const btn = $('[data-c="sleep"]');
  btn.classList.toggle('active', sleep.active);
  if (sleep.endOfSong) { b.textContent = 'fin'; b.classList.remove('hidden'); }
  else if (sleep.until) { b.textContent = fmtTime(sleep.remaining / 1000); b.classList.remove('hidden'); }
  else b.classList.add('hidden');
});

bus.on('player:song', paintSong);
bus.on('player:state', paintState);
bus.on('player:mode', paintMode);
bus.on('player:time', paintTime);
bus.on('player:buffer', paintBuffer);
bus.on('player:volume', paintVolume);
bus.on('favs', paintFav);
bus.on('drawer', (open) => $('[data-c="queue"]').classList.toggle('active', open));
bus.on('np:open', () => { $('[data-c="np"]').classList.add('active'); $('[data-c="lyrics"]').classList.toggle('active', np.tab === 'letra'); paintTime(); });
bus.on('np:close', () => { $('[data-c="np"]').classList.remove('active'); $('[data-c="lyrics"]').classList.remove('active'); });
bus.on('library:changed', () => invalidateLibraryCache());
bus.on('song:updated', () => { invalidateLibraryCache(); render(); });

// ═══════════════════ Atajos de teclado ═══════════════════

const SHORTCUTS = [
  ['Espacio', 'Reproducir / pausar'], ['→ / ←', 'Adelantar / retroceder 5 s'], ['Shift + → / ←', 'Siguiente / anterior'],
  ['↑ / ↓', 'Subir / bajar volumen'], ['M', 'Silenciar'], ['S', 'Aleatorio'], ['R', 'Repetir'], ['L', 'Letra'],
  ['Q', 'Cola'], ['F', 'Reproduciendo ahora'], ['/', 'Buscar'], ['?', 'Ver atajos'],
];
function showShortcuts() {
  modal({ title: 'Atajos de teclado', body: `<div class="kbd-list">${SHORTCUTS.map(([k, d]) => `<span>${k.split(' ').map((x) => (x === '/' && k.length > 1) || x === '+' ? x : `<kbd>${esc(x)}</kbd>`).join(' ')}</span><span class="muted">${d}</span>`).join('')}</div>` });
}
bus.on('shortcuts', showShortcuts);

document.addEventListener('keydown', (e) => {
  const t = e.target;
  if (t.closest?.('input, textarea, select, [contenteditable]') || e.ctrlKey || e.metaKey || e.altKey) return;
  if ($('.backdrop:not(.menu-backdrop)')) return;
  const k = e.key;
  const handled = () => e.preventDefault();
  if (k === ' ' || k === 'k') { handled(); player.toggle(); }
  else if (k === 'ArrowRight') { handled(); e.shiftKey ? player.next() : player.seekBy(5); }
  else if (k === 'ArrowLeft') { handled(); e.shiftKey ? player.prev() : player.seekBy(-5); }
  else if (k === 'ArrowUp' && !t.closest?.('.view')) { handled(); player.setVolume(player.volume + 0.05); }
  else if (k === 'ArrowDown' && !t.closest?.('.view')) { handled(); player.setVolume(player.volume - 0.05); }
  else if (k === 'm' || k === 'M') player.toggleMute();
  else if (k === 's' || k === 'S') player.toggleShuffle();
  else if (k === 'r' || k === 'R') player.cycleRepeat();
  else if (k === 'l' || k === 'L') np.toggle('letra');
  else if (k === 'q' || k === 'Q') queueDrawer.toggle();
  else if (k === 'f' || k === 'F') np.toggle();
  else if (k === '/') { handled(); search.focus(); search.select(); }
  else if (k === '?') showShortcuts();
  else if (k === 'Escape') { if (np.isOpen) np.close(); else if (queueDrawer.isOpen) queueDrawer.close(); }
});

// ═══════════════════ Inicio ═══════════════════

async function boot() {
  // Renovar el token (y el rol, por si un admin lo cambió)
  api('/api/auth/refresh', { method: 'POST' }).then((r) => {
    if (r?.token) { session.save(r.token, r.usuario); paintUser(r.usuario); }
  }).catch((e) => { if (e.status === 403) logout('desactivada'); });

  await lib.init();
  paintSidebar();
  player.restore();
  paintVolume();
  paintMode();
  paintState();
  startRouter();

  if (session.isAdmin) {
    api('/api/admin/resumen').then((r) => {
      const b = $('[data-pending]');
      if (r.solicitudesPendientes) { b.textContent = r.solicitudesPendientes; b.classList.remove('hidden'); }
    }).catch(() => { });
  }
  registrarServiceWorker();
}

/** Guarda la app en el dispositivo y avisa cuando hay una versión nueva. */
function registrarServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  let recargando = false;
  const habiaVersion = !!navigator.serviceWorker.controller; // la primera visita no recarga
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (recargando || !habiaVersion) return;
    recargando = true;
    location.reload();
  });
  const avisar = (worker) => toast('Hay una versión nueva de SpaceMusic', {
    ms: 15000,
    action: { label: 'Actualizar', fn: () => worker.postMessage('activar') },
  });
  navigator.serviceWorker.register('/sw.js').then((reg) => {
    if (reg.waiting && navigator.serviceWorker.controller) avisar(reg.waiting);
    reg.addEventListener('updatefound', () => {
      const w = reg.installing;
      w?.addEventListener('statechange', () => {
        if (w.state === 'installed' && navigator.serviceWorker.controller) avisar(w);
      });
    });
    // Buscar versiones nuevas cada 30 minutos mientras la app está abierta
    setInterval(() => reg.update().catch(() => { }), 30 * 60 * 1000);
  }).catch(() => { });
}

boot().catch((e) => { console.error(e); toast('No se pudo iniciar la app: ' + e.message, { error: true }); });
void cover; void prefs;
