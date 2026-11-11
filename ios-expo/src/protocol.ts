export type Song = {
  id: number; nombre: string; artista: string; album?: string;
  portadaUrl?: string; duracionSegundos?: number; formato?: string; _uid?: number;
};
export type Snapshot = {
  clientRevision?: number;
  items: Song[]; index: number; time: number; playing: boolean;
  repeat: 'off' | 'all' | 'one'; volume: number;
  context?: { type?: string; label?: string; route?: string };
  sleepUntil: number; sleepEnd: boolean; autoRadio: boolean;
};
export type Session = { token: string; user: { id: number; [key: string]: unknown } };
export type BridgeMessage =
  | { type: 'ready' | 'settings' }
  | { type: 'session'; session: Session | null }
  | { type: 'queue'; state: Snapshot; intent?: 'load' | 'sync' | 'restore' }
  | { type: 'play' | 'pause' | 'stop' }
  | { type: 'seek'; time: number }
  | { type: 'volume'; volume: number }
  | { type: 'download'; url: string; song: Song; exportFile: boolean }
  | { type: 'removeDownload'; id: number }
  | { type: 'clearDownloads' }
  | { type: 'notify'; title: string; body: string; route: string }
  | { type: 'error'; message: string }
  | { type: 'openPlayer'; tab: PlayerTab }
  | { type: 'ui'; ui: PlayerUi };
export type PlayerTab = 'letra' | 'cola' | null;
export type Rect = { top: number; left: number; width: number; height: number };
/** Estado de la web que el reproductor nativo necesita mostrar: reglas de cola y preferencias. */
export type PlayerUi = { songId: number; fav: boolean; shuffle: boolean; repeat: 'off' | 'all' | 'one'; autoRadio: boolean; bar: Rect | null };
/** Órdenes que el reproductor nativo puede pedir a la web (allowlist; el puente ignora el resto). */
export type WebCommand = 'shuffle' | 'repeat' | 'autoRadio' | 'fav' | 'remove' | 'move' | 'clear' | 'sleep' | 'radio' | 'artist' | 'album' | 'download' | 'removeDownload' | 'menu';
export function webCommand(name: WebCommand, arg?: unknown): string {
  return `window.__smCmd?.(${JSON.stringify(name)},${JSON.stringify(arg ?? null)})`;
}
const finite = (v: unknown, min: number, max: number) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
export function isRect(v: unknown): v is Rect {
  const r = v as Rect;
  return !!r && typeof r === 'object' && finite(r.top, -10000, 10000) && finite(r.left, -10000, 10000) && finite(r.width, 1, 10000) && finite(r.height, 1, 1000);
}

export function normalizeServer(value: string): string {
  const u = new URL(value.trim());
  if (u.protocol !== 'https:' || u.username || u.password || u.pathname !== '/' || u.search || u.hash) {
    throw new Error('Usa la dirección HTTPS del servidor, sin rutas ni contraseñas.');
  }
  return u.origin;
}
export function sameOrigin(url: string, server: string): boolean {
  if (typeof url !== 'string') return false;
  try { return new URL(url, server).origin === server; } catch { return false; }
}
export function audioURL(server: string, token: string, song: Song): string {
  return `${server}/api/canciones/${song.id}/audio?t=${encodeURIComponent(token)}`;
}
export function isSong(value: unknown): value is Song {
  if (!value || typeof value !== 'object') return false;
  const s = value as Partial<Song>;
  return Number.isSafeInteger(s.id) && Number(s.id) > 0 && typeof s.nombre === 'string' &&
    s.nombre.length <= 1000 && typeof s.artista === 'string' && s.artista.length <= 1000;
}
export function parseMessage(raw: string, server: string): BridgeMessage | null {
  if (raw.length > 1_500_000) return null;
  try {
    const m = JSON.parse(raw);
    if (m?.version !== 1 || typeof m.type !== 'string') return null;
    switch (m.type) {
      case 'ready': case 'settings': case 'play': case 'pause': case 'stop': case 'clearDownloads': return m;
      case 'error': return typeof m.message === 'string' && m.message.length <= 1000 ? m : null;
      case 'session': return m.session === null || (typeof m.session?.token === 'string' && m.session.token.length < 16000 && Number.isSafeInteger(m.session?.user?.id) && m.session.user.id > 0) ? m : null;
      case 'queue': {
        const s = m.state;
        return (!m.intent || ['load', 'sync', 'restore'].includes(m.intent)) && s && Array.isArray(s.items) && s.items.length <= 2000 && s.items.every(isSong) &&
          Number.isInteger(s.index) && s.index >= 0 && s.index < s.items.length &&
          Number.isFinite(s.time) && s.time >= 0 && typeof s.playing === 'boolean' &&
          ['off', 'all', 'one'].includes(s.repeat) && Number.isFinite(s.volume) && s.volume >= 0 && s.volume <= 1 &&
          Number.isFinite(s.sleepUntil) && typeof s.sleepEnd === 'boolean' && typeof s.autoRadio === 'boolean' &&
          (s.clientRevision === undefined || (Number.isSafeInteger(s.clientRevision) && s.clientRevision >= 0)) ? m : null;
      }
      case 'seek': return Number.isFinite(m.time) && m.time >= 0 ? m : null;
      case 'volume': return Number.isFinite(m.volume) && m.volume >= 0 && m.volume <= 1 ? m : null;
      case 'removeDownload': return Number.isSafeInteger(m.id) && m.id > 0 ? m : null;
      case 'download': {
        if (!sameOrigin(m.url, server) || !isSong(m.song)) return null;
        const path = new URL(m.url).pathname;
        return path === `/api/canciones/${m.song.id}/audio` || path === `/api/canciones/${m.song.id}/descargar` ? m : null;
      }
      case 'notify': return typeof m.title === 'string' && m.title.length <= 200 && typeof m.body === 'string' && m.body.length <= 1000 && typeof m.route === 'string' && /^#\/[\w\-/%?=&.]*$/.test(m.route) ? m : null;
      case 'openPlayer': return m.tab === null || m.tab === 'letra' || m.tab === 'cola' ? m : null;
      case 'ui': {
        const u = m.ui;
        return u && typeof u === 'object' && Number.isSafeInteger(u.songId) && u.songId >= 0 && typeof u.fav === 'boolean' && typeof u.shuffle === 'boolean' &&
          ['off', 'all', 'one'].includes(u.repeat) && typeof u.autoRadio === 'boolean' && (u.bar === null || isRect(u.bar))
          ? { type: 'ui', ui: { songId: u.songId, fav: u.fav, shuffle: u.shuffle, repeat: u.repeat, autoRadio: u.autoRadio, bar: u.bar } } : null;
      }
      default: return null;
    }
  } catch { return null; }
}

export function queueKeys(items: Song[]): string[] {
  return items.map((s, i) => `${s.id}:${s._uid ?? i}`);
}
export function appendOnly(previous: Song[], next: Song[]): boolean {
  const a = queueKeys(previous), b = queueKeys(next);
  return a.length <= b.length && a.every((k, i) => b[i] === k);
}
export function scopedKey(server: string, user: number): string {
  const hex = Array.from(server).map(c => c.charCodeAt(0).toString(16).padStart(2, '0')).join('');
  return `servers/${hex.match(/.{1,80}/g)?.join('/')}/users/${user}`;
}
