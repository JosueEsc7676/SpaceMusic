// Administración y subida de canciones
import { api, session, upload } from '../api.js';
import { esc, fmtNum, timeAgo, norm, plural, fmtDate } from '../util.js';
import { icon } from '../icons.js';
import { setHero, setTitle } from '../router.js';
import { toast, errorToast, confirmDialog, promptDialog, emptyState, trackList, cardArtist } from '../ui.js';
import { bus } from '../util.js';
import { avatarHtml } from './account.js';

let poll = null;
bus.on('route', () => { clearInterval(poll); poll = null; });

export async function adminView(el, _p, query, isStale) {
  if (!session.isAdmin) { el.innerHTML = emptyState('lock', 'Solo para administradores'); return; }
  const tab = query.tab || 'resumen';
  setTitle('Administración');
  setHero([120, 40, 50]);
  const tabs = [['resumen', 'Resumen'], ['biblioteca', 'Biblioteca'], ['usuarios', 'Usuarios'], ['solicitudes', 'Solicitudes']];
  const resumen = await api('/api/admin/resumen');
  if (isStale()) return;
  el.innerHTML = `<div class="section-head" style="margin-top:8px"><h2 style="font-size:28px">${icon('shield')} Administración</h2></div>
    <div class="chips">${tabs.map(([k, l]) => `<a class="chip ${tab === k ? 'active' : ''}" href="#/admin?tab=${k}">${l}${k === 'solicitudes' && resumen.solicitudesPendientes ? ` <span class="badge warning">${resumen.solicitudesPendientes}</span>` : ''}</a>`).join('')}</div>
    <div data-body></div>`;
  const body = el.querySelector('[data-body]');
  if (tab === 'resumen') await resumenTab(body, resumen);
  else if (tab === 'biblioteca') await bibliotecaTab(body);
  else if (tab === 'usuarios') await usuariosTab(body);
  else await solicitudesTab(body);
}

async function resumenTab(el, r) {
  const [g, tops, tend] = await Promise.all([
    api('/api/estadisticas'), api('/api/estadisticas/top-artistas?limite=6'), api('/api/estadisticas/tendencias?dias=7&limite=10'),
  ]);
  const stat = (ico, k, v, d = '') => `<div class="stat"><div class="k">${icon(ico)} ${k}</div><div class="v">${v}</div>${d ? `<div class="d">${d}</div>` : ''}</div>`;
  el.innerHTML = `
    ${!r.correoConfigurado ? `<div class="panel" style="margin-bottom:16px;border:1px solid rgba(245,165,36,.4)"><strong style="color:var(--warning)">El correo no está configurado.</strong>
      <span class="muted">Los nuevos usuarios entran sin verificar su correo y no se pueden recuperar contraseñas por email. Configura <code>spring.mail.username</code> y <code>spring.mail.password</code>.</span></div>` : ''}
    <div class="stat-cards">
      ${stat('note', 'Canciones', fmtNum(g.totalCanciones), g.duracionFormateada)}
      ${stat('user', 'Artistas', fmtNum(g.totalArtistas))}
      ${stat('disc', 'Álbumes', fmtNum(g.totalAlbumes))}
      ${stat('play', 'Reproducciones', fmtNum(g.totalReproducciones), fmtNum(g.reproducciones30d) + ' en 30 días')}
      ${stat('users', 'Usuarios', fmtNum(r.totalUsuarios), fmtNum(g.usuariosActivos30d) + ' activos en 30 días')}
      ${stat('mic', 'Con letra', fmtNum(g.conLetra), fmtNum(g.conLetraSincronizada) + ' sincronizadas')}
    </div>
    <div class="panels">
      <div class="panel"><h3>${icon('fire')} Tendencias de la semana</h3>${tend.length ? trackList(tend, { context: { type: 'tendencias', label: 'Tendencias' }, header: false, showAlbum: false }) : '<p class="muted">Sin reproducciones esta semana.</p>'}</div>
      <div class="panel"><h3>${icon('user')} Artistas más escuchados</h3><div class="grid dense">${tops.map((a) => cardArtist({ nombre: a.artista, portadaUrl: a.portadaUrl, totalCanciones: a.canciones })).join('')}</div></div>
    </div>
    <div class="panel"><h3>${icon('folder')} Formatos</h3><div class="row wrap">${Object.entries(g.formatos || {}).map(([k, v]) => `<span class="badge">${esc(k)}: ${fmtNum(v)}</span>`).join('')}</div></div>`;
}

async function bibliotecaTab(el) {
  const draw = (s) => {
    const pct = s.archivosEncontrados ? Math.round((s.procesados / Math.max(1, s.archivosEncontrados)) * 100) : 0;
    el.innerHTML = `<div class="panels">
      <div class="panel"><h3>${icon('folder')} Carpeta de música</h3>
        <p style="margin-top:0"><code style="word-break:break-all">${esc(s.ruta)}</code>
        ${s.rutaExiste ? '<span class="badge accent dot">Accesible</span>' : '<span class="badge danger dot">No existe</span>'}</p>
        <p class="muted">${plural(s.totalCanciones, 'canción', 'canciones')} en la biblioteca. Se revisa sola cada pocos minutos.</p>
        <div class="row wrap">
          <button class="btn primary sm" data-scan ${s.escaneando ? 'disabled' : ''}>${icon('refresh', 'sm')} Buscar canciones nuevas</button>
          <button class="btn sm" data-meta ${s.escaneando ? 'disabled' : ''}>${icon('info', 'sm')} Completar datos</button>
          <button class="btn danger sm" data-clean ${s.escaneando ? 'disabled' : ''}>${icon('trash', 'sm')} Quitar canciones borradas</button>
        </div>
        <p class="hint" style="font-size:12px;color:var(--text-3)">“Completar datos” vuelve a leer las etiquetas para llenar número de pista, año, portadas y letras que falten (no cambia títulos).<br>
        “Quitar canciones borradas” elimina de la base de datos las canciones cuyo archivo ya no existe.</p>
      </div>
      <div class="panel"><h3>${icon('clock')} Último proceso</h3>
        <p style="margin-top:0"><strong>${esc(s.fase)}</strong> ${s.escaneando ? '<span class="spinner" style="width:14px;height:14px;vertical-align:-2px"></span>' : ''}</p>
        ${s.escaneando && s.archivosEncontrados ? `<div class="progress-line"><i style="width:${pct}%"></i></div><p class="muted">${s.procesados} de ${s.archivosEncontrados}</p>` : ''}
        <p class="muted">Inicio: ${s.ultimoInicio ? timeAgo(s.ultimoInicio) : '—'} · Fin: ${s.ultimoFin ? timeAgo(s.ultimoFin) : '—'}</p>
        <div class="row wrap"><span class="badge">${s.archivosEncontrados} archivos</span><span class="badge accent">${s.nuevas} nuevas</span>
        <span class="badge info">${s.reubicadas} reubicadas</span><span class="badge ${s.errores ? 'danger' : ''}">${s.errores} errores</span></div>
        ${s.ultimoError ? `<p class="muted" style="font-size:12px;word-break:break-all">Último error: ${esc(s.ultimoError)}</p>` : ''}
      </div></div>
      <a class="btn" href="#/subir">${icon('upload', 'sm')} Subir canciones desde aquí</a>`;
    const act = async (path, msg) => {
      try { draw(await api(path, { method: 'POST' })); toast(msg); startPoll(); } catch (e) { errorToast(e); }
    };
    el.querySelector('[data-scan]').onclick = () => act('/api/admin/biblioteca/escanear', 'Buscando canciones nuevas…');
    el.querySelector('[data-meta]').onclick = () => act('/api/admin/biblioteca/metadatos', 'Actualizando datos…');
    el.querySelector('[data-clean]').onclick = async () => {
      if (!(await confirmDialog('Se quitarán de la biblioteca (y de playlists y favoritos) las canciones cuyo archivo ya no existe en el disco. Los archivos no se tocan.', { title: 'Quitar canciones borradas', ok: 'Quitar', danger: true }))) return;
      try {
        let r;
        try { r = await api('/api/admin/biblioteca/limpiar', { method: 'POST' }); }
        catch (e) {
          if (e.status === 400 && /Se borrarían/.test(e.message) && await confirmDialog(e.message, { title: 'Confirmar', ok: 'Quitar igualmente', danger: true })) {
            r = await api('/api/admin/biblioteca/limpiar?forzar=true', { method: 'POST' });
          } else throw e;
        }
        toast(`Se quitaron ${r.borradas} canciones`);
        draw(await api('/api/admin/biblioteca'));
      } catch (e) { errorToast(e); }
    };
  };
  const startPoll = () => {
    clearInterval(poll);
    poll = setInterval(async () => {
      if (!el.isConnected) return clearInterval(poll);
      const s = await api('/api/admin/biblioteca').catch(() => null);
      if (!s) return;
      draw(s);
      if (!s.escaneando) { clearInterval(poll); bus.emit('library:changed'); }
    }, 1500);
  };
  const s = await api('/api/admin/biblioteca');
  draw(s);
  if (s.escaneando) startPoll();
}

async function usuariosTab(el) {
  let users = await api('/api/admin/usuarios');
  const me = session.user?.id;
  const row = (u) => `<tr data-u="${u.id}">
    <td><div class="user-cell">${avatarHtml(u)}<div><div><strong>${esc(u.nombreDisplay)}</strong></div><div class="muted" style="font-size:12px">@${esc(u.username)}</div></div></div></td>
    <td class="muted">${esc(u.email)} ${u.emailVerificado ? '' : `<span class="badge warning" title="Correo sin verificar">sin verificar</span>`}</td>
    <td><select data-rol ${u.id === me ? 'disabled title="No puedes cambiar tu propio rol"' : ''}>${['USER', 'COLABORADOR', 'ADMIN'].map((r) => `<option ${u.rol === r ? 'selected' : ''} value="${r}">${{ USER: 'Usuario', COLABORADOR: 'Colaborador', ADMIN: 'Admin' }[r]}</option>`).join('')}</select></td>
    <td>${u.activo ? '<span class="badge accent dot">Activo</span>' : '<span class="badge danger dot">Desactivado</span>'}</td>
    <td class="muted" style="white-space:nowrap">${u.ultimoLogin ? timeAgo(u.ultimoLogin) : 'nunca'}</td>
    <td style="white-space:nowrap">${u.emailVerificado ? '' : `<button class="btn sm ghost" data-verify>Verificar</button>`}
      ${u.id === me ? '' : `<button class="btn sm ${u.activo ? 'ghost' : 'primary'}" data-toggle>${u.activo ? 'Desactivar' : 'Activar'}</button>`}</td></tr>`;
  const draw = () => {
    const q = norm(el.querySelector('[data-q]')?.value || '');
    const list = users.filter((u) => !q || norm(u.username + u.email + u.nombreDisplay).includes(q));
    el.querySelector('tbody').innerHTML = list.map(row).join('');
  };
  el.innerHTML = `<div class="panel"><div class="row" style="margin-bottom:12px;justify-content:space-between"><h3 style="margin:0">${icon('users')} ${plural(users.length, 'usuario', 'usuarios')}</h3>
    <label class="list-filter">${icon('search')}<input type="search" data-q placeholder="Buscar usuario"></label></div>
    <div class="table-wrap"><table class="table"><thead><tr><th>Usuario</th><th>Correo</th><th>Rol</th><th>Estado</th><th>Último acceso</th><th></th></tr></thead><tbody></tbody></table></div></div>`;
  draw();
  el.querySelector('[data-q]').addEventListener('input', draw);
  const upd = (u) => { users = users.map((x) => (x.id === u.id ? u : x)); draw(); };
  el.addEventListener('change', async (e) => {
    const s = e.target.closest('[data-rol]');
    if (!s) return;
    const id = +s.closest('tr').dataset.u;
    try { const r = await api(`/api/admin/usuarios/${id}/rol`, { method: 'PUT', body: { rol: s.value } }); upd(r.usuario); toast('Rol actualizado'); }
    catch (err) { errorToast(err); draw(); }
  });
  el.addEventListener('click', async (e) => {
    const tr = e.target.closest('tr[data-u]');
    if (!tr) return;
    const id = +tr.dataset.u;
    const u = users.find((x) => x.id === id);
    try {
      if (e.target.closest('[data-toggle]')) {
        if (u.activo && !(await confirmDialog(`${u.nombreDisplay} no podrá iniciar sesión.`, { title: 'Desactivar cuenta', ok: 'Desactivar', danger: true }))) return;
        const r = await api(`/api/admin/usuarios/${id}/estado`, { method: 'PUT', body: { activo: !u.activo } });
        upd(r.usuario);
      } else if (e.target.closest('[data-verify]')) {
        const r = await api(`/api/admin/usuarios/${id}/verificar`, { method: 'POST' });
        upd(r.usuario);
        toast('Correo marcado como verificado');
      }
    } catch (err) { errorToast(err); }
  });
}

async function solicitudesTab(el) {
  const list = await api('/api/admin/solicitudes');
  if (!list.length) { el.innerHTML = emptyState('checkCircle', 'No hay solicitudes pendientes', 'Cuando un usuario pida ser colaborador aparecerá aquí.'); return; }
  el.innerHTML = list.map((u) => `<div class="request" data-u="${u.id}">${avatarHtml(u)}
    <div class="grow"><strong>${esc(u.nombreDisplay)}</strong> <span class="muted">@${esc(u.username)} · ${esc(u.email)}</span>
      <div class="muted" style="font-size:12px">Pedido ${timeAgo(u.fechaSolicitud)}</div>
      ${u.motivoSolicitud ? `<div class="quote">“${esc(u.motivoSolicitud)}”</div>` : ''}</div>
    <div class="row"><button class="btn primary sm" data-ok>${icon('check', 'sm')} Aprobar</button><button class="btn danger sm" data-no>Rechazar</button></div></div>`).join('');
  el.addEventListener('click', async (e) => {
    const box = e.target.closest('[data-u]');
    if (!box) return;
    const ok = e.target.closest('[data-ok]'), no = e.target.closest('[data-no]');
    if (!ok && !no) return;
    const nota = await promptDialog(ok ? 'Aprobar solicitud' : 'Rechazar solicitud', { label: 'Nota para el usuario (opcional)', ok: ok ? 'Aprobar' : 'Rechazar', optional: true });
    if (nota === null) return;
    try {
      await api(`/api/admin/solicitudes/${box.dataset.u}/${ok ? 'aprobar' : 'rechazar'}`, { method: 'POST', body: { nota } });
      box.remove();
      toast(ok ? 'Ahora es colaborador' : 'Solicitud rechazada');
      if (!el.querySelector('[data-u]')) solicitudesTab(el);
    } catch (err) { errorToast(err); }
  });
  void fmtDate;
}

// ═══════════════════ Subir canciones ═══════════════════

const EXT = ['mp3', 'flac', 'm4a', 'ogg', 'oga', 'opus', 'wav'];

export async function uploadView(el) {
  if (!session.canEdit) { el.innerHTML = emptyState('lock', 'Solo para colaboradores', 'Pide ser colaborador desde tu perfil.', '<a class="btn primary" href="#/perfil">Ir al perfil</a>'); return; }
  setTitle('Subir canciones');
  setHero([40, 90, 140]);
  el.innerHTML = `<div class="section-head" style="margin-top:8px"><h2 style="font-size:28px">Subir canciones</h2></div>
    <label class="dropzone" data-drop>${icon('upload')}<div style="font-size:17px;font-weight:700;color:var(--text)">Arrastra archivos aquí o haz clic para elegir</div>
      <div>MP3, FLAC, M4A, OGG, OPUS o WAV · hasta 200 MB por archivo</div><input type="file" multiple accept="${EXT.map((e) => '.' + e).join(',')}" hidden data-file></label>
    <p class="muted" style="font-size:13px">Se guardan en la carpeta <strong>Subidas</strong> de la biblioteca y aparecen al instante. Los datos (título, artista, álbum, portada) se leen de las etiquetas del archivo.</p>
    <div data-items></div>`;
  const items = el.querySelector('[data-items]');
  const drop = el.querySelector('[data-drop]');
  const input = el.querySelector('[data-file]');
  let cola = Promise.resolve();

  const add = (files) => {
    for (const f of files) {
      const ext = f.name.split('.').pop().toLowerCase();
      const it = document.createElement('div');
      it.className = 'upload-item';
      it.innerHTML = `${icon('note')}<div class="tt"><div class="ellipsis"><strong>${esc(f.name)}</strong></div><div class="progress-line"><i style="width:0"></i></div><div class="muted st" style="font-size:12px">En espera…</div></div>`;
      items.prepend(it);
      const st = it.querySelector('.st'), bar = it.querySelector('.progress-line i');
      if (!EXT.includes(ext)) { st.textContent = 'Formato no soportado'; st.style.color = 'var(--danger)'; continue; }
      if (f.size > 200 * 1024 * 1024) { st.textContent = 'Demasiado grande (máx. 200 MB)'; st.style.color = 'var(--danger)'; continue; }
      // Subir de uno en uno para no saturar la conexión
      cola = cola.then(async () => {
        const fd = new FormData();
        fd.append('files', f);
        st.textContent = 'Subiendo…';
        try {
          const [r] = await upload('/api/canciones/subir', fd, (p) => { bar.style.width = Math.round(p * 100) + '%'; st.textContent = `Subiendo… ${Math.round(p * 100)}%`; });
          if (!r.ok) throw new Error(r.error);
          bar.style.width = '100%';
          const c = r.cancion;
          st.innerHTML = c ? `Lista: <a href="#/artista/${encodeURIComponent(c.artista)}">${esc(c.artista)}</a> — ${esc(c.nombre)}` : 'Lista';
          st.style.color = 'var(--accent)';
          bus.emit('library:changed');
        } catch (e) {
          st.textContent = e.message || 'Error';
          st.style.color = 'var(--danger)';
        }
      });
    }
  };
  input.onchange = () => { add(input.files); input.value = ''; };
  drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', (e) => { e.preventDefault(); drop.classList.remove('over'); add(e.dataTransfer.files); });
}
