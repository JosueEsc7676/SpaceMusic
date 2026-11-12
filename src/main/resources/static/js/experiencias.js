// Experiencias: rutas musicales, descubrimiento con control, "El punto donde coincidimos",
// dedicatorias y preparar escucha sin internet. Todo con canciones del servidor.
import { api, session } from './api.js';
import { esc, fmtTime, plural, coverImg, cover } from './util.js';
import { icon } from './icons.js';
import { setHero, setTitle, go } from './router.js';
import { toast, errorToast, emptyState, trackList, modal, promptDialog, confirmDialog, registerList } from './ui.js';
import { player } from './player.js';
import { descargarColeccion, coleccion, quitarColeccion, disponible, actualizarDisponibles } from './offline.js';

const cabecera = (titulo, sub, ico) => `<div class="exp-cab"><span class="exp-ico">${icon(ico)}</span><div><h1>${esc(titulo)}</h1><p class="muted">${esc(sub)}</p></div></div>`;
const mb = (b) => (b / 1048576).toFixed(b > 10 * 1048576 ? 0 : 1) + ' MB';

// ═══════════════════ 1. Rutas musicales ═══════════════════

export async function rutasView(el) {
  setHero([60, 90, 160]);
  setTitle('Rutas musicales');
  let ruta = null, pedido = { minutos: 25, desde: 'calma', hasta: 'energia', sorpresa: 0.2, inicioId: null, finId: null };
  const opciones = (nombre, valor) => ['calma', 'media', 'energia'].map((v) => `<label class="exp-op"><input type="radio" name="${nombre}" value="${v}" ${valor === v ? 'checked' : ''}><span>${{ calma: 'Tranquilo', media: 'Medio', energia: 'Con energía' }[v]}</span></label>`).join('');
  el.innerHTML = cabecera('Llévame de aquí hasta allá', 'Elige cuánto dura, cómo empieza y adónde quieres llegar. Se arma con tus canciones y va cambiando poco a poco.', 'compass') + `
    <form class="panel exp-form" data-form>
      <label class="field"><span>Duración: <b data-min-txt>${pedido.minutos} min</b></span><input type="range" min="10" max="90" step="5" value="${pedido.minutos}" data-min aria-label="Duración en minutos"></label>
      <fieldset class="exp-grupo"><legend>Empieza</legend>${opciones('desde', pedido.desde)}</fieldset>
      <fieldset class="exp-grupo"><legend>Llega a</legend>${opciones('hasta', pedido.hasta)}</fieldset>
      <label class="field"><span>Sorpresa: <b data-sor-txt>un poco</b></span><input type="range" min="0" max="1" step="0.1" value="${pedido.sorpresa}" data-sor aria-label="Cuánta sorpresa"></label>
      <div class="row wrap" style="gap:8px"><button class="btn primary" type="submit">${icon('sparkles', 'sm')} Armar ruta</button>
        ${player.current ? `<button class="btn ghost" type="button" data-desde-actual>Empezar con la que suena</button>` : ''}</div>
      <p class="muted" style="font-size:12px;margin:0">La energía de cada canción se estima por su sonido (volumen y dinámica). No detectamos emociones.</p>
    </form>
    <div data-ruta></div>`;
  const $ = (s) => el.querySelector(s);
  const sorTxt = (v) => (v < 0.15 ? 'nada' : v < 0.45 ? 'un poco' : v < 0.75 ? 'bastante' : 'mucha');
  $('[data-min]').addEventListener('input', (e) => { $('[data-min-txt]').textContent = e.target.value + ' min'; });
  $('[data-sor]').addEventListener('input', (e) => { $('[data-sor-txt]').textContent = sorTxt(+e.target.value); });
  $('[data-desde-actual]')?.addEventListener('click', () => { pedido.inicioId = player.current?.id || null; toast('Empezará con «' + player.current.nombre + '»'); });

  const pintar = () => {
    const box = $('[data-ruta]');
    if (!ruta) { box.innerHTML = ''; return; }
    if (!ruta.disponible) { box.innerHTML = emptyState('clock', 'Aún no está lista', ruta.nota); return; }
    const ctx = { type: 'ruta', label: 'Ruta musical', route: '#/rutas' };
    box.innerHTML = `<section class="panel">
      <div class="row wrap" style="justify-content:space-between;gap:8px"><h2 style="margin:0">Tu ruta · ${ruta.minutos} min</h2>
        <button class="btn primary" data-oir>${icon('play', 'sm')} Escuchar</button></div>
      <p class="muted">${esc(ruta.explicacion)}</p>
      <div class="exp-curva" aria-label="Energía de cada canción">${ruta.energias.map((e, i) => `<i style="height:${Math.round(10 + e * 90)}%" title="${esc(ruta.canciones[i].nombre)}: ${Math.round(e * 100)}"></i>`).join('')}</div>
      <div class="row wrap exp-ajustes" style="gap:8px;margin:12px 0" role="group" aria-label="Ajustar el resto de la ruta">
        <span class="muted">Desde lo que suena ahora:</span>
        <button class="btn sm" data-aj="calma">${icon('moon', 'sm')} Más calma</button>
        <button class="btn sm" data-aj="energia">${icon('fire', 'sm')} Más energía</button>
        <button class="btn sm" data-aj="sorpresa">${icon('sparkles', 'sm')} Más sorpresa</button>
        <button class="btn sm" data-aj="mantener">Mantener ambiente</button></div>
      ${trackList(ruta.canciones, { context: ctx, showAlbum: false })}</section>`;
    box.querySelector('[data-oir]').onclick = () => player.play(ruta.canciones, 0, ctx, { shuffle: false });
    box.querySelectorAll('[data-aj]').forEach((b) => b.onclick = () => ajustar(b.dataset.aj));
  };

  const pedir = async (cuerpo) => {
    try { ruta = await api('/api/rutas', { method: 'POST', body: cuerpo, timeout: 30000 }); pintar(); }
    catch (e) { errorToast(e); }
  };
  // Ajustar: se conserva lo ya escuchado (hasta la canción que suena) y se rehace el resto
  const ajustar = async (ajuste) => {
    if (!ruta?.canciones) return;
    const sonando = player.context?.type === 'ruta' ? player.index : -1;
    const conservar = ruta.canciones.slice(0, Math.max(1, sonando + 1)).map((c) => c.id);
    await pedir({ ...pedido, ajuste, conservar });
    if (sonando >= 0) {
      // La cola sigue desde la canción actual con la nueva continuación
      player.items.splice(player.index + 1);
      player.addToQueue(ruta.canciones.slice(sonando + 1));
      toast('Ruta ajustada desde la canción actual');
    }
  };
  $('[data-form]').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    pedido = { ...pedido, minutos: +$('[data-min]').value, desde: f.get('desde'), hasta: f.get('hasta'), sorpresa: +$('[data-sor]').value };
    pedir(pedido);
  });
}

// ═══════════════════ 4. Descubrimiento con control ═══════════════════

export async function descubrirView(el, _p, query) {
  setHero([60, 140, 255]);
  setTitle('Descubrir');
  const modo = ['familiar', 'cercano', 'sorpresa'].includes(query?.modo) ? query.modo : 'cercano';
  const MODOS = [['familiar', 'Familiar', 'Más de tus artistas'], ['cercano', 'Cercano', 'Tus estilos, otros artistas'], ['sorpresa', 'Sorpréndeme', 'Fuera de lo habitual']];
  el.innerHTML = cabecera('Descubrir', 'Canciones del servidor que aún no escuchas. Tú decides cuánto te alejas de lo de siempre.', 'compass') + `
    <div class="exp-selector" role="tablist">${MODOS.map(([k, t, s]) => `<a role="tab" aria-selected="${k === modo}" class="exp-modo ${k === modo ? 'active' : ''}" href="#/descubrir?modo=${k}"><b>${t}</b><span>${s}</span></a>`).join('')}</div>
    <div data-lista><p class="muted">Buscando…</p></div><div data-descartes></div>`;
  let lista;
  try { lista = await api(`/api/descubrir?modo=${modo}&cantidad=30`); } catch (e) { el.querySelector('[data-lista]').innerHTML = emptyState('info', 'No se pudo cargar', e.message); return; }
  const canciones = lista.map((x) => x.cancion);
  const ctx = { type: 'descubrir', label: 'Descubrir', route: '#/descubrir?modo=' + modo };
  const box = el.querySelector('[data-lista]');
  if (!lista.length) { box.innerHTML = emptyState('compass', 'Ya escuchaste todo por aquí', 'Prueba otro modo o vuelve cuando lleguen canciones nuevas.'); return; }
  const id = registerList(canciones, ctx);
  box.innerHTML = `<div class="row" style="justify-content:space-between;margin:12px 0"><span class="muted">${plural(lista.length, 'canción', 'canciones')}</span>
      <button class="btn primary" data-oir>${icon('play', 'sm')} Escuchar todo</button></div>
    <div class="exp-desc">${lista.map((x, i) => `<div class="exp-desc-fila" data-fila="${x.cancion.id}">
      <button class="exp-desc-play" data-act="play-list" data-list="${id}" data-i="${i}" aria-label="Reproducir ${esc(x.cancion.nombre)}">${coverImg(x.cancion.portadaUrl, 64)}</button>
      <div class="exp-desc-txt"><b class="ellipsis">${esc(x.cancion.nombre)}</b><span class="muted ellipsis">${esc(x.cancion.artista)}</span>
        <span class="exp-razon">${icon('info', 'sm')} ${esc(x.razon)}</span></div>
      <div class="exp-desc-acc"><button class="btn sm ghost" data-hoy="${x.cancion.id}" title="La escondo unos días">Hoy no</button>
        <button class="btn sm ghost" data-estilo="${x.cancion.id}" data-nombre="${esc(x.estiloNombre)}" title="Menos de este estilo">No me gusta ${esc(x.estiloNombre)}</button></div></div>`).join('')}</div>`;
  box.querySelector('[data-oir]').onclick = () => player.play(canciones, 0, ctx);
  const descartar = async (cancionId, tipo, fila) => {
    try {
      await api('/api/descubrir/descartar', { method: 'POST', body: { cancionId, tipo } });
      fila.remove();
      toast(tipo === 'hoy' ? 'Listo, no te la mostraremos estos días' : 'Entendido: menos de ese estilo por un tiempo');
      if (tipo === 'estilo') cargarDescartes();
    } catch (e) { errorToast(e); }
  };
  box.querySelectorAll('[data-hoy]').forEach((b) => b.onclick = () => descartar(+b.dataset.hoy, 'hoy', b.closest('[data-fila]')));
  box.querySelectorAll('[data-estilo]').forEach((b) => b.onclick = async () => {
    if (await confirmDialog(`Te mostraremos menos ${b.dataset.nombre} durante dos meses. Puedes deshacerlo abajo.`, { title: '¿No te gusta este estilo?', ok: 'Mostrar menos' }))
      descartar(+b.dataset.estilo, 'estilo', b.closest('[data-fila]'));
  });
  const cargarDescartes = async () => {
    const d = await api('/api/descubrir/estilos-descartados').catch(() => []);
    const c = el.querySelector('[data-descartes]');
    c.innerHTML = d.length ? `<p class="muted" style="margin-top:20px">Estilos que dijiste que no te gustan: ${d.map((x) => `<button class="chip" data-quitar="${x.id}">${esc(x.estilo)} ✕</button>`).join(' ')}</p>` : '';
    c.querySelectorAll('[data-quitar]').forEach((b) => b.onclick = async () => { await api('/api/descubrir/descartes/' + b.dataset.quitar, { method: 'DELETE' }).catch(errorToast); cargarDescartes(); });
  };
  cargarDescartes();
}

// ═══════════════════ 2. El punto donde coincidimos ═══════════════════

export async function coincidimosView(el, params) {
  setHero([150, 80, 160]);
  setTitle('El punto donde coincidimos');
  if (params?.id) return sesionView(el, params.id);
  const l = await api('/api/coincidimos');
  el.innerHTML = cabecera('El punto donde coincidimos', 'Invita a alguien que sigues o que te sigue. Si acepta, cada quien elige sus canciones y descubren lo que comparten.', 'users') + `
    <div class="row" style="gap:8px;margin:12px 0"><button class="btn primary" data-invitar>${icon('plus', 'sm')} Invitar a alguien</button></div>
    ${l.length ? `<div class="exp-lista">${l.map((s) => `<a class="exp-item" href="#/coincidimos/${s.id}"><b>${esc(s.conmigo.nombre)}</b>
      <span class="muted">${s.estado === 'INVITADA' ? (s.meInvitaron ? 'Te invitó · toca para responder' : 'Esperando que acepte') : 'Activa'}</span></a>`).join('')}</div>`
      : emptyState('users', 'Todavía no tienes sesiones', 'Las sesiones solo existen si las dos personas aceptan.')}
    <p class="muted" style="font-size:12px">No hay escucha sincronizada: cada quien escucha en su dispositivo.</p>`;
  el.querySelector('[data-invitar]').onclick = async () => {
    const u = await promptDialog('Invitar a coincidir', { label: 'Usuario (alguien que sigues o que te sigue)', placeholder: '@usuario', ok: 'Invitar' });
    if (!u) return;
    try { const r = await api('/api/coincidimos', { method: 'POST', body: { username: u } }); toast('Invitación enviada. Esperando que acepte.'); go('/coincidimos/' + r.id); }
    catch (e) { errorToast(e); }
  };
}

async function sesionView(el, id) {
  let s;
  try { s = await api('/api/coincidimos/' + id); } catch (e) { el.innerHTML = emptyState('lock', 'No puedes ver esta sesión', e.message); return; }
  const recargar = () => sesionView(el, id);
  if (s.estado === 'INVITADA') {
    el.innerHTML = cabecera('El punto donde coincidimos', s.meInvitaron ? `${s.conmigo.nombre} te invitó. Si aceptas, verá tu selección y los puentes entre los dos (no tu historial).` : `Esperando que ${s.conmigo.nombre} acepte.`, 'users')
      + (s.meInvitaron ? `<div class="row" style="gap:8px"><button class="btn primary" data-si>Acepto participar</button><button class="btn" data-no>No, gracias</button></div>` : '');
    el.querySelector('[data-si]')?.addEventListener('click', async () => { await api(`/api/coincidimos/${id}/respuesta`, { method: 'POST', body: { acepta: true } }).catch(errorToast); recargar(); });
    el.querySelector('[data-no]')?.addEventListener('click', async () => { await api(`/api/coincidimos/${id}/respuesta`, { method: 'POST', body: { acepta: false } }).catch(errorToast); go('/coincidimos'); });
    return;
  }
  if (s.estado !== 'ACTIVA') { el.innerHTML = emptyState('users', 'Esta sesión terminó'); return; }
  const ctxBase = { type: 'coincidimos', label: 'Coincidimos', route: '#/coincidimos/' + id };
  const fila = (c, quien) => {
    const mia = s.reacciones?.[c.id]?.yo;
    return `<div class="exp-coin" data-c="${c.id}">${coverImg(c.portadaUrl, 56)}<div class="exp-desc-txt"><b class="ellipsis">${esc(c.nombre)}</b><span class="muted ellipsis">${esc(c.artista)}${quien ? ' · ' + esc(quien) : ''}</span></div>
      <div class="exp-reac" role="group" aria-label="Tu reacción">${[['REPRESENTA', 'Me representa'], ['SORPRENDIO', 'Me sorprendió'], ['NO_ES_PARA_MI', 'No era para mí']].map(([k, t]) =>
        `<button class="chip ${mia === k ? 'active' : ''}" data-r="${k}" aria-pressed="${mia === k}">${t}</button>`).join('')}</div></div>`;
  };
  const bloque = (titulo, sub, lista, quien) => lista.length ? `<section class="panel"><div class="row" style="justify-content:space-between"><h3 style="margin:0">${titulo}</h3>
    <button class="btn sm" data-oir='${esc(JSON.stringify(lista.map((x) => x.id)))}'>${icon('play', 'sm')}</button></div><p class="muted" style="margin-top:4px">${sub}</p>${lista.map((c) => fila(c, quien)).join('')}</section>` : '';
  const puentes = s.puentes.map((p) => p.cancion);
  el.innerHTML = cabecera(`Tú y ${s.conmigo.nombre}`, s.estilosEnComun.length ? 'Coinciden en ' + s.estilosEnComun.join(', ') : 'Elijan sus canciones para encontrar dónde coinciden', 'users') + `
    <div class="row wrap" style="gap:8px;margin:10px 0"><button class="btn primary" data-aportar>${icon('plus', 'sm')} Aportar una canción (${s.miAporte.length}/5)</button><button class="btn ghost" data-cerrar>Terminar sesión</button></div>
    ${bloque('Lo que comparten', 'Les gusta a los dos', s.compartidas)}
    ${bloque('Puentes', 'Canciones que ninguno ha escuchado y están cerca de los dos', puentes)}
    ${bloque('Tu aporte', 'Lo que elegiste para esta sesión', s.miAporte, 'tú')}
    ${bloque('Su aporte', 'Lo que eligió ' + s.conmigo.nombre, s.suAporte, s.conmigo.nombre)}
    ${!s.compartidas.length && !puentes.length && !s.miAporte.length && !s.suAporte.length ? emptyState('note', 'Empiecen aportando canciones', 'Cada quien puede elegir hasta 5.') : ''}`;
  el.querySelectorAll('[data-oir]').forEach((b) => b.onclick = () => {
    const ids = JSON.parse(b.dataset.oir);
    const todas = [...s.compartidas, ...puentes, ...s.miAporte, ...s.suAporte];
    player.play(ids.map((i) => todas.find((c) => c.id === i)).filter(Boolean), 0, ctxBase);
  });
  el.querySelectorAll('[data-r]').forEach((b) => b.onclick = async () => {
    try { await api(`/api/coincidimos/${id}/reacciones`, { method: 'POST', body: { cancionId: +b.closest('[data-c]').dataset.c, reaccion: b.dataset.r } }); recargar(); } catch (e) { errorToast(e); }
  });
  el.querySelector('[data-aportar]').onclick = () => elegirCancion('Aportar una canción', async (c) => {
    await api(`/api/coincidimos/${id}/aportes`, { method: 'POST', body: { cancionId: c.id } });
    toast('Aportada'); recargar();
  });
  el.querySelector('[data-cerrar]').onclick = async () => {
    if (await confirmDialog('La sesión se cierra para los dos.', { title: '¿Terminar la sesión?', ok: 'Terminar', danger: true })) { await api(`/api/coincidimos/${id}/cerrar`, { method: 'POST' }).catch(errorToast); go('/coincidimos'); }
  };
}

/** Buscar una canción del servidor (para aportar o dedicar). */
async function elegirCancion(titulo, alElegir) {
  let resultados = [];
  await modal({
    title: titulo,
    body: `<div class="field"><input data-q placeholder="Busca una canción" autocomplete="off" aria-label="Buscar canción"></div><div data-r class="exp-busca"></div>`,
    actions: [{ label: 'Cerrar', value: null }],
    onOpen: (m, cerrar) => {
      let t;
      m.querySelector('[data-q]').addEventListener('input', (e) => {
        clearTimeout(t);
        t = setTimeout(async () => {
          const q = e.target.value.trim();
          if (q.length < 2) return;
          const r = await api('/api/buscar?q=' + encodeURIComponent(q)).catch(() => null);
          resultados = (r?.canciones || []).slice(0, 8);
          m.querySelector('[data-r]').innerHTML = resultados.map((c, i) => `<button class="exp-item" data-i="${i}">${esc(c.nombre)} <span class="muted">· ${esc(c.artista)}</span></button>`).join('') || '<p class="muted">Sin resultados</p>';
          m.querySelectorAll('[data-i]').forEach((b) => b.onclick = async () => { try { await alElegir(resultados[+b.dataset.i]); cerrar(null); } catch (err) { errorToast(err); } });
        }, 300);
      });
    },
  });
}

// ═══════════════════ 3. Dedicatorias (en dedicatorias.js) ═══════════════════

export { dedicarCancion, dedicatoriasView } from './dedicatorias.js';

// ═══════════════════ 5. Preparar escucha sin internet ═══════════════════

const CLAVE_PREPARADA = 'preparada';

export async function prepararView(el) {
  setHero([40, 120, 90]);
  setTitle('Preparar sin internet');
  let sel = null, pedido = { minutos: 120, maxMb: 1000, favoritas: 70 };
  const estado = coleccion(CLAVE_PREPARADA);
  el.innerHTML = cabecera('Preparar escucha sin internet', 'Elige cuánto tiempo y espacio. Revisa la selección, cambia lo que quieras y luego descárgala.', 'download') + `
    <div data-actual></div>
    <form class="panel exp-form" data-form>
      <label class="field"><span>Duración: <b data-t-min>2 h</b></span><input type="range" min="30" max="600" step="30" value="120" data-min></label>
      <label class="field"><span>Espacio máximo: <b data-t-mb>1000 MB</b></span><input type="range" min="100" max="5000" step="100" value="1000" data-mb></label>
      <label class="field"><span>Favoritas <b data-t-fav>70 %</b> · descubrimientos <b data-t-desc>30 %</b></span><input type="range" min="0" max="100" step="10" value="70" data-fav></label>
      <p class="muted" style="font-size:13px;margin:0" data-espacio></p>
      <button class="btn primary" type="submit">${icon('sparkles', 'sm')} Proponer selección</button>
    </form>
    <div data-sel></div>`;
  const $ = (s) => el.querySelector(s);
  const horas = (m) => (m >= 60 ? `${Math.floor(m / 60)} h${m % 60 ? ' ' + (m % 60) + ' min' : ''}` : m + ' min');
  $('[data-min]').oninput = (e) => { $('[data-t-min]').textContent = horas(+e.target.value); };
  $('[data-mb]').oninput = (e) => { $('[data-t-mb]').textContent = e.target.value + ' MB'; };
  $('[data-fav]').oninput = (e) => { $('[data-t-fav]').textContent = e.target.value + ' %'; $('[data-t-desc]').textContent = (100 - e.target.value) + ' %'; };
  // Espacio real disponible para la app en este dispositivo
  let libre = null;
  try { const est = await navigator.storage?.estimate?.(); if (est?.quota) { libre = est.quota - (est.usage || 0); $('[data-espacio]').textContent = `Espacio disponible para la app en este dispositivo: ${mb(libre)}.`; } } catch { }

  const pintarActual = async () => {
    const c = coleccion(CLAVE_PREPARADA);
    const box = $('[data-actual]');
    if (!c) { box.innerHTML = ''; return; }
    await actualizarDisponibles();
    const listas = c.canciones.filter((s) => disponible(s.id)).length;
    box.innerHTML = `<section class="panel"><h3 style="margin-top:0">${listas === c.canciones.length ? `${icon('checkCircle', 'sm')} Descargada` : `${icon('clock', 'sm')} Descargando`}: ${listas} de ${c.canciones.length} canciones</h3>
      <div class="dl-barra"><i style="width:${Math.round(100 * listas / Math.max(1, c.canciones.length))}%"></i></div>
      <div class="row wrap" style="gap:8px;margin-top:10px"><button class="btn sm" data-oir-prep>${icon('play', 'sm')} Escuchar</button><button class="btn sm ghost" data-quitar-prep>Quitar del dispositivo</button></div></section>`;
    box.querySelector('[data-oir-prep]').onclick = () => player.play(c.canciones, 0, { type: 'preparada', label: 'Para escuchar sin internet', route: '#/preparar' });
    box.querySelector('[data-quitar-prep]').onclick = async () => {
      if (await confirmDialog('Se quitan de este dispositivo (las que estén en otra descarga se conservan).', { title: '¿Quitar la selección?', ok: 'Quitar', danger: true })) { quitarColeccion(CLAVE_PREPARADA); pintarActual(); }
    };
    if (listas < c.canciones.length && el.isConnected) setTimeout(() => el.isConnected && pintarActual(), 4000);
  };
  pintarActual();

  const pintarSel = () => {
    const box = $('[data-sel]');
    if (!sel) { box.innerHTML = ''; return; }
    const total = sel.canciones.reduce((a, x) => a + x.bytes, 0), min = Math.round(sel.canciones.reduce((a, x) => a + x.cancion.duracionSegundos, 0) / 60);
    const noCabe = libre != null && total > libre;
    box.innerHTML = `<section class="panel"><div class="row wrap" style="justify-content:space-between;gap:8px">
        <h3 style="margin:0">Selección preparada · ${plural(sel.canciones.length, 'canción', 'canciones')} · ${horas(min)} · ${mb(total)}</h3>
        <button class="btn primary" data-bajar ${noCabe ? 'disabled' : ''}>${icon('download', 'sm')} Descargar</button></div>
      ${sel.nota ? `<p class="muted">${esc(sel.nota)}</p>` : ''}${noCabe ? `<p class="dl-mal">${icon('info', 'sm')} No cabe en el espacio disponible del dispositivo (${mb(libre)}).</p>` : ''}
      <p class="muted" style="font-size:13px">Todavía no se descargó nada: revisa y cambia lo que quieras.</p>
      <div class="exp-desc">${sel.canciones.map((x, i) => `<div class="exp-desc-fila">${coverImg(x.cancion.portadaUrl, 48)}
        <div class="exp-desc-txt"><b class="ellipsis">${esc(x.cancion.nombre)}</b><span class="muted ellipsis">${esc(x.cancion.artista)} · ${fmtTime(x.cancion.duracionSegundos)} · ${mb(x.bytes)}</span></div>
        <span class="sop-chip ${x.tipo === 'favorita' ? 'ok' : 'gray'}">${x.tipo === 'favorita' ? 'Favorita' : 'Descubrimiento'}</span>
        <button class="btn sm ghost" data-cambiar="${i}">Cambiar</button></div>`).join('')}</div></section>`;
    box.querySelectorAll('[data-cambiar]').forEach((b) => b.onclick = async () => {
      const i = +b.dataset.cambiar, x = sel.canciones[i];
      try {
        const nuevo = await api('/api/sin-internet/sustituir', { method: 'POST', body: { tipo: x.tipo, excluir: sel.canciones.map((y) => y.cancion.id), maxBytes: pedido.maxMb * 1048576 - total + x.bytes } });
        sel.canciones[i] = nuevo;
        pintarSel();
      } catch (e) { errorToast(e); }
    });
    box.querySelector('[data-bajar]')?.addEventListener('click', () => {
      const canciones = sel.canciones.map((x) => x.cancion);
      descargarColeccion(CLAVE_PREPARADA, 'preparada', { nombre: 'Para escuchar sin internet' }, canciones);
      toast('Descargando la selección: puedes seguir usando la app');
      sel = null;
      pintarSel();
      pintarActual();
    });
  };

  $('[data-form]').addEventListener('submit', async (e) => {
    e.preventDefault();
    pedido = { minutos: +$('[data-min]').value, maxMb: +$('[data-mb]').value, favoritas: +$('[data-fav]').value };
    try { sel = await api('/api/sin-internet/preparar', { method: 'POST', body: pedido, timeout: 30000 }); pintarSel(); }
    catch (err) { errorToast(err); }
  });
  void estado;
}
