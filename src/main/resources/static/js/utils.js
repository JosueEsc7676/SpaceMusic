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

function formatTime(sec) {
    if (isNaN(sec)) return '0:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
}

let currentContextMenu = null;

function closeContextMenu() {
    if (currentContextMenu) {
        currentContextMenu.remove();
        currentContextMenu = null;
    }
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