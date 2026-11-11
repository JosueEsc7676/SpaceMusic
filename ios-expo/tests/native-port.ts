export class FakePlaylist {
  id = 'fixture'; sources: { uri?: string; name?: string }[] = []; currentIndex = 0;
  playing = false; currentTime = 0; duration = 180; volume = 1; loop = 'none';
  private listeners = new Map<string, (s: any) => void>();
  addListener(name: string, callback: (s: any) => void) { this.listeners.set(name, callback); }
  emit(time: number, extra: Record<string, unknown> = {}) {
    this.currentTime = time;
    this.listeners.get('playlistStatusUpdate')?.({ id: this.id, currentIndex: this.currentIndex, trackCount: this.sources.length, currentTime: time, duration: this.duration, playing: this.playing, isBuffering: false, isLoaded: true, playbackRate: 1, muted: false, volume: this.volume, loop: this.loop, didJustFinish: false, ...extra });
  }
  pause() { this.playing = false; } play() { this.playing = true; }
  clear() { this.sources = []; this.currentIndex = 0; this.currentTime = 0; }
  add(source: { uri?: string; name?: string }) { this.sources.push(source); }
  remove(index: number) { this.sources.splice(index, 1); }
  insert(source: { uri?: string; name?: string }, index: number) { this.sources.splice(index, 0, source); }
  skipTo(index: number) { this.currentIndex = index; this.currentTime = 0; }
  next() { this.skipTo(Math.min(this.currentIndex + 1, this.sources.length - 1)); }
  async seekTo(time: number) { this.currentTime = time; }
  setActiveForLockScreen() {} clearLockScreenControls() {}
}
export let lastPlaylist: FakePlaylist;
export function createAudioPlaylist() { lastPlaylist = new FakePlaylist(); return lastPlaylist; }
export async function setAudioModeAsync() {}
export const AppState = { currentState: 'active' };
