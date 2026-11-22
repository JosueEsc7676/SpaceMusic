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
  normalizar: false,  // volumen parejo entre canciones
  mezcla: 0,          // segundos de transición entre canciones (0 = sin transición)
  ...store.get(PREFS_KEY, {}),
  save() { const { save, ...p } = this; store.set(PREFS_KEY, p); bus.emit('prefs', this); },
};

const audio = new Audio();
audio.preload = 'auto';
const preloader = new Audio();
preloader.preload = 'auto';
preloader.muted = true;

// iPhone: con la pantalla apagada (y más con datos móviles) iOS corta el audio si la
// siguiente canción tarda en llegar. Se descarga antes a la memoria y el cambio es instantáneo.
// (En iOS no se usa el segundo <audio> para precargar: puede interrumpir al principal.)
const MAX_MEMORIA = 30 * 1024 * 1024;
const siguiente = { id: null, url: null, ctrl: null };
function soltarSiguiente() {
  siguiente.ctrl?.abort();
  if (siguiente.url) URL.revokeObjectURL(siguiente.url);
  siguiente.id = siguiente.url = siguiente.ctrl = null;
}
async function traerSiguiente(id) {
  soltarSiguiente();
  siguiente.id = id;
  const ctrl = (siguiente.ctrl = new AbortController());
  try {
    const r = await fetch(audioUrl(id), { signal: ctrl.signal });
    const largo = Number(r.headers.get('Content-Length') || 0);
    if (!r.ok || largo > MAX_MEMORIA) { ctrl.abort(); return; }
    const blob = await r.blob();
    if (siguiente.id !== id || blob.size > MAX_MEMORIA) return;
    siguiente.url = URL.createObjectURL(blob);
  } catch { /* sin conexión o cancelada: se usará la red normal */ }
}
let urlEnUso = null; // copia en memoria que suena ahora (se libera al cambiar)

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
    // Sin conexión: la lista queda solo con lo descargado (en su mismo orden)
    if (!songs.every((x) => this.disponible(x))) {
      const elegida = start >= 0 ? songs[start] : null;
      // La que tocaste no está en el dispositivo: no se pone otra en su lugar
      if (elegida && !this.disponible(elegida)) { bus.emit('toast', { msg: 'Sin conexión: esta canción no está descargada' }); return; }
      songs = songs.filter((x) => this.disponible(x));
      if (!songs.length) { bus.emit('toast', { msg: 'Sin conexión: esta canción no está descargada' }); return; }
      start = start < 0 ? -1 : Math.max(0, songs.indexOf(elegida));
    }
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
    if (!this.disponible(this.current)) {
      bus.emit('toast', { msg: 'Sin conexión: esta canción no está descargada' });
      return;
    }
    fx.resume();
    if (!audio.src || audio.error) { this._load(this.index, true, audio.currentTime || 0); return; }
    audio.play().catch((e) => this._playError(e));
  },

  pause() { this._pausaPedida = Date.now(); audio.pause(); },

  /** ¿Se puede escuchar ahora? (sin conexión, solo lo que está en el dispositivo; lo define offline.js) */
  disponible: () => true,
  /** ¿Hay una copia de esta canción en el dispositivo? (lo define offline.js) */
  tieneCopia: () => false,

  next(auto = false) {
    if (!this.items.length) return;
    // Sin conexión se saltan las canciones que no están descargadas
    let j = this.index + 1;
    while (j < this.items.length && !this.disponible(this.items[j])) j++;
    // Avance automático justo después de una pausa pedida desde otro dispositivo: la siguiente queda lista, sin sonar
    if (auto && Date.now() - (this._pausaRemota || 0) < 3000) { if (j < this.items.length) this._load(j, false); return; }
    if (j < this.items.length) return this._load(j, true);
    // Quizá solo se cortaron las peticiones en segundo plano: si el sistema dice que hay red, se intenta
    if (auto && this.index < this.items.length - 1 && navigator.onLine !== false) return this._load(this.index + 1, true);
    if (this.index < this.items.length - 1 && auto) { audio.pause(); bus.emit('player:state'); bus.emit('toast', { msg: 'Sin conexión: no hay más canciones descargadas en esta lista' }); return; }
    if (this.repeat === 'all') return this._load(0, true);
    // Escuchando juntos como invitado: la siguiente la decide el anfitrión (nada de radio propia)
    if (prefs.autoRadio && this.current && this.context?.type !== 'juntos-oyente') return this._extendWithRadio(true);
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
    audio.volume = this.muted ? 0 : Math.pow(this.volume, 2) * sleep.factor * mezcla.entrada;
    mezcla.volumenCola();
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
    const uid = this.current._uid;
    const pedida = Date.now(); // si alguien pausa mientras llega la radio, la siguiente se carga sin arrancar
    this._radioLoading = true;
    try {
      const excluir = this.items.slice(-60).map((s) => s.id).join(',');
      // La radio sigue el estilo de las últimas canciones (no solo de la actual)
      const anteriores = this.items.slice(Math.max(0, this.index - 3), this.index).map((x) => x.id).join(',');
      const more = await api(`/api/radio/${this.current.id}?cantidad=20&excluir=${excluir}${anteriores ? '&anteriores=' + anteriores : ''}`);
      if (this.current?._uid !== uid) return; // el usuario eligió otra canción mientras llegaba la radio
      if (more?.length) {
        this._radioIntentos = 0;
        const wasEnd = this.index === this.items.length - 1;
        this.items.push(...more.map((s) => withUid(s)));
        if (!this.context || this.context.type !== 'radio') {
          this.context = { ...(this.context || {}), radio: true };
        }
        bus.emit('player:queue');
        if (advance && wasEnd) this._load(this.index + 1, !(this._pausaPedida > pedida));
      } else if (advance) {
        audio.pause();
      }
    } catch {
      if (advance) {
        this._radioIntentos = (this._radioIntentos || 0) + 1;
        if (this._radioIntentos <= 3) setTimeout(() => this._extendWithRadio(true), 2500);
        else { this._radioIntentos = 0; audio.pause(); }
      }
    } finally {
      this._radioLoading = false;
    }
  },

  /** Inicia una radio a partir de una canción. */
  startRadio(song) {
    // El toque debe arrancar el audio dentro del gesto del usuario; la radio se amplía después.
    this.play([song], 0, { type: 'radio', label: 'Radio de ' + song.nombre }, { shuffle: false });
    return this._extendWithRadio(false);
  },

  // ═══════════════════ Carga ═══════════════════

  _load(i, autoplay, startAt = 0) {
    this._finishListen();
    if (!mezcla.enCurso) mezcla.cortar();
    this.index = i;
    const s = this.current;
    if (!s) return;
    // Apartar las transferencias de fondo ANTES de asignar src (no después de play()).
    bus.emit('audio:prioridad', s);
    if (preloader.src) { preloader.pause(); preloader.removeAttribute('src'); preloader.load(); delete preloader.dataset.id; }
    this._listened = 0;
    this._scrobbled = false;
    this._lastTick = 0;
    this.buffering = true;
    audio.dataset.inicioMs = String(performance.now());
    delete audio.dataset.arranqueMs;
    delete audio.dataset.avanceMs;
    delete audio.dataset.metadataMs;
    if (urlEnUso) { URL.revokeObjectURL(urlEnUso); urlEnUso = null; }
    if (siguiente.id === s.id && siguiente.url) {
      // Ya estaba en memoria: arranca al instante aunque la pantalla esté apagada
      urlEnUso = siguiente.url;
      siguiente.url = null;
      siguiente.id = null;
      audio.src = urlEnUso;
    } else {
      // Una precarga todavía incompleta también se cancela si es la elegida:
      // el reproductor la pide ahora por streaming y no necesita otra descarga entera.
      if (siguiente.id !== null) soltarSiguiente();
      audio.src = audioUrl(s.id);
    }
    if (startAt > 0) {
      const onMeta = () => {
        if (this.current?._uid === s._uid) audio.currentTime = Math.min(startAt, (audio.duration || startAt) - 1);
        audio.removeEventListener('loadedmetadata', onMeta);
      };
      audio.addEventListener('loadedmetadata', onMeta);
    }
    if (autoplay) {
      fx.resume();
      audio.play().catch((e) => this._playError(e));
    } else {
      audio.load();
    }
    updateMediaSession(s);
    corteInicio = 0;
    bus.emit('player:song', s);
    bus.emit('player:queue');
    this._save();
    // Si quedan pocas canciones y la radio está activa, prepararla
    if (prefs.autoRadio && this.repeat === 'off' && this.items.length - this.index <= 2 && this.context?.type !== 'cola' && this.context?.type !== 'juntos-oyente') {
      const uid = s._uid;
      setTimeout(() => { if (this.current?._uid === uid && this.items.length - this.index <= 2) this._extendWithRadio(false); }, 1500);
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

let corteInicio = 0;
function informarRendimiento(metrica, ms) {
  if (!session.token || !navigator.onLine || ms < 0 || ms > 120000) return;
  fetch('/api/usuario/rendimiento', { method: 'POST', headers: { Authorization: 'Bearer ' + session.token, 'Content-Type': 'application/json' }, body: JSON.stringify({ metrica, ms, plataforma: window.SpaceMusicApp ? 'android' : 'web' }), signal: typeof AbortSignal.timeout === 'function' ? AbortSignal.timeout(3000) : undefined }).catch(() => {});
}
audio.addEventListener('playing', () => {
  if (corteInicio) { informarRendimiento('corte', performance.now() - corteInicio); corteInicio = 0; }
  if (!audio.dataset.arranqueMs && audio.dataset.inicioMs) {
    audio.dataset.arranqueMs = String(Math.round(performance.now() - Number(audio.dataset.inicioMs)));
    informarRendimiento('audioInicio', Number(audio.dataset.arranqueMs));
  }
  player.buffering = false; player._errors = 0; bus.emit('player:state');
});
audio.addEventListener('pause', () => { bus.emit('player:state'); player._save(); });
audio.addEventListener('waiting', () => { if (!audio.paused && audio.currentTime > 0 && !corteInicio) corteInicio = performance.now(); player.buffering = true; bus.emit('player:state'); });
audio.addEventListener('canplay', () => { player.buffering = false; bus.emit('player:state'); });
audio.addEventListener('loadedmetadata', () => {
  audio.dataset.metadataMs = String(Math.round(performance.now() - Number(audio.dataset.inicioMs)));
  bus.emit('player:time'); comprobarAudio();
});

// Si el audio que llegó dura muy distinto de lo esperado, es otra canción (una copia equivocada en el
// dispositivo): se borra esa copia y se vuelve a cargar del servidor, en el mismo punto.
const revisadas = new Set();
function comprobarAudio() {
  const s = player.current;
  const esperada = s?.duracionSegundos || 0;
  if (!s || esperada < 20 || !isFinite(audio.duration) || audio.duration <= 0) return;
  if (!player.tieneCopia(s.id) || mezcla.enCurso) return; // solo se revisan las copias del dispositivo
  if (Math.abs(audio.duration - esperada) <= Math.max(5, esperada * 0.05)) return;
  if (revisadas.has(s.id)) return; // una vez por canción: si sigue distinto, es la duración guardada la que está mal
  revisadas.add(s.id);
  console.warn('Audio distinto del esperado', s.id, s.nombre, audio.duration, esperada);
  const id = s.id, sonaba = !audio.paused;
  bus.emit('audio:distinto', {
    id,
    reintentar: () => {
      if (player.current?.id !== id) return;
      if (urlEnUso) { URL.revokeObjectURL(urlEnUso); urlEnUso = null; }
      const t = audio.currentTime;
      audio.src = audioUrl(id) + '&r=' + Date.now();
      if (t > 1) audio.addEventListener('loadedmetadata', () => { try { audio.currentTime = t; } catch { } }, { once: true });
      if (sonaba) audio.play().catch(() => { });
    },
  });
}
audio.addEventListener('durationchange', () => bus.emit('player:time'));
audio.addEventListener('progress', () => bus.emit('player:buffer'));

let lastSave = 0;
audio.addEventListener('timeupdate', () => {
  if (!audio.paused && audio.currentTime > 0 && !audio.dataset.avanceMs && audio.dataset.inicioMs) {
    audio.dataset.avanceMs = String(Math.round(performance.now() - Number(audio.dataset.inicioMs)));
  }
  player._tick();
  bus.emit('player:time');
  if (Date.now() - lastSave > 5000) { lastSave = Date.now(); player._save(); }
  if ('mediaSession' in navigator && navigator.mediaSession.setPositionState && isFinite(audio.duration)) {
    try { navigator.mediaSession.setPositionState({ duration: audio.duration, position: Math.min(audio.currentTime, audio.duration), playbackRate: 1 }); } catch { }
  }
  // Si la cola se acaba, pedir la radio con tiempo (no justo al terminar)
  const cur = player.current;
  if (prefs.autoRadio && cur && player.repeat === 'off' && player.items.length - player.index <= 2 && player.context?.type !== 'juntos-oyente'
      && audio.duration && audio.currentTime > audio.duration * 0.2 && player._radioPara !== cur._uid) {
    player._radioPara = cur._uid;
    player._extendWithRadio(false);
  }
  mezcla.revisar();
  // Precargar la siguiente canción cuando falte poco
  const nxt = player.items[player.index + 1];
  if (!nxt || !audio.duration) return;
  if (esIOS) {
    if (audio.currentTime > audio.duration * 0.35 && siguiente.id !== nxt.id) traerSiguiente(nxt.id);
  } else if (!navigator.serviceWorker?.controller && audio.currentTime > audio.duration * 0.6 && preloader.dataset.id !== String(nxt.id)) {
    // Con service worker la siguiente ya la precarga offline.js (entera o solo el principio, según la red):
    // un segundo <audio> la bajaría otra vez
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
  // Un fallo de red o de decodificación no expresa la intención de cambiar de canción.
  // Se conserva la pista y la posición; reproducir vuelve a cargar esta misma pista.
  audio.pause();
  bus.emit('player:state');
  bus.emit('toast', { msg: `No se pudo cargar “${s.nombre}”. Toca reproducir para reintentar.`, error: true });
});

window.addEventListener('pagehide', () => player._save());
document.addEventListener('visibilitychange', () => { if (document.hidden) player._save(); });

// ═══════════════════ Transición entre canciones (crossfade) ═══════════════════
// En los últimos segundos, la canción que termina pasa a un segundo <audio> que baja su volumen
// mientras la siguiente empieza en el principal y sube. Si algo falla se corta sin transición.
// En iPhone no se usa: un segundo <audio> puede interrumpir la música con la pantalla bloqueada.

const cola = new Audio();
cola.preload = 'auto';

export const mezcla = {
  entrada: 1,       // factor de volumen de la canción que entra
  salida: 0,        // factor de volumen de la que sale (en el segundo <audio>)
  enCurso: false,
  _para: null,
  _t: null,
  get disponible() { return !esIOS; },
  volumenCola() { cola.volume = player.muted ? 0 : Math.min(1, Math.pow(player.volume, 2) * sleep.factor * this.salida); },
  revisar() {
    const seg = Number(prefs.mezcla) || 0;
    const cur = player.current;
    if (!seg || !this.disponible || this.enCurso || !cur || audio.paused) return;
    const dur = audio.duration;
    if (!isFinite(dur) || dur < seg * 2 + 10) return;
    if (dur - audio.currentTime > seg || this._para === cur._uid) return;
    if (player.repeat === 'one' || sleep.endOfSong) return;
    const nxt = player.items[player.index + 1];
    if (!nxt || !player.disponible(nxt)) return;
    this._para = cur._uid;
    this.empezar(seg);
  },
  empezar(seg) {
    this.enCurso = true;
    // La canción actual sigue en el segundo <audio> desde el mismo punto
    cola.src = audio.currentSrc || audio.src;
    cola.volume = 0;
    let listo = false;
    const pasar = () => {
      if (listo) return;
      listo = true;
      if (Math.abs(cola.currentTime - audio.currentTime) > 0.2) { try { cola.currentTime = audio.currentTime + 0.05; } catch { } }
      this.salida = 1;
      this.entrada = 0;
      this.volumenCola();
      player.next(true);
      this.enCurso = false;
      const t0 = Date.now(), ms = seg * 1000;
      clearInterval(this._t);
      this._t = setInterval(() => {
        const k = Math.min(1, (Date.now() - t0) / ms);
        // Curva de igual potencia: el volumen total no baja a la mitad
        this.entrada = Math.sin(k * Math.PI / 2);
        this.salida = Math.cos(k * Math.PI / 2);
        player._applyVolume();
        if (k >= 1) this.cortar();
      }, 80);
    };
    cola.addEventListener('playing', pasar, { once: true });
    try { cola.currentTime = audio.currentTime; } catch { }
    cola.play().catch(() => { listo = true; this.enCurso = false; this.cortar(); });
    // Si el segundo <audio> no arranca rápido, se sigue sin transición
    setTimeout(() => { if (!listo) { listo = true; this.enCurso = false; this.cortar(); } }, 1500);
  },
  /** Termina la transición: la canción que sale se detiene y la nueva queda a volumen normal. */
  cortar() {
    clearInterval(this._t);
    this._t = null;
    if (cola.src) { cola.pause(); cola.removeAttribute('src'); try { cola.load(); } catch { } }
    this.salida = 0;
    if (this.entrada !== 1) { this.entrada = 1; player._applyVolume(); }
  },
};
/**
 * Salida de audio elegida en «Dispositivos» (solo navegadores con setSinkId). Se aplica al <audio> principal,
 * al de la transición y, si el ecualizador está activo, al contexto de Web Audio (por ahí suena entonces).
 */
export async function fijarSalida(deviceId = '') {
  const destinos = [audio, cola].filter((a) => typeof a.setSinkId === 'function');
  if (!destinos.length) throw new Error('Este navegador no permite elegir la salida de audio.');
  for (const a of destinos) await a.setSinkId(deviceId);
  if (fx.ctx && typeof fx.ctx.setSinkId === 'function') await fx.ctx.setSinkId(deviceId);
  store.set('sm_salida', deviceId);
}
export const salidaActual = () => audio.sinkId ?? null;
// Al abrir, se vuelve a la última salida elegida (si ya no existe, el navegador usa la predeterminada)
{ const guardada = store.get('sm_salida', ''); if (guardada && typeof audio.setSinkId === 'function') audio.setSinkId(guardada).catch(() => store.del('sm_salida')); }

// Pausar durante la transición detiene también la canción que sale
// (se comprueba un momento después: al cambiar de canción el navegador también avisa "pause")
audio.addEventListener('pause', () => setTimeout(() => { if (audio.paused && mezcla._t) mezcla.cortar(); }, 300));

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
    // Los últimos 30 s el volumen baja poco a poco (curva suave: casi no se nota al quedarse dormido)
    if (left <= 30000 && left > 0) { this.factor = Math.pow(Math.max(0, left / 30000), 1.5); player._applyVolume(); }
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
      // Volumen parejo: se mide el nivel de cada canción y se ajusta una ganancia; un limitador evita saturar
      this.nivel = this.ctx.createGain();
      this.medidor = this.ctx.createAnalyser();
      this.medidor.fftSize = 2048;
      this.limitador = this.ctx.createDynamicsCompressor();
      this.limitador.threshold.value = -3;
      this.limitador.knee.value = 2;
      this.limitador.ratio.value = 20;
      this.limitador.attack.value = 0.003;
      this.limitador.release.value = 0.25;
      let node = src;
      for (const f of this.filters) { node.connect(f); node = f; }
      node.connect(this.medidor);
      node.connect(this.nivel);
      this.nivel.connect(this.limitador);
      this.limitador.connect(this.analyser);
      this.analyser.connect(this.ctx.destination);
      this.apply();
      nivelador.iniciar();
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

// ═══════════════════ Volumen parejo ═══════════════════
// Mide la intensidad (RMS) de lo que suena y lleva cada canción a un nivel parecido (≈ -18 dBFS),
// entre -10 y +8 dB. Lo aprendido de cada canción se recuerda para que empiece ya ajustada.

const NIVELES_KEY = 'sm_niveles';
const OBJETIVO_DB = -18;
export const nivelador = {
  _t: null,
  _suma: 0,
  _n: 0,
  _id: null,
  _niveles: store.get(NIVELES_KEY, {}),
  get disponible() { return !esIOS && !!(window.AudioContext || window.webkitAudioContext); },
  iniciar() {
    if (this._t || !fx.ctx) return;
    const buf = new Float32Array(fx.medidor.fftSize);
    this._t = setInterval(() => {
      const cur = player.current;
      if (!cur) return;
      if (cur.id !== this._id) this._nueva(cur.id);
      if (!prefs.normalizar) { this._fijar(0, 0.3); return; }
      if (audio.paused || player.buffering) return;
      fx.medidor.getFloatTimeDomainData(buf);
      let e = 0;
      for (let i = 0; i < buf.length; i++) e += buf[i] * buf[i];
      const db = 10 * Math.log10(e / buf.length + 1e-12);
      if (db < -50) return; // silencio: no cuenta
      this._suma += Math.pow(10, db / 10);
      this._n++;
      if (this._n < 8) return; // unos segundos de muestra antes de ajustar
      const medio = 10 * Math.log10(this._suma / this._n);
      const g = Math.max(-10, Math.min(8, OBJETIVO_DB - medio));
      this._fijar(g, 1.5);
      if (this._n % 40 === 0) this._recordar(cur.id, g);
    }, 250);
  },
  _nueva(id) {
    if (this._id != null && this._n >= 40) this._recordar(this._id, Math.max(-10, Math.min(8, OBJETIVO_DB - 10 * Math.log10(this._suma / this._n))));
    this._id = id;
    this._suma = 0;
    this._n = 0;
    const g = this._niveles[id];
    if (prefs.normalizar) this._fijar(typeof g === 'number' ? g : 0, 0.05);
  },
  _recordar(id, g) {
    this._niveles[id] = Math.round(g * 10) / 10;
    const ks = Object.keys(this._niveles);
    if (ks.length > 3000) for (const k of ks.slice(0, ks.length - 3000)) delete this._niveles[k];
    store.set(NIVELES_KEY, this._niveles);
  },
  _fijar(db, tau) {
    if (!fx.nivel) return;
    fx.nivel.gain.setTargetAtTime(Math.pow(10, db / 20), fx.ctx.currentTime, tau);
  },
  /** Activa o desactiva desde Ajustes (necesita el gesto del usuario para crear el audio de Web Audio). */
  activar(on) {
    prefs.normalizar = !!on;
    prefs.save();
    if (on) { fx.ensure(); fx.resume(); }
  },
};

if (prefs.eqEnabled || prefs.normalizar) {
  // El contexto de audio necesita un gesto del usuario: se activa en el primer clic
  const once = () => { fx.ensure(); fx.resume(); window.removeEventListener('pointerdown', once); };
  window.addEventListener('pointerdown', once);
}
