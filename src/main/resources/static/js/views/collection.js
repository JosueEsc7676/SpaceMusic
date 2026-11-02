// Páginas de álbum, artista, playlist, género, mezcla, Me gusta e historial
import { api, session } from '../api.js';
import { esc, $, cover, coverImg, mosaic, dominantColor, fmtLong, plural, norm, bus, copyText } from '../util.js';
import { icon } from '../icons.js';
import { lib } from '../state.js';
import { player } from '../player.js';
import { setHero, setTitle, go } from '../router.js';
import {
  trackList, cardAlbum, cardArtist, section, emptyState, toast, errorToast, showMenu,
  pickPlaylist, editPlaylist, confirmDialog, sortable, playlistArt, genreColor,
} from '../ui.js';

// ── Piezas comunes ──

function head({ kind, title, art, round = false, meta = '', desc = '', editable = false }) {
  return `<div class="page-head">
    <div class="cover ${round ? 'round' : ''}">${art}${editable ? `<button class="edit-cover" data-edit-cover>${icon('edit', 'lg')}<br>Editar</button>` : ''}</div>
    <div class="info"><div class="kind">${esc(kind)}</div>
      <h1 class="${title.length > 28 ? 'long' : ''}">${esc(title)}</h1>
      ${desc ? `<div class="desc">${esc(desc)}</div>` : ''}
      <div class="meta">${meta}</div></div></div>`;
}

const sep = '<span class="sep"></span>';

function actions({ playId = 'play', extra = '', filter = false, liked = null }) {
  return `<div class="actions-bar">
    <button class="play-fab" data-${playId} aria-label="Reproducir">${icon('play')}</button>
    <button class="icon-btn" data-shuffle-play aria-label="Reproducir en aleatorio" title="Aleatorio">${icon('shuffle')}</button>
    ${liked !== null ? `<button class="icon-btn ${liked ? 'liked' : ''}" data-like-all aria-label="Me gusta" title="${liked ? 'Quitar todas de Me gusta' : 'Agregar todas a Me gusta'}">${icon(liked ? 'heartFill' : 'heart')}</button>` : ''}
    <button class="icon-btn" data-more aria-label="Más opciones" title="Más opciones">${icon('more')}</button>
    ${extra}
    <div class="spacer"></div>
    ${filter ? `<label class="list-filter">${icon('search')}<input type="search" placeholder="Buscar en la lista" data-filter></label>` : ''}
  </div>`;
}

/** Conecta los botones de reproducir/aleatorio/menú de una página de colección. */
function wire(el, getSongs, context, menuItems = () => []) {
  const playAll = (shuffle) => {
    const songs = getSongs();
    if (!songs.length) return toast('No hay canciones');
    player.play(songs, shuffle ? -1 : 0, context, { shuffle });
  };
  el.querySelector('[data-play]')?.addEventListener('click', () => {
    // Si ya suena esta colección, pausar/continuar
    if (player.context?.route === context.route && player.current) return player.toggle();
    playAll(player.shuffle);
  });
  el.querySelector('[data-shuffle-play]')?.addEventListener('click', () => playAll(true));
  el.querySelector('[data-more]')?.addEventListener('click', (e) => {
    const songs = getSongs();
    showMenu(e.currentTarget, [
      { label: 'Agregar a la cola', icon: 'queue', fn: () => { player.addToQueue(songs); toast(`${plural(songs.length, 'canción agregada', 'canciones agregadas')} a la cola`); } },
      { label: 'Agregar a playlist', icon: 'plusCircle', fn: () => pickPlaylist(songs) },
      ...menuItems(),
    ].filter(Boolean));
  });
  const likeAll = el.querySelector('[data-like-all]');
  likeAll?.addEventListener('click', async () => {
    const songs = getSongs();
    const all = songs.every((s) => lib.isFav(s.id));
    try {
      for (const s of songs) if (lib.isFav(s.id) === all) await lib.toggleFav(s.id);
      toast(all ? 'Quitadas de Me gusta' : 'Agregadas a Me gusta');
      likeAll.classList.toggle('liked', !all);
      likeAll.innerHTML = icon(!all ? 'heartFill' : 'heart');
    } catch (e) { errorToast(e); }
  });
  const f = el.querySelector('[data-filter]');
  f?.addEventListener('input', () => {
    const v = norm(f.value);
    el.querySelectorAll('.track[data-song]').forEach((row) => {
      row.classList.toggle('hidden', v && !norm(row.textContent).includes(v));
    });
  });
  // Pintar el botón de reproducir según el estado
  const sync = () => {
    const b = el.querySelector('[data-play]');
    if (!b) return off();
    if (!b.isConnected) { if (b._seen) off(); return; }
    b._seen = true;
    const mine = player.context?.route === context.route && player.current;
    b.innerHTML = icon(mine && player.playing ? 'pause' : 'play');
  };
  const off1 = bus.on('player:state', sync), off2 = bus.on('player:song', sync), off3 = bus.on('rendered', sync);
  const off = () => { off1(); off2(); off3(); };
  sync();
}

async function heroColor(url) {
  setHero(await dominantColor(url));
}

// ═══════════════════ Álbum ═══════════════════

export async function albumView(el, { nombre }, _q, isStale) {
  const a = await api('/api/albumes/detalle?nombre=' + encodeURIComponent(nombre));
  if (isStale()) return;
  setTitle(a.nombre);
  heroColor(a.portadaUrl);
  const route = '#/album/' + encodeURIComponent(a.nombre);
  const ctx = { type: 'album', label: a.nombre, route };
  const masDelArtista = a.artista !== 'Varios artistas'
    ? await api('/api/artistas/' + encodeURIComponent(a.artista)).catch(() => null) : null;
  if (isStale()) return;
  const otros = (masDelArtista?.albumes || []).filter((x) => norm(x.nombre) !== norm(a.nombre));
  const artistLink = a.artista === 'Varios artistas' ? '<strong>Varios artistas</strong>'
    : `${masDelArtista?.portadaUrl ? `<img class="mini" src="${esc(cover(masDelArtista.portadaUrl, 64))}" alt="">` : ''}<a href="#/artista/${encodeURIComponent(a.artista)}"><strong>${esc(a.artista)}</strong></a>`;

  el.innerHTML = head({
    kind: 'Álbum', title: a.nombre, art: coverImg(a.portadaUrl, 640, a.nombre),
    meta: `${artistLink}${a.anio ? sep + a.anio : ''}${sep}${plural(a.totalCanciones, 'canción', 'canciones')}, <span class="muted">${fmtLong(a.duracionTotal)}</span>`,
  })
    + actions({ liked: a.canciones.every((s) => lib.isFav(s.id)) })
    + trackList(a.canciones, { context: ctx, showAlbum: false, showCover: a.artista === 'Varios artistas', numbers: 'track' })
    + (otros.length ? `<div style="height:36px"></div>` + section('Más de ' + a.artista, `<div class="grid">${otros.slice(0, 6).map(cardAlbum).join('')}</div>`, { more: '#/artista/' + encodeURIComponent(a.artista) }) : '');
  wire(el, () => a.canciones, ctx, () => [
    a.artista !== 'Varios artistas' ? { label: 'Ir al artista', icon: 'user', fn: () => go('/artista/' + encodeURIComponent(a.artista)) } : null,
    { label: 'Crear playlist con este álbum', icon: 'plus', fn: () => editPlaylist(null, a.canciones).then((p) => p && go('/playlist/' + p.id)) },
  ]);
}

// ═══════════════════ Artista ═══════════════════

export async function artistView(el, { nombre }, _q, isStale) {
  const a = await api('/api/artistas/' + encodeURIComponent(nombre));
  if (isStale()) return;
  setTitle(a.nombre);
  heroColor(a.portadaUrl);
  const route = '#/artista/' + encodeURIComponent(a.nombre);
  const ctx = { type: 'artista', label: a.nombre, route };
  const todas = [...a.populares, ...a.canciones.filter((c) => !a.populares.some((p) => p.id === c.id))];

  el.innerHTML = `<div class="artist-hero"><div class="bg" style="background-image:url('${esc(cover(a.portadaUrl, 640))}')"></div>
    ${head({
      kind: 'Artista', title: a.nombre, art: coverImg(a.portadaUrl, 640, a.nombre), round: true,
      meta: `${plural(a.totalCanciones, 'canción', 'canciones')}${sep}${plural(a.totalAlbumes, 'álbum', 'álbumes')}${sep}${fmtLong(a.duracionTotal)}${a.totalReproducciones ? sep + plural(a.totalReproducciones, 'reproducción', 'reproducciones') : ''}`,
    })}</div>
    ${actions({ extra: `<button class="btn outline sm" data-mix>${icon('sparkles', 'sm')} Mix del artista</button><button class="btn outline sm" data-radio>${icon('radio', 'sm')} Radio</button>` })}
    ${section('Populares', `<div id="pop">${trackList(a.populares.slice(0, 5), { context: ctx, header: false })}</div>
      ${a.populares.length > 5 ? '<button class="btn ghost sm" data-more-pop style="margin-top:8px">Ver más</button>' : ''}`)}
    ${section('Discografía', a.albumes.length ? `<div class="grid">${a.albumes.map(cardAlbum).join('')}</div>` : '')}
    ${section('Sencillos y otras canciones', a.sencillos.length ? trackList(a.sencillos, { context: ctx, header: false, showAlbum: false }) : '')}
    ${section('Artistas parecidos', a.relacionados.length ? `<div class="grid">${a.relacionados.map(cardArtist).join('')}</div>` : '')}`;

  wire(el, () => todas, ctx);
  el.querySelector('[data-more-pop]')?.addEventListener('click', (e) => {
    el.querySelector('#pop').innerHTML = trackList(a.populares, { context: ctx, header: false });
    e.currentTarget.remove();
  });
  el.querySelector('[data-mix]').onclick = async () => {
    try { player.play(await api('/api/mixes/' + encodeURIComponent('artista:' + a.nombre)), 0, { type: 'mix', label: 'Mix de ' + a.nombre, route: '#/mix/' + encodeURIComponent('artista:' + a.nombre) }); }
    catch (e) { errorToast(e); }
  };
  el.querySelector('[data-radio]').onclick = () => player.startRadio(a.populares[0] || a.canciones[0]).catch(errorToast);
}

// ═══════════════════ Playlist ═══════════════════

export async function playlistView(el, { id }, _q, isStale) {
  let p = await api('/api/playlists/' + id);
  if (isStale()) return;
  setTitle(p.nombre);
  const route = '#/playlist/' + p.id;
  const ctx = { type: 'playlist', label: p.nombre, route };
  let canciones = p.canciones;

  const draw = () => {
    heroColor(p.portadaUrl || p.portadas?.[0]);
    const autor = p.propia ? (session.user?.nombreDisplay || 'Tú') : (p.autor || 'Otro usuario');
    el.innerHTML = head({
      kind: p.publica ? 'Playlist pública' : 'Playlist', title: p.nombre, art: playlistArt(p, 640), desc: p.descripcion,
      editable: p.propia,
      meta: `<strong>${esc(autor)}</strong>${sep}${plural(canciones.length, 'canción', 'canciones')}${canciones.length ? `, <span class="muted">${fmtLong(p.duracionTotal)}</span>` : ''}`,
    })
      + actions({ filter: canciones.length > 8, extra: p.propia ? '' : `<button class="btn outline sm" data-save>${icon('plus', 'sm')} Guardar copia</button>` })
      + (canciones.length
        ? `<div id="pltracks">${trackList(canciones, { context: ctx, playlist: p.propia ? p.id : null })}</div>`
        : emptyState('note', 'Esta playlist está vacía', 'Busca canciones y usa “Agregar a playlist” en su menú.', `<a class="btn primary" href="#/buscar">Buscar canciones</a>`))
      + (p.propia ? '<div id="recs"></div>' : '');

    wire(el, () => canciones, ctx, () => p.propia ? [
      { sep: true },
      { label: 'Editar detalles', icon: 'edit', fn: edit },
      { label: p.publica ? 'Hacer privada' : 'Hacer pública', icon: p.publica ? 'lock' : 'globe', fn: togglePublic },
      { label: 'Duplicar', icon: 'copy', fn: duplicar },
      { label: 'Copiar enlace', icon: 'link', fn: () => copyText(location.origin + '/' + route).then(() => toast('Enlace copiado')) },
      { sep: true },
      { label: 'Eliminar playlist', icon: 'trash', danger: true, fn: borrar },
    ] : [
      { label: 'Guardar una copia en mi biblioteca', icon: 'copy', fn: duplicar },
    ]);
    el.querySelector('[data-edit-cover]')?.addEventListener('click', edit);
    el.querySelector('[data-save]')?.addEventListener('click', duplicar);

    const tl = el.querySelector('#pltracks .tracks');
    if (tl && p.propia) {
      sortable(tl, '.track', '[data-drag]', async (from, to) => {
        const nuevo = [...canciones];
        const [m] = nuevo.splice(from, 1);
        nuevo.splice(to, 0, m);
        canciones = nuevo;
        draw();
        try { p = await api(`/api/playlists/${p.id}/orden`, { method: 'PUT', body: { ids: nuevo.map((s) => s.id) } }); canciones = p.canciones; }
        catch (e) { errorToast(e); }
      });
    }
    if (p.propia) loadRecs();
  };

  const loadRecs = async () => {
    const box = el.querySelector('#recs');
    if (!box) return;
    const semilla = canciones[canciones.length - 1] || null;
    if (!semilla) return;
    try {
      const recs = await api(`/api/radio/${semilla.id}?cantidad=8&excluir=${canciones.map((c) => c.id).join(',')}`);
      if (!recs.length || !box.isConnected) return;
      box.innerHTML = `<div style="height:40px"></div>` + section('Recomendadas', `<div class="rec-list">${trackList(recs, { context: { type: 'recomendadas', label: 'Recomendadas' }, header: false })}</div>`, { sub: 'Según lo que hay en esta playlist' });
      box.querySelectorAll('.track').forEach((row, i) => {
        const end = row.querySelector('.end');
        const b = document.createElement('button');
        b.className = 'btn outline sm';
        b.textContent = 'Agregar';
        b.onclick = async (ev) => {
          ev.stopPropagation();
          try {
            const r = await api(`/api/playlists/${p.id}/canciones`, { method: 'POST', body: { ids: [recs[i].id] } });
            p = { ...r.playlist, canciones: [...canciones, recs[i]] };
            canciones = p.canciones;
            lib.upsertPlaylist(r.playlist);
            toast('Agregada a la playlist');
            draw();
          } catch (err) { errorToast(err); }
        };
        end.prepend(b);
      });
    } catch { }
  };

  const edit = async () => {
    const r = await editPlaylist(p);
    if (r) { p = { ...p, ...r, canciones }; setTitle(p.nombre); draw(); }
  };
  const togglePublic = async () => {
    try {
      const r = await api(`/api/playlists/${p.id}`, { method: 'PUT', body: { publica: !p.publica } });
      p = { ...p, ...r, canciones };
      lib.upsertPlaylist(r);
      toast(r.publica ? 'Ahora otros usuarios pueden verla' : 'Ahora es privada');
      draw();
    } catch (e) { errorToast(e); }
  };
  const duplicar = async () => {
    try {
      const r = await api(`/api/playlists/${p.id}/duplicar`, { method: 'POST' });
      lib.upsertPlaylist(r);
      toast('Copia guardada en tu biblioteca', { action: { label: 'Ver', fn: () => go('/playlist/' + r.id) } });
    } catch (e) { errorToast(e); }
  };
  const borrar = async () => {
    if (!(await confirmDialog(`Se eliminará “${p.nombre}”. Las canciones no se borran.`, { title: 'Eliminar playlist', ok: 'Eliminar', danger: true }))) return;
    try {
      await api('/api/playlists/' + p.id, { method: 'DELETE' });
      lib.removePlaylist(p.id);
      toast('Playlist eliminada');
      go('/biblioteca');
    } catch (e) { errorToast(e); }
  };

  // Quitar canción desde el menú contextual
  const offRemove = bus.on('playlist:remove', async ({ playlistId, song }) => {
    if (playlistId !== p.id || !el.isConnected) return;
    try {
      const r = await api(`/api/playlists/${p.id}/canciones/${song.id}`, { method: 'DELETE' });
      const antes = canciones;
      canciones = canciones.filter((c) => c.id !== song.id);
      p = { ...p, ...r, canciones };
      lib.upsertPlaylist(r);
      draw();
      toast('Quitada de la playlist', {
        action: { label: 'Deshacer', fn: async () => {
          try {
            await api(`/api/playlists/${p.id}/canciones`, { method: 'POST', body: { ids: [song.id] } });
            const r2 = await api(`/api/playlists/${p.id}/orden`, { method: 'PUT', body: { ids: antes.map((c) => c.id) } });
            p = r2; canciones = r2.canciones; lib.upsertPlaylist(r2);
            if (el.isConnected) draw();
          } catch (err) { errorToast(err); }
        } },
      });
    } catch (e) { errorToast(e); }
  });
  const offChanged = bus.on('playlist:changed', async (pid) => {
    if (pid !== p.id || !el.isConnected) return;
    p = await api('/api/playlists/' + p.id);
    canciones = p.canciones;
    draw();
  });
  const offRoute = bus.on('route', () => { offRemove(); offChanged(); offRoute(); });

  draw();
}

// ═══════════════════ Me gusta ═══════════════════

export async function likedView(el, _p, _q, isStale) {
  let songs = await api('/api/usuario/favoritos');
  if (isStale()) return;
  setTitle('Me gusta');
  setHero([80, 56, 200]);
  const ctx = { type: 'favoritos', label: 'Me gusta', route: '#/favoritos' };
  const draw = () => {
    el.innerHTML = head({
      kind: 'Playlist', title: 'Me gusta',
      art: `<div class="art-liked" style="width:100%;height:100%">${icon('heartFill', 'xl')}</div>`,
      meta: `<strong>${esc(session.user?.nombreDisplay || '')}</strong>${sep}${plural(songs.length, 'canción', 'canciones')}`,
    })
      + actions({ filter: songs.length > 8 })
      + (songs.length ? trackList(songs, { context: ctx })
        : emptyState('heart', 'Las canciones que te gusten aparecerán aquí', 'Toca el corazón en cualquier canción para guardarla.', '<a class="btn primary" href="#/buscar">Buscar canciones</a>'));
    wire(el, () => songs, ctx);
  };
  draw();
  // Quitar filas al desmarcar sin recargar toda la página
  const off = bus.on('favs', async (ev) => {
    if (!el.isConnected) return off();
    if (ev && !ev.fav) {
      songs = songs.filter((s) => s.id !== ev.id);
      el.querySelector(`.track[data-song="${ev.id}"]`)?.remove();
    } else if (ev?.fav) {
      songs = await api('/api/usuario/favoritos');
      if (el.isConnected) draw();
    }
  });
}

// ═══════════════════ Género ═══════════════════

export async function genreView(el, { nombre }, _q, isStale) {
  const g = await api('/api/generos/' + encodeURIComponent(nombre));
  if (isStale()) return;
  setTitle(g.nombre);
  const hex = genreColor(g.nombre).slice(1);
  setHero([0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16)));
  const route = '#/genero/' + encodeURIComponent(g.nombre);
  const ctx = { type: 'genero', label: g.nombre, route };
  const artistas = [...new Map(g.canciones.map((c) => [norm(c.artista), c])).values()].slice(0, 12);
  el.innerHTML = head({
    kind: 'Género', title: g.nombre, art: mosaic(g.canciones.map((c) => c.portadaUrl), 640),
    meta: plural(g.canciones.length, 'canción', 'canciones'),
  })
    + actions({ filter: g.canciones.length > 8, extra: `<button class="btn outline sm" data-mix>${icon('sparkles', 'sm')} Mix de ${esc(g.nombre)}</button>` })
    + section('Artistas', `<div class="shelf">${artistas.map((c) => cardArtist({ nombre: c.artista, portadaUrl: c.portadaUrl, totalCanciones: g.canciones.filter((x) => norm(x.artista) === norm(c.artista)).length })).join('')}</div>`)
    + section('Canciones', trackList(g.canciones, { context: ctx }));
  wire(el, () => g.canciones, ctx);
  el.querySelector('[data-mix]').onclick = async () => {
    try { player.play(await api('/api/mixes/' + encodeURIComponent('genero:' + g.nombre)), 0, { type: 'mix', label: 'Mix de ' + g.nombre }); }
    catch (e) { errorToast(e); }
  };
}

// ═══════════════════ Mezcla ═══════════════════

const MIX_INFO = {
  diario: ['Mix del día', 'Una selección nueva cada día según lo que escuchas.', [255, 170, 60]],
  descubrir: ['Descubrir', 'Canciones de tu biblioteca que todavía no has escuchado.', [60, 140, 255]],
  aleatorio: ['Aleatorio', 'Canciones al azar de toda la biblioteca.', [150, 90, 220]],
};

export async function mixView(el, { id }, _q, isStale) {
  let songs = await api(`/api/mixes/${encodeURIComponent(id)}?cantidad=40`);
  if (isStale()) return;
  const [titulo, desc, color] = MIX_INFO[id] || [
    id.startsWith('artista:') ? 'Mix de ' + id.slice(8) : id.startsWith('genero:') ? 'Mix de ' + id.slice(7) : 'Mezcla',
    id.startsWith('artista:') ? 'Canciones del artista y de otros parecidos.' : 'Lo mejor del género en tu biblioteca.', null];
  setTitle(titulo);
  const route = '#/mix/' + encodeURIComponent(id);
  const ctx = { type: 'mix', label: titulo, route };
  if (color) setHero(color); else heroColor(songs[0]?.portadaUrl);
  const draw = () => {
    el.innerHTML = head({
      kind: 'Mezcla', title: titulo, desc, art: mosaic(songs.map((s) => s.portadaUrl), 640),
      meta: `SpaceMusic${sep}${plural(songs.length, 'canción', 'canciones')}`,
    })
      + actions({ extra: `<button class="btn outline sm" data-regen>${icon('refresh', 'sm')} Otra mezcla</button><button class="btn outline sm" data-savemix>${icon('plus', 'sm')} Guardar como playlist</button>` })
      + (songs.length ? trackList(songs, { context: ctx }) : emptyState('sparkles', 'Aún no hay suficiente música para esta mezcla'));
    wire(el, () => songs, ctx);
    el.querySelector('[data-regen]').onclick = async () => {
      songs = await api(`/api/mixes/${encodeURIComponent(id.startsWith('genero:') || id.startsWith('artista:') ? id : 'aleatorio')}?cantidad=40`).catch(() => songs);
      draw();
    };
    el.querySelector('[data-savemix]').onclick = async () => {
      const p = await editPlaylist(null, songs);
      if (p) toast('Playlist creada', { action: { label: 'Ver', fn: () => go('/playlist/' + p.id) } });
    };
  };
  draw();
}

// ═══════════════════ Historial ═══════════════════

export async function historyView(el, _p, _q, isStale) {
  const songs = await api('/api/usuario/recientes?limite=100');
  if (isStale()) return;
  setTitle('Escuchado recientemente');
  setHero([60, 80, 110]);
  const ctx = { type: 'historial', label: 'Escuchado recientemente', route: '#/historial' };
  el.innerHTML = `<div class="page-head" style="padding-top:12px"><div class="info"><div class="kind">Historial</div><h1 class="long">Escuchado recientemente</h1>
    <div class="meta">${plural(songs.length, 'canción', 'canciones')}</div></div></div>`
    + (songs.length ? actions({}) + trackList(songs, { context: ctx })
      : emptyState('history', 'Aún no has escuchado nada', 'Las canciones aparecen aquí cuando las escuchas al menos 30 segundos.'));
  if (songs.length) wire(el, () => songs, ctx);
  void $;
}
