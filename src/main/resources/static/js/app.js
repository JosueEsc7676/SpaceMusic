// ========== INICIALIZACIÓN DE LA APLICACIÓN ==========

function initApp() {
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
            if (document.getElementById('queuePanel')?.classList.contains('open')) {
                document.getElementById('queuePanel').classList.remove('open');
            }
            const searchDropdown = document.getElementById('searchDropdown');
            if (searchDropdown) searchDropdown.style.display = 'none';
        }
    });
}

// Iniciar cuando el DOM esté listo
document.addEventListener('DOMContentLoaded', initApp);