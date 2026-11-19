// Dedicatorias: "una canción para este momento".
// Dedicar en tres pasos: para quién (lista y buscador), qué parte (toda la canción o bloques de la letra)
// y mensaje con una tarjeta (imagen) que se genera con la portada, el artista y la letra elegida.
import { api, session } from './api.js';
import { esc, fmtTime, coverImg, cover, timeAgo } from './util.js';
import { icon } from './icons.js';
import { setHero, setTitle } from './router.js';
import { toast, errorToast, emptyState, trackList, modal, promptDialog, confirmDialog } from './ui.js';
import { player } from './player.js';

const fechaLarga = (ms) => new Date(ms).toLocaleString('es', { weekday: 'long', day: 'numeric', month: 'long', hour: 'numeric', minute: '2-digit' });
const fechaCorta = (ms) => new Date(ms).toLocaleString('es', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });

const avatar = (p, cls = '') => p?.fotoPerfil
  ? `<img class="ded-av ${cls}" src="${esc(p.fotoPerfil)}" alt="" loading="lazy">`
  : `<span class="ded-av ded-av-ini ${cls}">${esc((p?.nombre || p?.username || '?').slice(0, 1).toUpperCase())}</span>`;

// ═══════════════════ Letra en bloques ═══════════════════

/**
 * Divide la letra en bloques (estrofas). Con letra sincronizada cada línea trae su segundo:
 * un bloque se corta en una línea vacía, en un silencio de más de 6 s o a las 4 líneas.
 */
/** Sin marcas de tiempo de línea [00:12.30] ni de palabra <00:12.30> (letras "mejoradas"). */
const limpiarLinea = (l) => l.replace(/\[[^\]]*]|<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>/g, '').replace(/\s{2,}/g, ' ').trim();

export function bloquesDeLetra(letra, lrc) {
  const lineas = [];
  if (lrc && /\[\d{1,3}:\d{2}/.test(lrc)) {
    for (const l of lrc.split(/\r?\n/)) {
      const m = /^\s*\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)$/.exec(l);
      if (!m) continue;
      const t = +m[1] * 60 + +m[2] + (m[3] ? +('0.' + m[3]) : 0);
      lineas.push({ t, texto: limpiarLinea(m[4]) });
    }
  } else {
    for (const l of (letra || '').split(/\r?\n/)) lineas.push({ t: null, texto: limpiarLinea(l) });
  }
  const bloques = [];
  let actual = [];
  const cerrar = () => { if (actual.length) bloques.push(actual); actual = []; };
  for (let i = 0; i < lineas.length; i++) {
    const l = lineas[i];
    if (!l.texto) { cerrar(); continue; }
    const prev = actual[actual.length - 1];
    if (prev && ((l.t != null && prev.t != null && l.t - prev.t > 6) || actual.length >= 4)) cerrar();
    actual.push(l);
  }
  cerrar();
  // El fin de cada línea es el inicio de la siguiente
  const todas = bloques.flat();
  todas.forEach((l, i) => { l.fin = todas[i + 1]?.t ?? (l.t != null ? l.t + 5 : null); });
  return bloques;
}

// ═══════════════════ Tarjeta (imagen) de la dedicatoria ═══════════════════

const cargarImagen = (src) => new Promise((res) => {
  if (!src) return res(null);
  const i = new Image();
  i.onload = () => res(i);
  i.onerror = () => res(null);
  i.src = src;
});

function partirTexto(g, texto, ancho) {
  const palabras = texto.split(/\s+/), r = [];
  let linea = '';
  for (const p of palabras) {
    const prueba = linea ? linea + ' ' + p : p;
    if (g.measureText(prueba).width > ancho && linea) { r.push(linea); linea = p; } else linea = prueba;
  }
  if (linea) r.push(linea);
  return r;
}

/** Imagen 1080×1350 con la portada, la canción, el artista y la letra dedicada (o "canción completa"). */
export async function tarjetaDedicatoria({ cancion, lineas = [], completa = false, de, para, mensaje, encabezado }) {
  const W = 1080, H = 1350;
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const g = c.getContext('2d');
  const fuente = getComputedStyle(document.body).fontFamily || 'sans-serif';
  const img = await cargarImagen(cancion.portadaUrl ? cover(cancion.portadaUrl, 640) : null);
  // Fondo: la portada muy ampliada y oscurecida
  g.fillStyle = '#0b0b0e';
  g.fillRect(0, 0, W, H);
  if (img) {
    g.save();
    g.filter = 'blur(60px) saturate(1.3)';
    g.globalAlpha = 0.85;
    g.drawImage(img, -200, -200, W + 400, H + 400);
    g.restore();
  }
  const sombra = g.createLinearGradient(0, 0, 0, H);
  sombra.addColorStop(0, 'rgba(0,0,0,.25)');
  sombra.addColorStop(0.55, 'rgba(0,0,0,.55)');
  sombra.addColorStop(1, 'rgba(0,0,0,.85)');
  g.fillStyle = sombra;
  g.fillRect(0, 0, W, H);
  // Portada con esquinas redondeadas
  const lado = 300, x0 = 80, y0 = 110;
  if (img) {
    g.save();
    g.beginPath();
    if (g.roundRect) g.roundRect(x0, y0, lado, lado, 28); else g.rect(x0, y0, lado, lado);
    g.clip();
    g.drawImage(img, x0, y0, lado, lado);
    g.restore();
  }
  g.fillStyle = 'rgba(255,255,255,.7)';
  g.font = `600 30px ${fuente}`;
  g.fillText(encabezado || (para ? `Para @${para}` : 'Una canción para ti'), x0 + lado + 40, y0 + 60);
  g.fillStyle = '#fff';
  g.font = `800 56px ${fuente}`;
  const titulo = partirTexto(g, cancion.nombre, W - (x0 + lado + 40) - 70).slice(0, 2);
  titulo.forEach((t, i) => g.fillText(t, x0 + lado + 40, y0 + 140 + i * 64));
  g.fillStyle = 'rgba(255,255,255,.75)';
  g.font = `500 36px ${fuente}`;
  g.fillText(cancion.artista, x0 + lado + 40, y0 + 150 + titulo.length * 64);
  // Letra elegida, grande, con comillas
  let y = 560;
  if (completa || !lineas.length) {
    g.fillStyle = '#fff';
    g.font = `800 64px ${fuente}`;
    for (const t of partirTexto(g, completa ? 'Te dedico esta canción completa' : 'Te dedico esta canción', W - 160)) { g.fillText(t, 80, y); y += 80; }
  } else {
    g.fillStyle = 'rgba(255,255,255,.35)';
    g.font = `800 140px ${fuente}`;
    g.fillText('“', 64, y + 30);
    y += 70;
    g.fillStyle = '#fff';
    let tam = lineas.join(' ').length > 220 ? 44 : lineas.join(' ').length > 120 ? 52 : 60;
    g.font = `700 ${tam}px ${fuente}`;
    const filas = lineas.flatMap((l) => partirTexto(g, l, W - 160));
    for (const t of filas.slice(0, 11)) { g.fillText(t, 80, y); y += tam * 1.32; }
  }
  if (mensaje) {
    y = Math.max(y + 30, 1060);
    g.fillStyle = 'rgba(255,255,255,.75)';
    g.font = `italic 500 34px ${fuente}`;
    for (const t of partirTexto(g, mensaje, W - 160).slice(0, 2)) { g.fillText(t, 80, y); y += 46; }
  }
  g.fillStyle = 'rgba(255,255,255,.6)';
  g.font = `600 30px ${fuente}`;
  g.fillText(`${de ? 'De @' + de + ' · ' : ''}SpaceMusic`, 80, H - 70);
  return new Promise((res) => c.toBlob(res, 'image/png'));
}

/** Imagen → base64 (sin el prefijo «data:») para pasarla a la app de Android. */
function aBase64(blob) {
  return new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(String(r.result).split(',')[1] || '');
    r.onerror = () => rej(r.error);
    r.readAsDataURL(blob);
  });
}

export async function compartirImagen(blob, nombre) {
  // App de Android: la vista web no guarda enlaces «blob:»; la app la guarda en la galería y abre «Compartir»
  if (window.SpaceMusicApp?.guardarImagen) {
    try {
      const ok = window.SpaceMusicApp.guardarImagen(await aBase64(blob), nombre, true) === 'ok';
      toast(ok ? 'Imagen guardada en tu galería (álbum SpaceMusic)' : 'No se pudo guardar la imagen');
      if (ok) return;
    } catch { toast('No se pudo guardar la imagen'); }
  }
  const archivo = new File([blob], nombre, { type: 'image/png' });
  if (navigator.canShare?.({ files: [archivo] })) {
    try { await navigator.share({ files: [archivo] }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = nombre;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
}

// ═══════════════════ Dedicar (tres pasos) ═══════════════════

export async function dedicarCancion(song, { para: paraInicial = null } = {}) {
  const enCurso = player.current?.id === song.id ? Math.floor(player.audio.currentTime || 0) : 0;
  let personas = [], para = null, bloques = [], completa = false, desde = null, hasta = null, previa = null;
  try {
    const [p, l] = await Promise.all([api('/api/dedicatorias/personas'), api(`/api/canciones/${song.id}/letra`).catch(() => ({}))]);
    personas = p;
    bloques = bloquesDeLetra(l.letra, l.letraSincronizada);
  } catch (e) { errorToast(e); return; }
  const conTiempos = bloques.some((b) => b[0]?.t != null);
  if (!bloques.length) completa = true;

  const elegidas = () => (desde == null ? [] : bloques.slice(desde, (hasta ?? desde) + 1).flat());

  await modal({
    title: 'Dedicar esta canción',
    wide: true,
    body: `<div class="ded">
      <div class="ded-cancion">${coverImg(song.portadaUrl, 160, '', 'ded-portada')}
        <div class="ded-cancion-txt"><b class="ellipsis">${esc(song.nombre)}</b><span class="muted ellipsis">${esc(song.artista)}</span></div></div>
      <ol class="ded-pasos" aria-label="Pasos">
        <li data-p="1" class="activo">Para quién</li><li data-p="2">Qué parte</li><li data-p="3">Mensaje</li></ol>
      <section data-paso="1">
        <input class="ded-buscar" data-q type="search" placeholder="Buscar por nombre o usuario" aria-label="Buscar persona" autocomplete="off">
        <div class="ded-personas" data-personas role="listbox" aria-label="Personas"></div>
        <p class="muted ded-nota">Aparecen las personas que te siguen; primero las que también sigues.</p>
      </section>
      <section data-paso="2" hidden>
        <button class="ded-completa ${completa ? 'sel' : ''}" data-completa aria-pressed="${completa}">${icon('note')}<div><b>Toda la canción</b><span>Verá «Te dedicaron esta canción completa»</span></div></button>
        ${bloques.length ? `<p class="muted ded-nota">O toca un bloque de la letra. Toca otro para elegir hasta dónde llega.</p>
          <div class="ded-bloques" data-bloques>${bloques.map((b, i) => `<button class="ded-bloque" data-b="${i}" aria-pressed="false">
            ${b[0].t != null ? `<span class="ded-t">${fmtTime(b[0].t)}</span>` : ''}${b.map((l) => `<span class="ded-linea">${esc(l.texto)}</span>`).join('')}</button>`).join('')}</div>`
          : `<p class="muted ded-nota">Esta canción no tiene letra: se dedicará completa${enCurso ? '' : ''}.</p>`}
        <div class="ded-rango" data-rango aria-live="polite"></div>
      </section>
      <section data-paso="3" hidden>
        <textarea class="ded-msg" data-msg maxlength="280" placeholder="Por qué esta canción, por qué este momento (opcional)"></textarea>
        <div class="ded-cuando" role="radiogroup" aria-label="Cuándo llega">
          <button type="button" class="ded-cuando-op sel" data-cuando="ahora" aria-pressed="true">${icon('send', 'sm')} Ahora</button>
          <button type="button" class="ded-cuando-op" data-cuando="programar" aria-pressed="false">${icon('clock', 'sm')} Programar</button>
          <input type="datetime-local" data-fecha hidden aria-label="Fecha y hora en que llega">
        </div>
        <div class="ded-previa" data-previa><p class="muted">Preparando la tarjeta…</p></div>
      </section>
      <p class="muted ded-nota">Se envía solo cuando pulsas Enviar.</p>
    </div>`,
    actions: [{ label: 'Atrás', value: 'atras' }, { label: 'Siguiente', value: 'sig', primary: true }],
    onOpen: (m) => {
      m.classList.add('ded-modal');
      const $ = (s) => m.querySelector(s);
      let paso = 1;
      const botones = () => {
        const [atras, sig] = m.querySelectorAll('.foot button');
        atras.style.visibility = paso === 1 ? 'hidden' : 'visible';
        sig.textContent = paso === 3 ? 'Enviar' : 'Siguiente';
        sig.disabled = (paso === 1 && !para) || (paso === 2 && !completa && desde == null);
      };
      m._ir = (n) => {
        paso = n;
        m.querySelectorAll('[data-paso]').forEach((s) => { s.hidden = +s.dataset.paso !== n; });
        m.querySelectorAll('.ded-pasos li').forEach((li) => { li.classList.toggle('activo', +li.dataset.p === n); li.classList.toggle('hecho', +li.dataset.p < n); });
        if (n === 3) generarPrevia();
        botones();
      };
      m._paso = () => paso;
      const pintarPersonas = (q = '') => {
        const f = q.trim().toLowerCase().replace(/^@/, '');
        const l = personas.filter((p) => !f || (p.username + ' ' + p.nombre).toLowerCase().includes(f));
        $('[data-personas]').innerHTML = l.length ? l.map((p) => `<button class="ded-persona ${para === p.username ? 'sel' : ''}" data-u="${esc(p.username)}" role="option" aria-selected="${para === p.username}">
            ${avatar(p)}<span class="ded-persona-txt"><b class="ellipsis">${esc(p.nombre)}</b><span class="muted ellipsis">@${esc(p.username)}</span></span>
            ${p.mutuo ? '<span class="sop-chip ok">Se siguen</span>' : ''}${para === p.username ? icon('check', 'sm') : ''}</button>`).join('')
          : `<p class="muted">${personas.length ? 'Nadie coincide con la búsqueda.' : 'Todavía nadie te sigue. Solo puedes dedicar canciones a personas que te siguen.'}</p>`;
        m.querySelectorAll('[data-u]').forEach((b) => b.onclick = () => { para = b.dataset.u; pintarPersonas($('[data-q]').value); botones(); });
      };
      $('[data-q]').addEventListener('input', (e) => pintarPersonas(e.target.value));
      // Desde una conversación: la persona ya viene elegida (si puede recibir dedicatorias) y se pasa al paso 2
      if (paraInicial && personas.some((p) => p.username === paraInicial)) { para = paraInicial; setTimeout(() => m._ir(2), 0); }
      pintarPersonas();

      const pintarSeleccion = () => {
        m.querySelectorAll('[data-b]').forEach((b) => {
          const i = +b.dataset.b, sel = !completa && desde != null && i >= desde && i <= (hasta ?? desde);
          b.classList.toggle('sel', sel);
          b.classList.toggle('borde-ini', sel && i === desde);
          b.classList.toggle('borde-fin', sel && i === (hasta ?? desde));
          b.setAttribute('aria-pressed', String(sel));
        });
        $('[data-completa]').classList.toggle('sel', completa);
        $('[data-completa]').setAttribute('aria-pressed', String(completa));
        const ls = elegidas();
        $('[data-rango]').innerHTML = completa ? `${icon('checkCircle', 'sm')} Toda la canción`
          : ls.length ? `${icon('checkCircle', 'sm')} ${ls.length} línea(s)${conTiempos && ls[0].t != null ? ` · de ${fmtTime(ls[0].t)} a ${fmtTime(ls[ls.length - 1].fin)}` : ''}` : '';
        botones();
      };
      $('[data-completa]').onclick = () => { completa = !completa; if (completa) { desde = hasta = null; } pintarSeleccion(); };
      m.querySelectorAll('[data-b]').forEach((b) => b.onclick = () => {
        const i = +b.dataset.b;
        completa = false;
        if (desde == null || hasta != null || i < desde) { desde = i; hasta = null; }  // nuevo inicio
        else if (i === desde) { desde = null; }                                      // quitar
        else if (i - desde > 3) { toast('Elige hasta 4 bloques seguidos'); }
        else hasta = i;                                                               // fin del fragmento
        pintarSeleccion();
      });
      pintarSeleccion();

      let tGen;
      const generarPrevia = () => {
        clearTimeout(tGen);
        tGen = setTimeout(async () => {
          const blob = await tarjetaDedicatoria({ cancion: song, lineas: elegidas().map((l) => l.texto), completa, de: session.user?.username, para, mensaje: $('[data-msg]').value.trim() });
          if (previa) URL.revokeObjectURL(previa);
          previa = URL.createObjectURL(blob);
          $('[data-previa]').innerHTML = `<img src="${previa}" alt="Tarjeta de la dedicatoria" class="ded-tarjeta">`;
        }, 250);
      };
      $('[data-msg]').addEventListener('input', generarPrevia);
      // Programar: llega (y se avisa) en la fecha elegida; antes, la otra persona no la ve
      const fecha = $('[data-fecha]');
      const dosDig = (n) => String(n).padStart(2, '0');
      const local = (d) => `${d.getFullYear()}-${dosDig(d.getMonth() + 1)}-${dosDig(d.getDate())}T${dosDig(d.getHours())}:${dosDig(d.getMinutes())}`;
      const manana = new Date(Date.now() + 86400000); manana.setHours(9, 0, 0, 0);
      fecha.value = local(manana);
      fecha.min = local(new Date(Date.now() + 5 * 60000));
      fecha.max = local(new Date(Date.now() + 365 * 86400000));
      m.querySelectorAll('[data-cuando]').forEach((b) => b.onclick = () => {
        m.querySelectorAll('[data-cuando]').forEach((x) => { x.classList.toggle('sel', x === b); x.setAttribute('aria-pressed', String(x === b)); });
        fecha.hidden = b.dataset.cuando !== 'programar';
        m._programar = b.dataset.cuando === 'programar';
      });
      m._ir(1);
    },
    onAction: async (v, m) => {
      const paso = m._paso();
      if (v === 'atras') { m._ir(Math.max(1, paso - 1)); return false; }
      if (paso < 3) { m._ir(paso + 1); return false; }
      const ls = elegidas();
      let programadaPara = null;
      if (m._programar) {
        programadaPara = new Date(m.querySelector('[data-fecha]').value).getTime();
        if (!(programadaPara > Date.now() + 60000)) { toast('Elige una fecha y hora futura', { error: true }); return false; }
      }
      const cuerpo = {
        programadaPara,
        username: para, cancionId: song.id, mensaje: m.querySelector('[data-msg]').value, completa,
        lineas: completa ? [] : ls.map((l) => l.texto),
        segundo: completa ? 0 : ls[0]?.t != null ? Math.floor(ls[0].t) : enCurso,
        segundoFin: !completa && ls.length && ls[ls.length - 1].fin != null ? Math.ceil(ls[ls.length - 1].fin) : null,
      };
      const r = await api('/api/dedicatorias', { method: 'POST', body: cuerpo });
      toast(r?.programada ? `Programada: @${para} la recibirá el ${fechaLarga(programadaPara)}` : `Dedicatoria enviada a @${para}`);
    },
  });
  if (previa) URL.revokeObjectURL(previa);
}

// ═══════════════════ Vista de dedicatorias ═══════════════════

export async function dedicatoriasView(el, params) {
  setHero([200, 70, 110]);
  setTitle('Dedicatorias');
  if (params?.id) return detalle(el, params);
  const l = await api('/api/dedicatorias');
  const guardadas = l.recibidas.filter((d) => d.guardada && d.cancion);
  const item = (d) => `<div class="ded-fila" data-fila="${d.id}"><a class="ded-item" href="#/dedicatorias/${d.id}">
      <span class="ded-item-art">${d.cancion ? coverImg(d.cancion.portadaUrl, 160) : icon('note')}</span>
      <span class="ded-item-txt"><b class="ellipsis">${esc(d.cancion?.nombre || 'Canción eliminada')}</b>
        <span class="muted ellipsis">${d.recibida ? 'De' : 'Para'} ${esc(d.persona.nombre || '')} · ${esc(timeAgo(d.fecha))}</span>
        ${d.letra ? `<span class="ded-item-letra ellipsis">“${esc(d.letra.split('\n')[0])}”</span>` : d.completa ? '<span class="ded-item-letra">La canción completa</span>' : d.mensaje ? `<span class="ded-item-letra ellipsis">“${esc(d.mensaje)}”</span>` : ''}</span>
      ${d.programadaPara ? `<span class="sop-chip gray">${icon('clock', 'sm')} ${esc(fechaCorta(d.programadaPara))}</span>` : d.recibida && !d.vista ? '<span class="sop-chip warn">Nueva</span>' : d.respuesta ? `<span class="sop-chip ok">${d.recibida ? 'Respondiste' : 'Respondió'}</span>` : ''}</a>
      ${d.programadaPara ? '' : `<button class="icon-btn ded-borrar" data-borrar="${d.id}" aria-label="Borrar de mi lista" title="Borrar de mi lista">${icon('trash', 'sm')}</button>`}</div>`;
  let pestana = 'recibidas';
  try { pestana = sessionStorage.getItem('sm_ded_tab') === 'enviadas' ? 'enviadas' : 'recibidas'; } catch { }
  const lista = (tipo, vacio) => {
    const ds = l[tipo];
    const borrables = ds.filter((d) => !d.programadaPara && !(tipo === 'recibidas' && d.guardada)).length;
    return `<section class="panel ded-sec" data-sec="${tipo}" ${tipo === pestana ? '' : 'hidden'}>
      <div class="ded-sec-cab"><span class="muted">${ds.length ? `${ds.length} ${ds.length === 1 ? 'dedicatoria' : 'dedicatorias'}` : ''}</span>
        ${borrables > 1 ? `<button class="btn sm ghost" data-vaciar="${tipo}">${icon('trash', 'sm')} Borrar todas</button>` : ''}</div>
      ${ds.length ? `<div class="ded-lista">${ds.map(item).join('')}</div>` : `<p class="muted ded-vacio">${vacio}</p>`}</section>`;
  };
  el.innerHTML = `<div class="exp-cab"><span class="exp-ico">${icon('heart')}</span><div><h1>Dedicatorias</h1>
      <p class="muted">Canciones para un momento. Dedica desde el menú de cualquier canción (⋯ → Dedicar esta canción).</p></div></div>
    ${guardadas.length ? `<section class="panel"><div class="row" style="justify-content:space-between;align-items:center"><h3 style="margin:0">Canciones que me dedicaron</h3>
        <button class="btn sm primary" data-oir-g aria-label="Escuchar todas">${icon('play', 'sm')} Escuchar</button></div>
      ${trackList(guardadas.map((d) => d.cancion), { context: { type: 'dedicadas', label: 'Canciones que me dedicaron', route: '#/dedicatorias' }, showAlbum: false, header: false })}</section>` : ''}
    <div class="ded-tabs" role="tablist">
      <button role="tab" data-tab="recibidas" aria-selected="${pestana === 'recibidas'}">Recibidas${l.recibidas.length ? ` <span>${l.recibidas.length}</span>` : ''}</button>
      <button role="tab" data-tab="enviadas" aria-selected="${pestana === 'enviadas'}">Enviadas${l.enviadas.length ? ` <span>${l.enviadas.length}</span>` : ''}</button></div>
    ${lista('recibidas', 'Aún no te han dedicado canciones.')}
    ${lista('enviadas', 'Todavía no dedicas ninguna.')}
    <section class="panel"><h3 style="margin-top:0">Privacidad</h3><p class="muted" style="margin-top:0">Solo pueden dedicarte canciones las personas que tú sigues. Puedes dejar de recibir de alguien cuando quieras.</p>
      ${l.bloqueadas.length ? `<p>No recibes de: ${l.bloqueadas.map((u) => `<button class="chip" data-desbloq="${esc(u)}" aria-label="Volver a recibir de @${esc(u)}">@${esc(u)} ✕</button>`).join(' ')}</p>` : ''}
      <button class="btn sm" data-bloq>Dejar de recibir de alguien</button></section>`;
  el.querySelectorAll('[data-tab]').forEach((b) => b.onclick = () => {
    pestana = b.dataset.tab;
    try { sessionStorage.setItem('sm_ded_tab', pestana); } catch { }
    el.querySelectorAll('[data-tab]').forEach((x) => x.setAttribute('aria-selected', String(x === b)));
    el.querySelectorAll('[data-sec]').forEach((s) => { s.hidden = s.dataset.sec !== pestana; });
  });
  el.querySelectorAll('[data-borrar]').forEach((b) => b.onclick = async (e) => {
    e.preventDefault();
    const fila = b.closest('[data-fila]');
    fila.classList.add('saliendo');
    try {
      await api('/api/dedicatorias/' + b.dataset.borrar, { method: 'DELETE' });
      setTimeout(() => fila.remove(), 180);
    } catch (err) { fila.classList.remove('saliendo'); errorToast(err); }
  });
  el.querySelectorAll('[data-vaciar]').forEach((b) => b.onclick = async () => {
    const recibidas = b.dataset.vaciar === 'recibidas';
    const ok = await confirmDialog(recibidas ? 'Se borrarán de tu lista todas las dedicatorias recibidas. Las que guardaste en «Canciones que me dedicaron» se quedan.'
      : 'Se borrarán de tu lista todas las dedicatorias enviadas. Quien las recibió las sigue viendo.', { title: 'Borrar todas', ok: 'Borrar', danger: true });
    if (!ok) return;
    try {
      const r = await api('/api/dedicatorias?tipo=' + b.dataset.vaciar, { method: 'DELETE' });
      toast(`${r.borradas} ${r.borradas === 1 ? 'dedicatoria borrada' : 'dedicatorias borradas'}`);
      dedicatoriasView(el, params);
    } catch (err) { errorToast(err); }
  });
  el.querySelector('[data-oir-g]')?.addEventListener('click', () => player.play(guardadas.map((d) => d.cancion), 0, { type: 'dedicadas', label: 'Canciones que me dedicaron', route: '#/dedicatorias' }));
  el.querySelector('[data-bloq]').onclick = async () => {
    const u = await promptDialog('Dejar de recibir dedicatorias', { label: 'Usuario', placeholder: '@usuario', ok: 'Bloquear' });
    if (u) { await api('/api/dedicatorias/bloqueos', { method: 'POST', body: { username: u, bloquear: true } }).catch(errorToast); dedicatoriasView(el, params); }
  };
  el.querySelectorAll('[data-desbloq]').forEach((b) => b.onclick = async () => { await api('/api/dedicatorias/bloqueos', { method: 'POST', body: { username: b.dataset.desbloq, bloquear: false } }).catch(errorToast); dedicatoriasView(el, params); });
}

async function detalle(el, params) {
  let d;
  try { d = await api('/api/dedicatorias/' + params.id); } catch (e) { el.innerHTML = emptyState('lock', 'No puedes ver esta dedicatoria', e.message); return; }
  const c = d.cancion;
  if (!c) { el.innerHTML = emptyState('note', 'La canción ya no está en el servidor'); return; }
  const lineas = d.letra ? d.letra.split('\n') : [];
  const quien = d.recibida ? `${esc(d.persona.nombre)} te dedicó` : `Dedicaste a ${esc(d.persona.nombre)}`;
  el.innerHTML = `<article class="ded-det">
    <div class="ded-det-fondo" style="background-image:url('${esc(cover(c.portadaUrl, 640))}')"></div>
    <header class="ded-det-cab">${avatar(d.persona, 'grande')}<div><span class="muted">${esc(timeAgo(d.fecha))}</span><h1>${quien}</h1></div></header>
    ${d.programadaPara ? `<div class="ded-programada">${icon('clock', 'sm')}<span>Programada: ${esc(d.persona.nombre)} la recibirá el <b>${esc(fechaLarga(d.programadaPara))}</b>. Hasta entonces no la ve.</span>
      <button class="btn sm" data-cancelar>Cancelar envío</button></div>` : ''}
    <div class="ded-det-cancion">${coverImg(c.portadaUrl, 640, c.nombre, 'ded-det-portada')}
      <div><h2>${esc(c.nombre)}</h2><p class="muted">${esc(c.artista)}</p>
        ${d.completa ? `<span class="sop-chip ok">${d.recibida ? 'Te dedicaron esta canción completa' : 'La canción completa'}</span>`
          : lineas.length && d.segundoFin ? `<span class="sop-chip gray">${fmtTime(d.segundo)} – ${fmtTime(d.segundoFin)}</span>` : d.segundo ? `<span class="sop-chip gray">Desde el ${fmtTime(d.segundo)}</span>` : ''}</div></div>
    ${lineas.length ? `<blockquote class="ded-det-letra">${lineas.map((l) => `<span>${esc(l)}</span>`).join('')}</blockquote>`
      : d.completa ? '<p class="ded-det-completa">Te dedicaron esta canción completa</p>' : ''}
    ${d.mensaje ? `<div class="ded-det-msg">${avatar(d.recibida ? d.persona : session.user)}<p>${esc(d.mensaje)}</p></div>` : ''}
    <div class="row wrap ded-det-acc">
      <button class="btn primary" data-oir>${icon('play', 'sm')} ${lineas.length ? 'Escuchar el fragmento' : d.completa ? 'Escuchar completa' : d.segundo ? 'Escuchar desde ese momento' : 'Escuchar'}</button>
      ${d.recibida ? `<button class="btn" data-guardar aria-pressed="${d.guardada}">${icon(d.guardada ? 'heartFill' : 'heart', 'sm')} ${d.guardada ? 'Guardada' : 'Guardar'}</button>` : ''}
      <button class="btn" data-tarjeta>${icon('send', 'sm')} Compartir tarjeta</button>
    </div>
    <div class="ded-det-img" data-img></div>
    ${d.respuesta ? `<div class="ded-det-msg resp">${avatar(d.recibida ? session.user : d.persona)}<p><b>${d.recibida ? 'Tu respuesta' : 'Respondió'}:</b> ${esc(d.respuesta)}</p></div>`
      : d.programadaPara ? ''
      : d.recibida ? `<div class="ded-det-responder"><textarea data-resp maxlength="280" placeholder="Responder a ${esc(d.persona.nombre)}…" aria-label="Respuesta"></textarea>
          <button class="btn primary sm" data-responder>${icon('send', 'sm')} Responder</button></div>` : '<p class="muted">Todavía no responde.</p>'}
  </article>`;
  const ctx = { type: 'dedicatoria', label: 'Dedicatoria', route: '#/dedicatorias/' + d.id };
  el.querySelector('[data-oir]').onclick = () => {
    player.play([c], 0, ctx);
    const desde = d.completa ? 0 : d.segundo;
    if (desde) player.audio.addEventListener('loadedmetadata', () => player.seek(desde), { once: true });
  };
  // Tarjeta: se genera al abrir y se muestra (también sirve para hacer captura)
  let blob = null;
  tarjetaDedicatoria({ cancion: c, lineas, completa: d.completa, de: d.recibida ? d.persona.username : session.user?.username, para: d.recibida ? session.user?.username : d.persona.username, mensaje: d.mensaje })
    .then((b) => { blob = b; const box = el.querySelector('[data-img]'); if (box) box.innerHTML = `<img src="${URL.createObjectURL(b)}" alt="Tarjeta de la dedicatoria" class="ded-tarjeta">`; })
    .catch(() => { });
  el.querySelector('[data-tarjeta]').onclick = () => { if (blob) compartirImagen(blob, `dedicatoria-${d.id}.png`); };
  el.querySelector('[data-cancelar]')?.addEventListener('click', async () => {
    try { await api(`/api/dedicatorias/${d.id}/programada`, { method: 'DELETE' }); toast('Envío cancelado'); location.hash = '#/dedicatorias'; } catch (e) { errorToast(e); }
  });
  el.querySelector('[data-guardar]')?.addEventListener('click', async () => { await api(`/api/dedicatorias/${d.id}/guardar`, { method: 'POST', body: { guardar: !d.guardada } }).catch(errorToast); detalle(el, params); });
  el.querySelector('[data-responder]')?.addEventListener('click', async () => {
    try { await api(`/api/dedicatorias/${d.id}/respuesta`, { method: 'POST', body: { texto: el.querySelector('[data-resp]').value } }); toast('Respuesta enviada'); detalle(el, params); } catch (e) { errorToast(e); }
  });
}
