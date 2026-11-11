import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { AudioEngine } from '../src/audio';
import { lastPlaylist } from './native-port';
import type { Storage } from '../src/storage';
import type { Snapshot, Song } from '../src/protocol';

const song = (id: number): Song => ({ id, nombre: `Canción ${id}`, artista: 'Prueba', duracionSegundos: 180, _uid: id });
const snapshot = (items = [song(1), song(2)]): Snapshot => ({ items, index: 0, time: 12, playing: true, repeat: 'off', volume: 0.8, sleepUntil: 0, sleepEnd: false, autoRadio: false });
function storage(): Storage { return { local: () => undefined, saveQueue: async () => {} } as unknown as Storage; }

test('añadir y reordenar no reinicia la pista; saltar y pausar sí se aplican', async () => {
  const e = new AudioEngine(storage()); await e.init();
  e.configure('https://music.example', { token: 'fixture', user: { id: 1 } });
  await e.apply(snapshot(), 'load');
  assert.equal(lastPlaylist.currentTime, 12); assert.equal(lastPlaylist.playing, true);
  await e.apply(snapshot([song(1), song(2), song(3)]));
  assert.equal(lastPlaylist.currentTime, 12); assert.equal(lastPlaylist.sources.length, 3);
  lastPlaylist.skipTo(1); lastPlaylist.currentTime = 22;
  await e.apply(snapshot([song(2), song(1), song(3)]));
  assert.equal(lastPlaylist.currentIndex, 0); assert.equal(lastPlaylist.currentTime, 22);
  await e.apply({ ...snapshot([song(2), song(1), song(3)]), index: 2, time: 0 }, 'load');
  assert.equal(lastPlaylist.currentIndex, 2); assert.equal(lastPlaylist.currentTime, 0);
  e.pause(); assert.equal(lastPlaylist.playing, false);
  e.stop(); assert.equal(lastPlaylist.sources.length, 0);
});
test('el temporizador nativo detiene el audio aunque la interfaz no reciba eventos', async () => {
  mock.method(globalThis, 'fetch', async () => new Response('{}', { status: 200 }));
  const e = new AudioEngine(storage()); await e.init(); e.configure('https://music.example', { token: 'fixture', user: { id: 1 } });
  await e.apply({ ...snapshot(), sleepUntil: Date.now() - 1 }, 'load');
  lastPlaylist.emit(15);
  assert.equal(lastPlaylist.playing, false); assert.equal(e.snapshot()?.sleepUntil, 0);
  mock.restoreAll();
});
test('registra una escucha una sola vez tras el umbral y no usa el tiempo buscado como escucha', async () => {
  const paths: string[] = [];
  mock.method(globalThis, 'fetch', async (url: string | URL | Request) => { paths.push(String(url)); return new Response('{}', { status: 200 }); });
  const e = new AudioEngine(storage()); await e.init(); e.configure('https://music.example', { token: 'fixture', user: { id: 1 } });
  await e.apply({ ...snapshot(), time: 0 }, 'load');
  lastPlaylist.emit(90); assert.equal(paths.some(p => p.endsWith('/api/reproducciones')), false);
  for (let time = 91; time <= 130; time++) lastPlaylist.emit(time);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(paths.filter(p => p.endsWith('/api/reproducciones')).length, 1);
  mock.restoreAll();
});

test('la radio no salta la primera canción mientras prepara las siguientes', async () => {
  mock.method(globalThis, 'fetch', async (url: string | URL | Request) => new Response(JSON.stringify(String(url).includes('/api/radio/') ? [song(3)] : {}), { status: 200 }));
  const e = new AudioEngine(storage()); await e.init(); e.configure('https://music.example', { token: 'fixture', user: { id: 1 } });
  await e.apply({ ...snapshot(), time: 0, autoRadio: true }, 'load');
  lastPlaylist.emit(0);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(lastPlaylist.currentIndex, 0); assert.equal(lastPlaylist.sources.length, 3);
  mock.restoreAll();
});

test('renovar el token mantiene la canción y reemplaza las URLs de la cola', async () => {
  const e = new AudioEngine(storage()); await e.init(); e.configure('https://music.example', { token: 'old-fixture', user: { id: 1 } });
  await e.apply(snapshot(), 'load');
  lastPlaylist.currentTime = 24;
  e.configure('https://music.example', { token: 'new-fixture', user: { id: 1 } });
  await e.apply(e.snapshot()!);
  assert.equal(lastPlaylist.currentTime, 24); assert.equal(lastPlaylist.playing, true);
  assert.equal(lastPlaylist.sources.every(s => s.uri?.endsWith('t=new-fixture')), true);
});

test('una descarga nueva se utiliza en la siguiente canción sin reiniciar la actual', async () => {
  const files = new Map<number, string>();
  const store = { local: (id: number) => files.get(id), saveQueue: async () => {} } as unknown as Storage;
  const e = new AudioEngine(store); await e.init(); e.configure('https://music.example', { token: 'fixture', user: { id: 1 } });
  await e.apply(snapshot(), 'load');
  files.set(2, 'file:///fixture-2.mp3'); e.refreshDownloads();
  assert.equal(lastPlaylist.sources[1].uri, 'file:///fixture-2.mp3');
  assert.equal(lastPlaylist.currentTime, 12); assert.equal(lastPlaylist.playing, true);
  files.clear(); e.refreshDownloads();
  assert.equal(lastPlaylist.sources[1].uri?.startsWith('https://music.example/'), true);
});
