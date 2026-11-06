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

/** Color de la barra de estado del teléfono (iPhone/Android): el de la canción con el reproductor abierto. */
function colorBarra(el) {
  const meta = document.querySelector('meta[name="theme-color"]');
  if (!meta) return;
  const c = el ? getComputedStyle(el).getPropertyValue('--np').trim() : '';
  meta.setAttribute('content', c ? `rgb(${c})` : '#000000');
}

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
    el.innerHTML = `<div class="np-bg"></div><div class="np-tint"></div>
      <button class="np-grab" data-np-close aria-label="Cerrar"><span></span></button>
      <div class="np-body">
        <div class="np-art" data-np-art></div>
        <div class="np-head">
          <div class="np-thumb" data-np-thumb></div>
          <div class="tt"><div class="name ellipsis" data-np-name></div><div class="artist ellipsis" data-np-artist></div></div>
          <button class="np-more" data-np-more aria-label="Más opciones">${icon('more')}</button>
        </div>
        <button class="np-mini-lyr hidden" data-mini-lyr aria-label="Ver la letra completa"></button>
        <div class="np-controls">
          <div class="np-progress"><div class="seek" data-seek="np"><div class="rail"><div class="buf"></div><div class="fill"></div></div><div class="knob"></div><div class="tip"></div></div>
            <div class="times"><span data-np-cur>0:00</span><span data-np-dur>-0:00</span></div></div>
          <div class="np-ctrls">
            <button class="np-cb sm" data-c="shuffle" aria-label="Aleatorio">${icon('shuffle')}</button>
            <button class="np-cb" data-c="prev" aria-label="Anterior">${icon('rew')}</button>
            <button class="np-cb big" data-c="play" aria-label="Reproducir">${icon('play')}</button>
            <button class="np-cb" data-c="next" aria-label="Siguiente">${icon('fwd')}</button>
            <button class="np-cb sm" data-c="repeat" aria-label="Repetir">${icon('repeat')}</button>
          </div>
          <div class="np-vol">${icon('volMin', 'sm')}<div class="seek" data-seek="vol"><div class="rail"><div class="fill"></div></div><div class="knob"></div></div>${icon('volMax', 'sm')}</div>
          <div class="np-bottom">
            <button class="np-tb" data-np-fav aria-label="Me gusta">${icon('heart')}</button>
            <span class="grow"></span>
            <button class="np-tb" data-panel="letra" aria-label="Letra" title="Letra">${icon('quote')}</button>
            <button class="np-tb" data-panel="cola" aria-label="A continuación" title="A continuación">${icon('list')}</button>
          </div>
        </div>
        <div class="np-panel"><div class="np-tab" data-np-tab></div></div>
      </div>`;
    document.body.appendChild(el);
    this.el = el;

    el.querySelector('[data-np-close]').onclick = () => this.close();
    el.querySelector('[data-np-more]').onclick = (e) => this.menu(e.currentTarget);
    el.querySelector('[data-np-fav]').onclick = () => player.current && lib.toggleFav(player.current.id).catch(errorToast);
    el.querySelector('.np-ctrls').addEventListener('click', (e) => {
      const c = e.target.closest('[data-c]')?.dataset.c;
      if (c === 'play') player.toggle();
      else if (c === 'prev') player.prev();
      else if (c === 'next') player.next();
      else if (c === 'shuffle') player.toggleShuffle();
      else if (c === 'repeat') player.cycleRepeat();
    });
    // Letra y cola: el mismo botón la muestra u oculta (como Apple Music)
    el.querySelectorAll('[data-panel]').forEach((b) => b.addEventListener('click', () => {
      this.setTab(this.tab === b.dataset.panel ? null : b.dataset.panel);
    }));
    el.querySelector('[data-np-artist]').addEventListener('click', (e) => { if (e.target.closest('a')) this.close(); });
    // Letra en miniatura: al tocarla se abre la letra completa
    el.querySelector('[data-mini-lyr]').addEventListener('click', () => this.setTab('letra'));

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

    // Gesto: deslizar hacia abajo desde arriba para cerrar (móvil)
    let y0 = null;
    el.addEventListener('touchstart', (e) => { y0 = e.touches[0].clientY < 120 ? e.touches[0].clientY : null; }, { passive: true });
    el.addEventListener('touchend', (e) => { if (y0 !== null && e.changedTouches[0].clientY - y0 > 90) this.close(); y0 = null; });

    bus.on('player:song', () => this.isOpen && this.sync(true));
    bus.on('player:state', () => this.isOpen && this.syncState());
    bus.on('player:mode', () => this.isOpen && this.syncState());
    bus.on('player:queue', () => this.isOpen && this.tab === 'cola' && renderQueue(tabBox));
    bus.on('favs', () => this.isOpen && this.syncState());
  },

  open(tab) {
    if (!player.current) return toast('Reproduce una canción primero');
    if (!this.el) this.build();
    if (tab !== undefined) this.tab = tab;
    else if (isMobile()) this.tab = null; // en el celular se abre mostrando la portada
    this.isOpen = true;
    this.el.classList.add('open');
    colorBarra(this.el);
    this.el.setAttribute('aria-hidden', 'false');
    this.sync(true);
    this.loop();
    bus.emit('np:open');
  },

  close() {
    if (!this.el) return;
    this.isOpen = false;
    this.el.classList.remove('open');
    colorBarra(null);
    this.el.setAttribute('aria-hidden', 'true');
    cancelAnimationFrame(this.raf);
    bus.emit('np:close');
  },

  toggle(tab) { this.isOpen && (!tab || tab === this.tab) ? this.close() : this.open(tab); },

  setTab(t) {
    this.tab = t;
    if (t) store.set('sm_np_tab', t);
    this.renderTab();
  },

  /** Menú "…": canción y opciones de la letra. */
  menu(anchor) {
    const s = player.current;
    if (!s) return;
    const fav = lib.isFav(s.id);
    const conSync = this.synced && this.lines.length;
    showMenu(anchor, [
      { label: fav ? 'Quitar de Me gusta' : 'Agregar a Me gusta', icon: fav ? 'heartFill' : 'heart', fn: () => lib.toggleFav(s.id).catch(errorToast) },
      { label: 'Agregar a playlist', icon: 'plusCircle', fn: () => import('./ui.js').then((u) => u.pickPlaylist([s])) },
      { label: 'Ir al artista', icon: 'user', fn: () => { this.close(); location.hash = '#/artista/' + encodeURIComponent(s.artista); } },
      s.album && s.album !== 'Single' ? { label: 'Ir al álbum', icon: 'disc', fn: () => { this.close(); location.hash = '#/album/' + encodeURIComponent(s.album); } } : null,
      { sep: true },
      conSync ? { label: `Ajustar sincronía (${fmtOffCorto(getOffset(s.id))})`, icon: 'clock', fn: () => this.menuSync(anchor) } : null,
      session.canEdit ? { label: 'Editar letra', icon: 'edit', fn: () => editLyrics(s) } : null,
      !isMobile() ? { label: this.lyrFocus ? 'Letra normal' : 'Letra grande', icon: 'expand', fn: () => this.toggleFocus() } : null,
      { sep: true },
      { label: 'Más opciones de la canción', icon: 'more', fn: () => songMenu(anchor, s) },
    ].filter(Boolean));
  },

  menuSync(anchor) {
    const s = player.current;
    const cambiar = (d) => {
      setOffset(s.id, d === 0 ? 0 : getOffset(s.id) + d);
      toast(`Sincronía: ${fmtOffCorto(getOffset(s.id))}`, { ms: 1200 });
      this.updateLyrics(true);
    };
    showMenu(anchor, [
      { title: 'Si la letra va adelantada o atrasada' },
      { label: 'Letra antes (−0.5 s)', icon: 'left', keepOpen: true, fn: () => cambiar(-0.5) },
      { label: 'Letra antes (−0.1 s)', icon: 'left', keepOpen: true, fn: () => cambiar(-0.1) },
      { label: 'Letra después (+0.1 s)', icon: 'right', keepOpen: true, fn: () => cambiar(0.1) },
      { label: 'Letra después (+0.5 s)', icon: 'right', keepOpen: true, fn: () => cambiar(0.5) },
      { sep: true },
      { label: 'Restablecer', icon: 'refresh', fn: () => cambiar(0) },
    ]);
  },

  toggleFocus() {
    this.lyrFocus = !this.lyrFocus;
    store.set('sm_lyr_focus', this.lyrFocus);
    this.el.classList.toggle('lyr-focus', this.lyrFocus);
    setTimeout(() => this.updateLyrics(true), 400);
  },

  async sync(songChanged) {
    const s = player.current;
    if (!s || !this.el) return;
    const el = this.el;
    if (songChanged) {
      el.querySelector('[data-np-art]').innerHTML = coverImg(s.portadaUrl, 640, s.nombre);
      el.querySelector('[data-np-thumb]').innerHTML = coverImg(s.portadaUrl, 160, '');
      el.querySelector('.np-bg').style.backgroundImage = `url("${cover(s.portadaUrl, 300)}")`;
      el.querySelector('[data-np-name]').textContent = s.nombre;
      el.querySelector('[data-np-artist]').innerHTML = `<a href="#/artista/${encodeURIComponent(s.artista)}">${esc(s.artista)}</a>`;
      dominantColor(s.portadaUrl).then((rgb) => { el.style.setProperty('--np', rgb.join(',')); if (this.isOpen) colorBarra(el); });
      this.cargarMini(s);
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
    rep.innerHTML = icon('repeat') + (player.repeat === 'one' ? '<span class="rep1">1</span>' : '');
    const fav = lib.isFav(s.id);
    const fb = el.querySelector('[data-np-fav]');
    fb.classList.toggle('on', fav);
    fb.innerHTML = icon(fav ? 'heartFill' : 'heart');
    el.querySelectorAll('[data-panel]').forEach((b) => b.classList.toggle('on', b.dataset.panel === this.tab));
    el.classList.toggle('no-panel', !this.tab);
  },

  async renderTab() {
    const box = this.el?.querySelector('[data-np-tab]');
    if (!box) return;
    this.syncState();
    const s = player.current;
    if (!s) return;
    const panel = this.el.querySelector('.np-panel');
    if (this.tab !== 'letra') {
      panel.classList.remove('is-lyrics');
      this.lines = [];
      this.synced = false;
      if (this.tab === 'cola') renderQueue(box); else box.innerHTML = '';
      return;
    }
    panel.classList.add('is-lyrics');
    box.innerHTML = '<div class="lyrics-empty"><span class="spinner"></span></div>';
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
    void tieneTexto;
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
          const destino = a.offsetTop - box.clientHeight * 0.32;
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

  /** Letra sincronizada en miniatura (debajo del título, cuando no hay panel abierto). */
  async cargarMini(s) {
    const box = this.el?.querySelector('[data-mini-lyr]');
    if (!box) return;
    this.mini = { lines: [], activa: -2 };
    box.classList.add('hidden');
    box.innerHTML = '';
    const d = await getLyrics(s).catch(() => ({}));
    if (player.current?.id !== s.id || !d?.letraSincronizada) return;
    this.mini.lines = parseLrc(d.letraSincronizada).filter((l) => !l.interlude && l.text?.trim());
    if (this.mini.lines.length) box.classList.remove('hidden');
    this.updateMini(true);
  },

  updateMini(force) {
    const m = this.mini;
    if (!m?.lines.length || this.tab) return;
    const t = player.audio.currentTime + getOffset(player.current?.id) + 0.12;
    let i = -1;
    for (let k = 0; k < m.lines.length; k++) { if (m.lines[k].t <= t) i = k; else break; }
    if (i === m.activa && !force) return;
    m.activa = i;
    const box = this.el.querySelector('[data-mini-lyr]');
    const fila = (k, cls) => (m.lines[k] ? `<span class="${cls}">${esc(m.lines[k].text)}</span>` : '');
    // Solo la línea que se está cantando (antes de la primera, una nota)
    box.innerHTML = i < 0 ? '<span class="act dim">♪</span>' : fila(i, 'act');
  },

  loop() {
    cancelAnimationFrame(this.raf);
    const frame = () => {
      if (!this.isOpen) return;
      this.updateLyrics(false);
      this.updateMini(false);
      this.raf = requestAnimationFrame(frame);
    };
    frame();
  },
};

const fmtOffCorto = (v) => (v === 0 ? 'Sincronía' : `${v > 0 ? '+' : ''}${v.toFixed(1)} s`);


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
