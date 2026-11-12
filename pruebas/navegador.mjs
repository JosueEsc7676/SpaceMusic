// Pruebas de SpaceMusic en un navegador de celular simulado (Edge o Chrome sin ventana, sin instalar nada).
// Sirven cuando no hay un teléfono a mano: simulan Android, sin internet, servidor caído y pantalla apagada.
//
//   SM_USUARIO=admin SM_CLAVE=... node pruebas/navegador.mjs [http://localhost:8090]
//
// La cuenta debe ser de administrador (la prueba del reporte revisa el panel). Nada de esto se guarda en el repositorio.
// Navegador: se usa Edge; para otro, CHROME="ruta/al/navegador".
import { spawn, execSync } from 'node:child_process';
import { mkdtempSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BASE = (process.argv[2] || process.env.SM_BASE || 'http://localhost:8090').replace(/\/$/, '');
const USUARIO = process.env.SM_USUARIO, CLAVE = process.env.SM_CLAVE;
if (!USUARIO || !CLAVE) { console.error('Faltan SM_USUARIO y SM_CLAVE (una cuenta de administrador de pruebas).'); process.exit(1); }
const NAVEGADOR = process.env.CHROME || ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'].find((p) => existsSync(p));
const PUERTO = 9400 + Math.floor(Math.random() * 400);
const UA_ANDROID = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36';
const espera = (ms) => new Promise((r) => setTimeout(r, ms));

// ── Sesión de prueba ──
const login = await (await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ usuario: USUARIO, password: CLAVE }) })).json();
if (!login.token) { console.error('No se pudo iniciar sesión:', login.error || login); process.exit(1); }
const H = { Authorization: 'Bearer ' + login.token, 'Content-Type': 'application/json' };
const apiNode = async (ruta, opciones = {}) => (await fetch(BASE + ruta, { headers: H, ...opciones })).json();

// ── Navegador y protocolo DevTools ──
const perfil = mkdtempSync(join(tmpdir(), 'sm-pruebas-'));
const proc = spawn(NAVEGADOR, ['--headless=new', `--remote-debugging-port=${PUERTO}`, `--user-data-dir=${perfil}`, '--window-size=412,915',
  '--autoplay-policy=no-user-gesture-required', '--no-first-run', 'about:blank'], { stdio: 'ignore' });
let info;
for (let i = 0; i < 60 && !info; i++) { try { info = await (await fetch(`http://127.0.0.1:${PUERTO}/json/version`)).json(); } catch { await espera(250); } }
const ws = new WebSocket(info.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
let seq = 0;
const pend = new Map();
const eventos = [];
ws.addEventListener('message', (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pend.has(d.id)) { pend.get(d.id)(d); pend.delete(d.id); } else if (d.method) eventos.push(d);
});
const cmd = (method, params = {}, sessionId) => new Promise((res, rej) => {
  const id = ++seq;
  pend.set(id, (d) => (d.error ? rej(new Error(method + ': ' + d.error.message)) : res(d.result)));
  ws.send(JSON.stringify({ id, method, params, sessionId }));
});

const { targetId } = await cmd('Target.createTarget', { url: 'about:blank' });
const { sessionId: S } = await cmd('Target.attachToTarget', { targetId, flatten: true });
const pag = (m, p) => cmd(m, p, S);
await pag('Page.enable');
await pag('Runtime.enable');
await pag('Network.enable');
await pag('Emulation.setDeviceMetricsOverride', { width: 412, height: 915, deviceScaleFactor: 2, mobile: true });
await pag('Emulation.setUserAgentOverride', { userAgent: UA_ANDROID, platform: 'Android' });
await pag('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
await pag('Page.addScriptToEvaluateOnNewDocument', { source: `try { localStorage.setItem('sm_token', ${JSON.stringify(login.token)});
  localStorage.setItem('sm_user', ${JSON.stringify(JSON.stringify(login.usuario))}); localStorage.setItem('sm_novedades', '99');
  sessionStorage.setItem('sm_splash', '1'); localStorage.setItem('sm_gustos_preguntado:${login.usuario.id}', '1'); } catch (e) {}` });

/** Ejecuta código en la página (con await) y devuelve el resultado. */
async function js(codigo, timeout = 60000) {
  const r = await Promise.race([
    pag('Runtime.evaluate', { expression: `(async () => { ${codigo} })()`, awaitPromise: true, returnByValue: true }),
    espera(timeout).then(() => ({ exceptionDetails: { text: 'tiempo agotado' } })),
  ]);
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result.value;
}
async function ir(ruta, ms = 2500) {
  await pag('Page.navigate', { url: BASE + ruta });
  await espera(ms);
}
// Las condiciones de red de CDP son por sesión: se reutiliza una sola sesión por service worker
// (si se abre otra para "desbloquear", el bloqueo de la primera sigue activo)
const sesionesSw = new Map();
async function sesionSw(targetId) {
  if (!sesionesSw.has(targetId)) {
    const { sessionId } = await cmd('Target.attachToTarget', { targetId, flatten: true });
    await cmd('Network.enable', {}, sessionId).catch(() => { });
    sesionesSw.set(targetId, sessionId);
  }
  return sesionesSw.get(targetId);
}
/** Sin internet también para el service worker (es otro "proceso" del navegador). */
async function sinInternet(si) {
  await pag('Network.emulateNetworkConditions', { offline: si, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  const { targetInfos } = await cmd('Target.getTargets');
  for (const t of targetInfos.filter((x) => x.type === 'service_worker'))
    await cmd('Network.emulateNetworkConditions', { offline: si, latency: 0, downloadThroughput: -1, uploadThroughput: -1 }, await sesionSw(t.targetId)).catch(() => { });
}
/** Bloquea direcciones en la página y en el service worker (si no, el service worker seguiría bloqueado). */
async function bloquear(urls) {
  await pag('Network.setBlockedURLs', { urls });
  const { targetInfos } = await cmd('Target.getTargets');
  for (const t of targetInfos.filter((x) => x.type === 'service_worker'))
    await cmd('Network.setBlockedURLs', { urls }, await sesionSw(t.targetId)).catch(() => { });
}
/** "Pantalla apagada": la página pasa a segundo plano (document.hidden = true). */
const PANTALLA = (apagada) => `Object.defineProperty(document, 'hidden', { configurable: true, get: () => ${apagada} });
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => '${apagada ? 'hidden' : 'visible'}' });
  document.dispatchEvent(new Event('visibilitychange'));`;
const MODULOS = `const P = await import(location.origin + '/js/player.js'); const O = await import(location.origin + '/js/offline.js');`;

// ── Pruebas ──
const resultados = [];
async function prueba(nombre, fn) {
  const t0 = Date.now();
  try {
    const detalle = await fn();
    resultados.push({ nombre, ok: true, detalle });
    console.log(`✓ ${nombre}${detalle ? ' — ' + detalle : ''} (${((Date.now() - t0) / 1000).toFixed(1)} s)`);
  } catch (e) {
    resultados.push({ nombre, ok: false, detalle: e.message });
    console.log(`✗ ${nombre} — ${e.message.split('\n')[0]}`);
  }
}
const afirmar = (c, msg) => { if (!c) throw new Error(msg); };

const todas = (await apiNode('/api/buscar?q=a')).canciones || [];
afirmar(todas.length >= 3, 'Se necesitan al menos 3 canciones en la biblioteca');

await ir('/#/');
// El service worker toma el control en la segunda carga
await js(`await Promise.race([navigator.serviceWorker?.ready, new Promise((r) => setTimeout(r, 8000))]); return location.href;`);
await ir('/#/');

await prueba('La app abre en un Android (simulado)', async () => {
  const r = await js(`return { titulo: document.title, inicio: !!document.querySelector('.greeting, .home-head'), movil: matchMedia('(max-width: 820px)').matches };`);
  afirmar(r.inicio, 'No se ve el inicio');
  afirmar(r.movil, 'No se aplicó la vista de celular');
  return 'inicio visible en pantalla de celular';
});

await prueba('Medición: tiempo hasta ver el inicio y los resultados de búsqueda (celular simulado)', async () => {
  const veces = [];
  for (let i = 0; i < 5; i++) {
    const antes = await js('return performance.timeOrigin;');
    await pag('Page.navigate', { url: BASE + '/?m=' + i + '#/' });
    // Esperar a que sea la página nueva (no la anterior)
    for (let k = 0; k < 100; k++) { await espera(50); try { if (await js('return performance.timeOrigin;') !== antes) break; } catch { } }
    const t = await js(`for (let i = 0; i < 400 && !document.querySelector('.home-head, .greeting'); i++) await new Promise((r) => setTimeout(r, 10));
      return { inicio: performance.now(), dcl: performance.getEntriesByType('navigation')[0]?.domContentLoadedEventEnd };`);
    const b = await js(`const t0 = performance.now();
      location.hash = '#/buscar?q=' + encodeURIComponent(${JSON.stringify(todas[0].nombre.slice(0, 4))} + '${'${i}'}'.slice(0, 0));
      // Las pestañas de resultados (Todo, Canciones…) solo existen en la búsqueda
      for (let i = 0; i < 400 && !document.querySelector('a.chip[href*="buscar?q="]'); i++) await new Promise((r) => setTimeout(r, 10));
      return performance.now() - t0;`);
    veces.push({ inicio: t.inicio, dcl: t.dcl, busqueda: b });
  }
  const med = (k) => { const v = veces.map((x) => x[k]).sort((a, b) => a - b); return Math.round(v[Math.floor(v.length / 2)]); };
  return `inicio visible ${med('inicio')} ms (DOM listo ${med('dcl')} ms) · búsqueda en pantalla ${med('busqueda')} ms (mediana de 5)`;
});

await prueba('Agregar una canción recomendada a una playlist (sin que empiece a sonar)', async () => {
  const pl = await apiNode('/api/playlists', { method: 'POST', body: JSON.stringify({ nombre: 'Prueba automática' }) });
  await apiNode(`/api/playlists/${pl.id}/canciones`, { method: 'POST', body: JSON.stringify({ ids: [todas[0].id] }) });
  try {
    const r = await js(`${MODULOS} P.player.pause();
      location.hash = '#/playlist/${pl.id}';
      let b; for (let i = 0; i < 50 && !(b = document.querySelector('#recs .btn.outline')); i++) await new Promise((r) => setTimeout(r, 200));
      if (!b) return { sinRecs: true };
      b.click();
      await new Promise((r) => setTimeout(r, 1500));
      return { suena: P.player.playing };`);
    if (r.sinRecs) return 'no hubo recomendadas para probar (biblioteca pequeña)';
    const d = await apiNode('/api/playlists/' + pl.id);
    afirmar(d.cancionesCount === 2, `La playlist tiene ${d.cancionesCount} canción(es), se esperaban 2`);
    afirmar(!r.suena, 'Empezó a reproducir en vez de solo agregar');
    return 'se agregó y no empezó a sonar';
  } finally {
    await fetch(BASE + '/api/playlists/' + pl.id, { method: 'DELETE', headers: H });
  }
});

await prueba('Transición entre canciones (crossfade de 3 s)', async () => {
  const r = await js(`${MODULOS}
    P.prefs.mezcla = 3;
    const lista = ${JSON.stringify(todas.slice(0, 3))};
    P.player.play(lista, 0, { type: 'prueba', label: 'Prueba' });
    for (let i = 0; i < 40 && !(P.player.audio.duration > 0 && P.player.playing); i++) await new Promise((r) => setTimeout(r, 250));
    const dur = P.player.audio.duration;
    if (!(dur > 16)) return { corta: dur };
    P.player.seek(dur - 4.5);
    let vio = 0;
    for (let i = 0; i < 40; i++) { await new Promise((r) => setTimeout(r, 200)); if (P.mezcla.entrada > 0 && P.mezcla.entrada < 1) vio++; if (P.player.index === 1 && P.mezcla.entrada === 1 && vio) break; }
    const r = { indice: P.player.index, entrada: P.mezcla.entrada, rampa: vio, suena: P.player.playing, t: P.player.audio.currentTime, dur, items: P.player.items.length, nombre: P.player.current?.nombre };
    P.prefs.mezcla = 0; P.player.pause();
    return r;`);
  if (r.corta !== undefined) return `canciones de prueba muy cortas (${r.corta} s): se omite`;
  afirmar(r.indice === 1, 'No pasó a la siguiente canción ' + JSON.stringify(r));
  afirmar(r.rampa > 0, 'El volumen no subió poco a poco');
  afirmar(r.entrada === 1, 'La canción nueva no quedó a volumen completo');
  return `pasó a la siguiente y el volumen subió en ${r.rampa} pasos`;
});

await prueba('Volumen parejo sigue funcionando con la pantalla apagada', async () => {
  const r = await js(`${MODULOS}
    P.nivelador.activar(true);
    P.player.play(${JSON.stringify(todas.slice(0, 2))}, 0, { type: 'prueba', label: 'Prueba' });
    for (let i = 0; i < 40 && !P.player.playing; i++) await new Promise((r) => setTimeout(r, 250));
    ${PANTALLA(true)}
    const t0 = P.player.audio.currentTime;
    await new Promise((r) => setTimeout(r, 5000));
    const r = { ctx: P.fx.ctx?.state, avanzo: P.player.audio.currentTime - t0, ganancia: P.fx.nivel?.gain.value, muestras: P.nivelador._n, suena: P.player.playing };
    ${PANTALLA(false)}
    P.nivelador.activar(false); P.player.pause();
    return r;`);
  afirmar(r.suena && r.avanzo > 3, `La música no siguió sonando (avanzó ${r.avanzo?.toFixed?.(1)} s)`);
  afirmar(r.ctx === 'running', `El procesador de audio quedó "${r.ctx}"`);
  afirmar(r.muestras >= 8, 'No midió el volumen con la pantalla apagada');
  return `siguió sonando y ajustó ${(20 * Math.log10(r.ganancia || 1)).toFixed(1)} dB`;
});

await prueba('Con la pantalla apagada pasa a la siguiente canción aunque se corten las peticiones', async () => {
  const r = await js(`${MODULOS}
    P.prefs.mezcla = 0;
    P.player.play(${JSON.stringify(todas.slice(0, 3))}, 0, { type: 'prueba', label: 'Prueba' });
    for (let i = 0; i < 40 && !(P.player.audio.duration > 0 && P.player.playing); i++) await new Promise((r) => setTimeout(r, 250));
    ${PANTALLA(true)}
    return { dur: P.player.audio.duration };`);
  // Como en iPhone con la pantalla bloqueada: la página no puede hacer peticiones a la API
  await pag('Network.setBlockedURLs', { urls: [`*${new URL(BASE).host}/api/inicio*`, `*${new URL(BASE).host}/api/radio*`, `*${new URL(BASE).host}/api/notificaciones*`, `*${new URL(BASE).host}/api/eventos*`] });
  try {
    const f = await js(`${MODULOS}
      try { await (await import(location.origin + '/js/api.js')).api('/api/inicio'); } catch { }
      P.player.seek(P.player.audio.duration - 1.5);
      for (let i = 0; i < 30 && P.player.index === 0; i++) await new Promise((r) => setTimeout(r, 250));
      await new Promise((r) => setTimeout(r, 800));
      const r = { indice: P.player.index, suena: P.player.playing, sinRed: O.sinConexion() };
      ${PANTALLA(false)}
      P.player.pause();
      return r;`);
    afirmar(f.indice === 1, 'Se quedó en la misma canción');
    afirmar(f.suena, 'El reproductor se detuvo');
    return 'pasó a la siguiente y siguió sonando';
  } finally {
    await pag('Network.setBlockedURLs', { urls: [] });
  }
});

await prueba('Si la copia guardada es de otra canción, se corrige y suena la correcta', async () => {
  const con = todas.filter((c) => c.duracionSegundos >= 20).sort((a, b) => a.duracionSegundos - b.duracionSegundos);
  const a = con[con.length - 1], b = todas.filter((c) => Math.abs(c.duracionSegundos - a.duracionSegundos) > 8).sort((x, y) => x.duracionSegundos - y.duracionSegundos)[0];
  if (!a || !b) return 'no hay canciones con duraciones distintas para probar';
  const r = await js(`${MODULOS}
    const { audioUrl } = await import(location.origin + '/js/api.js');
    // Se guarda en el dispositivo el audio de B como si fuera A (copia equivocada)
    const c = await caches.open('sm-audio');
    await c.put('/offline/audio/${a.id}', await fetch(audioUrl(${b.id})));
    await O.actualizarDisponibles();
    P.player.play([${JSON.stringify(a)}], 0, { type: 'prueba', label: 'Prueba' });
    for (let i = 0; i < 40; i++) { await new Promise((r) => setTimeout(r, 250)); if (Math.abs(P.player.audio.duration - ${a.duracionSegundos}) < 3) break; }
    const r = { dur: P.player.audio.duration, esperada: ${a.duracionSegundos}, copia: !!(await c.match('/offline/audio/${a.id}')) };
    P.player.pause();
    return r;`);
  afirmar(Math.abs(r.dur - r.esperada) < 3, `Siguió sonando otra canción (duración ${r.dur?.toFixed?.(1)} s, se esperaba ${r.esperada} s)`);
  return `detectó la copia equivocada y sonó la correcta (${r.dur.toFixed(0)} s)`;
});

await prueba('Menú "Reportar un problema"', async () => {
  const antes = (await apiNode('/api/admin/comunidad/reportes?estado=ABIERTO')).total;
  const s = todas[1];
  const r = await js(`
    location.hash = '#/buscar?q=' + encodeURIComponent(${JSON.stringify(s.nombre)});
    let fila; for (let i = 0; i < 40 && !(fila = document.querySelector('.track[data-song="${s.id}"]')); i++) await new Promise((r) => setTimeout(r, 200));
    if (!fila) return { error: 'no apareció la canción en el buscador' };
    fila.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: 100, clientY: 300 }));
    await new Promise((r) => setTimeout(r, 400));
    const op = [...document.querySelectorAll('.menu button, .menu [role=menuitem], .menu .mi')].find((b) => /Reportar un problema/.test(b.textContent));
    if (!op) return { error: 'el menú no tiene "Reportar un problema"' };
    op.click();
    await new Promise((r) => setTimeout(r, 500));
    const m = document.querySelector('.modal');
    if (!m) return { error: 'no se abrió el formulario' };
    m.querySelector('input[name=motivo][value=datos]').click();
    m.querySelector('[data-detalle]').value = 'Prueba automática: ignorar';
    [...m.querySelectorAll('.foot button')].find((b) => /Enviar/.test(b.textContent)).click();
    await new Promise((r) => setTimeout(r, 1500));
    return { cerrado: !document.querySelector('.modal') };`);
  afirmar(!r.error, r.error);
  const despues = await apiNode('/api/admin/comunidad/reportes?estado=ABIERTO');
  const nuevo = despues.contenido.find((x) => x.detalle === 'Prueba automática: ignorar');
  afirmar(nuevo || despues.total > antes, 'El reporte no llegó al panel');
  if (nuevo) await apiNode('/api/admin/comunidad/reportes/' + nuevo.id, { method: 'PUT', body: JSON.stringify({ estado: 'DESCARTADO', respuesta: 'Prueba automática' }) });
  return 'el reporte llegó al panel de administración' + (nuevo ? ` (cliente: ${nuevo.cliente})` : '');
});

await prueba('Aviso "SpaceMusic está en mantenimiento" cuando el servidor no responde', async () => {
  await ir('/#/', 3000);
  // El servidor "se cae" (sus direcciones no responden) pero el resto de internet sí
  await bloquear([`*${new URL(BASE).host}/*`]);
  try {
    const r = await js(`
      const { api } = await import(location.origin + '/js/api.js');
      try { await api('/api/usuario/estadisticas'); } catch { }
      for (let i = 0; i < 30 && !document.documentElement.classList.contains('mantenimiento'); i++) await new Promise((r) => setTimeout(r, 300));
      const { textoSinRed } = await import(location.origin + '/js/util.js');
      const O = await import(location.origin + '/js/offline.js');
      const img = await new Promise((res) => { const i = new Image(); i.onload = () => res('carga'); i.onerror = () => res('error'); i.src = 'https://www.google.com/favicon.ico?_=' + Date.now(); setTimeout(() => res('tiempo'), 6000); });
      return { clase: document.documentElement.classList.contains('mantenimiento'), texto: textoSinRed(), sinRed: O.sinConexion(), img, online: navigator.onLine };`);
    if (!r.clase) throw new Error('No detectó el mantenimiento ' + JSON.stringify(r));
    afirmar(r.clase, 'No detectó el mantenimiento (¿este equipo tiene internet?)');
    afirmar(/mantenimiento/.test(r.texto), 'El aviso no dice mantenimiento');
    // El servidor vuelve: la app debe recuperarse sola al recargar
    await bloquear([]);
    await ir('/?v=' + Date.now() + '#/buscar?q=a', 3500);
    const v = await js(`const O = await import(location.origin + '/js/offline.js');
      const H = { Authorization: 'Bearer ' + localStorage.getItem('sm_token') };
      const r1 = await fetch('/api/buscar?q=a', { headers: H }).then((r) => r.status + ' ' + r.headers.get('X-SM-Copia')).catch((e) => 'ERR ' + e.message);
      const r2 = await fetch('/api/usuario/estadisticas', { headers: H }).then((r) => r.status).catch((e) => 'ERR ' + e.message);
      return { sinRed: O.sinConexion(), online: navigator.onLine, buscar: r1, otra: r2, resultados: !!document.querySelector('a.chip[href*="buscar?q="]'), banner: !!document.querySelector('.offline-banner') };`);
    afirmar(!v.sinRed && v.resultados, 'Al volver el servidor la app siguió como sin conexión ' + JSON.stringify(v));
    return `muestra “${r.texto}” y se recupera sola cuando el servidor vuelve`;
  } finally {
    await bloquear([]);
  }
});

await prueba('La app abre sin internet y suena lo descargado', async () => {
  const s = todas[2];
  const listo = await js(`${MODULOS}
    O.descargarColeccion('favoritos', 'favoritos', {}, [${JSON.stringify(s)}]);
    for (let i = 0; i < 120 && !O.disponible(${s.id}); i++) { await new Promise((r) => setTimeout(r, 500)); await O.actualizarDisponibles(); }
    return O.disponible(${s.id});`, 90000);
  if (!listo) {
    const d = await js(`return { controla: !!navigator.serviceWorker?.controller, sw: (await navigator.serviceWorker?.getRegistration())?.active?.state, cachés: await caches.keys() };`);
    throw new Error('La canción no terminó de descargarse ' + JSON.stringify(d));
  }
  await sinInternet(true);
  try {
    await ir('/#/', 4000);
    const r = await js(`${MODULOS}
      const pantalla = { inicio: !!document.querySelector('.home-head, .greeting'), aviso: document.querySelector('.offline-banner')?.textContent.trim().slice(0, 60), negro: document.body.innerText.trim().length < 20 };
      P.player.play([${JSON.stringify(s)}], 0, { type: 'prueba', label: 'Sin internet' });
      await new Promise((r) => setTimeout(r, 3500));
      return { ...pantalla, t: P.player.audio.currentTime, suena: P.player.playing };`);
    afirmar(!r.negro && r.inicio, 'La pantalla quedó vacía');
    afirmar(r.aviso, 'No muestra el aviso de sin conexión');
    afirmar(r.suena && r.t > 1, 'La canción descargada no sonó');
    return `“${r.aviso}” y la canción sonó ${r.t.toFixed(1)} s`;
  } finally {
    await sinInternet(false);
    await js(`${MODULOS} O.quitarColeccion('favoritos'); return true;`).catch(() => { });
  }
});

// ── Fin ──
const fallidas = resultados.filter((r) => !r.ok);
console.log(`\n${resultados.length - fallidas.length} de ${resultados.length} pruebas correctas`);
try { ws.close(); } catch { }
try { execSync(process.platform === 'win32' ? `taskkill /pid ${proc.pid} /T /F` : `kill ${proc.pid}`, { stdio: 'ignore' }); } catch { proc.kill(); }
// El perfil temporal del navegador se borra (si no, se acumulan cientos de MB por cada ejecución)
await espera(800);
try { (await import('node:fs')).rmSync(perfil, { recursive: true, force: true }); } catch { }
process.exit(fallidas.length ? 1 : 0);
