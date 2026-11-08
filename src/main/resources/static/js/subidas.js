// Subida de canciones en segundo plano: sigue aunque cambies de pantalla.
// La pantalla "Subir canciones" solo muestra este estado; al terminar se avisa con
// una notificación (cuántas, tamaño total y géneros).
import { api, upload } from './api.js';
import { bus } from './util.js';
import { icon } from './icons.js';
import { toast } from './ui.js';
import { avisoSistema } from './avisos.js';

export const EXT = ['mp3', 'flac', 'm4a', 'ogg', 'opus', 'wav', 'aac', 'wma'];
const MAX = 200 * 1024 * 1024;

let seq = 0;
/** Archivos de la subida actual (y de las anteriores, hasta que se limpie la lista). */
export const items = [];
let lote = null; // { total, hechas, fallidas, bytes, generos: Map, artistas: Set }
const carriles = [Promise.resolve(), Promise.resolve()];

export const subiendo = () => !!lote && lote.hechas < lote.total;
export const resumenLote = () => lote;

let aviso = 0;
const cambio = (inmediato) => {
  // El progreso cambia muchas veces por segundo: se avisa como máximo cada 150 ms
  if (inmediato) { bus.emit('subidas'); return; }
  if (aviso) return;
  aviso = setTimeout(() => { aviso = 0; bus.emit('subidas'); }, 150);
};

export const tamano = (b) => (b >= 1024 ** 3 ? (b / 1024 ** 3).toFixed(1) + ' GB' : b >= 1024 ** 2 ? Math.round(b / 1024 ** 2) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB');

export function agregar(files) {
  if (!lote || lote.hechas >= lote.total) lote = { total: 0, hechas: 0, fallidas: 0, bytes: 0, generos: new Map(), artistas: new Set() };
  for (const f of files) {
    const ext = f.name.split('.').pop().toLowerCase();
    const it = { id: ++seq, nombre: f.name, tamano: f.size, estado: 'espera', progreso: 0, mensaje: 'En espera…', cancion: null };
    items.unshift(it);
    if (!EXT.includes(ext)) { it.estado = 'error'; it.mensaje = 'Formato no soportado'; continue; }
    if (f.size > MAX) { it.estado = 'error'; it.mensaje = 'Demasiado grande (máx. 200 MB)'; continue; }
    lote.total++;
    // Dos a la vez: más rápido sin saturar la conexión
    const c = lote.total % 2;
    const este = lote;
    carriles[c] = carriles[c].then(() => subir(f, it, este));
  }
  cambio(true);
}

async function subir(f, it, l) {
  const fd = new FormData();
  fd.append('files', f);
  it.estado = 'subiendo';
  it.mensaje = 'Subiendo…';
  cambio(true);
  try {
    const [r] = await upload('/api/canciones/subir', fd, (p) => { it.progreso = p; it.mensaje = `Subiendo… ${Math.round(p * 100)}%`; cambio(); });
    if (!r.ok) throw new Error(r.error);
    it.estado = 'lista';
    it.progreso = 1;
    it.cancion = r.cancion || null;
    l.bytes += f.size;
    const g = r.cancion?.genero;
    if (g && g !== 'Desconocido') l.generos.set(g, (l.generos.get(g) || 0) + 1);
    if (r.cancion?.artista) l.artistas.add(r.cancion.artista);
    bus.emit('library:changed');
  } catch (e) {
    it.estado = 'error';
    it.mensaje = e.message || 'Error';
    l.fallidas++;
  }
  l.hechas++;
  cambio(true);
  if (l.hechas === l.total) terminar(l);
}

function terminar(l) {
  const ok = l.total - l.fallidas;
  const gen = [...l.generos.entries()].sort((a, b) => b[1] - a[1]).map(([g]) => g);
  let texto = ok
    ? `Se ${ok === 1 ? 'subió 1 canción' : `subieron ${ok} canciones`} (${tamano(l.bytes)})`
    : 'No se pudo subir ninguna canción';
  if (ok) texto += gen.length ? `. Géneros: ${gen.slice(0, 4).join(', ')}${gen.length > 4 ? '…' : ''}` : '. El género se buscará en unos minutos';
  if (l.fallidas) texto += `. ${l.fallidas} con error`;
  texto += '.';
  const local = () => {
    toast(texto, { ms: 8000, error: !ok, action: { label: 'Ver', fn: () => { location.hash = '#/subir'; } } });
    avisoSistema('Subida terminada', texto, '#/subir');
  };
  // El aviso queda en la campana y llega como notificación (teléfono y web); si eso falla, se avisa aquí mismo
  if (ok) api('/api/notificaciones/subida', { method: 'POST', body: { texto } }).catch(local);
  else local();
}

/** Quita de la lista las subidas terminadas. */
export function limpiar() {
  for (let i = items.length - 1; i >= 0; i--) if (items[i].estado === 'lista' || items[i].estado === 'error') items.splice(i, 1);
  cambio(true);
}

// Indicador fuera de la pantalla de subir: "Subiendo 3 de 10" (lleva a la pantalla al tocarlo)
function indicador() {
  let el = document.querySelector('.subidas-pill');
  const mostrar = subiendo() && !location.hash.startsWith('#/subir');
  if (!mostrar) { el?.remove(); return; }
  if (!el) {
    el = document.createElement('a');
    el.className = 'subidas-pill';
    el.href = '#/subir';
    document.body.appendChild(el);
  }
  const pct = Math.round(items.filter((i) => i.estado === 'subiendo').reduce((a, i) => a + i.progreso, 0) * 100 / Math.max(1, lote.total)
    + (lote.hechas / Math.max(1, lote.total)) * 100);
  el.innerHTML = `${icon('upload', 'sm')}<span>Subiendo ${lote.hechas + 1 > lote.total ? lote.total : lote.hechas + 1} de ${lote.total}</span><i style="width:${Math.min(100, pct)}%"></i>`;
}
bus.on('subidas', indicador);
bus.on('route', () => setTimeout(indicador, 0));

// En el navegador, avisar antes de cerrar la pestaña si aún se está subiendo
window.addEventListener('beforeunload', (e) => {
  if (subiendo()) { e.preventDefault(); e.returnValue = ''; }
});

