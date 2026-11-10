// Administración → Pedidos y reportes: canciones que piden los usuarios y problemas reportados.
import { api } from '../api.js';
import { esc, coverImg, timeAgo } from '../util.js';
import { icon } from '../icons.js';
import { toast, errorToast, emptyState, modal } from '../ui.js';
import { player } from '../player.js';

const EST_PED = { PENDIENTE: ['warning', 'Pendiente'], AGREGADA: ['success', 'Agregada'], RECHAZADA: ['danger', 'Rechazada'] };
const EST_REP = { ABIERTO: ['warning', 'Abierto'], RESUELTO: ['success', 'Resuelto'], DESCARTADO: ['', 'Descartado'] };

export async function pedidosTab(el, query) {
  const ver = query.ver === 'reportes' ? 'reportes' : 'pedidos';
  const conteos = await api('/api/admin/comunidad/conteos');
  el.innerHTML = `<div class="chips" style="margin-top:4px">
      <a class="chip ${ver === 'pedidos' ? 'active' : ''}" href="#/admin?tab=pedidos">${icon('request', 'sm')} Canciones pedidas${conteos.solicitudesPendientes ? ` <span class="badge warning">${conteos.solicitudesPendientes}</span>` : ''}</a>
      <a class="chip ${ver === 'reportes' ? 'active' : ''}" href="#/admin?tab=pedidos&ver=reportes">${icon('flag', 'sm')} Problemas reportados${conteos.reportesAbiertos ? ` <span class="badge warning">${conteos.reportesAbiertos}</span>` : ''}</a>
    </div>
    <div class="row wrap" style="gap:8px;margin:12px 0">
      <select class="sel" data-estado aria-label="Estado">${ver === 'pedidos'
        ? `<option value="PENDIENTE">Pendientes</option><option value="AGREGADA">Agregadas</option><option value="RECHAZADA">Rechazadas</option><option value="">Todas</option>`
        : `<option value="ABIERTO">Abiertos</option><option value="RESUELTO">Resueltos</option><option value="DESCARTADO">Descartados</option><option value="">Todos</option>`}</select>
      <span class="muted" style="font-size:13px">${ver === 'pedidos' ? 'Cuando una canción pedida llega a la biblioteca se marca sola y se avisa a quien la pidió.' : 'Al marcar un reporte como resuelto se le avisa a quien lo envió.'}</span>
    </div>
    <div data-lista></div>`;
  const lista = el.querySelector('[data-lista]');
  const sel = el.querySelector('[data-estado]');
  let pagina = 0;
  const cargar = async () => {
    lista.innerHTML = '<p class="muted">Cargando…</p>';
    try {
      const url = ver === 'pedidos' ? '/api/admin/comunidad/solicitudes' : '/api/admin/comunidad/reportes';
      const r = await api(`${url}?estado=${sel.value}&pagina=${pagina}&tam=25`);
      if (!el.isConnected) return;
      if (!r.contenido.length) {
        lista.innerHTML = emptyState('checkCircle', ver === 'pedidos' ? 'No hay pedidos aquí' : 'No hay reportes aquí');
        return;
      }
      lista.innerHTML = `<div class="pedidos">${r.contenido.map(ver === 'pedidos' ? filaPedido : filaReporte).join('')}</div>
        ${r.paginas > 1 ? `<div class="row" style="gap:8px;justify-content:center;margin-top:12px">
          <button class="btn sm" data-prev ${pagina === 0 ? 'disabled' : ''}>${icon('left', 'sm')}</button>
          <span class="muted">Página ${pagina + 1} de ${r.paginas}</span>
          <button class="btn sm" data-next ${pagina + 1 >= r.paginas ? 'disabled' : ''}>${icon('right', 'sm')}</button></div>` : ''}`;
      lista.querySelector('[data-prev]')?.addEventListener('click', () => { pagina--; cargar(); });
      lista.querySelector('[data-next]')?.addEventListener('click', () => { pagina++; cargar(); });
      lista.querySelectorAll('[data-oir]').forEach((b) => b.addEventListener('click', () => {
        const x = r.contenido.find((i) => String(i.id) === b.dataset.oir);
        if (x?.cancion) player.playOne(x.cancion);
      }));
      lista.querySelectorAll('[data-accion]').forEach((b) => b.addEventListener('click', () => responder(b.dataset.id, b.dataset.accion)));
    } catch (e) { lista.innerHTML = ''; errorToast(e); }
  };

  const responder = async (id, estado) => {
    const pide = ver === 'pedidos' ? estado === 'RECHAZADA' || estado === 'AGREGADA' : estado !== 'ABIERTO';
    let respuesta = null, cancionId = null;
    if (pide) {
      const titulo = ver === 'pedidos' ? (estado === 'AGREGADA' ? 'Marcar como agregada' : 'Rechazar pedido') : (estado === 'RESUELTO' ? 'Marcar como resuelto' : 'Descartar reporte');
      const extra = ver === 'pedidos' && estado === 'AGREGADA'
        ? `<div class="field"><label>Canción (opcional: si no la eliges, se busca por nombre)</label><input data-buscar placeholder="Buscar en la biblioteca" autocomplete="off"><div data-res class="pedido-res"></div></div>` : '';
      const v = await modal({
        title: titulo,
        body: `${extra}<div class="field"><label>Mensaje para el usuario (opcional)</label><input data-resp maxlength="300" placeholder="${estado === 'RECHAZADA' ? 'Por ejemplo: no la encontramos en buena calidad' : 'Opcional'}"></div>`,
        actions: [{ label: 'Cancelar', value: null }, { label: 'Guardar', value: 'ok', primary: true }],
        onOpen: (m) => {
          const q = m.querySelector('[data-buscar]');
          if (!q) return;
          let t;
          q.addEventListener('input', () => {
            clearTimeout(t);
            t = setTimeout(async () => {
              if (q.value.trim().length < 2) { m.querySelector('[data-res]').innerHTML = ''; return; }
              const r = await api('/api/buscar?q=' + encodeURIComponent(q.value.trim())).catch(() => null);
              m.querySelector('[data-res]').innerHTML = (r?.canciones || []).slice(0, 6).map((c) =>
                `<label class="rep-motivo"><input type="radio" name="cancion" value="${c.id}"><span>${esc(c.nombre)} · <span class="muted">${esc(c.artista)}</span></span></label>`).join('') || '<p class="muted">Sin resultados</p>';
            }, 300);
          });
        },
        onAction: (val, m) => {
          if (val !== 'ok') return;
          respuesta = m.querySelector('[data-resp]').value || null;
          const c = m.querySelector('input[name=cancion]:checked');
          cancionId = c ? Number(c.value) : null;
        },
      });
      if (v !== 'ok') return;
    }
    try {
      const url = ver === 'pedidos' ? `/api/admin/comunidad/solicitudes/${id}` : `/api/admin/comunidad/reportes/${id}`;
      await api(url, { method: 'PUT', body: { estado, respuesta, cancionId } });
      toast('Guardado');
      cargar();
    } catch (e) { errorToast(e); }
  };

  sel.addEventListener('change', () => { pagina = 0; cargar(); });
  cargar();
}

function filaPedido(s) {
  const [cls, txt] = EST_PED[s.estado] || ['', s.estado];
  return `<div class="pedido">
    ${s.cancion ? coverImg(s.cancion.portadaUrl, 56) : `<span class="pedido-ico">${icon('request')}</span>`}
    <div class="pedido-txt"><b class="ellipsis">${esc(s.titulo)}</b>
      <span class="muted ellipsis">${esc(s.artista || 'Artista sin indicar')} · pedida por @${esc(s.usuario || '?')} ${timeAgo ? '· ' + esc(timeAgo(s.fecha)) : ''}</span>
      ${s.nota ? `<span class="muted" style="font-size:13px">Nota: ${esc(s.nota)}</span>` : ''}
      ${s.respuesta ? `<span class="muted" style="font-size:13px">Respuesta: ${esc(s.respuesta)}</span>` : ''}</div>
    <span class="badge ${cls}">${txt}</span>
    <div class="row" style="gap:6px">
      ${s.cancion ? `<button class="btn sm" data-oir="${s.id}">${icon('play', 'sm')}</button>` : ''}
      ${s.estado === 'PENDIENTE' ? `<button class="btn sm primary" data-id="${s.id}" data-accion="AGREGADA">${icon('check', 'sm')} Agregada</button>
        <button class="btn sm" data-id="${s.id}" data-accion="RECHAZADA">Rechazar</button>`
        : `<button class="btn sm ghost" data-id="${s.id}" data-accion="PENDIENTE">Reabrir</button>`}
    </div></div>`;
}

function filaReporte(r) {
  const [cls, txt] = EST_REP[r.estado] || ['', r.estado];
  const c = r.cancion;
  return `<div class="pedido">
    ${c ? coverImg(c.portadaUrl, 56) : `<span class="pedido-ico">${icon('flag')}</span>`}
    <div class="pedido-txt"><b class="ellipsis">${esc(c ? c.nombre : 'Canción eliminada')}${c ? ` <span class="muted" style="font-weight:400">· ${esc(c.artista)}</span>` : ''}</b>
      <span class="ellipsis"><span class="badge">${esc(r.motivoTexto || r.motivo)}</span> <span class="muted">@${esc(r.usuario || '?')} · ${esc(r.cliente || 'web')} · ${esc(timeAgo(r.fecha))}</span></span>
      ${r.detalle ? `<span class="muted" style="font-size:13px">“${esc(r.detalle)}”</span>` : ''}
      ${r.respuesta ? `<span class="muted" style="font-size:13px">Respuesta: ${esc(r.respuesta)}</span>` : ''}</div>
    <span class="badge ${cls}">${txt}</span>
    <div class="row" style="gap:6px">
      ${c ? `<button class="btn sm" data-oir="${r.id}">${icon('play', 'sm')}</button>` : ''}
      ${r.estado === 'ABIERTO' ? `<button class="btn sm primary" data-id="${r.id}" data-accion="RESUELTO">${icon('check', 'sm')} Resuelto</button>
        <button class="btn sm" data-id="${r.id}" data-accion="DESCARTADO">Descartar</button>`
        : `<button class="btn sm ghost" data-id="${r.id}" data-accion="ABIERTO">Reabrir</button>`}
    </div></div>`;
}
