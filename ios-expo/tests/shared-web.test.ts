import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transform } from 'esbuild';

test('el puente funciona con el reproductor real de la web: lista, siguiente, cola, aleatorio y buscar posición', async () => {
  const messages: any[] = [];
  class LocalStorage {
    values = new Map<string, string>();
    getItem(k: string) { return this.values.get(k) ?? null; }
    setItem(k: string, v: string) { this.values.set(k, v); }
    removeItem(k: string) { this.values.delete(k); }
  }
  class FakeAudio extends EventTarget {
    preload = ''; muted = false; volume = 1; paused = true; ended = false; currentTime = 0; duration = 180; src = ''; dataset = {};
    play = async () => { this.paused = false; };
    pause() { this.paused = true; } load() {} removeAttribute() {}
  }
  const local = new LocalStorage(); local.setItem('sm_token', 'fixture'); local.setItem('sm_user', JSON.stringify({ id: 1 }));
  const win: any = { addEventListener() {}, removeEventListener() {}, ReactNativeWebView: { postMessage: (text: string) => messages.push(JSON.parse(text)) } };
  const globals: Record<string, unknown> = {
    window: win, document: { addEventListener() {}, querySelector: () => null }, navigator: { userAgent: 'iPhone', platform: 'iPhone' },
    localStorage: local, Storage: LocalStorage, Audio: FakeAudio, location: { origin: 'https://music.example', pathname: '/' }, MutationObserver: class { observe() {} },
  };
  const previous = Object.fromEntries(Object.keys(globals).map(k => [k, Object.getOwnPropertyDescriptor(globalThis, k)]));
  try {
    for (const [key, value] of Object.entries(globals)) Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
    const playerModule = await import(new URL('../shared/player.mjs', import.meta.url).href);
    const util = await import(new URL('../shared/util.mjs', import.meta.url).href);
    const { player } = playerModule;
    player.restore();
    const { code } = await transform(await readFile(new URL('../web/bridge.ts', import.meta.url), 'utf8'), { loader: 'ts', format: 'iife', target: 'es2022' });
    const importer = async (url: string) => url.endsWith('/player.js') ? playerModule : url.endsWith('/util.js') ? util : {};
    new Function('__testImport', code.replace(/\bimport\(/g, '__testImport('))(importer);
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(messages.some(m => m.type === 'error'), false);
    assert.equal(messages.some(m => m.type === 'ready'), true);
    const songs = [1, 2, 3].map(id => ({ id, nombre: `Canción ${id}`, artista: 'Prueba', duracionSegundos: 180 }));
    player.play(songs, 0, { type: 'playlist' });
    assert.equal(messages.findLast(m => m.type === 'queue').state.items.length, 3);
    player.next(); assert.equal(player.current.id, 2);
    player.seek(40); assert.equal(messages.findLast(m => m.type === 'seek').time, 40);
    player.playNext(songs[0]); assert.equal(player.upcoming[0].id, 1);
    player.toggleShuffle(); assert.equal(player.current.id, 2);
    player.toggleShuffle(); assert.equal(player.current.id, 2);
    player.pause(); assert.equal(messages.at(-1).type === 'pause' || player.playing === false, true);
    assert.equal(player.playing, false);
  } finally {
    for (const [key, descriptor] of Object.entries(previous)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else Reflect.deleteProperty(globalThis, key);
    }
  }
});
