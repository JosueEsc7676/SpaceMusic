// Estado compartido: favoritos y playlists del usuario
import { api } from './api.js';
import { bus } from './util.js';

export const lib = {
  favs: new Set(),
  playlists: [],
  loaded: false,

  async init() {
    const [ids, pls] = await Promise.all([
      api('/api/usuario/favoritos/ids').catch(() => []),
      api('/api/playlists').catch(() => []),
    ]);
    this.favs = new Set(ids);
    this.playlists = pls;
    this.loaded = true;
    bus.emit('favs');
    bus.emit('playlists');
  },

  isFav(id) { return this.favs.has(Number(id)); },

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
