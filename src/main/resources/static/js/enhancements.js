/* =====================================================
   enhancements.js
   Agregar ÚLTIMO en index.html:
   <script src="/js/enhancements.js"></script>
   ===================================================== */

// =====================================================
// 1. TRANSICIONES DE PÁGINA — fade + slide
// =====================================================
(function setupPageTransitions() {
    const content = document.getElementById('dynamicContent');
    if (!content) return;

    // Inyectar CSS de transición
    const style = document.createElement('style');
    style.textContent = `
        #dynamicContent {
            transition: opacity 0.22s ease, transform 0.22s ease;
        }
        #dynamicContent.page-exit {
            opacity: 0;
            transform: translateY(10px);
            pointer-events: none;
        }
        #dynamicContent.page-enter {
            opacity: 0;
            transform: translateY(-8px);
        }
        #dynamicContent.page-enter-active {
            opacity: 1;
            transform: translateY(0);
        }
    `;
    document.head.appendChild(style);

    // Helper global para hacer transición al cambiar contenido
    window.navigateTo = function(loadFn) {
        content.classList.add('page-exit');
        setTimeout(async () => {
            content.classList.remove('page-exit');
            content.classList.add('page-enter');
            await loadFn();
            requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                    content.classList.remove('page-enter');
                    content.classList.add('page-enter-active');
                    setTimeout(() => content.classList.remove('page-enter-active'), 250);
                });
            });
        }, 180);
    };

    // Parchear todas las funciones de carga de vistas
    const navFns = [
        'loadHome','loadFavoritesView','loadLikedSongsView','loadArtistasView',
        'loadEstadisticasView','loadLibraryView','loadArtistaDetalle',
        'verAlbum','loadPlaylistView'
    ];

    navFns.forEach(fnName => {
        const orig = window[fnName];
        if (typeof orig !== 'function') return;
        window[fnName] = function(...args) {
            // No animar si ya hay loading (la transición sería doble)
            const hasContent = content.children.length > 0 &&
                !content.querySelector('.loading');
            if (!hasContent) return orig(...args);
            return new Promise(resolve => {
                content.classList.add('page-exit');
                setTimeout(async () => {
                    content.classList.remove('page-exit');
                    content.classList.add('page-enter');
                    const result = await orig(...args);
                    requestAnimationFrame(() => {
                        requestAnimationFrame(() => {
                            content.classList.remove('page-enter');
                            content.classList.add('page-enter-active');
                            setTimeout(() => content.classList.remove('page-enter-active'), 250);
                        });
                    });
                    resolve(result);
                }, 160);
            });
        };
    });
})();


// =====================================================
// 2. DRAG & DROP EN LA COLA
// =====================================================
(function setupQueueDragDrop() {

    // Sobreescribir renderQueue para añadir drag handles
    const origRenderQueue = window.renderQueue;
    window.renderQueue = function() {
        const container = document.getElementById('queueList');
        if (!container) return;
        const queue = window.state?.queue || [];

        if (!queue.length) {
            container.innerHTML = '<div class="queue-empty">No hay canciones en cola</div>';
            return;
        }

        container.innerHTML = queue.map((song, idx) => `
            <div class="queue-item ${window.state?.usingQueue && window.state?.queueIndex === idx ? 'active' : ''}"
                 draggable="true"
                 data-idx="${idx}"
                 onclick="playFromQueue(${idx})">
                <div class="queue-drag-handle" onclick="event.stopPropagation()">
                    <i class="fas fa-grip-vertical"></i>
                </div>
                <img class="queue-item-img"
                     src="${song.portadaUrl}"
                     onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'">
                <div class="queue-item-info">
                    <div class="queue-item-title">${escapeHtml(song.nombre)}</div>
                    <div class="queue-item-artist">${escapeHtml(song.artista)}</div>
                </div>
                <button class="remove-from-queue"
                        onclick="event.stopPropagation(); removeFromQueue(${idx})">
                    <i class="fas fa-times"></i>
                </button>
            </div>
        `).join('');

        // Activar drag & drop
        _initDragDrop(container);
    };

    let dragSrc = null;

    function _initDragDrop(container) {
        const items = container.querySelectorAll('.queue-item[draggable]');

        items.forEach(item => {
            item.addEventListener('dragstart', (e) => {
                dragSrc = item;
                item.classList.add('queue-dragging');
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', item.dataset.idx);
            });

            item.addEventListener('dragend', () => {
                item.classList.remove('queue-dragging');
                container.querySelectorAll('.queue-item').forEach(i => {
                    i.classList.remove('queue-drag-over');
                });
                dragSrc = null;
            });

            item.addEventListener('dragover', (e) => {
                e.preventDefault();
                e.dataTransfer.dropEffect = 'move';
                if (item !== dragSrc) {
                    container.querySelectorAll('.queue-item').forEach(i =>
                        i.classList.remove('queue-drag-over'));
                    item.classList.add('queue-drag-over');
                }
            });

            item.addEventListener('drop', (e) => {
                e.preventDefault();
                if (!dragSrc || item === dragSrc) return;

                const fromIdx = parseInt(dragSrc.dataset.idx);
                const toIdx   = parseInt(item.dataset.idx);
                const queue   = window.state.queue;

                // Reordenar
                const [moved] = queue.splice(fromIdx, 1);
                queue.splice(toIdx, 0, moved);

                // Ajustar queueIndex si es necesario
                if (window.state.usingQueue) {
                    const qi = window.state.queueIndex;
                    if (qi === fromIdx)       window.state.queueIndex = toIdx;
                    else if (fromIdx < qi && toIdx >= qi) window.state.queueIndex--;
                    else if (fromIdx > qi && toIdx <= qi) window.state.queueIndex++;
                }

                window.renderQueue();
            });
        });

        // Touch drag para móvil
        _initTouchDrag(container);
    }

    // Touch drag simplificado para móvil
    function _initTouchDrag(container) {
        let touchItem = null, touchClone = null, startY = 0, startIdx = 0;

        container.addEventListener('touchstart', (e) => {
            const handle = e.target.closest('.queue-drag-handle');
            if (!handle) return;
            touchItem = handle.closest('.queue-item');
            if (!touchItem) return;
            startIdx = parseInt(touchItem.dataset.idx);
            startY = e.touches[0].clientY;

            // Clonar visualmente
            touchClone = touchItem.cloneNode(true);
            touchClone.style.cssText = `
                position:fixed; left:${touchItem.getBoundingClientRect().left}px;
                top:${touchItem.getBoundingClientRect().top}px;
                width:${touchItem.offsetWidth}px; z-index:99999;
                opacity:0.85; pointer-events:none;
                background:#3a3a3a; border-radius:8px;
                box-shadow:0 8px 24px rgba(0,0,0,0.5);
                transition:none;
            `;
            document.body.appendChild(touchClone);
            touchItem.style.opacity = '0.3';
            e.preventDefault();
        }, { passive: false });

        container.addEventListener('touchmove', (e) => {
            if (!touchClone) return;
            const dy = e.touches[0].clientY - startY;
            touchClone.style.top = (touchItem.getBoundingClientRect().top + dy) + 'px';
            e.preventDefault();
        }, { passive: false });

        container.addEventListener('touchend', (e) => {
            if (!touchClone || !touchItem) return;
            const endY   = e.changedTouches[0].clientY;
            const items  = [...container.querySelectorAll('.queue-item[draggable]')];
            let toIdx    = startIdx;

            items.forEach((it, i) => {
                const r = it.getBoundingClientRect();
                if (endY > r.top && endY < r.bottom) toIdx = i;
            });

            touchClone.remove();
            touchItem.style.opacity = '';
            touchClone = null;
            touchItem  = null;

            if (toIdx !== startIdx) {
                const queue = window.state.queue;
                const [moved] = queue.splice(startIdx, 1);
                queue.splice(toIdx, 0, moved);
                window.renderQueue();
            }
        });
    }
})();


// =====================================================
// 3. NOTIFICACIONES DEL SISTEMA
// =====================================================
(function setupNotifications() {
    if (!('Notification' in window)) return;

    // Pedir permiso la primera vez que el usuario interactúa
    document.addEventListener('click', async function askOnce() {
        if (Notification.permission === 'default') {
            await Notification.requestPermission();
        }
        document.removeEventListener('click', askOnce);
    }, { once: true });

    let lastNotifId = null;

    window._notifySongChange = function(song) {
        if (Notification.permission !== 'granted') return;
        // Cerrar notificación anterior
        if (lastNotifId) { lastNotifId.close(); lastNotifId = null; }

        try {
            const notif = new Notification(song.nombre, {
                body: `${song.artista}${song.album ? ' · ' + song.album : ''}`,
                icon: song.portadaUrl || '/favicon.ico',
                badge: song.portadaUrl || '/favicon.ico',
                silent: true,
                tag: 'spacemusic-now-playing'
            });
            notif.onclick = () => { window.focus(); notif.close(); };
            setTimeout(() => notif.close(), 4000);
            lastNotifId = notif;
        } catch(e) {
            // Falla silenciosamente si el contexto no lo permite
        }
    };
})();


// =====================================================
// 4. MEDIA SESSION API — controles nativos del SO
// =====================================================
(function setupMediaSession() {
    if (!('mediaSession' in navigator)) return;

    function updateMediaSession(song) {
        navigator.mediaSession.metadata = new MediaMetadata({
            title:  song.nombre  || 'Sin título',
            artist: song.artista || 'Desconocido',
            album:  song.album   || '',
            artwork: [
                { src: song.portadaUrl, sizes: '512x512', type: 'image/jpeg' },
                { src: song.portadaUrl, sizes: '256x256', type: 'image/jpeg' },
                { src: song.portadaUrl, sizes: '96x96',   type: 'image/jpeg' }
            ]
        });
    }

    function registerHandlers() {
        const ms = navigator.mediaSession;
        try {
            ms.setActionHandler('play',          () => { window.togglePlayPause?.(); ms.playbackState = 'playing'; });
            ms.setActionHandler('pause',         () => { window.togglePlayPause?.(); ms.playbackState = 'paused'; });
            ms.setActionHandler('previoustrack', () => window.prevSong?.());
            ms.setActionHandler('nexttrack',     () => window.nextSong?.());
            ms.setActionHandler('seekto',        (d) => {
                const audio = document.getElementById('audioElement');
                if (audio && d.seekTime != null) audio.currentTime = d.seekTime;
            });
            ms.setActionHandler('seekbackward', (d) => {
                const audio = document.getElementById('audioElement');
                if (audio) audio.currentTime = Math.max(0, audio.currentTime - (d.seekOffset || 10));
            });
            ms.setActionHandler('seekforward', (d) => {
                const audio = document.getElementById('audioElement');
                if (audio) audio.currentTime = Math.min(audio.duration, audio.currentTime + (d.seekOffset || 10));
            });
        } catch(e) { /* algunos handlers no soportados en todos los browsers */ }
    }

    // Sincronizar posición de reproducción
    const audio = document.getElementById('audioElement');
    audio.addEventListener('timeupdate', () => {
        if (!('mediaSession' in navigator) || !audio.duration) return;
        try {
            navigator.mediaSession.setPositionState({
                duration:     audio.duration,
                playbackRate: audio.playbackRate,
                position:     audio.currentTime
            });
        } catch(e) {}
    });

    audio.addEventListener('play',  () => { if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing'; });
    audio.addEventListener('pause', () => { if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused'; });

    registerHandlers();

    // Exponer para que playSongFromObject lo llame
    window._updateMediaSession = updateMediaSession;
})();


// =====================================================
// 5. HOOK playSongFromObject → notificación + media session
// =====================================================
(function hookSongChange() {
    const orig = window.playSongFromObject;
    if (!orig) return;

    window.playSongFromObject = function(song) {
        orig(song);
        // Notificación del sistema
        window._notifySongChange?.(song);
        // Media Session
        window._updateMediaSession?.(song);
    };
})();
