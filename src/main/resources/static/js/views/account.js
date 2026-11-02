// Perfil, ajustes y estadísticas personales
import { api, session, logout } from '../api.js';
import { esc, fmtNum, fmtDate, cover, coverImg, plural, store } from '../util.js';
import { icon } from '../icons.js';
import { prefs, fx, EQ_BANDS, EQ_PRESETS } from '../player.js';
import { setHero, setTitle } from '../router.js';
import { toast, errorToast, modal, registerList, trackList, emptyState } from '../ui.js';
import { bus } from '../util.js';
import { cancionesGuardadas } from '../offline.js';

export const ACCENTS = {
  Perla: ['#f5f5f7', '#000000'],
  Iris: ['#b4a5ff', '#120a33'],
  Cielo: ['#7cc8ff', '#001a2e'],
  Rosa: ['#ffa3c8', '#2e0716'],
  Coral: ['#ff8f78', '#2e0b04'],
  Ámbar: ['#f4c46a', '#2a1a00'],
};

export function applyAccent(name = store.get('sm_accent', 'Perla')) {
  const [c, ink] = ACCENTS[name] || ACCENTS.Perla; // los acentos antiguos (p. ej. Verde) pasan a Perla
  document.documentElement.style.setProperty('--accent', c);
  document.documentElement.style.setProperty('--accent-ink', ink);
}

export function avatarHtml(u, cls = '') {
  if (u?.fotoPerfil) return `<img class="avatar ${cls}" src="${esc(u.fotoPerfil)}" alt="">`;
  const ini = (u?.nombreDisplay || u?.username || '?').trim().charAt(0).toUpperCase();
  return `<div class="avatar ${cls}">${esc(ini)}</div>`;
}

const ROLES = { ADMIN: ['Administrador', 'danger'], COLABORADOR: ['Colaborador', 'info'], USER: ['Usuario', ''] };

// ═══════════════════ Perfil ═══════════════════

export async function profileView(el, _p, _q, isStale) {
  const u = await api('/api/auth/me');
  if (isStale()) return;
  session.setUser(u);
  bus.emit('user', u);
  setTitle('Perfil');
  setHero([70, 70, 90]);
  const [rolNombre, rolCls] = ROLES[u.rol] || ROLES.USER;
  const sol = u.solicitudColaborador;
  const accent = ACCENTS[store.get('sm_accent', 'Perla')] ? store.get('sm_accent', 'Perla') : 'Perla';

  el.innerHTML = `
    <div class="profile-head">
      <label class="avatar-pick">${avatarHtml(u, 'lg')}<div class="ov">${icon('edit')}&nbsp;Cambiar foto</div><input type="file" accept="image/*" hidden data-avatar></label>
      <div><div class="kind" style="font-size:13px;font-weight:700">Perfil</div>
        <h1 style="font-size:clamp(30px,5vw,64px);font-weight:900;letter-spacing:-1.5px;margin:4px 0 10px">${esc(u.nombreDisplay)}</h1>
        <div class="row wrap"><span class="muted">@${esc(u.username)}</span><span class="badge ${rolCls}">${rolNombre}</span>
        <span class="muted" style="font-size:13px">Miembro desde ${fmtDate(u.fechaRegistro)}</span></div></div>
    </div>

    <div class="settings">
      <div class="panel"><h3>${icon('user')} Tu perfil</h3>
        <div class="field"><label>Nombre visible</label><input data-name value="${esc(u.nombreDisplay)}" maxlength="100"></div>
        <div class="field"><label>Correo</label><input value="${esc(u.email)}" disabled>
          ${u.emailPendiente ? `<span class="hint">Pendiente de confirmar: ${esc(u.emailPendiente)}</span>` : ''}</div>
        <div class="row"><button class="btn primary sm" data-save-name>Guardar</button><button class="btn sm" data-change-email>${icon('mail', 'sm')} Cambiar correo</button></div>
      </div>

      <div class="panel"><h3>${icon('lock')} Contraseña</h3>
        <div class="field"><label>Contraseña actual</label><input type="password" data-p0 autocomplete="current-password"></div>
        <div class="field"><label>Nueva contraseña</label><input type="password" data-p1 autocomplete="new-password" placeholder="Mínimo 8 caracteres, con letras y números"></div>
        <div class="field"><label>Repite la nueva</label><input type="password" data-p2 autocomplete="new-password"></div>
        <button class="btn primary sm" data-save-pass>Cambiar contraseña</button>
      </div>

      <div class="panel"><h3>${icon('sliders')} Reproducción</h3>
        <div class="setting-row"><div><div class="t">Radio automática</div><div class="s">Al terminar una lista, sigue con canciones parecidas</div></div>
          <label class="switch"><input type="checkbox" data-pref="autoRadio" ${prefs.autoRadio ? 'checked' : ''}><span></span></label></div>
        <div class="setting-row"><div><div class="t">Visualizador</div><div class="s">Barras animadas en “Reproduciendo ahora”</div></div>
          <label class="switch"><input type="checkbox" data-pref="visualizer" ${prefs.visualizer ? 'checked' : ''}><span></span></label></div>
        <div class="setting-row"><div><div class="t">Ecualizador</div><div class="s">${prefs.eqEnabled ? 'Activado' : 'Desactivado'}</div></div>
          <button class="btn sm" data-eq>${icon('sliders', 'sm')} Ajustar</button></div>
        <div class="setting-row"><div><div class="t">Color de la app</div></div>
          <div class="swatches">${Object.entries(ACCENTS).map(([n, [c]]) => `<button class="swatch ${n === accent ? 'active' : ''}" data-accent="${n}" title="${n}" style="background:${c}"></button>`).join('')}</div></div>
        <div class="setting-row"><div><div class="t">Sonido de inicio</div><div class="s">Suena al abrir la app junto al logo</div></div>
          <label class="switch"><input type="checkbox" data-intro ${store.get('sm_intro_sound', true) !== false ? 'checked' : ''}><span></span></label></div>
        <div class="setting-row"><div><div class="t">Sin conexión</div><div class="s" data-offline>Las canciones que escuchas se guardan solas en este dispositivo</div></div>
          ${icon('download', 'sm')}</div>
        <div class="setting-row"><div><div class="t">Atajos de teclado</div><div class="s">Pulsa <kbd>?</kbd> en cualquier momento</div></div>
          <button class="btn sm" data-keys>${icon('keyboard', 'sm')} Ver</button></div>
      </div>

      ${u.rol === 'USER' ? `<div class="panel"><h3>${icon('star')} Ser colaborador</h3>
        <p class="muted" style="margin-top:0">Los colaboradores pueden subir canciones, corregir datos de canciones y agregar letras.</p>
        ${sol === 'PENDIENTE' ? '<span class="badge warning dot">Solicitud pendiente de revisión</span>' : `
          ${sol === 'RECHAZADA' ? `<p><span class="badge danger">Solicitud anterior rechazada</span>${u.respuestaAdmin ? ` <span class="muted">“${esc(u.respuestaAdmin)}”</span>` : ''}</p>` : ''}
          <div class="field"><label>¿Por qué quieres serlo?</label><textarea data-motivo maxlength="500" placeholder="Cuéntale brevemente al administrador"></textarea></div>
          <button class="btn primary sm" data-solicitar>${icon('send', 'sm')} Enviar solicitud</button>`}
      </div>` : ''}

      ${window.SpaceMusicApp ? '' : `<div class="panel"><h3>${icon('download')} Apps</h3>
        <p class="muted" style="margin-top:0">Escucha con la pantalla bloqueada y controles en la notificación.</p>
        <div class="row wrap"><a class="btn primary sm" href="/app/SpaceMusic.apk" download>${icon('download', 'sm')} Android</a>
          <a class="btn sm" href="/app/SpaceMusic-TV.apk" download>${icon('download', 'sm')} Android TV</a></div>
        <p class="muted" style="font-size:12px;margin-bottom:0">En iPhone: abre esta página en Safari → Compartir → “Agregar a inicio”.</p>
      </div>`}

      <div class="panel"><h3>${icon('logout')} Sesión</h3>
        <p class="muted" style="margin-top:0">Cierra la sesión en este dispositivo.</p>
        <div class="row wrap">${window.SpaceMusicApp?.cerrarApp ? `<button class="btn sm" data-cerrar-app>${icon('power', 'sm')} Cerrar app</button>` : ''}
        <button class="btn danger sm" data-logout>${icon('logout', 'sm')} Cerrar sesión</button></div>
      </div>
    </div>`;

  const $ = (s) => el.querySelector(s);
  $('[data-avatar]').onchange = async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const fd = new FormData();
    fd.append('file', f);
    try {
      const r = await api('/api/auth/me/foto', { method: 'POST', form: fd });
      const nu = { ...session.user, fotoPerfil: r.fotoPerfil };
      session.setUser(nu);
      bus.emit('user', nu);
      el.querySelector('.avatar-pick .avatar').outerHTML = avatarHtml(nu, 'lg');
      toast('Foto actualizada');
    } catch (err) { errorToast(err); }
  };
  $('[data-save-name]').onclick = async () => {
    try {
      const r = await api('/api/usuario/perfil', { method: 'PUT', body: { nombreDisplay: $('[data-name]').value } });
      session.setUser(r.usuario);
      bus.emit('user', r.usuario);
      el.querySelector('h1').textContent = r.usuario.nombreDisplay;
      toast('Perfil guardado');
    } catch (err) { errorToast(err); }
  };
  $('[data-save-pass]').onclick = async () => {
    const [a, b, c] = ['[data-p0]', '[data-p1]', '[data-p2]'].map((s) => $(s).value);
    if (b !== c) return toast('Las contraseñas nuevas no coinciden', { error: true });
    try {
      await api('/api/usuario/cambiar-password', { method: 'POST', body: { actual: a, nueva: b } });
      ['[data-p0]', '[data-p1]', '[data-p2]'].forEach((s) => { $(s).value = ''; });
      toast('Contraseña actualizada');
    } catch (err) { errorToast(err); }
  };
  $('[data-change-email]').onclick = () => modal({
    title: 'Cambiar correo',
    body: `<div class="field"><label>Nuevo correo</label><input type="email" id="ne"></div>
      <div class="field"><label>Tu contraseña</label><input type="password" id="np"></div>`,
    actions: [{ label: 'Cancelar', value: null }, { label: 'Cambiar', value: 'ok', primary: true }],
    onAction: async (v, m) => {
      if (v !== 'ok') return null;
      const r = await api('/api/usuario/cambiar-email', { method: 'POST', body: { nuevoEmail: m.querySelector('#ne').value, password: m.querySelector('#np').value } });
      toast(r.message);
      if (r.usuario) { session.setUser(r.usuario); }
      return r;
    },
  }).then((r) => r && profileView(el, {}, {}, () => false));
  el.querySelectorAll('[data-pref]').forEach((c) => c.addEventListener('change', () => {
    prefs[c.dataset.pref] = c.checked;
    prefs.save();
    toast('Preferencia guardada');
  }));
  el.querySelectorAll('[data-accent]').forEach((b) => b.addEventListener('click', () => {
    store.set('sm_accent', b.dataset.accent);
    applyAccent(b.dataset.accent);
    el.querySelectorAll('[data-accent]').forEach((x) => x.classList.toggle('active', x === b));
  }));
  $('[data-eq]').onclick = () => openEqualizer().then(() => profileView(el, {}, {}, () => false));
  $('[data-keys]').onclick = () => bus.emit('shortcuts');
  $('[data-intro]').onchange = (e) => store.set('sm_intro_sound', e.target.checked);
  cancionesGuardadas().then((n) => {
    const t = $('[data-offline]');
    if (t && n) t.textContent = plural(n, 'canción guardada', 'canciones guardadas') + ' para escuchar sin conexión';
  });
  $('[data-logout]').onclick = () => logout();
  $('[data-cerrar-app]')?.addEventListener('click', () => window.SpaceMusicApp.cerrarApp());
  $('[data-solicitar]')?.addEventListener('click', async () => {
    try {
      const r = await api('/api/usuario/solicitar-colaborador', { method: 'POST', body: { motivo: $('[data-motivo]').value } });
      toast(r.message);
      profileView(el, {}, {}, () => false);
    } catch (err) { errorToast(err); }
  });
}

// ═══════════════════ Ecualizador ═══════════════════

export function openEqualizer() {
  const fmtF = (f) => (f >= 1000 ? (f / 1000) + ' kHz' : f + ' Hz');
  return modal({
    title: 'Ecualizador',
    body: `<div class="setting-row"><div class="t">Activar ecualizador</div><label class="switch"><input type="checkbox" id="eqon" ${prefs.eqEnabled ? 'checked' : ''}><span></span></label></div>
      <div class="chips" style="margin:14px 0 4px">${Object.keys(EQ_PRESETS).map((n) => `<button class="chip" data-preset="${n}">${n}</button>`).join('')}</div>
      <div class="eq">${EQ_BANDS.map((f, i) => `<div class="band"><span data-v="${i}">${prefs.eq[i] > 0 ? '+' : ''}${prefs.eq[i]} dB</span>
        <input type="range" min="-12" max="12" step="1" value="${prefs.eq[i]}" data-band="${i}" aria-label="${fmtF(f)}"><span>${fmtF(f)}</span></div>`).join('')}</div>
      <p class="hint" style="font-size:12px">Se aplica al instante. Se guarda en este dispositivo.</p>`,
    actions: [{ label: 'Listo', value: true, primary: true }],
    onOpen: (m) => {
      if (!fx.ensure()) { toast('Tu navegador no permite ecualizador', { error: true }); }
      fx.resume();
      const upd = () => {
        m.querySelectorAll('[data-band]').forEach((r) => {
          const i = +r.dataset.band;
          prefs.eq[i] = +r.value;
          m.querySelector(`[data-v="${i}"]`).textContent = (r.value > 0 ? '+' : '') + r.value + ' dB';
        });
        prefs.save();
        fx.apply();
      };
      m.querySelectorAll('[data-band]').forEach((r) => r.addEventListener('input', () => {
        if (!prefs.eqEnabled) { prefs.eqEnabled = true; m.querySelector('#eqon').checked = true; }
        upd();
      }));
      m.querySelector('#eqon').addEventListener('change', (e) => { prefs.eqEnabled = e.target.checked; prefs.save(); fx.apply(); });
      m.querySelectorAll('[data-preset]').forEach((b) => b.addEventListener('click', () => {
        EQ_PRESETS[b.dataset.preset].forEach((v, i) => { m.querySelector(`[data-band="${i}"]`).value = v; });
        prefs.eqEnabled = b.dataset.preset !== 'Plano';
        m.querySelector('#eqon').checked = prefs.eqEnabled;
        upd();
      }));
    },
  });
}

// ═══════════════════ Estadísticas personales ═══════════════════

function bars(items, cls = '') {
  const max = Math.max(1, ...items.map((x) => x.v));
  return `<div class="bars ${cls}">${items.map((x) => `<div class="b"><i style="height:${Math.round((x.v / max) * 100)}%" data-v="${x.v}"></i><span>${esc(x.l)}</span></div>`).join('')}</div>`;
}

export async function statsView(el, _p, query, isStale) {
  const dias = Number(query.dias ?? 30);
  const s = await api('/api/usuario/estadisticas?dias=' + dias);
  if (isStale()) return;
  setTitle('Tus estadísticas');
  setHero([30, 120, 90]);
  const periodos = [[7, '7 días'], [30, '30 días'], [365, 'Este año'], [0, 'Siempre']];
  const maxC = Math.max(1, ...s.topCanciones.map((x) => x.veces));
  const maxA = Math.max(1, ...s.topArtistas.map((x) => x.veces));
  const lid = registerList(s.topCanciones.map((x) => x.cancion), { type: 'top', label: 'Tus más escuchadas' });

  el.innerHTML = `
    <div class="section-head" style="margin-top:8px"><h2 style="font-size:28px">Tus estadísticas</h2></div>
    <div class="chips">${periodos.map(([d, l]) => `<a class="chip ${d === dias ? 'active' : ''}" href="#/estadisticas?dias=${d}">${l}</a>`).join('')}</div>
    ${!s.totalReproducciones ? emptyState('chart', 'Todavía no hay datos', 'Escucha música y aquí verás tus canciones, artistas y géneros favoritos.') : `
    <div class="stat-cards">
      <div class="stat"><div class="k">${icon('clock')} Tiempo escuchado</div><div class="v">${esc(s.tiempoEscuchado)}</div></div>
      <div class="stat"><div class="k">${icon('play')} Reproducciones</div><div class="v">${fmtNum(s.totalReproducciones)}</div><div class="d">${plural(s.cancionesDistintas, 'canción distinta', 'canciones distintas')}</div></div>
      <div class="stat"><div class="k">${icon('fire')} Racha</div><div class="v">${s.rachaDias} ${s.rachaDias === 1 ? 'día' : 'días'}</div><div class="d">${plural(s.diasActivos, 'día activo', 'días activos')}</div></div>
      <div class="stat"><div class="k">${icon('heart')} Me gusta</div><div class="v">${fmtNum(s.totalFavoritos)}</div></div>
    </div>
    <div class="panels">
      <div class="panel"><h3>${icon('note')} Canciones más escuchadas</h3>
        ${s.topCanciones.map((x, i) => `<div class="rank" data-act="play-list" data-list="${lid}" data-i="${i}"><span class="pos">${i + 1}</span>${coverImg(x.cancion.portadaUrl, 64)}
          <div class="tt"><div class="name ellipsis">${esc(x.cancion.nombre)}</div><div class="muted ellipsis" style="font-size:12px">${esc(x.cancion.artista)}</div>
          <div class="bar"><i style="width:${(x.veces / maxC) * 100}%"></i></div></div><span class="val">${x.veces}×</span></div>`).join('')}
      </div>
      <div class="panel"><h3>${icon('user')} Artistas favoritos</h3>
        ${s.topArtistas.map((x, i) => `<a class="rank round" href="#/artista/${encodeURIComponent(x.artista)}"><span class="pos">${i + 1}</span>${coverImg(x.portadaUrl, 64)}
          <div class="tt"><div class="name ellipsis">${esc(x.artista)}</div><div class="bar"><i style="width:${(x.veces / maxA) * 100}%"></i></div></div><span class="val">${x.veces}×</span></a>`).join('')}
      </div>
    </div>
    <div class="panels">
      ${s.topGeneros.length ? `<div class="panel"><h3>${icon('disc')} Géneros</h3>
        ${s.topGeneros.map((g) => `<a class="rank" href="#/genero/${encodeURIComponent(g.genero)}"><div class="tt"><div class="name">${esc(g.genero)}</div><div class="bar"><i style="width:${g.porcentaje}%"></i></div></div><span class="val">${g.porcentaje}%</span></a>`).join('')}</div>` : ''}
      <div class="panel"><h3>${icon('clock')} ¿A qué hora escuchas?</h3>
        ${bars(s.porHora.map((v, h) => ({ v, l: h % 3 === 0 ? h + 'h' : '' })), 'hours')}</div>
    </div>
    ${s.porMes.length > 1 ? `<div class="panel"><h3>${icon('chart')} Reproducciones por mes</h3>${bars(s.porMes.map((m) => ({ v: m.cantidad, l: m.etiqueta })))}</div>` : ''}
    `}`;
  void cover; void trackList;
}
