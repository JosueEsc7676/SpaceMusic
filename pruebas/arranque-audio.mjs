// Tiempo desde que se toca una canción hasta el primer sonido (evento "playing" del <audio>),
// en un Android simulado con datos móviles y con WiFi. También mide pasar a la siguiente (precargada).
//
//   SM_USUARIO=admin SM_CLAVE=... node pruebas/arranque-audio.mjs [http://localhost:8090] [etiqueta]
//
// Escribe un JSON en pruebas/resultados/arranque-<etiqueta>.json para comparar antes y después.
import { spawn, execSync } from 'node:child_process';
import { mkdtempSync, existsSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BASE = (process.argv[2] || 'http://localhost:8090').replace(/\/$/, '');
const ETIQUETA = process.argv[3] || 'medicion';
const { SM_USUARIO, SM_CLAVE } = process.env;
if (!SM_USUARIO || !SM_CLAVE) { console.error('Faltan SM_USUARIO y SM_CLAVE'); process.exit(1); }
const NAVEGADOR = process.env.CHROME || ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium'].find((p) => existsSync(p));
const PUERTO = 9400 + Math.floor(Math.random() * 400);
const UA = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36';
const espera = (ms) => new Promise((r) => setTimeout(r, ms));
// Perfiles de red (como los de DevTools): datos móviles 4G regular y WiFi doméstica
const REDES = {
  movil: { latency: 120, downloadThroughput: 1_600_000 / 8, uploadThroughput: 750_000 / 8, connectionType: 'cellular4g' },
  wifi: { latency: 15, downloadThroughput: 30_000_000 / 8, uploadThroughput: 10_000_000 / 8, connectionType: 'wifi' },
};

const login = await (await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ usuario: SM_USUARIO, password: SM_CLAVE }) })).json();
if (!login.token) { console.error('No se pudo iniciar sesión'); process.exit(1); }

const perfil = mkdtempSync(join(tmpdir(), 'sm-arranque-'));
const proc = spawn(NAVEGADOR, ['--headless=new', `--remote-debugging-port=${PUERTO}`, `--user-data-dir=${perfil}`, '--window-size=412,915',
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
const { targetId } = await cmd('Target.createTarget', { url: 'about:blank' });
const { sessionId: S } = await cmd('Target.attachToTarget', { targetId, flatten: true });
const pag = (m, p) => cmd(m, p, S);
await pag('Page.enable'); await pag('Runtime.enable'); await pag('Network.enable');
await pag('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
await pag('Emulation.setUserAgentOverride', { userAgent: UA, platform: 'Android' });
await pag('Page.addScriptToEvaluateOnNewDocument', { source: `try { localStorage.setItem('sm_token', ${JSON.stringify(login.token)});
  localStorage.setItem('sm_user', ${JSON.stringify(JSON.stringify(login.usuario))}); localStorage.setItem('sm_novedades', '99');
  sessionStorage.setItem('sm_splash', '1'); localStorage.setItem('sm_gustos_preguntado:${login.usuario.id}', '1'); } catch (e) {}` });
async function js(codigo, timeout = 60000) {
  const r = await Promise.race([pag('Runtime.evaluate', { expression: `(async () => { ${codigo} })()`, awaitPromise: true, returnByValue: true }),
    espera(timeout).then(() => ({ exceptionDetails: { text: 'tiempo agotado' } }))]);
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result.value;
}
const sesionesSw = new Map();
async function red(p) {
  const c = { offline: false, ...p };
  await pag('Network.emulateNetworkConditions', c);
  const { targetInfos } = await cmd('Target.getTargets');
  for (const t of targetInfos.filter((x) => x.type === 'service_worker')) {
    if (!sesionesSw.has(t.targetId)) {
      const { sessionId } = await cmd('Target.attachToTarget', { targetId: t.targetId, flatten: true });
      await cmd('Network.enable', {}, sessionId).catch(() => { });
      sesionesSw.set(t.targetId, sessionId);
    }
    await cmd('Network.emulateNetworkConditions', c, sesionesSw.get(t.targetId)).catch(() => { });
  }
}
/** Sin nada guardado: ni caché del navegador ni copias del service worker. */
async function enFrio() {
  await pag('Network.clearBrowserCache');
  await js(`for (const k of await caches.keys()) if (/audio|descargas|inicio/.test(k)) await caches.delete(k); return true;`);
}

await pag('Page.navigate', { url: BASE + '/#/' });
await espera(2500);
await js(`await Promise.race([navigator.serviceWorker?.ready, new Promise((r) => setTimeout(r, 8000))]); return true;`);
await pag('Page.navigate', { url: BASE + '/#/buscar?q=' + encodeURIComponent(process.env.SM_BUSCAR || 'a') });
await espera(3000);

const pct = (l, p) => { const s = [...l].sort((a, b) => a - b); return s.length ? Math.round(s[Math.min(s.length - 1, Math.ceil(s.length * p) - 1)]) : null; };
const resultados = {};
for (const [nombre, cond] of Object.entries(REDES)) {
  await red(cond);
  const frio = [], siguiente = [];
  const filas = await js(`return document.querySelectorAll('.track[data-act="play-list"]').length;`);
  const n = Math.min(filas, 8);
  for (let k = 0; k < n * 2; k++) {
    const i = k % n;
    await enFrio();
    // Toque en la fila i de la lista → primer "playing"
    const t = await js(`const P = await import(location.origin + '/js/player.js'); P.player.pause(); P.player.items = []; P.player.index = -1;
      const filas = [...document.querySelectorAll('.track[data-act="play-list"]')];
      return await new Promise((res) => { const a = P.player.audio; let t0;
        const fin = () => { a.removeEventListener('playing', fin); res(performance.now() - t0); };
        a.addEventListener('playing', fin); t0 = performance.now(); filas[${i}].click(); setTimeout(() => res(-1), 30000); });`);
    if (t > 0) frio.push(t);
    if (i === n - 1) { process.stdout.write('.'); continue; }
    // Deja sonar unos segundos (la app precarga la siguiente) y pasa a la siguiente
    await espera(cond === REDES.movil ? 12000 : 9000);
    const t2 = await js(`const P = await import(location.origin + '/js/player.js');
      return await new Promise((res) => { const a = P.player.audio; let t0;
        const fin = () => { a.removeEventListener('playing', fin); res(performance.now() - t0); };
        a.addEventListener('playing', fin); t0 = performance.now(); P.player.next(); setTimeout(() => res(-1), 30000); });`);
    if (t2 > 0) siguiente.push(t2);
    process.stdout.write('.');
  }
  // Bytes de audio pedidos a la red en la última canción (para ver descargas repetidas)
  resultados[nombre] = { tocarP50: pct(frio, 0.5), tocarP95: pct(frio, 0.95), siguienteP50: pct(siguiente, 0.5), siguienteP95: pct(siguiente, 0.95), muestras: frio.length, frio, siguiente };
  console.log(`\n${nombre.padEnd(6)} tocar→sonar p50 ${resultados[nombre].tocarP50} ms · p95 ${resultados[nombre].tocarP95} ms · siguiente p50 ${resultados[nombre].siguienteP50} ms · p95 ${resultados[nombre].siguienteP95} ms (${frio.length} canciones)`);
}

// Descargas de audio por canción (red) mientras suena: cuenta peticiones /audio desde la página y el service worker
await red({ latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
await enFrio();
const bytes = await js(`const P = await import(location.origin + '/js/player.js'); P.player.pause();
  performance.clearResourceTimings();
  document.querySelectorAll('.track[data-act="play-list"]')[0].click();
  await new Promise((r) => setTimeout(r, 30000));
  const l = performance.getEntriesByType('resource').filter((e) => /\\/audio/.test(e.name));
  return l.map((e) => ({ url: e.name.replace(/\\?.*/, ''), bytes: e.transferSize, de: e.initiatorType }));`, 45000);
resultados.peticionesAudio = bytes;
console.log('Peticiones de audio desde la página en 30 s:', bytes.length, bytes.map((b) => `${b.url.split('/').slice(-2, -1)}(${b.de}) ${b.bytes} B`).join(', '));

mkdirSync('pruebas/resultados', { recursive: true });
writeFileSync(`pruebas/resultados/arranque-${ETIQUETA}.json`, JSON.stringify({ fecha: new Date().toISOString(), base: BASE, redes: REDES, resultados }, null, 2));
try { ws.close(); } catch { }
try { execSync(process.platform === 'win32' ? `taskkill /pid ${proc.pid} /T /F` : `kill ${proc.pid}`, { stdio: 'ignore' }); } catch { proc.kill(); }
await espera(800);
try { rmSync(perfil, { recursive: true, force: true }); } catch { }
process.exit(0);
