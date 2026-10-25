// Tu biblioteca: playlists, artistas y álbumes
import { api } from '../api.js';
import { esc, norm, store, plural } from '../util.js';
import { icon } from '../icons.js';
import { lib } from '../state.js';
import { setHero, setTitle } from '../router.js';
import { cardPlaylist, cardArtist, cardAlbum, emptyState, editPlaylist } from '../ui.js';

let cache = { artistas: null, albumes: null, publicas: null, t: 0 };

export async function libraryView(el, _p, query, isStale) {
  const tab = query.tab || store.get('sm_lib_tab', 'playlists');
  store.set('sm_lib_tab', tab);
  setTitle('Tu biblioteca');
  setHero([45, 45, 60]);

  if (Date.now() - cache.t > 60000) {
    const [artistas, albumes, publicas] = await Promise.all([
      api('/api/artistas'), api('/api/albumes'), api('/api/playlists/publicas').catch(() => []),
    ]);
    cache = { artistas, albumes, publicas, t: Date.now() };
  }
  if (isStale()) return;

  const sort = store.get('sm_lib_sort', 'reciente');
  const tabs = [['playlists', 'Playlists'], ['artistas', 'Artistas'], ['albumes', 'Álbumes'], ['comunidad', 'Comunidad']];
  el.innerHTML = `
    <div class="section-head" style="margin-top:8px"><h2 style="font-size:28px">Tu biblioteca</h2>
      <button class="btn sm" data-new>${icon('plus', 'sm')} Nueva playlist</button></div>
    <div class="row wrap" style="margin-bottom:20px;justify-content:space-between">
      <div class="chips" style="margin:0">${tabs.map(([k, l]) => `<a class="chip ${tab === k ? 'active' : ''}" href="#/biblioteca?tab=${k}">${l}</a>`).join('')}</div>
      <div class="row"><label class="list-filter">${icon('search')}<input type="search" placeholder="Filtrar" data-q></label>
      <select class="input" data-sort style="width:auto;padding:7px 10px">
        <option value="reciente" ${sort === 'reciente' ? 'selected' : ''}>Recientes</option>
        <option value="alfa" ${sort === 'alfa' ? 'selected' : ''}>Alfabético</option>
        <option value="popular" ${sort === 'popular' ? 'selected' : ''}>Más escuchados</option>
      </select></div>
    </div>
    <div data-list></div>`;

  const box = el.querySelector('[data-list]');
  const draw = () => {
    const q = norm(el.querySelector('[data-q]').value);
    const s = el.querySelector('[data-sort]').value;
    store.set('sm_lib_sort', s);
    const by = (arr, name, pop, date) => {
      arr = arr.filter((x) => !q || norm(name(x)).includes(q));
      if (s === 'alfa') return [...arr].sort((a, b) => name(a).localeCompare(name(b), 'es'));
      if (s === 'popular' && pop) return [...arr].sort((a, b) => pop(b) - pop(a));
      return date ? [...arr].sort((a, b) => String(date(b)).localeCompare(String(date(a)))) : arr;
    };
    let html = '';
    if (tab === 'playlists') {
      const fav = `<a class="card" href="#/favoritos"><div class="art" style="background:linear-gradient(135deg,#4a1fb8,#8fa7d8);display:grid;place-items:center">${icon('heartFill', 'xl')}</div><div class="t">Me gusta</div><div class="s">${plural(lib.favs.size, 'canción', 'canciones')}</div></a>`;
      const hist = `<a class="card" href="#/historial"><div class="art" style="background:linear-gradient(135deg,#1e5a4a,#4fb39b);display:grid;place-items:center">${icon('history', 'xl')}</div><div class="t">Escuchado recientemente</div><div class="s">Historial</div></a>`;
      const pls = by(lib.playlists, (p) => p.nombre, (p) => p.cancionesCount, (p) => p.fechaActualizacion || p.fechaCreacion);
      html = `<div class="grid">${q ? '' : fav + hist}${pls.map(cardPlaylist).join('')}</div>`;
      if (!pls.length && q) html = emptyState('search', 'Ninguna playlist coincide');
    } else if (tab === 'artistas') {
      const a = by(cache.artistas, (x) => x.nombre, (x) => x.totalReproducciones);
      html = a.length ? `<div class="grid">${a.map(cardArtist).join('')}</div>` : emptyState('user', 'No hay artistas');
    } else if (tab === 'albumes') {
      const a = by(cache.albumes, (x) => x.nombre + ' ' + x.artista, (x) => x.reproducciones, (x) => x.anio || 0);
      html = a.length ? `<div class="grid">${a.map(cardAlbum).join('')}</div>` : emptyState('disc', 'No hay álbumes', 'Las canciones con el álbum “Single” aparecen en su artista.');
    } else {
      const a = by(cache.publicas, (x) => x.nombre, (x) => x.cancionesCount, (x) => x.fechaActualizacion);
      html = a.length ? `<div class="grid">${a.map(cardPlaylist).join('')}</div>` : emptyState('globe', 'Aún no hay playlists públicas', 'Cuando otros usuarios hagan públicas sus playlists aparecerán aquí. También puedes compartir las tuyas.');
    }
    box.innerHTML = html;
  };
  el.querySelector('[data-q]').addEventListener('input', draw);
  el.querySelector('[data-sort]').addEventListener('change', draw);
  el.querySelector('[data-new]').addEventListener('click', async () => {
    const p = await editPlaylist(null);
    if (p) location.hash = '#/playlist/' + p.id;
  });
  draw();
  void esc;
}

export function invalidateLibraryCache() { cache.t = 0; }
