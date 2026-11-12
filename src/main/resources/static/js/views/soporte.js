// Centro de soporte técnico integrado en Administración (solo administradores): monitorización, diagnóstico, incidencias,
// biblioteca, base de datos, sesiones, alertas, seguridad, mantenimiento e informes.
// Todo lo que se muestra viene del servidor; si un dato no se puede medir se indica "No disponible".
import { api, session } from '../api.js';
import { esc, fmtNum, timeAgo, bus, store } from '../util.js';
import { icon } from '../icons.js';
import { toast, errorToast, confirmDialog, promptDialog, modal, emptyState } from '../ui.js';
import { setHero, setTitle } from '../router.js';

const SECCIONES = [
  ['resumen', 'Resumen', 'chart'], ['monitor', 'Monitor', 'wave'], ['diagnostico', 'Diagnóstico', 'checkCircle'],
  ['incidencias', 'Incidencias', 'info'], ['biblioteca', 'Biblioteca', 'disc'], ['basedatos', 'Base de datos', 'folder'],
  ['sesiones', 'Usuarios y sesiones', 'users'], ['alertas', 'Alertas', 'bell'], ['seguridad', 'Seguridad y auditoría', 'shield'],
  ['mantenimiento', 'Mantenimiento', 'sliders'], ['informes', 'Informes', 'history'],
];

let refresco = null;
bus.on('route', () => { clearInterval(refresco); refresco = null; });

const ND = '<span class="muted">No disponible</span>';
const val = (v, suf = '') => (v === null || v === undefined || v === 'No disponible' ? ND : esc(String(v)) + suf);
const ESTADOS = {
  operativo: ['Operativo', 'ok'], degradado: ['Degradado', 'warn'], no_disponible: ['No disponible', 'bad'], no_comprobado: ['No comprobado', 'gray'],
  correcta: ['Correcta', 'ok'], advertencia: ['Advertencia', 'warn'], fallida: ['Fallida', 'bad'],
  NUEVA: ['Nueva', 'bad'], EN_INVESTIGACION: ['En investigación', 'warn'], RESUELTA: ['Resuelta', 'ok'], DESCARTADA: ['Descartada', 'gray'],
  ACTIVA: ['Activa', 'bad'], RECONOCIDA: ['Reconocida', 'warn'], OK: ['OK', 'ok'], ERROR: ['Error', 'bad'], DENEGADA: ['Denegada', 'warn'],
  EN_CURSO: ['En curso', 'warn'], TERMINADO: ['Terminado', 'ok'], FALLIDO: ['Fallido', 'bad'],
};
const chip = (estado, titulo = '') => {
  const [t, c] = ESTADOS[estado] || [estado ?? '—', 'gray'];
  return `<span class="sop-chip ${c}" ${titulo ? `title="${esc(titulo)}"` : ''}>${esc(t)}</span>`;
};
const hora = (iso) => { if (!iso) return '—'; try { return new Date(typeof iso === 'number' ? iso : iso).toLocaleString('es', { dateStyle: 'short', timeStyle: 'medium' }); } catch { return String(iso); } };
const duracion = (seg) => {
  if (seg == null) return ND;
  const d = Math.floor(seg / 86400), h = Math.floor((seg % 86400) / 3600), m = Math.floor((seg % 3600) / 60);
  return d ? `${d} d ${h} h` : h ? `${h} h ${m} min` : `${m} min`;
};
const ayuda = (t) => `<span class="sop-ayuda" tabindex="0" title="${esc(t)}" aria-label="${esc(t)}">?</span>`;
const stat = (k, v, d = '', tip = '') => `<div class="stat"><div class="k">${esc(k)}${tip ? ' ' + ayuda(tip) : ''}</div><div class="v">${v}</div>${d ? `<div class="d">${d}</div>` : ''}</div>`;
const tabla = (cab, filas, vacio = 'Sin resultados') => filas.length
  ? `<div class="table-wrap"><table class="table sop-tabla"><thead><tr>${cab.map((c) => `<th>${c}</th>`).join('')}</tr></thead><tbody>${filas.join('')}</tbody></table></div>`
  : `<p class="muted sop-vacio">${esc(vacio)}</p>`;
const actualizado = (iso) => `<span class="sop-actualizado">${icon('clock', 'sm')} Actualizado ${esc(hora(iso || new Date().toISOString()))}</span>`;
const filtrosGuardados = (sec) => store.get('sm_sop_filtros_' + sec, {});
const guardarFiltros = (sec, f) => store.set('sm_sop_filtros_' + sec, f);

/** Gráfica de líneas simple (SVG). puntos: [{t, valor}] (valor null = hueco). */
function grafica(puntos, { unidad = '', color = 'var(--accent, #b4a5ff)', alto = 130, etiqueta = '' } = {}) {
  const vals = puntos.map((p) => p.v).filter((v) => v != null && !Number.isNaN(v));
  if (vals.length < 2) return `<div class="sop-graf-vacia">${etiqueta ? esc(etiqueta) + ': ' : ''}aún no hay datos suficientes</div>`;
  const W = 600, H = alto, P = 6;
  const max = Math.max(...vals), min = Math.min(0, ...vals);
  const rango = max - min || 1;
  const x = (i) => P + (i * (W - 2 * P)) / Math.max(1, puntos.length - 1);
  const y = (v) => H - P - ((v - min) * (H - 2 * P)) / rango;
  let d = '', abierto = false;
  puntos.forEach((p, i) => {
    if (p.v == null) { abierto = false; return; }
    d += `${abierto ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.v).toFixed(1)} `;
    abierto = true;
  });
  const ultimo = [...puntos].reverse().find((p) => p.v != null);
  return `<figure class="sop-graf" aria-label="${esc(etiqueta)}">
    <figcaption><b>${esc(etiqueta)}</b><span>${ultimo ? esc(String(ultimo.v)) + unidad : ''} · máx ${esc(String(Math.round(max * 10) / 10))}${unidad}</span></figcaption>
    <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img"><path d="${d}" fill="none" stroke="${color}" stroke-width="2" vector-effect="non-scaling-stroke"/>
    ${puntos.map((p, i) => (p.v == null ? '' : `<circle cx="${x(i)}" cy="${y(p.v)}" r="5" fill="transparent"><title>${esc(hora(p.t))}: ${esc(String(p.v))}${unidad}</title></circle>`)).join('')}</svg>
    <div class="sop-graf-eje"><span>${esc(hora(puntos[0].t))}</span><span>${esc(hora(puntos[puntos.length - 1].t))}</span></div></figure>`;
}

/** Barras simples (SVG) para conteos por día. */
function barras(items, { etiqueta = '', campos = [['errores', '#ff6b6b'], ['advertencias', '#f5a524']] } = {}) {
  if (!items.length || items.every((it) => campos.every(([c]) => !it[c]))) return `<div class="sop-graf-vacia">${esc(etiqueta)}: sin incidencias en el período</div>`;
  const W = 600, H = 130;
  const max = Math.max(1, ...items.map((it) => campos.reduce((a, [c]) => a + (it[c] || 0), 0)));
  const ancho = (W - 8) / items.length;
  const cuerpo = items.map((it, i) => {
    let y = H - 4;
    return campos.map(([c, col]) => {
      const h = ((it[c] || 0) * (H - 10)) / max;
      y -= h;
      return h ? `<rect x="${4 + i * ancho + 1}" y="${y}" width="${Math.max(1, ancho - 2)}" height="${h}" fill="${col}"><title>${esc(it.dia || it.periodo)}: ${it[c]} ${c}</title></rect>` : '';
    }).join('');
  }).join('');
  return `<figure class="sop-graf"><figcaption><b>${esc(etiqueta)}</b><span>${campos.map(([c, col]) => `<i style="background:${col}"></i>${c}`).join(' ')}</span></figcaption>
    <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img">${cuerpo}</svg>
    <div class="sop-graf-eje"><span>${esc(items[0].dia || items[0].periodo)}</span><span>${esc(items[items.length - 1].dia || items[items.length - 1].periodo)}</span></div></figure>`;
}

async function descargar(ruta, nombre) {
  try {
    const r = await fetch(ruta, { headers: { Authorization: 'Bearer ' + session.token } });
    if (!r.ok) throw new Error(r.status === 429 ? 'Demasiadas veces seguidas. Espera un minuto.' : 'No se pudo descargar (' + r.status + ')');
    const blob = await r.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = nombre;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
    toast('Descarga lista');
  } catch (e) { errorToast(e); }
}

// ═══════════════════ Entrada ═══════════════════

// Grupos del menú lateral
const GRUPOS = [
  ['Estado', ['resumen', 'monitor', 'alertas']],
  ['Revisar', ['diagnostico', 'incidencias', 'biblioteca', 'basedatos']],
  ['Personas y seguridad', ['sesiones', 'seguridad']],
  ['Herramientas', ['mantenimiento', 'informes']],
];
const DESCRIPCION = {
  resumen: 'Estado general del servidor y de cada componente.',
  monitor: 'Uso de recursos y rendimiento a lo largo del tiempo.',
  alertas: 'Avisos automáticos cuando algo pasa de los límites.',
  diagnostico: 'Pruebas de solo lectura para encontrar problemas.',
  incidencias: 'Errores del servidor agrupados, con su historial.',
  biblioteca: 'Canciones sin archivo, sin portada, repetidas…',
  basedatos: 'Conexión, rendimiento e integridad de los datos.',
  sesiones: 'Quién está conectado y desde qué dispositivo.',
  seguridad: 'Acciones de los administradores y medidas activas.',
  mantenimiento: 'Tareas, copias de seguridad, cachés y versiones.',
  informes: 'Tendencias por día, semana y mes, con exportación.',
};

/** Soporte dentro de la pestaña de administración, con su selector de secciones. */
export async function soporteView(el, _p, query) {
  if (!session.isAdmin) { el.innerHTML = emptyState('lock', 'Solo para administradores'); return; }
  const sec = SECCIONES.some(([k]) => k === query.sec) ? query.sec : 'resumen';
  const [, titulo, ic] = SECCIONES.find(([k]) => k === sec);
  setTitle('Soporte · ' + titulo);
  setHero(null);
  const enlace = (k) => {
    const [, l, i] = SECCIONES.find(([x]) => x === k);
    return `<a class="sop-lado-item ${k === sec ? 'active' : ''}" href="#/admin?tab=soporte&sec=${k}" ${k === sec ? 'aria-current="page"' : ''}>${icon(i, 'sm')}<span>${esc(l)}</span></a>`;
  };
  el.innerHTML = `<div class="sop-pagina">
    <aside class="sop-lado" aria-label="Secciones de soporte">
      <h2 class="sop-lado-t">${icon('sliders')} Soporte técnico</h2>
      <label class="sop-lado-movil"><span class="muted">Sección</span>
        <select class="sel" data-sec aria-label="Sección de soporte">${GRUPOS.map(([g, ks]) => `<optgroup label="${esc(g)}">${ks.map((k) =>
          `<option value="${k}" ${k === sec ? 'selected' : ''}>${esc(SECCIONES.find(([x]) => x === k)[1])}</option>`).join('')}</optgroup>`).join('')}</select></label>
      <nav class="sop-lado-nav">${GRUPOS.map(([g, ks]) => `<div class="sop-lado-g"><span class="sop-lado-gt">${esc(g)}</span>${ks.map(enlace).join('')}</div>`).join('')}</nav>
    </aside>
    <section class="sop-contenido">
      <header class="sop-cab"><h1>${icon(ic)} ${esc(titulo)}</h1><p class="muted">${esc(DESCRIPCION[sec] || '')}</p></header>
      <div class="sop-cuerpo" data-sop><div class="sop-cargando">${icon('clock', 'sm')} Cargando…</div></div>
    </section></div>`;
  el.querySelector('[data-sec]').addEventListener('change', (e) => { location.hash = '#/admin?tab=soporte&sec=' + e.target.value; });
  const box = el.querySelector('[data-sop]');
  clearInterval(refresco);
  try {
    await ({ resumen, monitor, diagnostico, incidencias, biblioteca, basedatos, sesiones, alertas, seguridad, mantenimiento, informes })[sec](box);
  } catch (e) {
    box.innerHTML = emptyState('info', 'No se pudo cargar esta sección', esc(e.message || 'Error'), '<button class="btn" data-reintentar>Reintentar</button>');
    box.querySelector('[data-reintentar]')?.addEventListener('click', () => soporteView(el, _p, query));
  }
}

// ═══════════════════ 1. Resumen ═══════════════════

async function resumen(box) {
  // La pantalla se muestra un instante después de pintarse: solo se deja de actualizar al salir de ella
  const pintar = async (inicial = false) => {
    const r = await api('/api/admin/soporte/resumen');
    if (!inicial && !box.isConnected) return clearInterval(refresco);
    const c = r.componentes || {};
    const nombres = { aplicacion: 'Aplicación', api: 'API', baseDatos: 'Base de datos', almacenamiento: 'Almacenamiento de canciones', tareas: 'Tareas en segundo plano' };
    const m = r.ultimoMinuto;
    const inc = r.incidencias || {};
    const al = r.alertas || {};
    box.innerHTML = `
      <div class="sop-cabecera"><div>${chip(r.estado)} <b class="sop-titulo">Estado general</b></div><div class="row">${actualizado(r.actualizado)}
        <button class="btn sm" data-refrescar>${icon('refresh', 'sm')} Actualizar</button></div></div>
      <div class="sop-componentes">${Object.entries(nombres).map(([k, n]) => `<div class="sop-comp">${chip(c[k]?.estado)}<b>${n}</b>
        <small>${esc(c[k]?.detalle || '')}</small><small class="muted">${c[k]?.comprobado ? 'Comprobado ' + esc(hora(c[k].comprobado)) : 'Sin comprobar'}</small></div>`).join('')}</div>
      <div class="stat-cards">
        ${stat('Tiempo activo', duracion(r.activoSegundos))}
        ${stat('Peticiones', fmtNum(r.totalPeticiones), m ? `${fmtNum(m.solicitudes)} en el último minuto` : 'desde que arrancó')}
        ${stat('Errores HTTP', `${fmtNum(r.total5xx)} <small>5xx</small> · ${fmtNum(r.total4xx)} <small>4xx</small>`, m ? `${m.errores5xx} del servidor en el último minuto` : '', '5xx: errores del servidor. 4xx: peticiones rechazadas (permisos, datos inválidos, no encontrado).')}
        ${stat('Latencia', m ? val(m.latenciaMediaMs, ' ms') : ND, m ? 'p95: ' + val(m.latenciaP95Ms, ' ms') : 'se mide cada minuto', 'Tiempo medio de respuesta de la API en el último minuto (sin contar la música en streaming). p95: el 95 % de las peticiones tardó menos que esto.')}
        ${stat('Memoria (Java)', `${fmtNum(r.memoriaUsadaMb)} MB`, `de ${fmtNum(r.memoriaMaxMb)} MB disponibles`, 'Memoria que usa SpaceMusic dentro de Java (no la del equipo completo).')}
        ${stat('CPU', r.cpuSistema != null ? r.cpuSistema + ' %' : ND, r.cpuProceso != null ? `SpaceMusic: ${r.cpuProceso} % · ${r.procesadores} núcleos` : `${r.procesadores} núcleos`)}
        ${stat('Disco', r.discoLibreGb != null ? r.discoLibreGb + ' GB libres' : ND, r.discoTotalGb != null ? `de ${r.discoTotalGb} GB` : '')}
        ${stat('En vivo', fmtNum(r.conexionesEnVivo), `${fmtNum(r.sesionesActivas)} cuentas activas (15 min)`, 'Conexiones abiertas para mensajes y notificaciones en tiempo real.')}
      </div>
      <div class="panels">
        <div class="panel"><h3>${icon('info')} Incidencias</h3>
          <div class="sop-mini">${['NUEVA', 'EN_INVESTIGACION', 'RESUELTA', 'DESCARTADA'].map((e) => `<a href="#/admin?tab=soporte&sec=incidencias" data-ver-estado="${e}">${chip(e)} <b>${fmtNum(inc[e] || 0)}</b></a>`).join('')}</div>
          ${inc.descartadasPorExceso ? `<p class="muted">${inc.descartadasPorExceso} eventos no se guardaron por exceso (la cola tiene un límite).</p>` : ''}</div>
        <div class="panel"><h3>${icon('bell')} Alertas</h3>
          <div class="sop-mini">${['ACTIVA', 'RECONOCIDA', 'RESUELTA'].map((e) => `<a href="#/admin?tab=soporte&sec=alertas">${chip(e)} <b>${fmtNum(al[e] || 0)}</b></a>`).join('')}</div></div>
        <div class="panel"><h3>${icon('checkCircle')} Último diagnóstico</h3>${r.ultimoDiagnostico
          ? `<p style="margin-top:0">${chip(r.ultimoDiagnostico.estado)} ${esc(hora(r.ultimoDiagnostico.inicio))} · por @${esc(r.ultimoDiagnostico.usuario)}</p>
             <div class="sop-mini"><span>${chip('correcta')} ${r.ultimoDiagnostico.correctas}</span><span>${chip('advertencia')} ${r.ultimoDiagnostico.advertencias}</span><span>${chip('fallida')} ${r.ultimoDiagnostico.fallidas}</span></div>`
          : '<p class="muted">Aún no se ejecutó ninguno.</p>'}
          <a class="btn sm" href="#/admin?tab=soporte&sec=diagnostico">${icon('checkCircle', 'sm')} Ir al diagnóstico</a></div>
        <div class="panel"><h3>${icon('sliders')} Entorno</h3>
          <div class="kv"><span>Java</span><b>${val(r.version?.java)}</b></div><div class="kv"><span>Spring Boot</span><b>${val(r.version?.springBoot)}</b></div>
          <div class="kv"><span>Sistema</span><b>${val(r.version?.sistema)}</b></div>
          <div class="kv"><span>Conexiones a la base</span><b>${r.pool ? `${r.pool.activas} activas · ${r.pool.libres} libres` : ND}</b></div></div>
      </div>`;
    box.querySelector('[data-refrescar]').onclick = () => pintar().catch(errorToast);
    box.querySelectorAll('[data-ver-estado]').forEach((a) => a.addEventListener('click', () => guardarFiltros('incidencias', { ...filtrosGuardados('incidencias'), estado: a.dataset.verEstado })));
  };
  await pintar(true);
  refresco = setInterval(() => { if (!document.hidden) pintar().catch(() => { }); }, 20000);
}

// ═══════════════════ 2. Monitor ═══════════════════

async function monitor(box) {
  const horas = store.get('sm_sop_horas', 6);
  const [h, r] = await Promise.all([api('/api/admin/soporte/monitor/historial?horas=' + horas), api('/api/admin/soporte/resumen')]);
  const serie = (f) => h.map((m) => ({ t: m.fecha, v: f(m) }));
  box.innerHTML = `
    <div class="sop-cabecera"><div class="chips">${[[1, '1 h'], [6, '6 h'], [24, '24 h'], [168, '7 días'], [720, '30 días']].map(([v, l]) =>
      `<button class="chip ${v === horas ? 'active' : ''}" data-horas="${v}">${l}</button>`).join('')}</div>
      <div class="row">${actualizado(r.actualizado)}<button class="btn sm" data-refrescar>${icon('refresh', 'sm')} Actualizar</button></div></div>
    ${h.length ? '' : `<div class="offline-banner">${icon('clock', 'sm')} El monitor guarda una muestra por minuto: las gráficas aparecen cuando haya datos.</div>`}
    <div class="sop-graficas">
      ${grafica(serie((m) => m.memoriaUsadaMb), { etiqueta: 'Memoria usada (MB)', unidad: ' MB' })}
      ${grafica(serie((m) => m.cpuSistema), { etiqueta: 'CPU del sistema (%)', unidad: ' %', color: '#5ac8fa' })}
      ${grafica(serie((m) => m.latenciaMediaMs), { etiqueta: 'Latencia media (ms)', unidad: ' ms', color: '#34c759' })}
      ${grafica(serie((m) => m.latenciaP95Ms), { etiqueta: 'Latencia p95 (ms)', unidad: ' ms', color: '#30b0c7' })}
      ${grafica(serie((m) => m.solicitudes), { etiqueta: 'Peticiones por minuto', color: '#b4a5ff' })}
      ${grafica(serie((m) => m.errores5xx), { etiqueta: 'Errores del servidor (5xx) por minuto', color: '#ff6b6b' })}
      ${grafica(serie((m) => m.dbLatenciaMs), { etiqueta: 'Respuesta de la base de datos (ms)', unidad: ' ms', color: '#f5a524' })}
      ${grafica(serie((m) => m.discoLibreGb), { etiqueta: 'Disco libre (GB)', unidad: ' GB', color: '#8e8e93' })}
      ${grafica(serie((m) => m.conexionesEnVivo), { etiqueta: 'Conexiones en vivo', color: '#af52de' })}
      ${grafica(serie((m) => m.fallosAudio), { etiqueta: 'Fallos de reproducción por minuto', color: '#ff9f0a' })}
    </div>
    <div class="panel"><h3>${icon('folder')} Almacenamiento y tareas</h3>
      <div class="kv"><span>Carpeta de música</span><b>${chip(r.componentes?.almacenamiento?.estado)} ${esc(r.componentes?.almacenamiento?.detalle || '')}</b></div>
      <div class="kv"><span>Tareas en segundo plano</span><b>${chip(r.componentes?.tareas?.estado)} ${esc(r.componentes?.tareas?.detalle || '')}</b></div>
      <div class="kv"><span>Hilos</span><b>${val(r.hilos)}</b></div></div>`;
  box.querySelectorAll('[data-horas]').forEach((b) => b.addEventListener('click', () => { store.set('sm_sop_horas', +b.dataset.horas); monitor(box).catch(errorToast); }));
  box.querySelector('[data-refrescar]').onclick = () => monitor(box).catch(errorToast);
  refresco = setInterval(() => { if (!document.hidden && box.isConnected) monitor(box).catch(() => { }); }, 60000);
}

// ═══════════════════ 3. Diagnóstico ═══════════════════

async function diagnostico(box) {
  const [pruebas, hist] = await Promise.all([api('/api/admin/soporte/diagnosticos/pruebas'), api('/api/admin/soporte/diagnosticos?tam=10')]);
  box.innerHTML = `
    <div class="sop-cabecera"><div><b class="sop-titulo">Pruebas controladas</b> <span class="muted">Solo leen: no cambian datos de nadie. Cada una tiene 15 s como máximo.</span></div>
      <button class="btn primary" data-completo>${icon('checkCircle', 'sm')} Ejecutar diagnóstico completo</button></div>
    <div class="sop-progreso hidden" data-progreso><div class="progress-line"><i style="width:0"></i></div><span></span></div>
    <div class="panel" data-resultados>${tabla(['Prueba', 'Estado', 'Hora', 'Duración', 'Resultado', ''], pruebas.map((p) => `<tr data-fila="${p.id}">
      <td><b>${esc(p.nombre)}</b><br><small class="muted">${esc(p.descripcion)}</small></td><td data-c="estado">${chip('no_comprobado')}</td><td data-c="hora">—</td><td data-c="dur">—</td>
      <td data-c="res" class="muted">Sin ejecutar</td><td><button class="btn sm ghost" data-una="${p.id}">Ejecutar</button></td></tr>`))}</div>
    <div class="panel"><h3>${icon('history')} Historial</h3>${tabla(['Fecha', 'Tipo', 'Por', 'Estado', 'Resultado', ''], hist.contenido.map((d) => `<tr>
      <td>${esc(hora(d.inicio))}</td><td>${d.tipo === 'completo' ? 'Completo' : 'Prueba'}</td><td>@${esc(d.usuario || '')}</td><td>${chip(d.estado)}</td>
      <td>${chip('correcta')} ${d.correctas} ${chip('advertencia')} ${d.advertencias} ${chip('fallida')} ${d.fallidas}</td>
      <td><button class="btn sm ghost" data-ver="${d.id}">Ver detalle</button></td></tr>`), 'Aún no hay diagnósticos')}</div>`;
  const prog = box.querySelector('[data-progreso]');
  const pintarFila = (r) => {
    const tr = box.querySelector(`[data-fila="${r.id}"]`);
    if (!tr) return;
    tr.querySelector('[data-c="estado"]').innerHTML = chip(r.estado);
    tr.querySelector('[data-c="hora"]').textContent = hora(r.hora);
    tr.querySelector('[data-c="dur"]').textContent = r.duracionMs + ' ms';
    tr.querySelector('[data-c="res"]').innerHTML = `${esc(r.resultado || '')}${r.recomendacion ? `<br><small class="sop-rec">${icon('info', 'sm')} ${esc(r.recomendacion)}</small>` : ''}`;
  };
  const seguir = async (id, total) => {
    prog.classList.remove('hidden');
    box.querySelectorAll('[data-completo],[data-una]').forEach((b) => { b.disabled = true; });
    for (;;) {
      if (!box.isConnected) return;
      const e = await api('/api/admin/soporte/diagnosticos/' + id).catch(() => null);
      if (e) {
        (e.resultados || []).forEach(pintarFila);
        const hechas = e.hechas ?? (e.resultados || []).length;
        prog.querySelector('i').style.width = Math.round((hechas / (e.total || total)) * 100) + '%';
        prog.querySelector('span').textContent = e.estado === 'EN_CURSO' ? `Ejecutando… ${hechas} de ${e.total || total}` : 'Terminado';
        if (e.estado !== 'EN_CURSO') {
          const res = e.resultados || [];
          const n = (s) => res.filter((x) => x.estado === s).length;
          toast(`Diagnóstico terminado: ${n('correcta')} correctas, ${n('advertencia')} advertencias, ${n('fallida')} fallidas`);
          box.querySelectorAll('[data-completo],[data-una]').forEach((b) => { b.disabled = false; });
          return;
        }
      }
      await new Promise((r) => setTimeout(r, 1200));
    }
  };
  const iniciar = async (prueba) => {
    try {
      const r = await api('/api/admin/soporte/diagnosticos' + (prueba ? '?prueba=' + encodeURIComponent(prueba) : ''), { method: 'POST' });
      seguir(r.id, r.total);
    } catch (e) { errorToast(e); }
  };
  box.querySelector('[data-completo]').onclick = () => iniciar(null);
  box.querySelectorAll('[data-una]').forEach((b) => b.addEventListener('click', () => iniciar(b.dataset.una)));
  box.querySelectorAll('[data-ver]').forEach((b) => b.addEventListener('click', async () => {
    const d = await api('/api/admin/soporte/diagnosticos/' + b.dataset.ver).catch(errorToast);
    if (!d) return;
    modal({ title: `Diagnóstico del ${hora(d.inicio)}`, wide: true, body: `<p class="muted">Por @${esc(d.usuario)} · ${chip(d.estado)} · terminó ${esc(hora(d.fin))}</p>`
      + tabla(['Prueba', 'Estado', 'Duración', 'Resultado'], (d.resultados || []).map((x) => `<tr><td>${esc(x.nombre)}</td><td>${chip(x.estado)}</td><td>${x.duracionMs} ms</td>
        <td>${esc(x.resultado)}${x.recomendacion ? `<br><small class="sop-rec">${esc(x.recomendacion)}</small>` : ''}</td></tr>`)) });
  }));
}

// ═══════════════════ 4. Incidencias ═══════════════════

async function incidencias(box, pagina = 0) {
  const f = { q: '', severidad: '', categoria: '', estado: '', desde: '', hasta: '', orden: 'fecha', ...filtrosGuardados('incidencias') };
  const qs = new URLSearchParams({ ...Object.fromEntries(Object.entries(f).filter(([, v]) => v)), pagina, tam: 25 });
  const r = await api('/api/admin/soporte/incidencias?' + qs);
  const cats = r.resumen?.categorias || [];
  box.innerHTML = `
    <form class="sop-filtros" data-filtros>
      <label class="list-filter">${icon('search')}<input type="search" name="q" value="${esc(f.q)}" placeholder="Buscar en mensajes, origen o ruta" maxlength="100"></label>
      <select name="severidad" class="input select-pill" aria-label="Severidad"><option value="">Toda severidad</option>${['ERROR', 'WARN'].map((s) => `<option ${f.severidad === s ? 'selected' : ''} value="${s}">${s === 'ERROR' ? 'Errores' : 'Advertencias'}</option>`).join('')}</select>
      <select name="categoria" class="input select-pill" aria-label="Módulo"><option value="">Todo módulo</option>${cats.map((c) => `<option ${f.categoria === c ? 'selected' : ''}>${esc(c)}</option>`).join('')}</select>
      <select name="estado" class="input select-pill" aria-label="Estado"><option value="">Todo estado</option>${['NUEVA', 'EN_INVESTIGACION', 'RESUELTA', 'DESCARTADA'].map((s) => `<option ${f.estado === s ? 'selected' : ''} value="${s}">${ESTADOS[s][0]}</option>`).join('')}</select>
      <label class="sop-fecha">Desde <input type="date" name="desde" value="${esc(f.desde)}"></label>
      <label class="sop-fecha">Hasta <input type="date" name="hasta" value="${esc(f.hasta)}"></label>
      <select name="orden" class="input select-pill" aria-label="Ordenar"><option value="fecha" ${f.orden === 'fecha' ? 'selected' : ''}>Más recientes</option><option value="frecuencia" ${f.orden === 'frecuencia' ? 'selected' : ''}>Más frecuentes</option><option value="prioridad" ${f.orden === 'prioridad' ? 'selected' : ''}>Prioridad</option><option value="antiguas" ${f.orden === 'antiguas' ? 'selected' : ''}>Más antiguas</option></select>
      <button class="btn sm" type="button" data-limpiar>Limpiar filtros</button>
    </form>
    <p class="muted" style="margin:6px 0 12px">${fmtNum(r.total)} incidencias · los errores iguales se agrupan y se cuentan sus repeticiones</p>
    ${tabla(['#', 'Severidad', 'Mensaje', 'Módulo', 'Ruta', 'Veces', 'Última vez', 'Estado'], r.contenido.map((i) => `<tr class="sop-click" data-inc="${i.id}" tabindex="0">
      <td>${i.id}</td><td>${chip(i.severidad === 'ERROR' ? 'fallida' : 'advertencia', i.severidad)}</td>
      <td class="sop-msg"><b>${esc(i.mensaje)}</b><small class="muted">${esc(i.origen || '')}${i.tipo ? ' · ' + esc(i.tipo.split('.').pop()) : ''}</small></td>
      <td>${esc(i.categoria || '')}</td><td><code>${esc(i.endpoint || '—')}</code></td><td><b>${fmtNum(i.repeticiones)}</b></td>
      <td title="${esc(hora(i.ultima))}">${esc(timeAgo(i.ultima))}</td><td>${chip(i.estado)}${i.reaparecio ? ' <span class="sop-chip bad" title="Volvió a ocurrir después de resolverse">reapareció</span>' : ''}</td></tr>`),
      'No hay incidencias con estos filtros')}
    ${r.paginas > 1 ? `<div class="sop-paginas"><button class="btn sm" data-pag="${pagina - 1}" ${pagina <= 0 ? 'disabled' : ''}>Anterior</button>
      <span>Página ${pagina + 1} de ${r.paginas}</span><button class="btn sm" data-pag="${pagina + 1}" ${pagina + 1 >= r.paginas ? 'disabled' : ''}>Siguiente</button></div>` : ''}`;
  const form = box.querySelector('[data-filtros]');
  let espera;
  const aplicar = () => { guardarFiltros('incidencias', Object.fromEntries(new FormData(form))); incidencias(box, 0).catch(errorToast); };
  form.addEventListener('change', aplicar);
  form.q.addEventListener('input', () => { clearTimeout(espera); espera = setTimeout(aplicar, 450); });
  form.addEventListener('submit', (e) => { e.preventDefault(); aplicar(); });
  box.querySelector('[data-limpiar]').onclick = () => { guardarFiltros('incidencias', {}); incidencias(box, 0).catch(errorToast); };
  box.querySelectorAll('[data-pag]').forEach((b) => b.addEventListener('click', () => incidencias(box, +b.dataset.pag).catch(errorToast)));
  const abrir = (id) => detalleIncidencia(id, () => incidencias(box, pagina).catch(() => { }));
  box.querySelectorAll('[data-inc]').forEach((tr) => {
    tr.addEventListener('click', () => abrir(tr.dataset.inc));
    tr.addEventListener('keydown', (e) => { if (e.key === 'Enter') abrir(tr.dataset.inc); });
  });
}

async function detalleIncidencia(id, alCambiar) {
  let i;
  try { i = await api('/api/admin/soporte/incidencias/' + id); } catch (e) { errorToast(e); return; }
  const body = `<div class="sop-detalle">
      <div class="kv"><span>Estado</span><b>${chip(i.estado)}${i.reaparecio ? ' <span class="sop-chip bad">reapareció</span>' : ''}</b></div>
      <div class="kv"><span>Severidad</span><b>${esc(i.severidad)}</b></div>
      <div class="kv"><span>Módulo · origen</span><b>${esc(i.categoria || '')} · ${esc(i.origen || '')}</b></div>
      <div class="kv"><span>Tipo de excepción</span><b>${esc(i.tipo || '—')}</b></div>
      <div class="kv"><span>Ruta</span><b><code>${esc(i.endpoint || '—')}</code></b></div>
      <div class="kv"><span>Id. de la última petición</span><b><code>${esc(i.requestId || '—')}</code></b></div>
      <div class="kv"><span>Repeticiones</span><b>${fmtNum(i.repeticiones)}</b></div>
      <div class="kv"><span>Primera · última vez</span><b>${esc(hora(i.primera))} · ${esc(hora(i.ultima))}</b></div>
      <div class="kv"><span>Actualizada</span><b>${esc(hora(i.actualizada))}</b></div>
      <h4>Mensaje</h4><pre class="sop-pre">${esc(i.mensaje)}</pre>
      ${i.traza ? `<details><summary>Traza técnica (solo administradores)</summary><pre class="sop-pre">${esc(i.traza)}</pre></details>` : ''}
      <h4>Notas de investigación</h4>${i.notas ? `<pre class="sop-pre">${esc(i.notas)}</pre>` : '<p class="muted">Sin notas.</p>'}
      <div class="field"><textarea data-nota rows="2" maxlength="1000" placeholder="Agregar una nota (opcional al cambiar el estado)"></textarea></div>
      <p class="muted" style="font-size:12px">Cambiar el estado es solo seguimiento: no modifica el registro técnico ni repara nada.</p></div>`;
  const acciones = [{ label: 'Cerrar', value: null }, { label: 'Guardar nota', value: 'nota' }];
  for (const [e, l] of [['EN_INVESTIGACION', 'En investigación'], ['RESUELTA', 'Marcar resuelta'], ['DESCARTADA', 'Descartar'], ['NUEVA', 'Reabrir']]) {
    if (i.estado !== e) acciones.push({ label: l, value: e, primary: e === 'RESUELTA' });
  }
  await modal({
    title: `Incidencia #${i.id}`, body, wide: true, actions: acciones,
    onAction: async (v, m) => {
      if (!v) return null;
      const nota = m.querySelector('[data-nota]').value.trim();
      if (v === 'nota') {
        if (!nota) { toast('Escribe la nota'); return false; }
        await api(`/api/admin/soporte/incidencias/${id}/notas`, { method: 'POST', body: { nota } });
        toast('Nota guardada');
      } else {
        await api(`/api/admin/soporte/incidencias/${id}/estado`, { method: 'PUT', body: { estado: v, nota } });
        toast('Estado actualizado');
      }
      alCambiar?.();
      return v;
    },
  });
}

// ═══════════════════ 5. Biblioteca musical ═══════════════════

async function biblioteca(box) {
  const r = await api('/api/admin/soporte/biblioteca/revision');
  const u = r.ultima?.resultado;
  const bloque = (titulo, d, columnas, fila, ayudaTxt = '') => {
    if (!d) return '';
    return `<details class="panel sop-bloque" ${d.total ? '' : 'data-vacio'}><summary><b>${esc(titulo)}</b> ${ayudaTxt ? ayuda(ayudaTxt) : ''}<span class="sop-num ${d.total ? 'warn' : 'ok'}">${fmtNum(d.total)}</span></summary>
      ${d.lista?.length ? tabla(columnas, d.lista.map(fila)) + (d.total > d.lista.length ? `<p class="muted">Se muestran ${d.lista.length} de ${fmtNum(d.total)}.</p>` : '') : '<p class="muted">Nada que revisar.</p>'}
      ${d.nota ? `<p class="muted" style="font-size:12px">${esc(d.nota)}</p>` : ''}</details>`;
  };
  const cancion = (x) => `<tr><td>#${x.id}</td><td>${esc(x.nombre || '')}</td><td>${esc(x.artista || '')}</td><td>${esc(x.archivo || x.motivo || (x.estado != null ? 'Respuesta ' + x.estado : ''))}</td></tr>`;
  box.innerHTML = `
    <div class="sop-cabecera"><div><b class="sop-titulo">Revisión de la biblioteca</b> <span class="muted">Solo lee: no borra ni cambia canciones, portadas, letras ni datos.</span></div>
      <div class="row"><a class="btn sm ghost" href="#/admin?tab=biblioteca">${icon('folder', 'sm')} Escáner y herramientas</a>
      <button class="btn primary" data-revisar ${r.progreso?.enCurso ? 'disabled' : ''}>${icon('refresh', 'sm')} Revisar ahora</button></div></div>
    <div class="sop-progreso ${r.progreso?.enCurso ? '' : 'hidden'}" data-progreso><div class="progress-line"><i style="width:${r.progreso?.pct || 0}%"></i></div><span>${esc(r.progreso?.fase || '')}</span></div>
    ${r.ultima ? `<p class="muted">Última revisión: ${esc(hora(r.ultima.inicio))} por @${esc(r.ultima.usuario)} · ${chip(r.ultima.estado)}</p>` : '<p class="muted">Aún no se revisó la biblioteca.</p>'}
    ${u && !u.error ? `
      <div class="stat-cards">
        ${stat('Canciones', fmtNum(u.totalCanciones))}
        ${stat('Sin archivo', fmtNum(u.sinArchivo.total), 'en la base pero no en el disco')}
        ${stat('Archivos sin registro', fmtNum(u.sinRegistro.total), 'en el disco pero no en la base')}
        ${stat('Reproducción', u.reproduccion?.probadas ? `${(u.reproduccion.estados?.['206'] || 0) + (u.reproduccion.estados?.['200'] || 0)} de ${u.reproduccion.probadas}` : ND, 'canciones de prueba que respondieron')}
      </div>
      ${bloque('Canciones sin archivo', u.sinArchivo, ['Id', 'Canción', 'Artista', 'Archivo'], cancion, 'Están en la base de datos pero su archivo ya no existe en la carpeta de música.')}
      ${bloque('Archivos sin registro', u.sinRegistro, ['Archivo'], (x) => `<tr><td>${esc(x)}</td></tr>`, 'Archivos de música en la carpeta que no están en la base de datos.')}
      ${bloque('Datos incompletos', u.metadatosIncompletos, ['Id', 'Canción', 'Artista', ''], cancion, 'Sin título o con artista "Desconocido".')}
      ${bloque('Sin portada', u.sinPortada, ['Id', 'Canción', 'Artista', ''], cancion)}
      ${bloque('Sin letra', u.sinLetra, ['Id', 'Canción', 'Artista', ''], cancion)}
      ${bloque('Posibles duplicados', u.duplicados, ['Canción', 'Artista', 'Ids'], (x) => `<tr><td>${esc(x.nombre)}</td><td>${esc(x.artista)}</td><td>${x.ids.map((i) => '#' + i).join(', ')}</td></tr>`, 'Mismo título y artista con una duración casi igual.')}
      ${bloque('Duraciones raras', u.duracionesRaras, ['Id', 'Canción', 'Artista', 'Motivo'], cancion)}
      ${bloque('Fallos de reproducción en la prueba', u.reproduccion ? { total: u.reproduccion.fallos.length, lista: u.reproduccion.fallos, nota: u.reproduccion.nota } : null, ['Id', 'Canción', 'Artista', 'Respuesta'], cancion)}
      <div class="panel"><h3>${icon('note')} Sin género</h3><p class="muted" style="margin:0">${fmtNum(u.sinGenero.total)} canciones · <a href="#/admin?tab=generos">Ver la sección de géneros</a></p></div>`
      : u?.error ? `<p class="sop-rec">${esc(u.error)}</p>` : ''}`;
  box.querySelector('[data-revisar]').onclick = async () => {
    try {
      await api('/api/admin/soporte/biblioteca/revisar', { method: 'POST' });
      toast('Revisando la biblioteca…');
      seguirRevision(box);
    } catch (e) { errorToast(e); }
  };
  if (r.progreso?.enCurso) setTimeout(() => seguirRevision(box), 400);
}

async function seguirRevision(box) {
  const prog = box.querySelector('[data-progreso]');
  prog.classList.remove('hidden');
  box.querySelector('[data-revisar]').disabled = true;
  for (;;) {
    if (!box.isConnected) return;
    const r = await api('/api/admin/soporte/biblioteca/revision').catch(() => null);
    if (r) {
      prog.querySelector('i').style.width = (r.progreso.pct || 0) + '%';
      prog.querySelector('span').textContent = r.progreso.fase || '';
      if (!r.progreso.enCurso) { toast('Revisión de la biblioteca terminada'); return biblioteca(box); }
    }
    await new Promise((res) => setTimeout(res, 1500));
  }
}

// ═══════════════════ 6. Base de datos ═══════════════════

async function basedatos(box) {
  const r = await api('/api/admin/soporte/basedatos');
  const integ = (d) => d ? `<p class="muted">Comprobado ${esc(hora(d.fecha))} · ${esc(d.nota)}</p>` + tabla(['Comprobación', 'Estado', 'Resultado', 'Duración'],
    d.pruebas.map((p) => `<tr><td>${esc(p.prueba)}</td><td>${chip(p.estado)}</td><td>${esc(String(p.resultado))}</td><td>${p.duracionMs} ms</td></tr>`)) : '<p class="muted">Aún no se comprobó.</p>';
  box.innerHTML = `
    <div class="sop-cabecera"><div>${chip(r.conectada ? 'operativo' : 'no_disponible')} <b class="sop-titulo">${esc(r.motor)}</b></div><div class="row">${actualizado(r.comprobado)}
      <button class="btn sm" data-refrescar>${icon('refresh', 'sm')} Actualizar</button></div></div>
    <div class="stat-cards">
      ${stat('Latencia ahora', r.latenciaMs != null ? r.latenciaMs + ' ms' : ND, 'consulta mínima SELECT 1')}
      ${stat('Latencia 24 h', typeof r.latencia24h === 'object' ? r.latencia24h.media + ' ms' : ND, typeof r.latencia24h === 'object' ? `máxima ${r.latencia24h.maxima} ms · ${fmtNum(r.latencia24h.muestras)} muestras` : '')}
      ${stat('Minutos sin conexión', fmtNum(r.minutosSinConexion24h), 'en las últimas 24 h')}
      ${stat('Errores de datos', fmtNum(r.erroresDeDatos), 'incidencias del módulo "base de datos" (7 días)')}
    </div>
    <div class="panels">
      <div class="panel"><h3>${icon('sliders')} Conexiones (pool)</h3>${typeof r.pool === 'object'
        ? `<div class="kv"><span>Activas</span><b>${r.pool.activas}</b></div><div class="kv"><span>Libres</span><b>${r.pool.libres}</b></div>
           <div class="kv"><span>Total · máximo</span><b>${r.pool.total} · ${esc(r.pool.maximo)}</b></div><div class="kv"><span>Esperando una conexión</span><b>${r.pool.esperando}</b></div>` : `<p>${ND}</p>`}</div>
      <div class="panel"><h3>${icon('folder')} Esquema</h3><div class="kv"><span>Sistema</span><b>${esc(r.esquema.sistema)}</b></div><p class="muted" style="font-size:13px">${esc(r.esquema.migraciones)}</p></div>
    </div>
    <div class="panel"><h3>${icon('clock')} Consultas lentas ${ayuda('Hibernate avisa cuando una consulta tarda más del umbral. Se muestran sin los valores.')}</h3>
      <p class="muted">Umbral: ${esc(String(r.umbralLentaMs))} ms</p>
      ${tabla(['Fecha', 'Consulta'], r.consultasLentas.map((c) => `<tr><td>${esc(hora(c.fecha))}</td><td><code class="sop-sql">${esc(c.consulta)}</code></td></tr>`), 'Ninguna consulta lenta desde que arrancó el servidor')}</div>
    <div class="panel"><h3>${icon('disc')} Espacio usado</h3>${typeof r.almacenamiento === 'object'
      ? `<p class="muted">Total: ${r.almacenamiento.totalMb} MB</p>` + tabla(['Tabla', 'Filas (aprox.)', 'MB'], r.almacenamiento.tablas.map((t) => `<tr><td>${esc(t.tabla || t.TABLA || '')}</td><td>${fmtNum(t.filas ?? t.FILAS ?? 0)}</td><td>${esc(String(t.mb ?? t.MB ?? ''))}</td></tr>`))
      : `<p class="muted">${esc(r.almacenamiento)}</p>`}</div>
    <div class="panel"><div class="sop-cabecera"><h3 style="margin:0">${icon('checkCircle')} Integridad (solo lectura)</h3><button class="btn sm" data-integridad>Comprobar</button></div><div data-integ>${integ(r.integridad)}</div></div>`;
  box.querySelector('[data-refrescar]').onclick = () => basedatos(box).catch(errorToast);
  box.querySelector('[data-integridad]').onclick = async (e) => {
    e.currentTarget.disabled = true;
    try { box.querySelector('[data-integ]').innerHTML = integ(await api('/api/admin/soporte/basedatos/integridad', { method: 'POST' })); toast('Integridad comprobada'); }
    catch (er) { errorToast(er); } finally { e.currentTarget.disabled = false; }
  };
}

// ═══════════════════ 7. Usuarios y sesiones ═══════════════════

async function sesiones(box) {
  const r = await api('/api/admin/soporte/sesiones');
  box.innerHTML = `
    <div class="sop-cabecera"><div><b class="sop-titulo">${fmtNum(r.activas.length)} sesiones activas</b> ${ayuda(r.nota)}</div>
      <div class="row">${actualizado()}<button class="btn sm" data-refrescar>${icon('refresh', 'sm')} Actualizar</button></div></div>
    <div class="panel"><h3>${icon('users')} Clientes en uso</h3><div class="sop-mini">${Object.entries(r.clientes).map(([c, n]) => `<span><b>${n}</b> ${esc(c)}</span>`).join('') || '<span class="muted">Nadie activo ahora</span>'}</div></div>
    <div class="panel"><h3>${icon('user')} Actividad reciente</h3>${tabla(['Cuenta', 'Cliente', 'Última actividad', 'Último inicio de sesión', 'Redes', ''],
      r.activas.map((a) => `<tr><td><b>${esc(a.nombre)}</b><br><small class="muted">@${esc(a.username)}</small></td><td>${esc(a.cliente)}${a.version ? ` <small class="muted">v${esc(a.version)}</small>` : ''}</td>
        <td>${esc(timeAgo(a.ultimaActividad))}</td><td>${a.ultimoLogin ? esc(timeAgo(a.ultimoLogin)) : '—'}</td><td>${a.redes.map(esc).join(', ')}</td>
        <td>${a.id === session.user?.id ? '' : `<button class="btn sm ghost" data-revocar="${a.id}" data-nombre="${esc(a.nombre)}">Cerrar sesiones</button>`}</td></tr>`), 'Sin actividad en los últimos 15 minutos')}</div>
    ${r.sospechosas.length ? `<div class="panel sop-alerta"><h3>${icon('shield')} Actividad para revisar</h3>${tabla(['Motivo', 'Cuenta / red', 'Desde'], r.sospechosas.map((s) => `<tr><td>${esc(s.motivo)}</td><td>${s.usuarioId ? '#' + s.usuarioId : esc(s.ip)}</td><td>${s.desde ? esc(timeAgo(s.desde)) : ''}</td></tr>`))}</div>` : ''}
    <div class="panels">
      <div class="panel"><h3>${icon('lock')} Accesos denegados (401/403)</h3>${tabla(['Fecha', 'Código', 'Ruta', 'Cliente', 'Red'], r.fallosAcceso.slice(0, 40).map((f) => `<tr><td>${esc(hora(f.fecha))}</td><td>${f.estado}</td><td><code>${esc(f.endpoint)}</code></td><td>${esc(f.cliente)}</td><td>${esc(f.ip)}</td></tr>`), 'Ninguno desde que arrancó el servidor')}</div>
      <div class="panel"><h3>${icon('user')} Inicios de sesión fallidos</h3>${tabla(['Fecha', 'Usuario', 'Red'], r.fallosLogin.slice(0, 40).map((f) => `<tr><td>${esc(hora(f.fecha))}</td><td>${esc(f.usuario)}</td><td>${esc(f.ip)}</td></tr>`), 'Ninguno desde que arrancó el servidor')}</div>
    </div>`;
  box.querySelector('[data-refrescar]').onclick = () => sesiones(box).catch(errorToast);
  box.querySelectorAll('[data-revocar]').forEach((b) => b.addEventListener('click', async () => {
    if (!(await confirmDialog(`${b.dataset.nombre} tendrá que volver a iniciar sesión en todos sus dispositivos. Queda registrado en la auditoría.`, { title: 'Cerrar sesiones', ok: 'Cerrar sesiones', danger: true }))) return;
    try { await api(`/api/admin/usuarios/${b.dataset.revocar}/cerrar-sesiones`, { method: 'POST' }); toast('Sesiones cerradas'); } catch (e) { errorToast(e); }
  }));
}

// ═══════════════════ 8. Alertas ═══════════════════

async function alertas(box) {
  const f = { estado: 'ACTIVA', ...filtrosGuardados('alertas') };
  const [r, cfg] = await Promise.all([api('/api/admin/soporte/alertas?tam=50' + (f.estado ? '&estado=' + f.estado : '')), api('/api/admin/soporte/config')]);
  const res = r.resumen;
  const tipos = { cpu: 'CPU', ram: 'Memoria', disco: 'Disco', errores: 'Errores', db: 'Base de datos', almacenamiento: 'Almacenamiento', audio: 'Reproducción', seguridad: 'Seguridad', incidencia: 'Incidencia' };
  box.innerHTML = `
    <div class="stat-cards">${['ACTIVA', 'RECONOCIDA', 'RESUELTA'].map((e) => stat(ESTADOS[e][0] + 's', fmtNum(res[e] || 0))).join('')}${stat('Nuevas (24 h)', fmtNum(res.ultimas24h))}</div>
    <div class="panel"><div class="sop-cabecera"><div class="chips">${[['ACTIVA', 'Activas'], ['RECONOCIDA', 'Reconocidas'], ['RESUELTA', 'Resueltas'], ['', 'Todas']].map(([v, l]) =>
      `<button class="chip ${f.estado === v ? 'active' : ''}" data-est="${v}">${l}</button>`).join('')}</div>
      <span class="muted">Avisos: campana ${esc(res.canales.campana)} · correo ${esc(res.canales.correo)}</span></div>
      ${tabla(['Alerta', 'Tipo', 'Estado', 'Creada', 'Última vez', 'Veces', ''], r.contenido.map((a) => `<tr><td><b>${esc(a.mensaje)}</b>${a.reconocidaPor ? `<br><small class="muted">Reconocida por @${esc(a.reconocidaPor)}</small>` : ''}</td>
        <td>${esc(tipos[a.tipo] || a.tipo)}</td><td>${chip(a.estado)}</td><td>${esc(hora(a.creada))}</td><td>${esc(timeAgo(a.actualizada))}</td><td>${fmtNum(a.ocurrencias)}</td>
        <td style="white-space:nowrap">${a.estado === 'ACTIVA' ? `<button class="btn sm ghost" data-reconocer="${a.id}">Reconocer</button>` : ''}${a.estado !== 'RESUELTA' ? `<button class="btn sm ghost" data-resolver="${a.id}">Resolver</button>` : ''}</td></tr>`), 'Sin alertas en este estado')}</div>
    <div class="panel"><h3>${icon('sliders')} Umbrales y retención</h3>
      <p class="muted" style="margin-top:0">Las alertas se revisan cada minuto con las métricas reales. Una misma condición no se repite: se avisa como máximo una vez cada "${cfg['alerta.repetirMinutos'].valor}" minutos.</p>
      <div class="sop-config">${Object.entries(cfg).map(([k, c]) => `<label class="sop-ajuste"><span>${esc(c.descripcion)}${c.actualizadaPor ? `<small class="muted">Cambiado por @${esc(c.actualizadaPor)} · ${esc(timeAgo(c.actualizada))}</small>` : ''}</span>
        <input type="number" step="any" min="${c.min}" max="${c.max}" value="${c.valor}" data-ajuste="${esc(k)}" aria-label="${esc(c.descripcion)}"></label>`).join('')}</div></div>`;
  box.querySelectorAll('[data-est]').forEach((b) => b.addEventListener('click', () => { guardarFiltros('alertas', { estado: b.dataset.est }); alertas(box).catch(errorToast); }));
  box.querySelectorAll('[data-reconocer]').forEach((b) => b.addEventListener('click', async () => {
    try { await api(`/api/admin/soporte/alertas/${b.dataset.reconocer}/reconocer`, { method: 'POST' }); toast('Alerta reconocida'); alertas(box); } catch (e) { errorToast(e); }
  }));
  box.querySelectorAll('[data-resolver]').forEach((b) => b.addEventListener('click', async () => {
    try { await api(`/api/admin/soporte/alertas/${b.dataset.resolver}/resolver`, { method: 'POST' }); toast('Alerta resuelta'); alertas(box); } catch (e) { errorToast(e); }
  }));
  box.querySelectorAll('[data-ajuste]').forEach((inp) => inp.addEventListener('change', async () => {
    try { await api('/api/admin/soporte/config', { method: 'PUT', body: { clave: inp.dataset.ajuste, valor: inp.value } }); toast('Ajuste guardado'); }
    catch (e) { errorToast(e); alertas(box); }
  }));
}

// ═══════════════════ 9. Seguridad y auditoría ═══════════════════

async function seguridad(box, pagina = 0) {
  const f = { q: '', resultado: '', ...filtrosGuardados('auditoria') };
  const qs = new URLSearchParams({ ...Object.fromEntries(Object.entries(f).filter(([, v]) => v)), pagina, tam: 30 });
  const r = await api('/api/admin/soporte/auditoria?' + qs);
  box.innerHTML = `
    <div class="panel"><h3>${icon('shield')} Medidas de seguridad activas</h3><ul class="sop-lista">${r.medidas.map((m) => `<li>${icon('check', 'sm')} ${esc(m)}</li>`).join('')}</ul></div>
    <div class="panel"><h3>${icon('history')} Auditoría administrativa</h3>
      <form class="sop-filtros" data-filtros><label class="list-filter">${icon('search')}<input type="search" name="q" value="${esc(f.q)}" placeholder="Buscar acción, detalle o cuenta" maxlength="100"></label>
        <select name="resultado" class="input select-pill" aria-label="Resultado"><option value="">Todo resultado</option>${['OK', 'ERROR', 'DENEGADA'].map((s) => `<option ${f.resultado === s ? 'selected' : ''} value="${s}">${ESTADOS[s][0]}</option>`).join('')}</select></form>
      <p class="muted">${fmtNum(r.total)} registros · solo lectura</p>
      ${tabla(['Fecha', 'Cuenta', 'Acción', 'Detalle', 'Resultado', 'Red'], r.contenido.map((a) => `<tr><td>${esc(hora(a.fecha))}</td><td>@${esc(a.usuario || '')}</td>
        <td><code>${esc(a.accion)}</code></td><td class="sop-msg">${esc(a.detalle || '')}</td><td>${chip(a.resultado)}</td><td>${esc(a.ip || '')}</td></tr>`), 'Sin registros')}
      ${r.paginas > 1 ? `<div class="sop-paginas"><button class="btn sm" data-pag="${pagina - 1}" ${pagina <= 0 ? 'disabled' : ''}>Anterior</button><span>Página ${pagina + 1} de ${r.paginas}</span>
        <button class="btn sm" data-pag="${pagina + 1}" ${pagina + 1 >= r.paginas ? 'disabled' : ''}>Siguiente</button></div>` : ''}</div>`;
  const form = box.querySelector('[data-filtros]');
  let espera;
  const aplicar = () => { guardarFiltros('auditoria', Object.fromEntries(new FormData(form))); seguridad(box, 0).catch(errorToast); };
  form.addEventListener('change', aplicar);
  form.q.addEventListener('input', () => { clearTimeout(espera); espera = setTimeout(aplicar, 450); });
  form.addEventListener('submit', (e) => { e.preventDefault(); aplicar(); });
  box.querySelectorAll('[data-pag]').forEach((b) => b.addEventListener('click', () => seguridad(box, +b.dataset.pag).catch(errorToast)));
}

// ═══════════════════ 10. Mantenimiento ═══════════════════

async function mantenimiento(box) {
  const r = await api('/api/admin/soporte/mantenimiento');
  const resp = r.respaldos;
  const v = r.versiones;
  const sino = (b) => (b ? `<span class="sop-chip ok">Sí</span>` : `<span class="sop-chip bad">No</span>`);
  box.innerHTML = `
    <div class="panel"><div class="sop-cabecera"><h3 style="margin:0">${icon('clock')} Tareas en segundo plano</h3><button class="btn sm" data-refrescar>${icon('refresh', 'sm')} Actualizar</button></div>
      ${tabla(['Tarea', 'Estado', 'Detalle'], r.tareas.map((t) => `<tr><td><b>${esc(t.nombre)}</b></td><td>${t.estado === 'en curso' ? chip('EN_CURSO') : '<span class="sop-chip gray">En espera</span>'}</td><td>${esc(t.detalle)}</td></tr>`))}</div>
    <div class="panels">
      <div class="panel"><h3>${icon('folder')} Biblioteca</h3><p class="muted" style="margin-top:0">El escaneo corre en segundo plano y no se repite si ya está en curso.</p>
        <div class="row wrap"><button class="btn sm primary" data-escanear>${icon('refresh', 'sm')} Volver a escanear</button>
        <button class="btn sm" data-cache="biblioteca">Recargar biblioteca en memoria</button></div>
        <p class="muted" style="font-size:12px;margin-bottom:0">Más herramientas (datos, portadas) en <a href="#/admin?tab=biblioteca">Biblioteca</a>.</p></div>
      <div class="panel"><h3>${icon('sliders')} Cachés</h3><p class="muted" style="margin-top:0">Se vacían sin riesgo y se recalculan solas.</p>
        <div class="row wrap"><button class="btn sm" data-cache="recomendaciones">Mezclas y recomendaciones</button><button class="btn sm" data-cache="generos">Géneros de artistas</button></div></div>
    </div>
    <div class="panel"><h3>${icon('folder')} Carpetas</h3>${tabla(['Carpeta', 'Nombre', 'Existe', 'Lectura', 'Escritura', 'Libre'], r.directorios.map((d) => `<tr><td><b>${esc(d.nombre)}</b></td><td><code>${esc(d.carpeta)}</code></td>
      <td>${sino(d.existe)}</td><td>${sino(d.lectura)}</td><td>${sino(d.escritura)}</td><td>${d.libreGb != null ? d.libreGb + ' GB' : ND}</td></tr>`))}
      <p class="muted" style="font-size:12px">Solo se comprueban los permisos: no se escribe nada.</p></div>
    <div class="panel"><div class="sop-cabecera"><h3 style="margin:0">${icon('download')} Copias de seguridad de la base de datos</h3>
      <button class="btn sm primary" data-respaldar ${resp.enCurso ? 'disabled' : ''}>${resp.enCurso ? 'Creando…' : 'Crear copia ahora'}</button></div>
      <p class="muted">${esc(resp.programacion)} · se conservan las últimas ${resp.conservar} · carpeta <code>${esc(resp.carpeta)}</code></p>
      ${resp.ultimo ? `<p>${chip(resp.ultimo.resultado)} Última: ${esc(hora(resp.ultimo.inicio))} (${esc(resp.ultimo.motivo)})${resp.ultimo.tamanoMb != null ? ` · ${resp.ultimo.tamanoMb} MB` : ''} · ${resp.ultimo.duracionSeg} s${resp.ultimo.error ? `<br><small class="sop-rec">${esc(resp.ultimo.error)}</small>` : ''}</p>` : ''}
      ${tabla(['Archivo', 'Tamaño', 'Fecha'], resp.copias.map((c) => `<tr><td><code>${esc(c.archivo)}</code></td><td>${c.tamanoMb} MB</td><td>${esc(hora(c.fecha))}</td></tr>`), 'Aún no hay copias')}
      <details><summary>Cómo restaurar una copia</summary><p class="muted">${esc(resp.restaurar)}</p><p class="muted" style="font-size:12px">La restauración no se hace desde la web porque reemplaza todos los datos.</p></details></div>
    <div class="panels">
      <div class="panel"><h3>${icon('info')} Versiones</h3>
        <div class="kv"><span>Web</span><b>${val(v.web)}</b></div>
        <div class="kv"><span>App de Android</span><b>${typeof v.apkMovil === 'object' ? esc(v.apkMovil.nombre) + ' (código ' + v.apkMovil.code + ')' : val(v.apkMovil)}</b></div>
        <div class="kv"><span>App de TV</span><b>${typeof v.apkTv === 'object' ? esc(v.apkTv.nombre) + ' (código ' + v.apkTv.code + ')' : val(v.apkTv)}</b></div>
        <div class="kv"><span>Java · Spring Boot</span><b>${val(v.plataforma?.java)} · ${val(v.plataforma?.springBoot)}</b></div></div>
      <div class="panel"><h3>${icon('download')} Exportar diagnóstico</h3><p class="muted" style="margin-top:0">Un archivo JSON con el estado, incidencias recientes (sin trazas), alertas, diagnósticos y ajustes. Sin correos, tokens ni contraseñas.</p>
        <button class="btn sm" data-exportar>${icon('download', 'sm')} Descargar</button></div>
    </div>`;
  box.querySelector('[data-refrescar]').onclick = () => mantenimiento(box).catch(errorToast);
  box.querySelector('[data-escanear]').onclick = async () => {
    if (!(await confirmDialog('Se buscarán canciones nuevas o movidas en la carpeta de música. La app sigue funcionando mientras tanto.', { title: 'Volver a escanear', ok: 'Escanear' }))) return;
    try { const r2 = await api('/api/admin/biblioteca/escanear', { method: 'POST' }); toast(r2.iniciado ? 'Escaneo iniciado' : 'Ya hay un escaneo en curso'); mantenimiento(box); } catch (e) { errorToast(e); }
  };
  box.querySelectorAll('[data-cache]').forEach((b) => b.addEventListener('click', async () => {
    try { await api('/api/admin/soporte/mantenimiento/cache?cual=' + b.dataset.cache, { method: 'POST' }); toast('Caché vaciada'); } catch (e) { errorToast(e); }
  }));
  box.querySelector('[data-respaldar]').onclick = async () => {
    if (!(await confirmDialog('Se creará una copia de toda la base de datos en el servidor. Puede tardar unos minutos con muchas portadas.', { title: 'Crear copia de seguridad', ok: 'Crear copia' }))) return;
    try {
      await api('/api/admin/soporte/respaldos', { method: 'POST' });
      toast('Creando la copia de seguridad…');
      const seguir = async () => {
        if (!box.isConnected) return;
        const x = await api('/api/admin/soporte/mantenimiento').catch(() => null);
        if (x && !x.respaldos.enCurso) { toast(x.respaldos.ultimo?.resultado === 'OK' ? 'Copia de seguridad lista' : 'La copia de seguridad falló', { error: x.respaldos.ultimo?.resultado !== 'OK' }); return mantenimiento(box); }
        setTimeout(seguir, 2000);
      };
      setTimeout(seguir, 1500);
    } catch (e) { errorToast(e); }
  };
  box.querySelector('[data-exportar]').onclick = () => descargar('/api/admin/soporte/exportar', `spacemusic-diagnostico-${new Date().toISOString().slice(0, 10)}.json`);
}

// ═══════════════════ 11. Informes ═══════════════════

async function informes(box) {
  const dias = store.get('sm_sop_dias', 7);
  const r = await api('/api/admin/soporte/informes?dias=' + dias);
  const s = r.serie;
  const comp = r.comparacion;
  const filaComp = (k, l, suf = '') => `<tr><td>${l}</td><td><b>${val(comp.actual[k], suf)}</b></td><td>${typeof comp.anterior === 'object' ? val(comp.anterior[k], suf) : ND}</td></tr>`;
  box.innerHTML = `
    <div class="sop-cabecera"><div class="chips">${[[1, 'Hoy'], [7, '7 días'], [30, '30 días'], [90, '90 días']].map(([v, l]) => `<button class="chip ${v === dias ? 'active' : ''}" data-dias="${v}">${l}</button>`).join('')}</div>
      <div class="row wrap">${['incidencias', 'metricas', 'endpoints'].map((t) => `<button class="btn sm" data-csv="${t}">${icon('download', 'sm')} CSV ${t === 'metricas' ? 'métricas' : t}</button>`).join('')}</div></div>
    <p class="muted">Del ${esc(r.desde)} al ${esc(r.hasta)} · ${r.datosDesde ? 'hay datos desde ' + esc(hora(r.datosDesde)) : 'aún no hay muestras guardadas'} · agrupado por ${esc(r.agrupacion)}</p>
    <div class="stat-cards">
      ${stat('Disponibilidad', typeof r.disponibilidad.porcentaje === 'number' ? r.disponibilidad.porcentaje + ' %' : ND, `${fmtNum(r.disponibilidad.minutosMedidos)} de ${fmtNum(r.disponibilidad.minutosDelPeriodo)} minutos medidos`, r.disponibilidad.nota)}
      ${stat('Incidencias pendientes', fmtNum((r.incidenciasPorEstado.NUEVA || 0) + (r.incidenciasPorEstado.EN_INVESTIGACION || 0)), `${fmtNum(r.incidenciasPorEstado.RESUELTA || 0)} resueltas · ${fmtNum(r.incidenciasPorEstado.DESCARTADA || 0)} descartadas`)}
      ${stat('Tiempo de resolución', r.tiempoResolucion.horasPromedio != null ? r.tiempoResolucion.horasPromedio + ' h' : ND, r.tiempoResolucion.nota || `promedio de ${r.tiempoResolucion.resueltas} incidencias`)}
    </div>
    <div class="sop-graficas">
      ${barras(r.incidenciasPorDia, { etiqueta: 'Incidencias por día' })}
      ${dias > 14 ? barras(r.incidenciasPorSemana, { etiqueta: 'Incidencias por semana' }) : ''}
      ${dias > 60 ? barras(r.incidenciasPorMes, { etiqueta: 'Incidencias por mes' }) : ''}
      ${grafica(s.map((p) => ({ t: p.t, v: p.latenciaMs })), { etiqueta: 'Latencia media (ms)', unidad: ' ms', color: '#34c759' })}
      ${grafica(s.map((p) => ({ t: p.t, v: p.memoriaMb })), { etiqueta: 'Memoria usada (MB)', unidad: ' MB' })}
      ${grafica(s.map((p) => ({ t: p.t, v: p.discoLibreGb })), { etiqueta: 'Disco libre (GB)', unidad: ' GB', color: '#8e8e93' })}
      ${grafica(s.map((p) => ({ t: p.t, v: p.disponibilidad })), { etiqueta: 'Disponibilidad (%)', unidad: ' %', color: '#30b0c7' })}
      ${grafica(s.map((p) => ({ t: p.t, v: p.fallosAudio })), { etiqueta: 'Fallos de reproducción', color: '#ff9f0a' })}
    </div>
    <div class="panels">
      <div class="panel"><h3>${icon('info')} Errores por módulo</h3>${tabla(['Módulo', 'Ocurrencias'], r.erroresPorModulo.map((m) => `<tr><td>${esc(m.modulo)}</td><td><b>${fmtNum(m.ocurrencias)}</b></td></tr>`), 'Sin errores en el período')}</div>
      <div class="panel"><h3>${icon('link')} Rutas con más errores</h3>${tabla(['Ruta', 'Código', 'Veces'], r.endpointsConMasErrores.map((e) => `<tr><td><code>${esc(e.endpoint)}</code></td><td>${e.estado}</td><td><b>${fmtNum(e.cuenta)}</b></td></tr>`), 'Sin errores en el período')}</div>
    </div>
    <div class="panel"><h3>${icon('history')} Comparación con el período anterior</h3>${tabla(['', 'Este período', 'Período anterior'], [
      filaComp('incidencias', 'Incidencias'), filaComp('errores5xx', 'Errores del servidor'), filaComp('fallosAudio', 'Fallos de reproducción'),
      filaComp('solicitudes', 'Peticiones'), filaComp('latenciaMediaMs', 'Latencia media', ' ms'), filaComp('disponibilidad', 'Disponibilidad', ' %')])}</div>`;
  box.querySelectorAll('[data-dias]').forEach((b) => b.addEventListener('click', () => { store.set('sm_sop_dias', +b.dataset.dias); informes(box).catch(errorToast); }));
  box.querySelectorAll('[data-csv]').forEach((b) => b.addEventListener('click', () =>
    descargar(`/api/admin/soporte/informes/csv?tipo=${b.dataset.csv}&dias=${dias}`, `spacemusic-${b.dataset.csv}-${new Date().toISOString().slice(0, 10)}.csv`)));
}

void promptDialog;
