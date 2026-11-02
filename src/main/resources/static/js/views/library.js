// Biblioteca: playlists, artistas, álbumes y comunidad (identidad "Noir")
import { api } from '../api.js';
import { esc, norm, store, plural, coverImg } from '../util.js';
import { icon } from '../icons.js';
import { lib } from '../state.js';
import { setHero, setTitle } from '../router.js';
import { cardPlaylist, cardArtist, cardAlbum, emptyState, editPlaylist, playlistArt, trackList } from '../ui.js';
import { descargadas } from '../offline.js';

let cache = { artistas: null, albumes: null, publicas: null, t: 0 };

const TABS = [['playlists', 'Playlists', 'library'], ['descargadas', 'Descargadas', 'download'], ['artistas', 'Artistas', 'user'], ['albumes', 'Álbumes', 'disc'], ['comunidad', 'Comunidad', 'globe']];

/** Fila de la vista en lista. */
function fila({ href, art, t, s, round = false }) {
  return `<a class="lib-row" href="${href}"><span class="lib-art ${round ? 'round' : ''}">${art}</span>
    <span class="lib-tt"><b class="ellipsis">${esc(t)}</b><small class="ellipsis">${s}</small></span>${icon('right', 'sm')}</a>`;
}

export async function libraryView(el, _p, query, isStale) {
  const tab = query.tab || store.get('sm_lib_tab', 'playlists');
  store.set('sm_lib_tab', tab);
  setTitle('Biblioteca');
  setHero(null);

  if (Date.now() - cache.t > 60000) {
    const [artistas, albumes, publicas] = await Promise.all([
      // Sin conexión se muestran listas vacías (las descargas siguen disponibles)
      api('/api/artistas').catch(() => []), api('/api/albumes').catch(() => []), api('/api/playlists/publicas').catch(() => []),
    ]);
    cache = { artistas, albumes, publicas, t: Date.now() };
  }
  if (isStale()) return;

  const sort = store.get('sm_lib_sort', 'reciente');
  const vista = store.get('sm_lib_view', 'grid');
  const resumen = [plural(lib.playlists.length, 'playlist', 'playlists'), plural(cache.artistas.length, 'artista', 'artistas'), plural(cache.albumes.length, 'álbum', 'álbumes')].join(' · ');

  el.innerHTML = `
    <header class="lib-head">
      <div><div class="eyebrow">Tu colección</div><h1 class="page-title">Biblioteca</h1><p class="muted">${resumen}</p></div>
      <button class="btn primary" data-new>${icon('plus', 'sm')} Nueva playlist</button>
    </header>
    <nav class="segmented" aria-label="Secciones">${TABS.map(([k, l, ic]) => `<a class="seg ${tab === k ? 'active' : ''}" href="#/biblioteca?tab=${k}">${icon(ic, 'sm')}<span>${l}</span></a>`).join('')}</nav>
    <div class="lib-tools">
      <label class="list-filter">${icon('search')}<input type="search" placeholder="Buscar en tu biblioteca" data-q></label>
      <select class="input select-pill" data-sort aria-label="Ordenar">
        <option value="reciente" ${sort === 'reciente' ? 'selected' : ''}>Recientes</option>
        <option value="alfa" ${sort === 'alfa' ? 'selected' : ''}>A–Z</option>
        <option value="popular" ${sort === 'popular' ? 'selected' : ''}>Más escuchados</option>
      </select>
      <div class="view-toggle" role="group" aria-label="Vista">
        <button class="icon-btn ${vista === 'grid' ? 'on' : ''}" data-view="grid" aria-label="Cuadrícula">${icon('grid', 'sm')}</button>
        <button class="icon-btn ${vista === 'list' ? 'on' : ''}" data-view="list" aria-label="Lista">${icon('list', 'sm')}</button>
      </div>
    </div>
    <div data-list></div>`;

  const box = el.querySelector('[data-list]');
  const draw = () => {
    const q = norm(el.querySelector('[data-q]').value);
    const s = el.querySelector('[data-sort]').value;
    const v = store.get('sm_lib_view', 'grid');
    store.set('sm_lib_sort', s);
    const by = (arr, name, pop, date) => {
      arr = arr.filter((x) => !q || norm(name(x)).includes(q));
      if (s === 'alfa') return [...arr].sort((a, b) => name(a).localeCompare(name(b), 'es'));
      if (s === 'popular' && pop) return [...arr].sort((a, b) => pop(b) - pop(a));
      return date ? [...arr].sort((a, b) => String(date(b)).localeCompare(String(date(a)))) : arr;
    };
    const pinta = (items, card, row, vacio) => {
      if (!items.length) return vacio;
      return v === 'list' ? `<div class="lib-list">${items.map(row).join('')}</div>` : `<div class="grid">${items.map(card).join('')}</div>`;
    };
    let html = '';
    if (tab === 'playlists') {
      const fijos = q ? '' : `<div class="lib-pinned">
        <a class="feature-tile art-liked" href="#/favoritos">${icon('heartFill', 'xl')}<div><b>Me gusta</b><small>${plural(lib.favs.size, 'canción', 'canciones')}</small></div>
          ${lib.favs.size ? `<button class="play-fab" data-act="play-liked" aria-label="Reproducir">${icon('play')}</button>` : ''}</a>
        <a class="feature-tile art-history" href="#/historial">${icon('history', 'xl')}<div><b>Escuchado recientemente</b><small>Tu historial</small></div></a>
      </div>`;
      const pls = by(lib.playlists, (p) => p.nombre, (p) => p.cancionesCount, (p) => p.fechaActualizacion || p.fechaCreacion);
      html = fijos + pinta(pls, cardPlaylist,
        (p) => fila({ href: '#/playlist/' + p.id, art: playlistArt(p, 160), t: p.nombre, s: 'Playlist · ' + plural(p.cancionesCount, 'canción', 'canciones') }),
        q ? emptyState('search', 'Ninguna playlist coincide') : emptyState('library', 'Crea tu primera playlist', 'Guarda tus canciones favoritas en listas para escucharlas cuando quieras.'));
    } else if (tab === 'descargadas') {
      const d = descargadas().filter((x) => !q || norm(x.nombre + ' ' + x.artista).includes(q));
      html = d.length
        ? `<p class="muted" style="margin-top:0">${plural(d.length, 'canción', 'canciones')} en este dispositivo · se escuchan sin conexión${window.SpaceMusicApp ? ' · los MP3 están en Música/SpaceMusic' : ''}</p>` + trackList(d, { context: { type: 'descargadas', label: 'Descargadas', route: '#/biblioteca?tab=descargadas' } })
        : emptyState('download', q ? 'Ninguna descarga coincide' : 'Aún no descargas canciones', 'Usa “Descargar” en el menú de una canción o el botón de descarga de un álbum o playlist.');
    } else if (tab === 'artistas') {
      const a = by(cache.artistas, (x) => x.nombre, (x) => x.totalReproducciones);
      html = pinta(a, cardArtist,
        (x) => fila({ href: '#/artista/' + encodeURIComponent(x.nombre), art: coverImg(x.portadaUrl, 160, x.nombre), t: x.nombre, s: 'Artista · ' + plural(x.totalCanciones, 'canción', 'canciones'), round: true }),
        emptyState('user', q ? 'Ningún artista coincide' : 'No hay artistas'));
    } else if (tab === 'albumes') {
      const a = by(cache.albumes, (x) => x.nombre + ' ' + x.artista, (x) => x.reproducciones, (x) => x.anio || 0);
      html = pinta(a, cardAlbum,
        (x) => fila({ href: '#/album/' + encodeURIComponent(x.nombre), art: coverImg(x.portadaUrl, 160, x.nombre), t: x.nombre, s: (x.anio ? x.anio + ' · ' : '') + esc(x.artista) }),
        emptyState('disc', q ? 'Ningún álbum coincide' : 'No hay álbumes', 'Las canciones con el álbum “Single” aparecen en su artista.'));
    } else {
      const a = by(cache.publicas, (x) => x.nombre, (x) => x.cancionesCount, (x) => x.fechaActualizacion);
      html = pinta(a, cardPlaylist,
        (p) => fila({ href: '#/playlist/' + p.id, art: playlistArt(p, 160), t: p.nombre, s: (p.autor ? 'De ' + esc(p.autor) + ' · ' : '') + plural(p.cancionesCount, 'canción', 'canciones') }),
        emptyState('globe', 'Aún no hay playlists públicas', 'Cuando otros usuarios hagan públicas sus playlists aparecerán aquí. También puedes compartir las tuyas.'));
    }
    box.innerHTML = html;
  };
  el.querySelector('[data-q]').addEventListener('input', draw);
  el.querySelector('[data-sort]').addEventListener('change', draw);
  el.querySelectorAll('[data-view]').forEach((b) => b.addEventListener('click', () => {
    store.set('sm_lib_view', b.dataset.view);
    el.querySelectorAll('[data-view]').forEach((x) => x.classList.toggle('on', x === b));
    draw();
  }));
  el.querySelector('[data-new]').addEventListener('click', async () => {
    const p = await editPlaylist(null);
    if (p) location.hash = '#/playlist/' + p.id;
  });
  draw();
}

export function invalidateLibraryCache() { cache.t = 0; }
