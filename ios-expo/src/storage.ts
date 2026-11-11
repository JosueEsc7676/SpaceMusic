import * as SecureStore from 'expo-secure-store';
import * as FS from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import { isSong, scopedKey, type Session, type Snapshot, type Song } from './protocol';

const root = `${FS.documentDirectory}SpaceMusic/`;
export type Download = { song: Song; uri: string; downloadedAt: number };
export class Storage {
  private scope = '';
  private generation = 0;
  private pending: { song: Song; url: string; exportFile: boolean; generation: number }[] = [];
  private transfer: FS.DownloadResumable | null = null;
  private running = false;
  private writes: Promise<void> = Promise.resolve();
  private warnedSave = false;
  private current: { song: Song; exportFile: boolean; generation: number } | null = null;
  downloads: Download[] = [];
  onChange: () => void = () => {};
  onError: (message: string) => void = () => {};
  onDownload: (id: number, ok: boolean) => void = () => {};

  async setScope(server: string, session: Session | null) {
    const scope = session ? scopedKey(server, session.user.id) : '';
    if (scope === this.scope) return;
    this.generation++;
    this.pending = [];
    await this.transfer?.cancelAsync().catch(() => {});
    await this.writes;
    this.scope = scope; this.downloads = [];
    if (scope) {
      await FS.makeDirectoryAsync(this.dir(), { intermediates: true });
      const saved = await this.read<Download[]>('downloads', []);
      for (const d of saved) {
        if (isSong(d.song) && d.uri.startsWith(this.dir()) && (await FS.getInfoAsync(d.uri)).exists) this.downloads.push(d);
      }
    }
    this.onChange();
  }
  private dir() { return `${root}${this.scope}/`; }
  async read<T>(name: string, fallback: T): Promise<T> {
    if (!this.scope) return fallback;
    try { return JSON.parse(await FS.readAsStringAsync(`${this.dir()}${name}.json`)) as T; } catch { return fallback; }
  }
  async write(name: string, data: unknown) {
    if (!this.scope) return;
    const generation = this.generation, uri = `${this.dir()}${name}.json`, json = JSON.stringify(data);
    const write = this.writes.then(async () => { if (generation === this.generation) await FS.writeAsStringAsync(uri, json); });
    this.writes = write.catch(() => {});
    await write;
  }
  async saveQueue(state: Snapshot) {
    try { await this.write('queue', { ...state, playing: false }); this.warnedSave = false; }
    catch { if (!this.warnedSave) { this.warnedSave = true; this.onError('No se pudo guardar la cola en el iPhone. Revisa el espacio disponible.'); } }
  }
  local(id: number) { return this.downloads.find(d => d.song.id === id)?.uri; }
  download(url: string, song: Song, exportFile: boolean) {
    if (!this.scope) return;
    const existing = this.local(song.id);
    if (existing) {
      if (exportFile) void this.share(existing, song).catch(() => this.onError('No se pudo exportar el archivo. La canción permanece descargada.'));
      this.onDownload(song.id, true); return;
    }
    if (this.current?.song.id === song.id && this.current.generation === this.generation) { this.current.exportFile ||= exportFile; return; }
    const pending = this.pending.find(p => p.song.id === song.id);
    if (pending) pending.exportFile ||= exportFile;
    else this.pending.push({ song, url, exportFile, generation: this.generation });
    void this.process();
  }
  private async process() {
    if (this.running) return; this.running = true;
    try {
      while (this.pending.length) {
        const p = this.pending.shift()!;
        if (p.generation !== this.generation) continue;
        this.current = p;
        const ext = /^[a-z0-9]{2,5}$/i.test(p.song.formato ?? '') ? p.song.formato : 'mp3';
        const uri = `${this.dir()}${p.song.id}.${ext}`;
        try {
          this.transfer = FS.createDownloadResumable(p.url, uri);
          const result = await this.transfer.downloadAsync();
          if (p.generation !== this.generation) { await FS.deleteAsync(uri, { idempotent: true }); continue; }
          if (!result || result.status < 200 || result.status >= 300) throw new Error('El servidor no pudo descargar esta canción.');
          this.downloads = [{ song: p.song, uri, downloadedAt: Date.now() }, ...this.downloads.filter(d => d.song.id !== p.song.id)];
          await this.write('downloads', this.downloads);
          if (p.generation !== this.generation) continue;
          this.onDownload(p.song.id, true); this.onChange();
          if (p.exportFile) await this.share(uri, p.song).catch(() => this.onError('No se pudo exportar el archivo. La canción permanece descargada.'));
        } catch (error) {
          await FS.deleteAsync(uri, { idempotent: true });
          if (p.generation === this.generation) {
            this.onDownload(p.song.id, false);
            this.onError(`No se descargó “${p.song.nombre}”. ${error instanceof Error ? error.message : ''}`);
          }
        } finally { this.transfer = null; this.current = null; }
      }
    } finally { this.running = false; }
  }
  async share(uri: string, song?: Song) {
    if (!(await Sharing.isAvailableAsync())) return;
    let target = uri;
    if (song && FS.cacheDirectory) {
      const extension = uri.split('.').at(-1) ?? 'mp3';
      const name = `${song.artista} - ${song.nombre}`.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 150);
      target = `${FS.cacheDirectory}${name}.${extension}`;
      await FS.copyAsync({ from: uri, to: target });
    }
    await Sharing.shareAsync(target);
  }
  async remove(id: number) {
    const d = this.downloads.find(d => d.song.id === id);
    if (d) await FS.deleteAsync(d.uri, { idempotent: true });
    this.downloads = this.downloads.filter(d => d.song.id !== id);
    await this.write('downloads', this.downloads); this.onChange();
  }
  async clear() {
    this.generation++; this.pending = [];
    await this.transfer?.cancelAsync().catch(() => {});
    await this.writes;
    if (this.scope) { await FS.deleteAsync(this.dir(), { idempotent: true }); await FS.makeDirectoryAsync(this.dir(), { intermediates: true }); }
    this.downloads = []; this.onChange();
  }
}

const key = (server: string) => 'session.' + Array.from(server).map(c => c.charCodeAt(0).toString(16)).join('');
export async function readSession(server: string): Promise<Session | null> {
  try { const s = JSON.parse(await SecureStore.getItemAsync(key(server)) ?? 'null'); return typeof s?.token === 'string' && Number.isSafeInteger(s?.user?.id) && s.user.id > 0 ? s : null; } catch { return null; }
}
export async function saveSession(server: string, session: Session | null) {
  if (session) await SecureStore.setItemAsync(key(server), JSON.stringify(session), { keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY });
  else await SecureStore.deleteItemAsync(key(server));
}
export async function readServer() { return await SecureStore.getItemAsync('server') ?? 'https://spacemusic.tail8ccfdf.ts.net'; }
export async function saveServer(server: string) { await SecureStore.setItemAsync('server', server); }
