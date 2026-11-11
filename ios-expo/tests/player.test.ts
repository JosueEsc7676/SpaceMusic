import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transform } from 'esbuild';
import { parseMessage, webCommand } from '../src/protocol';
import { activeLine, parseLrc } from '../src/player/lrc';
import { contextTitle, coverUrl, createStore, fmtTime } from '../src/player/store';
import { AudioEngine } from '../src/audio';
import { lastPlaylist } from './native-port';
import type { Storage } from '../src/storage';

const server = 'https://music.example';
const raw = (type: string, fields: object = {}) => JSON.stringify({ version: 1, type, ...fields });
const ui = { songId: 3, fav: true, shuffle: false, repeat: 'all', autoRadio: true, bar: { top: 700, left: 8, width: 359, height: 64 } };

test('el estado del reproductor que llega de la web se valida campo por campo', () => {
  assert.deepEqual(parseMessage(raw('ui', { ui }), server), { type: 'ui', ui });
  assert.deepEqual(parseMessage(raw('ui', { ui: { ...ui, bar: null, extra: 'x' } }), server), { type: 'ui', ui: { ...ui, bar: null } });
  for (const bad of [{ ...ui, repeat: 'x' }, { ...ui, fav: 'si' }, { ...ui, bar: { top: 0, left: 0, width: 0, height: 64 } }, { ...ui, bar: { top: 'x' } }, { ...ui, songId: -1 }])
    assert.equal(parseMessage(raw('ui', { ui: bad }), server), null);
  assert.equal(parseMessage(raw('openPlayer', { tab: 'letra' }), server)?.type, 'openPlayer');
  assert.equal(parseMessage(raw('openPlayer', { tab: null }), server)?.type, 'openPlayer');
  assert.equal(parseMessage(raw('openPlayer', { tab: '<script>' }), server), null);
});

test('las órdenes hacia la web se serializan sin permitir inyectar código', () => {
  assert.equal(webCommand('move', { from: 3, to: 1 }), 'window.__smCmd?.("move",{"from":3,"to":1})');
  assert.equal(webCommand('remove', '");alert(1);//'), 'window.__smCmd?.("remove","\\");alert(1);//")');
});

test('las letras LRC se leen como en la web: offset, créditos, varias marcas e interludios', () => {
  const lines = parseLrc('[offset:+500]\n[ar:x]\n[00:01.00]Letra: Alguien\n[00:10.00][00:40.00]Coro\n[00:20.50]Verso\n');
  assert.equal(lines.some(l => l.text === 'Letra: Alguien'), false);
  assert.equal(lines[0].interlude, true, 'antes del primer verso hay un interludio');
  assert.deepEqual(lines.filter(l => l.text).map(l => [l.t, l.text]), [[9.5, 'Coro'], [20, 'Verso'], [39.5, 'Coro']]);
  assert.equal(activeLine(lines, 0), 0);
  assert.equal(lines[activeLine(lines, 21)].text, 'Verso');
  assert.equal(lines[activeLine(lines, 30)].interlude, true, 'pausa larga tras el verso');
  assert.equal(lines[activeLine(lines, 999)].text, 'Coro');
  assert.equal(activeLine([], 10), -1);
});

test('portadas: solo del servidor elegido y con el tamaño que entiende el servidor', () => {
  assert.equal(coverUrl(server, '/api/portada/db/7?v=ab', 128), server + '/api/portada/db/7?s=128&v=ab');
  assert.equal(coverUrl(server, '/img/x.png'), server + '/img/x.png');
  assert.equal(coverUrl(server, 'https://evil.test/x.png'), undefined);
  assert.equal(coverUrl(server, undefined), undefined);
  assert.equal(contextTitle('busqueda'), 'Desde la búsqueda');
  assert.equal(contextTitle('desconocido'), 'Reproduciendo');
  assert.equal(fmtTime(84), '1:24'); assert.equal(fmtTime(NaN), '0:00');
});

test('el almacén solo avisa cuando algo cambia (no redibuja el reproductor sin motivo)', () => {
  const store = createStore({ a: 1, b: 'x' });
  let calls = 0; store.subscribe(() => calls++);
  store.set({ a: 1 }); assert.equal(calls, 0);
  store.set({ a: 2 }); assert.equal(calls, 1); assert.equal(store.get().b, 'x');
});

test('siguiente, anterior y saltar actúan en el motor nativo sin esperar a la web', async () => {
  const song = (id: number) => ({ id, nombre: `C${id}`, artista: 'P', duracionSegundos: 180, _uid: id });
  const e = new AudioEngine({ local: () => undefined, saveQueue: async () => {} } as unknown as Storage); await e.init();
  e.configure(server, { token: 'fixture', user: { id: 1 } });
  await e.apply({ items: [song(1), song(2), song(3)], index: 0, time: 0, playing: false, repeat: 'off', volume: 1, sleepUntil: 0, sleepEnd: false, autoRadio: false }, 'load');
  e.next(); assert.equal(lastPlaylist.currentIndex, 1); assert.equal(lastPlaylist.playing, true);
  lastPlaylist.currentTime = 40; e.previous();
  assert.equal(lastPlaylist.currentIndex, 1); assert.equal(lastPlaylist.currentTime, 0, 'pasados 3 s vuelve al inicio');
  e.previous(); assert.equal(lastPlaylist.currentIndex, 0);
  e.jump(2); e.next(); assert.equal(lastPlaylist.currentIndex, 2, 'al final sin repetir no salta');
  e.jump(99); assert.equal(lastPlaylist.currentIndex, 2);
  e.stop();
});

test('el puente cede la presentación al reproductor nativo y solo acepta órdenes conocidas', async () => {
  const messages: any[] = [];
  const values = new Map<string, string>([['sm_token', 'fixture'], ['sm_user', '{"id":1}']]);
  class LocalStorage { getItem(k: string) { return values.get(k) ?? null; } setItem(k: string, v: string) { values.set(k, v); } removeItem(k: string) { values.delete(k); } }
  const audio = { paused: true, ended: false, duration: 0, currentTime: 0, src: '', volume: 1, pause() {}, play: async () => {}, load() {}, removeAttribute() {} };
  const callbacks = new Map<string, ((...a: any[]) => void)[]>();
  const bus = { on: (e: string, cb: any) => callbacks.set(e, [...callbacks.get(e) ?? [], cb]), emit: (e: string, ...a: any[]) => callbacks.get(e)?.forEach(cb => cb(...a)) };
  const calls: string[] = [];
  const song = { id: 5, nombre: 'Uno', artista: 'Prueba', _uid: 1 };
  const player: any = {
    audio, items: [song], index: 0, repeat: 'off', volume: 1, muted: false, shuffle: false, context: {}, _finishListen() {}, _save() {},
    get current() { return this.items[this.index]; },
    toggleShuffle() { this.shuffle = !this.shuffle; calls.push('shuffle'); bus.emit('player:mode'); },
    removeAt(i: number) { calls.push(`remove:${i}`); }, move(a: number, b: number) { calls.push(`move:${a}>${b}`); }, cycleRepeat() {}, clearUpcoming() {},
  };
  const prefs = { autoRadio: true, save() { calls.push('prefs'); } };
  const np = { open(_tab?: string) { calls.push('web-np'); } };
  const window: any = { ReactNativeWebView: { postMessage: (m: string) => messages.push(JSON.parse(m)) } };
  const source = await readFile(new URL('../web/bridge.ts', import.meta.url), 'utf8');
  const { code } = await transform(source, { loader: 'ts', format: 'iife', target: 'es2022' });
  const importer = async (url: string) => url.endsWith('/player.js') ? { player, sleep: { until: 0, endOfSong: false, set() {} }, prefs }
    : url.endsWith('/util.js') ? { bus } : url.endsWith('/nowplaying.js') ? { np }
    : url.endsWith('/state.js') ? { lib: { isFav: (id: number) => id === 5, toggleFav: async () => { calls.push('fav'); } } } : {};
  new Function('window', 'Storage', 'localStorage', 'navigator', 'location', '__testImport', code.replace(/\bimport\(/g, '__testImport('))(window, LocalStorage, new LocalStorage(), {}, { origin: server, pathname: '/' }, importer);
  await new Promise(r => setTimeout(r, 60));
  assert.deepEqual(messages.findLast(m => m.type === 'ui').ui, { songId: 5, fav: true, shuffle: false, repeat: 'off', autoRadio: true, bar: null });
  np.open('letra');
  assert.deepEqual(messages.at(-1), { version: 1, type: 'openPlayer', tab: 'letra' });
  assert.equal(calls.includes('web-np'), false, 'la pantalla web no se abre');
  window.__smCmd('shuffle'); window.__smCmd('move', { from: 3, to: 1 }); window.__smCmd('autoRadio', false); window.__smCmd('fav'); window.__smCmd('eval', 'x');
  await new Promise(r => setTimeout(r, 60));
  assert.deepEqual(calls, ['shuffle', 'move:3>1', 'prefs', 'fav']);
  assert.equal(prefs.autoRadio, false);
  assert.equal(messages.findLast(m => m.type === 'ui').ui.shuffle, true);
});
