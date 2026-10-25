// ========== GESTIÓN DE PLAYLISTS ==========

async function loadPlaylists() {
    const data = await loadData('/api/playlists', 'playlists');
    state.playlists = data;
    renderPlaylistsSidebar();
    return data;
}

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
    if (!nombre?.trim()) {
        showToast('El nombre es requerido', true);
        return;
    }
    const formData = new FormData();
    formData.append('nombre', nombre.trim());
    if (descripcion) formData.append('descripcion', descripcion);
    if (file) formData.append('file', file);
    try {
        const response = await fetch('/api/playlists/con-imagen', { method: 'POST', body: formData });
        if (response.ok) {
            showToast('✅ Playlist creada con imagen');
            document.querySelector('.create-playlist-modal')?.remove();
            await loadPlaylists();
        } else {
            showToast('Error al crear playlist', true);
        }
    } catch(e) {
        showToast('Error al crear playlist', true);
    }
}

async function agregarAPlaylist(cancionId, playlistId, playlistNombre) {
    try {
        await fetch(`/api/playlists/${playlistId}/canciones/${cancionId}`, { method: 'POST' });
        showToast(`✅ Agregado a "${playlistNombre}"`);
        document.querySelector('.modal')?.remove();
        loadPlaylists();
    } catch(e) {
        showToast('Error', true);
    }
}

async function crearPlaylistYAgregar(cancionId) {
    const nombre = prompt('Nombre de la nueva playlist:');
    if (nombre?.trim()) {
        const response = await fetch('/api/playlists', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ nombre: nombre.trim() })
        });
        const playlist = await response.json();
        await agregarAPlaylist(cancionId, playlist.id, nombre);
    }
    document.querySelector('.modal')?.remove();
}