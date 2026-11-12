// Administración y subida de canciones
import { api, session, upload } from '../api.js';
import { esc, fmtNum, timeAgo, norm, plural, fmtDate, isMobile } from '../util.js';
import { icon } from '../icons.js';
import { setHero, setTitle } from '../router.js';
import { toast, errorToast, confirmDialog, promptDialog, emptyState, trackList, cardArtist, modal, genreColor } from '../ui.js';
import { bus } from '../util.js';
import { avatarHtml } from './account.js';
import { reportesTab } from './reportes-admin.js';
import { descargasTab } from './descargas-admin.js';
import { EXT, agregar, items, resumenLote, limpiar, tamano } from '../subidas.js';

let poll = null;
bus.on('route', () => { clearInterval(poll); poll = null; });

export async function adminView(el, _p, query, isStale) {
  if (!session.isAdmin) { el.innerHTML = emptyState('lock', 'Solo para administradores'); return; }
  const tab = query.tab || 'resumen';
  setTitle('Administración');
  setHero([120, 40, 50]);
  const tabs = [['resumen', 'Resumen'], ['descargas', 'Descargar música'], ['subir', 'Subir canciones'], ['biblioteca', 'Biblioteca'], ['generos', 'Géneros'], ['usuarios', 'Usuarios'], ['cuentas', 'Cuentas nuevas'], ['solicitudes', 'Colaboradores'], ['reportes', 'Reportes'], ['soporte', 'Soporte técnico']];
  const [resumen, com] = tab === 'soporte' ? [{}, {}] : await Promise.all([api('/api/admin/resumen'), api('/api/admin/comunidad/conteos').catch(() => ({}))]);
  const pendCom = com.reportesAbiertos || 0;
  if (isStale()) return;
  el.innerHTML = `<div class="section-head" style="margin-top:8px"><h2 style="font-size:28px">${icon('shield')} Administración</h2></div>
    <div class="adm-top"><a class="btn sm" href="#/">${icon('left', 'sm')} Volver a la música</a></div>
    <div class="chips">${tabs.map(([k, l]) => `<a class="chip ${tab === k ? 'active' : ''}" href="#/admin?tab=${k}">${l}${k === 'solicitudes' && resumen.solicitudesPendientes ? ` <span class="badge warning">${resumen.solicitudesPendientes}</span>` : ''}${k === 'cuentas' && resumen.cuentasPendientes ? ` <span class="badge warning">${resumen.cuentasPendientes}</span>` : ''}${k === 'reportes' && pendCom ? ` <span class="badge warning">${pendCom}</span>` : ''}</a>`).join('')}</div>
    <div data-body></div>`;
  const body = el.querySelector('[data-body]');
  if (tab === 'soporte') await (await import('./soporte.js')).soporteView(body, _p, query);
  else if (tab === 'subir') await uploadView(body);
  else if (tab === 'resumen') await resumenTab(body, resumen);
  else if (tab === 'biblioteca') await bibliotecaTab(body);
  else if (tab === 'usuarios') await usuariosTab(body);
  else if (tab === 'cuentas') await cuentasTab(body);
  else if (tab === 'generos') await generosTab(body);
  else if (tab === 'reportes') await reportesTab(body);
  else if (tab === 'descargas') await descargasTab(body);
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
          <button class="btn sm" data-covers ${s.escaneando ? 'disabled' : ''}>${icon('disc', 'sm')} Corregir portadas</button>
          <button class="btn danger sm" data-clean ${s.escaneando ? 'disabled' : ''}>${icon('trash', 'sm')} Quitar canciones borradas</button>
        </div>
        <p class="hint" style="font-size:12px;color:var(--text-3)">“Completar datos” vuelve a leer las etiquetas para llenar número de pista, año, portadas y letras que falten (no cambia títulos).<br>
        “Corregir portadas” vuelve a tomar la portada de cada archivo y reemplaza las que estén mal.<br>
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
      <a class="btn" href="#/admin?tab=subir">${icon('upload', 'sm')} Subir canciones desde aquí</a>`;
    const act = async (path, msg) => {
      try { draw(await api(path, { method: 'POST' })); toast(msg); startPoll(); } catch (e) { errorToast(e); }
    };
    el.querySelector('[data-scan]').onclick = () => act('/api/admin/biblioteca/escanear', 'Buscando canciones nuevas…');
    el.querySelector('[data-meta]').onclick = () => act('/api/admin/biblioteca/metadatos', 'Actualizando datos…');
    el.querySelector('[data-covers]').onclick = () => act('/api/admin/biblioteca/metadatos?portadas=true', 'Corrigiendo portadas…');
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
    <td style="white-space:nowrap"><button class="btn sm ghost" data-detalle>${icon('info', 'sm')} Detalle</button>${u.emailVerificado ? '' : `<button class="btn sm ghost" data-verify>Verificar</button>`}
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
      } else if (e.target.closest('[data-detalle]')) {
        detalleUsuario(id);
      } else if (e.target.closest('[data-verify]')) {
        const r = await api(`/api/admin/usuarios/${id}/verificar`, { method: 'POST' });
        upd(r.usuario);
        toast('Correo marcado como verificado');
      }
    } catch (err) { errorToast(err); }
  });
}

/** Cuentas recién creadas que esperan aprobación (también llegan por correo a los administradores). */
async function cuentasTab(el) {
  const list = await api('/api/admin/cuentas/pendientes');
  if (!list.length) { el.innerHTML = emptyState('checkCircle', 'No hay cuentas por aprobar', 'Cuando alguien se registre aparecerá aquí y recibirás un correo.'); return; }
  el.innerHTML = list.map((u) => `<div class="request" data-u="${u.id}">${avatarHtml(u)}
    <div class="grow"><strong>${esc(u.nombreDisplay || u.username)}</strong> <span class="muted">@${esc(u.username)} · ${esc(u.email)}</span>
      <div class="muted" style="font-size:12px">Se registró ${timeAgo(u.fechaRegistro)}</div></div>
    <div class="row"><button class="btn primary sm" data-ok>${icon('check', 'sm')} Aprobar</button><button class="btn danger sm" data-no>Rechazar</button></div></div>`).join('');
  el.addEventListener('click', async (e) => {
    const box = e.target.closest('[data-u]');
    if (!box) return;
    const ok = e.target.closest('[data-ok]'), no = e.target.closest('[data-no]');
    if (!ok && !no) return;
    (ok || no).disabled = true;
    try {
      await api(`/api/admin/cuentas/${box.dataset.u}/${ok ? 'aprobar' : 'rechazar'}`, { method: 'POST' });
      box.remove();
      toast(ok ? 'Cuenta aprobada · le avisamos por correo' : 'Cuenta rechazada');
      if (!el.querySelector('[data-u]')) cuentasTab(el);
    } catch (err) { (ok || no).disabled = false; errorToast(err); }
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


export async function uploadView(el) {
  if (!session.canEdit) { el.innerHTML = emptyState('lock', 'Solo para colaboradores', 'Pide ser colaborador desde tu perfil.', '<a class="btn primary" href="#/perfil">Ir al perfil</a>'); return; }
  setTitle('Subir canciones');
  setHero([40, 90, 140]);
  el.innerHTML = `<div class="section-head" style="margin-top:8px"><h2 style="font-size:28px">Subir canciones</h2></div>
    <label class="dropzone" data-drop>${icon('upload')}<div style="font-size:17px;font-weight:700;color:var(--text)">${isMobile() ? 'Toca para elegir canciones' : 'Arrastra canciones aquí o haz clic para elegir'}</div>
      <div>Puedes elegir varias a la vez</div><small>MP3, FLAC, M4A, OGG, OPUS o WAV · hasta 200 MB por archivo</small>
      <input type="file" multiple accept="${EXT.map((e) => '.' + e).join(',')}" hidden data-file></label>
    <p class="muted" style="font-size:13px;margin-top:14px">Se guardan en la carpeta <strong>Subidas</strong> y aparecen al instante. Puedes cambiar de pantalla mientras se suben: al terminar te avisamos.</p>
    <div class="upload-sum hidden" data-sum></div>
    <div data-items></div>`;
  const box = el.querySelector('[data-items]');
  const sum = el.querySelector('[data-sum]');
  const drop = el.querySelector('[data-drop]');
  const input = el.querySelector('[data-file]');

  const fila = (it) => {
    const color = it.estado === 'error' ? 'var(--danger)' : it.estado === 'lista' ? 'var(--accent)' : it.estado === 'repetida' ? 'var(--warning)' : '';
    const c = it.cancion;
    const msg = it.estado === 'lista'
      ? (c ? `Lista: <a href="#/artista/${encodeURIComponent(c.artista)}">${esc(c.artista)}</a> — ${esc(c.nombre)}${c.genero && c.genero !== 'Desconocido' ? ` · ${esc(c.genero)}` : ''}` : 'Lista')
      : esc(it.mensaje);
    return `<div class="upload-item" data-it="${it.id}">${icon(it.estado === 'lista' ? 'check' : it.estado === 'repetida' ? 'copy' : 'note')}<div class="tt">
      <div class="ellipsis"><strong>${esc(it.nombre)}</strong> <small class="muted">${tamano(it.tamano)}</small></div>
      <div class="progress-line"><i style="width:${Math.round(it.progreso * 100)}%"></i></div>
      <div class="muted st" style="font-size:12px${color ? `;color:${color}` : ''}">${msg}</div></div></div>`;
  };
  const pintar = () => {
    const l = resumenLote();
    if (l && (l.total || l.repetidas)) {
      const fin = l.hechas === l.total;
      sum.classList.remove('hidden');
      sum.innerHTML = `<span>${l.hechas} de ${l.total} ${l.total === 1 ? 'canción' : 'canciones'} · ${tamano(l.bytes)}${l.repetidas ? ` · <span style="color:var(--warning)">${l.repetidas} ya ${l.repetidas === 1 ? 'existía' : 'existían'}</span>` : ''}${l.fallidas ? ` · <span style="color:var(--danger)">${l.fallidas} con error</span>` : ''}</span>
        ${fin ? `<span class="row" style="gap:8px"><span class="badge accent">${icon('check', 'sm')} Terminado</span><button class="btn sm ghost" data-limpiar>Limpiar</button></span>` : '<span class="muted">Subiendo…</span>'}`;
      sum.querySelector('[data-limpiar]')?.addEventListener('click', limpiar);
    } else sum.classList.add('hidden');
    box.innerHTML = items.map(fila).join('');
  };
  pintar();
  // La pantalla aparece un instante después; se deja de escuchar cuando ya se salió de ella
  let visible = false;
  const quitar = bus.on('subidas', () => {
    if (el.isConnected) visible = true;
    else if (visible) { quitar?.(); return; }
    pintar();
  });
  input.onchange = () => { agregar(input.files); input.value = ''; };
  drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', (e) => { e.preventDefault(); drop.classList.remove('over'); agregar(e.dataTransfer.files); });
}

// ═══════════════════ Soporte técnico ═══════════════════

/** Ficha de soporte de un usuario: su cuenta, actividad y acciones rápidas. */
async function detalleUsuario(id) {
  let u;
  try { u = await api(`/api/admin/usuarios/${id}/detalle`); } catch (e) { errorToast(e); return; }
  const kv = (k, v) => `<div class="kv"><span>${k}</span><b>${v}</b></div>`;
  const body = `<div class="user-cell" style="margin-bottom:14px">${avatarHtml(u)}<div><strong>${esc(u.nombreDisplay)}</strong><div class="muted" style="font-size:13px">@${esc(u.username)} · ${esc(u.email)}</div></div></div>
    ${kv('Estado', u.activo ? (u.estadoCuenta === 'PENDIENTE' ? 'Esperando aprobación' : 'Activa') : 'Desactivada')}
    ${kv('Correo', u.emailVerificado ? 'Verificado' : 'Sin verificar')}
    ${kv('Rol', { USER: 'Usuario', COLABORADOR: 'Colaborador', ADMIN: 'Administrador' }[u.rol] || u.rol)}
    ${kv('Registro', u.fechaRegistro ? esc(fmtDate(u.fechaRegistro)) : '—')}
    ${kv('Último acceso', u.ultimoLogin ? esc(timeAgo(u.ultimoLogin)) : 'nunca')}
    ${kv('Conectado ahora', u.dispositivosConectados ? plural(u.dispositivosConectados, 'dispositivo', 'dispositivos') : 'No')}
    ${kv('Reproducciones', fmtNum(u.reproducciones))}
    ${kv('Me gusta · Playlists', `${fmtNum(u.favoritos)} · ${fmtNum(u.playlists)}`)}
    ${kv('Seguidores · Siguiendo', `${fmtNum(u.seguidores)} · ${fmtNum(u.siguiendo)}`)}
    ${kv('Sin leer', `${plural(u.mensajesSinLeer, 'mensaje', 'mensajes')} · ${plural(u.notificacionesSinLeer, 'notificación', 'notificaciones')}`)}
    <h4 style="margin:16px 0 6px">Últimas canciones</h4>
    ${u.ultimasCanciones.length ? u.ultimasCanciones.map((c) => `<div class="kv"><span class="ellipsis">${esc(c.nombre)} <small class="muted">${esc(c.artista)}</small></span><small class="muted">${esc(timeAgo(c.fecha))}</small></div>`).join('') : '<p class="muted">Aún no escucha nada.</p>'}`;
  const v = await modal({
    title: 'Soporte de usuario', body,
    actions: [{ label: 'Cerrar', value: null }, ...(u.id === session.user?.id ? [] : [{ label: 'Cerrar sus sesiones', value: 'salir', danger: true }]),
      { label: 'Enviar correo de contraseña', value: 'reset', primary: true }],
  });
  if (v === 'salir') {
    if (!(await confirmDialog(`${u.nombreDisplay} tendrá que volver a iniciar sesión en todos sus dispositivos.`, { title: 'Cerrar sesiones', ok: 'Cerrar sesiones', danger: true }))) return;
    try { await api(`/api/admin/usuarios/${id}/cerrar-sesiones`, { method: 'POST' }); toast('Sesiones cerradas'); } catch (e) { errorToast(e); }
  }
  if (v === 'reset') {
    if (!(await confirmDialog(`${u.nombreDisplay} recibirá un correo para crear una contraseña nueva.`, { title: 'Recuperar contraseña', ok: 'Enviar' }))) return;
    try { await api(`/api/admin/usuarios/${id}/recuperar`, { method: 'POST' }); toast('Correo enviado'); } catch (e) { errorToast(e); }
  }
}

// ═══════════════════ Géneros del servidor ═══════════════════

async function generosTab(el) {
  const g = await api('/api/admin/generos');
  const pct = g.totalCanciones ? Math.round((g.conGenero / g.totalCanciones) * 100) : 0;
  const max = Math.max(1, ...g.generos.map((x) => x.canciones));
  const familias = {};
  const NOMBRE_FAMILIA = { urbano: 'Urbano', regional: 'Regional mexicano', tropical: 'Tropical', cristiana: 'Cristiana', hiphop: 'Hip hop', rnb: 'R&B', electronica: 'Electrónica', romantica: 'Romántica', rock: 'Rock', pop: 'Pop', clasica: 'Clásica y jazz', otros: 'Otros' };
  g.generos.forEach((x) => { const f = NOMBRE_FAMILIA[x.familia] ? x.familia : 'otros'; familias[f] = (familias[f] || 0) + x.canciones; });
  el.innerHTML = `
    <div class="stat-cards">
      <div class="stat"><div class="k">${icon('sparkles')} Géneros</div><div class="v">${fmtNum(g.generos.length)}</div><div class="d">${Object.keys(familias).length} familias</div></div>
      <div class="stat"><div class="k">${icon('note')} Con género</div><div class="v">${pct}%</div><div class="d">${fmtNum(g.conGenero)} de ${fmtNum(g.totalCanciones)} canciones</div></div>
      <div class="stat"><div class="k">${icon('user')} Artistas sin género</div><div class="v">${fmtNum(g.artistasSinGenero)}</div><div class="d">${g.buscando ? 'buscando ahora…' : 'se buscan solos cada 30 min'}</div></div>
    </div>
    <div class="panel" style="margin-bottom:16px"><h3>${icon('library')} Por familia</h3><div class="row wrap">${Object.entries(familias).sort((a, b) => b[1] - a[1])
      .map(([f, n]) => `<span class="badge">${esc(NOMBRE_FAMILIA[f] || f)}: ${fmtNum(n)}</span>`).join('')}</div></div>
    <div class="panels">
      <div class="panel"><h3>${icon('chart')} Géneros en el servidor</h3>
        <div class="genre-bars">${g.generos.map((x) => `<a class="genre-bar" href="#/genero/${encodeURIComponent(x.genero)}" title="${esc(x.ejemplos.join(', '))}">
          <span class="gb-name ellipsis">${esc(x.genero)}</span>
          <span class="gb-track"><i style="width:${Math.max(3, (x.canciones / max) * 100)}%;background:${genreColor(x.genero)}"></i></span>
          <span class="gb-num">${fmtNum(x.canciones)} <small>· ${plural(x.artistas, 'artista', 'artistas')}</small></span></a>`).join('') || '<p class="muted">Todavía no hay géneros.</p>'}</div></div>
      <div class="panel"><div class="row" style="justify-content:space-between;margin-bottom:8px"><h3 style="margin:0">${icon('user')} Artistas sin género</h3>
        <button class="btn sm" data-buscar ${g.buscando ? 'disabled' : ''}>${icon('refresh', 'sm')} ${g.buscando ? 'Buscando…' : 'Buscar ahora'}</button></div>
        <p class="muted" style="margin-top:0;font-size:13px">Si internet no conoce al artista, ponle el género a mano: se aplica a todas sus canciones.</p>
        ${g.sinGenero.length ? g.sinGenero.map((a) => `<div class="kv"><span class="ellipsis">${esc(a.artista)} <small class="muted">${plural(a.canciones, 'canción', 'canciones')}</small></span>
          <button class="btn sm ghost" data-poner="${esc(a.artista)}">${icon('edit', 'sm')} Poner género</button></div>`).join('')
          : `<p class="muted">${icon('check', 'sm')} Todas las canciones tienen género.</p>`}</div>
    </div>`;
  el.querySelector('[data-buscar]').onclick = async () => {
    try { const r = await api('/api/admin/generos/buscar', { method: 'POST' }); toast(r.iniciado ? 'Buscando géneros en internet… tarda unos minutos' : 'Ya hay una búsqueda en marcha'); generosTab(el); }
    catch (e) { errorToast(e); }
  };
  el.querySelectorAll('[data-poner]').forEach((b) => b.addEventListener('click', async () => {
    const artista = b.dataset.poner;
    const genero = await promptDialog(`Género de ${artista}`, { label: 'Género', placeholder: 'Ej.: Banda, Reggaetón, Cristiana, Bachata', ok: 'Guardar' });
    if (!genero) return;
    try { const r = await api('/api/admin/generos/artista', { method: 'PUT', body: { artista, genero } }); toast(`Listo: ${plural(r.canciones, 'canción', 'canciones')} actualizadas`); bus.emit('library:changed'); generosTab(el); }
    catch (e) { errorToast(e); }
  }));
}
