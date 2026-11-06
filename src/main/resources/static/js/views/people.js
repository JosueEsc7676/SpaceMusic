// Perfiles públicos: seguir, amigos, playlists públicas, lo más escuchado, géneros, vibras y compatibilidad
import { api, session } from '../api.js';
import { esc, plural, timeAgo, cover, coverImg, fmtNum } from '../util.js';
import { icon } from '../icons.js';
import { setHero, setTitle } from '../router.js';
import { trackList, cardPlaylist, cardArtist, section, emptyState, toast, errorToast, modal, registerList } from '../ui.js';

/** Avatar redondo: la foto o la inicial sobre el brillo aurora. */
export function avatarPersona(u, cls = '') {
  if (u?.fotoPerfil) return `<img class="p-avatar ${cls}" src="${esc(u.fotoPerfil)}" alt="" loading="lazy">`;
  const ini = (u?.nombre || u?.username || '?').trim().charAt(0).toUpperCase();
  return `<span class="p-avatar ini ${cls}">${esc(ini)}</span>`;
}

/** Tarjeta de persona (buscador, seguidores, amigos). */
export function cardPersona(u) {
  const rel = u.amigos ? '<span class="badge accent">Amigos</span>' : u.meSigue ? '<span class="badge">Te sigue</span>' : '';
  return `<a class="card person" href="#/u/${encodeURIComponent(u.username)}">
    <div class="art">${avatarPersona(u)}</div>
    <div class="t ellipsis">${esc(u.nombre)}</div><div class="s">@${esc(u.username)} ${rel}</div></a>`;
}

const fechaMes = (iso) => {
  try { return new Date(iso).toLocaleDateString('es', { month: 'long', year: 'numeric' }); } catch { return ''; }
};

function botonSeguir(p) {
  if (p.esYo) return `<a class="btn outline" href="#/perfil">${icon('edit', 'sm')} Editar perfil</a>`;
  if (p.yoSigo) return `<button class="btn" data-seguir="dejar">${icon('check', 'sm')} ${p.amigos ? 'Amigos' : 'Siguiendo'}</button>`;
  return `<button class="btn primary" data-seguir="seguir">${icon('plus', 'sm')} ${p.meSigue ? 'Seguir también' : 'Seguir'}</button>`;
}

export async function perfilPublicoView(el, { username }, _q, isStale) {
  let p;
  try { p = await api('/api/perfiles/' + encodeURIComponent(username)); } catch (e) {
    el.innerHTML = emptyState('user', 'Este perfil no existe', 'Puede que el usuario haya cambiado de nombre o ya no esté en SpaceMusic.');
    return;
  }
  if (isStale()) return;
  setTitle(p.nombre);
  setHero([96, 84, 150]);

  const draw = () => {
    const act = p.actividad;
    let actividad = '';
    if (act?.cancion) {
      const lid = registerList([act.cancion], { type: 'perfil', label: 'De ' + p.nombre });
      actividad = `<div class="p-now ${act.ahora ? 'live' : ''}" data-act="play-list" data-list="${lid}" data-i="0">
        <span class="p-now-art">${coverImg(act.cancion.portadaUrl, 160)}</span>
        <span class="p-now-tt"><small>${act.ahora ? '<i class="dot"></i> Escuchando ahora' : 'Escuchó ' + esc(timeAgo(act.hace))}</small>
          <b class="ellipsis">${esc(act.cancion.nombre)}</b><span class="ellipsis">${esc(act.cancion.artista)}</span></span>
        <button class="pin-play" data-act="play-list" data-list="${lid}" data-i="0" aria-label="Reproducir">${icon('play', 'sm')}</button></div>`;
    }
    const compat = p.compatibilidad ? `<div class="p-compat" style="--pct:${p.compatibilidad.porcentaje}">
        <div class="ring"><span>${p.compatibilidad.porcentaje}%</span></div>
        <div><b>Compatibilidad musical</b><small>${p.compatibilidad.artistasEnComun?.length
          ? 'En común: ' + p.compatibilidad.artistasEnComun.map(esc).join(', ') : 'Todavía no tienen artistas en común'}</small></div></div>` : '';
    const vibras = p.vibras?.length ? section('Vibras', `<div class="vibes">${p.vibras.map((v) =>
      `<div class="vibe">${icon(v.icono)}<b>${esc(v.nombre)}</b><small>${esc(v.descripcion)}</small></div>`).join('')}</div>`, { eyebrow: 'Su estilo' }) : '';
    const generos = p.topGeneros?.length ? section('Géneros favoritos', `<div class="g-bars">${p.topGeneros.slice(0, 6).map((g) =>
      `<a class="g-bar" href="#/genero/${encodeURIComponent(g.genero)}"><span>${esc(g.genero)}</span><i style="--w:${Math.max(6, g.porcentaje)}%"></i><em>${Math.round(g.porcentaje)}%</em></a>`).join('')}</div>`, { eyebrow: 'Últimos 90 días' }) : '';
    const canciones = p.topCanciones?.length ? section('Lo que más escucha', trackList(p.topCanciones.map((x) => x.cancion),
      { context: { type: 'perfil', label: 'Lo más escuchado de ' + p.nombre }, showAlbum: false, header: false }), { eyebrow: 'Últimos 90 días' }) : '';
    const artistas = p.topArtistas?.length ? section('Artistas favoritos', `<div class="shelf">${p.topArtistas.map((a) =>
      cardArtist({ nombre: a.artista, portadaUrl: a.portadaUrl, totalCanciones: a.veces })).join('')}</div>`) : '';
    const pls = section(p.esYo ? 'Tus playlists públicas' : 'Playlists públicas', p.playlists?.length
      ? `<div class="grid">${p.playlists.map(cardPlaylist).join('')}</div>`
      : `<p class="muted">${p.esYo ? 'Marca una playlist como pública para que aparezca aquí.' : 'Todavía no tiene playlists públicas.'}</p>`);
    const privado = !p.esYo && !p.topCanciones && !p.topGeneros && !p.vibras
      ? `<p class="muted p-private">${icon('lock', 'sm')} ${esc(p.nombre)} prefiere no mostrar lo que escucha.</p>` : '';

    el.innerHTML = `
      <header class="p-head">
        ${avatarPersona(p, 'xl')}
        <div class="p-info">
          <div class="eyebrow">Perfil${p.amigos ? ' · Amigos' : p.meSigue && !p.esYo ? ' · Te sigue' : ''}</div>
          <h1 class="page-title">${esc(p.nombre)}</h1>
          <div class="p-meta">@${esc(p.username)}${p.desde ? ' · desde ' + esc(fechaMes(p.desde)) : ''}${p.totalReproducciones ? ' · ' + fmtNum(p.totalReproducciones) + ' escuchas' : ''}</div>
          ${p.biografia ? `<p class="p-bio">${esc(p.biografia)}</p>` : ''}
          <div class="p-counts">
            <button data-lista="seguidores"><b>${p.seguidores}</b> ${p.seguidores === 1 ? 'seguidor' : 'seguidores'}</button>
            <button data-lista="siguiendo"><b>${p.siguiendo}</b> siguiendo</button>
            <span><b>${p.playlists?.length || 0}</b> ${p.playlists?.length === 1 ? 'playlist' : 'playlists'}</span>
          </div>
          <div class="p-actions">${botonSeguir(p)}<button class="icon-btn" data-compartir title="Copiar enlace del perfil">${icon('link')}</button></div>
        </div>
      </header>
      ${actividad || compat ? `<div class="p-cards">${actividad}${compat}</div>` : ''}
      ${privado}${vibras}${generos}${canciones}${artistas}${pls}`;

    el.querySelector('[data-seguir]')?.addEventListener('click', async (e) => {
      const dejar = e.currentTarget.dataset.seguir === 'dejar';
      try {
        const r = await api(`/api/perfiles/${encodeURIComponent(p.username)}/seguir`, { method: dejar ? 'DELETE' : 'POST' });
        p = { ...p, ...r, seguidores: p.seguidores + (dejar ? -1 : 1) };
        toast(dejar ? 'Dejaste de seguir a ' + p.nombre : r.amigos ? '¡Ahora son amigos!' : 'Ahora sigues a ' + p.nombre);
        draw();
      } catch (err) { errorToast(err); }
    });
    el.querySelectorAll('[data-lista]').forEach((b) => b.addEventListener('click', () => verLista(p, b.dataset.lista)));
    el.querySelector('[data-compartir]')?.addEventListener('click', () => {
      const url = location.origin + '/#/u/' + encodeURIComponent(p.username);
      navigator.clipboard?.writeText(url).then(() => toast('Enlace del perfil copiado'), () => toast(url));
    });
  };
  draw();
}

async function verLista(p, tipo) {
  const lista = await api(`/api/perfiles/${encodeURIComponent(p.username)}/${tipo}`).catch(() => []);
  modal({
    title: tipo === 'seguidores' ? 'Seguidores de ' + p.nombre : p.nombre + ' sigue a',
    wide: true,
    body: lista.length ? `<div class="grid people">${lista.map(cardPersona).join('')}</div>`
      : `<p class="muted">${tipo === 'seguidores' ? 'Todavía no tiene seguidores.' : 'Todavía no sigue a nadie.'}</p>`,
    onOpen: (m, cerrar) => m.addEventListener('click', (e) => { if (e.target.closest('a.person')) cerrar(null); }),
  });
}

/** Sección "Lo que escuchan tus amigos" (inicio). */
export async function actividadAmigos(box) {
  const lista = await api('/api/amigos/actividad').catch(() => []);
  if (!lista.length || !box.isConnected) return;
  const lid = registerList(lista.map((x) => x.cancion), { type: 'amigos', label: 'Lo que escuchan tus amigos' });
  box.innerHTML = section('Lo que escuchan tus amigos', `<div class="shelf friends">${lista.map((x, i) => `
    <div class="friend-card" data-act="play-list" data-list="${lid}" data-i="${i}">
      <div class="art">${coverImg(x.cancion.portadaUrl, 300)}
        <a class="friend-who" href="#/u/${encodeURIComponent(x.usuario.username)}">${avatarPersona(x.usuario, 'sm')}<span class="ellipsis">${esc(x.usuario.nombre)}</span></a>
        ${x.ahora ? '<span class="live-tag"><i class="dot"></i> Ahora</span>' : ''}</div>
      <div class="t ellipsis">${esc(x.cancion.nombre)}</div>
      <div class="s ellipsis">${esc(x.cancion.artista)}${x.ahora ? '' : ' · ' + esc(timeAgo(x.hace))}</div></div>`).join('')}</div>`, { eyebrow: 'Amigos', more: '#/buscar' });
}

void cover; void session; void plural;
