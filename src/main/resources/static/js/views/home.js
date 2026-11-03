// Pantalla de inicio (identidad "Noir")
import { api, session } from '../api.js';
import { esc, cover, coverImg, mosaic, fmtTime } from '../util.js';
import { icon } from '../icons.js';
import { lib } from '../state.js';
import { setHero } from '../router.js';
import { cardSong, cardAlbum, cardArtist, cardPlaylist, cardMix, cardGenre, section, registerList, emptyState, playlistArt } from '../ui.js';

function saludo() {
  const h = new Date().getHours();
  return h < 6 ? 'Buenas noches' : h < 12 ? 'Buenos días' : h < 19 ? 'Buenas tardes' : 'Buenas noches';
}

function fechaHoy() {
  const t = new Date().toLocaleDateString('es', { weekday: 'long', day: 'numeric', month: 'long' });
  return t.charAt(0).toUpperCase() + t.slice(1);
}

const shelf = (html) => (html ? `<div class="shelf">${html}</div>` : '');

function songShelf(songs, ctx) {
  if (!songs?.length) return '';
  const id = registerList(songs, ctx);
  return shelf(songs.map((s, i) => cardSong(s, id, i)).join(''));
}

/** Gran tarjeta destacada: la primera mezcla del día. */
function spotlight(m) {
  if (!m) return '';
  const fondo = m.portadas?.[0] ? cover(m.portadas[0], 300) : '';
  return `<section class="spotlight">
    ${fondo ? `<div class="spot-bg" style="background-image:url('${esc(fondo)}')"></div>` : ''}
    <div class="spot-body">
      <div class="eyebrow">${icon('sparkles', 'sm')} Hecho para ti</div>
      <h2 class="spot-title">${esc(m.titulo)}</h2>
      <p class="spot-sub ellipsis2">${esc(m.artistas?.join(', ') || m.subtitulo || '')}</p>
      <div class="spot-actions">
        <button class="btn primary lg" data-act="play-mix" data-id="${esc(m.id)}">${icon('play', 'sm')} Reproducir</button>
        <a class="btn glass lg" href="#/mix/${encodeURIComponent(m.id)}">Ver mezcla</a>
      </div>
    </div>
    <a class="spot-art" href="#/mix/${encodeURIComponent(m.id)}" aria-label="${esc(m.titulo)}">${mosaic(m.portadas, 300)}</a>
  </section>`;
}

/** Lista numerada de dos columnas (tendencias). */
function ranking(songs, ctx) {
  if (!songs?.length) return '';
  const id = registerList(songs, ctx);
  return `<ol class="ranking">${songs.slice(0, 8).map((s, i) => `<li class="rank-row" data-act="play-list" data-list="${id}" data-i="${i}" data-song="${s.id}">
      <span class="rank-n">${i + 1}</span>
      <span class="rank-art">${coverImg(s.portadaUrl, 64, s.nombre)}</span>
      <span class="rank-tt"><b class="ellipsis">${esc(s.nombre)}</b><small class="ellipsis">${esc(s.artista)}</small></span>
      <span class="rank-d">${fmtTime(s.duracionSegundos)}</span>
    </li>`).join('')}</ol>`;
}

export async function homeView(el, _p, _q, isStale) {
  const d = await api('/api/inicio');
  if (isStale()) return;
  setHero(null);

  const nombre = session.user?.nombreDisplay ? ', ' + esc(session.user.nombreDisplay.split(' ')[0]) : '';
  const head = `<header class="home-head"><div class="eyebrow">${esc(fechaHoy())}</div><h1 class="greeting">${saludo()}${nombre}</h1></header>`;

  if (!d.totalCanciones) {
    el.innerHTML = head + emptyState('folder', 'Tu biblioteca está vacía',
      session.isAdmin
        ? 'Copia música en la carpeta configurada en el servidor (music.path) y se agregará sola en unos minutos, o súbela desde aquí.'
        : 'Todavía no hay canciones en el servidor. Pídele al administrador que agregue música.',
      session.canEdit ? `<a class="btn primary" href="#/subir">${icon('upload', 'sm')} Subir canciones</a>` : '');
    return;
  }

  // Accesos directos: Me gusta, tus playlists y mezclas
  const pins = [];
  if (lib.favs.size) pins.push(`<a class="pin" href="#/favoritos"><span class="pin-art art-liked">${icon('heartFill')}</span><span class="pin-t ellipsis">Me gusta</span><button class="pin-play" data-act="play-liked" aria-label="Reproducir">${icon('play', 'sm')}</button></a>`);
  for (const p of lib.playlists.slice(0, 5)) {
    pins.push(`<a class="pin" href="#/playlist/${p.id}"><span class="pin-art">${playlistArt(p, 160)}</span><span class="pin-t ellipsis">${esc(p.nombre)}</span>${p.cancionesCount ? `<button class="pin-play" data-act="play-playlist" data-id="${p.id}" aria-label="Reproducir">${icon('play', 'sm')}</button>` : ''}</a>`);
  }
  const mixes = d.mixes || [];
  for (const m of mixes.slice(1, 1 + Math.max(0, 8 - pins.length))) {
    pins.push(`<a class="pin" href="#/mix/${encodeURIComponent(m.id)}"><span class="pin-art">${mosaic(m.portadas, 160)}</span><span class="pin-t ellipsis">${esc(m.titulo)}</span><button class="pin-play" data-act="play-mix" data-id="${esc(m.id)}" aria-label="Reproducir">${icon('play', 'sm')}</button></a>`);
  }

  el.innerHTML = `
    ${head}
    <div class="home-top">
      ${spotlight(mixes[0])}
      ${pins.length ? `<div class="pins">${pins.join('')}</div>` : ''}
    </div>
    ${section('Continúa escuchando', songShelf(d.recientes, { type: 'historial', label: 'Escuchado recientemente' }), { more: '#/historial', eyebrow: 'Tu historial' })}
    ${section('Tus mezclas', shelf(mixes.map(cardMix).join('')), { eyebrow: 'Se actualizan con lo que escuchas' })}
    ${d.tendencias?.length ? section('Tendencias de la semana', ranking(d.tendencias, { type: 'tendencias', label: 'Tendencias' }), { eyebrow: 'Lo más escuchado por todos' }) : ''}
    ${section('Lo que más escuchas', songShelf(d.tusFavoritas, { type: 'top', label: 'Lo que más escuchas' }), { eyebrow: 'Últimos 30 días', more: '#/estadisticas' })}
    ${section('Recién llegadas', songShelf(d.recienAgregadas, { type: 'nuevas', label: 'Recién agregadas' }), { eyebrow: 'Nuevo en el servidor' })}
    ${section('Álbumes', shelf((d.albumesRecientes || []).map(cardAlbum).join('')))}
    ${section('Artistas', shelf((d.artistas || []).map(cardArtist).join('')))}
    ${section('De la comunidad', shelf((d.playlistsPublicas || []).map(cardPlaylist).join('')), { eyebrow: 'Playlists públicas' })}
    ${d.generos?.length ? section('Explora por género', `<div class="genre-grid">${d.generos.slice(0, 8).map(cardGenre).join('')}</div>`, { more: '#/buscar' }) : ''}
    ${!d.recientes?.length && d.top?.length ? section('Más reproducidas', songShelf(d.top, { type: 'top', label: 'Más reproducidas' })) : ''}
  `;
}
