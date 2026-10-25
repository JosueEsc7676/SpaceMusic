// Navegación por rutas con # (funciona el botón atrás del navegador)
import { $, bus } from './util.js';
import { icon } from './icons.js';
import { esc } from './util.js';

const routes = [];
let renderSeq = 0;
let current = null;
const scrollPos = new Map();
const stack = [];

/** Registra una ruta: pattern como '/album/:nombre'. */
export function route(pattern, view) {
  const keys = [];
  const re = new RegExp('^' + pattern.replace(/\/:(\w+)/g, (_, k) => { keys.push(k); return '/([^/]+)'; }) + '/?$');
  routes.push({ re, keys, view });
}

export function parse(hash = location.hash) {
  const raw = hash.replace(/^#/, '') || '/';
  const [path, qs = ''] = raw.split('?');
  return { path, query: Object.fromEntries(new URLSearchParams(qs)) };
}

export function go(path, { replace = false } = {}) {
  const h = '#' + path;
  if (replace) { history.replaceState(null, '', h); render(); }
  else if (location.hash === h) render();
  else location.hash = h;
}

/** Pinta la ruta actual dentro de #view. */
export async function render() {
  const seq = ++renderSeq;
  const { path, query } = parse();
  const view = $('#view');
  if (current) scrollPos.set(current, view.scrollTop);
  const key = location.hash || '#/';
  const isBack = stack.length > 1 && stack[stack.length - 2] === key;
  if (isBack) stack.pop(); else if (stack[stack.length - 1] !== key) stack.push(key);
  if (stack.length > 100) stack.shift();

  let match = null, params = {};
  for (const r of routes) {
    const m = path.match(r.re);
    if (m) { match = r; r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); }); break; }
  }
  bus.emit('route', { path, query });

  const inner = document.createElement('div');
  inner.className = 'view-inner page-enter';
  setHero(null);
  setTitle('');

  if (!match) {
    inner.innerHTML = `<div class="empty">${icon('compass')}<h3>Página no encontrada</h3><p>La dirección no existe.</p><a class="btn primary" href="#/">Ir al inicio</a></div>`;
    view.replaceChildren(inner);
    current = key;
    return;
  }

  // Esqueleto de carga si tarda más de 120 ms
  const timer = setTimeout(() => {
    if (seq !== renderSeq) return;
    view.innerHTML = `<div class="loading-view"><div class="skeleton h"></div><div class="grid">${'<div class="skeleton c"></div>'.repeat(6)}</div></div>`;
  }, 120);

  try {
    const isStale = () => seq !== renderSeq;
    await match.view(inner, params, query, isStale);
    if (isStale()) return;
    clearTimeout(timer);
    const hero = document.createElement('div');
    hero.className = 'hero-bg';
    view.replaceChildren(hero, inner);
    const back = scrollPos.get(key);
    view.scrollTop = isBack && back ? back : 0;
    current = key;
    bus.emit('rendered');
  } catch (e) {
    clearTimeout(timer);
    if (seq !== renderSeq) return;
    console.error(e);
    inner.innerHTML = `<div class="empty">${icon('info')}<h3>No se pudo cargar</h3><p>${esc(e.message || 'Error')}</p><button class="btn primary" data-retry>Reintentar</button></div>`;
    inner.querySelector('[data-retry]').onclick = () => render();
    view.replaceChildren(inner);
    current = key;
  }
}

/** Color del fondo superior de la página (rgb). */
export function setHero(rgb) {
  const c = rgb || [40, 40, 52];
  document.documentElement.style.setProperty('--hero', c.join(','));
}

/** Título que aparece en la barra superior al hacer scroll. */
export function setTitle(t) {
  const el = $('#topTitle');
  if (el) el.textContent = t || '';
}

export function startRouter() {
  window.addEventListener('hashchange', () => render());
  render();
}
