// Administración → Descargar música → Buscar en YouTube Music.
// Busca canciones, álbumes y artistas (biblioteca no oficial ytmusicapi), muestra si ya están en la
// biblioteca o en la cola y los agrega a la cola de siempre con un toque, sin copiar enlaces.
import { api } from '../api.js';
import { esc, fmtTime } from '../util.js';
import { icon } from '../icons.js';
import { toast, errorToast, emptyState, modal } from '../ui.js';

const ESTADOS = {
  biblioteca: ['ok', 'En tu biblioteca'],
  en_cola: ['gray', 'En cola'],
  descargando: ['warn', 'Descargando'],
  completado: ['ok', 'Completada'],
  fallido: ['bad', 'Falló'],
  disponible: ['', ''],
};
const TIPOS = [['songs', 'Canciones'], ['albums', 'Álbumes'], ['artists', 'Artistas']];
const bloqueado = (e) => ['biblioteca', 'en_cola', 'descargando'].includes(e);

export function iniciarBusqueda(caja, alCambiar) {
  let tipo = 'songs', limite = 20, consulta = '', resultados = [];
  const elegidas = new Set();

  caja.innerHTML = `<h3>${icon('search')} Buscar en YouTube Music</h3>
    <p class="muted" style="margin-top:0">Busca y agrega a la cola con un toque. Lo que ya está en tu biblioteca o en la cola no se repite.
      <span class="dl-nota">Búsqueda con ytmusicapi, una biblioteca no oficial: no todo resultado se puede descargar.</span></p>
    <form class="dl-yt-form" data-form role="search">
      <input data-q type="search" placeholder="Canción, artista o álbum" aria-label="Buscar en YouTube Music" maxlength="150" autocomplete="off">
      <button class="btn primary" type="submit">${icon('search', 'sm')} Buscar</button>
    </form>
    <div class="chips" role="tablist">${TIPOS.map(([k, l]) => `<button class="chip ${k === tipo ? 'active' : ''}" data-tipo="${k}" role="tab" aria-selected="${k === tipo}">${l}</button>`).join('')}</div>
    <div data-barra class="dl-yt-barra" hidden></div>
    <div data-res aria-live="polite"></div>`;
  const $ = (s) => caja.querySelector(s);

  const barra = () => {
    const b = $('[data-barra]');
    b.hidden = !elegidas.size;
    b.innerHTML = elegidas.size ? `<span><b>${elegidas.size}</b> seleccionada(s)</span>
      <button class="btn sm ghost" data-limpiar>Quitar selección</button>
      <button class="btn sm primary" data-agregar-sel>${icon('download', 'sm')} Agregar a la cola</button>` : '';
    b.querySelector('[data-limpiar]')?.addEventListener('click', () => { elegidas.clear(); pintar(); });
    b.querySelector('[data-agregar-sel]')?.addEventListener('click', () => agregar([...elegidas]));
  };

  const filaCancion = (r) => {
    const [cls, txt] = ESTADOS[r.estado] || ['', ''];
    const no = bloqueado(r.estado);
    return `<div class="dl-yt-fila" data-id="${esc(r.videoId)}">
      <input type="checkbox" class="dl-yt-chk" data-chk="${esc(r.videoId)}" ${elegidas.has(r.videoId) ? 'checked' : ''} ${no ? 'disabled' : ''} aria-label="Seleccionar ${esc(r.titulo)}">
      ${r.portada ? `<img class="dl-yt-img" src="${esc(r.portada)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : `<span class="dl-yt-img">${icon('note')}</span>`}
      <div class="dl-yt-txt"><b class="ellipsis">${esc(r.titulo)}</b>
        <span class="muted ellipsis">${esc((r.artistas || []).join(', '))}${r.album ? ' · ' + esc(r.album) : ''}</span></div>
      <span class="muted dl-yt-dur">${r.duracion ? fmtTime(r.duracion) : ''}</span>
      ${txt ? `<span class="sop-chip ${cls}">${txt}</span>` : ''}
      ${no ? '' : `<button class="btn sm" data-uno="${esc(r.videoId)}" aria-label="Agregar ${esc(r.titulo)} a la cola">${icon('plus', 'sm')}${r.estado === 'fallido' ? ' Reintentar' : ''}</button>`}
    </div>`;
  };
  const filaGrupo = (r) => `<button class="dl-yt-fila dl-yt-grupo" data-${r.tipo === 'album' ? 'album' : 'artista'}="${esc(r.browseId)}">
      ${r.portada ? `<img class="dl-yt-img ${r.tipo === 'artista' ? 'redonda' : ''}" src="${esc(r.portada)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : `<span class="dl-yt-img">${icon(r.tipo === 'album' ? 'disc' : 'user')}</span>`}
      <div class="dl-yt-txt"><b class="ellipsis">${esc(r.titulo)}</b>
        <span class="muted ellipsis">${r.tipo === 'album' ? `Álbum${(r.artistas || []).length ? ' · ' + esc(r.artistas.join(', ')) : ''}${r.anio ? ' · ' + esc(r.anio) : ''}` : 'Artista'}</span></div>
      ${icon('right', 'sm')}</button>`;

  const pintar = (hayMas = false) => {
    const box = $('[data-res]');
    if (!consulta) { box.innerHTML = ''; barra(); return; }
    if (!resultados.length) { box.innerHTML = emptyState('search', 'Sin resultados', 'Prueba con otras palabras o con el nombre del artista.'); barra(); return; }
    box.innerHTML = `<div class="dl-yt-lista">${resultados.map((r) => (r.videoId ? filaCancion(r) : filaGrupo(r))).join('')}</div>
      ${hayMas ? `<div class="row" style="justify-content:center;margin-top:10px"><button class="btn sm" data-mas>Cargar más</button></div>` : ''}`;
    box.querySelectorAll('[data-chk]').forEach((c) => c.addEventListener('change', () => { if (c.checked) elegidas.add(c.dataset.chk); else elegidas.delete(c.dataset.chk); barra(); }));
    box.querySelectorAll('[data-uno]').forEach((b) => b.addEventListener('click', () => agregar([b.dataset.uno], b)));
    box.querySelectorAll('[data-album]').forEach((b) => b.addEventListener('click', () => abrirGrupo('album', b.dataset.album)));
    box.querySelectorAll('[data-artista]').forEach((b) => b.addEventListener('click', () => abrirGrupo('artista', b.dataset.artista)));
    box.querySelector('[data-mas]')?.addEventListener('click', () => { limite = Math.min(50, limite + 15); buscar(); });
    barra();
  };

  const buscar = async () => {
    const box = $('[data-res]');
    box.innerHTML = `<p class="muted" role="status">${icon('clock', 'sm')} Buscando…</p>`;
    try {
      const r = await api(`/api/admin/descargas/buscar?q=${encodeURIComponent(consulta)}&tipo=${tipo}&limite=${limite}`, { timeout: 45000 });
      resultados = r.resultados;
      pintar(r.hayMas);
    } catch (e) {
      box.innerHTML = emptyState('info', 'No se pudo buscar', e.message || 'Inténtalo de nuevo', '<button class="btn sm" data-reint>Reintentar</button>');
      box.querySelector('[data-reint]')?.addEventListener('click', buscar);
    }
  };

  const agregar = async (ids, boton) => {
    if (boton) boton.disabled = true;
    try {
      const r = await api('/api/admin/descargas/canciones', { method: 'POST', body: { ids } });
      const omit = Object.keys(r.omitidas || {}).length;
      toast(r.agregadas ? `${r.agregadas} agregada(s) a la cola${omit ? ` · ${omit} ya estaban` : ''}` : 'Ya estaban en la biblioteca o en la cola');
      // Las agregadas pasan a "En cola" sin volver a buscar
      for (const x of resultados) if (ids.includes(x.videoId) && !r.omitidas?.[x.videoId]) x.estado = 'en_cola';
      for (const [id, e] of Object.entries(r.omitidas || {})) { const x = resultados.find((y) => y.videoId === id); if (x) x.estado = e; }
      ids.forEach((id) => elegidas.delete(id));
      pintar();
      alCambiar?.();
    } catch (e) { errorToast(e); if (boton) boton.disabled = false; }
  };

  const abrirGrupo = async (tipoGrupo, id) => {
    let datos;
    try { datos = await api(`/api/admin/descargas/buscar/${tipoGrupo}/${encodeURIComponent(id)}`, { timeout: 45000 }); }
    catch (e) { errorToast(e); return; }
    const info = datos.album || datos.artista || {};
    const lista = datos.resultados || [];
    const sel = new Set(lista.filter((x) => !bloqueado(x.estado)).map((x) => x.videoId));
    await modal({
      title: info.titulo || (tipoGrupo === 'album' ? 'Álbum' : 'Artista'),
      wide: true,
      body: `<p class="muted" style="margin-top:0">${tipoGrupo === 'album' ? `${lista.length} canciones${(info.artistas || []).length ? ' · ' + esc(info.artistas.join(', ')) : ''}` : 'Canciones más escuchadas del artista'}.
        Las que ya tienes vienen sin marcar.</p>
        <div class="dl-yt-lista">${lista.map((r) => {
          const [cls, txt] = ESTADOS[r.estado] || ['', ''];
          return `<label class="dl-yt-fila"><input type="checkbox" class="dl-yt-chk" value="${esc(r.videoId)}" ${sel.has(r.videoId) ? 'checked' : ''} ${bloqueado(r.estado) ? 'disabled' : ''}>
            <div class="dl-yt-txt"><b class="ellipsis">${esc(r.titulo)}</b><span class="muted ellipsis">${esc((r.artistas || []).join(', '))}</span></div>
            <span class="muted dl-yt-dur">${r.duracion ? fmtTime(r.duracion) : ''}</span>${txt ? `<span class="sop-chip ${cls}">${txt}</span>` : ''}</label>`;
        }).join('') || '<p class="muted">Sin canciones</p>'}</div>`,
      actions: [{ label: 'Cancelar', value: null }, { label: 'Agregar seleccionadas', value: 'ok', primary: true }],
      onAction: async (v, m) => {
        if (v !== 'ok') return;
        const ids = [...m.querySelectorAll('.dl-yt-chk:checked:not(:disabled)')].map((c) => c.value);
        if (!ids.length) { toast('No hay canciones nuevas seleccionadas'); return false; }
        await agregar(ids);
      },
    });
  };

  $('[data-form]').addEventListener('submit', (e) => {
    e.preventDefault();
    consulta = $('[data-q]').value.trim();
    if (consulta.length < 2) { toast('Escribe al menos 2 letras', { error: true }); return; }
    limite = 20;
    buscar();
  });
  caja.querySelectorAll('[data-tipo]').forEach((b) => b.addEventListener('click', () => {
    tipo = b.dataset.tipo;
    caja.querySelectorAll('[data-tipo]').forEach((x) => { x.classList.toggle('active', x === b); x.setAttribute('aria-selected', String(x === b)); });
    elegidas.clear();
    limite = 20;
    if (consulta) buscar();
  }));
}
