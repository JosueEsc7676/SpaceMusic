// Pruebas reales de las funciones del service worker sin servidor ni credenciales.
// node --test pruebas/audio-cache.test.mjs
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const codigo = readFileSync(new URL('../src/main/resources/sw/sw.js', import.meta.url), 'utf8')
  .replaceAll('__VERSION__', 'sm-test').replaceAll('__SHELL__', '');
const contexto = vm.createContext({ Response, Request, Headers, ReadableStream, AbortController, URL,
  setTimeout, clearTimeout, Date, console, self: { addEventListener() {}, navigator: { onLine: true } },
  caches: { async open() { return { async match() { return contexto.copia; } }; } } });
vm.runInContext(codigo, contexto);
const rango = (r, cabecera, total) => contexto.rangoGuardado(r, cabecera, total);
const respuesta = () => new Response(new Uint8Array([0, 1, 2, 3, 4, 5]), { headers: { 'Content-Type': 'audio/mp4', 'Content-Length': '6' } });

test('los primeros bytes se entregan sin leer ni esperar al resto del audio', async () => {
  let lecturas = 0, cancelado = false;
  const r = new Response(new ReadableStream({
    pull(c) {
      lecturas++;
      if (lecturas === 1) c.enqueue(new Uint8Array([1, 2, 3, 4]));
      else return new Promise(() => {}); // el resto nunca llega: el inicio debe poder reproducirse
    },
    cancel() { cancelado = true; },
  }, { highWaterMark: 0 }), { headers: { 'Content-Length': '10000000' } });
  const resultado = rango(r, 'bytes=0-1', 10000000);
  assert.equal(resultado.status, 206);
  assert.equal(resultado.headers.get('Content-Range'), 'bytes 0-1/10000000');
  assert.deepEqual([...new Uint8Array(await resultado.arrayBuffer())], [1, 2]);
  assert.equal(lecturas, 1);
  assert.equal(cancelado, true);
});

test('rangos abiertos, cerrados y sufijos entregan exactamente los bytes pedidos', async () => {
  for (const [peticion, esperado] of [['bytes=2-4', [2, 3, 4]], ['bytes=3-', [3, 4, 5]],
    ['bytes=-2', [4, 5]], ['bytes=0-100', [0, 1, 2, 3, 4, 5]]]) {
    const r = rango(respuesta(), peticion, 6);
    assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], esperado);
    assert.equal(Number(r.headers.get('Content-Length')), esperado.length);
  }
});

test('rangos imposibles dan 416; rangos múltiples pasan al servidor', () => {
  for (const h of ['bytes=6-', 'bytes=4-2', 'bytes=-0']) assert.equal(rango(respuesta(), h, 6).status, 416);
  assert.equal(rango(respuesta(), 'bytes=0-1,3-4', 6), null);
});

test('una copia incompleta falla explícitamente', async () => {
  const r = rango(respuesta(), 'bytes=0-9', 10);
  await assert.rejects(r.arrayBuffer(), /incompleta/);
});

test('las copias antiguas sin Content-Length siguen funcionando sin conexión', async () => {
  contexto.copia = new Response(new Uint8Array([1, 2, 3]));
  const r = await contexto.audioGuardado(new Request('https://ejemplo.test/api/canciones/1/audio', { headers: { Range: 'bytes=1-' } }), '/offline/audio/1');
  assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [2, 3]);
});

test('la precarga respeta bytes=0-1 y devuelve dos bytes, no 640 KB', async () => {
  contexto.copia = new Response(new Uint8Array([0, 1, 2, 3]), { headers: {
    'X-SM-Ligera': 'false', 'X-SM-Fecha': String(Date.now()), 'X-SM-Total': '1000', 'X-SM-Validador': '"v1"',
  } });
  const r = await contexto.inicioGuardado(new Request('https://ejemplo.test/api/canciones/1/audio', { headers: { Range: 'bytes=0-1' } }), '/offline/audio/1');
  assert.equal(r.headers.get('Content-Range'), 'bytes 0-1/1000');
  assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [0, 1]);
});

test('la precarga de otra calidad o con If-Range distinto no se mezcla', async () => {
  for (const headers of [{ Range: 'bytes=0-', 'If-Range': '"v2"' }, { Range: 'bytes=2-' }]) {
    assert.equal(await contexto.inicioGuardado(new Request('https://ejemplo.test/api/canciones/1/audio', { headers }), '/offline/audio/1'), null);
  }
  assert.equal(await contexto.inicioGuardado(new Request('https://ejemplo.test/api/canciones/1/audio?calidad=ligera'), '/offline/audio/1'), null);
});


test('una caché ocupada no bloquea el streaming en línea', async () => {
  const anterior = contexto.caches;
  let red = 0;
  contexto.caches = { async open() { return { match() { return new Promise(() => {}); } }; } };
  contexto.fetch = async () => { red++; return new Response('red'); };
  try {
    const inicio = Date.now();
    const r = await contexto.audioConPrioridad(new Request('https://ejemplo.test/api/canciones/1/audio'), '/offline/audio/1');
    assert.equal(await r.text(), 'red');
    assert.equal(red, 1);
    assert.ok(Date.now() - inicio < 2000, 'no debe esperar indefinidamente al almacenamiento');
  } finally { contexto.caches = anterior; }
});

test('la copia local rápida evita la red y funciona sin conexión', async () => {
  contexto.copia = new Response('copia');
  contexto.fetch = async () => { throw new Error('no debe usar red'); };
  for (const online of [true, false]) {
    contexto.self.navigator.onLine = online;
    contexto.copia = new Response('copia');
    const r = await contexto.audioConPrioridad(new Request('https://ejemplo.test/api/canciones/1/audio'), '/offline/audio/1');
    assert.equal(await r.text(), 'copia');
  }
  contexto.self.navigator.onLine = true;
});
