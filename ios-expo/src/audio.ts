import { createAudioPlaylist, setAudioModeAsync, type AudioPlaylist, type AudioPlaylistStatus } from 'expo-audio';
import { AppState } from 'react-native';
import { appendOnly, audioURL, queueKeys, sameOrigin, type Session, type Snapshot } from './protocol';
import type { Storage } from './storage';

export class AudioEngine {
  private playlist: AudioPlaylist | null = null;
  private state: Snapshot | null = null;
  private session: Session | null = null;
  private server = '';
  private elapsed = new Map<string, number>();
  private registered = new Set<string>();
  private lastTime = 0;
  private lastKey = '';
  private lastSave = 0;
  private extending = false;
  private version = 0;
  private wantsPlay = false;
  private lastActivity = '';
  private last: AudioPlaylistStatus | null = null;
  private syncing = false;
  private radioAfter = 0;
  private loadDeadline = 0;
  private exhausted = false;
  private sourceToken = '';
  onStatus: (status: AudioPlaylistStatus & { items: Snapshot['items']; sleepUntil: number; sleepEnd: boolean; intendedPlaying: boolean; clientRevision: number }) => void = () => {};
  onError: (message: string) => void = () => {};

  constructor(private storage: Storage) {}
  async init() {
    await setAudioModeAsync({ playsInSilentMode: true, shouldPlayInBackground: true, interruptionMode: 'doNotMix' });
    this.playlist = createAudioPlaylist({ updateInterval: 500 });
    this.playlist.addListener('playlistStatusUpdate', s => this.update(s));
    this.playlist.addListener('trackChanged', () => this.metadata());
  }
  configure(server: string, session: Session | null) {
    if (this.server !== server || this.session?.user.id !== session?.user.id) this.stop();
    this.server = server; this.session = session;
  }
  private source(song: Snapshot['items'][number]) {
    return { uri: this.storage.local(song.id) ?? audioURL(this.server, this.session?.token ?? '', song), name: `${song.nombre} · ${song.artista}` };
  }
  async apply(next: Snapshot, intent: 'load' | 'sync' = 'sync') {
    const p = this.playlist;
    if (!p || !this.session) return;
    this.syncing = true;
    try {
    const previous = this.state;
    const newToken = this.sourceToken !== this.session.token;
    const changed = newToken || queueKeys(previous?.items ?? []).join('|') !== queueKeys(next.items).join('|');
    const oldKey = previous && p.currentIndex >= 0 ? queueKeys(previous.items)[p.currentIndex] : '';
    const oldTime = p.currentTime;
    const activeIndex = oldKey ? queueKeys(next.items).indexOf(oldKey) : -1;
    if (changed) {
      if (!newToken && previous && appendOnly(previous.items, next.items)) {
        for (const song of next.items.slice(previous.items.length)) p.add(this.source(song));
      } else {
        this.version++;
        p.pause(); p.clear();
        for (const song of next.items) p.add(this.source(song));
      }
    }
    this.state = { ...next };
    this.sourceToken = this.session.token;
    p.loop = next.repeat === 'one' ? 'single' : next.repeat === 'all' ? 'all' : 'none';
    p.volume = next.volume;
    if (intent === 'load' || newToken || (changed && (!previous || !appendOnly(previous.items, next.items)))) {
      const index = intent === 'load' ? next.index : activeIndex >= 0 ? activeIndex : next.index;
      if (p.currentIndex !== index) p.skipTo(index);
      const time = intent === 'load' ? next.time : oldTime;
      if (time > 0) await p.seekTo(time);
      else if (intent === 'load' && p.currentTime > 0.1) await p.seekTo(0);
      this.loadDeadline = Date.now() + 20000;
      this.exhausted = false;
      this.wantsPlay = intent === 'load' ? next.playing : this.wantsPlay;
      if (this.wantsPlay) p.play(); else p.pause();
    }
    this.metadata();
    await this.storage.saveQueue({ ...next, index: p.currentIndex, time: p.currentTime });
    } finally { this.syncing = false; }
  }
  play() { this.wantsPlay = true; this.playlist?.play(); }
  pause() { this.wantsPlay = false; this.playlist?.pause(); }
  seek(time: number) { return this.playlist?.seekTo(time); }
  /** Saltos directos en la cola nativa: responden al instante y la web se sincroniza con el siguiente estado. */
  jump(index: number) {
    const p = this.playlist, state = this.state;
    if (!p || !state || index < 0 || index >= state.items.length) return;
    this.loadDeadline = Date.now() + 20000; this.exhausted = false; this.wantsPlay = true;
    if (p.currentIndex !== index) p.skipTo(index); else void p.seekTo(0);
    p.play(); this.metadata();
  }
  next() {
    const p = this.playlist, state = this.state;
    if (!p || !state) return;
    if (p.currentIndex + 1 < state.items.length) this.jump(p.currentIndex + 1);
    else if (state.repeat === 'all' && state.items.length) this.jump(0);
  }
  previous() {
    const p = this.playlist;
    if (!p || !this.state) return;
    if (p.currentTime > 3 || p.currentIndex <= 0) void p.seekTo(0);
    else this.jump(p.currentIndex - 1);
  }
  volume(value: number) { if (this.playlist) this.playlist.volume = value; if (this.state) this.state.volume = value; }
  stop() {
    this.version++; this.wantsPlay = false;
    this.playlist?.pause(); this.playlist?.clear(); this.playlist?.clearLockScreenControls();
    this.state = null; this.last = null; this.elapsed.clear(); this.registered.clear(); this.lastKey = ''; this.lastTime = 0;
    this.sourceToken = ''; this.exhausted = false; this.radioAfter = 0;
  }
  destroy() { this.stop(); this.playlist?.destroy(); this.playlist = null; }
  snapshot(): Snapshot | null {
    return this.state && this.playlist ? { ...this.state, index: this.playlist.currentIndex, time: this.playlist.currentTime, playing: this.playlist.playing } : null;
  }
  refreshDownloads() {
    const p = this.playlist, state = this.state;
    if (!p || !state || this.syncing) return;
    const sources = p.sources;
    this.syncing = true;
    try {
      for (let i = p.currentIndex + 1; i < state.items.length; i++) {
        const source = this.source(state.items[i]);
        if (sources[i]?.uri !== source.uri) { p.remove(i); p.insert(source, i); }
      }
    } finally { this.syncing = false; }
  }
  status() { if (this.last) this.update(this.last); }
  private metadata() {
    const p = this.playlist, song = this.state?.items[p?.currentIndex ?? -1];
    if (!p || !song) return;
    const art = song.portadaUrl && sameOrigin(song.portadaUrl, this.server) ? new URL(song.portadaUrl, this.server).href : undefined;
    p.setActiveForLockScreen(true, { title: song.nombre, artist: song.artista, albumTitle: song.album, artworkUrl: art }, { showSeekBackward: true, showSeekForward: true });
  }
  private async request(path: string, body?: unknown) {
    const session = this.session;
    if (!session) throw new Error('No hay sesión');
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), 20000);
    try {
    const res = await fetch(this.server + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: `Bearer ${session.token}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body), signal: abort.signal,
    });
    if (!res.ok) throw new Error(`Servidor (${res.status})`);
    return res.status === 204 ? null : res.json();
    } finally { clearTimeout(timeout); }
  }
  private update(s: AudioPlaylistStatus) {
    const state = this.state, song = state?.items[s.currentIndex];
    if (!state || !song || this.syncing) return;
    if (s.playing) this.wantsPlay = true;
    else if (!s.isBuffering && s.isLoaded && this.last?.playing && !s.didJustFinish) this.wantsPlay = false;
    if (s.didJustFinish && s.currentIndex === state.items.length - 1 && state.repeat === 'off') this.exhausted = true;
    this.last = s;
    if (s.isLoaded) this.loadDeadline = 0;
    else if (this.loadDeadline > 0 && Date.now() > this.loadDeadline) {
      this.loadDeadline = 0; this.pause();
      this.onError('No se pudo cargar esta canción. Revisa la conexión o prueba con otra canción.');
    }
    const key = `${song.id}:${song._uid ?? s.currentIndex}`;
    if (key !== this.lastKey || s.currentTime < this.lastTime - 2) {
      this.lastKey = key; this.lastTime = s.currentTime; this.metadata();
    }
    const delta = s.currentTime - this.lastTime;
    if (s.playing && delta > 0 && delta < 3) this.elapsed.set(key, (this.elapsed.get(key) ?? 0) + delta);
    this.lastTime = s.currentTime;
    const heard = this.elapsed.get(key) ?? 0;
    const threshold = Math.min(30, (s.duration || song.duracionSegundos || 60) / 2);
    if (heard >= threshold && !this.registered.has(key)) {
      this.registered.add(key);
      void this.request('/api/reproducciones', { cancionId: song.id, contexto: state.context?.type ?? 'otro', segundos: Math.round(heard) })
        .catch(() => this.registered.delete(key));
    }
    const activity = `${song.id}:${s.playing}`;
    if (activity !== this.lastActivity) {
      this.lastActivity = activity;
      void this.request('/api/actividad', { cancionId: song.id, sonando: s.playing }).catch(() => {});
    }
    if (state.sleepUntil > 0 && Date.now() >= state.sleepUntil) {
      this.pause(); state.sleepUntil = 0; state.sleepEnd = false;
    }
    if (state.sleepEnd && s.didJustFinish) { this.pause(); state.sleepEnd = false; }
    if (Date.now() - this.lastSave > 5000) {
      this.lastSave = Date.now();
      void this.storage.saveQueue({ ...state, index: s.currentIndex, time: s.currentTime });
    }
    if (state.autoRadio && state.repeat === 'off' && state.items.length - s.currentIndex <= 3) void this.extend();
    this.onStatus({ ...s, playing: this.playlist?.playing ?? s.playing, intendedPlaying: this.wantsPlay, clientRevision: state.clientRevision ?? 0, items: state.items, sleepUntil: state.sleepUntil, sleepEnd: state.sleepEnd });
  }
  private async extend() {
    const state = this.state, p = this.playlist, version = this.version;
    if (!state || !p || this.extending || !this.session || Date.now() < this.radioAfter) return;
    this.radioAfter = Date.now() + 30000;
    this.extending = true;
    try {
      const seed = state.items.at(-1)!;
      const lastIndex = state.items.length - 1;
      const exclude = state.items.slice(-150).map(s => s.id).join(',');
      const songs: unknown = await this.request(`/api/radio/${seed.id}?cantidad=25&excluir=${exclude}`);
      if (version !== this.version || state !== this.state || !Array.isArray(songs)) {
        if (version === this.version) this.radioAfter = 0;
        return;
      }
      const ids = new Set(state.items.map(s => s.id));
      for (const song of songs) {
        if (!song || !Number.isSafeInteger(song.id) || typeof song.nombre !== 'string' || typeof song.artista !== 'string' || ids.has(song.id)) continue;
        song._uid = -Date.now() - song.id;
        ids.add(song.id); state.items.push(song); p.add(this.source(song));
      }
      if (this.wantsPlay && this.exhausted) {
        if (p.currentIndex === lastIndex && p.currentIndex + 1 < state.items.length) p.next();
        p.play(); this.exhausted = false;
      }
    } catch {
      if (AppState.currentState === 'active') this.onError('No se pudo ampliar la radio. Se conserva la cola actual.');
    } finally { this.extending = false; }
  }
}
