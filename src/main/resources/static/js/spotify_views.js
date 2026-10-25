/* =====================================================
   spotify_views.js
   Agregar con <script src="/js/spotify_views.js"></script>
   DESPUÉS de general.js, uiux.js y lyrics_apple.js
   ===================================================== */

// =====================================================
// ESTADO DE LIBRERÍA
// =====================================================
const LibraryState = {
    currentLibraryFilter: 'all',   // all | playlists | albums | artists
    currentLibrarySort:   'recent', // recent | name | creator
    searchQuery: ''
};

// =====================================================
// 1. MENÚ CONTEXTUAL COMPLETO — estilo Spotify
// =====================================================
window.showContextMenu = function(event, song, extraContext) {
    event.stopPropagation();
    closeContextMenu();

    const isPlaying  = window.state?.currentSong?.id === song.id;
    const inPlaylist = extraContext?.playlistId || null;
    const inQueue    = window.state?.queue?.some(s => s.id === song.id);

    const menu = document.createElement('div');
    menu.className = 'ctx-menu';

    // Posición inicial
    menu.style.left = event.clientX + 'px';
    menu.style.top  = event.clientY + 'px';

    menu.innerHTML = `
        <!-- Preview de la canción -->
        <div class="ctx-song-preview">
            <img src="${song.portadaUrl}" class="ctx-thumb"
                 onerror="this.src='https://singlecolorimage.com/get/1DB954/48x48'">
            <div class="ctx-song-info">
                <span class="ctx-song-name">${escapeHtml(song.nombre)}</span>
                <span class="ctx-song-artist">${escapeHtml(song.artista)}</span>
            </div>
        </div>
        <div class="ctx-divider"></div>

        <!-- Favorito -->
        <button class="ctx-item ${song.favorito ? 'ctx-active' : ''}"
                onclick="ContextActions.toggleFavorito(${song.id}, this)">
            <i class="fas fa-heart"></i>
            <span>${song.favorito ? 'Quitar de Me gusta' : 'Agregar a Me gusta'}</span>
        </button>

        <!-- Agregar/quitar de cola -->
        <button class="ctx-item" onclick="ContextActions.toggleQueue(${JSON.stringify(song).replace(/"/g,'&quot;')})">
            <i class="fas fa-${inQueue ? 'minus' : 'plus'}-circle"></i>
            <span>${inQueue ? 'Quitar de la cola' : 'Agregar a la cola'}</span>
        </button>

        <!-- Reproducir siguiente -->
        <button class="ctx-item" onclick="ContextActions.playNext(${JSON.stringify(song).replace(/"/g,'&quot;')})">
            <i class="fas fa-arrow-right"></i>
            <span>Reproducir a continuación</span>
        </button>

        <div class="ctx-divider"></div>

        <!-- Agregar a playlist (submenu) -->
        <button class="ctx-item ctx-has-sub" onclick="ContextActions.openAddToPlaylist(${song.id}, this)">
            <i class="fas fa-plus"></i>
            <span>Agregar a playlist</span>
            <i class="fas fa-chevron-right ctx-arrow"></i>
        </button>

        ${inPlaylist ? `
        <!-- Quitar de playlist actual -->
        <button class="ctx-item ctx-danger"
                onclick="ContextActions.removeFromPlaylist(${song.id}, ${inPlaylist})">
            <i class="fas fa-trash-alt"></i>
            <span>Quitar de esta playlist</span>
        </button>` : ''}

        <div class="ctx-divider"></div>

        <!-- Ver artista -->
        <button class="ctx-item" onclick="ContextActions.goToArtist('${escapeHtml(song.artista)}')">
            <i class="fas fa-microphone-alt"></i>
            <span>Ver artista: ${escapeHtml(song.artista)}</span>
        </button>

        <!-- Ver álbum -->
        <button class="ctx-item" onclick="ContextActions.goToAlbum('${escapeHtml(song.album)}')">
            <i class="fas fa-compact-disc"></i>
            <span>Ver álbum: ${escapeHtml(song.album || 'Single')}</span>
        </button>

        <!-- Ver letra -->
        <button class="ctx-item" onclick="ContextActions.openLyrics(${song.id})">
            <i class="fas fa-music"></i>
            <span>Ver letra</span>
        </button>

        <div class="ctx-divider"></div>

        <!-- Compartir / Copiar info -->
        <button class="ctx-item" onclick="ContextActions.copyInfo(${JSON.stringify(song).replace(/"/g,'&quot;')})">
            <i class="fas fa-share-alt"></i>
            <span>Copiar información</span>
        </button>
    `;

    document.body.appendChild(menu);
    window.currentContextMenu = menu;

    // Ajustar posición para no salirse de pantalla
    requestAnimationFrame(() => {
        const rect = menu.getBoundingClientRect();
        const margin = 8;
        if (rect.right > window.innerWidth - margin) {
            menu.style.left = (event.clientX - rect.width) + 'px';
        }
        if (rect.bottom > window.innerHeight - margin) {
            menu.style.top = (event.clientY - rect.height) + 'px';
        }
        menu.classList.add('ctx-visible');
    });

    // Cerrar al hacer click fuera
    setTimeout(() => {
    document.addEventListener('click', function onClickOut(e) {
    if (!menu.contains(e.target)) {
        menu.classList.remove('ctx-visible');
        setTimeout(() => menu.remove(), 200);
        document.removeEventListener('click', onClickOut);
        if (window.currentContextMenu === menu) window.currentContextMenu = null;
    }
});
}, 10);
};

// =====================================================
// 2. ACCIONES DEL MENÚ CONTEXTUAL
// =====================================================
const ContextActions = {

async toggleFavorito(id, btnEl) {
try {
const res  = await fetch(`/api/canciones/${id}/favorito`, { method: 'PUT' });
const data = await res.json();

// Actualizar estado en caché
if (window.cache) {
window.cache.favoritos = null;
if (window.cache.todasCanciones) {
const s = window.cache.todasCanciones.find(c => c.id === id);
if (s) s.favorito = data.favorito;
}
}

// Actualizar botón del menú
if (btnEl) {
btnEl.classList.toggle('ctx-active', data.favorito);
btnEl.querySelector('span').textContent =
data.favorito ? 'Quitar de Me gusta' : 'Agregar a Me gusta';
}

// Actualizar corazón del player
if (window.state?.currentSong?.id === id) {
const heart = document.getElementById('favoriteHeart');
if (heart) heart.classList.toggle('heart-active', data.favorito);
if (window.state.currentSong) window.state.currentSong.favorito = data.favorito;
}

showToast(data.favorito ? '❤️ Agregado a Me gusta' : '💔 Quitado de Me gusta');
closeContextMenu();

// Si estamos en la vista de Me gusta, recargar
if (document.getElementById('liked-songs-view')) {
window.cache.favoritos = null;
loadLikedSongsView();
}
} catch { showToast('Error', true); }
},

toggleQueue(song) {
const idx = window.state?.queue?.findIndex(s => s.id === song.id);
if (idx > -1) {
window.state.queue.splice(idx, 1);
renderQueue();
showToast('Quitado de la cola');
} else {
addToQueue(song);
}
closeContextMenu();
},

playNext(song) {
if (!window.state.queue) window.state.queue = [];
const nextIdx = window.state.usingQueue
    ? window.state.queueIndex + 1
    : 0;
window.state.queue.splice(nextIdx, 0, song);
renderQueue();
showToast(`▶ Reproduciendo a continuación: ${song.nombre}`);
closeContextMenu();
},

openAddToPlaylist(songId, triggerEl) {
// Cerrar submenú anterior si existe
document.querySelector('.ctx-submenu')?.remove();

const playlists = window.state?.playlists || [];
const sub = document.createElement('div');
sub.className = 'ctx-submenu';

// Posicionar al lado del item
const rect = triggerEl.getBoundingClientRect();
sub.style.top  = rect.top  + 'px';
sub.style.left = (rect.right + 4) + 'px';

sub.innerHTML = `
            <div class="ctx-sub-search">
                <i class="fas fa-search"></i>
                <input type="text" placeholder="Buscar playlist..." id="ctxPlaylistSearch"
                       oninput="ContextActions._filterSubPlaylists(this.value, ${songId})">
            </div>
            <div class="ctx-sub-list" id="ctxSubPlaylistList">
                <button class="ctx-item" onclick="ContextActions.createAndAdd(${songId})">
                    <i class="fas fa-plus" style="color:#1db954"></i>
                    <span>Nueva playlist</span>
                </button>
                ${playlists.map(p => `
                    <button class="ctx-item" onclick="ContextActions.addToPlaylist(${songId}, ${p.id}, '${escapeHtml(p.nombre)}')">
                        <img src="${p.portadaUrl}" class="ctx-pl-thumb"
                             onerror="this.src='https://singlecolorimage.com/get/1DB954/32x32'">
                        <span>${escapeHtml(p.nombre)}</span>
                        <span class="ctx-pl-count">${p.cancionesCount || 0}</span>
                    </button>`).join('')}
            </div>`;

document.body.appendChild(sub);
requestAnimationFrame(() => {
// Ajustar si se sale por la derecha
const r = sub.getBoundingClientRect();
if (r.right > window.innerWidth - 8) {
sub.style.left = (rect.left - r.width - 4) + 'px';
}
if (r.bottom > window.innerHeight - 8) {
sub.style.top = (window.innerHeight - r.height - 8) + 'px';
}
sub.classList.add('ctx-visible');
});

setTimeout(() => {
document.getElementById('ctxPlaylistSearch')?.focus();
}, 50);
},

_filterSubPlaylists(query, songId) {
const list = document.getElementById('ctxSubPlaylistList');
if (!list) return;
const playlists = window.state?.playlists || [];
const filtered  = query
    ? playlists.filter(p => p.nombre.toLowerCase().includes(query.toLowerCase()))
    : playlists;
const items = list.querySelectorAll('.ctx-item:not(:first-child)');
items.forEach(el => el.remove());
filtered.forEach(p => {
const btn = document.createElement('button');
btn.className = 'ctx-item';
btn.innerHTML = `
                <img src="${p.portadaUrl}" class="ctx-pl-thumb"
                     onerror="this.src='https://singlecolorimage.com/get/1DB954/32x32'">
                <span>${escapeHtml(p.nombre)}</span>
                <span class="ctx-pl-count">${p.cancionesCount || 0}</span>`;
btn.onclick = () => ContextActions.addToPlaylist(songId, p.id, p.nombre);
list.appendChild(btn);
});
},

async addToPlaylist(songId, playlistId, playlistName) {
try {
const res = await fetch(`/api/playlists/${playlistId}/canciones/${songId}`, { method: 'POST' });
if (res.ok) {
showToast(`✅ Agregado a "${playlistName}"`);
window.cache.playlists = null;
await loadPlaylists();
} else {
showToast('Error al agregar', true);
}
} catch { showToast('Error', true); }
closeContextMenu();
},

async removeFromPlaylist(songId, playlistId) {
try {
await fetch(`/api/playlists/${playlistId}/canciones/${songId}`, { method: 'DELETE' });
showToast('Quitado de la playlist');
window.cache.playlists = null;
loadPlaylistView(playlistId);
} catch { showToast('Error', true); }
closeContextMenu();
},

async createAndAdd(songId) {
closeContextMenu();
const nombre = prompt('Nombre de la nueva playlist:');
if (!nombre?.trim()) return;
try {
const res  = await fetch('/api/playlists', {
method: 'POST',
headers: { 'Content-Type': 'application/json' },
body: JSON.stringify({ nombre: nombre.trim() })
});
const pl = await res.json();
await ContextActions.addToPlaylist(songId, pl.id, nombre.trim());
} catch { showToast('Error al crear playlist', true); }
},

goToArtist(artista) {
closeContextMenu();
loadArtistaDetalle(artista);
},

goToAlbum(album) {
closeContextMenu();
verAlbum(album);
},

async openLyrics(id) {
closeContextMenu();
// Si la canción no está cargada, cargarla primero
if (window.state?.currentSong?.id !== id) {
await playSong(id);
await new Promise(r => setTimeout(r, 400));
}
window.openLyricsModal?.();
},

copyInfo(song) {
closeContextMenu();
const text = `${song.nombre} — ${song.artista}${song.album ? ' · ' + song.album : ''}`;
navigator.clipboard?.writeText(text).then(() => {
showToast('📋 Copiado al portapapeles');
}).catch(() => showToast('No se pudo copiar', true));
}
};

window.ContextActions = ContextActions;

// Sobreescribir closeContextMenu para cerrar también submenús
window.closeContextMenu = function() {
document.querySelector('.ctx-submenu')?.remove();
if (window.currentContextMenu) {
window.currentContextMenu.classList.remove('ctx-visible');
setTimeout(() => window.currentContextMenu?.remove(), 200);
window.currentContextMenu = null;
}
};


// =====================================================
// 3. VISTA "ME GUSTA" — estilo Spotify
// =====================================================
window.loadLikedSongsView = async function() {
setActiveNav?.('favorites');
const content = document.getElementById('dynamicContent');
content.innerHTML = `<div class="loading"><i class="fas fa-spinner fa-spin"></i></div>`;

const favoritos = await fetch('/api/canciones/favoritos').then(r => r.json()).catch(() => []);
if (window.cache) window.cache.favoritos = favoritos;

const durTotal = favoritos.reduce((a, c) => a + (c.duracionSegundos || 0), 0);
const hrs = Math.floor(durTotal / 3600);
const min = Math.floor((durTotal % 3600) / 60);
const durStr = hrs > 0 ? `${hrs} h ${min} min` : `${min} min`;

content.innerHTML = `
        <!-- Header gradiente morado/azul como Spotify -->
        <div class="liked-header" id="liked-songs-view">
            <div class="liked-header-bg"></div>
            <div class="liked-header-content">
                <div class="liked-cover">
                    <i class="fas fa-heart"></i>
                </div>
                <div class="liked-info">
                    <p class="liked-type">LISTA</p>
                    <h1 class="liked-title">Canciones que te gustan</h1>
                    <p class="liked-meta">
                        <span class="liked-count">${favoritos.length} canciones</span>
                        ${durTotal > 0 ? `<span style="color:rgba(255,255,255,0.5)"> • ${durStr}</span>` : ''}
                    </p>
                </div>
            </div>
        </div>

        <!-- Barra de acciones -->
        <div class="liked-actions">
            ${favoritos.length ? `
            <button class="liked-play-btn" onclick="LikedSongs.playAll()">
                <i class="fas fa-play"></i>
            </button>
            <button class="liked-shuffle-btn" onclick="LikedSongs.playShuffle()">
                <i class="fas fa-random"></i>
            </button>` : ''}
            <div style="flex:1"></div>
            <div class="liked-sort-wrap">
                <span style="color:#b3b3b3;font-size:13px">Ordenar por</span>
                <select class="liked-sort-select" onchange="LikedSongs.sort(this.value)">
                    <option value="recent">Recientes</option>
                    <option value="name">Título</option>
                    <option value="artist">Artista</option>
                    <option value="album">Álbum</option>
                    <option value="plays">Reproducciones</option>
                </select>
            </div>
        </div>

        <!-- Lista de canciones -->
        ${favoritos.length === 0 ? `
        <div class="liked-empty">
            <i class="fas fa-heart" style="font-size:64px;color:rgba(255,255,255,0.1);margin-bottom:24px"></i>
            <h3>Canciones que te gustan</h3>
            <p>Guarda canciones dando clic en el ❤️ que está junto a cualquier canción.</p>
        </div>` : `
        <table class="spotify-table liked-table" id="likedTable">
            <thead>
                <tr>
                    <th class="st-idx">#</th>
                    <th>Título</th>
                    <th class="liked-col-album">Álbum</th>
                    <th class="liked-col-date" style="text-align:right">Fecha</th>
                    <th style="text-align:right;width:60px"><i class="fas fa-clock"></i></th>
                    <th style="width:32px"></th>
                </tr>
            </thead>
            <tbody id="likedTbody">
                ${renderLikedRows(favoritos)}
            </tbody>
        </table>`}`;
};

function renderLikedRows(songs) {
return songs.map((s, i) => `
        <tr class="liked-row ${window.state?.currentSong?.id === s.id ? 'playing' : ''}"
            ondblclick="playSong(${s.id})"
            data-id="${s.id}">
            <td class="st-idx">
                <span class="st-num">${i + 1}</span>
                <span class="st-play"><i class="fas fa-play"></i></span>
            </td>
            <td>
                <div class="st-song-cell">
                    <img class="st-thumb" src="${s.portadaUrl}"
                         onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'">
                    <div>
                        <div class="st-title ${s.favorito ? 'liked-title-green' : ''}">${escapeHtml(s.nombre)}</div>
                        <div class="st-artist">${escapeHtml(s.artista)}</div>
                    </div>
                </div>
            </td>
            <td class="st-album-cell liked-col-album">${escapeHtml(s.album || 'Single')}</td>
            <td class="liked-col-date" style="text-align:right;color:#6b7280;font-size:13px">—</td>
            <td class="st-duration">${formatDuration(s.duracionSegundos)}</td>
            <td class="st-menu">
                <button class="ctx-trigger" onclick="event.stopPropagation();showContextMenu(event,${JSON.stringify(s).replace(/"/g,'&quot;')})">
                    <i class="fas fa-ellipsis-h"></i>
                </button>
            </td>
        </tr>`).join('');
}

// Acciones de la vista Me gusta
const LikedSongs = {
async playAll() {
const favs = window.cache?.favoritos || await fetch('/api/canciones/favoritos').then(r=>r.json());
if (!favs.length) return;
window.state.queue = [...favs];
window.state.usingQueue = true;
window.state.queueIndex = 0;
renderQueue();
playSongFromObject(favs[0]);
showToast(`▶ Reproduciendo ${favs.length} canciones`);
},
async playShuffle() {
const favs = window.cache?.favoritos || await fetch('/api/canciones/favoritos').then(r=>r.json());
if (!favs.length) return;
const shuffled = [...favs].sort(() => Math.random() - 0.5);
window.state.queue = shuffled;
window.state.usingQueue = true;
window.state.queueIndex = 0;
renderQueue();
playSongFromObject(shuffled[0]);
showToast(`🔀 Reproducción aleatoria (${shuffled.length} canciones)`);
},
sort(by) {
const favs = window.cache?.favoritos || [];
if (!favs.length) return;
const sorted = [...favs];
if (by === 'name')   sorted.sort((a,b) => a.nombre.localeCompare(b.nombre));
if (by === 'artist') sorted.sort((a,b) => a.artista.localeCompare(b.artista));
if (by === 'album')  sorted.sort((a,b) => (a.album||'').localeCompare(b.album||''));
if (by === 'plays')  sorted.sort((a,b) => b.reproducciones - a.reproducciones);
const tbody = document.getElementById('likedTbody');
if (tbody) tbody.innerHTML = renderLikedRows(sorted);
}
};
window.LikedSongs = LikedSongs;

// Sobreescribir loadFavoritesView para usar la nueva
window.loadFavoritesView = window.loadLikedSongsView;


// =====================================================
// 4. VISTA LIBRERÍA — estilo Spotify
// =====================================================
window.loadLibraryView = async function() {
setActiveNav?.('library');
const content = document.getElementById('dynamicContent');
content.innerHTML = `<div class="loading"><i class="fas fa-spinner fa-spin"></i></div>`;

const [playlists, artistas, albumes] = await Promise.all([
fetch('/api/playlists').then(r=>r.json()).catch(()=>[]),
fetch('/api/artistas').then(r=>r.json()).catch(()=>[]),
fetch('/api/canciones/albumes').then(r=>r.json()).catch(()=>[])
]);

if (window.cache) {
window.cache.playlists = playlists;
window.state.playlists = playlists;
renderPlaylistsSidebar?.();
}

const totalItems = playlists.length + artistas.length + albumes.length;

content.innerHTML = `
        <div class="lib-header">
            <h1 class="lib-title">Tu biblioteca</h1>
            <button class="lib-create-btn" onclick="openCreatePlaylistModal()"
                    title="Crear playlist">
                <i class="fas fa-plus"></i>
            </button>
        </div>

        <!-- Filtros -->
        <div class="lib-filters" id="libFilters">
            <button class="lib-filter-chip active" data-filter="all"
                    onclick="Library.setFilter('all', this)">Todo</button>
            <button class="lib-filter-chip" data-filter="playlists"
                    onclick="Library.setFilter('playlists', this)">Playlists</button>
            <button class="lib-filter-chip" data-filter="artists"
                    onclick="Library.setFilter('artists', this)">Artistas</button>
            <button class="lib-filter-chip" data-filter="albums"
                    onclick="Library.setFilter('albums', this)">Álbumes</button>
        </div>

        <!-- Búsqueda dentro de librería -->
        <div class="lib-search-bar">
            <i class="fas fa-search"></i>
            <input type="text" id="libSearchInput" placeholder="Buscar en tu biblioteca"
                   oninput="Library.search(this.value)">
        </div>

        <!-- Sort -->
        <div class="lib-sort-row">
            <span class="lib-count">${totalItems} elementos</span>
            <button class="lib-sort-btn" onclick="Library.toggleSort()">
                <i class="fas fa-list-ul"></i>
                <span id="libSortLabel">Recientes</span>
            </button>
        </div>

        <!-- Lista de ítems -->
        <div class="lib-list" id="libList">
            ${renderLibraryItems(playlists, artistas, albumes, 'all', '')}
        </div>`;

// Guardar datos para filtrado
window._libData = { playlists, artistas, albumes };
};

function renderLibraryItems(playlists, artistas, albumes, filter, query) {
let items = [];

const q = query.toLowerCase();

if (filter === 'all' || filter === 'playlists') {
playlists
    .filter(p => !q || p.nombre.toLowerCase().includes(q))
    .forEach(p => items.push({
type: 'playlist', id: p.id, nombre: p.nombre,
sub: `Playlist • ${p.cancionesCount} canciones`,
img: p.portadaUrl, rounded: false,
onclick: `loadPlaylistView(${p.id})`
}));
}
if (filter === 'all' || filter === 'artists') {
artistas
    .filter(a => !q || a.nombre.toLowerCase().includes(q))
    .forEach(a => items.push({
type: 'artist', nombre: a.nombre,
sub: `Artista • ${a.totalCanciones} canciones`,
img: a.portadaUrl, rounded: true,
onclick: `loadArtistaDetalle('${escapeHtml(a.nombre)}')`
}));
}
if (filter === 'all' || filter === 'albums') {
albumes
    .filter(a => !q || a.nombre.toLowerCase().includes(q))
    .forEach(a => items.push({
type: 'album', nombre: a.nombre,
sub: `Álbum • ${a.conteo} canciones`,
img: a.portadaUrl, rounded: false,
onclick: `verAlbum('${escapeHtml(a.nombre)}')`
}));
}

if (!items.length) return `
        <div class="lib-empty">
            <i class="fas fa-search"></i>
            <p>No se encontraron resultados</p>
        </div>`;

return items.map(item => `
        <div class="lib-item" onclick="${item.onclick}">
            <div class="lib-item-img-wrap">
                <img src="${item.img}"
                     class="lib-item-img ${item.rounded ? 'lib-item-img--round' : ''}"
                     onerror="this.src='https://singlecolorimage.com/get/1DB954/56x56'">
            </div>
            <div class="lib-item-info">
                <span class="lib-item-name">${escapeHtml(item.nombre)}</span>
                <span class="lib-item-sub">${item.sub}</span>
            </div>
            ${item.type === 'playlist' ? `
            <button class="ctx-trigger lib-item-menu"
                    onclick="event.stopPropagation();Library.playlistMenu(event, ${item.id})">
                <i class="fas fa-ellipsis-h"></i>
            </button>` : ''}
        </div>`).join('');
}

const Library = {
setFilter(filter, el) {
LibraryState.currentLibraryFilter = filter;
document.querySelectorAll('.lib-filter-chip').forEach(c => c.classList.remove('active'));
el.classList.add('active');
this._refresh();
},
search(q) {
LibraryState.searchQuery = q;
this._refresh();
},
toggleSort() {
const opts = ['recent', 'name', 'creator'];
const labels = { recent: 'Recientes', name: 'Alfabético', creator: 'Creador' };
const cur  = LibraryState.currentLibrarySort;
const next = opts[(opts.indexOf(cur) + 1) % opts.length];
LibraryState.currentLibrarySort = next;
document.getElementById('libSortLabel').textContent = labels[next];
this._refresh();
},
_refresh() {
const d = window._libData;
if (!d) return;
let { playlists, artistas, albumes } = d;

// Ordenar
const sort = LibraryState.currentLibrarySort;
if (sort === 'name') {
playlists = [...playlists].sort((a,b) => a.nombre.localeCompare(b.nombre));
artistas  = [...artistas].sort((a,b)  => a.nombre.localeCompare(b.nombre));
albumes   = [...albumes].sort((a,b)   => a.nombre.localeCompare(b.nombre));
}

const list = document.getElementById('libList');
if (list) list.innerHTML = renderLibraryItems(
playlists, artistas, albumes,
LibraryState.currentLibraryFilter,
LibraryState.searchQuery
);
},
playlistMenu(event, playlistId) {
event.stopPropagation();
closeContextMenu();
const pl = window.state.playlists.find(p => p.id === playlistId);
if (!pl) return;

const menu = document.createElement('div');
menu.className = 'ctx-menu';
menu.style.left = event.clientX + 'px';
menu.style.top  = event.clientY + 'px';
menu.innerHTML = `
            <button class="ctx-item" onclick="loadPlaylistView(${playlistId})">
                <i class="fas fa-play"></i><span>Reproducir</span>
            </button>
            <button class="ctx-item" onclick="addMultipleToQueue(${JSON.stringify(pl.canciones||[]).replace(/"/g,'&quot;')})">
                <i class="fas fa-list"></i><span>Agregar a la cola</span>
            </button>
            <div class="ctx-divider"></div>
            <button class="ctx-item ctx-danger" onclick="Library.deletePlaylist(${playlistId})">
                <i class="fas fa-trash-alt"></i><span>Eliminar playlist</span>
            </button>`;
document.body.appendChild(menu);
window.currentContextMenu = menu;
requestAnimationFrame(() => {
const r = menu.getBoundingClientRect();
if (r.right  > window.innerWidth  - 8) menu.style.left = (event.clientX - r.width) + 'px';
if (r.bottom > window.innerHeight - 8) menu.style.top  = (event.clientY - r.height) + 'px';
menu.classList.add('ctx-visible');
});
setTimeout(() => {
document.addEventListener('click', function onOut(e) {
if (!menu.contains(e.target)) {
closeContextMenu();
document.removeEventListener('click', onOut);
}
});
}, 10);
},
async deletePlaylist(id) {
if (!confirm('¿Eliminar esta playlist? No se puede deshacer.')) return;
closeContextMenu();
try {
await fetch(`/api/playlists/${id}`, { method: 'DELETE' });
showToast('Playlist eliminada');
window.cache && (window.cache.playlists = null);
await loadPlaylists();
loadLibraryView();
} catch { showToast('Error al eliminar', true); }
}
};
window.Library = Library;


// =====================================================
// 5. CONECTAR NAVEGACIÓN
// =====================================================
// Sobreescribir setupNavigation para incluir favorites + library
const _origSetupNav = window.setupNavigation;
window.setupNavigation = function() {
document.querySelectorAll('.nav-item').forEach(item => {
item.addEventListener('click', () => {
const view = item.dataset.view;
document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
item.classList.add('active');
if      (view === 'home')        window.loadHome?.();
else if (view === 'favorites')   window.loadLikedSongsView();
else if (view === 'library')     window.loadLibraryView();
else if (view === 'artistas')    window.loadArtistasView?.();
else if (view === 'estadisticas')window.loadEstadisticasView?.();
});
});
};

// Re-inicializar navegación (timeout para asegurar que el DOM y otros scripts estén listos)
setTimeout(() => {
if (typeof setupNavigation === 'function') setupNavigation();
}, 0);
