/* =====================================================
   radio.js — Motor de Radio Inteligente v2
   
   Mejoras vs v1:
   - Usa /api/next/{id} con scoring backend (artista +10, género +5, álbum +3)
   - Registra historial en /api/history para excluir canciones recientes
   - Sesión persistente en sessionStorage (se resetea al cerrar el tab)
   - Fallback robusto: /api/shuffle/similar → /api/next → /api/shuffle/cola
   - Pre-carga silenciosa de la siguiente canción
   ===================================================== */

const Radio = (function() {

    // ── Configuración ──────────────────────────────────────────────────────
    const CONFIG = {
        queueSize:      12,    // canciones a mantener en cola
        refillAt:        4,    // rellenar cuando queden menos de N
        recentWindow:   20,    // tamaño de ventana local de recientes
        minArtistGap:    3,    // mínimo N canciones entre el mismo artista
        toastCooldown: 3000,
    };

    // ── Estado interno ─────────────────────────────────────────────────────
    let queue         = [];
    let recentIds     = [];
    let recentArtists = [];
    let active        = false;
    let fetching      = false;
    let seedSongId    = null;
    let lastToast     = 0;
    let sessionStart  = Date.now();   // para calcular segundos escuchados

    // ── ID de sesión anónima — persiste mientras el tab esté abierto ───────
    const SESSION_ID = (() => {
        let id = sessionStorage.getItem('sm_session_id');
        if (!id) {
            id = 'sess_' + Math.random().toString(36).slice(2) + Date.now().toString(36);
            sessionStorage.setItem('sm_session_id', id);
        }
        return id;
    })();

    // ═══════════════════════════════════════════════════════════════════════
    // INICIALIZACIÓN
    // ═══════════════════════════════════════════════════════════════════════
    function init() {
        _hookAudioEnded();
        _hookPlaySong();
        console.log('📻 Radio v2 inicializada — sesión:', SESSION_ID);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HOOK: audio.ended
    // ═══════════════════════════════════════════════════════════════════════
    function _hookAudioEnded() {
        const audio = document.getElementById('audioElement');
        if (!audio) return;

        audio.addEventListener('ended', () => {
            if (window.state?.repeatMode === 1) return;
            if (window.state?.usingQueue && window.state?.queue?.length > 0) return;
            if (window.state?.repeatMode === 2) return;

            // Registrar que la canción terminó (segundos = 0 → escuchó completa)
            _registrarHistorial(window.state?.currentSong, 0, 'RADIO');

            _onSongEnded();
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HOOK: playSongFromObject — registra cada inicio de reproducción
    // ═══════════════════════════════════════════════════════════════════════
    function _hookPlaySong() {
        const orig = window.playSongFromObject;
        if (!orig) { setTimeout(_hookPlaySong, 200); return; }

        window.playSongFromObject = function(song) {
            // Registrar la canción anterior si fue saltada antes de terminar
            const prev = window.state?.currentSong;
            if (prev && prev.id !== song.id) {
                const audio = document.getElementById('audioElement');
                const segs  = audio ? Math.floor(audio.currentTime) : 0;
                if (segs > 5) {
                    _registrarHistorial(prev, segs, 'MANUAL');
                }
            }

            orig(song);
            sessionStart = Date.now();
            _onSongStarted(song);
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Cada vez que empieza una canción
    // ═══════════════════════════════════════════════════════════════════════
    function _onSongStarted(song) {
        if (!song) return;
        _addToRecent(song.id, song.artista);

        if (active && queue.length < CONFIG.refillAt) {
            _refill(song.id);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lógica de continuación al terminar una canción
    // ═══════════════════════════════════════════════════════════════════════
    async function _onSongEnded() {
        const ctx     = window.state?.playContext;
        const ctxType = ctx?.type || 'all';
        const songs   = ctx?.songs || [];
        const idx     = window.state?.currentIndex ?? -1;
        const hasNext = idx >= 0 && idx < songs.length - 1;

        if (hasNext && ctxType !== 'search' && ctxType !== 'all') {
            window.nextSong?.();
            return;
        }

        await _playNextFromRadio();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Reproducir siguiente de la radio
    // ═══════════════════════════════════════════════════════════════════════
    async function _playNextFromRadio() {
        const currentSong = window.state?.currentSong;
        if (!currentSong) return;

        if (!active) {
            active     = true;
            seedSongId = currentSong.id;
        }

        if (queue.length > 0) {
            const next = queue.shift();
            _playRadioSong(next);
            if (queue.length < CONFIG.refillAt) _refill(next.id);
            return;
        }

        _showToast('📻 Cargando radio...');
        await _refill(currentSong.id);

        if (queue.length > 0) {
            const next = queue.shift();
            _playRadioSong(next);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Reproducir una canción de la radio
    // ═══════════════════════════════════════════════════════════════════════
    function _playRadioSong(song) {
        if (!song) return;
        song._fromSmartQueue = true;

        window.playSongFromObject(song);

        if (window.state) {
            window.state.playContext = {
                type:  'radio',
                songs: [],
                label: `Radio · ${song.artista}`
            };
        }

        _showToast(`📻 Radio · ${song.artista}`);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RELLENAR cola — prioridad:
    //   1. /api/next/{id}?sesion=X   (scoring backend, excluye historial real)
    //   2. /api/shuffle/similar/{id} (motor SmartShuffle existente)
    //   3. /api/shuffle/cola         (fallback general)
    // ═══════════════════════════════════════════════════════════════════════
    async function _refill(fromSongId) {
        if (fetching) return;
        fetching = true;

        try {
            const needed = CONFIG.queueSize - queue.length;
            let canciones = [];

            // Estrategia 1: scoring endpoint (devuelve 1 canción muy relevante)
            try {
                const res = await fetch(`/api/next/${fromSongId}?sesion=${SESSION_ID}`);
                if (res.ok) {
                    const song = await res.json();
                    if (song && song.id) canciones.push(song);
                }
            } catch(e) {}

            // Estrategia 2: similar (mismo artista + género)
            if (canciones.length < needed) {
                try {
                    const res = await fetch(
                        `/api/shuffle/similar/${fromSongId}?cantidad=${needed + 5}`
                    );
                    if (res.ok) {
                        const extra = await res.json();
                        canciones = [...canciones, ...extra];
                    }
                } catch(e) {}
            }

            // Estrategia 3: mix general como fallback
            if (canciones.length < needed) {
                try {
                    const res = await fetch(`/api/shuffle/cola?cantidad=${needed + 5}`);
                    if (res.ok) {
                        const extra = await res.json();
                        canciones = [...canciones, ...extra];
                    }
                } catch(e) {}
            }

            const filtered = _filterCandidates(canciones);
            const queueIds = new Set(queue.map(c => c.id));
            const newSongs = filtered.filter(c => !queueIds.has(c.id));

            queue.push(...newSongs.slice(0, needed));

        } catch(e) {
            console.error('Radio refill error:', e);
        } finally {
            fetching = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FILTRAR candidatos: deduplicar, excluir recientes, distribuir artistas
    // ═══════════════════════════════════════════════════════════════════════
    function _filterCandidates(canciones) {
        if (!canciones?.length) return [];

        const seen = new Set();
        let unique = canciones.filter(c => {
            if (seen.has(c.id)) return false;
            seen.add(c.id);
            return true;
        });

        const recentSet = new Set(recentIds);
        unique = unique.filter(c => !recentSet.has(c.id));

        unique = _weightedShuffle(unique);
        return _spreadArtists(unique);
    }

    // Shuffle ponderado por reproducciones + ruido aleatorio
    function _weightedShuffle(songs) {
        if (!songs.length) return [];
        const maxR = Math.max(...songs.map(s => s.reproducciones || 0), 1);

        return songs
            .map(s => ({
                song:  s,
                score: (0.3 + ((s.reproducciones || 0) / maxR) * 0.7) + Math.random() * 0.6
            }))
            .sort((a, b) => b.score - a.score)
            .map(w => w.song);
    }

    // Distribuir artistas: mínimo CONFIG.minArtistGap entre el mismo artista
    function _spreadArtists(songs) {
        const result   = [];
        const deferred = [];
        const gap      = CONFIG.minArtistGap;

        for (const song of songs) {
            const lastPos = result.reduce(
                (last, s, i) => s.artista?.toLowerCase() === song.artista?.toLowerCase() ? i : last,
                -999
            );
            if (result.length - lastPos <= gap) {
                deferred.push(song);
            } else {
                result.push(song);
            }
        }

        result.push(...deferred);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REGISTRAR HISTORIAL en backend
    // ═══════════════════════════════════════════════════════════════════════
    async function _registrarHistorial(song, segundos, fuente) {
        if (!song?.id) return;
        try {
            await fetch('/api/history', {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    cancionId:         song.id,
                    sesionId:          SESSION_ID,
                    segundosEscuchados: segundos,
                    fuente:            fuente
                })
            });
        } catch(e) {
            // Silencioso — no bloquear la reproducción por error de historial
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Historial local (para filtrado inmediato sin esperar al backend)
    // ═══════════════════════════════════════════════════════════════════════
    function _addToRecent(id, artista) {
        recentIds.unshift(id);
        if (recentIds.length > CONFIG.recentWindow) recentIds.length = CONFIG.recentWindow;

        recentArtists.unshift(artista);
        if (recentArtists.length > CONFIG.minArtistGap * 2) {
            recentArtists.length = CONFIG.minArtistGap * 2;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // API PÚBLICA
    // ═══════════════════════════════════════════════════════════════════════
    function start(song) {
        active     = true;
        seedSongId = song?.id;
        queue      = [];
        if (song) _refill(song.id);
    }

    function stop() {
        active = false;
        queue  = [];
    }

    async function skip() {
        await _playNextFromRadio();
    }

    function isActive()   { return active; }
    function getQueue()   { return [...queue]; }
    function getSession() { return SESSION_ID; }

    function _showToast(msg) {
        const now = Date.now();
        if (now - lastToast < CONFIG.toastCooldown) return;
        lastToast = now;
        window.showToast?.(msg);
    }

    return { init, start, stop, skip, isActive, getQueue, getSession };

})();

// Inicializar cuando el DOM esté listo
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', Radio.init);
} else {
    setTimeout(Radio.init, 100);
}

window.Radio = Radio;
