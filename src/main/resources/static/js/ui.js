// Componentes de interfaz: avisos, menús, diálogos, tarjetas y listas
import { icon } from './icons.js';
import { descargar, quitarDescarga, estaDescargada } from './offline.js';
import { esc, $, $$, bus, fmtTime, fmtNum, plural, cover, coverImg, mosaic, isMobile, norm } from './util.js';
import { api, session, downloadUrl, upload } from './api.js';
import { lib } from './state.js';
import { player } from './player.js';

// ═══════════════════ Avisos ═══════════════════

export function toast(msg, { error = false, action, ms = 3200 } = {}) {
  let box = $('#toasts');
  if (!box) { box = document.createElement('div'); box.id = 'toasts'; box.className = 'toasts'; document.body.appendChild(box); }
  const el = document.createElement('div');
  el.className = 'toast' + (error ? ' error' : '');
  el.setAttribute('role', 'status');
  el.innerHTML = `<span>${esc(msg)}</span>${action ? `<button type="button">${esc(action.label)}</button>` : ''}`;
  if (action) el.querySelector('button').onclick = () => { action.fn(); close(); };
  box.appendChild(el);
  while (box.children.length > 3) box.firstChild.remove();
  const close = () => { el.classList.add('out'); setTimeout(() => el.remove(), 260); };
  setTimeout(close, ms);
}
bus.on('toast', (t) => toast(t.msg, t));

export function errorToast(e) {
  if (e?.name === 'AbortError') return;
  toast(e?.message || 'Algo salió mal', { error: true });
}

// ═══════════════════ Menús contextuales ═══════════════════

let openMenu = null;
export function closeMenu() {
  if (openMenu) { openMenu.remove(); openMenu = null; }
  $('.menu-backdrop')?.remove();
}

/**
 * Muestra un menú. items: [{label, icon, fn, danger, sep, title, disabled}]
 * anchor: evento o elemento junto al que se coloca.
 */
export function showMenu(anchor, items, head) {
  closeMenu();
  const m = document.createElement('div');
  m.className = 'menu';
  m.setAttribute('role', 'menu');
  m.innerHTML = (head || '') + items.filter(Boolean).map((it, i) => {
    if (it.sep) return '<div class="menu-sep"></div>';
    if (it.title) return `<div class="menu-title">${esc(it.title)}</div>`;
    return `<button class="menu-item ${it.danger ? 'danger' : ''}" role="menuitem" data-i="${i}" ${it.disabled ? 'disabled' : ''}>
      ${icon(it.icon || 'note')}<span class="ellipsis">${esc(it.label)}</span>${it.sub ? icon('right', 'sm chev') : ''}</button>`;
  }).join('');
  const list = items.filter(Boolean);
  m.addEventListener('click', (e) => {
    const b = e.target.closest('.menu-item');
    if (!b) return;
    const it = list[+b.dataset.i];
    if (!it.keepOpen) closeMenu();
    it.fn?.(e);
  });
  if (isMobile()) {
    const bd = document.createElement('div');
    bd.className = 'backdrop menu-backdrop';
    bd.style.zIndex = 115;
    bd.onclick = closeMenu;
    document.body.appendChild(bd);
  }
  document.body.appendChild(m);
  openMenu = m;
  if (!isMobile()) {
    let x, y;
    if (anchor instanceof Event) { x = anchor.clientX; y = anchor.clientY; }
    else { const r = anchor.getBoundingClientRect(); x = r.left; y = r.bottom + 4; }
    const w = m.offsetWidth, h = m.offsetHeight;
    if (x + w > innerWidth - 8) x = Math.max(8, innerWidth - w - 8);
    if (y + h > innerHeight - 8) y = Math.max(8, innerHeight - h - 8);
    m.style.left = x + 'px';
    m.style.top = y + 'px';
  }
  m.querySelector('.menu-item')?.focus({ preventScroll: true });
}

document.addEventListener('pointerdown', (e) => {
  if (openMenu && !openMenu.contains(e.target) && !e.target.closest('.menu-backdrop')) closeMenu();
}, true);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeMenu(); });
window.addEventListener('resize', closeMenu);
document.addEventListener('scroll', closeMenu, true);

// ═══════════════════ Diálogos ═══════════════════

/**
 * Abre un diálogo. Devuelve una promesa con el valor del botón pulsado
 * (o null si se cierra). body puede ser HTML; onOpen recibe el elemento.
 */
export function modal({ title, body = '', actions = [{ label: 'Cerrar', value: null }], wide = false, onOpen, onAction }) {
  return new Promise((resolve) => {
    const bd = document.createElement('div');
    bd.className = 'backdrop';
    bd.innerHTML = `<div class="modal ${wide ? 'wide' : ''}" role="dialog" aria-modal="true">
      <h3><span>${esc(title)}</span><button class="icon-btn" data-close aria-label="Cerrar">${icon('close')}</button></h3>
      <div class="modal-body">${body}</div>
      ${actions.length ? `<div class="foot">${actions.map((a, i) =>
        `<button class="btn ${a.primary ? 'primary' : a.danger ? 'danger' : 'ghost'}" data-a="${i}" type="button">${esc(a.label)}</button>`).join('')}</div>` : ''}
    </div>`;
    const el = bd.querySelector('.modal');
    let done = false;
    const close = (v) => {
      if (done) return;
      done = true;
      bd.remove();
      document.removeEventListener('keydown', onKey);
      resolve(v);
    };
    const onKey = (e) => {
      if (e.key === 'Escape') { e.stopPropagation(); close(null); }
      if (e.key === 'Enter' && e.target.tagName === 'INPUT' && e.target.type !== 'file') {
        const p = actions.findIndex((a) => a.primary);
        if (p >= 0) { e.preventDefault(); el.querySelector(`[data-a="${p}"]`).click(); }
      }
    };
    bd.addEventListener('pointerdown', (e) => { if (e.target === bd) bd._down = true; });
    bd.addEventListener('click', async (e) => {
      if (e.target === bd && bd._down) return close(null);
      bd._down = false;
      if (e.target.closest('[data-close]')) return close(null);
      const b = e.target.closest('[data-a]');
      if (!b) return;
      const a = actions[+b.dataset.a];
      if (onAction) {
        b.disabled = true;
        try {
          const r = await onAction(a.value, el);
          if (r !== false) close(r === undefined ? a.value : r);
        } catch (err) { errorToast(err); }
        finally { b.disabled = false; }
      } else close(a.value);
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(bd);
    onOpen?.(el, close);
    el.querySelector('input:not([type=file]), textarea, select')?.focus();
  });
}

export async function confirmDialog(text, { title = '¿Seguro?', ok = 'Aceptar', danger = false } = {}) {
  const v = await modal({
    title, body: `<p>${esc(text)}</p>`,
    actions: [{ label: 'Cancelar', value: false }, { label: ok, value: true, primary: !danger, danger }],
  });
  return v === true;
}

export async function promptDialog(title, { label = '', value = '', placeholder = '', ok = 'Guardar', optional = false } = {}) {
  return modal({
    title,
    body: `<div class="field"><label>${esc(label)}</label><input id="pd" value="${esc(value)}" placeholder="${esc(placeholder)}" maxlength="250"></div>`,
    actions: [{ label: 'Cancelar', value: null }, { label: ok, value: 'ok', primary: true }],
    onAction: (v, el) => (v === 'ok' ? el.querySelector('#pd').value.trim() || (optional ? '' : false) : null),
  });
}

// ═══════════════════ Registro de listas (para menús y reproducción) ═══════════════════

const lists = new Map();
let listSeq = 0;
/** Guarda una lista de canciones con su contexto; devuelve su id. */
export function registerList(songs, context) {
  const id = 'l' + (++listSeq);
  lists.set(id, { songs, context });
  if (lists.size > 60) lists.delete(lists.keys().next().value);
  return id;
}
export const getList = (id) => lists.get(id);

// ═══════════════════ Tarjetas ═══════════════════

const playBtn = (attrs) => `<button class="play-fab" ${attrs} aria-label="Reproducir">${icon('play')}</button>`;

export function cardSong(s, listId, i) {
  return `<div class="card" data-act="play-list" data-list="${listId}" data-i="${i}" data-song="${s.id}">
    <div class="art">${coverImg(s.portadaUrl, 300, s.nombre)}${playBtn(`data-act="play-list" data-list="${listId}" data-i="${i}"`)}</div>
    <div class="t ellipsis">${esc(s.nombre)}</div>
    <div class="s">${esc(s.artista)}</div>
  </div>`;
}

export function cardAlbum(a) {
  const href = '#/album/' + encodeURIComponent(a.nombre);
  return `<a class="card" href="${href}" data-album="${esc(a.nombre)}">
    <div class="art">${coverImg(a.portadaUrl, 300, a.nombre)}${playBtn(`data-act="play-album" data-name="${esc(a.nombre)}"`)}</div>
    <div class="t ellipsis">${esc(a.nombre)}</div>
    <div class="s">${a.anio ? a.anio + ' • ' : ''}${esc(a.artista)}</div>
  </a>`;
}

export function cardArtist(a) {
  return `<a class="card round" href="#/artista/${encodeURIComponent(a.nombre)}">
    <div class="art">${coverImg(a.portadaUrl, 300, a.nombre)}${playBtn(`data-act="play-artist" data-name="${esc(a.nombre)}"`)}</div>
    <div class="t ellipsis">${esc(a.nombre)}</div>
    <div class="s">Artista • ${plural(a.totalCanciones, 'canción', 'canciones')}</div>
  </a>`;
}

export function playlistArt(p, size = 300) {
  return p.portadaUrl ? `<img src="${esc(p.portadaUrl)}" alt="" loading="lazy" data-fallback>` : mosaic(p.portadas, size);
}

export function cardPlaylist(p) {
  return `<a class="card" href="#/playlist/${p.id}">
    <div class="art">${playlistArt(p)}${p.cancionesCount ? playBtn(`data-act="play-playlist" data-id="${p.id}"`) : ''}</div>
    <div class="t ellipsis">${esc(p.nombre)}</div>
    <div class="s">${p.propia === false ? `<span class="badge">${icon('globe', 'sm')} Pública</span> ${p.autor ? 'de ' + esc(p.autor) : ''}` : 'Playlist'} • ${plural(p.cancionesCount, 'canción', 'canciones')}</div>
  </a>`;
}

export function cardMix(m) {
  return `<a class="card mix-card" href="#/mix/${encodeURIComponent(m.id)}">
    <div class="art">${mosaic(m.portadas)}<div class="mix-label">${esc(m.titulo)}</div>${playBtn(`data-act="play-mix" data-id="${esc(m.id)}"`)}</div>
    <div class="s">${esc(m.artistas?.join(', ') || m.subtitulo)}</div>
  </a>`;
}

// Paleta propia de géneros: tonos profundos que se funden con el negro de la marca
const GENRE_COLORS = ['#6c5ce7', '#3a7bd5', '#c2547a', '#d07a4a', '#3c9d9b', '#8e5bd1', '#b8863b', '#4d6fd8', '#a34fa0', '#5c8a6a', '#c96a5b', '#4b9ec9', '#7d6bb8', '#b0596f', '#5e7fa8', '#9a7a52'];
export function genreColor(name) {
  let h = 0;
  for (const c of norm(name)) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return GENRE_COLORS[h % GENRE_COLORS.length];
}
export function cardGenre(g) {
  return `<a class="genre" href="#/genero/${encodeURIComponent(g.nombre)}" style="--g:${genreColor(g.nombre)}">
    <div class="n">${esc(g.nombre)}</div><div class="c">${plural(g.totalCanciones, 'canción', 'canciones')}</div>
    ${g.portadaUrl ? `<img src="${esc(cover(g.portadaUrl, 160))}" alt="" loading="lazy" data-fallback>` : ''}
  </a>`;
}

export function section(title, content, { more, sub, id, eyebrow } = {}) {
  if (!content) return '';
  return `<section class="section" ${id ? `id="${id}"` : ''}>
    <div class="section-head"><div>${eyebrow ? `<div class="eyebrow">${esc(eyebrow)}</div>` : ''}<h2>${esc(title)}</h2>${sub ? `<div class="sub">${esc(sub)}</div>` : ''}</div>
    ${more ? `<a class="more" href="${more}">Mostrar todo</a>` : ''}</div>
    ${content}</section>`;
}

export function emptyState(ico, title, text = '', btn = '') {
  return `<div class="empty">${icon(ico)}<h3>${esc(title)}</h3>${text ? `<p>${esc(text)}</p>` : ''}${btn}</div>`;
}

// ═══════════════════ Lista de canciones ═══════════════════

/**
 * Lista de canciones estilo tabla.
 * opts: { context, showAlbum=true, showCover=true, numbers:'index'|'track', playlist (id si se pueden quitar/ordenar) }
 */
export function trackList(songs, opts = {}) {
  const { context = null, showAlbum = true, showCover = true, numbers = 'index', playlist = null, header = true } = opts;
  const id = registerList(songs, context);
  const cur = player.current?.id;
  const rows = songs.map((s, i) => {
    const n = numbers === 'track' && s.numeroPista ? s.numeroPista : i + 1;
    const fav = lib.isFav(s.id);
    return `<div class="track ${cur === s.id ? 'playing' : ''} ${player.playing ? '' : 'paused'}" data-act="play-list" data-list="${id}" data-i="${i}" data-song="${s.id}" tabindex="0" role="row">
      <div class="num">${playlist ? `<span class="drag" data-drag aria-label="Mover">${icon('drag', 'sm')}</span>` : ''}<span class="n">${n}</span><span class="play-ico">${icon('play', 'sm')}</span><span class="eq-anim"><i></i><i></i><i></i></span></div>
      <div class="main-col">${showCover ? coverImg(s.portadaUrl, 64, '') : ''}
        <div class="tt"><div class="name ellipsis">${esc(s.nombre)}${s.letraSincronizada ? '<span class="lrc-tag" title="Letra sincronizada">LRC</span>' : ''}</div>
        <div class="artist ellipsis"><a href="#/artista/${encodeURIComponent(s.artista)}" data-stop>${esc(s.artista)}</a></div></div></div>
      ${showAlbum ? `<div class="album ellipsis">${s.album && s.album !== 'Single' ? `<a href="#/album/${encodeURIComponent(s.album)}" data-stop>${esc(s.album)}</a>` : '<span class="dim">Sencillo</span>'}</div>` : ''}
      <div class="end">
        <button class="icon-btn fav-btn ${fav ? 'liked' : ''}" data-act="fav" data-id="${s.id}" aria-label="${fav ? 'Quitar de Me gusta' : 'Me gusta'}">${icon(fav ? 'heartFill' : 'heart')}</button>
        <span class="dur">${fmtTime(s.duracionSegundos)}</span>
        <button class="icon-btn" data-act="song-menu" data-list="${id}" data-i="${i}" ${playlist ? `data-pl="${playlist}"` : ''} aria-label="Más opciones">${icon('more')}</button>
      </div>
    </div>`;
  }).join('');
  return `<div class="tracks ${showAlbum ? '' : 'no-album'} ${playlist ? 'sortable' : ''}" data-list="${id}" role="table">
    ${header ? `<div class="track-head"><div style="text-align:center">#</div><div>Título</div>${showAlbum ? '<div>Álbum</div>' : ''}<div class="r">${icon('clock', 'sm')}</div></div>` : ''}
    ${rows}</div>`;
}

/** Marca la canción que suena en todas las listas visibles. */
export function refreshPlayingMarks() {
  const id = player.current?.id;
  const playing = player.playing;
  $$('.track[data-song]').forEach((r) => {
    r.classList.toggle('playing', Number(r.dataset.song) === id);
    r.classList.toggle('paused', !playing);
  });
  $$('.card[data-song]').forEach((c) => c.classList.toggle('is-playing', Number(c.dataset.song) === id));
}

export function refreshFavMarks() {
  $$('[data-act="fav"][data-id]').forEach((b) => {
    const fav = lib.isFav(b.dataset.id);
    b.classList.toggle('liked', fav);
    b.innerHTML = icon(fav ? 'heartFill' : 'heart');
    b.setAttribute('aria-label', fav ? 'Quitar de Me gusta' : 'Me gusta');
  });
}
bus.on('favs', refreshFavMarks);

// ═══════════════════ Menú de una canción ═══════════════════

export function songMenu(anchor, song, { playlistId } = {}) {
  const fav = lib.isFav(song.id);
  const head = `<div class="menu-head">${coverImg(song.portadaUrl, 64)}<div class="tt" style="min-width:0"><div class="t ellipsis">${esc(song.nombre)}</div><div class="s ellipsis">${esc(song.artista)}</div></div></div>`;
  showMenu(anchor, [
    { label: 'Reproducir a continuación', icon: 'play', fn: () => { player.playNext(song); toast('Sonará a continuación'); } },
    { label: 'Agregar a la cola', icon: 'queue', fn: () => { player.addToQueue(song); toast('Agregada a la cola'); } },
    { label: 'Iniciar radio', icon: 'radio', fn: () => player.startRadio(song).catch(errorToast) },
    { sep: true },
    { label: 'Agregar a playlist', icon: 'plusCircle', sub: true, fn: () => pickPlaylist([song]) },
    { label: fav ? 'Quitar de Me gusta' : 'Agregar a Me gusta', icon: fav ? 'heartFill' : 'heart', fn: () => lib.toggleFav(song.id).then((f) => toast(f ? 'Agregada a Me gusta' : 'Quitada de Me gusta')).catch(errorToast) },
    playlistId ? { label: 'Quitar de esta playlist', icon: 'trash', fn: () => bus.emit('playlist:remove', { playlistId, song }) } : null,
    { sep: true },
    { label: 'Ir al artista', icon: 'user', fn: () => { location.hash = '#/artista/' + encodeURIComponent(song.artista); } },
    song.album && song.album !== 'Single' ? { label: 'Ir al álbum', icon: 'disc', fn: () => { location.hash = '#/album/' + encodeURIComponent(song.album); } } : null,
    { label: 'Ver letra', icon: 'mic', fn: () => bus.emit('np:lyrics', song) },
    estaDescargada(song.id)
      ? { label: 'Quitar descarga', icon: 'trash', fn: () => { quitarDescarga(song.id); toast('Descarga quitada'); } }
      : { label: 'Descargar', icon: 'download', fn: () => {
        // En la app: queda sin conexión y también como MP3 en el teléfono. En el navegador: sin conexión + archivo.
        if (!descargar([song])) toast('Descargando…');
        if (!window.SpaceMusicApp) { const a = document.createElement('a'); a.href = downloadUrl(song.id); a.download = ''; document.body.appendChild(a); a.click(); a.remove(); }
      } },
    ...(session.canEdit ? [
      { sep: true },
      { label: 'Editar información', icon: 'edit', fn: () => editSong(song) },
      { label: 'Cambiar portada', icon: 'disc', fn: () => changeCover(song) },
      { label: 'Editar letra', icon: 'text', fn: () => editLyrics(song) },
    ] : []),
  ], head);
}

// ═══════════════════ Elegir playlist ═══════════════════

export async function pickPlaylist(songs) {
  const pls = lib.playlists;
  const body = `<div class="field"><input id="plq" placeholder="Buscar playlist" autocomplete="off"></div>
    <div class="option-list">
      <button class="option" data-new>${`<div class="ph">${icon('plus')}</div>`}<div><div class="t">Nueva playlist</div></div></button>
      ${pls.map((p) => `<button class="option" data-pl="${p.id}" data-n="${esc(norm(p.nombre))}">
        <div class="ph">${playlistArt(p, 160)}</div><div style="min-width:0"><div class="t ellipsis">${esc(p.nombre)}</div><div class="s">${plural(p.cancionesCount, 'canción', 'canciones')}</div></div></button>`).join('')}
    </div>`;
  await modal({
    title: songs.length === 1 ? 'Agregar a playlist' : `Agregar ${songs.length} canciones`,
    body, actions: [],
    onOpen: (el, close) => {
      const q = el.querySelector('#plq');
      q.addEventListener('input', () => {
        const v = norm(q.value);
        el.querySelectorAll('[data-pl]').forEach((o) => o.classList.toggle('hidden', !o.dataset.n.includes(v)));
      });
      el.addEventListener('click', async (e) => {
        const o = e.target.closest('.option');
        if (!o) return;
        if (o.dataset.new !== undefined) {
          close(null);
          const p = await editPlaylist(null, songs);
          if (p) toast(`Creada “${p.nombre}”`, { action: { label: 'Ver', fn: () => { location.hash = '#/playlist/' + p.id; } } });
          return;
        }
        try {
          const r = await api(`/api/playlists/${o.dataset.pl}/canciones`, { method: 'POST', body: { ids: songs.map((s) => s.id) } });
          lib.upsertPlaylist(r.playlist);
          close(null);
          const msg = r.agregadas === 0 ? 'Ya estaba en la playlist' : `Agregada a “${r.playlist.nombre}”`;
          toast(msg, { action: { label: 'Ver', fn: () => { location.hash = '#/playlist/' + r.playlist.id; } } });
          bus.emit('playlist:changed', r.playlist.id);
        } catch (err) { errorToast(err); }
      });
    },
  });
}

// ═══════════════════ Editor de playlist ═══════════════════

export async function editPlaylist(p, initialSongs = []) {
  const nueva = !p;
  let file = null;
  const body = `<div class="pl-editor">
    <div><div class="img-pick" id="plimg">${p ? playlistArt(p) : `<div class="cover-fallback">${icon('note')}</div>`}<div class="ov">${icon('edit')}<br>Elegir foto</div></div>
      ${p?.portadaUrl ? '<button class="btn sm ghost" id="plimgdel" style="margin-top:8px;width:100%">Quitar foto</button>' : ''}
      <input type="file" id="plfile" accept="image/*" hidden></div>
    <div>
      <div class="field"><label>Nombre</label><input id="pln" maxlength="250" value="${esc(p?.nombre || '')}" placeholder="Mi playlist"></div>
      <div class="field"><label>Descripción</label><textarea id="pld" maxlength="500" placeholder="Agrega una descripción (opcional)">${esc(p?.descripcion || '')}</textarea></div>
      <label class="row" style="justify-content:space-between"><span><strong>Pública</strong><br><span class="muted" style="font-size:12px">Otros usuarios podrán verla y escucharla</span></span>
        <span class="switch"><input type="checkbox" id="plpub" ${p?.publica ? 'checked' : ''}><span></span></span></label>
    </div></div>`;
  let quitarFoto = false;
  return modal({
    title: nueva ? 'Nueva playlist' : 'Editar playlist',
    body,
    actions: [{ label: 'Cancelar', value: null }, { label: nueva ? 'Crear' : 'Guardar', value: 'ok', primary: true }],
    onOpen: (el) => {
      const inp = el.querySelector('#plfile');
      el.querySelector('#plimg').onclick = () => inp.click();
      inp.onchange = () => {
        file = inp.files[0];
        if (!file) return;
        if (file.size > 8 * 1024 * 1024) { toast('La imagen no puede pasar de 8 MB', { error: true }); file = null; return; }
        el.querySelector('#plimg').firstElementChild.outerHTML = `<img src="${URL.createObjectURL(file)}" alt="">`;
      };
      const del = el.querySelector('#plimgdel');
      if (del) del.onclick = () => { quitarFoto = true; file = null; del.remove(); el.querySelector('#plimg').firstElementChild.outerHTML = mosaic(p.portadas); };
    },
    onAction: async (v, el) => {
      if (v !== 'ok') return null;
      const nombre = el.querySelector('#pln').value.trim();
      if (!nombre) { toast('Escribe un nombre', { error: true }); return false; }
      const descripcion = el.querySelector('#pld').value.trim();
      const publica = el.querySelector('#plpub').checked;
      let res;
      if (nueva) {
        res = await api('/api/playlists', { method: 'POST', body: { nombre, descripcion, publica, canciones: initialSongs.map((s) => s.id) } });
      } else {
        res = await api(`/api/playlists/${p.id}`, { method: 'PUT', body: { nombre, descripcion, publica } });
        if (quitarFoto) res = await api(`/api/playlists/${p.id}/imagen`, { method: 'DELETE' });
      }
      if (file) {
        const fd = new FormData();
        fd.append('file', file);
        res = await api(`/api/playlists/${res.id}/imagen`, { method: 'POST', form: fd });
      }
      lib.upsertPlaylist(res);
      bus.emit('playlist:changed', res.id);
      return res;
    },
  });
}

// ═══════════════════ Edición de canciones (colaboradores) ═══════════════════

export async function editSong(song) {
  const f = (k, label, type = 'text', extra = '') => `<div class="field"><label>${label}</label><input id="es-${k}" type="${type}" value="${esc(song[k] ?? '')}" ${extra}></div>`;
  const r = await modal({
    title: 'Editar información',
    body: `${f('nombre', 'Título', 'text', 'maxlength="250"')}${f('artista', 'Artista', 'text', 'maxlength="250"')}${f('album', 'Álbum', 'text', 'maxlength="250"')}
      <div class="row">${f('genero', 'Género', 'text', 'maxlength="100"')}${f('anio', 'Año', 'number', 'min="1000" max="2999"')}${f('numeroPista', 'Pista', 'number', 'min="1" max="999"')}</div>
      <p class="hint" style="font-size:12px">Los cambios se guardan en la base de datos; el archivo de audio no se modifica.</p>`,
    actions: [{ label: 'Cancelar', value: null }, { label: 'Guardar', value: 'ok', primary: true }],
    onAction: async (v, el) => {
      if (v !== 'ok') return null;
      const val = (k) => el.querySelector('#es-' + k).value.trim();
      return api(`/api/canciones/${song.id}`, {
        method: 'PUT',
        body: { nombre: val('nombre'), artista: val('artista'), album: val('album'), genero: val('genero'), anio: val('anio'), numeroPista: val('numeroPista') },
      });
    },
  });
  if (r) { Object.assign(song, r); toast('Información actualizada'); bus.emit('song:updated', r); }
}

export function changeCover(song) {
  const inp = document.createElement('input');
  inp.type = 'file';
  inp.accept = 'image/*';
  inp.onchange = async () => {
    const file = inp.files[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    try {
      const r = await api(`/api/canciones/${song.id}/portada`, { method: 'POST', form: fd });
      r.portadaUrl += (r.portadaUrl.includes('?') ? '&' : '?') + 'v=' + Date.now();
      Object.assign(song, r);
      toast('Portada actualizada');
      bus.emit('song:updated', r);
    } catch (e) { errorToast(e); }
  };
  inp.click();
}

export async function editLyrics(song) {
  let datos = { letra: '', letraSincronizada: '' };
  try { datos = await api(`/api/letras/${song.id}`); } catch { }
  const texto = datos.letraSincronizada || datos.letra || '';
  const r = await modal({
    title: 'Letra · ' + song.nombre,
    wide: true,
    body: `<p style="margin-top:0;font-size:13px">Pega la letra en texto o en formato LRC (<code>[01:23.45] línea</code>) para que se sincronice.</p>
      <div class="field"><textarea id="lt" rows="14" style="font-family:ui-monospace,Consolas,monospace;font-size:13px">${esc(texto)}</textarea></div>
      <div class="row wrap"><button class="btn sm" id="ltfile">${icon('upload', 'sm')} Cargar archivo .lrc</button>
      <button class="btn sm" id="ltweb">${icon('globe', 'sm')} Buscar en internet</button><input type="file" id="ltin" accept=".lrc,.txt" hidden></div>`,
    actions: [{ label: 'Borrar letra', value: 'del', danger: true }, { label: 'Cancelar', value: null }, { label: 'Guardar', value: 'ok', primary: true }],
    onOpen: (el) => {
      const ta = el.querySelector('#lt'), inp = el.querySelector('#ltin');
      el.querySelector('#ltfile').onclick = () => inp.click();
      inp.onchange = async () => { if (inp.files[0]) ta.value = await inp.files[0].text(); };
      el.querySelector('#ltweb').onclick = async (e) => {
        const b = e.currentTarget;
        b.disabled = true;
        try {
          const res = await api(`/api/letras/${song.id}/buscar?forzar=true`, { method: 'POST' });
          if (res.encontrada) { ta.value = res.letraSincronizada || res.letra; toast('Letra encontrada y guardada'); bus.emit('lyrics:changed', song.id); }
          else toast(res.message || 'No se encontró', { error: true });
        } catch (err) { errorToast(err); } finally { b.disabled = false; }
      };
    },
    onAction: async (v, el) => {
      if (v === 'del') {
        if (!(await confirmDialog('Se borrará la letra de esta canción.', { ok: 'Borrar', danger: true }))) return false;
        await api(`/api/letras/${song.id}`, { method: 'DELETE' });
        return 'del';
      }
      if (v !== 'ok') return null;
      return api(`/api/letras/${song.id}`, { method: 'PUT', body: { texto: el.querySelector('#lt').value } });
    },
  });
  if (r) { toast(r === 'del' ? 'Letra borrada' : 'Letra guardada'); bus.emit('lyrics:changed', song.id); }
}

// ═══════════════════ Reordenar arrastrando ═══════════════════

/**
 * Permite reordenar elementos arrastrando su asa (ratón y táctil).
 * onMove(desde, hasta) se llama al soltar.
 */
export function sortable(container, itemSel, handleSel, onMove) {
  let dragEl = null, from = -1, over = null, after = false;
  container.addEventListener('pointerdown', (e) => {
    const h = e.target.closest(handleSel);
    if (!h || !container.contains(h)) return;
    dragEl = h.closest(itemSel);
    if (!dragEl) return;
    e.preventDefault();
    e.stopPropagation();
    const items = $$(itemSel, container);
    from = items.indexOf(dragEl);
    dragEl.classList.add('dragging');
    h.setPointerCapture?.(e.pointerId);
    const move = (ev) => {
      const el = document.elementFromPoint(ev.clientX, ev.clientY)?.closest(itemSel);
      over?.classList.remove('drop-before', 'drop-after');
      if (!el || el === dragEl || !container.contains(el)) { over = null; return; }
      const r = el.getBoundingClientRect();
      after = ev.clientY > r.top + r.height / 2;
      over = el;
      el.classList.add(after ? 'drop-after' : 'drop-before');
      const scroller = container.closest('.view, .drawer-body, .np-tab');
      if (scroller) {
        const sr = scroller.getBoundingClientRect();
        if (ev.clientY < sr.top + 60) scroller.scrollTop -= 12;
        if (ev.clientY > sr.bottom - 60) scroller.scrollTop += 12;
      }
    };
    const up = () => {
      document.removeEventListener('pointermove', move);
      document.removeEventListener('pointerup', up);
      document.removeEventListener('pointercancel', up);
      dragEl.classList.remove('dragging');
      if (over) {
        over.classList.remove('drop-before', 'drop-after');
        const items2 = $$(itemSel, container);
        let to = items2.indexOf(over);
        if (after) to++;
        if (to > from) to--;
        if (to !== from) onMove(from, to);
      }
      container._suppressClick = true;
      setTimeout(() => { container._suppressClick = false; }, 50);
      dragEl = null; over = null;
    };
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', up);
    document.addEventListener('pointercancel', up);
  });
}

// ═══════════════════ Imágenes que no cargan ═══════════════════

document.addEventListener('error', (e) => {
  const img = e.target;
  if (img?.tagName === 'IMG' && img.hasAttribute('data-fallback') && !img.dataset.failed) {
    img.dataset.failed = '1';
    const div = document.createElement('div');
    div.className = 'cover-fallback ' + (img.className || '');
    div.innerHTML = icon('note');
    img.replaceWith(div);
  }
}, true);

export { fmtNum };
