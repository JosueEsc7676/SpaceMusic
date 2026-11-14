// SyncPlay entre dos cuentas reales (dos navegadores aislados, celular 390×844):
// invitación desde el chat → tarjeta en el chat del amigo → unirse → misma canción en el mismo segundo.
// También guarda capturas de las pantallas nuevas.
//
//   SM_USUARIO=admin SM_CLAVE=... SM_USUARIO2=... SM_CLAVE2=... node pruebas/syncplay-dos-cuentas.mjs [http://localhost:8090] [carpeta-capturas]
// Las dos cuentas deben seguirse mutuamente (amigos). La primera debe ser administradora (capturas del panel).
import { spawn, execSync } from 'node:child_process';
import { mkdtempSync, existsSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BASE = (process.argv[2] || 'http://localhost:8090').replace(/\/$/, '');
const CAPTURAS = process.argv[3] || null;
const { SM_USUARIO, SM_CLAVE, SM_USUARIO2, SM_CLAVE2 } = process.env;
if (!SM_USUARIO || !SM_CLAVE || !SM_USUARIO2 || !SM_CLAVE2) { console.error('Faltan SM_USUARIO, SM_CLAVE, SM_USUARIO2 y SM_CLAVE2'); process.exit(1); }
const NAVEGADOR = process.env.CHROME || ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium'].find((p) => existsSync(p));
const PUERTO = 9400 + Math.floor(Math.random() * 400);
const UA = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36';
const espera = (ms) => new Promise((r) => setTimeout(r, ms));
if (CAPTURAS) mkdirSync(CAPTURAS, { recursive: true });

async function entrar(usuario, password) {
  const r = await (await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ usuario, password }) })).json();
  if (!r.token) throw new Error('No se pudo iniciar sesión como ' + usuario);
  return r;
}
const [A, B] = [await entrar(SM_USUARIO, SM_CLAVE), await entrar(SM_USUARIO2, SM_CLAVE2)];

const perfil = mkdtempSync(join(tmpdir(), 'sm-syncplay-'));
const proc = spawn(NAVEGADOR, ['--headless=new', `--remote-debugging-port=${PUERTO}`, `--user-data-dir=${perfil}`,
  '--autoplay-policy=no-user-gesture-required', '--no-first-run', 'about:blank'], { stdio: 'ignore' });
let info;
for (let i = 0; i < 60 && !info; i++) { try { info = await (await fetch(`http://127.0.0.1:${PUERTO}/json/version`)).json(); } catch { await espera(250); } }
const ws = new WebSocket(info.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
let seq = 0;
const pend = new Map();
ws.addEventListener('message', (m) => { const d = JSON.parse(m.data); if (d.id && pend.has(d.id)) { pend.get(d.id)(d); pend.delete(d.id); } });
const cmd = (method, params = {}, sessionId) => new Promise((res, rej) => {
  const id = ++seq;
  pend.set(id, (d) => (d.error ? rej(new Error(method + ': ' + d.error.message)) : res(d.result)));
  ws.send(JSON.stringify({ id, method, params, sessionId }));
});

/** Un navegador aislado (contexto propio: su almacenamiento y su sesión) con un celular de 390×844. */
async function navegador(login) {
  const { browserContextId } = await cmd('Target.createBrowserContext', { disposeOnDetach: true });
  const { targetId } = await cmd('Target.createTarget', { url: 'about:blank', browserContextId });
  const { sessionId } = await cmd('Target.attachToTarget', { targetId, flatten: true });
  const p = (m, x) => cmd(m, x, sessionId);
  await p('Page.enable'); await p('Runtime.enable');
  await p('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await p('Emulation.setUserAgentOverride', { userAgent: UA, platform: 'Android' });
  await p('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  await p('Page.addScriptToEvaluateOnNewDocument', { source: `try { localStorage.setItem('sm_token', ${JSON.stringify(login.token)});
    localStorage.setItem('sm_user', ${JSON.stringify(JSON.stringify(login.usuario))}); localStorage.setItem('sm_novedades', '99');
    sessionStorage.setItem('sm_splash', '1'); localStorage.setItem('sm_gustos_preguntado:${login.usuario.id}', '1'); } catch (e) {}` });
  const js = async (codigo, timeout = 30000) => {
    const r = await Promise.race([p('Runtime.evaluate', { expression: `(async () => { ${codigo} })()`, awaitPromise: true, returnByValue: true }),
      espera(timeout).then(() => ({ exceptionDetails: { text: 'tiempo agotado' } }))]);
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  };
  const ir = async (ruta, ms = 2500) => { await p('Page.navigate', { url: BASE + ruta }); await espera(ms); };
  const captura = async (nombre) => {
    if (!CAPTURAS) return;
    const { data } = await p('Page.captureScreenshot', { format: 'png' });
    writeFileSync(join(CAPTURAS, nombre + '.png'), Buffer.from(data, 'base64'));
  };
  /** Espera a que la condición (código que devuelve un valor) sea verdadera. */
  const hasta = async (codigo, ms = 15000) => {
    const t0 = Date.now();
    for (;;) { const v = await js(codigo).catch(() => null); if (v) return v; if (Date.now() - t0 > ms) throw new Error('No se cumplió: ' + codigo.slice(0, 90)); await espera(300); }
  };
  return { js, ir, captura, hasta };
}

const resultados = [];
async function prueba(nombre, fn) {
  try { const d = await fn(); resultados.push(true); console.log(`✓ ${nombre}${d ? ' — ' + d : ''}`); }
  catch (e) { resultados.push(false); console.log(`✗ ${nombre} — ${e.message.split('\n')[0]}`); }
}
const afirmar = (c, m) => { if (!c) throw new Error(m); };

const a = await navegador(A), b = await navegador(B);
for (const n of [a, b]) { await n.ir('/#/'); await n.js(`await Promise.race([navigator.serviceWorker?.ready, new Promise((r) => setTimeout(r, 6000))]); return 1;`); }
// Salas anteriores de estas cuentas (de otra ejecución) se cierran
for (const [n, l] of [[a, A], [b, B]]) await n.js(`const t = ${JSON.stringify(l.token)}; const s = await (await fetch('/api/juntos', { headers: { Authorization: 'Bearer ' + t } })).json();
  for (const x of s) await fetch('/api/juntos/' + x.id + '/salir', { method: 'POST', headers: { Authorization: 'Bearer ' + t } }); return s.length;`);

let sala = null;
await prueba('Mensajes muestra los accesos SyncPlay, Coincidimos y Dedicatorias', async () => {
  await a.ir('/#/mensajes');
  const t = await a.hasta(`const n = document.querySelectorAll('.dm-accesos .dm-acceso'); return n.length === 3 && [...n].map((x) => x.textContent.trim()).join('|');`);
  await a.captura('01-mensajes-accesos');
  return t;
});

await prueba('Desde el chat, «Iniciar SyncPlay» envía un mensaje real con el enlace de la sala', async () => {
  await a.ir('/#/mensajes/' + encodeURIComponent(B.usuario.username), 3500);
  await a.hasta(`return document.querySelector('[data-syncplay]')`);
  const antes = await a.js(`return document.querySelectorAll('.bubble.mine a.msg-sync').length;`);
  await a.js(`document.querySelector('[data-syncplay]').click(); return 1;`);
  await espera(400);
  await a.captura('02-chat-menu-syncplay');
  await a.js(`[...document.querySelectorAll('.menu .menu-item')].find((x) => /Iniciar SyncPlay/.test(x.textContent)).click(); return 1;`);
  const href = await a.hasta(`const l = document.querySelectorAll('.bubble.mine a.msg-sync'); return l.length > ${antes} && l[l.length - 1].getAttribute('href');`);
  sala = Number(/#\/juntos\/(\d+)/.exec(href)[1]);
  await a.captura('03-chat-invitacion-enviada');
  // El mensaje existe de verdad en el servidor, con el enlace al mismo servidor
  const ultimo = await a.js(`const r = await (await fetch('/api/mensajes/${encodeURIComponent(B.usuario.username)}', { headers: { Authorization: 'Bearer ' + ${JSON.stringify(A.token)} } })).json();
    return r.mensajes.at(-1).texto;`);
  afirmar(ultimo.includes(`${BASE}/#/juntos/${sala}`) || ultimo.includes(`/#/juntos/${sala}`), 'El mensaje no lleva el enlace: ' + ultimo);
  return `sala ${sala}`;
});

await prueba('El amigo ve la invitación como tarjeta, la abre y se une', async () => {
  await b.ir('/#/mensajes/' + encodeURIComponent(A.usuario.username), 3500);
  const href = await b.hasta(`const c = [...document.querySelectorAll('.bubble:not(.mine) a.msg-sync')].at(-1); return c && c.getAttribute('href');`);
  afirmar(href === '#/juntos/' + sala, 'La tarjeta abre otra sala: ' + href);
  await b.captura('04-chat-invitacion-recibida');
  await b.js(`[...document.querySelectorAll('.bubble:not(.mine) a.msg-sync')].at(-1).click(); return 1;`);
  await b.hasta(`return document.querySelector('[data-unirse]')`);
  await b.captura('05-sala-invitacion');
  await b.js(`document.querySelector('[data-unirse]').click(); return 1;`);
  await b.hasta(`return document.querySelector('.juntos-reacciones')`);
  await b.captura('06-sala-dentro');
  return 'unido';
});

await prueba('SyncPlay: el amigo escucha la misma canción en el mismo segundo', async () => {
  // El anfitrión pone una canción y adelanta a los 20 s
  await a.ir('/#/buscar?q=' + encodeURIComponent(process.env.SM_BUSCAR || 'a'), 3500);
  await a.js(`document.querySelector('.track[data-act="play-list"]').click(); return 1;`);
  await a.hasta(`const P = await import(location.origin + '/js/player.js'); return P.player.playing;`);
  await a.js(`const P = await import(location.origin + '/js/player.js'); P.player.seek(Math.min(20, P.player.duration() - 5)); return 1;`);
  const r = await b.hasta(`const P = await import(location.origin + '/js/player.js'); return P.player.playing && P.player.current && { id: P.player.current.id, t: P.player.audio.currentTime };`, 20000);
  await espera(2500);
  const [ta, tb] = [await a.js(`const P = await import(location.origin + '/js/player.js'); return { id: P.player.current.id, t: P.player.audio.currentTime, now: Date.now() };`),
    await b.js(`const P = await import(location.origin + '/js/player.js'); return { id: P.player.current.id, t: P.player.audio.currentTime, now: Date.now() };`)];
  afirmar(ta.id === tb.id, `Suenan canciones distintas (${ta.id} y ${tb.id})`);
  const dif = Math.abs((ta.t + (tb.now - ta.now) / 1000) - tb.t);
  afirmar(dif < 2, `Desfase de ${dif.toFixed(2)} s`);
  // Pausa del anfitrión → pausa del invitado
  await a.js(`const P = await import(location.origin + '/js/player.js'); P.player.pause(); return 1;`);
  await b.hasta(`const P = await import(location.origin + '/js/player.js'); return !P.player.playing;`, 10000);
  return `misma canción (${ta.id}), desfase ${dif.toFixed(2)} s; la pausa también se sincronizó`;
});

await prueba('Dispositivos (antes del corazón) muestra la salida y SyncPlay activo', async () => {
  const orden = await a.js(`const m = document.querySelector('#playerBar .np-mini'); const bs = [...m.querySelectorAll('button')];
    return bs.findIndex((x) => x.matches('[data-dispositivos]')) < bs.findIndex((x) => x.matches('[data-pfav]'));`);
  afirmar(orden, 'El botón Dispositivos no está antes del corazón');
  await a.js(`document.querySelector('[data-dispositivos]').click(); return 1;`);
  const t = await a.hasta(`const m = document.querySelector('.modal .disp'); return m && !/Cargando|Buscando/.test(m.textContent) && m.textContent;`);
  afirmar(/SyncPlay/.test(t) && /Tu sala está activa/.test(t), 'No muestra la sala activa: ' + t.slice(0, 120));
  await a.captura('07-dispositivos');
  await a.js(`document.querySelector('.modal [data-close]').click(); return 1;`);
  return t.replace(/\s+/g, ' ').slice(0, 90);
});

await prueba('Menú de canción agrupado: Me gusta y playlist a mano; Cola y radio, Compartir y Más acciones', async () => {
  await a.js(`document.querySelector('.track [data-act="song-menu"]').click(); return 1;`);
  await espera(300);
  const t = await a.js(`return [...document.querySelectorAll('.menu > .menu-item')].map((x) => x.textContent.trim());`);
  afirmar(/Me gusta/.test(t[0]) && /playlist/.test(t[1]), 'Me gusta y Agregar a playlist no van primero: ' + t);
  for (const g of ['Cola y radio', 'Compartir', 'Más acciones']) afirmar(t.includes(g), 'Falta el grupo ' + g);
  await a.js(`[...document.querySelectorAll('.menu-grupo')].find((x) => x.textContent.includes('Compartir')).click(); return 1;`);
  await espera(250);
  const sub = await a.js(`return [...document.querySelectorAll('.menu-sub:not([hidden]) .menu-item')].map((x) => x.textContent.trim());`);
  afirmar(sub.includes('Enviar a un amigo') && sub.includes('Dedicar esta canción'), 'Compartir incompleto: ' + sub);
  await a.captura('08-menu-cancion');
  await a.js(`document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); return 1;`);
  return t.join(' · ');
});

await prueba('Letra a pantalla completa: cabecera compacta, controles abajo y líneas sin cambiar de tamaño', async () => {
  // Una canción con letra sincronizada (si la hay)
  await a.js(`const t = ${JSON.stringify(A.token)}; const l = await (await fetch('/api/buscar?q=' + encodeURIComponent(${JSON.stringify(process.env.SM_LETRA || 'Verano eterno')}), { headers: { Authorization: 'Bearer ' + t } })).json();
    const c = (l.canciones || [])[0]; if (c) { const P = await import(location.origin + '/js/player.js'); P.player.play([c], 0); } return 1;`);
  await espera(1500);
  await a.js(`const N = await import(location.origin + '/js/nowplaying.js'); N.np.open(); N.np.setTab('letra'); if (!N.np.lyrFocus) N.np.toggleFocus(); return 1;`);
  await espera(2500);
  // Mientras avanza la letra, la línea actual queda siempre a la misma altura y del mismo tamaño
  const pos = [];
  for (let i = 0; i < 6; i++) {
    pos.push(await a.js(`const x = document.querySelector('.np .lyrics .line.active'); if (!x) return null; const r = x.getBoundingClientRect(); return { top: Math.round(r.top), h: Math.round(r.height), t: x.textContent.slice(0, 20) };`));
    await espera(900);
  }
  // Cuenta la posición final de cada línea (la última muestra; una toma a mitad del desplazamiento no cuenta)
  const finales = Object.values(Object.fromEntries(pos.filter(Boolean).map((p) => [p.t, p])));
  if (finales.length > 1) {
    const tops = finales.map((p) => p.top), altos = new Set(finales.map((p) => p.h));
    afirmar(Math.max(...tops) - Math.min(...tops) <= 4, 'La línea actual no queda siempre a la misma altura: ' + JSON.stringify(pos));
    afirmar(altos.size === 1 || finales.every((p) => p.h % 41 === 0 || p.h > 41), 'Tamaños distintos');
  }
  const r = await a.js(`const el = document.querySelector('.np'); const h = el.querySelector('.np-head').getBoundingClientRect(), c = el.querySelector('.np-controls').getBoundingClientRect();
    const l = [...el.querySelectorAll('.lyrics .line')].map((x) => getComputedStyle(x).transform);
    return { foco: el.classList.contains('lyr-focus'), cabArriba: h.top < 120 && h.height < 90, ctrlAbajo: c.bottom > innerHeight - 140,
      salir: getComputedStyle(el.querySelector('[data-salir-focus]')).display !== 'none', sinEscala: l.every((t) => t === 'none'), lineas: l.length };`);
  await a.captura('09-letra-ampliada');
  afirmar(r.foco && r.cabArriba && r.ctrlAbajo && r.salir, JSON.stringify(r));
  afirmar(!r.lineas || r.sinEscala, 'Las líneas cambian de tamaño (transform)');
  await a.js(`const N = await import(location.origin + '/js/nowplaying.js'); N.np.toggleFocus(); N.np.close(); return 1;`);
  return `${r.lineas} líneas, sin escala`;
});

await prueba('Administración: registro de descargas plegado con desplazamiento propio y claves de Spotify arriba', async () => {
  await a.ir('/#/admin?tab=descargas', 4000);
  // Si este servidor no tiene la herramienta de descargas, se simula su respuesta con un registro largo
  await a.js(`if (document.querySelector('[data-reg]')) return 0;
    const f0 = window.fetch;
    window.fetch = (u, o) => String(u).includes('/api/admin/descargas?') ? Promise.resolve(new Response(JSON.stringify({ instalado: true, activo: false,
      progreso: {}, cola: [], terminadas: [], fallidas: [], spotifyConfigurado: false,
      registro: Array.from({ length: 400 }, (_, i) => (i % 7 ? '▶ Descargando canción ' : '✓ Lista ') + i) }), { headers: { 'Content-Type': 'application/json' } })) : f0(u, o);
    const M = await import(location.origin + '/js/views/descargas-admin.js');
    const el = document.querySelector('[data-body]'); el.innerHTML = ''; await M.descargasTab(el);
    await new Promise((r) => setTimeout(r, 800)); return 1;`);
  const r = await a.js(`const reg = document.querySelector('[data-reg]'), sp = document.querySelector('#dl-spotify'); const plegado = reg && !reg.open;
    if (!reg) return { sinDescargas: true, texto: document.querySelector('.dl')?.textContent.slice(0, 80) };
    const antes = sp.compareDocumentPosition(reg) & Node.DOCUMENT_POSITION_FOLLOWING;
    reg.open = true; await new Promise((r) => setTimeout(r, 200));
    const pre = reg.querySelector('[data-log]'); const cs = getComputedStyle(pre);
    return { plegadoAlInicio: plegado, spotifyAntes: !!antes, atajo: !!document.querySelector('[data-ir-spot]'), alto: pre.getBoundingClientRect().height, overflow: cs.overflowY, contener: cs.overscrollBehaviorY };`);
  await a.captura('10-admin-descargas');
  if (r.sinDescargas) return 'este servidor no tiene descargas instaladas: ' + r.texto;
  afirmar(r.plegadoAlInicio && r.spotifyAntes && r.atajo && r.overflow === 'auto' && r.alto <= 844 * 0.46, JSON.stringify(r));
  return `alto ${Math.round(r.alto)} px, desplazamiento ${r.overflow}`;
});

await prueba('Usuarios: tocar un usuario abre su ficha al instante sin recargar la lista', async () => {
  await a.ir('/#/admin?tab=usuarios', 3500);
  const r = await a.js(`let pedidasLista = 0; const f0 = window.fetch; window.fetch = (u, o) => { if (/\\/api\\/admin\\/usuarios$/.test(String(u))) pedidasLista++; return f0(u, o); };
    const fila = document.querySelector('tr[data-u] td'); const t0 = performance.now(); fila.click();
    const abierto = !!document.querySelector('.modal'); const ms = performance.now() - t0;
    await new Promise((r) => setTimeout(r, 1500));
    const m = document.querySelector('.backdrop'); const z = +getComputedStyle(m).zIndex;
    const arriba = document.elementFromPoint(innerWidth / 2, innerHeight / 2)?.closest('.backdrop') === m;
    window.fetch = f0;
    return { abierto, ms, detalle: /Reproducciones/.test(m.textContent), pedidasLista, z, arriba };`);
  await a.captura('11-admin-usuario');
  afirmar(r.abierto && r.detalle && r.pedidasLista === 0 && r.arriba, JSON.stringify(r));
  await a.js(`document.querySelector('.modal [data-close]').click(); return 1;`);
  return `ficha abierta en ${r.ms.toFixed(0)} ms, encima de todo, lista sin recargar`;
});

// Fin: se cierra la sala
await a.js(`await fetch('/api/juntos/${sala}/salir', { method: 'POST', headers: { Authorization: 'Bearer ' + ${JSON.stringify(A.token)} } }); return 1;`).catch(() => { });
const ok = resultados.filter(Boolean).length;
console.log(`\n${ok} de ${resultados.length} pruebas correctas`);
try { ws.close(); } catch { }
try { execSync(process.platform === 'win32' ? `taskkill /pid ${proc.pid} /T /F` : `kill ${proc.pid}`, { stdio: 'ignore' }); } catch { proc.kill(); }
await espera(800);
try { rmSync(perfil, { recursive: true, force: true }); } catch { }
process.exit(ok === resultados.length ? 0 : 1);
