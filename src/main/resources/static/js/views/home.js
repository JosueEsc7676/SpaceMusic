// Pantalla de inicio
import { api, session } from '../api.js';
import { esc, cover, coverImg, mosaic } from '../util.js';
import { icon } from '../icons.js';
import { lib } from '../state.js';
import { setHero } from '../router.js';
import { cardSong, cardAlbum, cardArtist, cardPlaylist, cardMix, cardGenre, section, registerList, emptyState, playlistArt } from '../ui.js';

function saludo() {
  const h = new Date().getHours();
  return h < 6 ? 'Buenas noches' : h < 12 ? 'Buenos días' : h < 19 ? 'Buenas tardes' : 'Buenas noches';
}

const shelf = (html) => (html ? `<div class="shelf">${html}</div>` : '');

function songShelf(songs, ctx) {
  if (!songs?.length) return '';
  const id = registerList(songs, ctx);
  return shelf(songs.map((s, i) => cardSong(s, id, i)).join(''));
}

export async function homeView(el, _p, _q, isStale) {
  const d = await api('/api/inicio');
  if (isStale()) return;
  setHero([40, 70, 60]);

  if (!d.totalCanciones) {
    el.innerHTML = `<h1 class="greeting">${saludo()}</h1>` + emptyState('folder', 'Tu biblioteca está vacía',
      session.isAdmin
        ? 'Copia música en la carpeta configurada en el servidor (music.path) y se agregará sola en unos minutos, o súbela desde aquí.'
        : 'Todavía no hay canciones en el servidor. Pídele al administrador que agregue música.',
      session.canEdit ? `<a class="btn primary" href="#/subir">${icon('upload', 'sm')} Subir canciones</a>` : '');
    return;
  }

  // Accesos rápidos: Me gusta + playlists + mezclas
  const quick = [];
  if (lib.favs.size) quick.push(`<a class="quick" href="#/favoritos"><div class="thumb" style="background:linear-gradient(135deg,#4a1fb8,#8fa7d8);display:grid;place-items:center">${icon('heartFill', 'lg')}</div><div class="t ellipsis">Me gusta</div><button class="play-fab" data-act="play-liked" aria-label="Reproducir">${icon('play')}</button></a>`);
  for (const p of lib.playlists.slice(0, 5)) {
    quick.push(`<a class="quick" href="#/playlist/${p.id}"><div class="thumb">${playlistArt(p, 160)}</div><div class="t ellipsis">${esc(p.nombre)}</div>${p.cancionesCount ? `<button class="play-fab" data-act="play-playlist" data-id="${p.id}" aria-label="Reproducir">${icon('play')}</button>` : ''}</a>`);
  }
  for (const m of (d.mixes || []).slice(0, Math.max(0, 6 - quick.length))) {
    quick.push(`<a class="quick" href="#/mix/${encodeURIComponent(m.id)}"><div class="thumb">${mosaic(m.portadas, 160)}</div><div class="t ellipsis">${esc(m.titulo)}</div><button class="play-fab" data-act="play-mix" data-id="${esc(m.id)}" aria-label="Reproducir">${icon('play')}</button></a>`);
  }

  el.innerHTML = `
    <h1 class="greeting">${saludo()}${session.user?.nombreDisplay ? ', ' + esc(session.user.nombreDisplay.split(' ')[0]) : ''}</h1>
    ${quick.length ? `<div class="quick-grid">${quick.join('')}</div>` : ''}
    ${section('Hecho para ti', shelf((d.mixes || []).map(cardMix).join('')), { sub: 'Mezclas que se actualizan con lo que escuchas' })}
    ${section('Escuchado recientemente', songShelf(d.recientes, { type: 'historial', label: 'Escuchado recientemente' }), { more: '#/historial' })}
    ${section('Lo que más escuchas', songShelf(d.tusFavoritas, { type: 'top', label: 'Lo que más escuchas' }), { sub: 'Últimos 30 días', more: '#/estadisticas' })}
    ${section('Tendencias de la semana', songShelf(d.tendencias, { type: 'tendencias', label: 'Tendencias' }), { sub: 'Lo más escuchado por todos' })}
    ${section('Recién agregadas', songShelf(d.recienAgregadas, { type: 'nuevas', label: 'Recién agregadas' }))}
    ${section('Álbumes', shelf((d.albumesRecientes || []).map(cardAlbum).join('')), { more: '#/biblioteca?tab=albumes' })}
    ${section('Artistas', shelf((d.artistas || []).map(cardArtist).join('')), { more: '#/biblioteca?tab=artistas' })}
    ${section('Playlists de la comunidad', shelf((d.playlistsPublicas || []).map(cardPlaylist).join('')))}
    ${d.generos?.length ? section('Explorar géneros', `<div class="genre-grid">${d.generos.slice(0, 8).map(cardGenre).join('')}</div>`, { more: '#/buscar' }) : ''}
    ${!d.recientes?.length && d.top?.length ? section('Más reproducidas', songShelf(d.top, { type: 'top', label: 'Más reproducidas' })) : ''}
  `;
}
