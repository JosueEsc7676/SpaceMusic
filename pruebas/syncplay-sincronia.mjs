// SyncPlay en dos navegadores (misma cuenta, ambos dentro): control compartido y sincronía fina.
// Mide el desfase entre los dos reproductores y comprueba que pausar, mover y cambiar de canción
// desde CUALQUIERA de los dos se refleja en el otro.
//
//   SM_USUARIO=admin SM_CLAVE=... node pruebas/syncplay-sincronia.mjs [http://localhost:8090] [carpeta-capturas]
import { spawn, execSync } from 'node:child_process';
import { mkdtempSync, existsSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BASE = (process.argv[2] || 'http://localhost:8090').replace(/\/$/, '');
const CAPTURAS = process.argv[3] || null;
const { SM_USUARIO, SM_CLAVE } = process.env;
if (!SM_USUARIO || !SM_CLAVE) { console.error('Faltan SM_USUARIO y SM_CLAVE'); process.exit(1); }
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
const A = await entrar(SM_USUARIO, SM_CLAVE);
const B = A; // la misma cuenta en otro dispositivo

const perfil = mkdtempSync(join(tmpdir(), 'sm-syncplay-sinc-'));
const proc = spawn(NAVEGADOR, ['--headless=new', `--remote-debugging-port=${PUERTO}`, `--user-data-dir=${perfil}`,
  '--autoplay-policy=no-user-gesture-required', '--disable-features=AudioFocusEnforcement,MediaSessionService,HardwareMediaKeyHandling', '--no-first-run', 'about:blank'], { stdio: 'ignore' });
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
  return { js, ir, captura, hasta, p };
}

const resultados = [];
async function prueba(nombre, fn) {
  try { const d = await fn(); resultados.push(true); console.log(`✓ ${nombre}${d ? ' — ' + d : ''}`); }
  catch (e) { resultados.push(false); console.log(`✗ ${nombre} — ${e.message.split('\n')[0]}`); }
}
const afirmar = (c, m) => { if (!c) throw new Error(m); };
const a = await navegador(A), b = await navegador(B);
for (const n of [a, b]) { await n.ir('/#/', 3500); }
const MOD = `const P = await import(location.origin + '/js/player.js'); const J = await import(location.origin + '/js/juntos.js');`;
const T = A.token;
const canciones = await a.js(`const r = await fetch('/api/canciones/top?limite=6', { headers: { Authorization: 'Bearer ' + ${JSON.stringify(T)} } }); return (await r.json()).slice(0, 3);`);
// SyncPlay anteriores de esta cuenta se cierran
await a.js(`const s = await (await fetch('/api/juntos', { headers: { Authorization: 'Bearer ' + ${JSON.stringify(T)} } })).json();
  for (const x of s) await fetch('/api/juntos/' + x.id + '/salir', { method: 'POST', headers: { Authorization: 'Bearer ' + ${JSON.stringify(T)} } }); return s.length;`);
const desfase = (x) => x.js(`${MOD} return P.player.audio.currentTime;`);

let sala;
await prueba('A inicia SyncPlay y pone una canción; B entra y suena lo mismo', async () => {
  sala = (await a.js(`${MOD} const d = await J.crearSala(); return d;`)).id;
  await a.ir('/#/juntos/' + sala, 2500);
  await b.ir('/#/juntos/' + sala, 2500);
  await a.js(`${MOD} P.player.play([${JSON.stringify(canciones[0])}], 0, { type: 'prueba' }); return 1;`); // dentro de SyncPlay: suena para todos
  await a.hasta(`${MOD} return P.player.playing && P.player.current?.id === ${canciones[0].id};`, 10000);
  await b.hasta(`${MOD} return P.player.playing && P.player.current?.id === ${canciones[0].id};`, 10000);
  await espera(3000);
  const [ta, tb] = [await desfase(a), await desfase(b)];
  await a.captura('syncplay-01-controles');
  return `misma canción · desfase ${Math.abs(ta - tb).toFixed(3)} s`;
});

await prueba('Sincronía fina: el desfase entre los dos queda por debajo de 0,25 s', async () => {
  const muestras = [];
  for (let i = 0; i < 5; i++) {
    const [ta, tb] = await Promise.all([desfase(a), desfase(b)]);
    muestras.push(Math.abs(ta - tb));
    await espera(700);
  }
  muestras.sort((x, y) => x - y);
  const med = muestras[2];
  afirmar(med < 0.25, 'desfase mediano ' + med.toFixed(3) + ' s');
  return `desfase mediano ${(med * 1000).toFixed(0)} ms (máx ${(muestras[4] * 1000).toFixed(0)} ms)`;
});

await prueba('B pausa desde su reproductor y A también se pausa', async () => {
  await b.js(`${MOD} P.player.toggle(); return 1;`);
  await a.hasta(`${MOD} return !P.player.playing;`, 4000);
  await b.hasta(`${MOD} return !P.player.playing;`, 4000);
  return 'pausado en los dos';
});

await prueba('A reanuda y mueve la posición: B la sigue', async () => {
  await a.js(`${MOD} P.player.toggle(); return 1;`);
  await b.hasta(`${MOD} return P.player.playing;`, 4000);
  const dur = await a.js(`${MOD} return P.player.duration();`);
  const obj = Math.max(2, Math.floor(dur * 0.5));
  await a.js(`${MOD} P.player.seek(${obj}); return 1;`);
  const tb = await b.hasta(`${MOD} const t = P.player.audio.currentTime; return t >= ${obj - 0.5} && t < ${obj + 3} && t;`, 5000);
  return `B en ${tb.toFixed(2)} s (pedido ${obj} s)`;
});

await prueba('B cambia de canción («poner») y A cambia también', async () => {
  await b.js(`${MOD} P.player.play([${JSON.stringify(canciones[1])}], 0, { type: 'prueba' }); return 1;`);
  await a.hasta(`${MOD} return P.player.current?.id === ${canciones[1].id} && P.player.playing;`, 10000);
  return 'los dos con la canción nueva';
});

await prueba('«Anterior» desde A vuelve a la canción de antes en los dos', async () => {
  await a.js(`${MOD} P.player.seek(0); return 1;`);
  await espera(600);
  await a.js(`${MOD} P.player.prev(); return 1;`);
  await b.hasta(`${MOD} return P.player.current?.id === ${canciones[0].id};`, 10000);
  await a.hasta(`${MOD} return P.player.current?.id === ${canciones[0].id};`, 10000);
  return 'volvieron a la primera';
});

await prueba('Con la red lenta, el que se une no se corta (sin saltos en bucle)', async () => {
  const larga = await a.js(`const r = await fetch('/api/canciones/top?limite=60', { headers: { Authorization: 'Bearer ' + ${JSON.stringify(T)} } });
    const b = await fetch('/api/buscar?q=' + encodeURIComponent('Cuatro minutos'), { headers: { Authorization: 'Bearer ' + ${JSON.stringify(T)} } });
    const todas = [...(await r.json()), ...((await b.json()).canciones || [])];
    return todas.filter((c) => c.duracionSegundos > 200).sort((x, y) => y.duracionSegundos - x.duracionSegundos)[0];`);
  afirmar(larga, 'no hay una canción de más de 200 s para probar (en desarrollo: data/music/Prueba Larga)');
  await a.js(`${MOD} P.player.play([${JSON.stringify(larga)}], 0, { type: 'prueba' }); return 1;`);
  await b.hasta(`${MOD} return P.player.playing && P.player.current?.id === ${larga.id};`, 10000);
  await espera(2000);
  await b.p('Network.enable');
  await b.p('Network.emulateNetworkConditions', { offline: false, latency: 1500, downloadThroughput: 90 * 1024, uploadThroughput: 64 * 1024 });
  await b.js(`${MOD} const a = P.player.audio; window.__sm = { saltos: 0, esperas: 0 };
    a.addEventListener('seeking', () => window.__sm.saltos++); a.addEventListener('waiting', () => window.__sm.esperas++); return 1;`);
  // A mueve a una parte que B todavía no ha bajado: B tiene que pedirla por la red lenta
  await a.js(`${MOD} P.player.seek(${Math.floor(larga.duracionSegundos * 0.6)}); return 1;`);
  await espera(22000);
  const r = await b.js(`${MOD} return { ...window.__sm, sonando: P.player.playing };`);
  const muestras = [];
  for (let i = 0; i < 3; i++) { const [ta, tb] = await Promise.all([desfase(a), desfase(b)]); muestras.push(Math.abs(ta - tb)); await espera(500); }
  await b.p('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  const med = muestras.sort((x, y) => x - y)[1];
  afirmar(r.saltos <= 3, `${r.saltos} saltos y ${r.esperas} cortes en 22 s (desfase ${med.toFixed(2)} s)`);
  afirmar(r.sonando && med < 0.6, `al final: sonando=${r.sonando}, desfase ${med.toFixed(2)} s`);
  return `${r.saltos} saltos, ${r.esperas} cortes · desfase final ${(med * 1000).toFixed(0)} ms`;
});

await prueba('Sin emojis ni «sala» en la pantalla de SyncPlay', async () => {
  const t = await a.js(`return document.querySelector('.juntos')?.innerText || '';`);
  afirmar(!/sala/i.test(t), 'aparece «sala»: ' + t.match(/.{0,20}sala.{0,20}/i)?.[0]);
  afirmar(!/\p{Extended_Pictographic}/u.test(t), 'hay emojis');
  return 'textos con «SyncPlay» y sin emojis';
});

await a.js(`await fetch('/api/juntos/${sala}/salir', { method: 'POST', headers: { Authorization: 'Bearer ' + ${JSON.stringify(T)} } }); return 1;`).catch(() => { });
const ok = resultados.filter(Boolean).length;
console.log(`\n${ok} de ${resultados.length} pruebas correctas`);
try { ws.close(); } catch { }
try { execSync(process.platform === 'win32' ? `taskkill /pid ${proc.pid} /T /F` : `kill ${proc.pid}`, { stdio: 'ignore' }); } catch { proc.kill(); }
await espera(800);
try { rmSync(perfil, { recursive: true, force: true }); } catch { }
process.exit(ok === resultados.length ? 0 : 1);
