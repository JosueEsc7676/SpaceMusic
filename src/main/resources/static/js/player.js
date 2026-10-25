// ========== REPRODUCTOR DE MÚSICA — MP3 + FLAC ==========

// Detectar MIME type por extensión del archivo
function detectAudioMime(rutaArchivo) {
    if (!rutaArchivo) return 'audio/mpeg';
    const lower = rutaArchivo.toLowerCase();
    if (lower.endsWith('.flac')) return 'audio/flac';
    if (lower.endsWith('.ogg'))  return 'audio/ogg';
    if (lower.endsWith('.wav'))  return 'audio/wav';
    if (lower.endsWith('.m4a'))  return 'audio/mp4';
    if (lower.endsWith('.aac'))  return 'audio/aac';
    return 'audio/mpeg'; // mp3 por defecto
}

function playSongFromObject(song) {
    state.currentSong = song;
    // Si el usuario selecciona manualmente una canción, limpiar la radio inteligente
    // Solo limpiar si NO viene de la propia smart queue
    if (!song._fromSmartQueue) {
        state.smartQueue = [];
    }
    const audioUrl = `/api/canciones/${song.id}/audio`;

    document.getElementById('currentSong').innerText   = song.nombre;
    document.getElementById('currentArtist').innerText = song.artista;
    document.getElementById('currentCover').src        = song.portadaUrl;

    const heartIcon = document.getElementById('favoriteHeart');
    if (song.favorito) heartIcon.classList.add('heart-active');
    else               heartIcon.classList.remove('heart-active');

    // ✅ Limpiar handlers anteriores para evitar duplicados
    audio.oncanplay  = null;
    audio.onerror    = null;
    audio.onstalled  = null;

    // ✅ Establecer type hint ANTES de asignar src
    // Crítico para FLAC: el navegador necesita saber el codec a tiempo
    const mimeType = detectAudioMime(song.rutaArchivo);
    audio.setAttribute('type', mimeType);

    // ✅ Usar <source> dinámico para mayor compatibilidad con FLAC
    audio.innerHTML = '';
    const source = document.createElement('source');
    source.src  = audioUrl;
    source.type = mimeType;
    audio.appendChild(source);
    audio.src = audioUrl;  // también asignar directamente como fallback

    audio.load();

    // ✅ canplay en vez de canplaythrough — dispara antes, reduce latencia
    audio.oncanplay = () => {
        const playPromise = audio.play();
        if (playPromise !== undefined) {
            playPromise
                .then(() => {
                document.getElementById('playPauseIcon').className = 'fas fa-pause';
            })
                .catch(err => {
                // AutoPlay bloqueado por el navegador — esperar interacción
                if (err.name === 'NotAllowedError') {
                    document.getElementById('playPauseIcon').className = 'fas fa-play';
                } else {
                    console.error('Error reproduciendo:', err);
                    showToast(`Error al reproducir: ${song.nombre}`, true);
                }
            });
        }
    };

    // ✅ Manejo de errores de audio (FLAC no soportado, archivo corrupto, etc.)
    audio.onerror = (e) => {
        const err = audio.error;
        let msg = 'Error al cargar el audio';
        if (err) {
            switch(err.code) {
                case MediaError.MEDIA_ERR_ABORTED:    msg = 'Carga cancelada'; break;
                case MediaError.MEDIA_ERR_NETWORK:    msg = 'Error de red al cargar el audio'; break;
                case MediaError.MEDIA_ERR_DECODE:
                    msg = mimeType === 'audio/flac'
                        ? 'FLAC no soportado en este navegador (usa Chrome o Firefox)'
                        : 'Error decodificando el audio';
                    break;
                case MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED:
                    msg = mimeType === 'audio/flac'
                        ? 'Tu navegador no soporta FLAC. Usa Chrome 56+, Firefox 51+ o Edge'
                        : 'Formato de audio no soportado';
                    break;
            }
        }
        console.error('Audio error:', err, 'MIME:', mimeType, 'URL:', audioUrl);
        showToast(`⚠️ ${msg}`, true);
        document.getElementById('playPauseIcon').className = 'fas fa-play';
    };

    // ✅ Detectar cuando el buffer se atasca (común en FLAC grandes)
    audio.onstalled = () => {
        console.warn('Audio stalled — el buffer tardó en cargar:', song.nombre);
    };

    fetch(`/api/canciones/${song.id}/reproducir`, { method: 'POST' }).catch(() => {});
    renderQueue();
}

async function playSong(id) {
    try {
        const response = await fetch(`/api/canciones/${id}`);
        const cancion  = await response.json();
        playSongFromObject(cancion);

        const todas = await loadTodasCanciones();
        state.allSongs    = todas;
        state.currentIndex = state.allSongs.findIndex(s => s.id === id);
        state.usingQueue  = false;

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

    } catch(e) {
        showToast(`Error: ${e.message}`, true);
    }
}

function getCurrentPlaylist() {
    if (state.usingQueue && state.queue.length) return state.queue;
    if (state.shuffleMode && state.shuffledSongs.length) return state.shuffledSongs;
    return state.allSongs;
}

function togglePlayPause() {
    if (audio.paused) {
        const promise = audio.play();
        if (promise) {
            promise
                .then(() => document.getElementById('playPauseIcon').className = 'fas fa-pause')
                .catch(err => {
                if (err.name !== 'AbortError') {
                    document.getElementById('playPauseIcon').className = 'fas fa-play';
                }
            });
        }
    } else {
        audio.pause();
        document.getElementById('playPauseIcon').className = 'fas fa-play';
    }
}

function nextSong() {
    if (state.usingQueue && state.queue.length) {
        if (state.queueIndex < state.queue.length - 1) {
            playFromQueue(state.queueIndex + 1);
        } else if (state.repeatMode === 2) {
            playFromQueue(0);
        } else {
            // Cola terminó → radio inteligente
            _smartAutoplay();
        }
        return;
    }
    const playlist = getCurrentPlaylist();
    if (state.repeatMode === 1 && state.currentSong) {
        playSong(state.currentSong.id);
    } else if (state.repeatMode === 2) {
        if (state.currentIndex === playlist.length - 1) playSong(playlist[0].id);
        else if (state.currentIndex < playlist.length - 1) playSong(playlist[state.currentIndex + 1].id);
    } else {
        // Shuffle activo → siguiente aleatoria
        if (state.shuffleMode && state.shuffledSongs.length > 0) {
            if (state.currentIndex < state.shuffledSongs.length - 1) {
                playSong(state.shuffledSongs[state.currentIndex + 1].id);
            } else {
                // Se acabó el shuffle → re-mezclar
                _reshuffleAndPlay();
            }
        } else if (state.currentIndex < playlist.length - 1) {
            // Sin shuffle → siguiente en lista
            playSong(playlist[state.currentIndex + 1].id);
        }
        // Si es la última canción sin repeat → no hace nada (o podría llamar _smartAutoplay)
    }
}
// (lógica de radio movida a radio.js)





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
    if (state.repeatMode === 0) {
        repeatBtn.classList.remove('active');
        repeatBtn.removeAttribute('data-mode');
        showToast('Repetición desactivada');
    } else if (state.repeatMode === 1) {
        repeatBtn.classList.add('active');
        repeatBtn.setAttribute('data-mode', 'one');
        showToast('Repetir una vez ✓');
    } else {
        repeatBtn.classList.add('active');
        repeatBtn.removeAttribute('data-mode');
        showToast('Repetir todas ↻');
    }
}

function toggleShuffle() {
    state.shuffleMode = !state.shuffleMode;
    const shuffleBtn = document.getElementById('shuffleBtn');
    if (state.shuffleMode) {
        shuffleBtn.classList.add('active');
        state.shuffledSongs = [...state.allSongs];
        for (let i = state.shuffledSongs.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [state.shuffledSongs[i], state.shuffledSongs[j]] =
            [state.shuffledSongs[j], state.shuffledSongs[i]];
        }
        if (state.currentSong) {
            state.currentIndex = state.shuffledSongs.findIndex(s => s.id === state.currentSong.id);
        }
        showToast('Aleatorio activado');
    } else {
        shuffleBtn.classList.remove('active');
        if (state.currentSong) {
            state.currentIndex = state.allSongs.findIndex(s => s.id === state.currentSong.id);
        }
        showToast('Aleatorio desactivado');
    }
}

// ========== FAVORITOS ==========
async function toggleFavorito(id, element) {
    try {
        const response = await fetch(`/api/canciones/${id}/favorito`, { method: 'PUT' });
        const updated  = await response.json();
        if (state.currentSong?.id === id) {
            const heart = document.getElementById('favoriteHeart');
            updated.favorito
                ? heart.classList.add('heart-active')
                : heart.classList.remove('heart-active');
            state.currentSong.favorito = updated.favorito;
        }
        cache.favoritos = null;
        showToast(updated.favorito ? '❤️ Agregado a favoritos' : '💔 Eliminado de favoritos');
    } catch(e) { showToast('Error', true); }
}

async function toggleCurrentFavorite() {
    if (state.currentSong) await toggleFavorito(state.currentSong.id, null);
}

// ========== CONTROL DE VOLUMEN ==========
let lastVolume = 70;

function setupVolumeControl() {
    const volumeSlider = document.getElementById('volumeSlider');
    audio.volume = 0.7;
    updateVolumeUI(70);
    volumeSlider.addEventListener('click', (e) => {
        const rect    = volumeSlider.getBoundingClientRect();
        const volume  = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
        audio.volume  = volume;
        const percent = Math.round(volume * 100);
        updateVolumeUI(percent);
        lastVolume = percent;
    });
}

function updateVolumeUI(volume) {
    document.getElementById('volumeFill').style.width = volume + '%';
    const icon = document.getElementById('volumeIcon');
    if (volume === 0)        icon.className = 'fas fa-volume-mute';
    else if (volume < 30)    icon.className = 'fas fa-volume-down';
    else                     icon.className = 'fas fa-volume-up';
}

function toggleMute() {
    if (audio.volume > 0) {
        lastVolume   = audio.volume * 100;
        audio.volume = 0;
        updateVolumeUI(0);
    } else {
        audio.volume = lastVolume / 100;
        updateVolumeUI(lastVolume);
    }
}

// ========== BARRA DE PROGRESO ==========
function setupProgressBar() {
    const wrapper      = document.getElementById('progressBarWrapper');
    const progressFill = document.getElementById('progressFill');

    wrapper.addEventListener('click', (e) => {
        if (audio.duration) {
            const rect    = wrapper.getBoundingClientRect();
            audio.currentTime = ((e.clientX - rect.left) / rect.width) * audio.duration;
        }
    });

    audio.addEventListener('timeupdate', () => {
        if (audio.duration) {
            progressFill.style.width =
            ((audio.currentTime / audio.duration) * 100) + '%';
            document.getElementById('currentTime').innerText =
            formatTime(audio.currentTime);
        }
    });

    audio.addEventListener('loadedmetadata', () => {
        document.getElementById('duration').innerText = formatTime(audio.duration);
    });

    audio.addEventListener('ended', () => {
        if (state.repeatMode === 1) {
            audio.currentTime = 0;
            audio.play();
            return;
        }
        // Radio.js maneja toda la lógica de continuación automática
        // Solo intervenir para cola manual o repeat all
        if (state.usingQueue && state.queue.length > 0) {
            nextSong();
            return;
        }
        if (state.repeatMode === 2) {
            nextSong();
            return;
        }
        // Radio.js se encarga del resto via su hook en audio 'ended'
    });
}
