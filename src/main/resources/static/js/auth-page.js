// Lógica de las páginas de acceso (login, registro, recuperación, instalación)
const $ = (s) => document.querySelector(s);
const page = document.body.dataset.page;
const params = new URLSearchParams(location.search);

function msg(text, type = 'error', action) {
  const m = $('#msg');
  m.className = 'msg show ' + type;
  m.textContent = text;
  if (action) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = action.label;
    b.onclick = action.fn;
    m.append(' ', b);
  }
}
const clearMsg = () => { $('#msg').className = 'msg'; };

async function post(url, body) {
  let res;
  try {
    res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  } catch {
    throw new Error('No hay conexión con el servidor');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.error) throw Object.assign(new Error(data.error || 'Error del servidor'), { data });
  return data;
}

function busy(btn, on, label) {
  btn.disabled = on;
  if (on) { btn.dataset.label = btn.textContent; btn.innerHTML = '<span class="spinner"></span>'; }
  else btn.textContent = label || btn.dataset.label;
}

/**
 * Pantalla de apertura al entrar. Los navegadores solo dejan sonar audio después de un clic,
 * así que aquí (tras pulsar "Entrar") es donde el sonido de inicio siempre se escucha en la web.
 */
function entrarConIntro(destino) {
  let sonido = true;
  try { sonido = localStorage.getItem('sm_intro_sound') !== 'false'; sessionStorage.setItem('sm_splash', '1'); } catch { }
  const sp = document.createElement('div');
  sp.id = 'splash';
  sp.innerHTML = `<div class="sp-logo"><svg viewBox="0 0 512 512">
      <rect x="3" y="3" width="506" height="506" rx="112" fill="#000" stroke="#2e2e34" stroke-width="6"/>
      <path class="sp-stem" d="M200 372V150l170-34v222"/>
      <circle class="sp-head sp-h1" cx="156" cy="372" r="54"/><circle class="sp-head sp-h2" cx="326" cy="338" r="54"/>
    </svg></div><div class="sp-name">SpaceMusic</div>`;
  document.body.appendChild(sp);
  let ido = false;
  const ir = () => { if (!ido) { ido = true; location.replace(destino); } };
  if (!sonido) { setTimeout(ir, 1300); return; }
  const a = new Audio('/sonidos/inicio.mp3');
  a.volume = 0.75;
  a.addEventListener('ended', ir);
  a.play().then(() => setTimeout(ir, 3500)).catch(() => setTimeout(ir, 1300));
}

function saveSession(d) {
  try {
    localStorage.setItem('sm_token', d.token);
    localStorage.setItem('sm_user', JSON.stringify(d.usuario));
  } catch { }
}

// Mostrar / ocultar contraseña
document.querySelectorAll('.pw button').forEach((b) => b.addEventListener('click', () => {
  const i = b.parentElement.querySelector('input');
  i.type = i.type === 'password' ? 'text' : 'password';
  b.textContent = i.type === 'password' ? 'Ver' : 'Ocultar';
}));

// Medidor de seguridad de la contraseña
const pwNew = $('[data-strength]');
if (pwNew) {
  const bar = $('.strength i');
  pwNew.addEventListener('input', () => {
    const v = pwNew.value;
    let s = 0;
    if (v.length >= 8) s++;
    if (v.length >= 12) s++;
    if (/[a-z]/i.test(v) && /\d/.test(v)) s++;
    if (/[^a-z0-9]/i.test(v)) s++;
    const colors = ['#ff6b6b', '#ff6b6b', '#f4c46a', '#b9e6a1', '#f5f5f7'];
    bar.style.width = (v ? Math.max(15, s * 25) : 0) + '%';
    bar.style.background = colors[s];
  });
}

const validPw = (p) => (p.length < 8 ? 'La contraseña debe tener al menos 8 caracteres'
  : !/[a-z]/i.test(p) || !/\d/.test(p) ? 'La contraseña debe tener letras y números' : null);

// ═══════════════════ Páginas ═══════════════════

if (page === 'login') {
  // Ya hay sesión válida → directo a la app
  try {
    const t = localStorage.getItem('sm_token');
    if (t) {
      const p = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      if (!p.exp || p.exp * 1000 > Date.now()) location.replace('/');
    }
  } catch { }

  const motivos = {
    expirada: ['Tu sesión expiró. Vuelve a iniciar sesión.', 'error'],
    desactivada: ['Tu cuenta está desactivada.', 'error'],
  };
  if (params.get('verified') === 'true') msg('Correo verificado. Ya puedes iniciar sesión.', 'ok');
  else if (params.get('verified') === 'false') msg('El enlace de verificación no es válido o ya se usó.');
  else if (params.get('reset') === 'true') msg('Contraseña actualizada. Inicia sesión.', 'ok');
  else if (params.get('emailChanged') === 'true') msg('Correo actualizado. Inicia sesión de nuevo.', 'ok');
  else if (params.get('emailChanged') === 'false') msg('El enlace para cambiar el correo no es válido o expiró.');
  else if (motivos[params.get('motivo')]) msg(...motivos[params.get('motivo')]);

  fetch('/api/auth/estado').then((r) => r.json()).then((e) => {
    if (e.necesitaAdmin) $('#setupBanner').classList.add('show');
  }).catch(() => { });

  $('#form').addEventListener('submit', async (e) => {
    e.preventDefault();
    clearMsg();
    const usuario = $('#usuario').value.trim(), password = $('#password').value;
    if (!usuario || !password) return msg('Escribe tu usuario y contraseña');
    const b = $('#btn');
    busy(b, true);
    try {
      const d = await post('/api/auth/login', { usuario, password });
      saveSession(d);
      entrarConIntro('/' + (params.get('next') || ''));
    } catch (err) {
      if (err.data?.needsVerification) {
        msg(err.message, 'error', { label: 'Reenviar correo', fn: async () => {
          const email = usuario.includes('@') ? usuario : prompt('Escribe tu correo:');
          if (!email) return;
          await post('/api/auth/resend-verification', { email }).catch(() => { });
          msg('Si la cuenta existe, te enviamos un nuevo correo de verificación.', 'ok');
        } });
      } else msg(err.message);
    } finally { busy(b, false); }
  });
}

if (page === 'register') {
  $('#form').addEventListener('submit', async (e) => {
    e.preventDefault();
    clearMsg();
    const email = $('#email').value.trim(), username = $('#username').value.trim(),
      nombreDisplay = $('#nombre').value.trim(), password = $('#password').value, p2 = $('#password2').value;
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(email)) return msg('Escribe un correo válido');
    if (!/^[A-Za-z0-9._-]{3,30}$/.test(username)) return msg('El usuario debe tener de 3 a 30 caracteres: letras, números, punto, guion o guion bajo');
    const pe = validPw(password);
    if (pe) return msg(pe);
    if (password !== p2) return msg('Las contraseñas no coinciden');
    const b = $('#btn');
    busy(b, true);
    try {
      const d = await post('/api/auth/register', { email, username, nombreDisplay: nombreDisplay || username, password });
      if (d.verificar || d.pendiente) {
        $('#form').innerHTML = `<p class="sub" style="font-size:15px;color:#f1f1f5">${d.message}</p><a class="btn" href="/login.html">Ir a iniciar sesión</a>`;
      } else {
        // Sin verificación por correo: entrar directamente
        const s = await post('/api/auth/login', { usuario: username, password });
        saveSession(s);
        location.replace('/');
      }
    } catch (err) { msg(err.message); } finally { if (document.body.contains(b)) busy(b, false); }
  });
}

if (page === 'forgot') {
  $('#form').addEventListener('submit', async (e) => {
    e.preventDefault();
    clearMsg();
    const email = $('#email').value.trim();
    if (!email) return msg('Escribe tu correo');
    const b = $('#btn');
    busy(b, true);
    try {
      const d = await post('/api/auth/forgot-password', { email });
      msg(d.message, 'ok');
      $('#email').value = '';
    } catch (err) { msg(err.message); } finally { busy(b, false); }
  });
}

if (page === 'reset') {
  const token = params.get('token');
  if (!token) { msg('El enlace no es válido. Pide uno nuevo.'); $('#btn').disabled = true; }
  $('#form').addEventListener('submit', async (e) => {
    e.preventDefault();
    clearMsg();
    const p = $('#password').value, p2 = $('#password2').value;
    const pe = validPw(p);
    if (pe) return msg(pe);
    if (p !== p2) return msg('Las contraseñas no coinciden');
    const b = $('#btn');
    busy(b, true);
    try {
      await post('/api/auth/reset-password', { token, password: p });
      location.replace('/login.html?reset=true');
    } catch (err) { msg(err.message); busy(b, false); }
  });
}

if (page === 'setup') {
  fetch('/api/auth/estado').then((r) => r.json()).then((e) => {
    if (!e.necesitaAdmin) {
      $('#form').innerHTML = '<p class="sub" style="color:#f1f1f5">Ya existe un administrador.</p><a class="btn" href="/login.html">Iniciar sesión</a>';
    } else if (!e.requiereClave) {
      $('#keyField').style.display = 'none';
      $('#keyHint').textContent = 'No hay clave configurada: abre esta página desde el propio servidor (localhost) o define app.setup-key.';
    }
  }).catch(() => { });
  $('#form').addEventListener('submit', async (e) => {
    e.preventDefault();
    clearMsg();
    const body = {
      email: $('#email').value.trim(), username: $('#username').value.trim(), nombreDisplay: $('#nombre').value.trim(),
      password: $('#password').value, setupKey: $('#key').value,
    };
    const pe = validPw(body.password);
    if (pe) return msg(pe);
    const b = $('#btn');
    busy(b, true);
    try {
      const d = await post('/api/auth/setup-admin', body);
      saveSession(d);
      location.replace('/#/admin');
    } catch (err) { msg(err.message); busy(b, false); }
  });
}

// Aprobación de una cuenta nueva desde el enlace del correo (administradores)
if (page === 'aprobar') {
  const token = params.get('token') || '';
  const box = $('#form');
  const pintar = (html) => { box.innerHTML = html; };
  fetch('/api/auth/aprobacion?token=' + encodeURIComponent(token)).then((r) => r.json()).then((d) => {
    if (d.error) { pintar(`<p class="sub" style="color:#f1f1f5"></p><a class="btn" href="/#/admin">Abrir administración</a>`); box.querySelector('p').textContent = d.error; return; }
    const u = d.usuario;
    pintar(`<div class="who"><div class="av"></div><div><b></b><span></span><small></small></div></div>
      <div class="row2"><button class="btn" id="ok" type="button">Aprobar cuenta</button><button class="btn ghost" id="no" type="button">Rechazar</button></div>`);
    box.querySelector('.av').textContent = (u.nombre || u.username).slice(0, 1).toUpperCase();
    box.querySelector('b').textContent = u.nombre;
    box.querySelector('span').textContent = '@' + u.username + ' · ' + u.email;
    box.querySelector('small').textContent = u.estado === 'PENDIENTE' ? 'Esperando aprobación' : 'Estado: ' + u.estado.toLowerCase();
    const resolver = async (aprobar, btn) => {
      clearMsg();
      busy(btn, true);
      try {
        const r = await post('/api/auth/aprobacion', { token, aprobar });
        pintar(`<p class="sub" style="color:#f1f1f5"></p><a class="btn" href="/#/admin">Ir a administración</a>`);
        box.querySelector('p').textContent = r.message + '. Le avisamos por correo.';
      } catch (err) { msg(err.message); busy(btn, false); }
    };
    $('#ok').addEventListener('click', (e) => resolver(true, e.currentTarget));
    $('#no').addEventListener('click', (e) => resolver(false, e.currentTarget));
  }).catch(() => msg('No hay conexión con el servidor'));
}
