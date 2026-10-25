/* =====================================================
   lyrics_apple.js — v4  DEFINITIVO
   Apple Music / Spotify lyrics experience
   - requestAnimationFrame (no setInterval lag)
   - Color dominante real de la portada
   - Layout: portada izq + letras derecha
   - Letras: SOLO se muestra la línea activa (karaoke puro)
   - Scroll automático suave centrado en línea activa
   - Responsive desktop + mobile (en móvil: letras arriba, reproductor abajo)
   ===================================================== */

const AppleLyrics = (function () {

    // ── Estado ──
    let modal       = null;
    let lines       = [];
    let rafId       = null;
    let activeIdx   = -1;
    let offset      = 0;
    let userScroll  = false;
    let scrollTimer = null;
    let color       = [28, 28, 28];
    let progDrag    = false;

    const audio = document.getElementById('audioElement');

    // =============================================
    // COLOR DOMINANTE — canvas 32×32, boost sat
    // =============================================
    function extractColor(src) {
        return new Promise(resolve => {
            const img = new Image();
            img.crossOrigin = 'anonymous';
            img.onload = () => {
                try {
                    const c = document.createElement('canvas');
                    c.width = c.height = 32;
                    const ctx = c.getContext('2d');
                    ctx.drawImage(img, 0, 0, 32, 32);
                    const d = ctx.getImageData(0, 0, 32, 32).data;
                    let r = 0, g = 0, b = 0, n = 0;
                    for (let i = 0; i < d.length; i += 4) {
                        const mx = Math.max(d[i], d[i+1], d[i+2]);
                        const mn = Math.min(d[i], d[i+1], d[i+2]);
                        const sat = mx === 0 ? 0 : (mx - mn) / mx;
                        const br  = mx / 255;
                        if (sat > 0.18 && br > 0.12 && br < 0.92) {
                            r += d[i]; g += d[i+1]; b += d[i+2]; n++;
                        }
                    }
                    if (n > 30) {
                        // Oscurecer para fondo legible
                        resolve([Math.round(r/n*0.48), Math.round(g/n*0.48), Math.round(b/n*0.48)]);
                    } else {
                        resolve([28, 28, 28]);
                    }
                } catch { resolve([28, 28, 28]); }
            };
            img.onerror = () => resolve([28, 28, 28]);
            img.src = src;
        });
    }

    function makeBg(r, g, b) {
        const r2 = Math.min(255, r + 55), g2 = Math.min(255, g + 55), b2 = Math.min(255, b + 55);
        return `
            radial-gradient(ellipse at 12% 55%, rgba(${r2},${g2},${b2},0.88) 0%, transparent 52%),
            radial-gradient(ellipse at 88% 22%, rgba(${r},${g},${Math.min(255,b+45)},0.72) 0%, transparent 48%),
            linear-gradient(150deg, rgb(${r},${g},${b}) 0%, rgb(${Math.round(r*.6)},${Math.round(g*.6)},${Math.round(b*.7)}) 100%)
        `;
    }

    // =============================================
    // OPEN / CLOSE
    // =============================================
    async function open() {
        if (!window.state?.currentSong) {
            window.showToast?.('No hay canción reproduciéndose', true);
            return;
        }
        if (modal) close();

        const song = window.state.currentSong;

        // Cargar color y letras en paralelo
        const [clr, lyricsData] = await Promise.all([
            extractColor(song.portadaUrl),
            window.loadLyrics?.(song.id) || Promise.resolve({})
        ]);

        color = clr;
        lines = _parseAll(lyricsData);

        _build(song);
        document.body.appendChild(modal);
        modal.offsetHeight; // reflow
        requestAnimationFrame(() => {
            requestAnimationFrame(() => modal.classList.add('al-open'));
        });
        _startRAF();
    }

    function close() {
        if (!modal) return;
        _stopRAF();
        modal.classList.remove('al-open');
        const m = modal;
        modal = null;
        activeIdx = -1;
        setTimeout(() => m?.remove(), 400);
    }

    function toggle() { modal ? close() : open(); }

    // =============================================
    // BUILD MODAL
    // =============================================
    function _build(song) {
        const [r, g, b] = color;

        modal = document.createElement('div');
        modal.className = 'al-overlay';
        modal.id = 'appleLyricsModal';
        modal.style.background = makeBg(r, g, b);

        modal.innerHTML = `
            <div class="al-bg-art" id="alBgArt"
                 style="background-image:url('${song.portadaUrl}')"></div>
            <div class="al-bg-noise"></div>

            <button class="al-close" onclick="AppleLyrics.close()" aria-label="Cerrar">
                <i class="fas fa-chevron-down"></i>
            </button>

            <div class="al-layout">

                <!-- ── IZQUIERDA ── -->
                <div class="al-left">

                    <div class="al-cover-wrap">
                        <img id="alCover" class="al-cover" src="${song.portadaUrl}"
                             onerror="this.src='https://singlecolorimage.com/get/1a1a2e/400x400'">
                    </div>

                    <div class="al-meta">
                        <div class="al-meta-title" id="alTitle">${_esc(song.nombre)}</div>
                        <div class="al-meta-sub" id="alSub">
                            ${_esc(song.artista)}${song.album ? ' — ' + _esc(song.album) : ''}
                        </div>
                    </div>

                    <div class="al-prog-wrap">
                        <div class="al-prog-track" id="alProgTrack">
                            <div class="al-prog-fill"  id="alProgFill"></div>
                            <div class="al-prog-thumb" id="alProgThumb"></div>
                        </div>
                        <div class="al-prog-times">
                            <span id="alCur">0:00</span>
                            <span id="alDur">0:00</span>
                        </div>
                    </div>

                    <div class="al-controls">
                        <button class="al-btn al-btn-sm" id="alShuf"
                                onclick="toggleShuffle(); AppleLyrics._syncBtns()"
                                aria-label="Aleatorio">
                            <i class="fas fa-random"></i>
                        </button>
                        <button class="al-btn al-btn-sm" onclick="prevSong()"
                                aria-label="Anterior">
                            <i class="fas fa-backward-step"></i>
                        </button>
                        <button class="al-btn al-btn-play"
                                onclick="togglePlayPause(); AppleLyrics._syncPlay()"
                                aria-label="Play/Pausa">
                            <i id="alPlayIco" class="fas ${audio.paused ? 'fa-play' : 'fa-pause'}"></i>
                        </button>
                        <button class="al-btn al-btn-sm" onclick="nextSong()"
                                aria-label="Siguiente">
                            <i class="fas fa-forward-step"></i>
                        </button>
                        <button class="al-btn al-btn-sm" id="alRep"
                                onclick="toggleRepeatMode(); AppleLyrics._syncBtns()"
                                aria-label="Repetir">
                            <i class="fas fa-repeat"></i>
                        </button>
                    </div>

                    <div class="al-vol-row">
                        <i class="fas fa-volume-down al-vol-ico" onclick="toggleMute()"></i>
                        <div class="al-vol-track" id="alVolTrack">
                            <div class="al-vol-fill" id="alVolFill"
                                 style="width:${Math.round(audio.volume*100)}%"></div>
                        </div>
                        <i class="fas fa-volume-up al-vol-ico" onclick="toggleMute()"></i>
                        <button class="al-lrc-btn" onclick="AppleLyrics.uploadLrc()"
                                title="Subir archivo .LRC">
                            <i class="fas fa-upload"></i>
                        </button>
                    </div>

                    <!-- Ajuste de offset -->
                    <div class="al-offset-row">
                        <i class="fas fa-sliders al-offset-ico"></i>
                        <input class="al-offset-slider" id="alOffSlider"
                               type="range" min="-5" max="5" step="0.1" value="0"
                               oninput="AppleLyrics._setOffset(this.value)">
                        <span class="al-offset-val" id="alOffVal">0.0s</span>
                    </div>

                </div>

                <!-- ── DERECHA: LETRAS (solo se ve la activa) ── -->
                <div class="al-right">
                    <div class="al-mask">
                        <div class="al-scroll" id="alScroll">
                            <div class="al-inner" id="alInner">
                                ${_renderLines()}
                            </div>
                        </div>
                    </div>
                </div>

            </div>`;

        _setupProg();
        _setupVol();
        _setupScrollDetect();
        audio.addEventListener('play',            _syncPlay);
        audio.addEventListener('pause',           _syncPlay);
        audio.addEventListener('loadedmetadata',  _syncDur);
        _syncBtns();
        _syncDur();
    }

    // =============================================
    // RENDER LETRAS
    // =============================================
    function _renderLines() {
        if (!lines.length) return `
            <div class="al-empty">
                <i class="fas fa-music"></i>
                <p>Sin letra disponible</p>
                <button class="al-upload-cta" onclick="AppleLyrics.uploadLrc()">
                    <i class="fas fa-upload"></i> Subir archivo LRC
                </button>
            </div>`;

        return lines.map((l, i) =>
        `<div class="al-line" data-i="${i}" onclick="AppleLyrics._seek(${l.time})">${_esc(l.text)}</div>`
        ).join('');
    }

    // =============================================
    // requestAnimationFrame LOOP — sin lag
    // =============================================
    function _startRAF() {
        _stopRAF();
        function loop() {
            _tick();
            rafId = requestAnimationFrame(loop);
        }
        rafId = requestAnimationFrame(loop);
    }

    function _stopRAF() {
        if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
    }

    // Función de actualización principal - SOLO MUESTRA LA LÍNEA ACTIVA
    function _tick() {
        if (!modal) return;

        // ── Barra de progreso ──
        if (audio.duration) {
            const pct = (audio.currentTime / audio.duration) * 100;
            const fill  = document.getElementById('alProgFill');
            const thumb = document.getElementById('alProgThumb');
            const cur   = document.getElementById('alCur');
            const dur   = document.getElementById('alDur');
            if (fill  && !progDrag) fill.style.width  = pct + '%';
            if (thumb && !progDrag) thumb.style.left  = pct + '%';
            if (cur)  cur.textContent = _fmt(audio.currentTime);
            if (dur)  dur.textContent = _fmt(audio.duration);
        }

        if (!lines.length) return;

        // ── Línea activa ──
        const t = audio.currentTime + offset;
        let idx = -1;
        for (let i = 0; i < lines.length; i++) {
            if (t >= lines[i].time) idx = i; else break;
        }

        // Si no cambió la línea activa, no hacer nada
        if (idx === activeIdx) return;
        activeIdx = idx;

        const inner = document.getElementById('alInner');
        if (!inner) return;

        const all = inner.querySelectorAll('.al-line');

        // Ocultar TODAS las líneas y mostrar SOLO la activa (si existe)
        all.forEach((el, i) => {
            if (i === idx) {
                // Línea activa: visible, grande y blanca
                el.classList.remove('al-past', 'al-next', 'al-hidden');
                el.classList.add('al-active');
                el.style.opacity = '1';
                el.style.visibility = 'visible';
            } else {
                // Todas las demás: completamente ocultas
                el.classList.remove('al-active', 'al-past', 'al-next');
                el.classList.add('al-hidden');
                el.style.opacity = '0';
                el.style.visibility = 'hidden';
            }
        });

        // ── Scroll para centrar la línea activa ──
        if (idx >= 0 && !userScroll) {
            const scrollContainer = document.getElementById('alScroll');
            const activeLine = all[idx];
            if (scrollContainer && activeLine) {
                // Calcular la posición para centrar perfectamente la línea activa
                const containerHeight = scrollContainer.clientHeight;
                const lineTop = activeLine.offsetTop;
                const lineHeight = activeLine.clientHeight;

                // El centro de la línea debe quedar en el centro del contenedor
                const targetScroll = lineTop - (containerHeight / 2) + (lineHeight / 2);

                // Aplicar scroll con comportamiento suave
                scrollContainer.scrollTo({
                    top: Math.max(0, targetScroll),
                    behavior: 'smooth'
                });
            }
        }
    }

    // =============================================
    // SETUP EVENTOS
    // =============================================
    function _setupProg() {
        const track = document.getElementById('alProgTrack');
        if (!track) return;

        const seek = (e) => {
            const r   = track.getBoundingClientRect();
            const x   = (e.touches ? e.touches[0].clientX : e.clientX);
            const pct = Math.max(0, Math.min(1, (x - r.left) / r.width));
            if (audio.duration) audio.currentTime = pct * audio.duration;
            const fill  = document.getElementById('alProgFill');
            const thumb = document.getElementById('alProgThumb');
            if (fill)  fill.style.width = (pct * 100) + '%';
            if (thumb) thumb.style.left = (pct * 100) + '%';
        };

        track.addEventListener('mousedown',  (e) => { progDrag = true; seek(e); });
        track.addEventListener('touchstart', (e) => { progDrag = true; seek(e); }, { passive: true });
        document.addEventListener('mousemove',  (e) => { if (progDrag) seek(e); });
        document.addEventListener('touchmove',  (e) => { if (progDrag) seek(e); }, { passive: true });
        document.addEventListener('mouseup',    () => { progDrag = false; });
        document.addEventListener('touchend',   () => { progDrag = false; });
    }

    function _setupVol() {
        const track = document.getElementById('alVolTrack');
        if (!track) return;
        track.addEventListener('click', (e) => {
            const r   = track.getBoundingClientRect();
            const vol = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
            audio.volume = vol;
            const fill = document.getElementById('alVolFill');
            if (fill) fill.style.width = (vol * 100) + '%';
        });
    }

    function _setupScrollDetect() {
        const scroll = document.getElementById('alScroll');
        if (!scroll) return;
        scroll.addEventListener('scroll', () => {
            userScroll = true;
            clearTimeout(scrollTimer);
            scrollTimer = setTimeout(() => { userScroll = false; }, 2500);
        }, { passive: true });
    }

    // =============================================
    // PARSERS
    // =============================================
    function _parseAll(data) {
        if (data?.letraSincronizada?.trim()) return _parseLrc(data.letraSincronizada);
        if (data?.letra?.trim())             return _plainLines(data.letra);
        return [];
    }

    function _parseLrc(lrc) {
        const res = [], re = /\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)/g;
        let m;
        while ((m = re.exec(lrc)) !== null) {
            let ms = m[3] ? parseInt(m[3]) : 0;
            ms = ms > 99 ? ms / 1000 : ms / 100;
            const time = parseInt(m[1]) * 60 + parseInt(m[2]) + ms;
            const text = m[4].trim().replace(/\[.*?\]/g, '').trim();
            if (text) res.push({ time, text });
        }
        return res.sort((a, b) => a.time - b.time);
    }

    function _plainLines(txt) {
        const rows = txt.split('\n').filter(l => l.trim());
        const tpl  = (audio.duration || rows.length * 3.5) / rows.length;
        return rows.map((t, i) => ({ time: i * tpl, text: t.trim() }));
    }

    // =============================================
    // SUBIR LRC
    // =============================================
    function uploadLrc() {
        const song = window.state?.currentSong;
        if (!song) return;
        const inp = document.createElement('input');
        inp.type = 'file'; inp.accept = '.lrc';
        inp.onchange = async (e) => {
            const file = e.target.files[0];
            if (!file?.name.toLowerCase().endsWith('.lrc')) {
                window.showToast?.('Solo archivos .lrc', true); return;
            }
            const fd = new FormData(); fd.append('file', file);
            try {
                const res  = await fetch(`/api/letras/subir/${song.id}`, { method: 'POST', body: fd });
                const json = await res.json();
                if (res.ok) {
                    window.showToast?.(json.message || '✅ Letra subida');
                    window.cache?.letras?.delete(song.id);
                    close(); setTimeout(open, 300);
                } else {
                    window.showToast?.(json.error || 'Error al subir', true);
                }
            } catch { window.showToast?.('Error de conexión', true); }
            inp.remove();
        };
        document.body.appendChild(inp); inp.click();
    }

    // =============================================
    // CAMBIO DE CANCIÓN
    // =============================================
    async function onSongChange(song) {
        if (!modal) return;

        // Actualizar portada inmediatamente
        const cover = document.getElementById('alCover');
        if (cover) cover.src = song.portadaUrl;

        const bgArt = document.getElementById('alBgArt');
        if (bgArt) bgArt.style.backgroundImage = `url('${song.portadaUrl}')`;

        // Actualizar meta
        const title = document.getElementById('alTitle');
        const sub   = document.getElementById('alSub');
        if (title) title.textContent = song.nombre;
        if (sub)   sub.textContent   = song.artista + (song.album ? ' — ' + song.album : '');

        // Actualizar color y letras en paralelo
        const [clr, lyricsData] = await Promise.all([
            extractColor(song.portadaUrl),
            window.loadLyrics?.(song.id) || Promise.resolve({})
        ]);

        color = clr;
        modal.style.background = makeBg(...color);

        lines    = _parseAll(lyricsData);
        activeIdx = -1;
        userScroll = false;

        const inner = document.getElementById('alInner');
        if (inner) inner.innerHTML = _renderLines();
    }

    // =============================================
    // SYNC HELPERS
    // =============================================
    function _syncPlay() {
        const ico = document.getElementById('alPlayIco');
        if (ico) ico.className = `fas ${audio.paused ? 'fa-play' : 'fa-pause'}`;
    }

    function _syncDur() {
        const el = document.getElementById('alDur');
        if (el && audio.duration) el.textContent = _fmt(audio.duration);
    }

    function _syncBtns() {
        const shuf = document.getElementById('alShuf');
        const rep  = document.getElementById('alRep');
        if (shuf) shuf.classList.toggle('al-btn-on', !!window.state?.shuffleMode);
        if (rep)  rep.classList.toggle('al-btn-on', (window.state?.repeatMode || 0) > 0);
    }

    function _seek(time)    { audio.currentTime = Math.max(0, time); }
    function _setOffset(v)  {
        offset = parseFloat(v);
        const el = document.getElementById('alOffVal');
        if (el) el.textContent = offset.toFixed(1) + 's';
        activeIdx = -1; // forzar re-sync
    }

    function _fmt(s) {
        if (!s || isNaN(s)) return '0:00';
        return `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
    }

    function _esc(s) {
        if (!s) return '';
        return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
            .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
    }

    return { open, close, toggle, uploadLrc, onSongChange,
        _syncPlay, _syncBtns, _seek, _setOffset };

})();

// ── Exports globales ──
window.AppleLyrics     = AppleLyrics;
window.openLyricsModal  = () => AppleLyrics.open();
window.closeLyricsModal = () => AppleLyrics.close();

// ── Hook cambio de canción ──
(function() {
    const orig = window.playSongFromObject;
    if (!orig) return;
    window.playSongFromObject = function(song) {
        orig(song);
        AppleLyrics.onSongChange(song);
    };
})();

// =====================================================
// CSS
// =====================================================
(function injectCSS() {
    const s = document.createElement('style');
    s.textContent = `

/* ═══════════════════════════════════════════
   OVERLAY
═══════════════════════════════════════════ */
.al-overlay {
    position: fixed;
    inset: 0;
    z-index: 10000;
    opacity: 0;
    transform: translateY(100%);
    transition: transform 0.4s cubic-bezier(0.32,0,0.15,1),
                opacity   0.4s cubic-bezier(0.32,0,0.15,1);
    overflow: hidden;
    will-change: transform, opacity;
    font-family: -apple-system, 'Inter', 'Helvetica Neue', sans-serif;
}
.al-overlay.al-open {
    opacity: 1;
    transform: translateY(0);
}

/* Portada desenfocada de fondo */
.al-bg-art {
    position: absolute;
    inset: -50px;
    background-size: cover;
    background-position: center;
    filter: blur(70px) brightness(0.2) saturate(1.4);
    transform: scale(1.15);
    z-index: 0;
    will-change: filter;
    transition: background-image 0.5s ease;
}

/* Ruido sutil sobre el fondo */
.al-bg-noise {
    position: absolute;
    inset: 0;
    z-index: 1;
    background: rgba(0,0,0,0.22);
    pointer-events: none;
}

.al-overlay > * { position: relative; z-index: 2; }

/* Botón cerrar */
.al-close {
    position: absolute;
    top: 14px; left: 16px;
    z-index: 20;
    background: rgba(0,0,0,0.35);
    border: none; color: rgba(255,255,255,0.75);
    width: 36px; height: 36px;
    border-radius: 50%;
    display: flex; align-items: center; justify-content: center;
    font-size: 14px; cursor: pointer;
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    transition: background 0.18s, color 0.18s, transform 0.15s;
}
.al-close:hover { background: rgba(255,255,255,0.18); color: #fff; transform: scale(1.08); }
.al-close:active { transform: scale(0.94); }

/* ═══════════════════════════════════════════
   LAYOUT: dos columnas (Desktop)
═══════════════════════════════════════════ */
.al-layout {
    display: flex;
    height: 100%;
    overflow: hidden;
}

/* ═══════════════════════════════════════════
   COLUMNA IZQUIERDA (Desktop)
═══════════════════════════════════════════ */
.al-left {
    width: 400px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 14px;
    padding: 58px 28px 24px;
    background: rgba(0,0,0,0.14);
    backdrop-filter: blur(2px);
}

/* Portada */
.al-cover-wrap {
    width: 100%;
    max-width: 320px;
}
.al-cover {
    width: 100%;
    aspect-ratio: 1;
    border-radius: 14px;
    object-fit: cover;
    box-shadow: 0 22px 64px rgba(0,0,0,0.6), 0 4px 16px rgba(0,0,0,0.4);
    display: block;
    transition: transform 0.3s ease, box-shadow 0.3s ease;
}
.al-cover:hover { transform: scale(1.015); }

/* Meta */
.al-meta { width: 100%; max-width: 320px; }
.al-meta-title {
    font-size: 18px; font-weight: 800;
    color: #fff; margin-bottom: 3px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.al-meta-sub {
    font-size: 13px; color: rgba(255,255,255,0.58);
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

/* Progreso */
.al-prog-wrap { width: 100%; max-width: 320px; }
.al-prog-track {
    position: relative;
    height: 4px;
    background: rgba(255,255,255,0.18);
    border-radius: 2px;
    cursor: pointer;
    margin-bottom: 5px;
    transition: height 0.15s;
}
.al-prog-track:hover { height: 6px; }
.al-prog-fill {
    position: absolute; left: 0; top: 0; height: 100%;
    background: #fff; border-radius: 2px;
    pointer-events: none;
}
.al-prog-thumb {
    position: absolute; top: 50%;
    transform: translate(-50%, -50%) scale(0);
    width: 14px; height: 14px;
    background: #fff; border-radius: 50%;
    pointer-events: none;
    transition: transform 0.15s;
    box-shadow: 0 2px 6px rgba(0,0,0,0.35);
}
.al-prog-track:hover .al-prog-thumb { transform: translate(-50%, -50%) scale(1); }
.al-prog-times {
    display: flex; justify-content: space-between;
    font-size: 11px; color: rgba(255,255,255,0.4);
    font-variant-numeric: tabular-nums;
}

/* Controles */
.al-controls {
    display: flex; align-items: center;
    justify-content: space-between;
    width: 100%; max-width: 320px;
}
.al-btn {
    background: none; border: none;
    cursor: pointer; color: rgba(255,255,255,0.75);
    display: flex; align-items: center; justify-content: center;
    border-radius: 50%;
    transition: color 0.18s, background 0.18s, transform 0.15s;
    flex-shrink: 0; position: relative;
}
.al-btn-sm   { width: 44px; height: 44px; font-size: 19px; }
.al-btn-sm:hover { color: #fff; background: rgba(255,255,255,0.1); transform: scale(1.08); }
.al-btn-sm:active { transform: scale(0.92); }

/* Punto indicador de activo */
.al-btn-on { color: #fff !important; }
.al-btn-on::after {
    content: '';
    position: absolute; bottom: 4px; left: 50%;
    transform: translateX(-50%);
    width: 4px; height: 4px;
    background: #fff; border-radius: 50%;
}

/* Play button */
.al-btn-play {
    width: 60px; height: 60px;
    background: rgba(255,255,255,0.94) !important;
    color: #000 !important;
    font-size: 22px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.4);
}
.al-btn-play:hover  { transform: scale(1.06) !important; background: #fff !important; }
.al-btn-play:active { transform: scale(0.95) !important; }

/* Volumen + LRC */
.al-vol-row {
    display: flex; align-items: center;
    gap: 7px; width: 100%; max-width: 320px;
}
.al-vol-ico {
    color: rgba(255,255,255,0.4); font-size: 13px;
    cursor: pointer; flex-shrink: 0;
    transition: color 0.18s;
}
.al-vol-ico:hover { color: #fff; }
.al-vol-track {
    flex: 1; height: 4px;
    background: rgba(255,255,255,0.18);
    border-radius: 2px; cursor: pointer;
}
.al-vol-fill {
    height: 100%;
    background: rgba(255,255,255,0.72);
    border-radius: 2px; pointer-events: none;
}
.al-lrc-btn {
    background: rgba(0,0,0,0.28); border: none;
    color: rgba(255,255,255,0.62);
    width: 34px; height: 34px;
    border-radius: 50%;
    display: flex; align-items: center; justify-content: center;
    font-size: 12px; cursor: pointer;
    transition: all 0.18s; flex-shrink: 0;
}
.al-lrc-btn:hover { background: rgba(255,255,255,0.2); color: #fff; }

/* Offset de sincronización */
.al-offset-row {
    display: flex; align-items: center;
    gap: 6px; width: 100%; max-width: 320px;
    opacity: 0.38;
    transition: opacity 0.2s;
}
.al-offset-row:hover { opacity: 1; }
.al-offset-ico { color: rgba(255,255,255,0.5); font-size: 12px; }
.al-offset-slider {
    flex: 1; -webkit-appearance: none; appearance: none;
    height: 2px; background: rgba(255,255,255,0.22);
    border-radius: 1px; outline: none; cursor: pointer;
}
.al-offset-slider::-webkit-slider-thumb {
    -webkit-appearance: none;
    width: 12px; height: 12px;
    background: rgba(255,255,255,0.7);
    border-radius: 50%; cursor: pointer;
}
.al-offset-val {
    font-size: 10px; color: rgba(255,255,255,0.38);
    min-width: 32px; text-align: right;
    font-variant-numeric: tabular-nums;
}

/* ═══════════════════════════════════════════
   COLUMNA DERECHA — LETRAS (KARAOKE PURO)
═══════════════════════════════════════════ */
.al-right {
    flex: 1;
    display: flex;
    overflow: hidden;
    min-width: 0;
}

.al-mask {
    flex: 1;
    overflow: hidden;
    mask-image: linear-gradient(
        to bottom,
        transparent  0%,
        black       18%,
        black       82%,
        transparent 100%
    );
    -webkit-mask-image: linear-gradient(
        to bottom,
        transparent  0%,
        black       18%,
        black       82%,
        transparent 100%
    );
}

.al-scroll {
    height: 100%;
    overflow-y: auto;
    scrollbar-width: none;
    scroll-behavior: smooth;
    overscroll-behavior: contain;
}
.al-scroll::-webkit-scrollbar { display: none; }

.al-inner {
    padding: 130px 56px 150px 52px;
    min-height: 100%;
    display: flex;
    flex-direction: column;
    justify-content: center;
}

/* ── LÍNEAS: POR DEFECTO TODAS OCULTAS ── */
.al-line {
    font-size: 27px;
    font-weight: 700;
    line-height: 1.38;
    padding: 7px 0;
    cursor: pointer;
    user-select: none;
    -webkit-user-select: none;
    text-align: center;
    transition: opacity 0.3s ease, visibility 0.3s ease, transform 0.3s ease;
    opacity: 0;
    visibility: hidden;
    transform: scale(0.95);
}

/* Línea activa: totalmente visible y destacada */
.al-line.al-active {
    opacity: 1 !important;
    visibility: visible !important;
    font-size: 42px;
    font-weight: 800;
    color: #ffffff;
    letter-spacing: -0.3px;
    transform: scale(1);
    text-shadow: 0 2px 20px rgba(0,0,0,0.3);
}

/* Clase oculta (redundante pero por seguridad) */
.al-line.al-hidden {
    opacity: 0;
    visibility: hidden;
}

/* Sin letra */
.al-empty {
    display: flex; flex-direction: column;
    align-items: flex-start; gap: 14px;
    padding: 40px 0; color: rgba(255,255,255,0.22);
    font-size: 15px;
}
.al-empty i { font-size: 42px; }
.al-upload-cta {
    background: rgba(255,255,255,0.1);
    border: 1px solid rgba(255,255,255,0.18);
    color: #fff; padding: 10px 20px;
    border-radius: 20px; cursor: pointer;
    font-size: 13px; display: flex;
    align-items: center; gap: 8px;
    transition: background 0.18s;
}
.al-upload-cta:hover { background: rgba(255,255,255,0.2); }

/* ═══════════════════════════════════════════
   RESPONSIVE MÓVIL: Letras ARRIBA, Reproductor ABAJO
═══════════════════════════════════════════ */
@media (max-width: 860px) {
    /* Layout vertical invertido */
    .al-layout {
        flex-direction: column-reverse; /* Esto pone las letras arriba y el reproductor abajo */
        overflow: hidden;
    }

    /* Reproductor (antes izquierda, ahora abajo) */
    .al-left {
        width: 100%;
        flex-direction: row;
        flex-wrap: wrap;
        flex-shrink: 0;
        padding: 14px 14px 20px;
        gap: 8px;
        align-items: flex-start;
        justify-content: flex-start;
        background: rgba(0,0,0,0.22);
        max-height: 42vh;
        overflow-y: auto;
        order: 2; /* Asegura que esté abajo */
    }
    
    /* Portada más pequeña en móvil */
    .al-cover-wrap { 
        width: 70px; 
        flex-shrink: 0; 
    }
    .al-cover { 
        border-radius: 10px; 
    }
    .al-meta { 
        flex: 1; 
        min-width: 0; 
        align-self: center; 
    }
    .al-meta-title { 
        font-size: 14px; 
    }
    .al-meta-sub { 
        font-size: 11px; 
    }
    
    /* Controles más compactos */
    .al-prog-wrap, .al-controls, .al-vol-row, .al-offset-row {
        width: 100%; 
        max-width: 100%; 
        order: 3;
    }
    
    .al-controls {
        gap: 8px;
        justify-content: center;
    }
    
    .al-btn-sm { 
        width: 38px; 
        height: 38px; 
        font-size: 16px; 
    }
    
    .al-btn-play { 
        width: 48px; 
        height: 48px; 
        font-size: 18px; 
    }
    
    .al-vol-row {
        margin-top: 5px;
    }

    /* Letras (antes derecha, ahora arriba) */
    .al-right {
        flex: 1;
        min-height: 0;
        overflow: hidden;
        display: flex;
        flex-direction: column;
        order: 1; /* Asegura que esté arriba */
    }
    
    .al-mask {
        flex: 1;
        min-height: 0;
        overflow: hidden;
        mask-image: linear-gradient(
            to bottom,
            transparent  0%,
            black       12%,
            black       78%,
            transparent 100%
        );
        -webkit-mask-image: linear-gradient(
            to bottom,
            transparent  0%,
            black       12%,
            black       78%,
            transparent 100%
        );
    }
    
    .al-scroll {
        height: 100%;
        overflow-y: auto;
        -webkit-overflow-scrolling: touch;
    }
    
    .al-inner {
        padding: 30px 20px;
        display: flex;
        flex-direction: column;
        justify-content: center;
        min-height: 100%;
    }
    
    .al-empty { 
        align-items: center; 
        text-align: center;
        padding: 20px;
    }
    
    /* Tamaño de letra en móvil */
    .al-line.al-active {
        font-size: 28px;
        padding: 12px 0;
    }
    
    /* Ocultar offset en móvil por espacio */
    .al-offset-row { 
        display: none; 
    }
}

@media (max-width: 520px) {
    .al-left {
        padding: 10px 12px 16px;
        gap: 6px;
        max-height: 40vh;
    }
    .al-cover-wrap { width: 60px; }
    .al-inner { padding: 20px 16px; }
    .al-line.al-active { font-size: 24px; }
    .al-btn-sm { width: 34px; height: 34px; font-size: 14px; }
    .al-btn-play { width: 44px; height: 44px; font-size: 16px; }
    .al-meta-title { font-size: 12px; }
    .al-meta-sub { font-size: 10px; }
    .al-prog-times { font-size: 9px; }
}

/* Touch feedback */
@media (hover: none) {
    .al-line:hover { color: inherit !important; }
    .al-btn-sm:active  { transform: scale(0.88); background: rgba(255,255,255,0.12); }
    .al-btn-play:active { transform: scale(0.92) !important; }
}
`;
    document.head.appendChild(s);
})();
