import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transform } from 'esbuild';

test('la interfaz controla el audio nativo sin arrancar un segundo audio web', async () => {
  const messages: any[] = [];
  class LocalStorage {
    values = new Map<string, string>();
    getItem(k: string) { return this.values.get(k) ?? null; }
    setItem(k: string, v: string) { this.values.set(k, v); }
    removeItem(k: string) { this.values.delete(k); }
  }
  const local = new LocalStorage();
  local.setItem('sm_token', 'fixture'); local.setItem('sm_user', JSON.stringify({ id: 1 }));
  const audio = { paused: true, ended: false, duration: 180, currentTime: 0, src: '', volume: 0.8, pause() {}, play: async () => {}, load() {}, removeAttribute() {}, addEventListener() {}, removeEventListener() {} };
  const callbacks = new Map<string, ((...args: any[]) => void)[]>();
  const bus = { on: (e: string, cb: (...args: any[]) => void) => callbacks.set(e, [...callbacks.get(e) ?? [], cb]), emit: (e: string, ...args: any[]) => callbacks.get(e)?.forEach(cb => cb(...args)) };
  const song = { id: 1, nombre: 'Primera', artista: 'Prueba', duracionSegundos: 180, _uid: 1 };
  const player: any = { audio, items: [song], index: 0, repeat: 'off', volume: 0.8, muted: false, context: {}, _finishListen() {}, _save() {}, get current() { return this.items[this.index]; }, get playing() { return !audio.paused && !audio.ended; } };
  const sleep = { until: 0, endOfSong: false };
  const window: any = { ReactNativeWebView: { postMessage: (m: string) => messages.push(JSON.parse(m)) } };
  const source = await readFile(new URL('../web/bridge.ts', import.meta.url), 'utf8');
  const { code } = await transform(source, { loader: 'ts', format: 'iife', target: 'es2022' });
  const importer = async (url: string) => url.endsWith('/player.js') ? { player, sleep, prefs: { autoRadio: true } } : url.endsWith('/util.js') ? { bus } : {};
  new Function('window', 'Storage', 'localStorage', 'navigator', 'location', '__testImport', code.replace(/\bimport\(/g, '__testImport('))(window, LocalStorage, local, {}, { origin: 'https://music.example', pathname: '/' }, importer);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(messages.some(m => m.type === 'ready'), true);
  messages.length = 0;
  player._load(0, true, 12);
  const command = messages.find(m => m.type === 'queue' && m.intent === 'load');
  assert.equal(command.state.time, 12); assert.equal(command.state.playing, true);
  audio.pause(); assert.equal(messages.at(-1).type, 'pause');
  audio.currentTime = 30; assert.equal(messages.findLast(m => m.type === 'seek').time, 30);
  const second = { ...song, id: 2, _uid: 2, nombre: 'Segunda' };
  const before = messages.length;
  window.__smNativeStatus({ clientRevision: command.state.clientRevision, items: [song, second], currentIndex: 1, currentTime: 2, duration: 180, playing: true, isBuffering: false, didJustFinish: false, sleepUntil: 0, sleepEnd: false });
  assert.equal(player.current.id, 2); assert.equal(player.playing, true); assert.equal(audio.currentTime, 2);
  assert.equal(messages.length, before, 'una respuesta nativa no debe producir un bucle de órdenes');
  player._load(0, true, 0);
  window.__smNativeStatus({ clientRevision: command.state.clientRevision, items: [song, second], currentIndex: 1, currentTime: 30, duration: 180, playing: true });
  assert.equal(player.current.id, 1, 'una respuesta antigua no debe reemplazar la selección nueva');
});
