// node --test pruebas/letras-sincronia.test.mjs
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const codigo = readFileSync(new URL('../src/main/resources/static/js/lrc.js', import.meta.url), 'utf8');
const lector = vm.createContext({});
vm.runInContext(codigo.replace('export function', 'function'), lector);
const parse = lector.parseLrc;

test('offset compartido desplaza líneas y palabras sin modificar su texto', () => {
  for (const offset of [-1500, 500, 0]) {
    const reales = parse(`[offset:${offset}]\n[00:10.00]<00:10.00>uno <00:11.00>dos`).filter(x => !x.interlude);
    assert.equal(reales[0].t, 10 - offset / 1000);
    assert.equal(reales[0].words[1].t, 11 - offset / 1000);
    assert.equal(reales[0].text, 'uno dos');
  }
});

test('panel web permanece abierto al ajustar, guardar y recargar', async () => {
  const fuente = readFileSync(new URL('../src/main/resources/static/js/nowplaying.js', import.meta.url), 'utf8');
  const inicio = fuente.indexOf('async function editarSincroniaCompartida(');
  const fin = fuente.indexOf('// ═══════════════════ Cola', inicio);
  const nodos = new Map();
  const nodo = s => { if (!nodos.has(s)) nodos.set(s, { textContent: '', dataset: {} }); return nodos.get(s); };
  const pasos = [-0.5, -0.1, 0.1, 0.5].map(n => ({ dataset: { delta: String(n) } }));
  const el = { querySelector: nodo, querySelectorAll: () => pasos };
  const vista = { isOpen: true, updateLyrics() {}, async renderTab() {} };
  const llamadas = [];
  const base = { letraSincronizada: '[00:10]uno', sincroniaGlobalSegundos: 0, revisionSincronia: 'original' };
  let personalesBorrados = 0;
  const ctx = vm.createContext({
    getLyrics: async () => base, parseLrc: parse, esc: x => x,
    player: { current: { id: 7 }, audio: { currentTime: 10 }, seek() {}, toggle() {} },
    vistaSincronia: null, lyricsCache: new Map(), setInterval: () => 1, clearInterval() {},
    setOffset: () => personalesBorrados++,
    api: async (url, opciones) => {
      llamadas.push({ url, opciones });
      return { ...base, letraSincronizada: '[offset:-600]\n[00:10]uno', sincroniaGlobalSegundos: 0.6, revisionSincronia: 'nueva' };
    },
    modal: async opciones => {
      opciones.onOpen(el);
      pasos[3].onclick(); pasos[2].onclick();
      assert.equal(ctx.vistaSincronia.delta, -0.6);
      assert.equal(await opciones.onAction('guardar', el), false);
      assert.equal(llamadas[0].opciones.body.segundos, 0.6);
      assert.equal(llamadas[0].opciones.body.revision, 'original');
      assert.equal(personalesBorrados, 1);
      assert.equal(ctx.vistaSincronia.delta, 0);
      assert.equal(await opciones.onAction('recargar', el), false);
      assert.match(llamadas[1].url, /sincronia=/);
      assert.equal(personalesBorrados, 1);
    },
  });
  vm.runInContext(fuente.slice(inicio, fin), ctx);
  await ctx.editarSincroniaCompartida(vista, { id: 7, nombre: 'Canción' });
  assert.equal(ctx.vistaSincronia, null);
  assert.equal(vista._editandoSincronia, undefined);
});

test('dedicatorias buscan el fragmento usando el mismo offset global', () => {
  const fuente = readFileSync(new URL('../src/main/resources/static/js/dedicatorias.js', import.meta.url), 'utf8');
  const inicio = fuente.indexOf('const limpiarLinea');
  const fin = fuente.indexOf('\n}', fuente.indexOf('export function bloquesDeLetra', inicio)) + 2;
  const ctx = vm.createContext({});
  vm.runInContext(fuente.slice(inicio, fin).replace('export function', 'function'), ctx);
  const b = ctx.bloquesDeLetra('', '[offset:-1500]\n[00:10]uno\n[00:12]dos');
  assert.equal(b[0][0].t, 11.5);
  assert.equal(b[0][0].fin, 13.5);
});
