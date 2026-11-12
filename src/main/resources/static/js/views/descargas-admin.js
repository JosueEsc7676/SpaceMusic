// Administración → Descargar música: lo mismo que "Descargar musica.bat", desde la web.
// Pega enlaces (Spotify, YouTube, SoundCloud…) o "Artista - Canción"; se bajan en segundo plano
// en el servidor y aquí se ve todo en vivo: cola, progreso, registro, fallidas y Spotify.
import { api } from '../api.js';
import { esc, bus, timeAgo, store } from '../util.js';
import { icon } from '../icons.js';
import { toast, errorToast, confirmDialog, modal, emptyState } from '../ui.js';
import { iniciarBusqueda } from './descargas-buscar.js';

let reloj = null;
bus.on('route', () => { clearTimeout(reloj); reloj = null; });

const BORRADOR = 'sm_descargas_borrador';
const tipoDe = (l) => /spotify\.com/i.test(l) ? 'Spotify' : /youtu\.?be|music\.youtube/i.test(l) ? 'YouTube'
  : /soundcloud/i.test(l) ? 'SoundCloud' : /^https?:\/\//i.test(l) ? 'Enlace' : 'Búsqueda';

/** Entradas que se enviarán (igual que el servidor: varios enlaces en una línea se separan). */
function entradas(texto) {
  const r = [];
  for (const l of texto.replace(/\r/g, '').split('\n')) {
    const t = l.trim();
    if (!t) continue;
    if (/^https?:\/\//i.test(t) && t.includes(' ')) r.push(...t.split(/\s+/));
    else r.push(t);
  }
  return r;
}

export async function descargasTab(el) {
  el.innerHTML = `<div class="dl">
    <div class="dl-estado" data-estado><p class="muted">Cargando…</p></div>
    <section class="panel dl-yt" data-yt></section>
    <div class="dl-grid">
      <section class="panel dl-pegar">
        <h3>${icon('download')} Agregar a la cola</h3>
        <p class="muted" style="margin-top:0">Pega los enlaces que quieras, sin límite: uno por línea o varios en una línea separados por espacio.
          También puedes escribir <b>Artista - Canción</b>. Lo ya descargado no se repite.</p>
        <div class="dl-fuentes">${['Spotify (canción, álbum, playlist o artista)', 'YouTube y YouTube Music', 'SoundCloud y otros'].map((t) => `<span class="badge">${esc(t)}</span>`).join('')}</div>
        <textarea data-texto rows="8" placeholder="https://open.spotify.com/album/…&#10;https://www.youtube.com/watch?v=…&#10;Bad Bunny - Tití Me Preguntó" spellcheck="false"></textarea>
        <div class="dl-previa" data-previa></div>
        <div class="row wrap" style="gap:8px;justify-content:flex-end">
          <button class="btn ghost sm" data-vaciar>Vaciar</button>
          <button class="btn primary" data-encolar>${icon('download', 'sm')} Agregar a la cola</button>
        </div>
        <p class="muted" style="font-size:12px;margin-bottom:0">La descarga sigue en el servidor aunque cierres esta página o apagues el equipo.</p>
      </section>
      <section class="panel dl-cola-p"><h3>${icon('queue')} Cola</h3><div data-cola></div>
        <h3 style="margin-top:20px">${icon('checkCircle')} Terminadas</h3><div data-hechas></div></section>
    </div>
    <section class="panel"><div class="row" style="justify-content:space-between;gap:8px;flex-wrap:wrap">
        <h3 style="margin:0">${icon('info')} Fallidas</h3><div class="row" style="gap:8px" data-acc-fallidas></div></div>
      <div data-fallidas></div></section>
    <section class="panel"><div class="row" style="justify-content:space-between;gap:8px;flex-wrap:wrap">
        <h3 style="margin:0">${icon('text')} Registro en vivo</h3>
        <div class="row" style="gap:8px"><input class="dl-filtro" data-filtro placeholder="Filtrar líneas" aria-label="Filtrar registro">
          <label class="row" style="gap:6px;font-size:13px"><input type="checkbox" data-seguir checked> Seguir</label></div></div>
      <pre class="dl-log" data-log></pre></section>
    <section class="panel"><h3>${icon('sliders')} Cuenta de Spotify</h3><div data-spotify></div></section>
  </div>`;
  const $ = (s) => el.querySelector(s);
  iniciarBusqueda($('[data-yt]'), () => cargar(true));
  const texto = $('[data-texto]');
  try { texto.value = store.get(BORRADOR, '') || ''; } catch { }

  const previa = () => {
    const l = entradas(texto.value);
    store.set(BORRADOR, texto.value);
    if (!l.length) { $('[data-previa]').innerHTML = ''; return; }
    const tipos = {};
    l.forEach((x) => { const t = tipoDe(x); tipos[t] = (tipos[t] || 0) + 1; });
    const malas = l.filter((x) => x.startsWith('-') || /^[a-z]+:\/\//i.test(x) && !/^https?:\/\//i.test(x));
    const rep = l.length - new Set(l).size;
    $('[data-previa]').innerHTML = `<b>${l.length} entrada(s)</b> · ${Object.entries(tipos).map(([t, n]) => `${n} ${esc(t)}`).join(' · ')}`
      + (rep ? ` · <span class="muted">${rep} repetida(s) se envían una vez</span>` : '')
      + (malas.length ? `<div class="dl-mal">${icon('info', 'sm')} No válidas: ${malas.slice(0, 3).map(esc).join(', ')}</div>` : '');
  };
  texto.addEventListener('input', previa);
  previa();
  $('[data-vaciar]').onclick = () => { texto.value = ''; previa(); texto.focus(); };
  $('[data-encolar]').onclick = async (e) => {
    const b = e.currentTarget;
    if (!entradas(texto.value).length) { toast('Pega al menos un enlace', { error: true }); return; }
    b.disabled = true;
    try {
      const r = await api('/api/admin/descargas', { method: 'POST', body: { texto: texto.value } });
      toast(`${r.agregadas} entrada(s) agregadas a la cola`);
      texto.value = '';
      previa();
      cargar(true);
    } catch (err) { errorToast(err); } finally { b.disabled = false; }
  };

  let ultimo = null;
  const accion = async (ruta, metodo = 'POST', ok) => {
    try {
      const r = await api(ruta, { method: metodo });
      toast(r?.mensaje || ok || 'Listo');
      cargar(true);
    } catch (err) { errorToast(err); }
  };

  const pintar = (r) => {
    if (!r.instalado) {
      el.querySelector('.dl').innerHTML = emptyState('download', 'Descargas no disponibles en este servidor', r.nota || '');
      return false;
    }
    const p = r.progreso || {};
    const enCurso = r.cola.find((c) => c.enCurso);
    const pct = p.de ? Math.round(100 * (p.actual - 1) / p.de) : 0;
    $('[data-estado]').innerHTML = `<div class="dl-estado-c ${r.activo ? 'on' : ''}">
      <div class="dl-estado-t"><span class="sop-chip ${r.activo ? 'warn' : r.cola.length ? 'gray' : 'ok'}">${r.activo ? 'Descargando' : r.cola.length ? 'En pausa' : 'Sin descargas pendientes'}</span>
        ${r.activo && p.entrada ? `<b class="ellipsis">${esc(p.entrada)}</b>` : `<span class="muted">${r.cola.length ? `${r.cola.length} lista(s) esperando` : 'Todo al día'}</span>`}</div>
      ${r.activo && p.de ? `<div class="dl-barra"><i style="width:${pct}%"></i></div>
        <div class="muted" style="font-size:13px">Entrada ${p.actual} de ${p.de}${enCurso ? ` · lista con ${enCurso.entradas} entrada(s)` : ''}${p.inicio ? ` · empezó ${esc(p.inicio)}` : ''}</div>` : ''}
      ${r.spotifyLimitadoHasta ? `<div class="dl-aviso">${icon('info', 'sm')} Spotify limitó tus claves hasta ${esc(new Date(r.spotifyLimitadoHasta).toLocaleString())}. Mientras tanto se usa el modo sin claves (más lento, pero sigue).</div>` : ''}
      <div class="row wrap" style="gap:8px;margin-top:10px">
        ${r.activo ? `<button class="btn sm danger" data-detener>${icon('pause', 'sm')} Detener</button>`
          : r.cola.length ? `<button class="btn sm primary" data-reanudar>${icon('play', 'sm')} Reanudar</button>` : ''}
        <button class="btn sm" data-refrescar>${icon('refresh', 'sm')} Actualizar</button>
      </div></div>`;
    $('[data-detener]')?.addEventListener('click', async () => {
      if (await confirmDialog('Se detiene lo que se está bajando. La lista queda en la cola y lo ya bajado no se repite al reanudar.', { title: '¿Detener descargas?', ok: 'Detener', danger: true })) accion('/api/admin/descargas/detener');
    });
    $('[data-reanudar]')?.addEventListener('click', () => accion('/api/admin/descargas/reanudar'));
    $('[data-refrescar]').addEventListener('click', () => cargar(true));

    $('[data-cola]').innerHTML = r.cola.length ? `<div class="dl-lista">${r.cola.map((c) => `<div class="dl-item">
        <div style="min-width:0;flex:1"><b>${c.entradas} entrada(s)</b> <span class="muted">· ${esc(timeAgo(c.fecha))}</span>
          <div class="muted ellipsis" style="font-size:13px">${c.muestra.map(esc).join(' · ')}</div></div>
        ${c.enCurso ? '<span class="sop-chip warn">En curso</span>' : `<button class="btn sm ghost" data-quitar="${esc(c.nombre)}">Quitar</button>`}</div>`).join('')}</div>`
      : '<p class="muted">La cola está vacía.</p>';
    el.querySelectorAll('[data-quitar]').forEach((b) => b.addEventListener('click', async () => {
      if (!(await confirmDialog('Esa lista no se descargará.', { title: '¿Quitar de la cola?', ok: 'Quitar', danger: true }))) return;
      accion('/api/admin/descargas/cola/' + encodeURIComponent(b.dataset.quitar), 'DELETE', 'Lista quitada');
    }));
    $('[data-hechas]').innerHTML = r.terminadas.length ? `<div class="dl-lista">${r.terminadas.map((c) => `<div class="dl-item"><span>${c.entradas} entrada(s)</span><span class="muted">${esc(timeAgo(c.fecha))}</span></div>`).join('')}</div>`
      : '<p class="muted">Todavía no hay listas terminadas.</p>';

    $('[data-acc-fallidas]').innerHTML = r.fallidas.length ? `<button class="btn sm primary" data-reintentar>${icon('refresh', 'sm')} Reintentar todas</button>
      <button class="btn sm ghost" data-olvidar>Olvidar</button>` : '';
    $('[data-fallidas]').innerHTML = r.fallidas.length ? `<p class="muted">${r.fallidas.length} entrada(s) no se pudieron bajar después de 3 intentos. Suele ser algo pasajero: reinténtalas más tarde.</p>
      <div class="dl-fallidas">${r.fallidas.map((f) => `<code>${esc(f)}</code>`).join('')}</div>` : '<p class="muted">Nada falló. 🎉</p>';
    $('[data-reintentar]')?.addEventListener('click', () => accion('/api/admin/descargas/reintentar'));
    $('[data-olvidar]')?.addEventListener('click', async () => {
      if (await confirmDialog('Se olvida la lista de fallidas (no se borra ninguna canción).', { title: '¿Olvidar fallidas?', ok: 'Olvidar' })) accion('/api/admin/descargas/fallidas', 'DELETE', 'Lista de fallidas vacía');
    });

    ultimo = r.registro;
    pintarLog();
    $('[data-spotify]').innerHTML = `<p style="margin-top:0">${r.spotifyConfigurado ? `<span class="sop-chip ok">Configurada</span> Las descargas de Spotify usan tus propias claves.` : `<span class="sop-chip gray">Sin configurar</span> Con tus claves (gratis) Spotify bloquea menos las descargas.`}</p>
      <button class="btn sm" data-spot>${icon('edit', 'sm')} ${r.spotifyConfigurado ? 'Cambiar claves' : 'Configurar'}</button>`;
    $('[data-spot]').addEventListener('click', configurarSpotify);
    return r.activo;
  };

  const pintarLog = () => {
    const pre = $('[data-log]');
    if (!pre || !ultimo) return;
    const f = $('[data-filtro]').value.trim().toLowerCase();
    const lineas = f ? ultimo.filter((l) => l.toLowerCase().includes(f)) : ultimo;
    pre.innerHTML = lineas.length ? lineas.map((l) => {
      const c = /^✓|Todo descargado/.test(l) ? 'ok' : /^✗|Error|error|Falló|falló|fallaron/.test(l) ? 'bad' : /^▶|════/.test(l) ? 'tit' : /Reintentando|pendiente|limitó/.test(l) ? 'warn' : '';
      return `<span class="${c}">${esc(l)}</span>`;
    }).join('\n') : '<span class="muted">Todavía no hay registro.</span>';
    if ($('[data-seguir]').checked) pre.scrollTop = pre.scrollHeight;
  };
  $('[data-filtro]').addEventListener('input', pintarLog);

  const cargar = async (ahora = false) => {
    clearTimeout(reloj);
    let activo = false;
    try {
      const r = await api('/api/admin/descargas?lineas=200');
      if (!el.isConnected && !ahora) return;
      if (pintar(r) === false) return;
      activo = r.activo;
    } catch (err) { if (ahora) errorToast(err); }
    // En vivo: cada 3 s mientras descarga; cada 15 s si no
    reloj = setTimeout(() => { if (el.isConnected) cargar(); }, activo ? 3000 : 15000);
  };
  cargar(true);
}

async function configurarSpotify() {
  await modal({
    title: 'Claves de Spotify',
    body: `<ol class="dl-pasos">
        <li>Entra a <b>developer.spotify.com/dashboard</b> e inicia sesión con tu cuenta.</li>
        <li>“Create app”: nombre SpaceMusic, Redirect URI <code>http://127.0.0.1:8080</code>, marca “Web API”.</li>
        <li>En la app, abre “Settings” y copia el Client ID y el Client secret.</li></ol>
      <div class="field"><label>Client ID</label><input data-id autocomplete="off" spellcheck="false"></div>
      <div class="field"><label>Client secret</label><input data-secreto type="password" autocomplete="new-password" spellcheck="false"></div>
      <p class="muted" style="font-size:12px;margin:0">Se guardan solo en el servidor y no se vuelven a mostrar.</p>`,
    actions: [{ label: 'Cancelar', value: null }, { label: 'Guardar', value: 'ok', primary: true }],
    onAction: async (v, m) => {
      if (v !== 'ok') return;
      await api('/api/admin/descargas/spotify', { method: 'PUT', body: { clientId: m.querySelector('[data-id]').value, clientSecret: m.querySelector('[data-secreto]').value } });
      toast('Claves guardadas. Las descargas de Spotify ahora usan tu cuenta.');
    },
  });
}
