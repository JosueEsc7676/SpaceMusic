// Motor de reproducción: cola, aleatorio, repetir, radio automática,
// registro de escuchas, sesión de medios, temporizador y ecualizador.
import { api, audioUrl , session, deUsuario} from './api.js';
import { bus, store, shuffleArr, cover , esIOS } from './util.js';

const SAVE_KEY = 'sm_player';
const PREFS_KEY = 'sm_prefs';
let uidSeq = 1;
const withUid = (s, user = false) => ({ ...s, _uid: uidSeq++, _user: user });
const strip = ({ _uid, _user, ...s }) => s;

export const prefs = {
  autoRadio: true,
  normalizeTitles: false,
  eqEnabled: false,
  eq: [0, 0, 0, 0, 0],
  visualizer: true,
  ...store.get(PREFS_KEY, {}),
  save() { const { save, ...p } = this; store.set(PREFS_KEY, p); bus.emit('prefs', this); },
};

const audio = new Audio();
audio.preload = 'auto';
const preloader = new Audio();
preloader.preload = 'auto';
preloader.muted = true;

export const player = {
  audio,
  items: [],
  index: -1,
  original: null,
  shuffle: false,
  repeat: 'off', // off | all | one
  context: null, // { type, label, route }
  volume: 0.8,
  muted: false,
  buffering: false,
  _errors: 0,
  _listened: 0,
  _lastTick: 0,
  _scrobbled: false,
  _radioLoading: false,

  get current() { return this.items[this.index] || null; },
  get playing() { return !audio.paused && !audio.ended; },
  get upcoming() { return this.items.slice(this.index + 1); },

  // ═══════════════════ Reproducir listas ═══════════════════

  /** Reproduce una lista empezando en `start` (o al azar si shuffle y start < 0). */
  play(songs, start = 0, context = null, opts = {}) {
    if (!songs?.length) return;
    if (opts.shuffle !== undefined) this.shuffle = !!opts.shuffle;
    let list = songs.map((s) => withUid(s));
    this.original = this.shuffle ? [...list] : null;
    if (start < 0) start = this.shuffle ? Math.floor(Math.random() * list.length) : 0;
    start = Math.min(Math.max(0, start), list.length - 1);
    if (this.shuffle) {
      const first = list[start];
      list = [first, ...shuffleArr(list.filter((_, i) => i !== start))];
      start = 0;
    }
    this.items = list;
    this.context = context;
    this._load(start, true);
    bus.emit('player:queue');
    bus.emit('player:mode');
  },

  /** Reproduce una sola canción y deja que la radio continúe después. */
  playOne(song, context = null) {
    this.play([song], 0, context || { type: 'cancion', label: song.nombre });
  },

  playNext(songs) {
    songs = [].concat(songs);
    if (!this.current) return this.play(songs, 0, { type: 'cola', label: 'Tu cola' });
    this.items.splice(this.index + 1, 0, ...songs.map((s) => withUid(s, true)));
    if (this.original) this.original.push(...this.items.slice(this.index + 1, this.index + 1 + songs.length));
    bus.emit('player:queue');
  },

  addToQueue(songs) {
    songs = [].concat(songs);
    if (!this.current) return this.play(songs, 0, { type: 'cola', label: 'Tu cola' });
    // Después de lo que el usuario ya agregó, antes del resto de la lista
    let pos = this.index + 1;
    while (pos < this.items.length && this.items[pos]._user) pos++;
    const nuevos = songs.map((s) => withUid(s, true));
    this.items.splice(pos, 0, ...nuevos);
    if (this.original) this.original.push(...nuevos);
    bus.emit('player:queue');
  },

  jump(i) {
    if (i < 0 || i >= this.items.length) return;
    this._load(i, true);
  },

  removeAt(i) {
    if (i === this.index || i < 0 || i >= this.items.length) return;
    const [r] = this.items.splice(i, 1);
    if (this.original) this.original = this.original.filter((x) => x._uid !== r._uid);
    if (i < this.index) this.index--;
    bus.emit('player:queue');
  },

  move(from, to) {
    if (from === to || from < 0 || to < 0 || from >= this.items.length || to >= this.items.length) return;
    const cur = this.current;
    const [it] = this.items.splice(from, 1);
    this.items.splice(to, 0, it);
    this.index = this.items.indexOf(cur);
    bus.emit('player:queue');
  },

  clearUpcoming() {
    this.items = this.items.slice(0, this.index + 1);
    if (this.original) this.original = [...this.items];
    bus.emit('player:queue');
  },

  // ═══════════════════ Controles ═══════════════════

  toggle() {
    if (!this.current) return;
    if (audio.paused) this.resume(); else audio.pause();
  },

  resume() {
    if (!this.current) return;
    fx.resume();
    if (!audio.src) { this._load(this.index, true); return; }
    audio.play().catch((e) => this._playError(e));
  },

  pause() { audio.pause(); },

  next(auto = false) {
    if (!this.items.length) return;
    if (this.index < this.items.length - 1) return this._load(this.index + 1, true);
    if (this.repeat === 'all') return this._load(0, true);
    if (prefs.autoRadio && this.current) return this._extendWithRadio(true);
    if (auto) { audio.pause(); audio.currentTime = 0; bus.emit('player:state'); }
  },

  prev() {
    if (!this.current) return;
    if (audio.currentTime > 3 || this.index === 0) { audio.currentTime = 0; return; }
    this._load(this.index - 1, true);
  },

  /** Duración real del audio, o la de la base de datos mientras el navegador no la conoce. */
  duration() {
    return isFinite(audio.duration) && audio.duration > 0 ? audio.duration : (this.current?.duracionSegundos || 0);
  },

  seek(t) {
    const dur = this.duration();
    if (!dur || !isFinite(t)) return;
    audio.currentTime = Math.max(0, Math.min(dur - 0.25, t));
    bus.emit('player:time');
  },

  seekBy(d) { this.seek(audio.currentTime + d); },

  setVolume(v) {
    this.volume = Math.max(0, Math.min(1, v));
    this.muted = this.volume === 0 ? this.muted : false;
    this._applyVolume();
    store.set('sm_volume', { v: this.volume, m: this.muted });
  },

  toggleMute() {
    this.muted = !this.muted;
    this._applyVolume();
    store.set('sm_volume', { v: this.volume, m: this.muted });
  },

  _applyVolume() {
    // Curva cuadrática: el control se siente más natural
    audio.volume = this.muted ? 0 : Math.pow(this.volume, 2) * sleep.factor;
    bus.emit('player:volume');
  },

  toggleShuffle() {
    this.shuffle = !this.shuffle;
    if (!this.items.length) { bus.emit('player:mode'); return; }
    const cur = this.current;
    const played = this.items.slice(0, this.index + 1);
    const rest = this.items.slice(this.index + 1);
    if (this.shuffle) {
      this.original = [...this.items];
      const user = rest.filter((x) => x._user), others = rest.filter((x) => !x._user);
      this.items = [...played, ...user, ...shuffleArr(others)];
    } else if (this.original) {
      const restIds = new Set(rest.map((x) => x._uid));
      const user = rest.filter((x) => x._user);
      const ordered = this.original.filter((x) => restIds.has(x._uid) && !x._user);
      // Volver al orden original a partir de la canción actual
      const pos = this.original.findIndex((x) => x._uid === cur._uid);
      const after = pos >= 0 ? this.original.slice(pos + 1).filter((x) => restIds.has(x._uid) && !x._user) : ordered;
      const before = ordered.filter((x) => !after.includes(x));
      this.items = [...played, ...user, ...after, ...before];
      this.original = null;
    }
    this.index = this.items.indexOf(cur);
    bus.emit('player:mode');
    bus.emit('player:queue');
  },

  cycleRepeat() {
    this.repeat = this.repeat === 'off' ? 'all' : this.repeat === 'all' ? 'one' : 'off';
    bus.emit('player:mode');
  },

  /** Agrega canciones parecidas al final de la cola (radio). */
  async _extendWithRadio(advance = false) {
    if (this._radioLoading || !this.current) return;
    this._radioLoading = true;
    try {
      const excluir = this.items.slice(-60).map((s) => s.id).join(',');
      // La radio sigue el estilo de las últimas canciones (no solo de la actual)
      const anteriores = this.items.slice(Math.max(0, this.index - 3), this.index).map((x) => x.id).join(',');
      const more = await api(`/api/radio/${this.current.id}?cantidad=20&excluir=${excluir}${anteriores ? '&anteriores=' + anteriores : ''}`);
      if (more?.length) {
        const wasEnd = this.index === this.items.length - 1;
        this.items.push(...more.map((s) => withUid(s)));
        if (!this.context || this.context.type !== 'radio') {
          this.context = { ...(this.context || {}), radio: true };
        }
        bus.emit('player:queue');
        if (advance && wasEnd) this._load(this.index + 1, true);
      } else if (advance) {
        audio.pause();
      }
    } catch {
      if (advance) audio.pause();
    } finally {
      this._radioLoading = false;
    }
  },

  /** Inicia una radio a partir de una canción. */
  async startRadio(song) {
    const lista = await api(`/api/radio/${song.id}?cantidad=30`);
    this.play([song, ...lista], 0, { type: 'radio', label: 'Radio de ' + song.nombre }, { shuffle: false });
  },

  // ═══════════════════ Carga ═══════════════════

  _load(i, autoplay, startAt = 0) {
    this._finishListen();
    this.index = i;
    const s = this.current;
    if (!s) return;
    this._listened = 0;
    this._scrobbled = false;
    this._lastTick = 0;
    this.buffering = true;
    audio.src = audioUrl(s.id);
    if (startAt > 0) {
      const onMeta = () => { audio.currentTime = Math.min(startAt, (audio.duration || startAt) - 1); audio.removeEventListener('loadedmetadata', onMeta); };
      audio.addEventListener('loadedmetadata', onMeta);
    }
    if (autoplay) {
      fx.resume();
      audio.play().catch((e) => this._playError(e));
    } else {
      audio.load();
    }
    updateMediaSession(s);
    bus.emit('player:song', s);
    bus.emit('player:queue');
    this._save();
    // Si quedan pocas canciones y la radio está activa, prepararla
    if (prefs.autoRadio && this.repeat === 'off' && this.items.length - this.index <= 2 && this.context?.type !== 'cola') {
      setTimeout(() => this._extendWithRadio(false), 1500); // pronto, para que la siguiente ya se pueda precargar
    }
  },

  _playError(e) {
    if (e?.name === 'NotAllowedError') {
      bus.emit('player:state');
      bus.emit('toast', { msg: 'Toca reproducir para empezar' });
    } else if (e?.name !== 'AbortError') {
      console.warn('play()', e);
    }
  },

  // ═══════════════════ Registro de escuchas ═══════════════════

  _tick() {
    const now = audio.currentTime;
    if (this._lastTick && now > this._lastTick && now - this._lastTick < 2.5) this._listened += now - this._lastTick;
    this._lastTick = now;
    const dur = audio.duration || this.current?.duracionSegundos || 0;
    const umbral = dur > 0 ? Math.min(30, dur * 0.5) : 30;
    if (!this._scrobbled && this._listened >= umbral && this.current) {
      this._scrobbled = true;
      const s = this.current;
      api('/api/reproducciones', {
        method: 'POST',
        body: { cancionId: s.id, contexto: this.context?.type || 'otro', segundos: Math.round(this._listened) },
      }).catch(() => { this._scrobbled = false; });
      s.reproducciones = (s.reproducciones || 0) + 1;
      bus.emit('player:scrobble', s);
    }
  },

  _finishListen() { /* el registro se hace al pasar el umbral */ },

  // ═══════════════════ Persistencia ═══════════════════

  _save() {
    // Solo se guarda para la cuenta con la que se abrió la página (nunca bajo otra cuenta)
    if (!session.user?.id || session.user.id !== this._uid) return;
    if (!this.items.length) { store.del(deUsuario(SAVE_KEY)); return; }
    const from = Math.max(0, this.index - 30);
    store.set(deUsuario(SAVE_KEY), {
      items: this.items.slice(from, this.index + 300).map(strip),
      index: this.index - from,
      time: audio.currentTime || 0,
      shuffle: this.shuffle,
      repeat: this.repeat,
      context: this.context,
    });
  },

  restore() {
    const v = store.get('sm_volume');
    if (v) { this.volume = v.v ?? 0.8; this.muted = !!v.m; }
    this._applyVolume();
    this._uid = session.user?.id ?? null;
    store.del(SAVE_KEY); // formato anterior (compartido por todas las cuentas del equipo)
    const s = store.get(deUsuario(SAVE_KEY));
    if (!s?.items?.length) return;
    this.items = s.items.map((x) => withUid(x));
    this.shuffle = !!s.shuffle;
    this.repeat = s.repeat || 'off';
    this.context = s.context || null;
    this.index = Math.min(s.index || 0, this.items.length - 1);
    this._load(this.index, false, s.time || 0);
    bus.emit('player:mode');
  },
};

// ═══════════════════ Eventos del <audio> ═══════════════════

audio.addEventListener('playing', () => { player.buffering = false; player._errors = 0; bus.emit('player:state'); });
audio.addEventListener('pause', () => { bus.emit('player:state'); player._save(); });
audio.addEventListener('waiting', () => { player.buffering = true; bus.emit('player:state'); });
audio.addEventListener('canplay', () => { player.buffering = false; bus.emit('player:state'); });
audio.addEventListener('loadedmetadata', () => bus.emit('player:time'));
audio.addEventListener('durationchange', () => bus.emit('player:time'));
audio.addEventListener('progress', () => bus.emit('player:buffer'));

let lastSave = 0;
audio.addEventListener('timeupdate', () => {
  player._tick();
  bus.emit('player:time');
  if (Date.now() - lastSave > 5000) { lastSave = Date.now(); player._save(); }
  if ('mediaSession' in navigator && navigator.mediaSession.setPositionState && isFinite(audio.duration)) {
    try { navigator.mediaSession.setPositionState({ duration: audio.duration, position: Math.min(audio.currentTime, audio.duration), playbackRate: 1 }); } catch { }
  }
  // Precargar la siguiente canción cuando falte poco
  const nxt = player.items[player.index + 1];
  if (nxt && audio.duration && audio.currentTime > audio.duration * 0.6 && preloader.dataset.id !== String(nxt.id)) {
    preloader.dataset.id = String(nxt.id);
    preloader.src = audioUrl(nxt.id);
    preloader.load();
  }
});

audio.addEventListener('ended', () => {
  if (sleep.endOfSong) { sleep.stop(); bus.emit('toast', { msg: 'Temporizador: música detenida' }); bus.emit('player:state'); return; }
  if (player.repeat === 'one') { audio.currentTime = 0; audio.play().catch(() => { }); return; }
  player.next(true);
});

audio.addEventListener('error', () => {
  if (!audio.src || !player.current) return;
  const s = player.current;
  player.buffering = false;
  player._errors++;
  bus.emit('player:state');
  bus.emit('toast', { msg: `No se pudo reproducir “${s.nombre}”`, error: true });
  if (player._errors < 3 && player.index < player.items.length - 1) {
    setTimeout(() => player.next(true), 1200);
  }
});

window.addEventListener('pagehide', () => player._save());
document.addEventListener('visibilitychange', () => { if (document.hidden) player._save(); });

// ═══════════════════ Media Session (teclas multimedia, pantalla bloqueada) ═══════════════════

function updateMediaSession(s) {
  if (!('mediaSession' in navigator) || !s) return;
  const art = s.portadaUrl ? new URL(cover(s.portadaUrl, 640), location.origin).href : undefined;
  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: s.nombre, artist: s.artista, album: s.album === 'Single' ? '' : s.album,
      artwork: art ? [{ src: art, sizes: '640x640' }] : [],
    });
  } catch { }
  document.title = `${s.nombre} · ${s.artista}`;
}

if ('mediaSession' in navigator) {
  const ms = navigator.mediaSession;
  const h = (a, fn) => { try { ms.setActionHandler(a, fn); } catch { } };
  h('play', () => player.resume());
  h('pause', () => player.pause());
  h('previoustrack', () => player.prev());
  h('nexttrack', () => player.next());
  h('seekbackward', (d) => player.seekBy(-(d.seekOffset || 10)));
  h('seekforward', (d) => player.seekBy(d.seekOffset || 10));
  h('seekto', (d) => player.seek(d.seekTime));
}

// ═══════════════════ Temporizador para dormir ═══════════════════

export const sleep = {
  until: 0,
  endOfSong: false,
  factor: 1,
  _t: null,
  set(minutes) {
    this.stop(true);
    if (minutes === 'end') { this.endOfSong = true; bus.emit('sleep'); return; }
    if (!minutes) { bus.emit('sleep'); return; }
    this.until = Date.now() + minutes * 60000;
    this._t = setInterval(() => this._check(), 1000);
    bus.emit('sleep');
  },
  _check() {
    const left = this.until - Date.now();
    if (left <= 8000 && left > 0) { this.factor = Math.max(0, left / 8000); player._applyVolume(); }
    if (left <= 0) {
      player.pause();
      this.stop();
      bus.emit('toast', { msg: 'Temporizador: música detenida' });
    }
    bus.emit('sleep');
  },
  stop(silent) {
    clearInterval(this._t);
    this._t = null;
    this.until = 0;
    this.endOfSong = false;
    this.factor = 1;
    player._applyVolume();
    if (!silent) bus.emit('sleep');
  },
  get active() { return this.endOfSong || this.until > 0; },
  get remaining() { return this.until ? Math.max(0, this.until - Date.now()) : 0; },
};

// ═══════════════════ Ecualizador y visualizador (Web Audio) ═══════════════════

export const EQ_BANDS = [60, 230, 910, 3600, 14000];
export const EQ_PRESETS = {
  'Plano': [0, 0, 0, 0, 0],
  'Graves': [6, 4, 0, 0, 1],
  'Agudos': [0, 0, 0, 3, 6],
  'Voz': [-2, 0, 4, 3, 0],
  'Rock': [4, 2, -1, 2, 4],
  'Electrónica': [5, 3, 0, 2, 4],
  'Acústico': [3, 1, 1, 2, 2],
  'Noche': [2, 1, 0, -2, -4],
};

export const fx = {
  ctx: null,
  analyser: null,
  filters: [],
  /** Crea el grafo solo cuando hace falta (la primera vez que se usa el EQ o el visualizador). */
  ensure() {
    if (this.ctx) return true;
    if (esIOS) return false; // en iPhone el ecualizador cortaría la música en segundo plano
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return false;
    try {
      this.ctx = new AC();
      const src = this.ctx.createMediaElementSource(audio);
      this.filters = EQ_BANDS.map((f, i) => {
        const b = this.ctx.createBiquadFilter();
        b.type = i === 0 ? 'lowshelf' : i === EQ_BANDS.length - 1 ? 'highshelf' : 'peaking';
        b.frequency.value = f;
        b.Q.value = 1;
        b.gain.value = 0;
        return b;
      });
      this.analyser = this.ctx.createAnalyser();
      this.analyser.fftSize = 256;
      this.analyser.smoothingTimeConstant = 0.8;
      let node = src;
      for (const f of this.filters) { node.connect(f); node = f; }
      node.connect(this.analyser);
      this.analyser.connect(this.ctx.destination);
      this.apply();
      return true;
    } catch (e) {
      console.warn('Web Audio no disponible', e);
      return false;
    }
  },
  resume() { if (this.ctx?.state === 'suspended') this.ctx.resume().catch(() => { }); },
  apply() {
    if (!this.ctx) return;
    const g = prefs.eqEnabled ? prefs.eq : [0, 0, 0, 0, 0];
    this.filters.forEach((f, i) => f.gain.setTargetAtTime(g[i] || 0, this.ctx.currentTime, 0.05));
  },
};

if (prefs.eqEnabled) {
  // El contexto de audio necesita un gesto del usuario: se activa en el primer clic
  const once = () => { fx.ensure(); fx.resume(); window.removeEventListener('pointerdown', once); };
  window.addEventListener('pointerdown', once);
}
