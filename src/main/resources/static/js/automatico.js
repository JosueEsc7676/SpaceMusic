// Tareas automáticas de la app de Android:
//  - Descargas inteligentes: con wifi y el teléfono cargando, una vez al día se guardan para escuchar sin conexión
//    tus más escuchadas, el Mix del día y lo nuevo de tus artistas (desactivable en Ajustes).
//  - Avisos al instante: si no hay una app «distribuidora» (ntfy), se sugiere instalarla una sola vez.
import { api } from './api.js';
import { store } from './util.js';
import { toast } from './ui.js';
import { descargar } from './offline.js';

const app = window.SpaceMusicApp;
const CLAVE = 'sm_auto_descargas';
const ULTIMA = 'sm_auto_descargas_ultima';
const MAX = 60;

export const descargasAutomaticas = {
  get disponibles() { return !!app?.wifiYCargando; },
  get activas() { return store.get(CLAVE, true) !== false; },
  set activas(v) { store.set(CLAVE, !!v); if (v) revisar(true); },
};

let corriendo = false;
async function revisar(forzar = false) {
  if (corriendo || !descargasAutomaticas.disponibles || !descargasAutomaticas.activas) return;
  if (!forzar && Date.now() - (store.get(ULTIMA, 0) || 0) < 20 * 3600 * 1000) return;
  let listo = false;
  try { listo = app.wifiYCargando(); } catch { listo = false; }
  if (!listo) return;
  corriendo = true;
  try {
    const [inicio, mix] = await Promise.all([
      api('/api/inicio').catch(() => ({})),
      api('/api/mixes/diario?cantidad=25').catch(() => []),
    ]);
    const top = inicio.tusFavoritas || [];
    const misArtistas = new Set(top.map((c) => (c.artista || '').toLowerCase()));
    const nuevasDeMisArtistas = (inicio.recienAgregadas || []).filter((c) => misArtistas.has((c.artista || '').toLowerCase()));
    const vistos = new Set();
    const lista = [...top, ...mix, ...nuevasDeMisArtistas].filter((c) => c?.id && !vistos.has(c.id) && vistos.add(c.id)).slice(0, MAX);
    if (lista.length) {
      descargar(lista);
      store.set(ULTIMA, Date.now());
      toast(`Guardando ${lista.length} canciones para escuchar sin conexión (wifi y cargando)`, { ms: 4000 });
    }
  } finally { corriendo = false; }
}

function sugerirPush() {
  if (!app?.pushEstado) return;
  let estado = '';
  try { estado = app.pushEstado(); } catch { return; }
  if (estado === 'pendiente') { try { app.pushRegistrar(); } catch { } return; }
  if (estado !== 'sin-distribuidora' || store.get('sm_push_sugerido', false)) return;
  store.set('sm_push_sugerido', true);
  toast('Recibe tus mensajes al instante aunque la app esté cerrada: instala la app gratuita ntfy', {
    ms: 12000,
    action: { label: 'Instalar', fn: () => { location.href = 'https://play.google.com/store/apps/details?id=io.heckel.ntfy'; } },
  });
}

export function iniciarAutomatico() {
  if (!app) return;
  setTimeout(() => { revisar(); sugerirPush(); }, 8000);
  setInterval(() => revisar(), 10 * 60 * 1000);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return;
    revisar();
    // Al volver (por ejemplo, después de instalar ntfy) se intenta registrar otra vez
    try { if (app.pushEstado?.() !== 'activo') app.pushRegistrar?.(); } catch { }
  });
}
