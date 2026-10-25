/* =====================================================
   uiux.js — versión 2.0
   Requiere: general.js cargado antes
   ===================================================== */

// =====================================================
// 1. MINI PLAYER AL HACER SCROLL
// =====================================================
(function setupMiniPlayer() {
    const player      = document.getElementById('globalPlayer');
    const mainContent = document.getElementById('mainContent');

    const miniProgress = document.createElement('div');
    miniProgress.className = 'mini-progress';
    miniProgress.innerHTML = '<div class="mini-progress-fill" id="miniProgressFill"></div>';
    player.appendChild(miniProgress);

    const hint = document.createElement('div');
    hint.className = 'expand-hint';
    hint.textContent = 'Clic para expandir';
    player.appendChild(hint);

    const miniCtrls = document.createElement('div');
    miniCtrls.className = 'mini-controls';
    miniCtrls.innerHTML = `
        <button class="mini-control-btn" onclick="prevSong()"><i class="fas fa-step-backward"></i></button>
        <button class="mini-control-btn mini-play-btn" onclick="togglePlayPause()"><i class="fas fa-play" id="miniPlayIcon"></i></button>
        <button class="mini-control-btn" onclick="nextSong()"><i class="fas fa-step-forward"></i></button>`;
    player.appendChild(miniCtrls);

    let isMini   = false;
    let scrollLock = false;

    mainContent.addEventListener('scroll', () => {
        if (scrollLock) return;
        const scrollTop = mainContent.scrollTop;
        if (scrollTop > 60 && !isMini) {
            isMini = true;
            player.classList.add('mini');
        } else if (scrollTop <= 20 && isMini) {
            isMini = false;
            player.classList.remove('mini');
        }
    });

    player.addEventListener('click', (e) => {
        if (!isMini) return;
        if (e.target.closest('.mini-control-btn') || e.target.closest('.player-actions')) return;
        scrollLock = true;
        isMini = false;
        player.classList.remove('mini');
        mainContent.scrollTo({ top: 0, behavior: 'smooth' });
        setTimeout(() => { scrollLock = false; }, 600);
    });

    const audioEl = document.getElementById('audioElement');
    audioEl.addEventListener('timeupdate', () => {
        if (!audioEl.duration) return;
        const pct  = (audioEl.currentTime / audioEl.duration) * 100;
        const fill = document.getElementById('miniProgressFill');
        if (fill) fill.style.width = pct + '%';
        const miniIcon = document.getElementById('miniPlayIcon');
        if (miniIcon) miniIcon.className = audioEl.paused ? 'fas fa-play' : 'fas fa-pause';
    });
})();


// =====================================================
// 2. TEMA DINÁMICO POR ÁLBUM
// =====================================================
const AlbumTheme = (function() {
    function extractColor(imgEl) {
        return new Promise((resolve) => {
            try {
                const canvas = document.createElement('canvas');
                const ctx    = canvas.getContext('2d');
                canvas.width = canvas.height = 16;
                const img = new Image();
                img.crossOrigin = 'anonymous';
                img.onload = () => {
                    ctx.drawImage(img, 0, 0, 16, 16);
                    const data = ctx.getImageData(0, 0, 16, 16).data;
                    let r = 0, g = 0, b = 0, count = 0;
                    for (let i = 0; i < data.length; i += 4) {
                        const pr = data[i], pg = data[i+1], pb = data[i+2];
                        const max = Math.max(pr, pg, pb), min = Math.min(pr, pg, pb);
                        const sat = max === 0 ? 0 : (max - min) / max;
                        if (sat > 0.3 && max > 60 && max < 230) { r += pr; g += pg; b += pb; count++; }
                    }
                    resolve(count > 10 ? boostSaturation(Math.round(r/count), Math.round(g/count), Math.round(b/count)) : [29, 185, 84]);
                };
                img.onerror = () => resolve([29, 185, 84]);
                img.src = imgEl.src;
            } catch { resolve([29, 185, 84]); }
        });
    }

    function boostSaturation(r, g, b) {
        const rn = r/255, gn = g/255, bn = b/255;
        const max = Math.max(rn,gn,bn), min = Math.min(rn,gn,bn);
        let h, s, l = (max+min)/2;
        if (max === min) { h = s = 0; } else {
            const d = max-min;
            s = l > 0.5 ? d/(2-max-min) : d/(max+min);
            switch(max){ case rn: h=(gn-bn)/d+(gn<bn?6:0); break; case gn: h=(bn-rn)/d+2; break; default: h=(rn-gn)/d+4; }
            h /= 6;
        }
        s = Math.min(1, s*1.6); l = Math.min(0.55, Math.max(0.3, l));
        function hue2rgb(p,q,t){ if(t<0)t+=1; if(t>1)t-=1; if(t<1/6)return p+(q-p)*6*t; if(t<1/2)return q; if(t<2/3)return p+(q-p)*(2/3-t)*6; return p; }
        const q2 = l<0.5?l*(1+s):l+s-l*s, p2 = 2*l-q2;
        return [Math.round(hue2rgb(p2,q2,h+1/3)*255), Math.round(hue2rgb(p2,q2,h)*255), Math.round(hue2rgb(p2,q2,h-1/3)*255)];
    }

    function applyColor(rgb) {
        const [r,g,b] = rgb;
        const root = document.documentElement;
        root.style.setProperty('--album-color',        `rgb(${r},${g},${b})`);
        root.style.setProperty('--album-color-dark',   `rgb(${Math.round(r*.6)},${Math.round(g*.6)},${Math.round(b*.6)})`);
        root.style.setProperty('--album-color-subtle', `rgba(${r},${g},${b},0.12)`);
        root.style.setProperty('--album-r', r);
        root.style.setProperty('--album-g', g);
        root.style.setProperty('--album-b', b);
        const player = document.getElementById('globalPlayer');
        player.style.borderTop = `1px solid rgba(${r},${g},${b},0.3)`;
        const main = document.getElementById('mainContent');
        main.style.background = `linear-gradient(180deg, rgba(${r},${g},${b},0.1) 0%, #0a0a0a 300px)`;
    }

    async function updateFromCover(coverUrl) {
        const img = new Image();
        img.crossOrigin = 'anonymous';
        img.src = coverUrl;
        await new Promise(r => { img.onload = r; img.onerror = r; });
        applyColor(await extractColor(img));
    }

    return { updateFromCover, applyColor };
})();


// =====================================================
// 3. NOW PLAYING FULLSCREEN — estilo Spotify imagen 2
// =====================================================
const NowPlaying = (function() {
    let overlay      = null;
    let lyricsLines  = [];
    let lyricsInterval = null;
    let currentLine  = -1;
    let lyricsOffset = 0;
    let activeTab    = 'lyrics';
    const audioEl    = document.getElementById('audioElement');

    function isOpen() { return overlay && overlay.classList.contains('np-open'); }

    let clockInterval = null;

    function _startClock() {
        _tickClock();
        clockInterval = setInterval(_tickClock, 1000);
    }
    function _stopClock() {
        if (clockInterval) { clearInterval(clockInterval); clockInterval = null; }
    }
    function _tickClock() {
        const el = document.getElementById('npClock');
        if (!el) return;
        const now = new Date();
        let h = now.getHours(), m = now.getMinutes();
        const ampm = h >= 12 ? 'PM' : 'AM';
        h = h % 12 || 12;
        el.textContent = `${h}:${String(m).padStart(2,'0')} ${ampm}`;
    }

    function open() {
        if (!overlay) create();
        overlay.classList.add('np-open');
        const song = window.state?.currentSong;
        if (song) _syncSong(song);
        loadAndRenderLyrics();
        startLyricsSync();
        _startClock();
    }

    function close() {
        if (!overlay) return;
        overlay.classList.remove('np-open');
        stopLyricsSync();
        _stopClock();
        Visualizer.detachFromNP();
    }

    function toggle() { isOpen() ? close() : open(); }

    function create() {
        overlay = document.createElement('div');
        overlay.className = 'np-overlay';
        overlay.id = 'nowPlayingOverlay';

        overlay.innerHTML = `
            <!-- Fondo: portada difuminada cubre TODO -->
            <div class="np-bg-img" id="npBgImg"></div>
            <!-- Overlay oscuro encima del blur para legibilidad -->
            <div class="np-bg-overlay"></div>

            <!-- Botón cerrar -->
            <button class="np-btn-close" onclick="NowPlaying.close()">
                <i class="fas fa-chevron-down"></i>
            </button>

            <!-- Topbar: info izquierda + hora centro -->
            <div class="np-topbar">
                <div class="np-topbar-left">
                    <i class="fas fa-circle-dot np-topbar-icon"></i>
                    <div class="np-topbar-text">
                        <span class="np-topbar-label" id="npTopLabel">REPRODUCIENDO</span>
                        <span class="np-topbar-source" id="npTopSource">—</span>
                    </div>
                </div>
                <div class="np-topbar-clock" id="npClock">00:00</div>
                <button class="np-btn-close-top" onclick="NowPlaying.close()">
                    <i class="fas fa-chevron-down"></i>
                </button>
            </div>

            <!-- Cuerpo principal: portada izquierda + letra derecha -->
            <div class="np-body">

                <!-- IZQUIERDA: portada + controles -->
                <div class="np-left-col">
                    <div class="np-cover-wrap">
                        <img class="np-cover-img" id="npCoverImg"
                             src="https://singlecolorimage.com/get/1DB954/400x400">
                    </div>

                    <!-- Info canción -->
                    <div class="np-song-row">
                        <div class="np-song-text">
                            <h2 class="np-song-name" id="npSongName">—</h2>
                            <p  class="np-song-artist" id="npSongArtist">—</p>
                        </div>
                        <button class="np-fav-btn" id="npFavBtn" onclick="NowPlaying.toggleFav()">
                            <i class="fas fa-heart"></i>
                        </button>
                    </div>

                    <!-- Barra de progreso -->
                    <div class="np-prog-wrap">
                        <div class="np-prog-bg" id="npProgBg">
                            <div class="np-prog-fill" id="npProgFill"></div>
                            <div class="np-prog-thumb" id="npProgThumb"></div>
                        </div>
                        <div class="np-prog-times">
                            <span id="npCurTime">0:00</span>
                            <span id="npDurTime">0:00</span>
                        </div>
                    </div>

                    <!-- Controles de reproducción -->
                    <div class="np-ctrl-row">
                        <button class="np-ctrl-btn np-ctrl-sm" id="npShuffleBtn"
                                onclick="toggleShuffle(); NowPlaying._syncButtons()">
                            <i class="fas fa-random"></i>
                        </button>
                        <button class="np-ctrl-btn np-ctrl-sm" onclick="prevSong()">
                            <i class="fas fa-backward-step"></i>
                        </button>
                        <button class="np-ctrl-btn np-ctrl-play" onclick="togglePlayPause(); NowPlaying._syncPlay()">
                            <i class="fas fa-play" id="npPlayIcon"></i>
                        </button>
                        <button class="np-ctrl-btn np-ctrl-sm" onclick="nextSong()">
                            <i class="fas fa-forward-step"></i>
                        </button>
                        <button class="np-ctrl-btn np-ctrl-sm" id="npRepeatBtn"
                                onclick="toggleRepeatMode(); NowPlaying._syncButtons()">
                            <i class="fas fa-repeat"></i>
                        </button>
                    </div>

                    <!-- Volumen + extras -->
                    <div class="np-extras-row">
                        <div class="np-vol-wrap">
                            <i class="fas fa-volume-down np-vol-icon" onclick="toggleMute()"></i>
                            <div class="np-vol-bar" id="npVolBar">
                                <div class="np-vol-fill" id="npVolFill" style="width:70%"></div>
                            </div>
                            <i class="fas fa-volume-up np-vol-icon" onclick="toggleMute()"></i>
                        </div>
                        <div class="np-action-btns">
                            <button class="np-action-btn" onclick="NowPlaying._uploadLrc()" title="Subir archivo LRC">
                                <i class="fas fa-upload"></i>
                            </button>
                            <button class="np-action-btn" onclick="toggleQueuePanel()" title="Ver cola">
                                <i class="fas fa-list-ul"></i>
                            </button>
                        </div>
                    </div>

                    <!-- Sin visualizador de ondas -->
                </div>

                <!-- DERECHA: letra sincronizada -->
                <div class="np-right-col">
                    <div class="np-lyrics-mask" id="npLyricsMask">
                        <div class="np-lyrics-inner" id="npLyricsInner">
                            <div class="np-lyrics-loading">
                                <i class="fas fa-music"></i>
                                <p>Cargando letra…</p>
                            </div>
                        </div>
                    </div>
                    <div class="np-sync-row">
                        <i class="fas fa-sliders" style="color:rgba(255,255,255,0.3);font-size:12px"></i>
                        <input type="range" min="-5" max="5" step="0.1" value="0"
                               class="np-sync-slider" id="npSyncSlider"
                               oninput="NowPlaying._setOffset(this.value)">
                        <span class="np-sync-val" id="npSyncVal">0.0s</span>
                    </div>
                </div>
            </div>`;

        document.body.appendChild(overlay);
        _setupProgressDrag();
        _setupVolumeDrag();
        _setupAudioListeners();
    }

    /* Drag en barra de progreso */
    function _setupProgressDrag() {
        const bg = document.getElementById('npProgBg');
        if (!bg) return;
        let dragging = false;
        const seek = (e) => {
            const rect = bg.getBoundingClientRect();
            const x = e.touches ? e.touches[0].clientX : e.clientX;
            const pct = Math.max(0, Math.min(1, (x - rect.left) / rect.width));
            if (audioEl.duration) audioEl.currentTime = pct * audioEl.duration;
        };
        bg.addEventListener('mousedown',  (e) => { dragging = true; seek(e); });
        bg.addEventListener('touchstart', (e) => { dragging = true; seek(e); }, { passive: true });
        document.addEventListener('mousemove',  (e) => { if (dragging) seek(e); });
        document.addEventListener('touchmove',  (e) => { if (dragging) seek(e); }, { passive: true });
        document.addEventListener('mouseup',    () => { dragging = false; });
        document.addEventListener('touchend',   () => { dragging = false; });
    }

    function _setupVolumeDrag() {
        const bar = document.getElementById('npVolBar');
        if (!bar) return;
        bar.addEventListener('click', (e) => {
            const rect = bar.getBoundingClientRect();
            const vol  = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
            audioEl.volume = vol;
            const fill = document.getElementById('npVolFill');
            if (fill) fill.style.width = (vol * 100) + '%';
        });
    }

    function _setupAudioListeners() {
        audioEl.addEventListener('timeupdate',    _syncProgress);
        audioEl.addEventListener('loadedmetadata',_syncProgress);
        audioEl.addEventListener('play',  _syncPlay);
        audioEl.addEventListener('pause', _syncPlay);
    }

    function _syncProgress() {
        if (!isOpen() || !audioEl.duration) return;
        const pct   = (audioEl.currentTime / audioEl.duration) * 100;
        const fill  = document.getElementById('npProgFill');
        const thumb = document.getElementById('npProgThumb');
        const ct    = document.getElementById('npCurTime');
        const dur   = document.getElementById('npDurTime');
        if (fill)  fill.style.width  = pct + '%';
        if (thumb) thumb.style.left  = pct + '%';
        if (ct)    ct.textContent    = _fmt(audioEl.currentTime);
        if (dur)   dur.textContent   = _fmt(audioEl.duration);
    }

    function _syncPlay() {
        const icon = document.getElementById('npPlayIcon');
        if (icon) icon.className = audioEl.paused ? 'fas fa-play' : 'fas fa-pause';
    }

    function _syncSong(song) {
        if (!overlay) return;
        const cover  = document.getElementById('npCoverImg');
        const bgImg  = document.getElementById('npBgImg');
        const name   = document.getElementById('npSongName');
        const artist = document.getElementById('npSongArtist');
        const favBtn = document.getElementById('npFavBtn');
        const src    = document.getElementById('npTopSource');

        if (cover)  cover.src                   = song.portadaUrl;
        if (bgImg)  bgImg.style.backgroundImage = `url('${song.portadaUrl}')`;
        if (name)   name.textContent             = song.nombre;
        if (artist) artist.textContent           = song.artista;
        if (favBtn) favBtn.classList.toggle('np-fav-active', !!song.favorito);
        // Mostrar álbum en topbar
        if (src)    src.textContent = song.album || '';
    }

    function update(song) {
        _syncSong(song);
        loadAndRenderLyrics();
    }

    /* Parsers LRC */
    function parseLrcNP(lrc) {
        const lines = [], regex = /\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)/g;
        let m;
        while ((m = regex.exec(lrc)) !== null) {
            let ms = m[3] ? parseInt(m[3]) : 0;
            if (ms > 99) ms /= 1000; else ms /= 100;
            const time = parseInt(m[1])*60 + parseInt(m[2]) + ms;
            const text = m[4].trim().replace(/\[.*?\]/g,'').trim();
            if (text) lines.push({ time, text });
        }
        return lines.sort((a,b) => a.time - b.time);
    }
    function plainToLinesNP(txt) {
        const rows = txt.split('\n').filter(l => l.trim());
        const tpl  = (audioEl.duration || rows.length * 4) / rows.length;
        return rows.map((t,i) => ({ time: i * tpl, text: t.trim() }));
    }

    async function loadAndRenderLyrics() {
        const inner = document.getElementById('npLyricsInner');
        if (!inner) return;
        inner.innerHTML = '<div class="np-lyrics-loading"><i class="fas fa-circle-notch fa-spin"></i></div>';
        if (!window.state?.currentSong) return;
        const data = await window.loadLyrics(window.state.currentSong.id);
        lyricsLines = [];
        if (data.letraSincronizada?.trim()) lyricsLines = parseLrcNP(data.letraSincronizada);
        else if (data.letra?.trim())        lyricsLines = plainToLinesNP(data.letra);
        _renderLyrics();
    }

    function _renderLyrics() {
        const inner = document.getElementById('npLyricsInner');
        if (!inner) return;
        if (!lyricsLines.length) {
            inner.innerHTML = `
                <div class="np-no-lyrics">
                    <i class="fas fa-music"></i>
                    <p>Sin letra disponible</p>
                </div>`;
            return;
        }
        inner.innerHTML = lyricsLines.map((l,i) =>
        `<div class="np-lyric-line" data-idx="${i}" data-time="${l.time}"
              onclick="document.getElementById('audioElement').currentTime=${l.time - 0.1}">
            ${escapeHtml(l.text)}
         </div>`
        ).join('');
    }

    function startLyricsSync() {
        stopLyricsSync();
        lyricsInterval = setInterval(() => {
            if (!lyricsLines.length || !isOpen()) return;
            const t      = audioEl.currentTime + lyricsOffset;
            let active   = -1;
            for (let i = 0; i < lyricsLines.length; i++) {
                if (t >= lyricsLines[i].time) active = i; else break;
            }
            if (active === currentLine) return;
            currentLine  = active;
            const all    = document.querySelectorAll('#npLyricsInner .np-lyric-line');
            all.forEach((el, i) => {
                el.classList.remove('np-lyric-active','np-lyric-past','np-lyric-next');
                if      (i === active)   el.classList.add('np-lyric-active');
                else if (i < active)     el.classList.add('np-lyric-past');
                else if (i === active+1) el.classList.add('np-lyric-next');
            });
            // Scroll: línea activa al 38% del contenedor
            if (active >= 0) {
                const mask   = document.getElementById('npLyricsMask');
                const activeLine = all[active];
                if (mask && activeLine) {
                    const target = activeLine.offsetTop - mask.clientHeight * 0.38 + activeLine.clientHeight / 2;
                    mask.scrollTo({ top: target, behavior: 'smooth' });
                }
            }
        }, 80);
    }

    function stopLyricsSync() {
        if (lyricsInterval) { clearInterval(lyricsInterval); lyricsInterval = null; }
        currentLine = -1;
    }

    function toggleFav() {
        if (!window.state?.currentSong) return;
        window.toggleCurrentFavorite();
        const btn = document.getElementById('npFavBtn');
        if (btn) btn.classList.toggle('np-fav-active');
    }

    function _setOffset(val) {
        lyricsOffset = parseFloat(val);
        const span = document.getElementById('npSyncVal');
        if (span) span.textContent = lyricsOffset.toFixed(1) + 's';
    }

    function _syncButtons() {
        const sb = document.getElementById('npShuffleBtn');
        const rb = document.getElementById('npRepeatBtn');
        if (sb) sb.classList.toggle('np-btn-active', !!window.state?.shuffleMode);
        if (rb) rb.classList.toggle('np-btn-active',  window.state?.repeatMode > 0);
    }

    function _fmt(s) {
        if (isNaN(s)) return '0:00';
        return `${Math.floor(s/60)}:${Math.floor(s%60).toString().padStart(2,'0')}`;
    }

    function _uploadLrc() {
        const song = window.state?.currentSong;
        if (!song) { showToast('No hay canción reproduciéndose', true); return; }
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.lrc';
        input.onchange = async (e) => {
            const file = e.target.files[0];
            if (!file || !file.name.toLowerCase().endsWith('.lrc')) {
                showToast('Solo archivos .lrc', true); return;
            }
            const formData = new FormData();
            formData.append('file', file);
            try {
                const res  = await fetch(`/api/letras/subir/${song.id}`, { method: 'POST', body: formData });
                const json = await res.json();
                if (res.ok) {
                    showToast(json.message || '✅ Letra sincronizada subida');
                    if (window.cache?.letras) window.cache.letras.delete(song.id);
                    loadAndRenderLyrics();
                } else {
                    showToast(json.error || 'Error al subir', true);
                }
            } catch { showToast('Error de conexión', true); }
            input.remove();
        };
        document.body.appendChild(input);
        input.click();
    }

    return { open, close, toggle, isOpen, update,
        toggleFav, _syncPlay, _syncButtons, _setOffset, _uploadLrc };
})();

window.NowPlaying    = NowPlaying;
window.openNowPlaying = () => NowPlaying.open();

// Portada del player abre Now Playing al hacer clic
document.getElementById('currentCover').style.cursor = 'pointer';
document.getElementById('currentCover').title        = 'Ver ahora reproduciendo';
document.getElementById('currentCover').addEventListener('click', () => NowPlaying.toggle());
document.getElementById('currentSong').style.cursor  = 'pointer';
document.getElementById('currentSong').addEventListener('click', () => NowPlaying.toggle());


// =====================================================
// 4. HOOK playSongFromObject → sincronizar NowPlaying y AlbumTheme
// =====================================================
const _origPlaySongFromObject = window.playSongFromObject;
window.playSongFromObject = function(song) {
    _origPlaySongFromObject(song);
    if (song.portadaUrl) {
        AlbumTheme.updateFromCover(song.portadaUrl);
    }
    if (NowPlaying.isOpen()) NowPlaying.update(song);
    Visualizer.startIfActive();
};


// =====================================================
// 5. VISUALIZADOR DE AUDIO (Web Audio API)
// =====================================================
const Visualizer = (function() {
    let audioCtx = null, analyser = null, source = null;
    let animFrame = null, active = false, npCanvas = null;
    const audioEl = document.getElementById('audioElement');

    function init() {
        if (audioCtx) return;
        try {
            audioCtx  = new (window.AudioContext || window.webkitAudioContext)();
            analyser  = audioCtx.createAnalyser();
            analyser.fftSize = 128;
            source    = audioCtx.createMediaElementSource(audioEl);
            source.connect(analyser);
            analyser.connect(audioCtx.destination);
        } catch(e) { console.warn('Web Audio API no disponible:', e); }
    }

    function drawBars(canvas, colorStr, h) {
        if (!analyser || !canvas) return;
        const ctx    = canvas.getContext('2d');
        const W      = canvas.width  = canvas.offsetWidth;
        const H      = canvas.height = h || canvas.offsetHeight;
        const bufLen = analyser.frequencyBinCount;
        const data   = new Uint8Array(bufLen);
        analyser.getByteFrequencyData(data);
        ctx.clearRect(0, 0, W, H);
        const barW = (W / bufLen) * 2.2;
        let x = 0;
        for (let i = 0; i < bufLen; i++) {
            const barH  = (data[i] / 255) * H;
            const alpha = 0.4 + (data[i] / 255) * 0.6;
            ctx.fillStyle = colorStr || `rgba(255,255,255,${alpha})`;
            ctx.beginPath();
            ctx.roundRect(x, H - barH, Math.max(2, barW - 1), barH, 2);
            ctx.fill();
            x += barW + 1;
        }
    }

    function loop() {
        if (!active) return;
        if (npCanvas && document.getElementById('nowPlayingOverlay')?.classList.contains('np-open')) {
            drawBars(npCanvas, null, 60);
        }
        animFrame = requestAnimationFrame(loop);
    }

    function start() { init(); if (audioCtx?.state === 'suspended') audioCtx.resume(); active = true; if (!animFrame) loop(); }
    function stop()  { active = false; if (animFrame) { cancelAnimationFrame(animFrame); animFrame = null; } }
    function startIfActive() { if (active) start(); }
    function attachToNP()    { npCanvas = document.getElementById('npVisualizer'); if (npCanvas) start(); }
    function detachFromNP()  { npCanvas = null; stop(); }

    audioEl.addEventListener('play',  () => { if (document.getElementById('nowPlayingOverlay')?.classList.contains('np-open')) attachToNP(); });
    audioEl.addEventListener('pause', stop);
    return { start, stop, startIfActive, attachToNP, detachFromNP };
})();
window.Visualizer = Visualizer;


// =====================================================
// 6. BÚSQUEDA MEJORADA ESTILO SPOTIFY
// =====================================================
window._uiuxDisplayResults = function(results, query) {
    const content = document.getElementById('dynamicContent');
    const filter  = window.state.currentFilter;

    if (!results?.length) {
        content.innerHTML = `
            <div style="text-align:center;padding:80px 20px;color:#6b7280">
                <i class="fas fa-search" style="font-size:56px;margin-bottom:16px;display:block;opacity:0.3"></i>
                <h3 style="font-size:20px;margin-bottom:8px;color:#fff">Sin resultados para "${escapeHtml(query)}"</h3>
                <p>Prueba con otro nombre de canción, artista o álbum</p>
            </div>`;
        return;
    }

    const topResult  = results[0];
    const canciones  = results.slice(0, 10);
    const albumesMap = new Map();
    results.forEach(c => { if (c.album && !albumesMap.has(c.album)) albumesMap.set(c.album, c); });
    const artistas = [...new Map(results.map(c=>[c.artista,c])).values()].slice(0,6);

    let html = `<div class="search-results-page">
        <h2 style="font-size:22px;font-weight:800;margin-bottom:24px">Resultados para "<span style="color:var(--album-color)">${escapeHtml(query)}</span>"</h2>`;

    if (filter === 'all') {
        html += `<div style="display:grid;grid-template-columns:1fr 1.8fr;gap:20px;margin-bottom:40px" class="search-top-row">
            <div>
                <h3 style="font-size:14px;font-weight:700;text-transform:uppercase;letter-spacing:1px;color:#b3b3b3;margin-bottom:16px">Mejor resultado</h3>
                <div class="top-result-card" onclick="playSong(${topResult.id})">
                    <img src="${topResult.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/92x92'">
                    <h3>${escapeHtml(topResult.nombre)}</h3>
                    <div class="top-result-meta">
                        <span class="top-result-tag">Canción</span>
                        <span>${escapeHtml(topResult.artista)}</span>
                    </div>
                    <div class="top-result-play"><i class="fas fa-play"></i></div>
                </div>
            </div>
            <div>
                <h3 style="font-size:14px;font-weight:700;text-transform:uppercase;letter-spacing:1px;color:#b3b3b3;margin-bottom:16px">Canciones</h3>
                <div class="search-songs-list">
                    ${canciones.map((s,i) => `
                        <div class="search-song-row" onclick="playSong(${s.id})">
                            <span class="search-song-num">${i+1}</span>
                            <i class="search-song-play-icon fas fa-play"></i>
                            <img class="search-song-img" src="${s.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'">
                            <div class="search-song-info">
                                <div class="search-song-name">${escapeHtml(s.nombre)}</div>
                                <div class="search-song-artist">${escapeHtml(s.artista)}</div>
                            </div>
                            <span class="search-song-duration">${formatDuration(s.duracionSegundos)}</span>
                        </div>`).join('')}
                </div>
            </div>
        </div>`;
        if (artistas.length) html += `<div class="section"><h3 style="font-size:20px;font-weight:700;margin-bottom:16px">Artistas</h3><div class="grid" id="searchArtistasGrid"></div></div>`;
        if (albumesMap.size)  html += `<div class="section"><h3 style="font-size:20px;font-weight:700;margin-bottom:16px">Álbumes</h3><div class="grid" id="searchAlbumesGrid"></div></div>`;
    }
    if (filter === 'canciones') html += `<div class="search-songs-list" id="searchSongsList"></div>`;
    if (filter === 'albumes')   html += `<div class="grid" id="searchAlbumesGrid"></div>`;
    if (filter === 'playlists') html += `<div class="grid" id="searchPlaylistsGrid"></div>`;
    html += `</div>`;
    content.innerHTML = html;

    setTimeout(() => {
        if (document.getElementById('searchArtistasGrid')) {
            renderArtistasGrid(artistas.map(a => ({
                nombre: a.artista, portadaUrl: a.portadaUrl,
                totalCanciones: results.filter(r=>r.artista===a.artista).length,
                totalAlbumes: new Set(results.filter(r=>r.artista===a.artista).map(r=>r.album)).size,
                totalReproducciones: results.filter(r=>r.artista===a.artista).reduce((s,r)=>s+r.reproducciones,0)
            })), 'searchArtistasGrid');
        }
        const ag = document.getElementById('searchAlbumesGrid');
        if (ag) ag.innerHTML = [...albumesMap.values()].map(album => `
            <div class="song-card" onclick="verAlbum('${escapeHtml(album.album)}')">
                <div class="card-image"><img src="${album.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/180x180'"><div class="play-overlay"><i class="fas fa-play"></i></div></div>
                <h4>${escapeHtml(album.album)}</h4><p>${escapeHtml(album.artista)}</p>
            </div>`).join('');
        const sl = document.getElementById('searchSongsList');
        if (sl) sl.innerHTML = results.map((s,i) => `
            <div class="search-song-row" onclick="playSong(${s.id})">
                <span class="search-song-num">${i+1}</span><i class="search-song-play-icon fas fa-play"></i>
                <img class="search-song-img" src="${s.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'">
                <div class="search-song-info"><div class="search-song-name">${escapeHtml(s.nombre)}</div><div class="search-song-artist">${escapeHtml(s.artista)}</div></div>
                <span class="search-song-duration">${formatDuration(s.duracionSegundos)}</span>
            </div>`).join('');
        const pg = document.getElementById('searchPlaylistsGrid');
        if (pg && window.state.playlists) pg.innerHTML = window.state.playlists.map(p => `
            <div class="song-card" onclick="loadPlaylistView(${p.id})">
                <div class="card-image"><img src="${p.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/180x180'"><div class="play-overlay"><i class="fas fa-play"></i></div></div>
                <h4>${escapeHtml(p.nombre)}</h4><p>${p.cancionesCount||0} canciones</p>
            </div>`).join('');
    }, 0);
};


// =====================================================
// 7. VISTAS PLAYLIST Y ÁLBUM MEJORADAS
// =====================================================
window.loadPlaylistView = async function(id) {
    const content = document.getElementById('dynamicContent');
    content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i></div>';
    const p = await fetch(`/api/playlists/${id}`).then(r => r.json());
    const canciones = p.canciones || [];
    const durTotal  = canciones.reduce((a,c) => a+(c.duracionSegundos||0), 0);
    const portada   = p.portadaUrl || 'https://singlecolorimage.com/get/1DB954/192x192';
    content.innerHTML = `
        <div class="playlist-detail-header dynamic-hero">
            <img class="playlist-detail-cover" src="${portada}" onerror="this.src='https://singlecolorimage.com/get/1DB954/192x192'">
            <div class="playlist-detail-info">
                <p class="type-label">Playlist</p>
                <h1>${escapeHtml(p.nombre)}</h1>
                ${p.descripcion ? `<p style="color:rgba(255,255,255,0.7);margin-bottom:8px;font-size:14px">${escapeHtml(p.descripcion)}</p>` : ''}
                <p class="meta">${canciones.length} canciones • ${formatDurationLong(durTotal)}</p>
                <div class="playlist-detail-actions">
                    <button class="btn-green-pill" onclick="addMultipleToQueue(${JSON.stringify(canciones).replace(/"/g,'&quot;')})"><i class="fas fa-play"></i> Reproducir</button>
                    <button class="btn-icon-circle" onclick="addMultipleToQueue(${JSON.stringify(canciones).replace(/"/g,'&quot;')})" title="Cola"><i class="fas fa-list"></i></button>
                </div>
            </div>
        </div>
        <table class="spotify-table">
            <thead><tr><th style="width:40px">#</th><th>Título</th><th class="st-album-cell">Álbum</th><th style="text-align:right"><i class="fas fa-clock"></i></th><th style="width:32px"></th></tr></thead>
            <tbody>${canciones.map((s,i) => `
                <tr onclick="playSong(${s.id})" class="${window.state?.currentSong?.id===s.id?'playing':''}">
                    <td class="st-idx"><span class="st-num">${i+1}</span><span class="st-play"><i class="fas fa-play"></i></span></td>
                    <td><div class="st-song-cell"><img class="st-thumb" src="${s.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/40x40'"><div><div class="st-title">${escapeHtml(s.nombre)}</div><div class="st-artist">${escapeHtml(s.artista)}</div></div></div></td>
                    <td class="st-album-cell">${escapeHtml(s.album||'')}</td>
                    <td class="st-duration">${formatDuration(s.duracionSegundos)}</td>
                    <td class="st-menu"><div class="menu-btn" style="position:relative;top:0;right:0;background:none" onclick="event.stopPropagation();showContextMenu(event,${JSON.stringify(s).replace(/"/g,'&quot;')})"><i class="fas fa-ellipsis-h"></i></div></td>
                </tr>`).join('')}</tbody>
        </table>`;
    AlbumTheme.updateFromCover(portada);
};

window.verAlbum = async function(albumNombre) {
    const content = document.getElementById('dynamicContent');
    content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i></div>';
    try {
        const canciones = await fetch(`/api/canciones/album/${encodeURIComponent(albumNombre)}`).then(r=>r.json());
        if (!canciones.length) { content.innerHTML = '<div class="loading">No se encontraron canciones</div>'; return; }
        const portada  = canciones[0]?.portadaUrl || 'https://singlecolorimage.com/get/1DB954/220x220';
        const durTotal = canciones.reduce((a,c)=>a+(c.duracionSegundos||0),0);
        content.innerHTML = `
            <div class="album-detail-header">
                <img class="album-detail-cover" src="${portada}" onerror="this.src='https://singlecolorimage.com/get/1DB954/220x220'">
                <div class="album-detail-info">
                    <p class="type-label">Álbum</p>
                    <h1>${escapeHtml(albumNombre)}</h1>
                    <p style="color:rgba(255,255,255,0.7);margin:8px 0;font-size:14px">${escapeHtml(canciones[0]?.artista||'')} • ${canciones.length} canciones • ${formatDurationLong(durTotal)}</p>
                    <div class="playlist-detail-actions" style="margin-top:12px">
                        <button class="btn-green-pill" onclick="addMultipleToQueue(${JSON.stringify(canciones).replace(/"/g,'&quot;')})"><i class="fas fa-play"></i> Reproducir</button>
                        <button class="btn-icon-circle" onclick="loadSmartShuffleQueue(null,'${escapeHtml(canciones[0]?.artista||'')}')" title="Mix del artista"><i class="fas fa-magic"></i></button>
                    </div>
                </div>
            </div>
            <table class="spotify-table">
                <thead><tr><th style="width:40px">#</th><th>Título</th><th style="text-align:right"><i class="fas fa-clock"></i></th><th style="width:32px"></th></tr></thead>
                <tbody>${canciones.map((s,i) => `
                    <tr onclick="playSong(${s.id})" class="${window.state?.currentSong?.id===s.id?'playing':''}">
                        <td class="st-idx"><span class="st-num">${i+1}</span><span class="st-play"><i class="fas fa-play"></i></span></td>
                        <td><div class="st-song-cell"><div><div class="st-title">${escapeHtml(s.nombre)}</div><div class="st-artist">${escapeHtml(s.artista)}</div></div></div></td>
                        <td class="st-duration">${formatDuration(s.duracionSegundos)}</td>
                        <td class="st-menu"><div class="menu-btn" style="position:relative;top:0;right:0;background:none" onclick="event.stopPropagation();showContextMenu(event,${JSON.stringify(s).replace(/"/g,'&quot;')})"><i class="fas fa-ellipsis-h"></i></div></td>
                    </tr>`).join('')}</tbody>
            </table>`;
        AlbumTheme.updateFromCover(portada);
    } catch(e) { showToast('Error al cargar el álbum', true); }
};


// =====================================================
// 8. HOME DINÁMICO
// =====================================================
window.loadHome = async function() {
    const content = document.getElementById('dynamicContent');
    content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando...</div>';
    const [top, favoritos, albumes, artistas] = await Promise.all([
        window.loadTop(), window.loadFavoritos(), window.loadAlbumes(), window.loadArtistas()
    ]);
    const hora    = new Date().getHours();
    const saludo  = hora < 12 ? '🌅 Buenos días' : hora < 19 ? '☀️ Buenas tardes' : '🌙 Buenas noches';
    let html = `<h2 class="home-greeting">${saludo}</h2>`;
    if (top?.length) html += `<div class="quick-picks">${top.slice(0,6).map(s => `
        <div class="quick-pick-item" onclick="playSong(${s.id})">
            <img class="quick-pick-img" src="${s.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/64x64'">
            <div class="quick-pick-info"><strong>${escapeHtml(s.nombre)}</strong><span>${escapeHtml(s.artista)}</span></div>
            <div class="quick-pick-play"><i class="fas fa-play"></i></div>
        </div>`).join('')}</div>`;
    if (artistas?.length) html += `<div class="section"><div class="section-header"><h2>🎤 Artistas</h2><button onclick="loadArtistasView()" style="color:var(--album-color);background:none;border:none;cursor:pointer;font-size:13px;font-weight:600">Ver todos →</button></div><div class="scroll-row" id="artistasHomeRow"></div></div>`;
    if (favoritos?.length) html += `<div class="section"><div class="section-header"><h2>❤️ Favoritas</h2></div><div class="scroll-row" id="favRow"></div></div>`;
    if (albumes?.length) html += `<div class="section"><div class="section-header"><h2>📀 Álbumes</h2><button style="background:#1db954;color:#000;padding:8px 16px;border-radius:20px;font-size:12px;border:none;cursor:pointer;font-weight:700" onclick="loadSmartShuffleQueue()"><i class="fas fa-magic"></i> Mix inteligente</button></div><div class="scroll-row" id="albumRow"></div></div>`;
    content.innerHTML = html;
    if (artistas?.length) { const r=document.getElementById('artistasHomeRow'); if(r) r.innerHTML=artistas.slice(0,12).map(a=>`<div class="song-card artista-card" style="width:160px;flex-shrink:0" onclick="loadArtistaDetalle('${escapeHtml(a.nombre)}')"><div class="card-image artista-img-wrapper"><img src="${a.portadaUrl}" onerror="this.src='https://singlecolorimage.com/get/1DB954/160x160'" style="border-radius:50%"><div class="play-overlay"><i class="fas fa-play"></i></div></div><h4 style="text-align:center;font-size:13px">${escapeHtml(a.nombre)}</h4><p style="text-align:center;font-size:11px">${a.totalCanciones} canciones</p></div>`).join(''); }
    if (favoritos?.length) renderSongsWithMenu(favoritos.slice(0,12), 'favRow');
    if (albumes?.length)   renderAlbumes(albumes.slice(0,12), 'albumRow');
};


// =====================================================
// 9. ATAJOS DE TECLADO
// =====================================================
document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
    if (e.key === ' ')          { e.preventDefault(); window.togglePlayPause(); }
    if (e.key === 'ArrowRight' && e.altKey) { e.preventDefault(); window.nextSong(); }
    if (e.key === 'ArrowLeft'  && e.altKey) { e.preventDefault(); window.prevSong(); }
    if (e.key === 'f' || e.key === 'F')     NowPlaying.toggle();
    if (e.key === 'Escape' && NowPlaying.isOpen()) NowPlaying.close();
});
