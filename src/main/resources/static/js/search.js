// ========== BÚSQUEDA ==========

const searchInput = document.getElementById('searchInput');
const searchDropdown = document.getElementById('searchDropdown');
const searchLoading = document.getElementById('searchLoading');

async function performSpotifySearch(query) {
    if (!query || query.trim().length < 2) {
        searchDropdown.style.display = 'none';
        return;
    }
    searchLoading.style.display = 'block';
    try {
        const response = await fetch(`/api/canciones/buscar?q=${encodeURIComponent(query)}`);
        const results = await response.json();
        if (!results?.length) {
            searchDropdown.innerHTML = `<div class="empty-results"><i class="fas fa-search" style="font-size:32px;margin-bottom:12px;display:block;"></i>No se encontraron resultados para "${escapeHtml(query)}"</div>`;
        } else {
            searchDropdown.innerHTML = results.slice(0, 8).map(cancion => `
                <div class="result-item" onclick="playSong(${cancion.id}); searchDropdown.style.display='none';">
                    <img class="result-item-img" src="${cancion.portadaUrl}" onerror="this.src='/api/portada/${encodeURIComponent(cancion.nombre)}'">
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
    } catch(e) {
        console.error(e);
    } finally {
        searchLoading.style.display = 'none';
    }
}

function debouncedSpotifySearch() {
    if (searchTimeout) clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
        const query = searchInput.value;
        state.currentSearchQuery = query;
        performSpotifySearch(query);
    }, 250);
}

async function loadSearchHistory() {
    try {
        const res = await fetch('/api/canciones/historial');
        const history = await res.json();
        searchDropdown.innerHTML = history.map(q => `
            <div class="result-item" onclick="buscarDesdeHistorial('${q}')">
                <i class="fas fa-history"></i>
                <span>${q}</span>
            </div>
        `).join('');
        searchDropdown.style.display = 'block';
    } catch(e) {}
}

function buscarDesdeHistorial(q) {
    document.getElementById('searchInput').value = q;
    state.currentSearchQuery = q;
    performSearchWithFilter();
}

function setupSearchEvents() {
    searchInput.addEventListener('input', debouncedSpotifySearch);
    searchInput.addEventListener('focus', () => {
        if (searchInput.value.length >= 2) searchDropdown.style.display = 'block';
        else loadSearchHistory();
    });
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
    if (!query || query.length < 2) {
        loadHome();
        return;
    }
    const content = document.getElementById('dynamicContent');
    content.innerHTML = '<div class="loading">Buscando...</div>';
    try {
        const response = await fetch(`/api/canciones/buscar?q=${encodeURIComponent(query)}`);
        const results = await response.json();
        state.currentSearchResults = results;
        displayFilteredResults(results, query);
    } catch(e) {
        showToast('Error en búsqueda', true);
    }
}

function displayFilteredResults(results, query) {
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
            const albumesContainer = document.getElementById('albumesGrid');
            if (albumesContainer) {
                albumesContainer.innerHTML = albumesUnicos.map(album => `
                    <div class="song-card" onclick="verAlbum('${escapeHtml(album.album)}')">
                        <div class="card-image"><img src="${album.portadaUrl}" onerror="this.src='/api/portada/${encodeURIComponent(album.album)}'"><div class="play-overlay"><i class="fas fa-play"></i></div></div>
                        <h4>${escapeHtml(album.album)}</h4><p>${escapeHtml(album.artista)}</p>
                    </div>
                `).join('');
            }
        }, 0);
    }
    if (filter === 'all' || filter === 'playlists') {
        html += `<div class="section"><div class="section-header"><h3><i class="fas fa-list"></i> Playlists</h3></div><div class="grid" id="playlistsGrid"></div></div>`;
        setTimeout(() => {
            const playlistsContainer = document.getElementById('playlistsGrid');
            if (playlistsContainer) {
                playlistsContainer.innerHTML = state.playlists.map(p => `
                    <div class="song-card" onclick="loadPlaylistView(${p.id})">
                        <div class="card-image"><img src="${p.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/180x180'"><div class="play-overlay"><i class="fas fa-play"></i></div></div>
                        <h4>${escapeHtml(p.nombre)}</h4><p>${p.cancionesCount || 0} canciones</p>
                    </div>
                `).join('');
            }
        }, 0);
    }
    content.innerHTML = html;
}