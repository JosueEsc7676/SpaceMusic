// Control remoto entre dos dispositivos con LA MISMA cuenta (dos navegadores aislados, celular 390×844):
// el dispositivo A ve al B en «Tus otros dispositivos» y lo controla (pausa, volumen, adelantar,
// «escuchar aquí» y «pasar mi música»). También guarda capturas.
//
//   SM_USUARIO=admin SM_CLAVE=... node pruebas/control-remoto.mjs [http://localhost:8090] [carpeta-capturas]
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

const perfil = mkdtempSync(join(tmpdir(), 'sm-remoto-'));
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
    for (;;) { const v = await js(codigo).catch(() => null); if (v) return v; if (Date.now() - t0 > ms) throw new Error('No se cumplió: …' + codigo.slice(-110)); await espera(300); }
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
for (const n of [a, b]) { await n.ir('/#/', 3500); }
const MOD = `const P = await import(location.origin + '/js/player.js'); const R = await import(location.origin + '/js/remoto.js');`;
const idB = await b.js(`${MOD} return R.miDispositivo();`);
const idA = await a.js(`${MOD} return R.miDispositivo();`);
const cancion = await b.js(`const r = await fetch('/api/canciones/top?limite=5', { headers: { Authorization: 'Bearer ' + localStorage.getItem('sm_token') } }); const l = await r.json(); return l.find((c) => c.duracionSegundos > 90) || l[0];`);

await prueba('Los dos usan la conexión permanente (WebSocket, como Spotify Connect)', async () => {
  await a.hasta(`${MOD} return R.conectadoEnVivo();`, 8000);
  await b.hasta(`${MOD} return R.conectadoEnVivo();`, 8000);
  return 'WebSocket abierto en A y en B';
});

await prueba('Cada navegador es un dispositivo distinto', async () => {
  afirmar(idA && idB && idA !== idB, 'mismo id');
  return `${idA.slice(0, 7)}… y ${idB.slice(0, 7)}…`;
});

await prueba('A ve al dispositivo B sonando en «Tus otros dispositivos»', async () => {
  await b.js(`${MOD} P.player.play([${JSON.stringify(cancion)}], 0, { type: 'prueba' }); return 1;`);
  await b.hasta(`${MOD} return P.player.playing && P.player.audio.currentTime > 0.5;`);
  const d = await a.hasta(`${MOD} const l = await R.cargarDispositivos(); const x = l.find((y) => y.id === ${JSON.stringify(idB)}); return x && x.sonando && x.cancion?.id === ${cancion.id} && x;`);
  await a.js(`(await import(location.origin + '/js/dispositivos.js')).abrirDispositivos(); return 1;`);
  await espera(1500);
  await a.captura('remoto-01-dispositivos');
  afirmar(await a.js(`return !!document.querySelector('[data-remoto="${idB}"]');`), 'no aparece en el panel');
  return `«${d.nombre}» sonando «${d.cancion.nombre}»`;
});

await prueba('Desde A: abrir el control de B', async () => {
  await a.js(`document.querySelector('[data-remoto="${idB}"]').click(); return 1;`);
  await a.hasta(`return !!document.querySelector('[data-remoto-box] [data-o="toggle"]');`);
  await espera(600);
  await a.captura('remoto-02-control');
  return 'pantalla de control abierta';
});

await prueba('Pausar y reanudar B desde A (llega en menos de 3 s)', async () => {
  const t0 = Date.now();
  await a.js(`${MOD} await R.ordenar(${JSON.stringify(idB)}, 'pause'); return 1;`);
  await b.hasta(`${MOD} return !P.player.playing;`, 3000);
  const ms = Date.now() - t0;
  await a.js(`${MOD} await R.ordenar(${JSON.stringify(idB)}, 'play'); return 1;`);
  await b.hasta(`${MOD} return P.player.playing;`, 3000);
  return `pausa aplicada en ${ms} ms`;
});

// Punto al que se adelanta: el 60 % de la canción (las canciones de prueba locales son cortas)
const DUR = await b.js(`${MOD} return P.player.duration();`);
const OBJ = Math.max(2, Math.floor(DUR * 0.6));
await prueba('Volumen y adelantar en B desde A', async () => {
  await a.js(`${MOD} await R.ordenar(${JSON.stringify(idB)}, 'volumen', { volumen: 0.3 }); await R.ordenar(${JSON.stringify(idB)}, 'seek', { segundo: ${OBJ} }); return 1;`);
  const r = await b.hasta(`${MOD} const v = P.player.volume, t = P.player.audio.currentTime; return Math.abs(v - 0.3) < 0.01 && t >= ${OBJ - 1} && t < ${OBJ + 4} && { v, t };`, 5000);
  return `volumen ${r.v}, posición ${r.t.toFixed(1)} s`;
});

await prueba('A ve el estado nuevo de B al instante (evento en vivo)', async () => {
  const d = await a.hasta(`${MOD} const x = R.otrosDispositivos().find((y) => y.id === ${JSON.stringify(idB)}); return x && Math.abs(x.volumen - 0.3) < 0.02 && x.segundo >= ${OBJ - 1} && x;`, 5000);
  return `volumen ${d.volumen}, segundo ${d.segundo}`;
});

await prueba('«Escuchar aquí»: la música de B pasa a A y B se pausa', async () => {
  await b.js(`${MOD} P.player.seek(${OBJ}); P.player.pause(); P.player.resume(); return 1;`);
  await espera(1500);
  await a.js(`document.querySelector('.modal [data-aqui]')?.click() ?? null; return 1;`);
  await a.hasta(`${MOD} return P.player.playing && P.player.current?.id === ${cancion.id} && P.player.audio.currentTime >= ${OBJ - 1};`, 12000);
  await b.hasta(`${MOD} return !P.player.playing;`, 5000);
  return 'A suena desde el mismo segundo y B quedó en pausa';
});

await prueba('«Pasar mi música» de A a B', async () => {
  await a.js(`${MOD} const d = (await R.cargarDispositivos()).find((y) => y.id === ${JSON.stringify(idB)}); await R.pasarA(d); return 1;`);
  await b.hasta(`${MOD} return P.player.playing && P.player.current?.id === ${cancion.id};`, 12000);
  await a.hasta(`${MOD} return !P.player.playing;`, 3000);
  await b.captura('remoto-03-b-recibe');
  return 'B suena y A quedó en pausa';
});

await prueba('Un solo dispositivo suena a la vez: al poner música en A, B se pausa solo', async () => {
  await b.hasta(`${MOD} return P.player.playing;`, 5000);
  await espera(2000); // como una persona: B ya está sonando de forma estable antes de reclamar la música en A
  // Registro de lo que hace el audio de B (para entender cualquier falla)
  await b.js(`${MOD} window.__reg = []; const t0 = performance.now(); const au = P.player.audio;
    for (const ev of ['play', 'playing', 'pause', 'ended', 'loadstart']) au.addEventListener(ev, () => window.__reg.push(Math.round(performance.now() - t0) + ' ' + ev + ' ' + Math.round(au.currentTime * 10) / 10));
    const orig = HTMLMediaElement.prototype.play; HTMLMediaElement.prototype.play = function () { window.__reg.push(Math.round(performance.now() - t0) + ' play() ' + String(new Error().stack).replace(/\s+/g, ' ').slice(30, 260)); return orig.apply(this, arguments); };
    return 1;`);
  await a.js(`${MOD} P.player.play([${JSON.stringify(cancion)}], 0, { type: 'prueba' }); return 1;`);
  await a.hasta(`${MOD} return P.player.playing;`, 8000);
  await b.hasta(`${MOD} return !P.player.playing;`, 5000).catch(async (e) => {
    const reg = await b.js(`${MOD} return JSON.stringify([R.miDispositivo().slice(0, 6), P.player.playing, window.__reg]);`) + ' A=' + idA.slice(0, 6);
    throw new Error('B no se pausó · ' + reg);
  });
  const act = await a.hasta(`${MOD} await R.cargarDispositivos(); return R.dispositivoActivo() === R.miDispositivo() && R.dispositivoActivo();`, 5000).catch(async (e) => {
    const diag = await a.js(`${MOD} return JSON.stringify({ activo: R.dispositivoActivo(), yo: R.miDispositivo(), b: ${JSON.stringify(idB)}, aSuena: P.player.playing,
      lista: R.otrosDispositivos().map((d) => d.id.slice(0, 6) + ':' + (d.sonando ? 'suena' : 'pausa') + (d.activo ? ':activo' : '')) });`);
    throw new Error(e.message.slice(-60) + ' · ' + diag);
  });
  return 'A quedó como dispositivo activo y B en pausa';
});

await prueba('Orden confirmada por el dispositivo (ack) y error si no existe', async () => {
  const r = await a.js(`${MOD} const t0 = performance.now(); const x = await R.ordenar(${JSON.stringify(idB)}, 'volumen', { volumen: 0.5 }); return { ms: performance.now() - t0, legado: x.legado };`);
  afirmar(r.legado === false, 'la confirmación no vino del dispositivo');
  const e = await a.js(`${MOD} try { await R.ordenar('no-existe-123', 'pause'); return 'sin error'; } catch (e) { return e.message; }`);
  afirmar(e !== 'sin error', 'una orden a un dispositivo inexistente debería fallar');
  return `confirmada en ${r.ms.toFixed(0)} ms · inexistente: «${e}»`;
});

await prueba('Como Spotify Connect: la barra de A muestra «Escuchando en B» y controla B', async () => {
  // B suena; A deja de sonar: la barra de A pasa a controlar B
  await b.js(`${MOD} P.player.play([${JSON.stringify(cancion)}], 0, { type: 'prueba' }); return 1;`);
  await b.hasta(`${MOD} return P.player.playing;`, 8000);
  await a.hasta(`${MOD} await R.cargarDispositivos(); return R.dispositivoActivo() === ${JSON.stringify(idB)};`, 6000).catch(async (e) => {
    const diag = await a.js(`${MOD} return JSON.stringify({ activo: R.dispositivoActivo(), yo: R.miDispositivo(), b: ${JSON.stringify(idB)}, aSuena: P.player.playing,
      lista: R.otrosDispositivos().map((d) => d.id.slice(0, 6) + ':' + (d.sonando ? 'suena' : 'pausa') + (d.activo ? ':activo' : '')) });`);
    throw new Error('B empezó a sonar pero no pasó a ser el activo · ' + diag);
  });
  await a.js(`${MOD} P.player.pause(); return 1;`);
  const txt = await a.hasta(`const pb = document.getElementById('playerBar'); return pb?.classList.contains('remoto') && document.querySelector('[data-br-donde]')?.textContent;`, 8000);
  await a.captura('remoto-04-barra-escuchando-en');
  // Pausar desde la barra de A pausa B
  await a.js(`document.querySelector('[data-br="toggle"]').click(); return 1;`);
  await b.hasta(`${MOD} return !P.player.playing;`, 5000);
  // Reanudar desde la barra de A reanuda B
  await a.hasta(`return document.querySelector('[data-br-donde]')?.textContent.startsWith('En pausa');`, 5000).catch(async (e) => {
    const diag = await a.js(`${MOD} return JSON.stringify({ barra: document.querySelector('[data-br-donde]')?.textContent, remoto: document.getElementById('playerBar').className,
      activo: R.dispositivoActivo(), yo: R.miDispositivo(), b: ${JSON.stringify(idB)}, aSuena: P.player.playing,
      lista: R.otrosDispositivos().map((d) => d.id.slice(0, 6) + ':' + (d.sonando ? 'suena' : 'pausa') + (d.activo ? ':activo' : '')) });`);
    throw new Error(e.message.slice(0, 60) + ' · ' + diag);
  });
  await a.js(`document.querySelector('[data-br="toggle"]').click(); return 1;`);
  await b.hasta(`${MOD} return P.player.playing;`, 5000);
  return `«${txt}»: pausa y reanuda B desde la barra de A`;
});

await prueba('Al cerrar sesión en B, deja de aparecer en A', async () => {
  await b.js(`(await import(location.origin + '/js/api.js')).logout(); return 1;`).catch(() => { });
  await a.hasta(`${MOD} const l = await R.cargarDispositivos(); return !l.some((y) => y.id === ${JSON.stringify(idB)});`, 8000);
  return 'desaparece al instante';
});

const ok = resultados.filter(Boolean).length;
console.log(`
${ok} de ${resultados.length} pruebas correctas`);
try { ws.close(); } catch { }
try { execSync(process.platform === 'win32' ? `taskkill /pid ${proc.pid} /T /F` : `kill ${proc.pid}`, { stdio: 'ignore' }); } catch { proc.kill(); }
await espera(800);
try { rmSync(perfil, { recursive: true, force: true }); } catch { }
process.exit(ok === resultados.length ? 0 : 1);
