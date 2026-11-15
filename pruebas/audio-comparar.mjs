// Comparación reproducible con un archivo real y su copia remuxada, en localhost.
// SM_AUDIO_MUESTRA=<carpeta con original.m4a, optimizado.m4a, sw-antes.js> node pruebas/audio-comparar.mjs
// No usa cuentas ni consulta la base de producción; no guarda archivos de música en git.
import http from 'node:http';
import { readFileSync, mkdtempSync, rmSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
const carpeta = process.env.SM_AUDIO_MUESTRA;
if (!carpeta) throw new Error('Falta SM_AUDIO_MUESTRA');
const archivos = { antes: readFileSync(join(carpeta, 'original.m4a')), despues: readFileSync(join(carpeta, 'optimizado.m4a')) };
const scripts = { antes: readFileSync(join(carpeta, 'sw-antes.js'), 'utf8'), despues: readFileSync('src/main/resources/sw/sw.js', 'utf8') };
let modo = 'antes';
let peticionesAudio = 0;
const servidor = http.createServer((req, res) => {
  if (req.url.startsWith('/sw.js')) {
    res.setHeader('Content-Type', 'application/javascript'); res.setHeader('Cache-Control', 'no-store');
    res.end(scripts[modo].replaceAll('__VERSION__', `sm-medicion-${modo}`).replaceAll('__SHELL__', '') + '\nself.skipWaiting();'); return;
  }
  if (req.url.startsWith('/api/canciones/1/audio')) {
    peticionesAudio++;
    const b = archivos[modo], m = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || '');
    const inicio = m ? Number(m[1]) : 0, fin = m?.[2] ? Math.min(Number(m[2]), b.length - 1) : b.length - 1;
    res.setHeader('Content-Type', 'audio/mp4'); res.setHeader('Accept-Ranges', 'bytes');
    if (inicio >= b.length) { res.writeHead(416, { 'Content-Range': `bytes */${b.length}` }); res.end(); return; }
    if (m) { res.statusCode = 206; res.setHeader('Content-Range', `bytes ${inicio}-${fin}/${b.length}`); }
    res.setHeader('Content-Length', fin - inicio + 1); res.end(b.subarray(inicio, fin + 1)); return;
  }
  res.setHeader('Content-Type', 'text/html'); res.end('<!doctype html><html lang="es"><title>Medición local de audio</title></html>');
});
await new Promise(r => servidor.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${servidor.address().port}`;
const perfil = mkdtempSync(join(tmpdir(), 'sm-comparar-'));
const puerto = 9589;
const navegador = spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', ['--headless=new', '--no-first-run',
  `--user-data-dir=${perfil}`, `--remote-debugging-port=${puerto}`, '--autoplay-policy=no-user-gesture-required', 'about:blank'], { stdio: 'ignore' });
const esperar = ms => new Promise(r => setTimeout(r, ms));
let ws;
try {
  let pagina;
  for (let i = 0; i < 80; i++) { try { pagina = (await (await fetch(`http://127.0.0.1:${puerto}/json`)).json()).find(x => x.type === 'page'); if (pagina) break; } catch {} await esperar(200); }
  ws = new WebSocket(pagina.webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r));
  let seq = 0; const pendientes = new Map();
  ws.addEventListener('message', e => { const d = JSON.parse(e.data); if (d.id) { pendientes.get(d.id)?.(d); pendientes.delete(d.id); } });
  const cmd = (method, params = {}) => new Promise((resolve, reject) => { const id = ++seq;
    pendientes.set(id, d => d.error ? reject(new Error(d.error.message)) : resolve(d.result)); ws.send(JSON.stringify({ id, method, params })); });
  const js = async codigo => { const r = await cmd('Runtime.evaluate', { expression: `(async()=>{${codigo}})()`, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'Error JS'); return r.result.value; };
  await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Network.enable');
  await cmd('Network.setCacheDisabled', { cacheDisabled: true });
  await cmd('Page.navigate', { url: base }); await esperar(600);
  const salida = { condiciones: 'Edge sin ventana, 1.6 Mbps y 120 ms de latencia, 5 muestras por caso; localhost', resultados: {} };
  for (const m of ['antes', 'despues']) {
    modo = m;
    await cmd('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await js(`for(const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
      for(const k of await caches.keys()) await caches.delete(k);
      await navigator.serviceWorker.register('/sw.js?modo=${m}'); await navigator.serviceWorker.ready;`);
    await cmd('Page.navigate', { url: base }); await esperar(600);
    await cmd('Network.emulateNetworkConditions', { offline: false, latency: 120, downloadThroughput: 200000, uploadThroughput: 93750 });
    const casos = {};
    for (const caliente of [false, true]) {
      await cmd('Network.setBypassServiceWorker', { bypass: !caliente });
      if (caliente) {
        // Se guarda la copia como lo hace la app, antes de que se construya el índice del worker.
        await js(`const r=await fetch('/api/canciones/1/audio'); await (await caches.open('sm-audio')).put('/offline/audio/1',r);
          navigator.serviceWorker.controller.postMessage({tipo:'quitar-descarga',id:99999999});
          await new Promise(r=>setTimeout(r,300));`);
      }
      const solicitudesAntes = peticionesAudio;
      const medidas = [];
      for (let n = 0; n < 5; n++) {
        const medida = await js(`return await new Promise(resolve=>{
          const a=new Audio(); const t=performance.now(); let fin=false;
          const acabar=(error)=>{if(fin)return;fin=true;clearTimeout(reloj);const ms=performance.now()-t;
            a.pause(); a.removeAttribute('src'); a.load(); resolve({ms,error});};
          const reloj=setTimeout(()=>acabar('timeout'),20000);
          a.onplaying=()=>acabar(null); a.onerror=()=>acabar('audio '+a.error?.code);
          a.src='/api/canciones/1/audio'; a.play().catch(e=>acabar(e.name));
        });`);
        if (medida.error) throw new Error(`${m}: ${medida.error}`);
        medidas.push(Math.round(medida.ms));
      }
      const orden = [...medidas].sort((a, b) => a - b);
      const solicitudes = peticionesAudio - solicitudesAntes;
      if (caliente && solicitudes) throw new Error('La muestra guardada tocó la red: medición no válida');
      casos[caliente ? 'guardada' : 'frio'] = { p50: orden[2], p95: orden[4], muestras: medidas, solicitudesAudioRed: solicitudes };
    }
    salida.resultados[m] = casos;
    console.log(m, JSON.stringify(casos));
  }
  mkdirSync('pruebas/resultados', { recursive: true });
  writeFileSync('pruebas/resultados/audio-3.6.1.json', JSON.stringify(salida, null, 2) + '\n');
} finally {
  ws?.close(); servidor.closeAllConnections(); servidor.close();
  try { execFileSync('taskkill', ['/PID', String(navegador.pid), '/T', '/F'], { stdio: 'ignore' }); } catch {}
  await esperar(800); try { rmSync(perfil, { recursive: true, force: true }); } catch {}
}
