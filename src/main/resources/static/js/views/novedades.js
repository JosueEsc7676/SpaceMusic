// Historial de novedades de SpaceMusic (lo abren las notificaciones de actualización)
import { esc } from '../util.js';
import { setHero, setTitle } from '../router.js';
import { emptyState } from '../ui.js';

const mayuscula = (t) => t.charAt(0).toUpperCase() + t.slice(1);

export async function novedadesView(el, { version } = {}) {
  setTitle('Novedades');
  setHero(null);
  let n;
  try { n = await fetch('/app/novedades.json', { cache: 'no-store' }).then((r) => r.json()); } catch {
    el.innerHTML = emptyState('sparkles', 'Sin conexión', 'Vuelve a intentarlo cuando tengas internet.');
    return;
  }
  const versiones = [{ version: n.version, titulo: n.titulo, puntos: n.puntos }, ...(n.anteriores || [])];
  el.innerHTML = `<header class="lib-head"><div><div class="eyebrow">SpaceMusic</div><h1 class="page-title">Novedades</h1></div></header>
    <div class="nov-list">${versiones.map((v, i) => `<section class="nov ${v.version === (version || n.version) ? 'destacada' : ''}" id="v${esc(v.version)}">
      <div class="nov-ver">${i === 0 ? '<span class="badge accent">Actual</span>' : ''} Versión ${esc(v.version)}</div>
      <h2>${esc(mayuscula((v.titulo || '').replace(/^Novedades:?\s*/i, '') || 'Mejoras'))}</h2>
      <ul class="novedades">${(v.puntos || []).map((x) => `<li>${esc(x)}</li>`).join('')}</ul></section>`).join('')}</div>`;
  el.querySelector('.nov.destacada')?.scrollIntoView({ block: 'start' });
}
