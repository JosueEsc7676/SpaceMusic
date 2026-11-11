import { useEffect, useState } from 'react';
import { parseLrc, type LyricLine } from './lrc';

export type Lyrics = { synced: LyricLine[]; plain: string };
export type LyricsState = { status: 'idle' | 'loading' | 'ready' | 'empty' | 'error'; lyrics: Lyrics | null };

const cache = new Map<string, Lyrics | null>();
const pending = new Map<string, Promise<Lyrics | null>>();

async function call(server: string, token: string, path: string, method: 'GET' | 'POST') {
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 15000);
  try {
    const res = await fetch(server + path, { method, headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }, signal: abort.signal });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`Servidor (${res.status})`);
    return await res.json();
  } finally { clearTimeout(timer); }
}
function toLyrics(d: any): Lyrics | null {
  const synced = typeof d?.letraSincronizada === 'string' && d.letraSincronizada ? parseLrc(d.letraSincronizada).filter((l, i, a) => l.text || i < a.length - 1) : [];
  const plain = typeof d?.letra === 'string' ? d.letra.trim() : '';
  return synced.some(l => l.text) || plain ? { synced: synced.some(l => l.text) ? synced : [], plain } : null;
}
/** Igual que la web: GET /api/letras/{id} y, si no hay letra, una sola búsqueda POST /api/letras/{id}/buscar. */
export function loadLyrics(server: string, token: string, id: number): Promise<Lyrics | null> {
  const key = `${server}|${id}`;
  if (cache.has(key)) return Promise.resolve(cache.get(key)!);
  const running = pending.get(key);
  if (running) return running;
  const job = (async () => {
    let found = toLyrics(await call(server, token, `/api/letras/${id}`, 'GET'));
    if (!found) {
      const r = await call(server, token, `/api/letras/${id}/buscar`, 'POST').catch(() => null);
      if (r?.encontrada) found = toLyrics(r);
    }
    cache.set(key, found);
    return found;
  })().finally(() => pending.delete(key));
  pending.set(key, job);
  return job;
}
export function useLyrics(server: string, token: string | null, id: number | undefined, enabled = true): LyricsState & { retry: () => void } {
  const [state, setState] = useState<LyricsState>({ status: 'idle', lyrics: null });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!enabled || !id || !token || !server) { setState({ status: 'idle', lyrics: null }); return; }
    let alive = true;
    const key = `${server}|${id}`;
    if (cache.has(key)) { const l = cache.get(key)!; setState({ status: l ? 'ready' : 'empty', lyrics: l }); return; }
    setState({ status: 'loading', lyrics: null });
    loadLyrics(server, token, id).then(l => { if (alive) setState({ status: l ? 'ready' : 'empty', lyrics: l }); })
      .catch(() => { if (alive) setState({ status: 'error', lyrics: null }); });
    return () => { alive = false; };
  }, [server, token, id, enabled, attempt]);
  return { ...state, retry: () => setAttempt(a => a + 1) };
}
