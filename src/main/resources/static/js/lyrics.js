// ========== MODAL DE LETRAS ==========

let lyricsModal = null;
let lyricsLines = [];
let lyricsUpdateInterval = null;
let currentLyricsLine = -1;
let lyricsOffset = 0;

function parseLrc(lrcContent) {
    if (!lrcContent) return [];
    const lines = [];
    const regex = /\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)/g;
    let match;
    while ((match = regex.exec(lrcContent)) !== null) {
        const minutes = parseInt(match[1]);
        const seconds = parseInt(match[2]);
        let millis = match[3] ? parseInt(match[3]) : 0;
        if (millis > 99) millis = millis / 1000;
        else millis = millis / 100;
        const time = minutes * 60 + seconds + millis;
        let text = match[4].trim().replace(/\[.*?\]/g, '').trim();
        if (text) lines.push({ time, text });
    }
    return lines.sort((a, b) => a.time - b.time);
}

function plainToLines(plainLyrics) {
    if (!plainLyrics) return [];
    const lines = [];
    const textLines = plainLyrics.split('\n');
    const totalDuration = audio.duration || (textLines.length * 4);
    const timePerLine = totalDuration / textLines.filter(l => l.trim()).length;
    textLines.forEach((line, idx) => {
        if (line.trim()) lines.push({ time: idx * timePerLine, text: line.trim() });
    });
    return lines;
}

async function openLyricsModal() {
    if (!state.currentSong) {
        showToast('No hay canción reproduciéndose', true);
        return;
    }
    const lyricsData = await loadLyrics(state.currentSong.id);
    let lines = [];
    if (lyricsData.letraSincronizada?.trim()) {
        lines = parseLrc(lyricsData.letraSincronizada);
    } else if (lyricsData.letra?.trim()) {
        lines = plainToLines(lyricsData.letra);
    }
    lyricsLines = lines;
    lyricsModal = document.createElement('div');
    lyricsModal.className = 'lyrics-modal';
    // Pequeño delay para animación de entrada
    setTimeout(() => lyricsModal.classList.add('lyrics-modal-open'), 10);
    lyricsModal.innerHTML = `
        <div class="lyrics-container">
            <div class="lyrics-left">
                <img class="album-art" src="${state.currentSong.portadaUrl}" onerror="this.src='/api/portada/${encodeURIComponent(state.currentSong.nombre)}'">
                <h2 class="song-title">${escapeHtml(state.currentSong.nombre)}</h2>
                <p class="artist-name">${escapeHtml(state.currentSong.artista)}</p>
                <div class="lyrics-controls">
                    <button onclick="toggleShuffle()" id="modalShuffleBtn"><i class="fas fa-random"></i></button>
                    <button onclick="prevSong()"><i class="fas fa-step-backward"></i></button>
                    <button class="play-pause-btn" onclick="togglePlayPauseFromModal()"><i id="modalPlayIcon" class="fas ${audio.paused ? 'fa-play' : 'fa-pause'}"></i></button>
                    <button onclick="nextSong()"><i class="fas fa-step-forward"></i></button>
                    <button onclick="toggleRepeatMode()" id="modalRepeatBtn"><i class="fas fa-redo-alt"></i></button>
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
                <div class="offset-control">
                    <label>
                        <i class="fas fa-sliders-h"></i> Sincronización:
                        <input type="range" min="-3" max="3" step="0.1" value="0" oninput="updateOffsetFromModal(this.value)">
                        <span id="offsetValueModal">0.0s</span>
                    </label>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(lyricsModal);
    if (lines.length) {
        updateCurrentLyricsLineFromModal();
        lyricsUpdateInterval = setInterval(updateCurrentLyricsLineFromModal, 150);
    }
}

function updateCurrentLyricsLineFromModal() {
    if (!lyricsLines.length) return;
    const currentTime = audio.currentTime + lyricsOffset;
    let activeIndex = -1;
    for (let i = 0; i < lyricsLines.length; i++) {
        if (currentTime >= lyricsLines[i].time) activeIndex = i;
        else break;
    }
    if (activeIndex !== currentLyricsLine) {
        currentLyricsLine = activeIndex;
        const lines = document.querySelectorAll('.lyrics-line');
        lines.forEach((line, idx) => {
            line.classList.remove('active', 'past');
            if (idx === activeIndex) line.classList.add('active');
            else if (idx < activeIndex) line.classList.add('past');
        });
        if (activeIndex >= 0) {
            document.querySelector('.lyrics-line.active')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    }
}

function updateOffsetFromModal(value) {
    lyricsOffset = parseFloat(value);
    document.getElementById('offsetValueModal').innerText = lyricsOffset.toFixed(1) + 's';
    updateCurrentLyricsLineFromModal();
}

function seekToTime(time) {
    audio.currentTime = Math.max(0, time);
}

function closeLyricsModal() {
    if (lyricsUpdateInterval) clearInterval(lyricsUpdateInterval);
    if (lyricsModal) lyricsModal.remove();
    lyricsModal = null;
    currentLyricsLine = -1;
}

function togglePlayPauseFromModal() {
    togglePlayPause();
    const modalPlayIcon = document.getElementById('modalPlayIcon');
    if (modalPlayIcon) {
        modalPlayIcon.className = `fas ${audio.paused ? 'fa-play' : 'fa-pause'}`;
    }
}

function uploadLrcFileFromModal() {
    if (!state.currentSong) {
        showToast('No hay canción seleccionada', true);
        return;
    }
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.lrc';
    input.onchange = async function(e) {
        const file = e.target.files[0];
        if (!file) return;
        if (!file.name.toLowerCase().endsWith('.lrc')) {
            showToast('Solo archivos .lrc', true);
            input.remove();
            return;
        }
        const formData = new FormData();
        formData.append('file', file);
        try {
            const response = await fetch(`/api/letras/subir/${state.currentSong.id}`, {
                method: 'POST',
                body: formData
            });
            const result = await response.json();
            if (response.ok) {
                showToast(result.message || 'Letra subida correctamente');
                cache.letras.delete(state.currentSong.id);
                if (lyricsModal) {
                    closeLyricsModal();
                    setTimeout(() => openLyricsModal(), 500);
                }
            } else {
                showToast(result.error || 'Error al subir la letra', true);
            }
        } catch(error) {
            showToast('Error de conexión', true);
        }
        input.remove();
    };
    document.body.appendChild(input);
    input.click();
}

// ── CSS del modal de letras ──
(function() {
    if (document.getElementById('_lyricsModalCSS')) return;
    const s = document.createElement('style');
    s.id = '_lyricsModalCSS';
    s.textContent = `
        .lyrics-modal {
            position: fixed;
            inset: 0;
            z-index: 10000;
            background: rgba(0,0,0,0.92);
            opacity: 0;
            transform: translateY(100%);
            transition: transform 0.38s cubic-bezier(0.32,0,0.15,1),
                        opacity   0.38s cubic-bezier(0.32,0,0.15,1);
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }
        .lyrics-modal.lyrics-modal-open {
            opacity: 1;
            transform: translateY(0);
        }
        .lyrics-container {
            display: flex;
            height: 100%;
            overflow: hidden;
        }
        /* Columna izquierda */
        .lyrics-left {
            width: 320px;
            flex-shrink: 0;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            gap: 14px;
            padding: 40px 24px 24px;
            background: rgba(0,0,0,0.15);
        }
        .lyrics-left .album-art {
            width: 100%;
            max-width: 260px;
            aspect-ratio: 1;
            border-radius: 12px;
            object-fit: cover;
            box-shadow: 0 16px 48px rgba(0,0,0,0.6);
        }
        .lyrics-left .song-title {
            font-size: 17px; font-weight: 800; color: #fff;
            text-align: center; margin: 0;
            white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
            width: 100%;
        }
        .lyrics-left .artist-name {
            font-size: 13px; color: rgba(255,255,255,0.55);
            text-align: center; margin: 0;
        }
        .lyrics-controls {
            display: flex; align-items: center;
            justify-content: space-between;
            width: 100%; gap: 4px;
        }
        .lyrics-controls button {
            background: none; border: none;
            color: rgba(255,255,255,0.75);
            width: 42px; height: 42px;
            border-radius: 50%;
            display: flex; align-items: center; justify-content: center;
            font-size: 18px; cursor: pointer;
            transition: all 0.18s; flex-shrink: 0;
        }
        .lyrics-controls button:hover { color:#fff; background: rgba(255,255,255,0.1); }
        .lyrics-controls .play-pause-btn {
            width: 54px; height: 54px;
            background: rgba(255,255,255,0.92) !important;
            color: #000 !important; font-size: 20px;
            box-shadow: 0 4px 16px rgba(0,0,0,0.35);
        }

        /* Columna derecha — letras */
        .lyrics-right {
            flex: 1;
            min-width: 0;
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }
        .lyrics-header-actions {
            display: flex; justify-content: flex-end;
            gap: 8px; padding: 14px 16px 8px;
            flex-shrink: 0;
        }
        .upload-lrc-btn, .close-lyrics-btn {
            background: rgba(255,255,255,0.1);
            border: none; color: #fff;
            padding: 8px 14px; border-radius: 20px;
            font-size: 12px; cursor: pointer;
            transition: background 0.18s;
            display: flex; align-items: center; gap: 6px;
        }
        .upload-lrc-btn:hover, .close-lyrics-btn:hover {
            background: rgba(255,255,255,0.2);
        }

        /* Scroll de letras con máscara */
        .lyrics-content-scroll {
            flex: 1;
            min-height: 0;
            overflow-y: auto;
            scrollbar-width: none;
            padding: 20px 48px 60px 40px;
            mask-image: linear-gradient(
                to bottom, transparent 0%, black 12%, black 82%, transparent 100%
            );
            -webkit-mask-image: linear-gradient(
                to bottom, transparent 0%, black 12%, black 82%, transparent 100%
            );
        }
        .lyrics-content-scroll::-webkit-scrollbar { display: none; }

        .lyrics-line {
            font-size: 26px; font-weight: 700;
            line-height: 1.4; color: rgba(255,255,255,0.18);
            padding: 7px 0; cursor: pointer;
            transition: font-size 0.28s cubic-bezier(0.4,0,0.2,1),
                        color 0.28s ease;
        }
        .lyrics-line:hover { color: rgba(255,255,255,0.5) !important; }
        .lyrics-line.past  { font-size: 23px; color: rgba(255,255,255,0.22); }
        .lyrics-line.active {
            font-size: 33px; font-weight: 800;
            color: #fff; letter-spacing: -0.3px;
        }
        .no-lyrics {
            text-align: center; padding: 60px 20px;
            color: rgba(255,255,255,0.3); font-size: 15px; line-height: 2;
        }
        .offset-control {
            flex-shrink: 0; padding: 8px 16px 12px;
            color: rgba(255,255,255,0.35); font-size: 12px;
            display: flex; align-items: center; gap: 8px;
            opacity: 0.4; transition: opacity 0.2s;
        }
        .offset-control:hover { opacity: 1; }
        .offset-control input[type=range] {
            flex: 1; -webkit-appearance: none; height: 2px;
            background: rgba(255,255,255,0.2); border-radius: 1px;
            outline: none; cursor: pointer;
        }
        .offset-control input[type=range]::-webkit-slider-thumb {
            -webkit-appearance: none; width: 12px; height: 12px;
            background: rgba(255,255,255,0.7); border-radius: 50%;
        }

        /* ── MÓVIL ── */
        @media (max-width: 860px) {
            .lyrics-container {
                flex-direction: column;
                overflow: hidden; /* NO scroll global */
            }
            .lyrics-left {
                width: 100%;
                flex-direction: row; flex-wrap: wrap;
                padding: 50px 14px 10px;
                gap: 8px; flex-shrink: 0;
                align-items: flex-start;
                justify-content: flex-start;
                max-height: 50vh;
                overflow: hidden;
            }
            .lyrics-left .album-art {
                width: 90px; height: 90px;
                flex-shrink: 0; align-self: flex-start;
            }
            .lyrics-left .song-title {
                font-size: 14px; text-align: left;
                flex: 1; min-width: 0; align-self: center;
            }
            .lyrics-left .artist-name {
                font-size: 12px; text-align: left;
                width: 100%; order: 3;
            }
            .lyrics-controls {
                width: 100%; order: 4; justify-content: space-around;
            }
            .lyrics-right {
                flex: 1; min-height: 0; overflow: hidden;
            }
            .lyrics-content-scroll {
                padding: 40px 20px 60px;
            }
            .lyrics-line         { font-size: 18px; }
            .lyrics-line.active  { font-size: 24px; }
            .lyrics-line.past    { font-size: 16px; }
        }
        @media (max-width: 520px) {
            .lyrics-left    { max-height: 46vh; padding: 44px 10px 8px; }
            .lyrics-left .album-art { width: 76px; height: 76px; }
            .lyrics-content-scroll  { padding: 32px 14px 52px; }
            .lyrics-line            { font-size: 15px; }
            .lyrics-line.active     { font-size: 20px; }
            .offset-control         { display: none; }
        }
    `;
    document.head.appendChild(s);
})();