import type { PlayerUi, Song } from '../protocol';

/** Almacén mínimo: solo los componentes del reproductor se vuelven a dibujar con cada actualización de tiempo. */
export function createStore<T>(initial: T) {
  let value = initial;
  const listeners = new Set<() => void>();
  return {
    get: () => value,
    set(next: Partial<T>) {
      const merged = { ...value, ...next };
      if ((Object.keys(next) as (keyof T)[]).every(k => Object.is(value[k], merged[k]))) return;
      value = merged; listeners.forEach(l => l());
    },
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
  };
}
export type Store<T> = ReturnType<typeof createStore<T>>;

export type Playback = {
  items: Song[]; count: number; index: number; time: number; duration: number;
  playing: boolean; buffering: boolean; sleepUntil: number; sleepEnd: boolean;
  context?: { type?: string; label?: string };
};
export const emptyPlayback: Playback = { items: [], count: 0, index: -1, time: 0, duration: 0, playing: false, buffering: false, sleepUntil: 0, sleepEnd: false };
export const emptyUi: PlayerUi = { songId: 0, fav: false, shuffle: false, repeat: 'off', autoRadio: true, bar: null };

/** Texto "Desde …" de la cabecera, con los contextos reales que envía la web (player.context.type). */
const contexts: Record<string, string> = {
  busqueda: 'Desde la búsqueda', album: 'Desde el álbum', artista: 'Desde el artista', playlist: 'Desde la playlist',
  favoritos: 'Desde Me gusta', historial: 'Desde el historial', radio: 'Radio', genero: 'Desde el género', estilo: 'Desde el estilo',
  mix: 'Desde el mix', descargadas: 'Desde las descargas', descargas: 'Desde las descargas', perfil: 'Desde el perfil', amigos: 'Desde tus amigos',
  mensajes: 'Desde los mensajes', nuevas: 'Novedades', recomendadas: 'Recomendadas', tendencias: 'Tendencias', top: 'Lo más escuchado', cola: 'Desde la cola',
};
export function contextTitle(type?: string): string { return (type && contexts[type]) || 'Reproduciendo'; }
export function fmtTime(seconds: number): string {
  const s = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
/** Igual que cover() de la web: solo las portadas guardadas en la base aceptan tamaño (?s=), conservando su huella (?v=). */
export function coverUrl(server: string, url: string | undefined, size = 600): string | undefined {
  if (!url || !server) return undefined;
  try {
    const u = new URL(url, server);
    if (u.origin !== server) return undefined;
    if (u.pathname.startsWith('/api/portada/db/')) {
      const v = u.searchParams.get('v');
      u.search = `?s=${size}${v ? `&v=${encodeURIComponent(v)}` : ''}`;
    }
    return u.href;
  } catch { return undefined; }
}
