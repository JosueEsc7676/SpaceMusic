import { test } from 'node:test';
import assert from 'node:assert/strict';
import { appendOnly, audioURL, normalizeServer, parseMessage, sameOrigin, scopedKey } from '../src/protocol';
import { bootstrap } from '../src/bootstrap';
const server = 'https://music.example';
const song = { id: 1, nombre: 'Canción', artista: 'Artista', _uid: 1 };
const state = { items: [song], index: 0, time: 0, playing: false, repeat: 'off', volume: 0.8, sleepUntil: 0, sleepEnd: false, autoRadio: true };
const raw = (type: string, fields: object = {}) => JSON.stringify({ version: 1, type, ...fields });

test('solo admite servidor HTTPS sin credenciales ni rutas', () => {
  assert.equal(normalizeServer(' https://music.example/ '), server);
  for (const url of ['http://music.example', 'https://u:p@music.example', server + '/api', server + '?t=secret']) assert.throws(() => normalizeServer(url));
});
test('el puente rechaza otros orígenes y mensajes malformados', () => {
  assert.equal(sameOrigin('https://music.example.evil/x', server), false);
  assert.equal(parseMessage('{', server), null);
  assert.equal(parseMessage(raw('download', { url: 'https://evil.test/file', song }), server), null);
  assert.equal(parseMessage(raw('download', { url: server + '/api/auth/me', song }), server), null);
  assert.equal(parseMessage(raw('seek', { time: -1 }), server), null);
  assert.equal(parseMessage(raw('queue', { state: { ...state, index: 8 } }), server), null);
  assert.equal(parseMessage(raw('queue', { state, intent: 'arbitrary' }), server), null);
});
test('preserva canciones repetidas y distingue nuevas inserciones de una cola añadida', () => {
  const repeated = { ...song, _uid: 2 };
  assert.equal(appendOnly([song], [song, repeated]), true);
  assert.equal(appendOnly([song], [repeated, song]), false);
  assert.equal(parseMessage(raw('queue', { state, intent: 'load' }), server)?.type, 'queue');
  assert.equal(audioURL(server, 'a&b', song), server + '/api/canciones/1/audio?t=a%26b');
});
test('las carpetas locales son distintas por servidor y cuenta y no contienen escapes de URL', () => {
  assert.notEqual(scopedKey(server, 1), scopedKey(server, 2));
  assert.notEqual(scopedKey(server, 1), scopedKey(server + ':8443', 1));
  assert.match(scopedKey(server, 1), /^servers\/[a-f0-9/]+\/users\/1$/);
  assert.equal(scopedKey(server, 1).includes('%'), false);
});
test('el arranque no entrega la sesión a una página externa ni sobrescribe una sesión nueva', () => {
  const saved = { token: 'old-fixture', user: { id: 1 } };
  const values = new Map<string, string>();
  const local = { getItem: (k: string) => values.get(k), setItem: (k: string, v: string) => values.set(k, v) };
  const window: Record<string, unknown> = {};
  const run = new Function('window', 'location', 'localStorage', 'navigator', 'document', bootstrap(saved, server));
  run(window, { origin: 'https://evil.example', pathname: '/' }, local, {}, {});
  assert.equal(values.size, 0); assert.equal(window.SpaceMusicApp, undefined);
  values.set('sm_token', 'new-fixture');
  run(window, { origin: server, pathname: '/' }, local, {}, { documentElement: { style: {} } });
  assert.equal(values.get('sm_token'), 'new-fixture');
  values.clear();
  run({}, { origin: server, pathname: '/login.html' }, local, {}, { documentElement: { style: {} } });
  assert.equal(values.size, 0);
});
