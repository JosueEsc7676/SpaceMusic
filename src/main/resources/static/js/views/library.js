// Biblioteca: playlists, artistas, álbumes y comunidad (identidad "Noir")
import { api } from '../api.js';
import { esc, norm, store, plural, coverImg, albumHref , textoSinRed, detalleSinRed } from '../util.js';
import { icon } from '../icons.js';
import { lib } from '../state.js';
import { setHero, setTitle } from '../router.js';
import { cardPlaylist, cardArtist, cardAlbum, emptyState, editPlaylist, playlistArt, trackList } from '../ui.js';
import { colecciones, estaColeccion, sinConexion } from '../offline.js';

let cache = { artistas: null, albumes: null, publicas: null, t: 0 };

const TABS = [['playlists', 'Playlists', 'library'], ['albumes', 'Álbumes', 'disc']];

/** Fila de la vista en lista. */
function fila({ href, art, t, s, round = false }) {
  return `<a class="lib-row" href="${href}"><span class="lib-art ${round ? 'round' : ''}">${art}</span>
    <span class="lib-tt"><b class="ellipsis">${esc(t)}</b><small class="ellipsis">${s}</small></span>${icon('right', 'sm')}</a>`;
}

export async function libraryView(el, _p, query, isStale) {
  let tab = query.tab || store.get('sm_lib_tab', 'playlists');
  if (!TABS.some(([k]) => k === tab)) tab = 'playlists'; // pestañas antiguas (Artistas, Comunidad)
  store.set('sm_lib_tab', tab);
  setTitle('Biblioteca');
  setHero(null);

  if (Date.now() - cache.t > 60000) {
    const [albumes, publicas] = await Promise.all([
      // Sin conexión se muestran listas vacías (las descargas siguen disponibles)
      api('/api/albumes').catch(() => []), api('/api/playlists/publicas').catch(() => []),
    ]);
    cache = { artistas: [], albumes, publicas, t: Date.now() };
  }
  if (isStale()) return;

  const sort = store.get('sm_lib_sort', 'reciente');
  const vista = store.get('sm_lib_view', 'grid');
  // Solo lo que el usuario guardó: sus playlists, las públicas que guardó y sus álbumes
  const guardadas = cache.publicas.filter((x) => lib.estaGuardado('playlist', x.id)).map((x) => ({ ...x, propia: false }));
  let albumesGuardados = cache.albumes.filter((x) => lib.estaGuardado('album', x.clave || x.nombre));
  let misPlaylists = [...lib.playlists, ...guardadas];
  // Sin conexión (como Spotify): solo lo descargado
  const offline = sinConexion();
  if (offline) {
    const cols = colecciones();
    misPlaylists = cols.filter((c) => c.tipo === 'playlist').map((c) => ({ ...c.datos, cancionesCount: (c.canciones || []).length }));
    albumesGuardados = cols.filter((c) => c.tipo === 'album').map((c) => ({ ...c.datos, totalCanciones: (c.canciones || []).length }));
  }
  const resumen = [plural(misPlaylists.length, 'playlist', 'playlists'), plural(albumesGuardados.length, 'álbum', 'álbumes')].join(' · ');

  el.innerHTML = `
    <header class="lib-head">
      <div><div class="eyebrow">Tu colección</div><h1 class="page-title">Biblioteca</h1><p class="muted">${resumen}</p></div>
      ${offline ? '' : `<button class="btn primary" data-new>${icon('plus', 'sm')} Nueva playlist</button>`}
    </header>
    ${offline ? `<div class="offline-banner">${icon('download', 'sm')} ${textoSinRed()} · aquí está lo que descargaste</div>` : ''}
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
      const verMeGusta = !offline || estaColeccion('favoritos');
      const fijos = q ? '' : `<div class="lib-pinned">
        ${verMeGusta ? `<a class="feature-tile art-liked" href="#/favoritos">${icon('heartFill', 'xl')}<div><b>Me gusta</b><small>${offline ? 'Descargada' : plural(lib.favs.size, 'canción', 'canciones')}</small></div>
          ${lib.favs.size && !offline ? `<button class="play-fab" data-act="play-liked" aria-label="Reproducir">${icon('play')}</button>` : ''}</a>` : ''}
        ${offline ? '' : `<a class="feature-tile art-history" href="#/historial">${icon('history', 'xl')}<div><b>Escuchado recientemente</b><small>Tu historial</small></div></a>`}
      </div>`;
      // En vista de lista, Me gusta y el historial también se muestran como filas
      const fijosLista = q ? '' : `<div class="lib-list">
        ${verMeGusta ? fila({ href: '#/favoritos', art: `<span class="art-liked lib-fijo">${icon('heartFill')}</span>`, t: 'Me gusta', s: offline ? 'Descargada' : 'Playlist · ' + plural(lib.favs.size, 'canción', 'canciones') }) : ''}
        ${offline ? '' : fila({ href: '#/historial', art: `<span class="art-history lib-fijo">${icon('history')}</span>`, t: 'Escuchado recientemente', s: 'Tu historial' })}
      </div>`;
      const pls = by(misPlaylists, (p) => p.nombre, (p) => p.cancionesCount, (p) => p.fechaActualizacion || p.fechaCreacion);
      html = (v === 'list' ? fijosLista : fijos) + pinta(pls, cardPlaylist,
        (p) => fila({ href: '#/playlist/' + p.id, art: playlistArt(p, 160), t: p.nombre, s: (p.propia === false ? `Pública${p.autor ? ' · de ' + esc(p.autor) : ''}` : 'Playlist') + ' · ' + plural(p.cancionesCount, 'canción', 'canciones') }),
        q ? emptyState('search', 'Ninguna playlist coincide') : offline ? emptyState('download', 'No hay playlists descargadas', 'Con conexión, abre una playlist y toca el botón de descarga.') : emptyState('library', 'Crea tu primera playlist', 'Guarda tus canciones en listas. Las playlists públicas que encuentres en el buscador también se pueden guardar aquí.'));
    } else if (tab === 'artistas') {
      const a = by(cache.artistas, (x) => x.nombre, (x) => x.totalReproducciones);
      html = pinta(a, cardArtist,
        (x) => fila({ href: '#/artista/' + encodeURIComponent(x.nombre), art: coverImg(x.portadaUrl, 160, x.nombre), t: x.nombre, s: 'Artista · ' + plural(x.totalCanciones, 'canción', 'canciones'), round: true }),
        emptyState('user', q ? 'Ningún artista coincide' : 'No hay artistas'));
    } else if (tab === 'albumes') {
      const a = by(albumesGuardados, (x) => x.nombre + ' ' + x.artista, (x) => x.reproducciones, (x) => x.anio || 0);
      html = pinta(a, cardAlbum,
        (x) => fila({ href: albumHref(x.nombre, x.artista), art: coverImg(x.portadaUrl, 160, x.nombre), t: x.nombre, s: (x.anio ? x.anio + ' · ' : '') + esc(x.artista) }),
        offline && !q ? emptyState('download', 'No hay álbumes descargados', 'Con conexión, abre un álbum y toca el botón de descarga.') : emptyState('disc', q ? 'Ningún álbum coincide' : 'Aún no guardas álbumes', 'Abre un álbum y toca “Guardar en biblioteca” para tenerlo aquí.'));
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
  el.querySelector('[data-new]')?.addEventListener('click', async () => {
    const p = await editPlaylist(null);
    if (p) location.hash = '#/playlist/' + p.id;
  });
  draw();
}

export function invalidateLibraryCache() { cache.t = 0; }
