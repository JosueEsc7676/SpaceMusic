// Administración → Organización: organizador automático, proveedores externos, IA local,
// cambios propuestos/aplicados/revertidos y rendimiento. Solo administradores.
import { api } from '../api.js';
import { esc, bus, timeAgo, coverImg } from '../util.js';
import { icon } from '../icons.js';
import { toast, errorToast, confirmDialog, emptyState } from '../ui.js';

let reloj = null;
bus.on('route', () => { clearTimeout(reloj); reloj = null; });

const ESTADO_TRABAJO = { EN_CURSO: ['warn', 'En curso'], PAUSADO: ['gray', 'En pausa'], TERMINADO: ['ok', 'Terminado'], CANCELADO: ['gray', 'Cancelado'], FALLIDO: ['bad', 'Falló'] };
const ESTADO_CAMBIO = { PENDIENTE: ['warn', 'Pendiente'], APLICADO: ['ok', 'Aplicado'], REVERTIDO: ['gray', 'Revertido'], RECHAZADO: ['gray', 'Rechazado'] };
const CAMPOS = { letra: 'Letra', portada: 'Portada', anio: 'Año', nombre: 'Título', duplicado: 'Posible duplicado', album: 'Álbum', artista: 'Artista', genero: 'Género' };
const AJUSTES_ORDEN = ['org.activo', 'org.horaDiaria', 'org.minutoDiario', 'org.diaSemanal', 'org.horaSemanal', 'org.lote', 'org.maxMinutos', 'org.cpuMax', 'org.pausaSiReproduce', 'org.iaActiva', 'org.iaHilos'];
const chip = (cls, t) => `<span class="sop-chip ${cls}">${esc(t)}</span>`;
const fecha = (iso) => (iso ? new Date(iso).toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' }) : '—');

export async function organizacionTab(el) {
  el.innerHTML = `<div class="org">
    <section class="panel" data-trabajo aria-live="polite"><p class="muted">Cargando…</p></section>
    <div class="org-grid">
      <section class="panel" data-catalogo></section>
      <section class="panel" data-ia></section>
    </div>
    <section class="panel"><div class="row wrap" style="justify-content:space-between;gap:8px">
        <h3 style="margin:0">${icon('edit')} Cambios de datos</h3>
        <div class="row wrap" style="gap:8px">
          <select class="sel" data-f-estado aria-label="Estado"><option value="PENDIENTE">Pendientes</option><option value="APLICADO">Aplicados</option><option value="REVERTIDO">Revertidos</option><option value="RECHAZADO">Rechazados</option></select>
          <select class="sel" data-f-campo aria-label="Tipo"><option value="">Todos</option>${Object.entries(CAMPOS).map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}</select>
        </div></div>
      <p class="muted" style="font-size:13px">Solo se completan datos que faltan; lo corregido a mano nunca se toca. Todo lo aplicado se puede revertir.</p>
      <div data-cambios></div></section>
    <section class="panel"><h3>${icon('link')} Vínculos con YouTube Music por revisar</h3><div data-vinculos></div></section>
    <section class="panel"><h3>${icon('sparkles')} Etiquetas propuestas por la IA</h3><div data-etiquetas></div></section>
    <div class="org-grid">
      <section class="panel" data-proveedores></section>
      <section class="panel" data-ajustes></section>
    </div>
    <section class="panel" data-rendimiento></section>
  </div>`;
  const $ = (s) => el.querySelector(s);

  const accion = async (ruta, ok, metodo = 'POST', body) => {
    try { await api(ruta, { method: metodo, body }); if (ok) toast(ok); await cargar(); }
    catch (e) { errorToast(e); }
  };

  const pintarTrabajo = (r) => {
    const t = r.actual;
    const ult = (r.ultimos || []).find((x) => x.estado !== 'EN_CURSO' && x.estado !== 'PAUSADO');
    const avance = (() => { try { return JSON.parse(t?.avance || '{}'); } catch { return {}; } })();
    const [cls, txt] = t ? ESTADO_TRABAJO[t.estado] : ['ok', 'Sin trabajos en curso'];
    $('[data-trabajo]').innerHTML = `<div class="row wrap" style="justify-content:space-between;gap:10px">
        <h3 style="margin:0">${icon('sliders')} Organizador automático</h3>
        <div class="row wrap" style="gap:8px">
          ${!t ? `<button class="btn sm primary" data-iniciar>${icon('play', 'sm')} Ejecutar ahora</button>` : ''}
          ${t?.estado === 'EN_CURSO' ? `<button class="btn sm" data-pausar>${icon('pause', 'sm')} Pausar</button>` : ''}
          ${t?.estado === 'PAUSADO' ? `<button class="btn sm primary" data-reanudar>${icon('play', 'sm')} Reanudar</button>` : ''}
          ${t ? `<button class="btn sm danger" data-cancelar>Cancelar</button>` : ''}
        </div></div>
      <p style="margin:10px 0 6px">${chip(cls, txt)} ${t ? `<b>${esc(t.mensaje || '')}</b>` : ''}
        ${r.esperandoPor ? ` · <span class="muted">Esperando: ${esc(r.esperandoPor)}</span>` : ''}</p>
      ${t ? `<ol class="org-etapas">${r.etapas.map((e, i) => `<li class="${i < t.etapa ? 'hecha' : i === t.etapa ? 'actual' : ''}">${esc(e.nombre)}${avance[e.clave] != null ? ` <span class="muted">· ${esc(String(avance[e.clave]))}</span>` : ''}</li>`).join('')}</ol>` : ''}
      <p class="muted" style="font-size:13px;margin-bottom:0">Próxima diaria: <b>${fecha(r.proximas?.diaria)}</b> · semanal: <b>${fecha(r.proximas?.semanal)}</b> (hora de El Salvador)
        ${ult ? `<br>Último resultado: ${chip(...ESTADO_TRABAJO[ult.estado])} ${esc(ult.tipo)} · ${fecha(ult.fin || ult.actualizado)} · ${esc(ult.mensaje || '')}` : ''}</p>`;
    $('[data-iniciar]')?.addEventListener('click', () => accion('/api/admin/organizacion/iniciar', 'Organizador en marcha'));
    $('[data-pausar]')?.addEventListener('click', () => accion('/api/admin/organizacion/pausar', 'En pausa: el avance queda guardado'));
    $('[data-reanudar]')?.addEventListener('click', () => accion('/api/admin/organizacion/reanudar', 'Reanudado'));
    $('[data-cancelar]')?.addEventListener('click', async () => {
      if (await confirmDialog('Lo hecho hasta ahora se conserva.', { title: '¿Cancelar el trabajo?', ok: 'Cancelar trabajo', danger: true })) accion('/api/admin/organizacion/cancelar', 'Cancelado');
    });
  };

  const pintarCatalogo = (r) => {
    const v = r.vinculos || {}, m = r.metadatos || {}, a = r.audio || {};
    $('[data-catalogo]').innerHTML = `<h3>${icon('library')} Catálogo</h3>
      <div class="org-cifras">
        <div><b>${v.VERIFICADA ?? 0}</b><span>vinculadas a YouTube Music</span></div>
        <div><b>${v.AMBIGUA ?? 0}</b><span>vínculos por revisar</span></div>
        <div><b>${v.pendientes ?? 0}</b><span>sin revisar todavía</span></div>
        <div><b>${v.relaciones ?? 0}</b><span>relaciones para recomendar</span></div>
        <div><b>${m.sinLetra ?? 0}</b><span>sin letra</span></div>
        <div><b>${m.sinPortada ?? 0}</b><span>sin portada</span></div>
        <div><b>${m.PENDIENTE ?? 0}</b><span>cambios pendientes</span></div>
        <div><b>${a.analizadas ?? 0} / ${a.total ?? 0}</b><span>con sonido analizado</span></div>
      </div>
      <p class="muted" style="font-size:12px;margin-bottom:0">Datos verificados de proveedores, características calculadas del audio (${esc(a.metodo || 'ffmpeg')}) y etiquetas inferidas por IA se guardan por separado.</p>`;
  };

  const pintarIa = (r) => {
    const ia = r.ia || {}, ev = r.evaluacion, aj = r.ajustes || {};
    const activa = (aj['org.iaActiva']?.valor ?? 0) >= 1;
    $('[data-ia]').innerHTML = `<h3>${icon('sparkles')} IA local (Ollama)</h3>
      <p style="margin-top:0">${ia.disponible ? chip('ok', 'Ollama ' + (ia.version || '')) : chip('bad', 'Ollama no responde')}
        ${activa ? chip('warn', 'Etiquetas IA encendidas') : chip('gray', 'Etiquetas IA apagadas')}</p>
      <p class="muted" style="font-size:13px">Modelo: <b>${esc(r.modelo)}</b> · En memoria: ${(ia.cargados || []).length ? esc(ia.cargados.map((x) => `${x.nombre} (${x.memoriaMb} MB)`).join(', ')) : 'ninguno (se descarga al terminar)'}
        <br>Instalados: ${esc((ia.modelos || []).map((x) => `${x.nombre} (${x.tamanoMb} MB)`).join(', ') || '—')}</p>
      ${ev ? `<p>Última evaluación (${esc(ev.fecha)} · ${esc(ev.modelo || '')}): <b>${Math.round(ev.precision * 100)} %</b> de aciertos en la muestra revisada${ev.segundosPorCancion ? `, ${ev.segundosPorCancion} s por canción` : ''}
        ${ev.apta ? chip('ok', 'Apta') : chip('bad', 'No apta (se necesita 80 %)')}</p>` : '<p class="muted">El modelo todavía no se ha evaluado.</p>'}
      ${r.evaluando ? `<p role="status">${icon('clock', 'sm')} Evaluando… (unas 20 canciones; tarda varios minutos)</p>` : ''}
      ${r.evaluacionDetalle?.errores?.length ? `<details><summary class="muted">Errores de la última evaluación</summary><ul class="org-errores">${r.evaluacionDetalle.errores.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></details>` : ''}
      <div class="row wrap" style="gap:8px;margin-top:10px">
        <button class="btn sm" data-evaluar ${r.evaluando || r.corriendo ? 'disabled' : ''}>${icon('checkCircle', 'sm')} Evaluar modelo</button>
        <button class="btn sm ghost" data-modelo>${icon('edit', 'sm')} Cambiar modelo</button>
      </div>
      <p class="muted" style="font-size:12px;margin-bottom:0">Un modelo de texto no escucha canciones: solo propone etiquetas a partir del título y el artista, y siempre quedan para revisión. Si la IA está apagada, todo lo demás funciona igual.</p>`;
    $('[data-evaluar]')?.addEventListener('click', () => accion('/api/admin/organizacion/evaluar', 'Evaluación en marcha'));
    $('[data-modelo]')?.addEventListener('click', async () => {
      const { promptDialog } = await import('../ui.js');
      const nombre = await promptDialog('Modelo de Ollama', { label: 'Nombre del modelo ya instalado (por ejemplo qwen3:1.7b)', value: r.modelo, ok: 'Guardar' });
      if (nombre) accion('/api/admin/organizacion/modelo', 'Modelo guardado: evalúalo antes de encender la IA', 'PUT', { nombre });
    });
  };

  const pintarProveedores = (r) => {
    $('[data-proveedores]').innerHTML = `<h3>${icon('globe')} Proveedores</h3>
      ${(r.proveedores || []).length ? `<div class="table-wrap"><table class="table sop-tabla"><thead><tr><th>Servicio</th><th>Llamadas</th><th>Fallos</th><th>Estado</th></tr></thead><tbody>
      ${r.proveedores.map((p) => `<tr><td><b>${esc(p.nombre)}</b><div class="muted" style="font-size:12px">${esc(p.descripcion || '')}</div></td><td>${p.llamadas}</td><td>${p.fallos}</td>
        <td>${p.pausadoHasta ? chip('bad', 'En pausa hasta ' + fecha(p.pausadoHasta)) : p.ultimoError && (!p.ultimoExito || p.ultimoFallo > p.ultimoExito) ? chip('warn', 'Último intento falló') : p.llamadas ? chip('ok', 'Funciona') : chip('gray', 'Sin uso aún')}
        ${p.ultimoError ? `<div class="muted" style="font-size:12px">${esc(p.ultimoError)} · ${fecha(p.ultimoFallo)}</div>` : ''}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted">Todavía no hay datos de proveedores.</p>'}
      <p class="muted" style="font-size:12px;margin-bottom:0">Todos son gratuitos. YouTube Music se consulta con ytmusicapi, una biblioteca no oficial.</p>`;
  };

  const pintarAjustes = (r) => {
    const aj = r.ajustes || {};
    $('[data-ajustes]').innerHTML = `<h3>${icon('clock')} Programación y límites</h3>
      <div class="org-ajustes">${AJUSTES_ORDEN.filter((k) => aj[k]).map((k) => `<label class="org-ajuste"><span>${esc(aj[k].descripcion)}</span>
        <input type="number" data-ajuste="${k}" value="${aj[k].valor}" min="${aj[k].min}" max="${aj[k].max}" step="1" inputmode="numeric"></label>`).join('')}</div>`;
    $('[data-ajustes]').querySelectorAll('[data-ajuste]').forEach((inp) => inp.addEventListener('change', () =>
      accion('/api/admin/organizacion/ajustes', 'Ajuste guardado', 'PUT', { clave: inp.dataset.ajuste, valor: inp.value })));
  };

  const pintarRendimiento = (r) => {
    const f = r.rendimiento || [];
    $('[data-rendimiento]').innerHTML = `<h3>${icon('chart')} Rendimiento del servidor</h3>
      <p class="muted" style="font-size:13px;margin-top:0">Últimas peticiones de cada ruta: tiempo total del servidor y de la base de datos (p50 / p95). Canciones sonando ahora: <b>${r.audiosActivos ?? 0}</b>.</p>
      ${f.length ? `<div class="table-wrap"><table class="table sop-tabla"><thead><tr><th>Ruta</th><th>Peticiones</th><th>Servidor p50/p95</th><th>Base de datos p50/p95</th><th>Consultas</th></tr></thead><tbody>
      ${f.map((x) => `<tr><td><code>${esc(x.ruta)}</code></td><td>${x.peticiones}</td><td>${x.appP50} / ${x.appP95} ms</td><td>${x.dbP50} / ${x.dbP95} ms</td><td>${x.consultasMedia}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted">Aún no hay suficientes peticiones medidas.</p>'}`;
  };

  // ── Cambios ──
  let pagina = 0;
  const cargarCambios = async () => {
    const box = $('[data-cambios]');
    const estado = $('[data-f-estado]').value, campo = $('[data-f-campo]').value;
    try {
      const r = await api(`/api/admin/organizacion/cambios?estado=${estado}&campo=${campo}&pagina=${pagina}`);
      if (!r.contenido.length) { box.innerHTML = emptyState('checkCircle', 'Nada por aquí'); return; }
      box.innerHTML = `<div class="org-cambios">${r.contenido.map((c) => {
        const s = c.cancion;
        const [cls, txt] = ESTADO_CAMBIO[c.estado] || ['gray', c.estado];
        return `<div class="org-cambio">
          ${s ? coverImg(s.portadaUrl, 56) : `<span class="pedido-ico">${icon('note')}</span>`}
          <div class="org-cambio-txt"><b class="ellipsis">${esc(CAMPOS[c.campo] || c.campo)} · ${esc(s ? s.nombre + ' – ' + s.artista : 'Canción #' + c.id)}</b>
            <span class="muted">${esc(c.fuente)} · ${esc(c.metodo || '')} · ${esc(timeAgo(c.fecha))}${c.resueltoPor ? ' · ' + esc(c.resueltoPor) : ''}</span>
            ${c.detalle ? `<span class="muted" style="font-size:13px">${esc(c.detalle)}</span>` : ''}
            ${c.imagen ? `<img class="org-prop-img" src="/api/admin/organizacion/cambios/${c.id}/imagen" alt="Portada propuesta" loading="lazy" data-img>` : ''}
            ${c.nuevo && c.campo !== 'duplicado' ? `<details><summary class="muted">Ver ${c.anterior ? 'antes y después' : 'propuesta'}</summary>${c.anterior ? `<pre class="org-pre">Antes: ${esc(c.anterior)}</pre>` : ''}<pre class="org-pre">${esc(c.nuevo)}</pre></details>` : ''}
          </div>
          ${chip(cls, txt)}
          <div class="row" style="gap:6px">
            ${c.estado === 'PENDIENTE' ? `<button class="btn sm primary" data-c="${c.id}" data-a="aplicar">${c.campo === 'duplicado' ? 'Confirmar' : 'Aplicar'}</button><button class="btn sm" data-c="${c.id}" data-a="rechazar">Rechazar</button>` : ''}
            ${c.estado === 'APLICADO' && c.fuente !== 'manual' ? `<button class="btn sm ghost" data-c="${c.id}" data-a="revertir">Revertir</button>` : ''}
          </div></div>`;
      }).join('')}</div>
      ${r.paginas > 1 ? `<div class="row" style="justify-content:center;gap:8px;margin-top:10px"><button class="btn sm" data-prev ${pagina === 0 ? 'disabled' : ''}>${icon('left', 'sm')}</button><span class="muted">${pagina + 1} / ${r.paginas}</span><button class="btn sm" data-next ${pagina + 1 >= r.paginas ? 'disabled' : ''}>${icon('right', 'sm')}</button></div>` : ''}`;
      // Las imágenes de propuestas necesitan el token: se piden con fetch
      box.querySelectorAll('[data-img]').forEach(async (img) => {
        const src = img.getAttribute('src');
        img.removeAttribute('src');
        try { const b = await (await fetch(src, { headers: { Authorization: 'Bearer ' + localStorage.getItem('sm_token') } })).blob(); img.src = URL.createObjectURL(b); } catch { img.remove(); }
      });
      box.querySelectorAll('[data-c]').forEach((b) => b.addEventListener('click', async () => {
        b.disabled = true;
        try { const r2 = await api(`/api/admin/organizacion/cambios/${b.dataset.c}/${b.dataset.a}`, { method: 'POST' }); toast(r2.estado === 'RECHAZADO' && b.dataset.a === 'aplicar' ? 'No se aplicó: ' + r2.detalle : 'Hecho'); cargarCambios(); }
        catch (e) { errorToast(e); b.disabled = false; }
      }));
      box.querySelector('[data-prev]')?.addEventListener('click', () => { pagina--; cargarCambios(); });
      box.querySelector('[data-next]')?.addEventListener('click', () => { pagina++; cargarCambios(); });
    } catch (e) { box.innerHTML = emptyState('info', 'No se pudieron cargar los cambios', e.message, '<button class="btn sm" data-r>Reintentar</button>'); box.querySelector('[data-r]').onclick = cargarCambios; }
  };
  $('[data-f-estado]').addEventListener('change', () => { pagina = 0; cargarCambios(); });
  $('[data-f-campo]').addEventListener('change', () => { pagina = 0; cargarCambios(); });

  const cargarVinculos = async () => {
    const box = $('[data-vinculos]');
    try {
      const l = await api('/api/admin/organizacion/vinculos');
      box.innerHTML = l.length ? `<p class="muted" style="font-size:13px;margin-top:0">Casi coinciden (artista, título, versión o duración no del todo iguales). Mientras no se confirmen, no se usan para recomendar.</p>
        <div class="org-cambios">${l.map((v) => `<div class="org-cambio"><div class="org-cambio-txt"><b>${esc(v.cancion?.nombre || '')} – ${esc(v.cancion?.artista || '')}</b>
          <span class="muted">${esc(v.detalle)}</span></div>
          <div class="row" style="gap:6px"><button class="btn sm" data-v="${v.id}" data-a="confirmar">Es la misma</button><button class="btn sm ghost" data-v="${v.id}" data-a="descartar">No es</button></div></div>`).join('')}</div>`
        : '<p class="muted">No hay vínculos por revisar.</p>';
      box.querySelectorAll('[data-v]').forEach((b) => b.addEventListener('click', () => accion(`/api/admin/organizacion/vinculos/${b.dataset.v}/${b.dataset.a}`, 'Guardado')));
    } catch (e) { box.innerHTML = `<p class="muted">${esc(e.message)}</p>`; }
  };

  const cargarEtiquetas = async () => {
    const box = $('[data-etiquetas]');
    try {
      const r = await api('/api/admin/organizacion/etiquetas');
      box.innerHTML = r.contenido.length ? `<div class="org-cambios">${r.contenido.map((e) => `<div class="org-cambio"><div class="org-cambio-txt">
          <b>${esc(e.cancion?.nombre || '')} – ${esc(e.cancion?.artista || '')}</b><span class="muted">${e.tipo === 'estilo' ? 'Estilo' : 'Para'}: <b>${esc(e.valor)}</b> · inferido por ${esc(e.modelo)}</span></div>
          <div class="row" style="gap:6px"><button class="btn sm" data-e="${e.id}" data-a="aceptar">Aceptar</button><button class="btn sm ghost" data-e="${e.id}" data-a="rechazar">Rechazar</button></div></div>`).join('')}</div>`
        : '<p class="muted">No hay etiquetas propuestas. La IA solo propone cuando está encendida y aprobó la evaluación.</p>';
      box.querySelectorAll('[data-e]').forEach((b) => b.addEventListener('click', () => accion(`/api/admin/organizacion/etiquetas/${b.dataset.e}/${b.dataset.a}`, 'Guardado')));
    } catch (e) { box.innerHTML = `<p class="muted">${esc(e.message)}</p>`; }
  };

  let primera = true;
  async function cargar() {
    clearTimeout(reloj);
    let activo = false;
    try {
      const r = await api('/api/admin/organizacion');
      if (!el.isConnected && !primera) return;
      activo = r.corriendo || r.evaluando;
      pintarTrabajo(r); pintarCatalogo(r); pintarIa(r); pintarProveedores(r); pintarAjustes(r); pintarRendimiento(r);
      if (primera) { primera = false; cargarCambios(); cargarVinculos(); cargarEtiquetas(); }
    } catch (e) {
      $('[data-trabajo]').innerHTML = emptyState('info', 'No se pudo cargar la organización', e.message, '<button class="btn sm" data-r>Reintentar</button>');
      $('[data-trabajo]').querySelector('[data-r]').onclick = cargar;
    }
    reloj = setTimeout(() => { if (el.isConnected) cargar(); }, activo ? 5000 : 30000);
  }
  await cargar();
}
