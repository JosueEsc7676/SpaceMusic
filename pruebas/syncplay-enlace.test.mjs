// Enlaces de invitación a SyncPlay: solo abren salas del servidor de la cuenta.
//   node --test pruebas/syncplay-enlace.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { enlaceSala, salaDeInvitacion } from '../src/main/resources/static/js/syncplay-enlace.js';

const SERVIDOR = 'https://music.example';

test('la invitación del mensaje abre la sala correcta', () => {
  const enlace = enlaceSala(42, SERVIDOR);
  assert.equal(enlace, 'https://music.example/#/juntos/42');
  assert.equal(salaDeInvitacion(`Te invito a escuchar conmigo en SyncPlay 🎧\n${enlace}`, SERVIDOR), 42);
  assert.equal(enlaceSala(0, SERVIDOR), null);
  assert.equal(enlaceSala(-3, SERVIDOR), null);
  assert.equal(enlaceSala('x', SERVIDOR), null);
});

test('no abre otro servidor, otra cuenta, otro puerto ni rutas parecidas', () => {
  for (const texto of ['https://music.example.evil/#/juntos/2', 'https://evil.example/#/juntos/2', 'https://u:p@music.example/#/juntos/2',
    'https://music.example/#/juntos/2/otra', 'https://music.example/#/juntos/-2', 'https://music.example:8888/#/juntos/2',
    'http://music.example/#/juntos/2', 'https://music.example/otra/#/juntos/2', 'https://music.example/?x=1#/juntos/2',
    'https://music.example/#/juntos/0', 'https://music.example/#/juntos/2x']) {
    assert.equal(salaDeInvitacion(texto, SERVIDOR), null, texto);
  }
});

test('los mensajes normales no se convierten en invitaciones', () => {
  assert.equal(salaDeInvitacion('¿Qué estás escuchando?', SERVIDOR), null);
  assert.equal(salaDeInvitacion('', SERVIDOR), null);
  assert.equal(salaDeInvitacion(null, SERVIDOR), null);
  assert.equal(salaDeInvitacion('Ven: https://music.example/#/juntos/18.', SERVIDOR), 18);
  assert.equal(salaDeInvitacion('Mira https://evil.example/#/juntos/1 y https://music.example/#/juntos/7', SERVIDOR), 7);
});

test('con puerto local (desarrollo) también exige el mismo puerto', () => {
  assert.equal(salaDeInvitacion('http://localhost:8090/#/juntos/5', 'http://localhost:8090'), 5);
  assert.equal(salaDeInvitacion('http://localhost:8091/#/juntos/5', 'http://localhost:8090'), null);
});
