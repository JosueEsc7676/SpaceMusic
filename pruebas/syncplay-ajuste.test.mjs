// Corrección de la sincronía de SyncPlay con una conexión lenta (cada salto tarda en volver a sonar).
//   node --test pruebas/syncplay-ajuste.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { crearAjuste, decidir } from '../src/main/resources/static/js/syncplay-ajuste.js';

/**
 * Simula 60 s de un reproductor que sigue al SyncPlay revisando cada 0,5 s.
 * Cada salto deja el audio en silencio `demora` segundos (lo que tarda en bajar esa parte).
 */
function simular({ demora, inicio = -3, algoritmo }) {
  let local = 0, objetivo = 0, silencioHasta = -1, velocidad = 1, saltos = 0, cortado = 0;
  local = objetivo + inicio;
  for (let t = 0; t < 60; t += 0.1) {
    objetivo += 0.1;
    if (t < silencioHasta) { cortado += 0.1; continue; }
    local += 0.1 * velocidad;
    if (Math.round(t * 10) % 5 !== 0) continue; // revisa cada 0,5 s
    const r = algoritmo(local - objetivo, t * 1000);
    velocidad = r.velocidad;
    if (r.saltar != null) { saltos++; local = objetivo + r.saltar; silencioHasta = t + demora; }
  }
  return { saltos, cortado, desfase: Math.abs(local - objetivo) };
}

/** Lo que hacía la app antes: salto a objetivo + 0,05 en cuanto el desfase pasaba de 0,6 s. */
const anterior = (d) => (Math.abs(d) > 0.6 ? { velocidad: 1, saltar: 0.05 } : { velocidad: Math.min(1.05, Math.max(0.95, 1 - d * 0.5)) });

test('antes: con una conexión lenta saltaba en bucle (el audio se cortaba a cada rato)', () => {
  const r = simular({ demora: 1.5, algoritmo: anterior });
  assert.ok(r.saltos > 15, `saltos: ${r.saltos}`);
});

for (const demora of [0.2, 0.8, 1.5, 3]) {
  test(`ahora: con ${demora} s por salto, pocos saltos y queda sincronizado`, () => {
    const st = crearAjuste();
    const r = simular({ demora, algoritmo: (d, ahora) => decidir(st, d, ahora) });
    assert.ok(r.saltos <= 3, `saltos: ${r.saltos}`);
    assert.ok(r.desfase < 0.1, `desfase final: ${r.desfase.toFixed(3)} s`);
  });
}

test('un desfase pequeño se corrige con la velocidad, sin saltos', () => {
  const st = crearAjuste();
  const r = simular({ demora: 1, inicio: -0.5, algoritmo: (d, ahora) => decidir(st, d, ahora) });
  assert.equal(r.saltos, 0);
  assert.ok(r.desfase < 0.05, `desfase final: ${r.desfase.toFixed(3)} s`);
});

test('dentro del margen no toca la velocidad (no titubea)', () => {
  const st = crearAjuste();
  assert.deepEqual(decidir(st, 0.04, 0), { velocidad: 1 });
  assert.equal(decidir(st, -0.3, 500).velocidad > 1, true);
});

test('sin búfer suficiente no acelera (solo frena)', () => {
  const st = crearAjuste();
  assert.equal(decidir(st, -0.3, 0, { puedeAcelerar: false }).velocidad, 1);
  assert.ok(decidir(st, 0.3, 500, { puedeAcelerar: false }).velocidad < 1);
});

test('un estado nuevo del servidor salta aunque esté esperando tras otro salto', () => {
  const st = crearAjuste();
  assert.ok(decidir(st, -2, 0).saltar != null);
  assert.equal(decidir(st, -2, 1000).saltar, undefined);
  assert.ok(decidir(st, 30, 1200, { forzar: true }).saltar != null);
});
