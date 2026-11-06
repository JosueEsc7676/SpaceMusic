// Búsqueda
import { api , deUsuario } from '../api.js';
import { cardPersona } from './people.js';
import { esc, store, coverImg, plural } from '../util.js';
import { icon } from '../icons.js';
import { setHero, setTitle } from '../router.js';
import { trackList, cardAlbum, cardArtist, cardPlaylist, cardGenre, section, emptyState, registerList } from '../ui.js';

const RECENT_BASE = 'sm_busquedas';
const RECENT_KEY = () => deUsuario(RECENT_BASE); // cada cuenta ve sus propias búsquedas

export function addRecentSearch(q) {
  q = q.trim();
  if (q.length < 2) return;
  const r = store.get(RECENT_KEY(), []).filter((x) => x.toLowerCase() !== q.toLowerCase());
  r.unshift(q);
  store.set(RECENT_KEY(), r.slice(0, 10));
}

let generosCache = null;

export async function searchView(el, _p, query, isStale) {
  const q = (query.q || '').trim();
  const tab = query.tab || 'todo';
  setHero([40, 40, 52]);
  setTitle(q ? `“${q}”` : 'Buscar');

  if (!q) {
    if (!generosCache) generosCache = await api('/api/generos').catch(() => []);
    if (isStale()) return;
    const recientes = store.get(RECENT_KEY(), []);
    el.innerHTML = `
      ${recientes.length ? section('Búsquedas recientes', `<div class="recent-searches">${recientes.map((r) =>
        `<a class="chip" href="#/buscar?q=${encodeURIComponent(r)}">${icon('history', 'sm')} ${esc(r)}</a>`).join('')}
        <button class="chip" data-clear-recent>${icon('close', 'sm')} Borrar</button></div>`) : ''}
      ${generosCache.length ? section('Explorar todo', `<div class="genre-grid">${generosCache.map(cardGenre).join('')}</div>`) :
        emptyState('search', 'Busca canciones, artistas y álbumes', 'Escribe arriba lo que quieras escuchar. No importan los acentos ni pequeños errores.')}
    `;
    el.querySelector('[data-clear-recent]')?.addEventListener('click', () => { store.del(RECENT_KEY()); el.querySelector('.recent-searches').closest('.section').remove(); });
    return;
  }

  const r = await api('/api/buscar?q=' + encodeURIComponent(q));
  if (isStale()) return;
  addRecentSearch(q);

  const total = r.canciones.length + r.artistas.length + r.albumes.length + r.playlists.length + (r.usuarios?.length || 0);
  const tabs = [['todo', 'Todo'], ['canciones', 'Canciones'], ['artistas', 'Artistas'], ['albumes', 'Álbumes'], ['playlists', 'Playlists'], ['personas', 'Personas']];
  const chips = `<div class="chips">${tabs.map(([k, l]) =>
    `<a class="chip ${tab === k ? 'active' : ''}" href="#/buscar?q=${encodeURIComponent(q)}${k === 'todo' ? '' : '&tab=' + k}">${l}</a>`).join('')}</div>`;

  if (!total) {
    el.innerHTML = chips + emptyState('search', `Sin resultados para “${q}”`, 'Revisa la ortografía o prueba con menos palabras.');
    return;
  }

  const ctx = { type: 'busqueda', label: `Búsqueda “${q}”`, route: `#/buscar?q=${encodeURIComponent(q)}` };
  let html = chips;

  if (tab === 'todo') {
    // Resultado principal: el artista si coincide de lleno, si no la primera canción
    const nq = q.toLowerCase();
    const art = r.artistas[0];
    const topIsArtist = art && (art.nombre.toLowerCase().startsWith(nq) || !r.canciones.length);
    let top = '';
    if (topIsArtist) {
      top = `<a class="top-result round" href="#/artista/${encodeURIComponent(art.nombre)}">${coverImg(art.portadaUrl, 300)}
        <div class="t ellipsis">${esc(art.nombre)}</div><div class="s"><span class="badge">Artista</span></div>
        <button class="play-fab" data-act="play-artist" data-name="${esc(art.nombre)}" aria-label="Reproducir">${icon('play')}</button></a>`;
    } else if (r.canciones[0]) {
      const s = r.canciones[0];
      const lid = registerList([s], ctx);
      top = `<div class="top-result" data-act="play-list" data-list="${lid}" data-i="0">${coverImg(s.portadaUrl, 300)}
        <div class="t ellipsis">${esc(s.nombre)}</div><div class="s"><span class="badge">Canción</span>${esc(s.artista)}</div>
        <button class="play-fab" data-act="play-list" data-list="${lid}" data-i="0" aria-label="Reproducir">${icon('play')}</button></div>`;
    }
    html += `<div class="search-top"><div><div class="section-head"><h2>Resultado principal</h2></div>${top}</div>
      <div style="min-width:0"><div class="section-head"><h2>Canciones</h2>${r.canciones.length > 5 ? `<a class="more" href="#/buscar?q=${encodeURIComponent(q)}&tab=canciones">Mostrar todo</a>` : ''}</div>
      ${trackList(r.canciones.slice(0, 5), { context: ctx, showAlbum: false, header: false })}</div></div>`;
    html += section('Artistas', r.artistas.length ? `<div class="grid">${r.artistas.slice(0, 6).map(cardArtist).join('')}</div>` : '');
    html += section('Álbumes', r.albumes.length ? `<div class="grid">${r.albumes.slice(0, 6).map(cardAlbum).join('')}</div>` : '');
    html += section('Playlists', r.playlists.length ? `<div class="grid">${r.playlists.slice(0, 6).map(cardPlaylist).join('')}</div>` : '');
    html += section('Personas', r.usuarios?.length ? `<div class="grid people">${r.usuarios.slice(0, 6).map(cardPersona).join('')}</div>` : '');
    html += section('Géneros', r.generos?.length ? `<div class="genre-grid">${r.generos.map(cardGenre).join('')}</div>` : '');
  } else if (tab === 'canciones') {
    html += r.canciones.length ? trackList(r.canciones, { context: ctx }) : emptyState('note', 'No hay canciones');
  } else if (tab === 'artistas') {
    html += r.artistas.length ? `<div class="grid">${r.artistas.map(cardArtist).join('')}</div>` : emptyState('user', 'No hay artistas');
  } else if (tab === 'albumes') {
    html += r.albumes.length ? `<div class="grid">${r.albumes.map(cardAlbum).join('')}</div>` : emptyState('disc', 'No hay álbumes');
  } else if (tab === 'playlists') {
    html += r.playlists.length ? `<div class="grid">${r.playlists.map(cardPlaylist).join('')}</div>` : emptyState('list', 'No hay playlists');
  } else if (tab === 'personas') {
    html += r.usuarios?.length ? `<div class="grid people">${r.usuarios.map(cardPersona).join('')}</div>` : emptyState('users', 'No encontramos a nadie con ese nombre');
  }
  el.innerHTML = html;
  void plural;
}
