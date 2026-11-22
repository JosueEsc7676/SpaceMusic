// Funciones para los usuarios: reportar un problema de una canción,
// elegir gustos al crear la cuenta y "Tu año en SpaceMusic".
import { api, session } from './api.js';
import { esc, cover, coverImg, plural, fmtNum, store, esIOS, textoSinRed } from './util.js';
import { icon } from './icons.js';
import { setHero, setTitle } from './router.js';
import { toast, errorToast, modal, emptyState, trackList, registerList } from './ui.js';
import { sinConexion } from './offline.js';
import { player } from './player.js';

const cliente = () => (window.SM_TV ? 'tv' : window.SpaceMusicApp ? 'android' : esIOS ? 'iphone' : 'web');
const necesitaRed = () => {
  if (!sinConexion()) return false;
  toast(textoSinRed() + ': inténtalo cuando vuelvas a estar en línea', { error: true });
  return true;
};

// ═══════════════════ Reportar un problema ═══════════════════

const MOTIVOS = [
  ['audio', 'No suena o se corta'],
  ['letra', 'La letra está mal o desfasada'],
  ['datos', 'Nombre, artista o álbum incorrecto'],
  ['portada', 'La portada no corresponde'],
  ['duplicada', 'Está repetida'],
  ['otro', 'Otro problema'],
];

export async function reportarProblema(song) {
  if (necesitaRed()) return;
  await modal({
    title: 'Reportar un problema',
    body: `<div class="row" style="gap:12px;margin-bottom:12px">${coverImg(song.portadaUrl, 48)}<div style="min-width:0"><b class="ellipsis" style="display:block">${esc(song.nombre)}</b><span class="muted ellipsis" style="display:block">${esc(song.artista)}</span></div></div>
      <div class="rep-motivos">${MOTIVOS.map(([v, t], i) => `<label class="rep-motivo"><input type="radio" name="motivo" value="${v}" ${i === 0 ? 'checked' : ''}><span>${esc(t)}</span></label>`).join('')}</div>
      <div class="field"><label>Cuéntanos más (opcional)</label><textarea data-detalle maxlength="500" placeholder="Por ejemplo: se corta en el minuto 2"></textarea></div>
      <p class="muted" style="font-size:12px;margin:0">El administrador lo revisará y te avisaremos cuando esté arreglado.</p>`,
    actions: [{ label: 'Cancelar', value: null }, { label: 'Enviar reporte', value: 'ok', primary: true }],
    onAction: async (v, m) => {
      if (v !== 'ok') return;
      const motivo = m.querySelector('input[name=motivo]:checked')?.value;
      const r = await api('/api/reportes', { method: 'POST', body: { cancionId: song.id, motivo, detalle: m.querySelector('[data-detalle]').value, cliente: cliente() } });
      toast(r.repetido ? 'Ya habías reportado esto: lo estamos revisando' : 'Gracias, enviamos tu reporte');
    },
  });
}

// ═══════════════════ Gustos al crear la cuenta ═══════════════════

export async function elegirGustos({ inicial = false } = {}) {
  if (necesitaRed()) return;
  const [op, actuales] = await Promise.all([api('/api/gustos/opciones'), api('/api/gustos')]);
  const gen = new Set(actuales.generos || []);
  const art = new Set(actuales.artistas || []);
  const body = `<p class="muted" style="margin-top:0">${inicial ? '¡Bienvenido a SpaceMusic! ' : ''}Elige lo que te gusta y armaremos tu inicio y tus mixes con eso. Puedes cambiarlo cuando quieras en Ajustes.</p>
    <h4 class="gustos-t">Géneros</h4>
    <div class="gustos-generos">${op.generos.map((g) => `<button type="button" class="chip ${gen.has(g.nombre) ? 'active' : ''}" data-gen="${esc(g.nombre)}">${esc(g.nombre)}</button>`).join('')}</div>
    <h4 class="gustos-t">Artistas</h4>
    <div class="field" style="margin-bottom:8px"><input data-buscar placeholder="Buscar artista" autocomplete="off"></div>
    <div class="gustos-artistas">${op.artistas.map((a) => `<button type="button" class="gusto-art ${art.has(a.nombre) ? 'active' : ''}" data-art="${esc(a.nombre)}">
      <span class="ga-img">${a.portadaUrl ? `<img src="${esc(cover(a.portadaUrl, 160))}" alt="" loading="lazy">` : icon('user')}<span class="ga-check">${icon('check', 'sm')}</span></span>
      <span class="ga-n ellipsis">${esc(a.nombre)}</span></button>`).join('')}</div>`;
  return modal({
    title: inicial ? '¿Qué música te gusta?' : 'Tus gustos',
    wide: true,
    body,
    actions: [{ label: inicial ? 'Omitir' : 'Cancelar', value: 'omitir' }, { label: 'Listo', value: 'ok', primary: true }],
    onOpen: (m) => {
      m.classList.add('gustos-modal');
      m.addEventListener('click', (e) => {
        const g = e.target.closest('[data-gen]');
        if (g) { g.classList.toggle('active'); if (g.classList.contains('active')) gen.add(g.dataset.gen); else gen.delete(g.dataset.gen); }
        const a = e.target.closest('[data-art]');
        if (a) { a.classList.toggle('active'); if (a.classList.contains('active')) art.add(a.dataset.art); else art.delete(a.dataset.art); }
      });
      const q = m.querySelector('[data-buscar]');
      q.addEventListener('input', () => {
        const t = q.value.trim().toLowerCase();
        m.querySelectorAll('[data-art]').forEach((b) => { b.hidden = t && !b.dataset.art.toLowerCase().includes(t); });
      });
    },
    onAction: async (v) => {
      if (v === 'omitir') {
        // La primera vez, "Omitir" también cuenta como hecho (no se vuelve a preguntar)
        if (inicial) await api('/api/gustos', { method: 'PUT', body: { generos: [], artistas: [] } }).catch(() => { });
        return;
      }
      await api('/api/gustos', { method: 'PUT', body: { generos: [...gen], artistas: [...art] } });
      toast('¡Listo! Tu inicio se armará con lo que te gusta');
      if (location.hash === '' || location.hash === '#/') window.dispatchEvent(new HashChangeEvent('hashchange'));
    },
  });
}

/** Al abrir la app: a quien creó su cuenta hace poco y no eligió sus gustos, se le pregunta una vez. */
export async function revisarBienvenida() {
  const u = session.user;
  if (!u || sinConexion() || window.SM_TV) return;
  const clave = 'sm_gustos_preguntado:' + u.id;
  if (store.get(clave)) return;
  const reg = u.fechaRegistro ? new Date(u.fechaRegistro) : null;
  if (!reg || Date.now() - reg.getTime() > 30 * 86400000) return;
  try {
    const g = await api('/api/gustos');
    store.set(clave, 1);
    if (!g.hecho) await elegirGustos({ inicial: true });
  } catch { }
}

// ═══════════════════ Tu año / tu mes en SpaceMusic ═══════════════════

const MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

export async function resumenView(el, _p, query, isStale) {
  setHero(null);
  setTitle('Tu resumen');
  if (sinConexion()) {
    el.innerHTML = `<h1 class="page-title">Tu resumen</h1>` + emptyState('download', textoSinRed(), 'Tu resumen se arma con datos del servidor: vuelve cuando estés en línea.');
    return;
  }
  const hoy = new Date();
  const semana = query?.semana === '1' || query?.semana === 'true';
  const anio = Number(query?.anio) || hoy.getFullYear();
  const mes = !semana && query?.mes ? Number(query.mes) : null;
  const [r, per] = await Promise.all([
    api(semana ? '/api/resumen?semana=true' : `/api/resumen?anio=${anio}${mes ? '&mes=' + mes : ''}`),
    api('/api/resumen/periodos').catch(() => ({ anios: [anio] })),
  ]);
  if (isStale?.()) return;
  const anios = per.anios?.length ? per.anios : [anio];
  const selector = `<div class="row wrap resumen-sel" style="gap:8px">
    <select data-anio aria-label="Año">${anios.map((a) => `<option value="${a}" ${a === anio ? 'selected' : ''}>${a}</option>`).join('')}</select>
    <select data-mes aria-label="Período"><option value="semana" ${semana ? 'selected' : ''}>Esta semana</option><option value="" ${!semana && !mes ? 'selected' : ''}>Todo el año</option>${MESES.map((m, i) => (anio === hoy.getFullYear() && i > hoy.getMonth()) ? '' : `<option value="${i + 1}" ${mes === i + 1 ? 'selected' : ''}>${m}</option>`).join('')}</select>
  </div>`;
  const cabecera = `<div class="row wrap" style="justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px">
    <h1 class="page-title" style="margin:0">${esc(r.titulo)}</h1>${selector}</div>`;

  if (!r.suficiente) {
    el.innerHTML = cabecera + emptyState('gift', 'Aún no hay resumen', r.nota || 'Escucha más música en este período.');
  } else {
    const top = r.topCanciones || [];
    const lista = top.map((x) => x.cancion);
    const listId = registerList(lista, { type: 'resumen', label: r.titulo });
    el.innerHTML = cabecera + `
      ${r.enCurso ? `<p class="muted" style="margin-top:-6px">El período sigue en curso: los números se actualizan mientras escuchas.</p>` : ''}
      <div class="resumen-grid">
        <div class="resumen-card grande"><span class="rc-k">Minutos escuchados</span><b class="rc-v">${fmtNum(r.minutos)}</b>
          <span class="rc-s">${plural(r.reproducciones, 'reproducción', 'reproducciones')} · ${plural(r.diasActivos, 'día', 'días')} con música</span></div>
        <div class="resumen-card"><span class="rc-k">Canciones distintas</span><b class="rc-v">${fmtNum(r.cancionesDistintas)}</b></div>
        <div class="resumen-card"><span class="rc-k">Artistas</span><b class="rc-v">${fmtNum(r.artistasDistintos)}</b><span class="rc-s">${esc(r.estilo)}</span></div>
        <div class="resumen-card"><span class="rc-k">Mejor racha</span><b class="rc-v">${plural(r.racha, 'día', 'días')}</b><span class="rc-s">seguidos escuchando</span></div>
        <div class="resumen-card"><span class="rc-k">Tu momento</span><b class="rc-v" style="font-size:22px">${esc(r.momento)}</b><span class="rc-s">sobre todo los ${esc(r.diaFavorito)}${r.mesMasActivo ? ` · tu mes: ${esc(r.mesMasActivo)}` : ''}</span></div>
        ${r.cambioPorcentaje != null ? `<div class="resumen-card"><span class="rc-k">Comparado con ${semana ? 'la semana anterior' : mes ? 'el mes anterior' : 'el año anterior'}</span>
          <b class="rc-v" style="color:${r.cambioPorcentaje >= 0 ? 'var(--accent)' : 'inherit'}">${r.cambioPorcentaje > 0 ? '+' : ''}${r.cambioPorcentaje}%</b>
          <span class="rc-s">${r.cambioPorcentaje >= 0 ? 'escuchaste más' : 'escuchaste menos'} (${fmtNum(r.reproduccionesAnterior)} → ${fmtNum(r.reproducciones)})</span></div>` : ''}
        ${r.descubrimiento ? `<a class="resumen-card resumen-descubrimiento" href="#/artista/${encodeURIComponent(r.descubrimiento.nombre)}">
          <span class="rc-k">Tu descubrimiento</span>${coverImg(r.descubrimiento.portadaUrl, 56)}
          <b class="rc-v" style="font-size:22px">${esc(r.descubrimiento.nombre)}</b>
          <span class="rc-s">nuevo para ti · ${plural(r.descubrimiento.minutos, 'minuto', 'minutos')}</span></a>` : ''}
      </div>
      ${r.topArtistas?.length ? `<h2 class="resumen-h">Tus artistas</h2><div class="resumen-artistas">${r.topArtistas.map((a, i) => `
        <a class="resumen-art" href="#/artista/${encodeURIComponent(a.nombre)}"><span class="ra-n">${i + 1}</span>${coverImg(a.portadaUrl, 72)}
          <span style="min-width:0"><b class="ellipsis" style="display:block">${esc(a.nombre)}</b><span class="muted">${plural(a.minutos, 'minuto', 'minutos')}</span></span></a>`).join('')}</div>` : ''}
      ${lista.length ? `<h2 class="resumen-h">Tus canciones</h2>${trackList(lista, { context: { type: 'resumen', label: r.titulo }, showAlbum: false })}` : ''}
      ${r.topGeneros?.length ? `<h2 class="resumen-h">Tus géneros</h2><div class="resumen-generos">${r.topGeneros.map((g) => `
        <div class="rg"><span>${esc(g.nombre)}</span><span class="rg-bar"><i style="width:${Math.max(4, g.porcentaje)}%"></i></span><b>${g.porcentaje}%</b></div>`).join('')}</div>` : ''}
      ${r.serie?.length > 1 ? `<h2 class="resumen-h">${mes || semana ? 'Minutos por día' : 'Minutos por mes'}</h2>${barrasResumen(r.serie, !mes && !semana)}` : ''}
      ${r.primeraCancion ? `<p class="muted" style="margin-top:24px">La primera canción del período fue <b>${esc(r.primeraCancion.nombre)}</b> de ${esc(r.primeraCancion.artista)}.</p>` : ''}
      <div class="row wrap" style="gap:10px;margin-top:20px">
        ${lista.length ? `<button class="btn primary" data-oir>${icon('play', 'sm')} Escuchar tus favoritas</button>` : ''}
        <button class="btn" data-compartir>${icon('send', 'sm')} Compartir tarjeta</button></div>`;
    el.querySelector('[data-oir]')?.addEventListener('click', () => player.play(lista, 0, { type: 'resumen', label: r.titulo, route: location.hash }));
    el.querySelector('[data-compartir]')?.addEventListener('click', () => compartir(r).catch(errorToast));
    void listId;
  }
  const ir = () => {
    const a = el.querySelector('[data-anio]').value, m = el.querySelector('[data-mes]').value;
    location.hash = m === 'semana' ? '#/resumen?semana=1' : `#/resumen?anio=${a}${m ? '&mes=' + m : ''}`;
  };
  el.querySelector('[data-anio]').onchange = () => { el.querySelector('[data-mes]').value = ''; ir(); };
  el.querySelector('[data-mes]').onchange = ir;
}

function barrasResumen(serie, porMes) {
  const max = Math.max(1, ...serie.map((s) => s.minutos));
  return `<div class="resumen-barras">${serie.map((s) => {
    const et = porMes ? MESES[Number(s.periodo.slice(5, 7)) - 1].slice(0, 3) : String(Number(s.periodo.slice(8, 10)));
    return `<div class="rb" title="${esc(s.periodo)}: ${s.minutos} min"><i style="height:${Math.max(3, Math.round(100 * s.minutos / max))}%"></i><span>${et}</span></div>`;
  }).join('')}</div>`;
}

/** Tarjeta para compartir (imagen 1080×1350) con lo principal del resumen. */
async function compartir(r) {
  const c = document.createElement('canvas');
  c.width = 1080;
  c.height = 1350;
  const g = c.getContext('2d');
  const fondo = g.createLinearGradient(0, 0, 1080, 1350);
  fondo.addColorStop(0, '#3b0d17');
  fondo.addColorStop(0.55, '#120b10');
  fondo.addColorStop(1, '#000');
  g.fillStyle = fondo;
  g.fillRect(0, 0, 1080, 1350);
  const fuente = getComputedStyle(document.body).fontFamily || 'sans-serif';
  const texto = (t, x, y, size, color = '#fff', peso = 700) => { g.fillStyle = color; g.font = `${peso} ${size}px ${fuente}`; g.fillText(t, x, y); };
  const recortar = (t, max, size, peso = 700) => {
    g.font = `${peso} ${size}px ${fuente}`;
    if (g.measureText(t).width <= max) return t;
    while (t.length > 1 && g.measureText(t + '…').width > max) t = t.slice(0, -1);
    return t + '…';
  };
  texto('SpaceMusic', 80, 120, 40, 'rgba(255,255,255,.7)', 600);
  texto(recortar(r.titulo, 920, 64), 80, 210, 64);
  texto(fmtNum(r.minutos), 80, 380, 150);
  texto('minutos escuchados', 80, 440, 40, 'rgba(255,255,255,.75)', 500);
  let y = 560;
  texto('Artistas', 80, y, 36, 'rgba(255,255,255,.6)', 600);
  (r.topArtistas || []).slice(0, 5).forEach((a, i) => texto(`${i + 1}. ${recortar(a.nombre, 420, 40)}`, 80, y + 60 + i * 56, 40));
  texto('Canciones', 560, y, 36, 'rgba(255,255,255,.6)', 600);
  (r.topCanciones || []).slice(0, 5).forEach((x, i) => texto(`${i + 1}. ${recortar(x.cancion.nombre, 440, 40)}`, 560, y + 60 + i * 56, 40));
  texto(`${r.cancionesDistintas} canciones · ${r.artistasDistintos} artistas · racha de ${r.racha} días`, 80, 1000, 34, 'rgba(255,255,255,.8)', 500);
  if (r.topGeneros?.[0]) texto(`Género favorito: ${recortar(r.topGeneros[0].nombre, 700, 34, 500)}`, 80, 1060, 34, 'rgba(255,255,255,.8)', 500);
  texto(`Escuchas ${r.momento} · ${r.estilo}`, 80, 1120, 34, 'rgba(255,255,255,.8)', 500);
  texto('@' + (session.user?.username || ''), 80, 1260, 34, 'rgba(255,255,255,.55)', 500);

  const blob = await new Promise((res) => c.toBlob(res, 'image/png'));
  const nombre = r.titulo.replace(/[^\p{L}\p{N}]+/gu, '-').toLowerCase() + '.png';
  if (window.SpaceMusicApp?.guardarImagen) {
    const { compartirImagen } = await import('./dedicatorias.js');
    await compartirImagen(blob, nombre);
    return;
  }
  const archivo = new File([blob], nombre, { type: 'image/png' });
  if (navigator.canShare?.({ files: [archivo] })) {
    try { await navigator.share({ files: [archivo], title: r.titulo }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  // Sin "Compartir" del sistema: se muestra la imagen para guardarla o hacerle captura
  const url = URL.createObjectURL(blob);
  await modal({
    title: 'Tu tarjeta',
    body: `<img src="${url}" alt="${esc(r.titulo)}" style="width:100%;border-radius:12px;display:block">
      <p class="muted" style="font-size:13px">${window.SpaceMusicApp ? 'Mantén presionada la imagen o haz una captura de pantalla para compartirla.' : 'Descárgala y compártela donde quieras.'}</p>`,
    actions: window.SpaceMusicApp ? [{ label: 'Cerrar', value: null }]
      : [{ label: 'Cerrar', value: null }, { label: 'Descargar', value: 'd', primary: true }],
    onAction: (v) => {
      if (v !== 'd') return;
      const a = document.createElement('a');
      a.href = url;
      a.download = nombre;
      document.body.appendChild(a);
      a.click();
      a.remove();
    },
  });
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
