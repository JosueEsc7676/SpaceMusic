// Pantalla "Reproduciendo ahora", letras sincronizadas y cola
import { api, session } from './api.js';
import { esc, $, bus, cover, coverImg, fmtTime, dominantColor, store, isMobile, fmtDate } from './util.js';
import { icon } from './icons.js';
import { player, prefs, fx } from './player.js';
import { lib } from './state.js';
import { toast, errorToast, sortable, songMenu, editLyrics, showMenu } from './ui.js';
import { parseLrc } from './lrc.js';

// ═══════════════════ Letras ═══════════════════

const lyricsCache = new Map();
const buscadas = new Set();

export { parseLrc };

const esSafari = /iP(hone|ad|od)/.test(navigator.userAgent) ||
  (/Safari/.test(navigator.userAgent) && !/Chrome|Chromium|Edg|OPR|Android/.test(navigator.userAgent));

/** Desplazamiento suave con desaceleración (como Apple Music). */
function scrollSuave(box, destino, ms = 650) {
  cancelAnimationFrame(box._anim);
  const ini = box.scrollTop, delta = destino - ini;
  if (Math.abs(delta) < 2) return;
  const t0 = performance.now();
  const ease = (x) => 1 - Math.pow(1 - x, 4);
  const paso = (now) => {
    const k = Math.min(1, (now - t0) / ms);
    box.scrollTop = ini + delta * ease(k);
    if (k < 1) box._anim = requestAnimationFrame(paso);
  };
  box._anim = requestAnimationFrame(paso);
}

async function getLyrics(song, { autoSearch = true } = {}) {
  if (lyricsCache.has(song.id)) return lyricsCache.get(song.id);
  let d = await api(`/api/letras/${song.id}`).catch(() => ({ letra: '', letraSincronizada: '' }));
  if (!d.letra && !d.letraSincronizada && autoSearch && !buscadas.has(song.id)) {
    buscadas.add(song.id);
    const r = await api(`/api/letras/${song.id}/buscar`, { method: 'POST' }).catch(() => null);
    if (r?.encontrada) { d = r; song.tieneLetra = true; song.letraSincronizada = !!r.letraSincronizada; }
  }
  lyricsCache.set(song.id, d);
  return d;
}
bus.on('lyrics:changed', (id) => { lyricsCache.delete(id); buscadas.delete(id); if (np.isOpen && player.current?.id === id) np.renderTab(); });

const offsets = store.get('sm_lrc_offset', {});
const getOffset = (id) => offsets[id] || 0;
function setOffset(id, v) {
  v = Math.round(v * 10) / 10;
  if (v) offsets[id] = v; else delete offsets[id];
  store.set('sm_lrc_offset', offsets);
}

// ═══════════════════ Cola (compartida con el panel lateral) ═══════════════════

export function renderQueue(box, { compact = false } = {}) {
  const cur = player.current;
  if (!cur) { box.innerHTML = `<div class="empty" style="padding:40px 10px">${icon('queue')}<h3>La cola está vacía</h3><p>Reproduce algo para empezar.</p></div>`; return; }
  const item = (s, i, current) => `<div class="q-item ${current ? 'current' : ''}" data-qi="${i}">
      ${s._user && !current ? '<span class="user-added" title="Agregada por ti"></span>' : ''}
      ${current ? '' : `<span class="drag" data-drag aria-label="Mover">${icon('drag', 'sm')}</span>`}
      ${coverImg(s.portadaUrl, 64)}
      <div class="tt"><div class="name ellipsis">${esc(s.nombre)}</div><div class="artist ellipsis">${esc(s.artista)}</div></div>
      ${current ? '' : `<button class="icon-btn" data-qrm="${i}" aria-label="Quitar de la cola">${icon('close', 'sm')}</button>`}
    </div>`;
  const up = player.upcoming;
  const ctxLabel = player.context?.label ? esc(player.context.label) : 'la cola';
  const limit = compact ? 60 : 200;
  box.innerHTML = `<div class="q-label">Sonando ahora</div>${item(cur, player.index, true)}
    ${up.length ? `<div class="q-label"><span>A continuación${player.context?.label ? ' · de ' + ctxLabel : ''}</span>
      <button class="btn sm ghost" data-qclear>Vaciar</button></div>
      <div data-qlist>${up.slice(0, limit).map((s, k) => item(s, player.index + 1 + k, false)).join('')}</div>
      ${up.length > limit ? `<div class="q-label">y ${up.length - limit} más…</div>` : ''}`
    : `<div class="q-label">${prefs.autoRadio ? 'Al terminar seguirá la radio con canciones parecidas' : 'No hay más canciones'}</div>`}`;
  const list = box.querySelector('[data-qlist]');
  if (list) sortable(list, '.q-item', '[data-drag]', (from, to) => player.move(player.index + 1 + from, player.index + 1 + to));
}

function bindQueue(box) {
  box.addEventListener('click', (e) => {
    if (e.target.closest('[data-qclear]')) { player.clearUpcoming(); return; }
    const rm = e.target.closest('[data-qrm]');
    if (rm) { e.stopPropagation(); player.removeAt(+rm.dataset.qrm); return; }
    const it = e.target.closest('.q-item');
    if (it && !it.closest('[data-qlist]')?._suppressClick && !e.target.closest('[data-drag]')) {
      const i = +it.dataset.qi;
      if (i === player.index) player.toggle(); else player.jump(i);
    }
  });
  box.addEventListener('contextmenu', (e) => {
    const it = e.target.closest('.q-item');
    if (!it) return;
    e.preventDefault();
    const s = player.items[+it.dataset.qi];
    if (s) songMenu(e, s);
  });
}

// ═══════════════════ Pantalla completa ═══════════════════

export const np = {
  el: null,
  isOpen: false,
  tab: store.get('sm_np_tab', 'letra'),
  lines: [],
  synced: false,
  active: -1,
  userScrollUntil: 0,
  raf: 0,

  build() {
    const el = document.createElement('div');
    el.className = 'np' + (esSafari ? ' safari' : '');
    el.setAttribute('aria-hidden', 'true');
    el.innerHTML = `<div class="np-bg"></div>
      <div class="np-top"><button class="round-btn" data-np-close aria-label="Cerrar">${icon('down')}</button>
        <div class="ctx"><div class="k">Reproduciendo desde</div><div class="v ellipsis" data-np-ctx></div></div>
        <button class="round-btn" data-np-more aria-label="Más opciones">${icon('more')}</button></div>
      <div class="np-body">
        <div class="np-left">
          <div class="np-art" data-np-art></div>
          <div class="np-meta"><div class="tt"><div class="name ellipsis" data-np-name></div><div class="artist ellipsis" data-np-artist></div></div>
            <button class="icon-btn" data-np-fav aria-label="Me gusta">${icon('heart')}</button></div>
          <div class="np-progress"><div class="seek" data-seek="np"><div class="rail"><div class="buf"></div><div class="fill"></div></div><div class="knob"></div><div class="tip"></div></div>
            <div class="times"><span data-np-cur>0:00</span><span data-np-dur>0:00</span></div></div>
          <div class="np-ctrls">
            <button class="icon-btn" data-c="shuffle" aria-label="Aleatorio">${icon('shuffle')}</button>
            <button class="icon-btn" data-c="prev" aria-label="Anterior">${icon('prev')}</button>
            <button class="big" data-c="play" aria-label="Reproducir">${icon('play')}</button>
            <button class="icon-btn" data-c="next" aria-label="Siguiente">${icon('next')}</button>
            <button class="icon-btn" data-c="repeat" aria-label="Repetir">${icon('repeat')}</button>
          </div>
          <div class="row np-only-mobile" style="justify-content:center;gap:10px">
            <button class="btn sm" data-mtab="letra" style="background:rgba(255,255,255,.14)">${icon('mic', 'sm')} Letra</button>
            <button class="btn sm" data-mtab="cola" style="background:rgba(255,255,255,.14)">${icon('queue', 'sm')} Cola</button>
          </div>
          <canvas class="np-visual" data-np-vis width="880" height="92"></canvas>
        </div>
        <div class="np-panel">
          <div class="np-tabs">
            <button data-tab="letra">Letra</button><button data-tab="cola">A continuación</button><button data-tab="info">Información</button>
            <span style="flex:1"></span><button class="np-only-mobile" data-mback aria-label="Volver">${icon('close', 'sm')}</button>
          </div>
          <div class="lyr-bar hidden" data-lyr-bar></div>
          <div class="np-tab" data-np-tab></div>
        </div>
      </div>`;
    document.body.appendChild(el);
    this.el = el;

    el.querySelector('[data-np-close]').onclick = () => this.close();
    el.querySelector('[data-np-more]').onclick = (e) => player.current && songMenu(e.currentTarget, player.current);
    el.querySelector('[data-np-fav]').onclick = () => player.current && lib.toggleFav(player.current.id).catch(errorToast);
    el.querySelector('.np-ctrls').addEventListener('click', (e) => {
      const c = e.target.closest('[data-c]')?.dataset.c;
      if (c === 'play') player.toggle();
      else if (c === 'prev') player.prev();
      else if (c === 'next') player.next();
      else if (c === 'shuffle') player.toggleShuffle();
      else if (c === 'repeat') player.cycleRepeat();
    });
    el.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => this.setTab(b.dataset.tab)));
    el.querySelectorAll('[data-mtab]').forEach((b) => b.addEventListener('click', () => { this.setTab(b.dataset.mtab); el.querySelector('.np-body').classList.add('show-panel'); }));
    el.querySelector('[data-mback]').onclick = () => el.querySelector('.np-body').classList.remove('show-panel');
    el.querySelector('[data-np-artist]').addEventListener('click', (e) => { if (e.target.closest('a')) this.close(); });

    const tabBox = el.querySelector('[data-np-tab]');
    bindQueue(tabBox);
    const manual = () => { cancelAnimationFrame(tabBox._anim); this.userScrollUntil = Date.now() + 3500; };
    tabBox.addEventListener('wheel', manual, { passive: true });
    tabBox.addEventListener('touchmove', manual, { passive: true });
    this.lyrFocus = store.get('sm_lyr_focus', false);
    el.classList.toggle('lyr-focus', this.lyrFocus);
    tabBox.addEventListener('click', (e) => {
      const line = e.target.closest('.line[data-t]');
      if (line && this.synced) {
        cancelAnimationFrame(tabBox._anim);
        player.seek(+line.dataset.t - getOffset(player.current.id) + 0.05);
        this.userScrollUntil = 0;
        if (!player.playing) player.resume();
      }
    });

    // Gesto: deslizar hacia abajo para cerrar (móvil)
    let y0 = null;
    el.querySelector('.np-top').addEventListener('touchstart', (e) => { y0 = e.touches[0].clientY; }, { passive: true });
    el.querySelector('.np-top').addEventListener('touchend', (e) => { if (y0 !== null && e.changedTouches[0].clientY - y0 > 80) this.close(); y0 = null; });

    bus.on('player:song', () => this.isOpen && this.sync(true));
    bus.on('player:state', () => this.isOpen && this.syncState());
    bus.on('player:mode', () => this.isOpen && this.syncState());
    bus.on('player:queue', () => this.isOpen && this.tab === 'cola' && renderQueue(tabBox));
    bus.on('favs', () => this.isOpen && this.syncState());
  },

  open(tab) {
    if (!player.current) return toast('Reproduce una canción primero');
    if (!this.el) this.build();
    if (tab) this.tab = tab;
    this.isOpen = true;
    this.el.classList.add('open');
    this.el.setAttribute('aria-hidden', 'false');
    if (tab && isMobile()) this.el.querySelector('.np-body').classList.add('show-panel');
    this.sync(true);
    if (prefs.visualizer) { fx.ensure(); fx.resume(); }
    this.loop();
    bus.emit('np:open');
  },

  close() {
    if (!this.el) return;
    this.isOpen = false;
    this.el.classList.remove('open');
    this.el.setAttribute('aria-hidden', 'true');
    this.el.querySelector('.np-body').classList.remove('show-panel');
    cancelAnimationFrame(this.raf);
    bus.emit('np:close');
  },

  toggle(tab) { this.isOpen && (!tab || tab === this.tab) ? this.close() : this.open(tab); },

  setTab(t) {
    this.tab = t;
    store.set('sm_np_tab', t);
    this.renderTab();
  },

  async sync(songChanged) {
    const s = player.current;
    if (!s || !this.el) return;
    const el = this.el;
    if (songChanged) {
      el.querySelector('[data-np-art]').innerHTML = coverImg(s.portadaUrl, 640, s.nombre);
      el.querySelector('.np-bg').style.backgroundImage = `url("${cover(s.portadaUrl, 300)}")`;
      el.querySelector('[data-np-name]').textContent = s.nombre;
      el.querySelector('[data-np-artist]').innerHTML = `<a href="#/artista/${encodeURIComponent(s.artista)}">${esc(s.artista)}</a>`;
      const c = player.context;
      el.querySelector('[data-np-ctx]').textContent = c?.label ? c.label + (c.radio ? ' · Radio' : '') : 'Tu música';
      dominantColor(s.portadaUrl).then((rgb) => el.style.setProperty('--np', rgb.join(',')));
      this.renderTab();
    }
    this.syncState();
  },

  syncState() {
    const el = this.el;
    const s = player.current;
    if (!el || !s) return;
    el.classList.toggle('paused', !player.playing);
    el.querySelector('[data-c="play"]').innerHTML = icon(player.playing ? 'pause' : 'play');
    el.querySelector('[data-c="shuffle"]').classList.toggle('active', player.shuffle);
    const rep = el.querySelector('[data-c="repeat"]');
    rep.classList.toggle('active', player.repeat !== 'off');
    rep.innerHTML = icon('repeat') + (player.repeat === 'one' ? '<span class="repeat-one" style="position:absolute;font-size:9px;font-weight:900;top:9px;right:8px">1</span>' : '');
    const fav = lib.isFav(s.id);
    const fb = el.querySelector('[data-np-fav]');
    fb.classList.toggle('liked', fav);
    fb.innerHTML = icon(fav ? 'heartFill' : 'heart');
    el.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('active', b.dataset.tab === this.tab));
  },

  async renderTab() {
    const box = this.el?.querySelector('[data-np-tab]');
    if (!box) return;
    this.el.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('active', b.dataset.tab === this.tab));
    const s = player.current;
    if (!s) return;
    if (this.tab !== 'letra') {
      this.el.querySelector('[data-lyr-bar]').classList.add('hidden');
      this.el.querySelector('.np-panel').classList.remove('is-lyrics');
      this.lines = [];
      if (this.tab === 'cola') renderQueue(box); else box.innerHTML = info(s);
      return;
    }

    const bar = this.el.querySelector('[data-lyr-bar]');
    const panel = this.el.querySelector('.np-panel');
    panel.classList.add('is-lyrics');
    box.innerHTML = '<div class="lyrics-empty"><span class="spinner"></span></div>';
    bar.classList.add('hidden');
    this.lines = [];
    this.wordLine = null;
    this.karaoke = false;
    const d = await getLyrics(s);
    if (player.current?.id !== s.id || this.tab !== 'letra') return;

    const tieneSync = !!d.letraSincronizada;
    const tieneTexto = !!d.letra || tieneSync;

    if (tieneSync) {
      this.lines = parseLrc(d.letraSincronizada);
      this.synced = true;
      this.karaoke = this.lines.some((l) => l.words);
      box.innerHTML = `<div class="lyrics synced ${this.karaoke ? 'karaoke' : ''}">${this.lines.map((l, i) => {
        if (l.interlude) return `<div class="interlude" data-i="${i}" aria-hidden="true"><i></i><i></i><i></i></div>`;
        const contenido = l.words
          ? l.words.map((w, k) => `<span class="w" data-w="${k}">${esc(w.w)}</span>`).join('')
          : esc(l.text);
        return `<p class="line" data-i="${i}" data-t="${l.t}">${contenido}</p>`;
      }).join('')}</div>`;
      this.active = -2;
      this.updateLyrics(true);
    } else if (d.letra) {
      this.synced = false;
      box.innerHTML = `<div class="lyrics plain">${d.letra.split(/\n/).map((l) => (l.trim() ? `<p class="line">${esc(l)}</p>` : '<div class="gap"></div>')).join('')}</div>`;
      box.scrollTop = 0;
    } else {
      this.synced = false;
      box.innerHTML = `<div class="lyrics-empty">${icon('mic')}<h3>No hay letra para esta canción</h3>
        <p style="opacity:.8">La buscamos en internet y no apareció.</p>
        <div class="row" style="justify-content:center"><button class="btn sm" data-retry-lyr>${icon('refresh', 'sm')} Buscar otra vez</button>
        ${session.canEdit ? `<button class="btn sm" data-edit-lyr>${icon('edit', 'sm')} Agregar letra</button>` : ''}</div></div>`;
      box.querySelector('[data-edit-lyr]')?.addEventListener('click', () => editLyrics(s));
      box.querySelector('[data-retry-lyr]')?.addEventListener('click', async (e) => {
        e.currentTarget.disabled = true;
        const r = await api(`/api/letras/${s.id}/buscar?forzar=true`, { method: 'POST' }).catch(() => null);
        lyricsCache.delete(s.id);
        if (r?.encontrada) { toast('¡Letra encontrada!'); this.renderTab(); }
        else { toast(r?.message || 'No se encontró', { error: true }); e.currentTarget.disabled = false; }
      });
    }
    this.renderBar(s, tieneSync, tieneTexto);
  },

  /** Barra superior de la letra: tipo, sincronía, edición y modo letra. Nunca tapa el texto. */
  renderBar(s, tieneSync, tieneTexto) {
    const bar = this.el.querySelector('[data-lyr-bar]');
    if (!tieneTexto) { bar.classList.add('hidden'); return; }
    bar.classList.remove('hidden');
    const off = getOffset(s.id);
    bar.innerHTML = `
      <span class="lyr-kind">${tieneSync ? `${icon('wave', 'sm')} ${this.karaoke ? 'Palabra por palabra' : 'Sincronizada'}` : `${icon('text', 'sm')} Solo texto`}</span>
      <span class="lyr-tools">
        ${tieneSync ? `<button class="lyr-pill ${off ? 'on' : ''}" data-sync title="Ajustar sincronía">${icon('clock', 'sm')}<span data-offv>${fmtOffCorto(off)}</span></button>` : ''}
        ${session.canEdit ? `<button class="lyr-pill" data-edit-lyr title="Editar letra">${icon('edit', 'sm')}<span>Editar</span></button>` : ''}
        <button class="lyr-pill np-only-desktop ${this.lyrFocus ? 'on' : ''}" data-focus title="Modo letra">${icon('expand', 'sm')}</button>
      </span>`;
    bar.querySelector('[data-edit-lyr]')?.addEventListener('click', () => editLyrics(s));
    bar.querySelector('[data-focus]')?.addEventListener('click', (e) => {
      this.lyrFocus = !this.lyrFocus;
      store.set('sm_lyr_focus', this.lyrFocus);
      this.el.classList.toggle('lyr-focus', this.lyrFocus);
      e.currentTarget.classList.toggle('on', this.lyrFocus);
      setTimeout(() => this.updateLyrics(true), 400);
    });
    bar.querySelector('[data-sync]')?.addEventListener('click', (e) => {
      const btn = e.currentTarget;
      const cambiar = (d) => {
        setOffset(s.id, d === 0 ? 0 : getOffset(s.id) + d);
        const v = getOffset(s.id);
        btn.querySelector('[data-offv]').textContent = fmtOffCorto(v);
        btn.classList.toggle('on', !!v);
        this.updateLyrics(true);
      };
      showMenu(btn, [
        { title: 'Si la letra va adelantada o atrasada' },
        { label: 'Letra antes (−0.5 s)', icon: 'left', keepOpen: true, fn: () => cambiar(-0.5) },
        { label: 'Letra antes (−0.1 s)', icon: 'left', keepOpen: true, fn: () => cambiar(-0.1) },
        { label: 'Letra después (+0.1 s)', icon: 'right', keepOpen: true, fn: () => cambiar(0.1) },
        { label: 'Letra después (+0.5 s)', icon: 'right', keepOpen: true, fn: () => cambiar(0.5) },
        { sep: true },
        { label: 'Restablecer', icon: 'refresh', fn: () => cambiar(0) },
      ]);
    });
  },

  updateLyrics(force) {
    if (!this.synced || !this.lines.length || this.tab !== 'letra') return;
    const t = player.audio.currentTime + getOffset(player.current?.id) + 0.12;
    let i = -1;
    let lo = 0, hi = this.lines.length - 1;
    while (lo <= hi) { const m = (lo + hi) >> 1; if (this.lines[m].t <= t) { i = m; lo = m + 1; } else hi = m - 1; }
    const box = this.el.querySelector('[data-np-tab]');

    if (i !== this.active || force) {
      this.active = i;
      box.querySelectorAll('.lyrics [data-i]').forEach((n) => {
        const k = +n.dataset.i;
        const dist = Math.abs(k - i);
        n.classList.toggle('active', k === i);
        n.classList.toggle('past', k < i);
        if (!n.classList.contains('interlude')) {
          // Más tenue y desenfocada cuanto más lejos de la línea actual
          n.style.setProperty('--o', k === i ? 1 : Math.max(0.16, 0.42 - Math.min(dist, 6) * 0.04).toFixed(2));
          n.style.setProperty('--b', k === i ? '0px' : (Math.min(dist, 5) * 0.55).toFixed(2) + 'px');
        }
        if (k !== i) n.querySelectorAll('.w').forEach((w) => w.style.removeProperty('--p'));
      });
      this.wordLine = this.lines[i]?.words ? box.querySelector(`.line[data-i="${i}"]`) : null;
      if (Date.now() > this.userScrollUntil) {
        const a = box.querySelector(`.lyrics [data-i="${Math.max(0, i)}"]`);
        if (a) {
          const destino = a.offsetTop - box.clientHeight * 0.3;
          if (force) { cancelAnimationFrame(box._anim); box.scrollTop = destino; } else scrollSuave(box, destino);
        }
      }
    }

    // Palabra por palabra: avance de cada palabra de la línea actual
    if (this.wordLine) {
      const l = this.lines[i];
      const spans = this.wordLine.querySelectorAll('.w');
      l.words.forEach((w, k) => {
        const fin = l.words[k + 1]?.t ?? Math.min(l.end, w.t + 1.2);
        const p = t <= w.t ? 0 : t >= fin ? 100 : ((t - w.t) / (fin - w.t)) * 100;
        spans[k]?.style.setProperty('--p', p.toFixed(1) + '%');
      });
    }
    // Interludio: los puntos se encienden según avanza la pausa
    const cur = this.lines[i];
    if (cur?.interlude) {
      const el = box.querySelector(`.interlude[data-i="${i}"]`);
      const p = Math.max(0, Math.min(1, (t - cur.t) / Math.max(1, cur.end - cur.t)));
      el?.style.setProperty('--p', p.toFixed(3));
    }
  },

  loop() {
    cancelAnimationFrame(this.raf);
    const vis = this.el.querySelector('[data-np-vis]');
    const ctx2d = vis.getContext('2d');
    let data = null;
    const frame = () => {
      if (!this.isOpen) return;
      this.updateLyrics(false);
      if (prefs.visualizer && fx.analyser && player.playing) {
        vis.style.display = '';
        data = data || new Uint8Array(fx.analyser.frequencyBinCount);
        fx.analyser.getByteFrequencyData(data);
        const w = vis.width, h = vis.height, n = 48, bw = w / n;
        ctx2d.clearRect(0, 0, w, h);
        ctx2d.fillStyle = 'rgba(255,255,255,.75)';
        for (let k = 0; k < n; k++) {
          const v = data[Math.floor(Math.pow(k / n, 1.6) * (data.length * 0.75))] / 255;
          const bh = Math.max(3, v * h);
          const x = k * bw + bw * 0.2;
          ctx2d.beginPath();
          if (ctx2d.roundRect) ctx2d.roundRect(x, h - bh, bw * 0.6, bh, 3); else ctx2d.rect(x, h - bh, bw * 0.6, bh);
          ctx2d.fill();
        }
      } else if (!prefs.visualizer) {
        vis.style.display = 'none';
      }
      this.raf = requestAnimationFrame(frame);
    };
    frame();
  },
};

const fmtOffCorto = (v) => (v === 0 ? 'Sincronía' : `${v > 0 ? '+' : ''}${v.toFixed(1)} s`);

function info(s) {
  const row = (k, v) => (v || v === 0 ? `<dt>${k}</dt><dd>${v}</dd>` : '');
  return `<div class="np-info"><h3 style="margin:4px 0 6px">${esc(s.nombre)}</h3><dl>
    ${row('Artista', `<a href="#/artista/${encodeURIComponent(s.artista)}">${esc(s.artista)}</a>`)}
    ${row('Álbum', s.album && s.album !== 'Single' ? `<a href="#/album/${encodeURIComponent(s.album)}">${esc(s.album)}</a>` : 'Sencillo')}
    ${row('Género', s.genero && s.genero !== 'Desconocido' ? `<a href="#/genero/${encodeURIComponent(s.genero)}">${esc(s.genero)}</a>` : '')}
    ${row('Año', s.anio)}
    ${row('Pista', s.numeroPista)}
    ${row('Duración', fmtTime(s.duracionSegundos))}
    ${row('Formato', s.formato ? s.formato.toUpperCase() : '')}
    ${row('Reproducciones', s.reproducciones)}
    ${row('Agregada', fmtDate(s.fechaAgregada))}
    ${row('Letra', s.letraSincronizada ? 'Sincronizada' : s.tieneLetra ? 'Texto' : 'No')}
  </dl></div>`;
}

// ═══════════════════ Panel lateral de la cola ═══════════════════

export const queueDrawer = {
  el: null,
  build() {
    const el = document.createElement('aside');
    el.className = 'drawer';
    el.setAttribute('aria-label', 'Cola de reproducción');
    el.innerHTML = `<div class="drawer-head"><h3>Cola</h3><div class="row">
      <button class="icon-btn" data-qmenu aria-label="Opciones">${icon('more')}</button>
      <button class="icon-btn" data-qclose aria-label="Cerrar">${icon('close')}</button></div></div><div class="drawer-body"></div>`;
    document.body.appendChild(el);
    this.el = el;
    const body = el.querySelector('.drawer-body');
    bindQueue(body);
    el.querySelector('[data-qclose]').onclick = () => this.close();
    el.querySelector('[data-qmenu]').onclick = (e) => showMenu(e.currentTarget, [
      { label: 'Vaciar lo que sigue', icon: 'trash', fn: () => player.clearUpcoming() },
      { label: prefs.autoRadio ? 'Desactivar radio automática' : 'Activar radio automática', icon: 'radio', fn: () => { prefs.autoRadio = !prefs.autoRadio; prefs.save(); renderQueue(body, { compact: true }); } },
    ]);
    bus.on('player:queue', () => this.isOpen && renderQueue(body, { compact: true }));
    bus.on('player:song', () => this.isOpen && renderQueue(body, { compact: true }));
  },
  get isOpen() { return this.el?.classList.contains('open'); },
  open() {
    if (!this.el) this.build();
    renderQueue(this.el.querySelector('.drawer-body'), { compact: true });
    this.el.classList.add('open');
    bus.emit('drawer', true);
  },
  close() { this.el?.classList.remove('open'); bus.emit('drawer', false); },
  toggle() { this.isOpen ? this.close() : this.open(); },
};

bus.on('np:lyrics', async (song) => {
  if (player.current?.id !== song.id) player.playOne(song);
  np.open('letra');
});

void $;
