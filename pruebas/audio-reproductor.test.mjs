// Motor real con un medio controlado: errores y final auténtico son eventos distintos.
// node --experimental-vm-modules --test pruebas/audio-reproductor.test.mjs
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';

async function motor() {
  class Medio extends EventTarget {
    dataset = {}; src = ''; paused = true; ended = false; error = null;
    currentTime = 0; duration = 180; cargas = 0;
    play() { this.paused = false; this.dispatchEvent(new Event('playing')); return Promise.resolve(); }
    pause() { this.paused = true; this.dispatchEvent(new Event('pause')); }
    load() { this.cargas++; this.error = null; }
    removeAttribute(k) { if (k === 'src') this.src = ''; }
    setAttribute() {}
  }
  const avisos = [];
  const bus = { on() {}, emit(...v) { avisos.push(v); } };
  const contexto = vm.createContext({ Audio: Medio, Event, setTimeout, clearTimeout,
    setInterval: (...args) => { const t = setInterval(...args); t.unref(); return t; }, clearInterval,
    performance, console, URL, AbortController, fetch, navigator: {},
    window: { addEventListener() {}, removeEventListener() {} },
    document: { addEventListener() {}, hidden: false },
  });
  const api = new vm.SyntheticModule(['api','audioUrl','session','deUsuario'], function() {
    this.setExport('api', async () => []); this.setExport('audioUrl', id => `/audio/${id}`);
    this.setExport('session', {user: {id: 1}}); this.setExport('deUsuario', k => k);
  }, {context: contexto});
  const util = new vm.SyntheticModule(['bus','store','shuffleArr','cover','esIOS'], function() {
    this.setExport('bus', bus); this.setExport('store', {get: (_k,d) => d, set() {}, del() {}});
    this.setExport('shuffleArr', a => [...a]); this.setExport('cover', s => s); this.setExport('esIOS', false);
  }, {context: contexto});
  const m = new vm.SourceTextModule(readFileSync(new URL('../src/main/resources/static/js/player.js', import.meta.url), 'utf8'), {context: contexto});
  await m.link(spec => spec === './api.js' ? api : util);
  await m.evaluate();
  m.namespace.prefs.autoRadio = false;
  const p = m.namespace.player;
  p.play([{id: 1, nombre: 'Una'}, {id: 2, nombre: 'Dos'}, {id: 3, nombre: 'Tres'}]);
  return {p, avisos};
}

test('un fallo conserva canción, cola y posición sin saltos tardíos', async () => {
  const {p} = await motor();
  p.audio.currentTime = 17;
  p.audio.error = {code: 2};
  p.audio.dispatchEvent(new Event('error'));
  assert.equal(p.audio.paused, true);
  assert.equal(p.index, 0);
  assert.equal(p.audio.currentTime, 17);
  await new Promise(r => setTimeout(r, 1400));
  assert.equal(p.index, 0);
  assert.equal(p.items.length, 3);
});

test('un error antiguo no mueve la nueva elección del usuario', async () => {
  const {p} = await motor();
  p.audio.error = {code: 2}; p.audio.dispatchEvent(new Event('error'));
  p.next();
  await new Promise(r => setTimeout(r, 1400));
  assert.equal(p.current.id, 2);
});

test('el final verdadero sigue avanzando normalmente', async () => {
  const {p} = await motor();
  p.audio.dispatchEvent(new Event('ended'));
  assert.equal(p.current.id, 2);
});

test('reproducir tras un error recarga la misma pista y restaura su posición', async () => {
  const {p} = await motor();
  p.audio.currentTime = 17; p.audio.error = {code: 2};
  p.audio.dispatchEvent(new Event('error'));
  p.resume();
  p.audio.currentTime = 0;
  p.audio.dispatchEvent(new Event('loadedmetadata'));
  assert.equal(p.current.id, 1);
  assert.equal(p.audio.currentTime, 17);
});

test('sin conexión no sustituye la canción elegida por otra descargada', async () => {
  const {p} = await motor();
  p.pause(); p.disponible = c => c.id !== 1;
  p.resume();
  assert.equal(p.current.id, 1);
  assert.equal(p.audio.paused, true);
});

test('los metadatos de un reintento no adelantan la siguiente elección', async () => {
  const {p} = await motor();
  p.audio.currentTime = 17; p.audio.error = {code: 2}; p.audio.dispatchEvent(new Event('error'));
  p.resume(); p.next(); p.audio.currentTime = 0;
  p.audio.dispatchEvent(new Event('loadedmetadata'));
  assert.equal(p.current.id, 2);
  assert.equal(p.audio.currentTime, 0);
});
