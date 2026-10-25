// ========== ESTADO GLOBAL ==========
// Registro global de contextos de reproducción por contenedor
// Evita pasar JSON dentro de onclick (que se rompe con caracteres especiales)
window._playContextRegistry = {};

let cache = {
    todasCanciones: null,
    favoritos: null,
    top: null,
    albumes: null,
    playlists: null,
    artistas: null,
    estadisticas: null,
    letras: new Map(),
    albumesDetalle: new Map()
};

let state = {
    currentSong: null,
    currentIndex: -1,
    allSongs: [],
    playlists: [],
    repeatMode: 0,
    shuffleMode: false,
    shuffledSongs: [],
    queue: [],
    queueIndex: -1,
    usingQueue: false,
    currentFilter: 'all',
    currentSearchResults: null,
    currentSearchQuery: '',
    smartQueue: [],          // cola de radio inteligente automática
    smartQueueActive: false, // indica si estamos en modo radio
    // Contexto de reproducción: define qué lista se navega con siguiente/anterior
    // Tipos: 'album', 'playlist', 'search', 'favorites', 'top', 'smart', 'all'
    playContext: { type: 'all', songs: [], label: '' }
};

let searchTimeout = null;
let currentContextMenu = null;

// Exponer state y cache globalmente para que otros scripts puedan accederlos
window.state = state;
window.cache = cache;

const audio = document.getElementById('audioElement');

// Resolver autoplay bloqueado: en el primer click del usuario, reproducir si hay canción pendiente
window._pendingPlay = false;
document.addEventListener('click', function resumeAudio() {
    if (window._pendingPlay && audio.src && audio.paused) {
        audio.play()
            .then(() => {
            document.getElementById('playPauseIcon').className = 'fas fa-pause';
            window._pendingPlay = false;
        })
            .catch(() => {});
    }
}, { passive: true });

// ========== FUNCIONES UTILITARIAS ==========
function showToast(msg, isError = false) {
const toast = document.createElement('div');
toast.className = `toast ${isError ? 'error' : ''}`;
toast.innerHTML = msg;
document.body.appendChild(toast);
setTimeout(() => toast.remove(), 3000);
}

function escapeHtml(text) {
if (!text) return '';
const div = document.createElement('div');
div.textContent = text;
return div.innerHTML;
}

function formatDuration(seconds) {
if (!seconds || isNaN(seconds)) return '0:00';
const mins = Math.floor(seconds / 60);
const secs = Math.floor(seconds % 60);
return `${mins}:${secs.toString().padStart(2, '0')}`;
}

function formatDurationLong(seconds) {
if (!seconds || isNaN(seconds)) return '0 min';
const h = Math.floor(seconds / 3600);
const m = Math.floor((seconds % 3600) / 60);
if (h > 0) return `${h}h ${m}m`;
return `${m} min`;
}

function formatNumber(n) {
if (!n) return '0';
if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
return n.toString();
}

function closeContextMenu() {
if (currentContextMenu) {
currentContextMenu.remove();
currentContextMenu = null;
}
}

// ========== MENÚ CONTEXTUAL (3 PUNTOS) ==========
function showContextMenu(event, song) {
event.stopPropagation();
closeContextMenu();

const menu = document.createElement('div');
menu.className = 'context-menu';
menu.style.left = `${event.clientX}px`;
menu.style.top = `${event.clientY}px`;

menu.innerHTML = `
        <div class="context-menu-item" onclick="agregarAFavoritos(${song.id}); closeContextMenu();">
            <i class="fas fa-heart"></i>
            <span>${song.favorito ? 'Quitar de favoritos' : 'Agregar a favoritos'}</span>
        </div>
        <div class="context-menu-item" onclick="addToQueue(${JSON.stringify(song).replace(/"/g, '&quot;')}); closeContextMenu();">
            <i class="fas fa-list"></i>
            <span>Agregar a cola</span>
        </div>
        <div class="context-menu-divider"></div>
        <div class="context-menu-item" onclick="showPlaylistMenuFromContext(${song.id}); closeContextMenu();">
            <i class="fas fa-plus-circle"></i>
            <span>Agregar a playlist</span>
        </div>
        <div class="context-menu-item" onclick="irAlArtista('${escapeHtml(song.artista)}'); closeContextMenu();">
            <i class="fas fa-microphone"></i>
            <span>Ir al artista</span>
        </div>
        <div class="context-menu-item" onclick="irAlAlbum('${escapeHtml(song.album)}'); closeContextMenu();">
            <i class="fas fa-compact-disc"></i>
            <span>Ir al álbum</span>
        </div>
    `;

document.body.appendChild(menu);
currentContextMenu = menu;

// Ajustar posición si se sale de pantalla
const rect = menu.getBoundingClientRect();
if (rect.right > window.innerWidth) menu.style.left = (event.clientX - rect.width) + 'px';
if (rect.bottom > window.innerHeight) menu.style.top = (event.clientY - rect.height) + 'px';

setTimeout(() => {
document.addEventListener('click', function closeMenu(e) {
if (!menu.contains(e.target)) {
menu.remove();
document.removeEventListener('click', closeMenu);
if (currentContextMenu === menu) currentContextMenu = null;
}
});
}, 10);
}

async function agregarAFavoritos(id) {
await toggleFavorito(id, null);
}

function showPlaylistMenuFromContext(cancionId) {
if (!state.playlists.length) {
if (confirm('No hay playlists. ¿Crear una?')) crearPlaylistYAgregar(cancionId);
return;
}
const modal = document.createElement('div');
modal.className = 'modal';
modal.innerHTML = `<div class="modal-content"><h3>Agregar a Playlist</h3>${state.playlists.map(p => `<div class="playlist-option" onclick="agregarAPlaylist(${cancionId}, ${p.id}, '${escapeHtml(p.nombre)}')"><img src="${p.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'"><span>${escapeHtml(p.nombre)} (${p.cancionesCount})</span></div>`).join('')}<hr><div class="playlist-option" onclick="crearPlaylistYAgregar(${cancionId})"><i class="fas fa-plus-circle" style="color:#1db954;"></i> Crear nueva playlist</div><button onclick="this.closest('.modal').remove()" style="margin-top:16px; padding:8px; background:#333; border:none; border-radius:8px; color:#fff; width:100%;">Cancelar</button></div>`;
document.body.appendChild(modal);
modal.onclick = (e) => { if (e.target === modal) modal.remove(); };
}

function irAlArtista(artista) {
loadArtistaDetalle(artista);
}

function irAlAlbum(album) {
verAlbum(album);
}

// ========== COLA DE REPRODUCCIÓN ==========
function toggleQueuePanel() {
document.getElementById('queuePanel').classList.toggle('open');
}

function renderQueue() {
const container = document.getElementById('queueList');
if (!state.queue.length) {
container.innerHTML = '<div class="queue-empty">No hay canciones en cola</div>';
return;
}
container.innerHTML = state.queue.map((song, idx) => `
        <div class="queue-item ${state.usingQueue && state.queueIndex === idx ? 'active' : ''}" onclick="playFromQueue(${idx})">
            <img class="queue-item-img" src="${song.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'">
            <div class="queue-item-info">
                <div class="queue-item-title">${escapeHtml(song.nombre)}</div>
                <div class="queue-item-artist">${escapeHtml(song.artista)}</div>
            </div>
            <button class="remove-from-queue" onclick="event.stopPropagation(); removeFromQueue(${idx})"><i class="fas fa-trash"></i></button>
        </div>
    `).join('');
}

function addToQueue(song) {
state.queue.push(song);
renderQueue();
showToast(`✅ Agregado a la cola: ${song.nombre}`);
}

function addMultipleToQueue(songs) {
state.queue.push(...songs);
renderQueue();
showToast(`✅ ${songs.length} canciones agregadas a la cola`);
}

function removeFromQueue(index) {
state.queue.splice(index, 1);
if (state.usingQueue && state.queueIndex >= index) {
state.queueIndex--;
if (state.queueIndex >= state.queue.length) state.queueIndex = state.queue.length - 1;
}
if (state.queue.length === 0) {
state.usingQueue = false;
state.queueIndex = -1;
}
renderQueue();
showToast('🗑 Eliminado de la cola');
}

function playFromQueue(index) {
if (index >= 0 && index < state.queue.length) {
state.usingQueue = true;
state.queueIndex = index;
playSongFromObject(state.queue[index]);
}
}

function playSongFromObject(song) {
state.currentSong = song;
const audioUrl = `/api/canciones/${song.id}/audio`;

document.getElementById('currentSong').innerText   = song.nombre;
document.getElementById('currentArtist').innerText = song.artista;
document.getElementById('currentCover').src        = song.portadaUrl;

const heartIcon = document.getElementById('favoriteHeart');
if (song.favorito) heartIcon.classList.add('heart-active');
else               heartIcon.classList.remove('heart-active');

// Detectar MIME type para que el navegador sepa el codec (clave para FLAC)
function getMime(rutaArchivo) {
if (!rutaArchivo) return 'audio/mpeg';
const ext = rutaArchivo.split('.').pop().toLowerCase();
const mimes = { flac:'audio/flac', mp3:'audio/mpeg', ogg:'audio/ogg',
wav:'audio/wav', m4a:'audio/mp4', aac:'audio/aac' };
return mimes[ext] || 'audio/mpeg';
}

const mimeType = getMime(song.rutaArchivo);

// Limpiar handlers anteriores
audio.oncanplay = null;
audio.onerror   = null;

// Para FLAC usar /api/files/stream/ que tiene Range support garantizado
// Para MP3 usar el endpoint normal
const finalUrl = mimeType === 'audio/flac'
    ? `/api/files/stream/${encodeURIComponent(song.rutaArchivo)}`
    : audioUrl;

console.log('🎵 Reproduciendo:', song.nombre, '| URL:', finalUrl, '| MIME:', mimeType);

audio.src = finalUrl;
audio.type = mimeType;
audio.load();

// Intentar reproducir cuando el audio esté listo
audio.oncanplay = () => {
const p = audio.play();
if (p) p
    .then(() => {
document.getElementById('playPauseIcon').className = 'fas fa-pause';
window._pendingPlay = false;
})
    .catch(err => {
if (err.name === 'NotAllowedError') {
// Navegador bloqueó autoplay — esperar primer click del usuario
window._pendingPlay = true;
document.getElementById('playPauseIcon').className = 'fas fa-play';
showToast('▶ Toca play para comenzar', false);
} else if (err.name === 'AbortError') {
// Normal: se interrumpió porque se cargó otra canción
} else if (err.name === 'NotSupportedError') {
showToast('Formato no soportado. Usa Chrome o Firefox.', true);
} else {
console.error('Error al reproducir:', err.name, err.message);
}
});
};

audio.onerror = () => {
const err = audio.error;
const msgs = {
1: 'Carga cancelada',
2: 'Error de red al cargar el audio',
3: mimeType === 'audio/flac'
    ? 'Error decodificando FLAC. ¿Archivo corrupto?'
    : 'Error decodificando el audio',
4: `Archivo no encontrado o formato no soportado (404/codec). MIME: ${mimeType}`
};
const msg = (err && msgs[err.code]) || 'Error desconocido';
// Verificar si es realmente un 404 del servidor
if (err?.code === 4) {
fetch(audio.src, {method:'HEAD'})
    .then(r => {
if (r.status === 404) {
console.error('❌ 404 del servidor — archivo no encontrado en el backend');
showToast('⚠️ Archivo no encontrado en el servidor (404)', true);
} else {
console.error('Audio error:', err?.code, msg, '| MIME:', mimeType);
showToast('⚠️ ' + msg, true);
}
})
    .catch(() => showToast('⚠️ ' + msg, true));
} else {
console.error('Audio error:', err?.code, msg, '| MIME:', mimeType, '| URL:', audioUrl);
showToast('⚠️ ' + msg, true);
}
document.getElementById('playPauseIcon').className = 'fas fa-play';
};

fetch(`/api/canciones/${song.id}/reproducir`, { method: 'POST' }).catch(() => {});
renderQueue();
}

// ========== CARGA CON CACHÉ ==========
async function loadData(endpoint, cacheKey) {
if (cache[cacheKey]) return cache[cacheKey];
try {
const response = await fetch(endpoint);
const data = await response.json();
cache[cacheKey] = data;
return data;
} catch(e) {
return [];
}
}

async function loadTodasCanciones() { return loadData('/api/canciones', 'todasCanciones'); }
async function loadFavoritos() { return loadData('/api/canciones/favoritos', 'favoritos'); }
async function loadTop() { return loadData('/api/canciones/top', 'top'); }
async function loadAlbumes() { return loadData('/api/canciones/albumes', 'albumes'); }
async function loadArtistas() { return loadData('/api/artistas', 'artistas'); }
async function loadEstadisticas() { return loadData('/api/estadisticas', 'estadisticas'); }

async function loadPlaylists() {
const data = await loadData('/api/playlists', 'playlists');
state.playlists = data;
renderPlaylistsSidebar();
return data;
}

async function loadLyrics(songId) {
if (cache.letras.has(songId)) return cache.letras.get(songId);
try {
const response = await fetch(`/api/canciones/${songId}/letra`);
const data = await response.json();
cache.letras.set(songId, { letra: data.letra || '', letraSincronizada: data.letraSincronizada || '' });
return cache.letras.get(songId);
} catch(e) {
return { letra: '', letraSincronizada: '' };
}
}

// ========== RENDER PLAYLISTS EN SIDEBAR ==========
function renderPlaylistsSidebar() {
const container = document.getElementById('playlistList');
if (!container) return;
container.innerHTML = state.playlists.map(p => `
        <div class="playlist-item" onclick="loadPlaylistView(${p.id})">
            <img class="playlist-item-img" src="${p.portadaUrl || 'https://singlecolorimage.com/get/1DB954/40x40'}" onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'">
            <div class="playlist-item-info">
                <div class="playlist-item-name">${escapeHtml(p.nombre)}</div>
                <div class="playlist-item-count">${p.cancionesCount || 0} canciones</div>
                ${p.descripcion ? `<div class="playlist-item-desc">${escapeHtml(p.descripcion)}</div>` : ''}
            </div>
        </div>
    `).join('');
}

// ========== CREAR PLAYLIST CON IMAGEN ==========
function openCreatePlaylistModal() {
const modal = document.createElement('div');
modal.className = 'create-playlist-modal';
modal.innerHTML = `
        <div class="create-playlist-content">
            <h3>Crear nueva playlist</h3>
            <img class="image-preview" id="playlistImagePreview" src="https://singlecolorimage.com/get/1DB954/100x100" onclick="document.getElementById('playlistImageInput').click()">
            <input type="file" id="playlistImageInput" accept="image/*" style="display:none">
            <input type="text" id="playlistName" placeholder="Nombre de la playlist">
            <textarea id="playlistDesc" placeholder="Descripción (opcional)"></textarea>
            <div class="create-playlist-buttons">
                <button class="btn-cancel" onclick="this.closest('.create-playlist-modal').remove()">Cancelar</button>
                <button class="btn-create" onclick="createPlaylistWithImage()">Crear</button>
            </div>
        </div>
    `;
document.body.appendChild(modal);
const imageInput = document.getElementById('playlistImageInput');
const imagePreview = document.getElementById('playlistImagePreview');
imageInput.onchange = (e) => {
const file = e.target.files[0];
if (file) {
const reader = new FileReader();
reader.onload = (event) => { imagePreview.src = event.target.result; };
reader.readAsDataURL(file);
}
};
}

async function createPlaylistWithImage() {
const nombre = document.getElementById('playlistName')?.value;
const descripcion = document.getElementById('playlistDesc')?.value;
const imageInput = document.getElementById('playlistImageInput');
const file = imageInput?.files[0];
if (!nombre?.trim()) { showToast('El nombre es requerido', true); return; }
const formData = new FormData();
formData.append('nombre', nombre.trim());
if (descripcion) formData.append('descripcion', descripcion);
if (file) formData.append('file', file);
try {
const response = await fetch('/api/playlists/con-imagen', { method: 'POST', body: formData });
if (response.ok) {
showToast('✅ Playlist creada');
document.querySelector('.create-playlist-modal')?.remove();
cache.playlists = null;
await loadPlaylists();
} else {
showToast('Error al crear playlist', true);
}
} catch(e) { showToast('Error al crear playlist', true); }
}

// ========== VISTAS PRINCIPALES ==========
async function loadHome() {
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando...</div>';
const [top, favoritos, albumes, artistas] = await Promise.all([loadTop(), loadFavoritos(), loadAlbumes(), loadArtistas()]);
let html = '';
if (top?.length) html += `
        <div class="section">
            <div class="section-header">
                <h2>🔥 Más reproducidas</h2>
                <div style="display:flex;gap:8px;flex-wrap:wrap">
                    <button class="action-btn" onclick="loadSmartShuffleQueue()" style="background:#1db954;color:#000;padding:8px 16px;border-radius:20px;font-size:12px"><i class="fas fa-magic"></i> Mix inteligente</button>
                    <button class="action-btn" onclick="_playFromGrid('topGrid', top[0]?.id, 'top', 'Más reproducidas')" style="background:#282828;color:#fff;padding:8px 16px;border-radius:20px;font-size:12px"><i class="fas fa-play"></i> Reproducir</button>
                </div>
            </div>
            <div class="grid" id="topGrid"></div>
        </div>`;
if (artistas?.length) html += `
        <div class="section">
            <div class="section-header">
                <h2>🎤 Artistas</h2>
                <button class="action-btn" onclick="loadArtistasView()" style="color:#1db954;background:none;border:none;cursor:pointer;font-size:13px;">Ver todos →</button>
            </div>
            <div class="grid" id="artistasHomeGrid"></div>
        </div>`;
if (favoritos?.length) html += `
        <div class="section">
            <div class="section-header"><h2>❤️ Tus favoritas</h2></div>
            <div class="grid" id="favGrid"></div>
        </div>`;
if (albumes?.length) html += `
        <div class="section">
            <div class="section-header"><h2>📀 Álbumes</h2></div>
            <div class="grid" id="albumGrid"></div>
        </div>`;
content.innerHTML = html;
if (top?.length) renderSongsWithMenu(top, 'topGrid', 'top', 'Más reproducidas');
if (artistas?.length) renderArtistasGrid(artistas.slice(0, 8), 'artistasHomeGrid');
if (favoritos?.length) renderSongsWithMenu(favoritos, 'favGrid', 'favorites', 'Favoritas');
if (albumes?.length) renderAlbumes(albumes, 'albumGrid');
}

async function loadFavoritesView() {
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando favoritos...</div>';
const favoritos = await loadFavoritos();
if (!favoritos?.length) {
content.innerHTML = '<div class="loading"><i class="fas fa-heart" style="font-size:48px"></i><br>No tienes canciones favoritas</div>';
return;
}
content.innerHTML = `
        <div class="section">
            <div class="section-header">
                <h2>❤️ Canciones favoritas</h2>
                <button class="action-btn" onclick="_playFromGrid('favoritesGrid', favoritos[0]?.id, 'favorites', 'Favoritas')" style="background:#1db954;color:#000;padding:8px 16px;border-radius:20px;font-size:12px"><i class="fas fa-play"></i> Reproducir todas</button>
            </div>
            <div class="grid" id="favoritesGrid"></div>
        </div>`;
renderSongsWithMenu(favoritos, 'favoritesGrid', 'favorites', 'Canciones favoritas');
}

// ========== VISTA ARTISTAS ==========
async function loadArtistasView() {
setActiveNav('artistas');
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando artistas...</div>';
const artistas = await loadArtistas();
if (!artistas?.length) {
content.innerHTML = '<div class="loading"><i class="fas fa-microphone" style="font-size:48px"></i><br>No hay artistas</div>';
return;
}
content.innerHTML = `
        <div class="section">
            <div class="section-header">
                <h2>🎤 Todos los artistas</h2>
                <span style="color:#b3b3b3;font-size:14px">${artistas.length} artistas</span>
            </div>
            <div class="grid" id="artistasGrid"></div>
        </div>`;
renderArtistasGrid(artistas, 'artistasGrid');
}

function renderArtistasGrid(artistas, containerId) {
const container = document.getElementById(containerId);
if (!container) return;
container.innerHTML = artistas.map(a => `
        <div class="song-card artista-card" onclick="loadArtistaDetalle('${escapeHtml(a.nombre)}')">
            <div class="card-image artista-img-wrapper">
                <img src="${a.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/180x180'" style="border-radius:50%">
                <div class="play-overlay"><i class="fas fa-play"></i></div>
            </div>
            <h4 style="text-align:center">${escapeHtml(a.nombre)}</h4>
            <p style="text-align:center">${a.totalCanciones} canciones • ${a.totalAlbumes} álbumes</p>
            ${a.totalReproducciones > 0 ? `<p style="text-align:center;color:#1db954;font-size:11px">${formatNumber(a.totalReproducciones)} reproducciones</p>` : ''}
        </div>
    `).join('');
}

async function loadArtistaDetalle(nombre) {
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando artista...</div>';
try {
const data = await fetch(`/api/artistas/${encodeURIComponent(nombre)}`).then(r => r.json());
let html = `
            <div class="artista-hero">
                <div class="artista-hero-bg" style="background-image:url('${data.portadaUrl}')"></div>
                <div class="artista-hero-content">
                    <div class="artista-avatar">
                        <img src="${data.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/120x120'">
                    </div>
                    <div class="artista-hero-info">
                        <p style="font-size:12px;text-transform:uppercase;letter-spacing:2px;color:#b3b3b3">Artista</p>
                        <h1 style="font-size:clamp(32px,5vw,72px);font-weight:900;margin:8px 0">${escapeHtml(data.nombre)}</h1>
                        <p style="color:#b3b3b3">${data.totalCanciones} canciones • ${data.totalAlbumes} álbumes • ${formatNumber(data.totalReproducciones)} reproducciones</p>
                        <div style="display:flex;gap:12px;margin-top:20px;flex-wrap:wrap">
                            <button class="btn-play-big" onclick="loadSmartShuffleQueue(null, '${escapeHtml(data.nombre)}')">
                                <i class="fas fa-play"></i> Reproducir
                            </button>
                            <button class="btn-mix-artista" onclick="loadMixArtista('${escapeHtml(data.nombre)}')">
                                <i class="fas fa-magic"></i> Mix del artista
                            </button>
                        </div>
                    </div>
                </div>
            </div>`;

if (data.topCanciones?.length) {
html += `
                <div class="section" style="margin-top:32px">
                    <div class="section-header"><h3>🔥 Canciones populares</h3></div>
                    <table class="songs-table">
                        <thead><tr><th>#</th><th>Título</th><th>Álbum</th><th>Duración</th><th></th></tr></thead>
                        <tbody>
                            ${data.topCanciones.map((song, idx) => `
                                <tr onclick="playSong(${song.id})">
                                    <td class="song-play">${idx + 1}</td>
                                    <td class="song-title">${escapeHtml(song.nombre)}</td>
                                    <td class="song-artist" style="color:#b3b3b3">${escapeHtml(song.album)}</td>
                                    <td class="song-duration">${formatDuration(song.duracionSegundos)}</td>
                                    <td><div class="menu-btn" style="position:relative;top:0;right:0;background:none" onclick="event.stopPropagation();showContextMenu(event,${JSON.stringify(song).replace(/"/g, '&quot;')})"><i class="fas fa-ellipsis-v"></i></div></td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>`;
}

if (data.albumes?.length) {
html += `<div class="section"><div class="section-header"><h3>📀 Discografía</h3></div><div style="display:flex;flex-direction:column;gap:32px">`;
data.albumes.forEach(album => {
html += `
                    <div class="album-section">
                        <div style="display:flex;align-items:center;gap:20px;margin-bottom:16px">
                            <img src="${album.portadaUrl}" style="width:80px;height:80px;border-radius:8px;object-fit:cover">
                            <div>
                                <h4 style="font-size:18px;margin-bottom:4px">${escapeHtml(album.nombre)}</h4>
                                <p style="color:#b3b3b3;font-size:13px">${album.totalCanciones} canciones • ${formatDurationLong(album.duracionTotal)}</p>
                            </div>
                            <button onclick="_playFromAlbum('${escapeHtml(album.nombre).replace(/'/g, "\'")}', null)" style="margin-left:auto;background:#282828;border:none;color:#fff;padding:8px 16px;border-radius:20px;cursor:pointer;font-size:12px">
                                <i class="fas fa-play"></i> Reproducir
                            </button>
                        </div>
                        <table class="songs-table">
                            <tbody>
                                ${album.canciones.map((song, idx) => `
                                    <tr onclick="playSong(${song.id})">
                                        <td class="song-play" style="width:32px">${idx + 1}</td>
                                        <td class="song-title">${escapeHtml(song.nombre)}</td>
                                        <td class="song-duration">${formatDuration(song.duracionSegundos)}</td>
                                        <td><div class="menu-btn" style="position:relative;top:0;right:0;background:none" onclick="event.stopPropagation();showContextMenu(event,${JSON.stringify(song).replace(/"/g, '&quot;')})"><i class="fas fa-ellipsis-v"></i></div></td>
                                    </tr>
                                `).join('')}
                            </tbody>
                        </table>
                    </div>`;
});
html += `</div></div>`;
}

content.innerHTML = html;
} catch(e) {
showToast('Error al cargar el artista', true);
loadHome();
}
}

// ========== VISTA ESTADÍSTICAS ==========
async function loadEstadisticasView() {
setActiveNav('estadisticas');
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando estadísticas...</div>';

try {
const [resumen, topArtistas, topGeneros, porMes] = await Promise.all([
fetch('/api/estadisticas').then(r => r.json()),
fetch('/api/estadisticas/top-artistas?limite=8').then(r => r.json()),
fetch('/api/estadisticas/top-generos').then(r => r.json()),
fetch('/api/estadisticas/por-mes').then(r => r.json())
]);

const html = `
            <div class="section">
                <div class="section-header"><h2>📊 Tu biblioteca</h2></div>

                <!-- Tarjetas resumen -->
                <div class="stats-grid">
                    <div class="stat-card">
                        <i class="fas fa-music stat-icon"></i>
                        <div class="stat-number">${formatNumber(resumen.totalCanciones || 0)}</div>
                        <div class="stat-label">Canciones</div>
                    </div>
                    <div class="stat-card">
                        <i class="fas fa-microphone stat-icon"></i>
                        <div class="stat-number">${formatNumber(resumen.totalArtistas || 0)}</div>
                        <div class="stat-label">Artistas</div>
                    </div>
                    <div class="stat-card">
                        <i class="fas fa-compact-disc stat-icon"></i>
                        <div class="stat-number">${formatNumber(resumen.totalAlbumes || 0)}</div>
                        <div class="stat-label">Álbumes</div>
                    </div>
                    <div class="stat-card">
                        <i class="fas fa-clock stat-icon"></i>
                        <div class="stat-number">${resumen.duracionFormateada || '0h'}</div>
                        <div class="stat-label">Duración total</div>
                    </div>
                    <div class="stat-card">
                        <i class="fas fa-play-circle stat-icon"></i>
                        <div class="stat-number">${formatNumber(resumen.totalReproducciones || 0)}</div>
                        <div class="stat-label">Reproducciones</div>
                    </div>
                    <div class="stat-card">
                        <i class="fas fa-heart stat-icon" style="color:#e91e63"></i>
                        <div class="stat-number">${formatNumber(resumen.totalFavoritos || 0)}</div>
                        <div class="stat-label">Favoritas</div>
                    </div>
                </div>

                <!-- Top artistas y géneros en fila -->
                <div class="stats-row">
                    <!-- Top artistas -->
                    <div class="stats-panel">
                        <h3 style="margin-bottom:16px"><i class="fas fa-microphone" style="color:#1db954;margin-right:8px"></i>Top artistas</h3>
                        <div class="top-list">
                            ${(topArtistas || []).map((a, i) => {
const maxRepros = topArtistas[0]?.reproducciones || 1;
const pct = Math.round((a.reproducciones / maxRepros) * 100);
return `
                                    <div class="top-item" onclick="loadArtistaDetalle('${escapeHtml(a.artista)}')">
                                        <span class="top-rank">${i + 1}</span>
                                        <div class="top-item-info">
                                            <div class="top-item-name">${escapeHtml(a.artista)}</div>
                                            <div class="top-item-bar-wrap">
                                                <div class="top-item-bar" style="width:${pct}%"></div>
                                            </div>
                                        </div>
                                        <span class="top-item-count">${formatNumber(a.reproducciones)}</span>
                                    </div>`;
}).join('')}
                        </div>
                    </div>

                    <!-- Top géneros -->
                    <div class="stats-panel">
                        <h3 style="margin-bottom:16px"><i class="fas fa-tag" style="color:#1db954;margin-right:8px"></i>Géneros</h3>
                        <div class="top-list">
                            ${(topGeneros || []).slice(0, 8).map((g, i) => {
const colores = ['#1db954','#e91e63','#ff9800','#2196f3','#9c27b0','#00bcd4','#ff5722','#8bc34a'];
return `
                                    <div class="top-item">
                                        <span class="top-rank" style="background:${colores[i % colores.length]}20;color:${colores[i % colores.length]}">${i + 1}</span>
                                        <div class="top-item-info">
                                            <div class="top-item-name">${escapeHtml(g.genero)}</div>
                                            <div class="top-item-bar-wrap">
                                                <div class="top-item-bar" style="width:${g.porcentaje || 0}%;background:${colores[i % colores.length]}"></div>
                                            </div>
                                        </div>
                                        <span class="top-item-count">${g.porcentaje || 0}%</span>
                                    </div>`;
}).join('')}
                        </div>
                    </div>
                </div>

                <!-- Canciones agregadas por mes -->
                ${porMes?.length ? `
                <div class="stats-panel" style="margin-top:24px">
                    <h3 style="margin-bottom:20px"><i class="fas fa-calendar" style="color:#1db954;margin-right:8px"></i>Canciones agregadas</h3>
                    <div class="bar-chart">
                        ${(() => {
const max = Math.max(...porMes.map(m => m.cantidad), 1);
return porMes.map(m => `
                                <div class="bar-col">
                                    <div class="bar-value">${m.cantidad}</div>
                                    <div class="bar-fill" style="height:${Math.round((m.cantidad / max) * 120)}px"></div>
                                    <div class="bar-label">${m.etiqueta}</div>
                                </div>`).join('');
})()}
                    </div>
                </div>` : ''}
            </div>`;

content.innerHTML = html;
} catch(e) {
showToast('Error al cargar estadísticas', true);
content.innerHTML = '<div class="loading">Error al cargar estadísticas</div>';
}
}

// ========== SMART SHUFFLE ==========
async function loadSmartShuffleQueue(genero = null, artista = null) {
try {
let url = '/api/shuffle/cola?cantidad=25';
if (genero) url = `/api/shuffle/genero/${encodeURIComponent(genero)}?cantidad=25`;
if (artista) url = `/api/shuffle/artista/${encodeURIComponent(artista)}?cantidad=25`;

const canciones = await fetch(url).then(r => r.json());
if (!canciones?.length) { showToast('No hay canciones para el mix', true); return; }

state.queue = canciones;
state.usingQueue = true;
state.queueIndex = 0;
renderQueue();
playSongFromObject(canciones[0]);
showToast(`🎵 Mix inteligente: ${canciones.length} canciones`);
} catch(e) {
showToast('Error al cargar mix', true);
}
}

async function loadMixDiario() {
try {
const canciones = await fetch('/api/shuffle/diario?cantidad=25').then(r => r.json());
if (!canciones?.length) { showToast('No hay canciones', true); return; }
state.queue = canciones;
state.usingQueue = true;
state.queueIndex = 0;
renderQueue();
playSongFromObject(canciones[0]);
showToast(`☀️ Mix diario: ${canciones.length} canciones`);
} catch(e) {
showToast('Error al cargar mix diario', true);
}
}

async function loadMixArtista(artista) {
await loadSmartShuffleQueue(null, artista);
}

// ========== RENDERIZADO ==========
function renderSongsWithMenu(songs, containerId, contextType, contextLabel) {
const container = document.getElementById(containerId);
if (!container || !songs) return;

// Guardar en registro global (evita pasar JSON en onclick)
window._playContextRegistry[containerId] = songs;

container.innerHTML = songs.map(song => `
        <div class="song-card">
            <div class="card-image"
                 onclick="_playFromGrid('${containerId}', ${song.id}, '${contextType || 'grid'}', '${(contextLabel || '').replace(/'/g, "\'")}')">
                <img src="${song.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/180x180'">
                <div class="play-overlay"><i class="fas fa-play"></i></div>
            </div>
            <div class="menu-btn" onclick="event.stopPropagation(); showContextMenu(event, ${JSON.stringify(song).replace(/"/g, '&quot;')})">
                <i class="fas fa-ellipsis-v"></i>
            </div>
            <h4 onclick="_playFromGrid('${containerId}', ${song.id}, '${contextType || 'grid'}', '${(contextLabel || '').replace(/'/g, "\'")}')">${escapeHtml(song.nombre)}</h4>
            <p  onclick="_playFromGrid('${containerId}', ${song.id}, '${contextType || 'grid'}', '${(contextLabel || '').replace(/'/g, "\'")}')">${escapeHtml(song.artista)}</p>
        </div>
    `).join('');
}

// Helper: reproducir desde un grid usando el registro global
function _playFromGrid(containerId, songId, contextType, label) {
const songs = window._playContextRegistry[containerId];
if (songs && songs.length) {
setContextAndPlay(songs, songId, contextType, label);
} else {
playSong(songId); // fallback
}
}

function renderAlbumes(albumes, containerId) {
const container = document.getElementById(containerId);
if (!container) return;
container.innerHTML = albumes.map(album => `
        <div class="song-card" onclick="verAlbum('${escapeHtml(album.nombre)}')">
            <div class="card-image">
                <img src="${album.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/180x180'">
                <div class="play-overlay"><i class="fas fa-play"></i></div>
            </div>
            <h4>${escapeHtml(album.nombre)}</h4>
            <p>${album.conteo} canciones</p>
        </div>
    `).join('');
}

// ========== VER ÁLBUM (DETALLE) ==========
async function verAlbum(albumNombre) {
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando álbum...</div>';
try {
const response = await fetch(`/api/canciones/album/${encodeURIComponent(albumNombre)}`);
const canciones = await response.json();
if (!canciones.length) { content.innerHTML = '<div class="loading">No se encontraron canciones en este álbum</div>'; return; }
const portada = canciones[0]?.portadaUrl || 'https://singlecolorimage.com/get/1DB954/200x200';
const duracionTotal = canciones.reduce((acc, c) => acc + (c.duracionSegundos || 0), 0);
content.innerHTML = `
            <div style="display:flex; align-items:flex-end; gap:24px; margin-bottom:32px; flex-wrap:wrap; padding:20px; background:linear-gradient(180deg,#2a2a2a,transparent); border-radius:16px">
                <img src="${portada}" style="width:200px; height:200px; border-radius:12px; object-fit:cover; box-shadow:0 8px 32px rgba(0,0,0,0.6);">
                <div>
                    <p style="font-size:12px;text-transform:uppercase;letter-spacing:2px;color:#b3b3b3">Álbum</p>
                    <h1 style="font-size:clamp(24px,4vw,48px); margin:8px 0 4px;">${escapeHtml(albumNombre)}</h1>
                    <p style="color:#b3b3b3;">${canciones[0]?.artista || ''} • ${canciones.length} canciones • ${formatDurationLong(duracionTotal)}</p>
                    <div style="display:flex;gap:12px;margin-top:16px;flex-wrap:wrap">
                        <button onclick="_playFromAlbum('${escapeHtml(albumNombre)}', null)" class="btn-play-big"><i class="fas fa-play"></i> Reproducir</button>
                        <button onclick="loadSmartShuffleQueue(null, '${escapeHtml(canciones[0]?.artista || '')}')" style="background:#282828;border:none;color:#fff;padding:10px 20px;border-radius:24px;cursor:pointer;font-size:13px"><i class="fas fa-magic"></i> Mix del artista</button>
                    </div>
                </div>
            </div>
            <table class="songs-table">
                <thead><tr><th>#</th><th>Título</th><th>Artista</th><th>Duración</th><th></th></tr></thead>
                <tbody>
                    ${canciones.map((song, idx) => `
                        <tr onclick="_playFromAlbum('${escapeHtml(albumNombre).replace(/'/g,"\'")}', ${song.id})">
                            <td class="song-play"><i class="fas fa-play" style="color:#1db954; font-size:12px;"></i></td>
                            <td class="song-title">${escapeHtml(song.nombre)}</td>
                            <td class="song-artist">${escapeHtml(song.artista)}</td>
                            <td class="song-duration">${formatDuration(song.duracionSegundos)}</td>
                            <td><div class="menu-btn" style="position:relative; top:0; right:0; background:none;" onclick="event.stopPropagation(); showContextMenu(event, ${JSON.stringify(song).replace(/"/g, '&quot;')})"><i class="fas fa-ellipsis-v"></i></div></td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>`;
} catch(e) { showToast('Error al cargar el álbum', true); loadHome(); }
}

// ========== VER PLAYLIST ==========
async function loadPlaylistView(id) {
const p = await fetch(`/api/playlists/${id}`).then(r => r.json());
const content = document.getElementById('dynamicContent');
const duracionTotal = (p.canciones || []).reduce((acc, c) => acc + (c.duracionSegundos || 0), 0);
// Registrar canciones para _playFromPlaylist
window._playContextRegistry['pl_' + p.id] = p.canciones || [];
content.innerHTML = `
        <div style="display:flex; align-items:flex-end; gap:24px; margin-bottom:32px; flex-wrap:wrap; padding:20px; background:linear-gradient(180deg,#2a2a2a,transparent); border-radius:16px">
            <img src="${p.portadaUrl}" style="width:200px; height:200px; border-radius:12px; object-fit:cover;">
            <div>
                <p style="font-size:12px;text-transform:uppercase;letter-spacing:2px;color:#b3b3b3">Playlist</p>
                <h1 style="font-size:clamp(24px,4vw,48px); margin:8px 0 4px;">${escapeHtml(p.nombre)}</h1>
                ${p.descripcion ? `<p style="color:#b3b3b3;margin-bottom:4px">${escapeHtml(p.descripcion)}</p>` : ''}
                <p style="color:#b3b3b3;">${p.cancionesCount} canciones • ${formatDurationLong(duracionTotal)}</p>
                <button class="btn-play-big" onclick="_playFromPlaylist(${p.id}, null)" style="margin-top:16px"><i class="fas fa-play"></i> Reproducir</button>
            </div>
        </div>
        <table class="songs-table">
            <thead><tr><th>#</th><th>Título</th><th>Artista</th><th>Duración</th><th></th></tr></thead>
            <tbody>
                ${(p.canciones || []).map((song, idx) => `
                    <tr onclick="_playFromPlaylist(${p.id}, ${song.id})">
                        <td class="song-play"><i class="fas fa-play" style="color:#1db954; font-size:12px;"></i></td>
                        <td class="song-title">${escapeHtml(song.nombre)}</td>
                        <td class="song-artist">${escapeHtml(song.artista)}</td>
                        <td class="song-duration">${formatDuration(song.duracionSegundos)}</td>
                        <td><div class="menu-btn" style="position:relative; top:0; right:0; background:none;" onclick="event.stopPropagation(); showContextMenu(event, ${JSON.stringify(song).replace(/"/g, '&quot;')})"><i class="fas fa-ellipsis-v"></i></div></td>
                    </tr>
                `).join('')}
            </tbody>
        </table>`;
}

// ========== BÚSQUEDA ==========
const searchInput = document.getElementById('searchInput');
const searchDropdown = document.getElementById('searchDropdown');
const searchLoading = document.getElementById('searchLoading');

async function performSpotifySearch(query) {
if (!query || query.trim().length < 2) { searchDropdown.style.display = 'none'; return; }
searchLoading.style.display = 'block';
try {
const response = await fetch(`/api/canciones/buscar?q=${encodeURIComponent(query)}`);
const results = await response.json();
if (!results?.length) {
searchDropdown.innerHTML = `<div class="empty-results"><i class="fas fa-search" style="font-size:32px;margin-bottom:12px;display:block;"></i>No se encontraron resultados para "${escapeHtml(query)}"</div>`;
} else {
searchDropdown.innerHTML = results.slice(0, 8).map(cancion => `
                <div class="result-item" onclick="_playFromSearch(${cancion.id}); searchDropdown.style.display='none';">
                    <img class="result-item-img" src="${cancion.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/48x48'">
                    <div class="result-item-info">
                        <div class="result-item-title">${escapeHtml(cancion.nombre)}</div>
                        <div class="result-item-artist">${escapeHtml(cancion.artista)}</div>
                    </div>
                    <div class="result-item-duration">${formatDuration(cancion.duracionSegundos)}</div>
                </div>
            `).join('');
if (results.length > 8) {
searchDropdown.innerHTML += `<div class="result-item" style="justify-content:center;color:#1db954;" onclick="performSearchWithFilter(); searchDropdown.style.display='none';">Ver todos los ${results.length} resultados</div>`;
}
}
searchDropdown.style.display = 'block';
} catch(e) { console.error(e); } finally { searchLoading.style.display = 'none'; }
}

function debouncedSpotifySearch() {
if (searchTimeout) clearTimeout(searchTimeout);
searchTimeout = setTimeout(() => {
const query = searchInput.value;
state.currentSearchQuery = query;
performSpotifySearch(query);
}, 250);
}

function setupSearchEvents() {
searchInput.addEventListener('input', debouncedSpotifySearch);
searchInput.addEventListener('focus', () => { if (searchInput.value.length >= 2) searchDropdown.style.display = 'block'; });
searchInput.addEventListener('blur', () => setTimeout(() => searchDropdown.style.display = 'none', 200));
}

// ========== FILTROS DE BÚSQUEDA ==========
function setupSearchFilters() {
document.querySelectorAll('.filter-btn').forEach(btn => {
btn.addEventListener('click', () => {
document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
btn.classList.add('active');
state.currentFilter = btn.dataset.filter;
if (state.currentSearchQuery && state.currentSearchQuery.length >= 2) performSearchWithFilter();
else if (!state.currentSearchQuery || state.currentSearchQuery.length < 2) loadHome();
});
});
}

async function performSearchWithFilter() {
const query = document.getElementById('searchInput').value;
state.currentSearchQuery = query;
if (!query || query.length < 2) { loadHome(); return; }
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading">Buscando...</div>';
try {
const response = await fetch(`/api/canciones/buscar?q=${encodeURIComponent(query)}`);
const results = await response.json();
state.currentSearchResults = results;
// Guardar también en el registro para que _playFromSearch los use
window._playContextRegistry['_searchResults'] = results;
displayFilteredResults(results, query);
} catch (e) { console.error('Search error:', e); showToast('Error en búsqueda: ' + (e.message || e), true); }
}

function displayFilteredResults(results, query) {
// Delega a uiux.js si está disponible (versión mejorada estilo Spotify)
if (typeof window._uiuxDisplayResults === 'function') {
window._uiuxDisplayResults(results, query);
return;
}
// Fallback básico
const content = document.getElementById('dynamicContent');
const filter = state.currentFilter;
if (!results?.length) {
content.innerHTML = `<div class="loading"><i class="fas fa-search" style="font-size:48px"></i><br>No se encontraron resultados para "${escapeHtml(query)}"</div>`;
return;
}
let html = `<h2 style="margin-bottom:24px;">Resultados para: "${escapeHtml(query)}"</h2>`;
if (filter === 'all' || filter === 'canciones') {
html += `<div class="section"><div class="section-header"><h3><i class="fas fa-music"></i> Canciones</h3></div><div class="grid" id="cancionesGrid"></div></div>`;
setTimeout(() => renderSongsWithMenu(results, 'cancionesGrid'), 0);
}
if (filter === 'all' || filter === 'albumes') {
const albumesUnicos = [...new Map(results.map(c => [c.album, c])).values()];
html += `<div class="section"><div class="section-header"><h3><i class="fas fa-compact-disc"></i> Álbumes</h3></div><div class="grid" id="albumesGrid"></div></div>`;
setTimeout(() => {
const c = document.getElementById('albumesGrid');
if (c) c.innerHTML = albumesUnicos.map(album => `
                <div class="song-card" onclick="verAlbum('${escapeHtml(album.album)}')">
                    <div class="card-image"><img src="${album.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/180x180'"><div class="play-overlay"><i class="fas fa-play"></i></div></div>
                    <h4>${escapeHtml(album.album)}</h4><p>${escapeHtml(album.artista)}</p>
                </div>`).join('');
}, 0);
}
if (filter === 'all' || filter === 'playlists') {
html += `<div class="section"><div class="section-header"><h3><i class="fas fa-list"></i> Playlists</h3></div><div class="grid" id="playlistsGrid"></div></div>`;
setTimeout(() => {
const c = document.getElementById('playlistsGrid');
if (c) c.innerHTML = state.playlists.map(p => `
                <div class="song-card" onclick="loadPlaylistView(${p.id})">
                    <div class="card-image"><img src="${p.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/180x180'"><div class="play-overlay"><i class="fas fa-play"></i></div></div>
                    <h4>${escapeHtml(p.nombre)}</h4><p>${p.cancionesCount || 0} canciones</p>
                </div>`).join('');
}, 0);
}
content.innerHTML = html;
}

// ========== REPRODUCCIÓN ==========
function getCurrentPlaylist() {
if (state.usingQueue && state.queue.length) return state.queue;
if (state.shuffleMode && state.shuffledSongs.length) return state.shuffledSongs;
return state.allSongs;
}

async function playSong(id) {
try {
const response = await fetch(`/api/canciones/${id}`);
const cancion  = await response.json();
playSongFromObject(cancion);

// Usar el contexto actual para definir qué canciones navegar
// Si no hay contexto definido o es 'all', usar todas
const ctx = state.playContext;
if (ctx && ctx.songs && ctx.songs.length > 0) {
state.allSongs    = ctx.songs;
} else {
const todas = await loadTodasCanciones();
state.allSongs = todas;
}

state.currentIndex = state.allSongs.findIndex(s => s.id === id);
state.usingQueue   = false;

if (state.shuffleMode) {
state.shuffledSongs = [...state.allSongs];
for (let i = state.shuffledSongs.length - 1; i > 0; i--) {
const j = Math.floor(Math.random() * (i + 1));
[state.shuffledSongs[i], state.shuffledSongs[j]] =
[state.shuffledSongs[j], state.shuffledSongs[i]];
}
state.currentIndex = state.shuffledSongs.findIndex(s => s.id === id);
}

const searchDropdown = document.getElementById('searchDropdown');
if (searchDropdown) searchDropdown.style.display = 'none';

} catch(e) { showToast(`Error: ${e.message}`, true); }
}

// Establecer contexto y reproducir — usado por todas las vistas
function setContextAndPlay(songs, songId, contextType, label) {
state.playContext = { type: contextType, songs: [...songs], label: label || contextType };
state.smartQueue  = [];
playSong(songId);
}

// ── Helpers seguros (sin JSON en onclick) ──

// Reproducir desde álbum — directo, sin cola, en orden del álbum
async function _playFromAlbum(albumNombre, songId) {
let canciones = cache.albumesDetalle?.get(albumNombre);
if (!canciones || !canciones.length) {
canciones = await fetch(`/api/canciones/album/${encodeURIComponent(albumNombre)}`).then(r => r.json());
if (!cache.albumesDetalle) cache.albumesDetalle = new Map();
cache.albumesDetalle.set(albumNombre, canciones);
}
if (!canciones.length) { showToast('Álbum vacío', true); return; }
const startId = songId || canciones[0].id;
setContextAndPlay(canciones, startId, 'album', albumNombre);
}

// Reproducir desde playlist — directo, sin pasar por la cola
async function _playFromPlaylist(playlistId, songId) {
const key = 'pl_' + playlistId;
let canciones = window._playContextRegistry[key];
if (!canciones || !canciones.length) {
const p = await fetch(`/api/playlists/${playlistId}`).then(r => r.json());
canciones = p.canciones || [];
window._playContextRegistry[key] = canciones;
}
if (!canciones.length) { showToast('La playlist está vacía', true); return; }
// Shuffle suave al iniciar (no siempre la primera)
const startSong = songId
    ? canciones.find(c => c.id === songId) || canciones[0]
    : canciones[0];
setContextAndPlay(canciones, startSong.id, 'playlist', 'Playlist');
}

// Reproducir desde búsqueda — activa radio inteligente
function _playFromSearch(songId) {
const results = state.currentSearchResults || [];
// Contexto 'search' → al terminar activa radio inteligente
if (results.length > 0) {
setContextAndPlay(results, songId, 'search', 'Búsqueda');
} else {
// Sin resultados cacheados, reproducir directamente con radio
state.playContext = { type: 'search', songs: [], label: 'Búsqueda' };
state.smartQueue  = [];
playSong(songId);
}
}

function togglePlayPause() {
if (audio.paused) {
const p = audio.play();
if (p) p
    .then(() => { document.getElementById('playPauseIcon').className = 'fas fa-pause'; })
    .catch(err => {
if (err.name !== 'AbortError') {
console.error('togglePlayPause error:', err.name);
}
});
} else {
audio.pause();
document.getElementById('playPauseIcon').className = 'fas fa-play';
}
}

function nextSong() {
if (state.usingQueue && state.queue.length) {
if (state.queueIndex < state.queue.length - 1) playFromQueue(state.queueIndex + 1);
else if (state.repeatMode === 2 && state.queue.length) playFromQueue(0);
return;
}
const playlist = getCurrentPlaylist();
if (state.repeatMode === 1 && state.currentSong) playSong(state.currentSong.id);
else if (state.repeatMode === 2) {
if (state.currentIndex === playlist.length - 1) playSong(playlist[0].id);
else if (state.currentIndex < playlist.length - 1) playSong(playlist[state.currentIndex + 1].id);
} else {
if (state.currentIndex < playlist.length - 1) playSong(playlist[state.currentIndex + 1].id);
}
}

function prevSong() {
if (state.usingQueue && state.queue.length) {
if (state.queueIndex > 0) playFromQueue(state.queueIndex - 1);
return;
}
const playlist = getCurrentPlaylist();
if (state.currentIndex > 0) playSong(playlist[state.currentIndex - 1].id);
}

function toggleRepeatMode() {
state.repeatMode = (state.repeatMode + 1) % 3;
const repeatBtn = document.getElementById('repeatBtn');
if (state.repeatMode === 0) { repeatBtn.classList.remove('active'); repeatBtn.removeAttribute('data-mode'); showToast('Repetición desactivada'); }
else if (state.repeatMode === 1) { repeatBtn.classList.add('active'); repeatBtn.setAttribute('data-mode', 'one'); showToast('Repetir una vez ✓'); }
else { repeatBtn.classList.add('active'); repeatBtn.removeAttribute('data-mode'); showToast('Repetir todas ↻'); }
}

function toggleShuffle() {
state.shuffleMode = !state.shuffleMode;
const shuffleBtn = document.getElementById('shuffleBtn');
if (state.shuffleMode) {
shuffleBtn.classList.add('active');
state.shuffledSongs = [...state.allSongs];
for (let i = state.shuffledSongs.length - 1; i > 0; i--) {
const j = Math.floor(Math.random() * (i + 1));
[state.shuffledSongs[i], state.shuffledSongs[j]] = [state.shuffledSongs[j], state.shuffledSongs[i]];
}
if (state.currentSong) state.currentIndex = state.shuffledSongs.findIndex(s => s.id === state.currentSong.id);
showToast('Aleatorio activado');
} else {
shuffleBtn.classList.remove('active');
if (state.currentSong) state.currentIndex = state.allSongs.findIndex(s => s.id === state.currentSong.id);
showToast('Aleatorio desactivado');
}
}

// ========== CONTROL DE VOLUMEN ==========
let lastVolume = 70;
function setupVolumeControl() {
const volumeSlider = document.getElementById('volumeSlider');
audio.volume = 0.7;
updateVolumeUI(70);
volumeSlider.addEventListener('click', (e) => {
const rect = volumeSlider.getBoundingClientRect();
const volume = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
audio.volume = volume;
const pct = Math.round(volume * 100);
updateVolumeUI(pct);
lastVolume = pct;
});
}

function updateVolumeUI(volume) {
document.getElementById('volumeFill').style.width = volume + '%';
const icon = document.getElementById('volumeIcon');
if (volume === 0) icon.className = 'fas fa-volume-mute';
else if (volume < 30) icon.className = 'fas fa-volume-down';
else icon.className = 'fas fa-volume-up';
}

function toggleMute() {
if (audio.volume > 0) { lastVolume = audio.volume * 100; audio.volume = 0; updateVolumeUI(0); }
else { audio.volume = lastVolume / 100; updateVolumeUI(lastVolume); }
}

// ========== BARRA DE PROGRESO ==========
function setupProgressBar() {
const wrapper = document.getElementById('progressBarWrapper');
wrapper.addEventListener('click', (e) => {
if (audio.duration) {
const rect = wrapper.getBoundingClientRect();
audio.currentTime = ((e.clientX - rect.left) / rect.width) * audio.duration;
}
});
audio.addEventListener('timeupdate', () => {
if (audio.duration) {
document.getElementById('progressFill').style.width = (audio.currentTime / audio.duration * 100) + '%';
document.getElementById('currentTime').innerText = formatTime(audio.currentTime);
}
});
audio.addEventListener('loadedmetadata', () => { document.getElementById('duration').innerText = formatTime(audio.duration); });
audio.addEventListener('ended', () => { if (state.repeatMode === 1) { audio.currentTime = 0; audio.play(); } else nextSong(); });
}

function formatTime(sec) {
if (isNaN(sec)) return '0:00';
return `${Math.floor(sec / 60)}:${Math.floor(sec % 60).toString().padStart(2, '0')}`;
}

// ========== FAVORITOS ==========
async function toggleFavorito(id, element) {
try {
const response = await fetch(`/api/canciones/${id}/favorito`, { method: 'PUT' });
const updated = await response.json();
if (state.currentSong?.id === id) {
const heart = document.getElementById('favoriteHeart');
updated.favorito ? heart.classList.add('heart-active') : heart.classList.remove('heart-active');
state.currentSong.favorito = updated.favorito;
}
cache.favoritos = null;
showToast(updated.favorito ? '❤️ Agregado a favoritos' : '💔 Eliminado de favoritos');
} catch(e) { showToast('Error', true); }
}

async function toggleCurrentFavorite() { if (state.currentSong) await toggleFavorito(state.currentSong.id, null); }

// ========== PLAYLISTS (AGREGAR) ==========
async function agregarAPlaylist(cancionId, playlistId, playlistNombre) {
try {
await fetch(`/api/playlists/${playlistId}/canciones/${cancionId}`, { method: 'POST' });
showToast(`✅ Agregado a "${playlistNombre}"`);
document.querySelector('.modal')?.remove();
cache.playlists = null;
loadPlaylists();
} catch(e) { showToast('Error', true); }
}

async function crearPlaylistYAgregar(cancionId) {
const nombre = prompt('Nombre de la nueva playlist:');
if (nombre?.trim()) {
const response = await fetch('/api/playlists', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ nombre: nombre.trim() }) });
const playlist = await response.json();
await agregarAPlaylist(cancionId, playlist.id, nombre);
}
document.querySelector('.modal')?.remove();
}

// ========== LETRA DINÁMICA ==========
let lyricsModal = null, lyricsLines = [], lyricsUpdateInterval = null, currentLyricsLine = -1, lyricsOffset = 0;

function parseLrc(lrcContent) {
if (!lrcContent) return [];
const lines = [];
const regex = /\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)/g;
let match;
while ((match = regex.exec(lrcContent)) !== null) {
const minutes = parseInt(match[1]), seconds = parseInt(match[2]);
let millis = match[3] ? parseInt(match[3]) : 0;
if (millis > 99) millis = millis / 1000; else millis = millis / 100;
const time = minutes * 60 + seconds + millis;
let text = match[4].trim().replace(/\[.*?\]/g, '').trim();
if (text) lines.push({ time, text });
}
return lines.sort((a, b) => a.time - b.time);
}

function plainToLines(plainLyrics) {
if (!plainLyrics) return [];
const textLines = plainLyrics.split('\n').filter(l => l.trim());
const timePerLine = (audio.duration || textLines.length * 4) / textLines.length;
return textLines.map((line, idx) => ({ time: idx * timePerLine, text: line.trim() }));
}

async function openLyricsModal() {
if (!state.currentSong) { showToast('No hay canción reproduciéndose', true); return; }
const lyricsData = await loadLyrics(state.currentSong.id);
let lines = [];
if (lyricsData.letraSincronizada?.trim()) lines = parseLrc(lyricsData.letraSincronizada);
else if (lyricsData.letra?.trim()) lines = plainToLines(lyricsData.letra);
lyricsLines = lines;
lyricsModal = document.createElement('div');
lyricsModal.className = 'lyrics-modal';
lyricsModal.innerHTML = `
        <div class="lyrics-container">
            <div class="lyrics-left">
                <img class="album-art" src="${state.currentSong.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/200x200'">
                <h2 class="song-title">${escapeHtml(state.currentSong.nombre)}</h2>
                <p class="artist-name">${escapeHtml(state.currentSong.artista)}</p>
                <div class="lyrics-controls">
                    <button onclick="toggleShuffleFromModal()" id="modalShuffleBtn"><i class="fas fa-random"></i></button>
                    <button onclick="prevSongFromModal()"><i class="fas fa-step-backward"></i></button>
                    <button class="play-pause-btn" onclick="togglePlayPauseFromModal()"><i id="modalPlayIcon" class="fas ${audio.paused ? 'fa-play' : 'fa-pause'}"></i></button>
                    <button onclick="nextSongFromModal()"><i class="fas fa-step-forward"></i></button>
                    <button onclick="toggleRepeatFromModal()" id="modalRepeatBtn"><i class="fas fa-redo-alt"></i></button>
                </div>
            </div>
            <div class="lyrics-right">
                <div class="lyrics-header-actions">
                    <button class="upload-lrc-btn" onclick="uploadLrcFileFromModal()"><i class="fas fa-upload"></i> Subir LRC</button>
                    <button class="close-lyrics-btn" onclick="closeLyricsModal()"><i class="fas fa-times"></i> Cerrar</button>
                </div>
                <div class="lyrics-content-scroll" id="lyricsContentScroll">
                    ${lines.length ? lines.map(line => `<div class="lyrics-line" onclick="seekToTime(${line.time})">${escapeHtml(line.text)}</div>`).join('') : '<div class="no-lyrics"><i class="fas fa-music"></i><br>No hay letra disponible</div>'}
                </div>
                <div class="offset-control"><label><i class="fas fa-sliders-h"></i> Sincronización: <input type="range" min="-3" max="3" step="0.1" value="0" oninput="updateOffsetFromModal(this.value)"><span id="offsetValueModal">0.0s</span></label></div>
            </div>
        </div>`;
document.body.appendChild(lyricsModal);
if (lines.length) { updateCurrentLyricsLineFromModal(); lyricsUpdateInterval = setInterval(updateCurrentLyricsLineFromModal, 150); }
}

function updateCurrentLyricsLineFromModal() {
if (!lyricsLines.length) return;
const currentTime = audio.currentTime + lyricsOffset;
let activeIndex = -1;
for (let i = 0; i < lyricsLines.length; i++) { if (currentTime >= lyricsLines[i].time) activeIndex = i; else break; }
if (activeIndex !== currentLyricsLine) {
currentLyricsLine = activeIndex;
const lines = document.querySelectorAll('.lyrics-line');
lines.forEach((line, idx) => { line.classList.remove('active', 'past'); if (idx === activeIndex) line.classList.add('active'); else if (idx < activeIndex) line.classList.add('past'); });
if (activeIndex >= 0) document.querySelector('.lyrics-line.active')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
}

function updateOffsetFromModal(value) { lyricsOffset = parseFloat(value); document.getElementById('offsetValueModal').innerText = lyricsOffset.toFixed(1) + 's'; updateCurrentLyricsLineFromModal(); }
function seekToTime(time) { audio.currentTime = Math.max(0, time); }
function closeLyricsModal() { if (lyricsUpdateInterval) clearInterval(lyricsUpdateInterval); if (lyricsModal) lyricsModal.remove(); lyricsModal = null; currentLyricsLine = -1; }
function togglePlayPauseFromModal() { togglePlayPause(); document.getElementById('modalPlayIcon').className = `fas ${audio.paused ? 'fa-play' : 'fa-pause'}`; }
function prevSongFromModal() { prevSong(); setTimeout(() => { if (lyricsModal && state.currentSong) refreshLyricsModal(); }, 200); }
function nextSongFromModal() { nextSong(); setTimeout(() => { if (lyricsModal && state.currentSong) refreshLyricsModal(); }, 200); }
function toggleShuffleFromModal() { toggleShuffle(); refreshLyricsModal(); }
function toggleRepeatFromModal() { toggleRepeatMode(); refreshLyricsModal(); }
async function refreshLyricsModal() { if (lyricsModal && state.currentSong) { closeLyricsModal(); await openLyricsModal(); } }

function uploadLrcFileFromModal() {
if (!state.currentSong) { showToast('No hay canción seleccionada', true); return; }
const input = document.createElement('input');
input.type = 'file';
input.accept = '.lrc';
input.onchange = async function(e) {
const file = e.target.files[0];
if (!file) return;
if (!file.name.toLowerCase().endsWith('.lrc')) { showToast('Solo archivos .lrc', true); input.remove(); return; }
const formData = new FormData();
formData.append('file', file);
try {
const response = await fetch(`/api/letras/subir/${state.currentSong.id}`, { method: 'POST', body: formData });
const result = await response.json();
if (response.ok) {
showToast(result.message || 'Letra subida correctamente');
cache.letras.delete(state.currentSong.id);
if (lyricsModal) { closeLyricsModal(); setTimeout(() => openLyricsModal(), 500); }
} else { showToast(result.error || 'Error al subir la letra', true); }
} catch (error) { showToast('Error de conexión', true); }
input.remove();
};
document.body.appendChild(input);
input.click();
}

// ========== NAVEGACIÓN ==========
function setActiveNav(view) {
document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
const navItem = document.querySelector(`.nav-item[data-view="${view}"]`);
if (navItem) navItem.classList.add('active');
}

function setupNavigation() {
document.querySelectorAll('.nav-item').forEach(item => {
item.addEventListener('click', () => {
const view = item.dataset.view;
document.querySelectorAll('.nav-item').forEach(nav => nav.classList.remove('active'));
item.classList.add('active');
if (view === 'home') loadHome();
else if (view === 'favorites') (window.loadLikedSongsView || loadFavoritesView)();
else if (view === 'library') window.loadLibraryView?.();
else if (view === 'artistas') loadArtistasView();
else if (view === 'estadisticas') loadEstadisticasView();
});
});
}

// ========== INICIALIZAR ==========
setupNavigation();
setupSearchFilters();
setupSearchEvents();
loadPlaylists();
loadHome();
setupProgressBar();
setupVolumeControl();

document.addEventListener('click', closeContextMenu);
document.addEventListener('keydown', (e) => {
if (e.key === 'Escape') {
if (lyricsModal) closeLyricsModal();
if (document.getElementById('queuePanel')?.classList.contains('open')) document.getElementById('queuePanel').classList.remove('open');
searchDropdown.style.display = 'none';
}
if (e.key === ' ' && e.target.tagName !== 'INPUT' && e.target.tagName !== 'TEXTAREA') {
e.preventDefault();
togglePlayPause();
}
});

// =============================================
// EXPORTAR FUNCIONES CLAVE A WINDOW
// Permite que uiux.js, lyrics_apple.js y spotify_views.js
// puedan referenciar y sobreescribir estas funciones
// =============================================
window.playSongFromObject    = playSongFromObject;
window.playSong              = playSong;
window.loadLyrics            = loadLyrics;
window.loadPlaylists         = loadPlaylists;
window.loadHome              = loadHome;
window.loadFavoritesView     = loadFavoritesView;
window.loadArtistasView      = loadArtistasView;
window.loadEstadisticasView  = loadEstadisticasView;
window.loadArtistaDetalle    = loadArtistaDetalle;
window.loadPlaylistView      = loadPlaylistView;
window.verAlbum              = verAlbum;
window.loadSmartShuffleQueue = loadSmartShuffleQueue;
window.loadMixDiario         = loadMixDiario;
window.addToQueue            = addToQueue;
window.addMultipleToQueue    = addMultipleToQueue;
window.renderQueue           = renderQueue;
window.toggleFavorito        = toggleFavorito;
window.toggleCurrentFavorite = toggleCurrentFavorite;
window.toggleShuffle         = toggleShuffle;
window.toggleRepeatMode      = toggleRepeatMode;
window.togglePlayPause       = togglePlayPause;
window.nextSong              = nextSong;
window.prevSong              = prevSong;
window.playFromQueue         = playFromQueue;
window.renderSongsWithMenu   = renderSongsWithMenu;
window.renderArtistasGrid    = renderArtistasGrid;
window.renderAlbumes         = renderAlbumes;
window.openCreatePlaylistModal = openCreatePlaylistModal;
window.openLyricsModal       = openLyricsModal;
window.closeLyricsModal      = closeLyricsModal;
window.showContextMenu       = showContextMenu;
window.closeContextMenu      = closeContextMenu;
window.setActiveNav          = setActiveNav;
window.formatDuration        = formatDuration;
window.formatDurationLong    = formatDurationLong;
window.formatNumber          = formatNumber;
window.escapeHtml            = escapeHtml;
window.showToast             = showToast;
window.setContextAndPlay     = setContextAndPlay;
window._playFromGrid         = _playFromGrid;
window._playFromAlbum        = _playFromAlbum;
window._playFromPlaylist      = _playFromPlaylist;
window._playFromSearch       = _playFromSearch;