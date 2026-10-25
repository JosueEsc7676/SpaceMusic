// ========== INTERFAZ DE USUARIO ==========

// ========== MENÚ CONTEXTUAL ==========
function showContextMenu(event, song) {
    event.stopPropagation();
    closeContextMenu();

    const menu = document.createElement('div');
    menu.className = 'context-menu';
    menu.style.left = `${event.clientX}px`;
    menu.style.top = `${event.clientY}px`;

    menu.innerHTML = `
        <div class="context-menu-item" onclick="agregarAFavoritos(${song.id}); closeContextMenu();">
            <i class="fas ${song.favorito ? 'fa-heart' : 'fa-heart'}"></i>
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
modal.innerHTML = `
        <div class="modal-content">
            <h3>Agregar a Playlist</h3>
            ${state.playlists.map(p => `
                <div class="playlist-option" onclick="agregarAPlaylist(${cancionId}, ${p.id}, '${escapeHtml(p.nombre)}')">
                    <img src="${p.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'">
                    <span>${escapeHtml(p.nombre)} (${p.cancionesCount})</span>
                </div>
            `).join('')}
            <hr>
            <div class="playlist-option" onclick="crearPlaylistYAgregar(${cancionId})">
                <i class="fas fa-plus-circle" style="color:#1db954;"></i> Crear nueva playlist
            </div>
            <button onclick="this.closest('.modal').remove()" style="margin-top:16px; padding:8px; background:#333; border:none; border-radius:8px; color:#fff; width:100%;">Cancelar</button>
        </div>
    `;
document.body.appendChild(modal);
modal.onclick = (e) => { if (e.target === modal) modal.remove(); };
}

function irAlArtista(artista) {
document.getElementById('searchInput').value = artista;
state.currentSearchQuery = artista;
performSearchWithFilter();
}

function irAlAlbum(album) {
document.getElementById('searchInput').value = album;
state.currentSearchQuery = album;
performSearchWithFilter();
}

// ========== RENDERIZADO DE CANCIONES ==========
function renderSongsWithMenu(songs, containerId) {
const container = document.getElementById(containerId);
if (!container || !songs) return;
container.innerHTML = songs.map(song => `
        <div class="song-card">
            <div class="card-image" onclick="playSong(${song.id})">
                <img src="${song.portadaUrl}" onerror="this.src='/api/portada/${encodeURIComponent(song.nombre)}'">
                <div class="play-overlay"><i class="fas fa-play"></i></div>
            </div>
            <div class="menu-btn" onclick="event.stopPropagation(); showContextMenu(event, ${JSON.stringify(song).replace(/"/g, '&quot;')})">
                <i class="fas fa-ellipsis-v"></i>
            </div>
            <h4 onclick="playSong(${song.id})">${escapeHtml(song.nombre)}</h4>
            <p onclick="playSong(${song.id})">${escapeHtml(song.artista)}</p>
        </div>
    `).join('');
}

function renderAlbumes(albumes, containerId) {
const container = document.getElementById(containerId);
if (!container) return;
container.innerHTML = albumes.map(album => `
        <div class="song-card" onclick="verAlbum('${escapeHtml(album.nombre)}')">
            <div class="card-image">
                <img src="${album.portadaUrl}" onerror="this.src='/api/portada/${encodeURIComponent(album.nombre)}'">
                <div class="play-overlay"><i class="fas fa-play"></i></div>
            </div>
            <h4>${escapeHtml(album.nombre)}</h4>
            <p>${album.conteo} canciones</p>
        </div>
    `).join('');
}

// ========== VISTAS PRINCIPALES ==========
async function loadHome() {
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando...</div>';
const [top, favoritos, albumes] = await Promise.all([loadTop(), loadFavoritos(), loadAlbumes()]);
let html = '';
if (top?.length) {
html += `
            <div class="section">
                <div class="section-header">
                    <h2>🔥 Más reproducidas</h2>
                    <button class="action-btn" onclick="addMultipleToQueue(${JSON.stringify(top).replace(/"/g, '&quot;')})" style="background:#1db954;color:#000;padding:8px 16px;border-radius:20px;font-size:12px">
                        <i class="fas fa-list"></i> Agregar todas a cola
                    </button>
                </div>
                <div class="grid" id="topGrid"></div>
            </div>
        `;
}
if (favoritos?.length) {
html += `
            <div class="section">
                <div class="section-header"><h2>❤️ Tus favoritas</h2></div>
                <div class="grid" id="favGrid"></div>
            </div>
        `;
}
if (albumes?.length) {
html += `
            <div class="section">
                <div class="section-header"><h2>📀 Álbumes destacados</h2></div>
                <div class="grid" id="albumGrid"></div>
            </div>
        `;
}
content.innerHTML = html;
if (top?.length) renderSongsWithMenu(top, 'topGrid');
if (favoritos?.length) renderSongsWithMenu(favoritos, 'favGrid');
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
                <button class="action-btn" onclick="addMultipleToQueue(${JSON.stringify(favoritos).replace(/"/g, '&quot;')})" style="background:#1db954;color:#000;padding:8px 16px;border-radius:20px;font-size:12px">
                    <i class="fas fa-list"></i> Agregar todas a cola
                </button>
            </div>
            <div class="grid" id="favoritesGrid"></div>
        </div>
    `;
renderSongsWithMenu(favoritos, 'favoritesGrid');
}

// ========== VER ÁLBUM ==========
async function verAlbum(albumNombre) {
const content = document.getElementById('dynamicContent');
content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando álbum...</div>';
try {
const response = await fetch(`/api/canciones/album/${encodeURIComponent(albumNombre)}`);
const canciones = await response.json();
if (!canciones.length) {
content.innerHTML = '<div class="loading">No se encontraron canciones en este álbum</div>';
return;
}
const portada = canciones[0]?.portadaUrl || `/api/portada/${encodeURIComponent(albumNombre)}`;
content.innerHTML = `
            <div style="display:flex; align-items:center; gap:24px; margin-bottom:32px; flex-wrap:wrap;">
                <img src="${portada}" style="width:200px; height:200px; border-radius:16px; object-fit:cover; box-shadow:0 8px 24px rgba(0,0,0,0.5);">
                <div>
                    <h1 style="font-size:48px; margin-bottom:8px;">${escapeHtml(albumNombre)}</h1>
                    <p style="color:#b3b3b3;">${canciones.length} canciones</p>
                    <button class="action-btn" onclick="addMultipleToQueue(${JSON.stringify(canciones).replace(/"/g, '&quot;')})" style="background:#1db954;color:#000;padding:8px 16px;border-radius:20px;font-size:12px;margin-top:12px">
                        <i class="fas fa-list"></i> Agregar álbum a cola
                    </button>
                </div>
            </div>
            <table class="songs-table">
                <thead><tr><th>#</th><th>Título</th><th>Artista</th><th>Duración</th><th></th></tr></thead>
                <tbody>
                    ${canciones.map((song, idx) => `
                        <tr onclick="playSong(${song.id})">
                            <td class="song-play"><i class="fas fa-play" style="color:#1db954; font-size:12px;"></i></td>
                            <td class="song-title">${escapeHtml(song.nombre)}</td>
                            <td class="song-artist">${escapeHtml(song.artista)}</td>
                            <td class="song-duration">${formatDuration(song.duracionSegundos)}</td>
                            <td><div class="menu-btn" style="position:relative; top:0; right:0; background:none;" onclick="event.stopPropagation(); showContextMenu(event, ${JSON.stringify(song).replace(/"/g, '&quot;')})"><i class="fas fa-ellipsis-v"></i></div></td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>
        `;
} catch(e) {
showToast('Error al cargar el álbum', true);
loadHome();
}
}

// ========== VER PLAYLIST ==========
async function loadPlaylistView(id) {
const p = await fetch(`/api/playlists/${id}`).then(r => r.json());
const content = document.getElementById('dynamicContent');
content.innerHTML = `
        <div style="display:flex; align-items:center; gap:24px; margin-bottom:32px; flex-wrap:wrap;">
            <img src="${p.portadaUrl}" style="width:200px; height:200px; border-radius:16px; object-fit:cover;">
            <div>
                <h1 style="font-size:48px; margin-bottom:8px;">${escapeHtml(p.nombre)}</h1>
                <p style="color:#b3b3b3;">${p.descripcion || ''}</p>
                <p style="color:#b3b3b3;">${p.cancionesCount} canciones</p>
                <button class="action-btn" onclick="addMultipleToQueue(${JSON.stringify(p.canciones || []).replace(/"/g, '&quot;')})" style="background:#1db954;color:#000;padding:8px 16px;border-radius:20px;font-size:12px;margin-top:12px">
                    <i class="fas fa-list"></i> Agregar playlist a cola
                </button>
            </div>
        </div>
        <table class="songs-table">
            <thead><tr><th>#</th><th>Título</th><th>Artista</th><th>Duración</th><th></th></tr></thead>
            <tbody>
                ${(p.canciones || []).map((song, idx) => `
                    <tr onclick="playSong(${song.id})">
                        <td class="song-play"><i class="fas fa-play" style="color:#1db954; font-size:12px;"></i></td>
                        <td class="song-title">${escapeHtml(song.nombre)}</td>
                        <td class="song-artist">${escapeHtml(song.artista)}</td>
                        <td class="song-duration">${formatDuration(song.duracionSegundos)}</td>
                        <td><div class="menu-btn" style="position:relative; top:0; right:0; background:none;" onclick="event.stopPropagation(); showContextMenu(event, ${JSON.stringify(song).replace(/"/g, '&quot;')})"><i class="fas fa-ellipsis-v"></i></div></td>
                    </tr>
                `).join('')}
            </tbody>
        </table>
    `;
}

// ========== NAVEGACIÓN ==========
function setupNavigation() {
document.querySelectorAll('.nav-item').forEach(item => {
item.addEventListener('click', () => {
const view = item.dataset.view;
document.querySelectorAll('.nav-item').forEach(nav => nav.classList.remove('active'));
item.classList.add('active');
if (view === 'home') loadHome();
else if (view === 'favorites') loadFavoritesView();
});
});
}