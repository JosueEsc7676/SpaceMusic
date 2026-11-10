// Administración → Reportes: problemas que los usuarios reportan desde el menú de una canción.
import { api } from '../api.js';
import { esc, coverImg, timeAgo } from '../util.js';
import { icon } from '../icons.js';
import { toast, errorToast, emptyState, modal } from '../ui.js';
import { player } from '../player.js';

const EST_REP = { ABIERTO: ['warning', 'Abierto'], RESUELTO: ['success', 'Resuelto'], DESCARTADO: ['', 'Descartado'] };

export async function reportesTab(el) {
  el.innerHTML = `
    <div class="row wrap" style="gap:8px;margin:12px 0">
      <select class="sel" data-estado aria-label="Estado"><option value="ABIERTO">Abiertos</option><option value="RESUELTO">Resueltos</option><option value="DESCARTADO">Descartados</option><option value="">Todos</option></select>
      <span class="muted" style="font-size:13px">Al marcar un reporte como resuelto se le avisa a quien lo envió.</span>
    </div>
    <div data-lista></div>`;
  const lista = el.querySelector('[data-lista]');
  const sel = el.querySelector('[data-estado]');
  let pagina = 0;
  const cargar = async () => {
    lista.innerHTML = '<p class="muted">Cargando…</p>';
    try {
      const r = await api(`/api/admin/comunidad/reportes?estado=${sel.value}&pagina=${pagina}&tam=25`);
      if (!el.isConnected) return;
      if (!r.contenido.length) {
        lista.innerHTML = emptyState('checkCircle', 'No hay reportes aquí');
        return;
      }
      lista.innerHTML = `<div class="pedidos">${r.contenido.map(filaReporte).join('')}</div>
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
    let respuesta = null;
    if (estado !== 'ABIERTO') {
      const v = await modal({
        title: estado === 'RESUELTO' ? 'Marcar como resuelto' : 'Descartar reporte',
        body: `<div class="field"><label>Mensaje para el usuario (opcional)</label><input data-resp maxlength="300" placeholder="Opcional"></div>`,
        actions: [{ label: 'Cancelar', value: null }, { label: 'Guardar', value: 'ok', primary: true }],
        onAction: (val, m) => { if (val === 'ok') respuesta = m.querySelector('[data-resp]').value || null; },
      });
      if (v !== 'ok') return;
    }
    try {
      await api(`/api/admin/comunidad/reportes/${id}`, { method: 'PUT', body: { estado, respuesta } });
      toast('Guardado');
      cargar();
    } catch (e) { errorToast(e); }
  };

  sel.addEventListener('change', () => { pagina = 0; cargar(); });
  cargar();
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
