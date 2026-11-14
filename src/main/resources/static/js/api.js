// Comunicación con el servidor y sesión del usuario
import { store, bus } from './util.js';

const TOKEN = 'sm_token';
const USER = 'sm_user';

export const session = {
  get token() { try { return localStorage.getItem(TOKEN); } catch { return null; } },
  get user() { return store.get(USER); },
  save(token, user) {
    try { if (token) localStorage.setItem(TOKEN, token); } catch { }
    if (user) store.set(USER, user);
  },
  setUser(user) { store.set(USER, user); },
  clear() { try { localStorage.removeItem(TOKEN); localStorage.removeItem(USER); } catch { } },
  /** true si hay un token que aún no expira. */
  valid() {
    const t = this.token;
    if (!t) return false;
    try {
      const p = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      return !p.exp || p.exp * 1000 > Date.now() + 5000;
    } catch { return false; }
  },
  get role() { return this.user?.rol || 'USER'; },
  get isAdmin() { return this.role === 'ADMIN'; },
  get canEdit() { return this.role === 'ADMIN' || this.role === 'COLABORADOR'; },
};

/** Clave de almacenamiento propia de la cuenta abierta (así no se mezclan datos entre usuarios del mismo equipo). */
export const deUsuario = (clave) => `${clave}:${session.user?.id ?? 'anon'}`;

export function logout(reason) {
  // Para la música de esta cuenta antes de salir
  try { document.querySelectorAll('audio').forEach((a) => a.pause()); } catch { }
  session.clear();
  // Los datos y canciones guardados sin conexión son de esta cuenta: se borran al salir
  try { navigator.serviceWorker?.controller?.postMessage({ tipo: 'limpiar-usuario' }); } catch { }
  const p = new URLSearchParams();
  if (reason) p.set('motivo', reason);
  if (reason && location.hash.length > 2) p.set('next', location.hash);
  const q = p.toString();
  // La app de TV tiene su propio inicio de sesión
  location.href = (window.SM_TV ? '/tv.html' : '/login.html') + (q ? '?' + q : '');
}

export class ApiError extends Error {
  constructor(message, status, data) { super(message); this.status = status; this.data = data; }
}

/**
 * Petición a la API. Agrega el token, convierte a JSON y lanza ApiError
 * con un mensaje en español si algo falla.
 */
export async function api(path, { method = 'GET', body, form, signal, timeout } = {}) {
  const headers = {};
  if (session.token) headers.Authorization = 'Bearer ' + session.token;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }

  let res, reloj;
  if (timeout) {
    const ctrl = new AbortController();
    signal?.addEventListener('abort', () => ctrl.abort());
    reloj = setTimeout(() => ctrl.abort('tiempo'), timeout);
    signal = ctrl.signal;
  }
  try {
    res = await fetch(path, { method, headers, body: payload, signal });
  } catch (e) {
    if (signal?.reason === 'tiempo') throw new ApiError('La conexión está lenta. Inténtalo de nuevo.', 0);
    if (e.name === 'AbortError') throw e;
    bus.emit('red', false);
    throw new ApiError('Sin conexión a internet. Inténtalo cuando vuelvas a estar en línea.', 0);
  } finally {
    clearTimeout(reloj);
  }
  if (res.status === 401 && !path.startsWith('/api/auth/login')) {
    logout('expirada');
    throw new ApiError('Tu sesión expiró', 401);
  }
  // El service worker marca con X-SM-Copia lo que respondió desde la copia guardada (sin conexión)
  // (si solo tardó, la copia viene marcada "lenta" y no cuenta como sin conexión)
  bus.emit('red', res.headers.get('X-SM-Copia') !== 'sin-red' && res.status !== 503);
  if (res.status === 204) return null;
  const type = res.headers.get('content-type') || '';
  const data = type.includes('json') ? await res.json().catch(() => null) : await res.text();
  if (!res.ok) {
    const msg = (data && data.error) || (res.status === 403 ? 'No tienes permiso para esto' :
      res.status === 404 ? 'No encontrado' : 'Error del servidor (' + res.status + ')');
    throw new ApiError(msg, res.status, data);
  }
  return data;
}

/** URL de audio con el token (la etiqueta <audio> no envía cabeceras). */
/**
 * Calidad del audio: 'auto' (ligera con datos móviles o «ahorro de datos», alta con WiFi), 'alta' u 'ligera'.
 * La ligera es AAC 128 kbps preparada en el servidor (si aún no está lista, llega la original).
 */
const leerAjuste = (k, def) => { try { return localStorage.getItem(k) || def; } catch { return def; } };
export const ajustesCalidad = {
  get escucha() { return leerAjuste('sm_calidad', 'auto'); },
  set escucha(v) { try { localStorage.setItem('sm_calidad', v); } catch { } },
  get descargas() { return leerAjuste('sm_calidad_descargas', 'alta'); },
  set descargas(v) { try { localStorage.setItem('sm_calidad_descargas', v); } catch { } },
};
export function calidadAudio() {
  const c = ajustesCalidad.escucha;
  if (c !== 'auto') return c;
  const n = navigator.connection;
  // Datos móviles o red lenta (también una WiFi mala): se empieza con la versión ligera, que llega mucho antes
  return n && (n.saveData || n.type === 'cellular' || /2g|3g/.test(n.effectiveType || '')
    || (n.downlink && n.downlink < 1.5) || (n.rtt && n.rtt > 500)) ? 'ligera' : 'alta';
}
export const audioUrl = (id, calidad = calidadAudio()) =>
  `/api/canciones/${id}/audio?t=${encodeURIComponent(session.token || '')}${calidad === 'ligera' ? '&calidad=ligera' : ''}`;
export const downloadUrl = (id) => `/api/canciones/${id}/descargar?t=${encodeURIComponent(session.token || '')}`;

/** Subida con progreso (fetch no informa el progreso de envío). */
export function upload(path, form, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', path);
    if (session.token) xhr.setRequestHeader('Authorization', 'Bearer ' + session.token);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress?.(e.loaded / e.total); };
    xhr.onload = () => {
      let data = null;
      try { data = JSON.parse(xhr.responseText); } catch { }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else reject(new ApiError(data?.error || 'Error al subir (' + xhr.status + ')', xhr.status, data));
    };
    xhr.onerror = () => reject(new ApiError('Sin conexión a internet. Inténtalo cuando vuelvas a estar en línea.', 0));
    xhr.send(form);
  });
}
