// Utilidades generales
import { icon } from './icons.js';

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
/** Escapa texto para insertarlo en HTML (también dentro de atributos). */
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ESC[c]);

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

export function fmtTime(sec) {
  if (!isFinite(sec) || sec < 0) sec = 0;
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = Math.floor(sec % 60);
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
}

export function fmtLong(sec) {
  sec = Math.round(sec || 0);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
  if (h > 0) return `${h} h ${m} min`;
  if (m > 0) return `${m} min`;
  return `${sec} s`;
}

export function fmtNum(n) {
  n = Number(n) || 0;
  if (n >= 1e6) return (n / 1e6).toFixed(1).replace('.0', '') + ' M';
  if (n >= 1e4) return Math.round(n / 1e3) + ' K';
  return n.toLocaleString('es');
}

export function plural(n, uno, varios) {
  return `${fmtNum(n)} ${n === 1 ? uno : varios}`;
}

export function fmtDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d)) return '—';
  return d.toLocaleDateString('es', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function timeAgo(iso) {
  if (!iso) return 'nunca';
  const d = new Date(iso), s = (Date.now() - d) / 1000;
  if (s < 60) return 'hace un momento';
  if (s < 3600) return `hace ${Math.floor(s / 60)} min`;
  if (s < 86400) return `hace ${Math.floor(s / 3600)} h`;
  if (s < 86400 * 30) return `hace ${Math.floor(s / 86400)} d`;
  return fmtDate(iso);
}

/** Minúsculas y sin acentos, para filtrar listas. */
export const norm = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();

/** URL de portada en el tamaño pedido (solo para portadas del servidor). */
export function cover(url, size = 300) {
  if (!url) return '';
  if (url.startsWith('/api/portada/db/')) {
    // Conserva la huella (?v=) para que el navegador no reutilice una portada vieja
    const [base, qs = ''] = url.split('?');
    const v = new URLSearchParams(qs).get('v');
    return base + '?s=' + size + (v ? '&v=' + v : '');
  }
  return url;
}

export function coverImg(url, size = 300, alt = '', cls = '') {
  if (!url) return `<div class="cover-fallback ${cls}">${icon('note')}</div>`;
  return `<img src="${esc(cover(url, size))}" alt="${esc(alt)}" loading="lazy" decoding="async" class="${cls}" data-fallback>`;
}

/** Mosaico de 4 portadas (o 1) para playlists y mezclas. */
export function mosaic(urls, size = 300) {
  const u = [...new Set((urls || []).filter(Boolean))];
  if (u.length === 0) return `<div class="cover-fallback">${icon('note')}</div>`;
  if (u.length < 4) return `<div class="mosaic one"><img src="${esc(cover(u[0], size))}" alt="" loading="lazy" decoding="async" data-fallback></div>`;
  const half = size >= 600 ? 300 : 160; // cada cuadro ocupa la mitad: basta una miniatura más pequeña
  return `<div class="mosaic">${u.slice(0, 4).map((x) => `<img src="${esc(cover(x, half))}" alt="" loading="lazy" decoding="async" data-fallback>`).join('')}</div>`;
}

export function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

export function throttle(fn, ms) {
  let last = 0, t;
  return (...a) => {
    const now = Date.now();
    clearTimeout(t);
    if (now - last >= ms) { last = now; fn(...a); }
    else t = setTimeout(() => { last = Date.now(); fn(...a); }, ms - (now - last));
  };
}

export function shuffleArr(a) {
  const r = [...a];
  for (let i = r.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [r[i], r[j]] = [r[j], r[i]];
  }
  return r;
}

// ── Almacenamiento local seguro (modo privado, cuota llena, etc.) ──
export const store = {
  get(k, def = null) {
    try { const v = localStorage.getItem(k); return v == null ? def : JSON.parse(v); } catch { return def; }
  },
  set(k, v) {
    try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* sin espacio */ }
  },
  del(k) { try { localStorage.removeItem(k); } catch { } },
};

// ── Bus de eventos sencillo ──
const listeners = new Map();
export const bus = {
  on(ev, fn) { if (!listeners.has(ev)) listeners.set(ev, new Set()); listeners.get(ev).add(fn); return () => listeners.get(ev).delete(fn); },
  emit(ev, data) { listeners.get(ev)?.forEach((fn) => { try { fn(data); } catch (e) { console.error(e); } }); },
};

/** Color dominante de una imagen (para los fondos). Devuelve [r,g,b]. */
const colorCache = new Map();
export function dominantColor(url) {
  if (!url) return Promise.resolve([70, 66, 92]);
  const key = cover(url, 64);
  if (colorCache.has(key)) return Promise.resolve(colorCache.get(key));
  return new Promise((resolve) => {
    const img = new Image();
    img.decoding = 'async';
    img.onload = () => {
      try {
        const c = document.createElement('canvas');
        c.width = c.height = 24;
        const ctx = c.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(img, 0, 0, 24, 24);
        const d = ctx.getImageData(0, 0, 24, 24).data;
        let best = null, bestScore = -1, r = 0, g = 0, b = 0, n = 0;
        const buckets = new Map();
        for (let i = 0; i < d.length; i += 4) {
          const R = d[i], G = d[i + 1], B = d[i + 2];
          r += R; g += G; b += B; n++;
          const max = Math.max(R, G, B), min = Math.min(R, G, B);
          const sat = max === 0 ? 0 : (max - min) / max, lum = max / 255;
          if (lum < .15 || lum > .95) continue;
          const k = `${R >> 5},${G >> 5},${B >> 5}`;
          const e = buckets.get(k) || { c: 0, R: 0, G: 0, B: 0, sat: 0 };
          e.c++; e.R += R; e.G += G; e.B += B; e.sat += sat;
          buckets.set(k, e);
        }
        for (const e of buckets.values()) {
          const score = e.c * (0.4 + e.sat / e.c);
          if (score > bestScore) { bestScore = score; best = [e.R / e.c, e.G / e.c, e.B / e.c]; }
        }
        let col = best || [r / n, g / n, b / n];
        // Ajustar brillo para que el texto blanco se lea bien
        const lum = (0.299 * col[0] + 0.587 * col[1] + 0.114 * col[2]) / 255;
        const f = lum > 0.6 ? 0.6 / lum : lum < 0.25 ? 0.3 / Math.max(lum, 0.05) : 1;
        col = col.map((x) => Math.max(0, Math.min(255, Math.round(x * f))));
        colorCache.set(key, col);
        resolve(col);
      } catch { resolve([80, 80, 100]); }
    };
    img.onerror = () => resolve([80, 80, 100]);
    img.src = key;
  });
}

/** Copia texto al portapapeles. */
export async function copyText(text) {
  try { await navigator.clipboard.writeText(text); return true; } catch {
    const ta = document.createElement('textarea');
    ta.value = text; document.body.appendChild(ta); ta.select();
    const ok = document.execCommand('copy'); ta.remove(); return ok;
  }
}

export const isMobile = () => matchMedia('(max-width: 820px)').matches;
