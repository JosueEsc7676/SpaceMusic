// Estado compartido: favoritos y playlists del usuario
import { api } from './api.js';
import { bus } from './util.js';

export const lib = {
  favs: new Set(),
  playlists: [],
  /** Álbumes (por nombre) y playlists públicas de otros (por id) guardados en la biblioteca. */
  guardados: { album: new Set(), playlist: new Set() },
  loaded: false,

  async init() {
    const [ids, pls, g] = await Promise.all([
      api('/api/usuario/favoritos/ids').catch(() => []),
      api('/api/playlists').catch(() => []),
      api('/api/usuario/guardados').catch(() => ({ albumes: [], playlists: [] })),
    ]);
    this.favs = new Set(ids);
    this.playlists = pls;
    this.guardados = { album: new Set(g.albumes || []), playlist: new Set((g.playlists || []).map(Number)) };
    this.loaded = true;
    bus.emit('favs');
    bus.emit('playlists');
  },

  isFav(id) { return this.favs.has(Number(id)); },

  estaGuardado(tipo, clave) { return this.guardados[tipo].has(tipo === 'playlist' ? Number(clave) : clave); },

  /** Guarda o quita un álbum / playlist pública de la biblioteca. Devuelve el nuevo estado. */
  async toggleGuardado(tipo, clave) {
    const k = tipo === 'playlist' ? Number(clave) : clave;
    const set = this.guardados[tipo];
    const era = set.has(k);
    era ? set.delete(k) : set.add(k);
    bus.emit('guardados');
    try {
      await api(`/api/usuario/guardados/${tipo}?clave=${encodeURIComponent(String(k))}`, { method: era ? 'DELETE' : 'POST' });
      return !era;
    } catch (e) {
      era ? set.add(k) : set.delete(k);
      bus.emit('guardados');
      throw e;
    }
  },

  async toggleFav(id) {
    id = Number(id);
    const was = this.favs.has(id);
    // Actualización optimista: la interfaz responde al instante
    was ? this.favs.delete(id) : this.favs.add(id);
    bus.emit('favs', { id, fav: !was });
    try {
      await api(`/api/usuario/favoritos/${id}`, { method: was ? 'DELETE' : 'POST' });
      return !was;
    } catch (e) {
      was ? this.favs.add(id) : this.favs.delete(id);
      bus.emit('favs', { id, fav: was });
      throw e;
    }
  },

  async refreshPlaylists() {
    this.playlists = await api('/api/playlists').catch(() => this.playlists);
    bus.emit('playlists');
    return this.playlists;
  },

  upsertPlaylist(p) {
    const i = this.playlists.findIndex((x) => x.id === p.id);
    const meta = { ...p, canciones: undefined };
    if (i >= 0) this.playlists[i] = { ...this.playlists[i], ...meta };
    else this.playlists.unshift(meta);
    bus.emit('playlists');
  },

  removePlaylist(id) {
    this.playlists = this.playlists.filter((p) => p.id !== id);
    bus.emit('playlists');
  },
};
