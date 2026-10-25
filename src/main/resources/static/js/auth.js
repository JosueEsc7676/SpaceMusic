/* =====================================================
   auth.js — v2
   Cargar PRIMERO en index.html
   ===================================================== */
(function() {
    const token   = localStorage.getItem('sm_token');
    const userStr = localStorage.getItem('sm_user');

    if (!token || !userStr) { location.href = '/login.html'; return; }

    let currentUser;
    try { currentUser = JSON.parse(userStr); }
    catch(e) { localStorage.clear(); location.href = '/login.html'; return; }

    // Verificar expiración JWT
    try {
        const payload = JSON.parse(atob(token.split('.')[1]));
        if (payload.exp && Date.now()/1000 > payload.exp) {
            localStorage.clear(); location.href = '/login.html'; return;
        }
    } catch(e) { location.href = '/login.html'; return; }

    window.currentUser = currentUser;
    window.authToken   = token;
    window.isAdmin     = currentUser.rol === 'ADMIN';
    window.isColab     = currentUser.rol === 'COLABORADOR';

    // ── Inyectar Authorization en todos los fetch a /api/ ──
    const origFetch = window.fetch;
    window.fetch = function(url, options = {}) {
        if (typeof url === 'string' && url.startsWith('/api/')) {
            options.headers = { ...options.headers, 'Authorization': 'Bearer ' + token };
        }
        return origFetch(url, options);
    };

    document.addEventListener('DOMContentLoaded', () => {
        _applyRoleUI();
        _renderUserProfile();
        _hookLogout();
        _hookFavoritos();
        _hookPlaySong();
    });

    // ── Restricciones por rol ──
    function _applyRoleUI() {
        // Estadísticas globales: solo ADMIN
        if (!window.isAdmin) {
            const statsNav = document.querySelector('.nav-item[data-view="estadisticas"]');
            if (statsNav) statsNav.style.display = 'none';
        }
        // Botón LRC: ADMIN o COLABORADOR
        if (!window.isAdmin && !window.isColab) {
            const lyricsBtn = document.querySelector('.lyrics-btn');
            if (lyricsBtn) lyricsBtn.style.display = 'none';
        }
    }

    // ── Avatar en top bar ──
    function _renderUserProfile() {
        const profileEl = document.querySelector('.user-profile');
        if (!profileEl) return;

        const avatarHtml = currentUser.fotoPerfil
            ? `<img src="${currentUser.fotoPerfil}" style="width:32px;height:32px;border-radius:50%;object-fit:cover;border:2px solid #1db954;flex-shrink:0;" onerror="this.src=''">`
            : `<div style="width:32px;height:32px;border-radius:50%;background:#282828;border:2px solid #333;display:flex;align-items:center;justify-content:center;flex-shrink:0;"><i class="fas fa-user" style="font-size:13px;color:#b3b3b3;"></i></div>`;

        const rolColor  = window.isAdmin ? '#e74c3c' : window.isColab ? '#3498db' : '#555';
        const rolLabel  = window.isAdmin ? 'Administrador' : window.isColab ? 'Colaborador' : 'Usuario';
        const dotColor  = window.isAdmin ? '#e74c3c' : window.isColab ? '#3498db' : 'transparent';

        profileEl.style.position = 'relative';
        profileEl.innerHTML = `
            <div onclick="window._toggleUserMenu()" id="userProfileBtn"
                 style="display:flex;align-items:center;gap:10px;cursor:pointer;
                        padding:5px 10px 5px 6px;border-radius:24px;
                        border:1px solid rgba(255,255,255,0.08);
                        background:rgba(255,255,255,0.04);
                        transition:background .2s,border-color .2s;"
                 onmouseover="this.style.background='rgba(255,255,255,0.08)';this.style.borderColor='rgba(255,255,255,0.15)'"
                 onmouseout="this.style.background='rgba(255,255,255,0.04)';this.style.borderColor='rgba(255,255,255,0.08)'">
                <div style="position:relative;">
                    ${avatarHtml}
                    <span style="position:absolute;bottom:0;right:0;width:9px;height:9px;
                                 background:${dotColor};border-radius:50%;
                                 border:2px solid #0a0a0a;"></span>
                </div>
                <div style="min-width:0;line-height:1.2;">
                    <div style="font-size:13px;font-weight:600;color:#fff;
                                white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:110px;">
                        ${currentUser.nombreDisplay || currentUser.username}
                    </div>
                    <div style="font-size:10px;font-weight:600;color:${rolColor};">${rolLabel}</div>
                </div>
                <i class="fas fa-chevron-down" style="font-size:9px;color:#555;flex-shrink:0;margin-left:2px;"></i>
            </div>

            <div id="userMenu" style="
                display:none;
                position:absolute;top:calc(100% + 8px);right:0;
                background:#111;
                border:1px solid rgba(255,255,255,0.1);
                border-radius:16px;
                padding:8px;
                min-width:230px;
                z-index:9999;
                box-shadow:0 20px 60px rgba(0,0,0,0.8),0 4px 16px rgba(0,0,0,0.4);">

                <!-- Header del menú -->
                <div style="display:flex;align-items:center;gap:12px;
                            padding:10px 12px 12px;
                            border-bottom:1px solid rgba(255,255,255,0.07);
                            margin-bottom:6px;">
                    ${avatarHtml.replace('32px','42px').replace('32px','42px')}
                    <div style="min-width:0;">
                        <div style="font-size:14px;font-weight:700;color:#fff;
                                    white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:150px;">
                            ${currentUser.nombreDisplay || currentUser.username}
                        </div>
                        <div style="font-size:11px;color:#666;margin-top:1px;">@${currentUser.username}</div>
                        <div style="font-size:10px;font-weight:700;color:${rolColor};margin-top:2px;">${rolLabel}</div>
                    </div>
                </div>

                ${_menuItem('fas fa-user-circle', 'Perfil y configuración', 'window.loadMiPerfil()')}
                ${_menuItem('fas fa-chart-bar',   'Mis estadísticas',       'window.loadMisEstadisticas()')}
                ${window.isAdmin ? `
                    <div style="height:1px;background:rgba(255,255,255,0.07);margin:6px 4px;"></div>
                    ${_menuItem('fas fa-shield-alt', 'Panel de administración', 'window.loadPanelAdmin()', '#e74c3c')}
                ` : ''}
                <div style="height:1px;background:rgba(255,255,255,0.07);margin:6px 4px;"></div>
                ${_menuItem('fas fa-sign-out-alt', 'Cerrar sesión', 'window.doLogout()', '#e74c3c')}
            </div>`;
    }

    function _menuItem(icon, label, onclick, color) {
        const textColor = color || 'rgba(255,255,255,0.85)';
        const iconBg    = color === '#e74c3c'
            ? 'rgba(231,76,60,0.12)'
            : color === '#3498db'
            ? 'rgba(52,152,219,0.12)'
            : 'rgba(255,255,255,0.06)';
        const iconColor = color || '#1db954';
        return `<div onclick="${onclick};document.getElementById('userMenu').style.display='none'"
            style="display:flex;align-items:center;gap:12px;
                   padding:9px 10px;border-radius:10px;cursor:pointer;
                   color:${textColor};font-size:13px;font-weight:500;
                   transition:background .15s;"
            onmouseover="this.style.background='rgba(255,255,255,0.05)'"
            onmouseout="this.style.background='transparent'">
            <div style="width:32px;height:32px;border-radius:9px;
                        background:${iconBg};flex-shrink:0;
                        display:flex;align-items:center;justify-content:center;">
                <i class="${icon}" style="font-size:13px;color:${iconColor};"></i>
            </div>
            <span style="flex:1;">${label}</span>
        </div>`;
    }

    function _hookLogout() {
        window._toggleUserMenu = function() {
            const m = document.getElementById('userMenu');
            if (m) m.style.display = m.style.display === 'none' ? 'block' : 'none';
        };
        document.addEventListener('click', (e) => {
            const btn = document.getElementById('userProfileBtn');
            const menu = document.getElementById('userMenu');
            if (menu && btn && !btn.contains(e.target)) menu.style.display = 'none';
        });
        window.doLogout = function() {
            localStorage.clear(); location.href = '/login.html';
        };
    }

    // ── Favoritos por usuario ──
    function _hookFavoritos() {
        const check = () => {
            if (typeof window.toggleFavorito !== 'function') { setTimeout(check, 300); return; }

            // Override loadFavoritesView
            window.loadFavoritesView = async function() {
                const content = document.getElementById('dynamicContent');
                content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando favoritos...</div>';
                window.cache.favoritos = null;
                const favs = await fetch('/api/usuario/favoritos').then(r => r.json()).catch(() => []);
                if (!favs?.length) {
                    content.innerHTML = '<div class="loading"><i class="fas fa-heart" style="font-size:48px"></i><br>No tienes canciones favoritas aún</div>';
                    return;
                }
                content.innerHTML = `<div class="section"><div class="section-header"><h2>❤️ Mis favoritas</h2></div><div class="grid" id="favoritesGrid"></div></div>`;
                window.renderSongsWithMenu?.(favs, 'favoritesGrid', 'favorites', 'Mis favoritas');
            };
        };
        setTimeout(check, 500);
    }

    // ── Registrar reproducción por usuario ──
    function _hookPlaySong() {
        const check = () => {
            if (typeof window.playSongFromObject !== 'function') { setTimeout(check, 400); return; }
            const orig = window.playSongFromObject;
            window.playSongFromObject = function(song) {
                orig(song);
                fetch(`/api/usuario/reproduccion/${song.id}?contexto=${window.state?.playContext?.type || 'unknown'}`,
                    { method: 'POST' }).catch(() => {});
            };
        };
        setTimeout(check, 600);
    }

    // =======================================================
    // PERFIL Y CONFIGURACIÓN
    // =======================================================
    window.loadMiPerfil = function() {
        const u = window.currentUser;
        const content = document.getElementById('dynamicContent');
        if (!content) return;
        document.getElementById('userMenu').style.display = 'none';

        const fotoHTML = u.fotoPerfil
            ? `<img src="${u.fotoPerfil}" id="avatarImg" style="width:110px;height:110px;border-radius:50%;object-fit:cover;border:3px solid #1db954;" onerror="this.style.display='none'">`
            : `<div style="width:110px;height:110px;border-radius:50%;background:#282828;display:flex;align-items:center;justify-content:center;font-size:44px;color:#555;border:3px solid #333;"><i class="fas fa-user"></i></div>`;

        const solicitudBadge = {
            NINGUNA:   '',
            PENDIENTE: '<span style="background:#f39c12;color:#000;font-size:11px;padding:3px 10px;border-radius:12px;font-weight:700;">Solicitud pendiente ⏳</span>',
            APROBADA:  '<span style="background:#1db954;color:#000;font-size:11px;padding:3px 10px;border-radius:12px;font-weight:700;">Colaborador ✓</span>',
            RECHAZADA: '<span style="background:#e74c3c;color:#fff;font-size:11px;padding:3px 10px;border-radius:12px;font-weight:700;">Solicitud rechazada</span>',
        }[u.solicitudColaborador || 'NINGUNA'] || '';

        content.innerHTML = `
        <div class="section">
            <div style="display:flex;align-items:center;gap:24px;margin-bottom:32px;flex-wrap:wrap;">
                <div style="position:relative;cursor:pointer;" onclick="document.getElementById('avatarInput').click()">
                    ${fotoHTML}
                    <div style="position:absolute;bottom:4px;right:4px;width:28px;height:28px;background:#1db954;border-radius:50%;display:flex;align-items:center;justify-content:center;">
                        <i class="fas fa-camera" style="color:#000;font-size:11px;"></i>
                    </div>
                </div>
                <input type="file" id="avatarInput" accept="image/*" style="display:none" onchange="_uploadAvatar(this)">
                <div>
                    <h1 style="font-size:28px;font-weight:800;margin-bottom:4px;">${u.nombreDisplay || u.username}</h1>
                    <p style="color:#b3b3b3;">@${u.username}</p>
                    <p style="color:#b3b3b3;font-size:13px;">${u.email}</p>
                    <div style="margin-top:6px;display:flex;gap:8px;flex-wrap:wrap;">
                        <span style="background:#282828;color:#fff;font-size:11px;padding:3px 10px;border-radius:12px;">${u.rol}</span>
                        ${solicitudBadge}
                    </div>
                </div>
            </div>

            <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:16px;">

                <!-- Editar perfil -->
                <div style="background:#1a1a1a;border-radius:14px;padding:20px;">
                    <h3 style="margin-bottom:14px;font-size:15px;"><i class="fas fa-user" style="color:#1db954;margin-right:8px;"></i>Editar perfil</h3>
                    <label style="font-size:11px;color:#b3b3b3;text-transform:uppercase;display:block;margin-bottom:4px;">Nombre visible</label>
                    <input id="editNombre" value="${u.nombreDisplay || ''}" style="${_inputStyle()}" onfocus="this.style.borderColor='#1db954'" onblur="this.style.borderColor='#333'">
                    <button onclick="_saveProfile()" style="${_btnStyle()};margin-top:10px;">Guardar</button>
                </div>

                <!-- Cambiar contraseña -->
                <div style="background:#1a1a1a;border-radius:14px;padding:20px;">
                    <h3 style="margin-bottom:14px;font-size:15px;"><i class="fas fa-lock" style="color:#1db954;margin-right:8px;"></i>Cambiar contraseña</h3>
                    <label style="font-size:11px;color:#b3b3b3;text-transform:uppercase;display:block;margin-bottom:4px;">Contraseña actual</label>
                    <input type="password" id="passActual" placeholder="••••••••" style="${_inputStyle()};margin-bottom:8px;" onfocus="this.style.borderColor='#1db954'" onblur="this.style.borderColor='#333'">
                    <label style="font-size:11px;color:#b3b3b3;text-transform:uppercase;display:block;margin-bottom:4px;">Nueva contraseña</label>
                    <input type="password" id="passNueva" placeholder="Mínimo 8 caracteres" style="${_inputStyle()};margin-bottom:8px;" onfocus="this.style.borderColor='#1db954'" onblur="this.style.borderColor='#333'">
                    <label style="font-size:11px;color:#b3b3b3;text-transform:uppercase;display:block;margin-bottom:4px;">Confirmar nueva</label>
                    <input type="password" id="passConfirm" placeholder="Repite la nueva" style="${_inputStyle()}" onfocus="this.style.borderColor='#1db954'" onblur="this.style.borderColor='#333'">
                    <button onclick="_changePassword()" style="${_btnStyle()};margin-top:10px;">Actualizar contraseña</button>
                </div>

                <!-- Cambiar email -->
                <div style="background:#1a1a1a;border-radius:14px;padding:20px;">
                    <h3 style="margin-bottom:14px;font-size:15px;"><i class="fas fa-envelope" style="color:#1db954;margin-right:8px;"></i>Cambiar email</h3>
                    <p style="font-size:12px;color:#b3b3b3;margin-bottom:10px;">Email actual: <strong>${u.email}</strong></p>
                    <label style="font-size:11px;color:#b3b3b3;text-transform:uppercase;display:block;margin-bottom:4px;">Nuevo email</label>
                    <input type="email" id="newEmail" placeholder="nuevo@correo.com" style="${_inputStyle()};margin-bottom:8px;" onfocus="this.style.borderColor='#1db954'" onblur="this.style.borderColor='#333'">
                    <label style="font-size:11px;color:#b3b3b3;text-transform:uppercase;display:block;margin-bottom:4px;">Contraseña actual</label>
                    <input type="password" id="emailPass" placeholder="Para confirmar tu identidad" style="${_inputStyle()}" onfocus="this.style.borderColor='#1db954'" onblur="this.style.borderColor='#333'">
                    <button onclick="_changeEmail()" style="${_btnStyle()};margin-top:10px;">Solicitar cambio</button>
                </div>

                <!-- Solicitar colaborador (solo si es USER sin solicitud pendiente/aprobada) -->
                ${(u.rol === 'USER' && u.solicitudColaborador !== 'PENDIENTE' && u.solicitudColaborador !== 'APROBADA') ? `
                <div style="background:#1a1a1a;border-radius:14px;padding:20px;">
                    <h3 style="margin-bottom:10px;font-size:15px;"><i class="fas fa-star" style="color:#f39c12;margin-right:8px;"></i>Ser colaborador</h3>
                    <p style="font-size:12px;color:#b3b3b3;margin-bottom:12px;">Como colaborador podrás subir letras LRC, subir canciones y editar nombres de canciones.</p>
                    <label style="font-size:11px;color:#b3b3b3;text-transform:uppercase;display:block;margin-bottom:4px;">¿Por qué quieres ser colaborador?</label>
                    <textarea id="motivoColab" placeholder="Cuéntanos brevemente..." rows="3" style="${_inputStyle()};resize:vertical;"></textarea>
                    <button onclick="_solicitarColab()" style="${_btnStyle('#f39c12','#000')};margin-top:10px;"><i class="fas fa-paper-plane"></i> Enviar solicitud</button>
                    ${u.solicitudColaborador === 'RECHAZADA' ? '<p style="color:#e74c3c;font-size:12px;margin-top:8px;">Tu solicitud anterior fue rechazada. Puedes intentar de nuevo.</p>' : ''}
                </div>` : ''}

                <!-- Zona de peligro -->
                <div style="background:#1a1a1a;border:1px solid #e74c3c33;border-radius:14px;padding:20px;">
                    <h3 style="margin-bottom:10px;font-size:15px;color:#e74c3c;"><i class="fas fa-exclamation-triangle" style="margin-right:8px;"></i>Zona de peligro</h3>
                    <p style="font-size:12px;color:#b3b3b3;margin-bottom:12px;">Cerrar sesión en todos los dispositivos.</p>
                    <button onclick="window.doLogout()" style="${_btnStyle('#e74c3c')};">
                        <i class="fas fa-sign-out-alt"></i> Cerrar sesión
                    </button>
                </div>
            </div>
        </div>`;
    };

    // ── Helpers de UI ──
    function _inputStyle() {
        return 'width:100%;background:#111;border:1.5px solid #333;border-radius:8px;padding:9px 12px;color:#fff;font-size:13px;font-family:inherit;box-sizing:border-box;';
    }
    function _btnStyle(bg, color) {
        bg = bg || '#1db954'; color = color || '#000';
        return `background:${bg};color:${color};border:none;border-radius:8px;padding:9px 18px;font-size:13px;font-weight:700;cursor:pointer;width:100%;`;
    }

    window._saveProfile = async function() {
        const nombre = document.getElementById('editNombre')?.value?.trim();
        const res  = await fetch('/api/usuario/perfil', {
            method: 'PUT', headers: {'Content-Type':'application/json'},
            body: JSON.stringify({ nombreDisplay: nombre })
        });
        const data = await res.json();
        if (data.usuario) {
            window.currentUser = data.usuario;
            localStorage.setItem('sm_user', JSON.stringify(data.usuario));
            window.showToast?.('✅ Perfil actualizado');
            window.loadMiPerfil?.();
        } else window.showToast?.(data.error || 'Error', true);
    };

    window._changePassword = async function() {
        const actual  = document.getElementById('passActual')?.value;
        const nueva   = document.getElementById('passNueva')?.value;
        const confirm = document.getElementById('passConfirm')?.value;
        if (nueva !== confirm) { window.showToast?.('Las contraseñas no coinciden', true); return; }
        const res  = await fetch('/api/usuario/cambiar-password', {
            method: 'POST', headers: {'Content-Type':'application/json'},
            body: JSON.stringify({ actual, nueva })
        });
        const data = await res.json();
        if (data.ok) {
            window.showToast?.('✅ Contraseña actualizada');
            document.getElementById('passActual').value = '';
            document.getElementById('passNueva').value  = '';
            document.getElementById('passConfirm').value = '';
        } else window.showToast?.(data.error || 'Error', true);
    };

    window._changeEmail = async function() {
        const nuevoEmail = document.getElementById('newEmail')?.value?.trim();
        const password   = document.getElementById('emailPass')?.value;
        const res  = await fetch('/api/usuario/cambiar-email', {
            method: 'POST', headers: {'Content-Type':'application/json'},
            body: JSON.stringify({ nuevoEmail, password })
        });
        const data = await res.json();
        if (data.ok) window.showToast?.('✅ ' + data.message);
        else         window.showToast?.(data.error || 'Error', true);
    };

    window._solicitarColab = async function() {
        const motivo = document.getElementById('motivoColab')?.value?.trim();
        const res  = await fetch('/api/usuario/solicitar-colaborador', {
            method: 'POST', headers: {'Content-Type':'application/json'},
            body: JSON.stringify({ motivo })
        });
        const data = await res.json();
        if (data.ok) {
            window.showToast?.('✅ ' + data.message);
            window.currentUser.solicitudColaborador = 'PENDIENTE';
            localStorage.setItem('sm_user', JSON.stringify(window.currentUser));
            window.loadMiPerfil?.();
        } else window.showToast?.(data.error || 'Error', true);
    };

    window._uploadAvatar = async function(input) {
        const file = input.files[0];
        if (!file) return;
        const fd = new FormData(); fd.append('file', file);
        const res  = await fetch('/api/auth/me/foto', { method: 'POST', body: fd });
        const data = await res.json();
        if (data.fotoPerfil) {
            window.currentUser.fotoPerfil = data.fotoPerfil;
            localStorage.setItem('sm_user', JSON.stringify(window.currentUser));
            window.showToast?.('✅ Foto actualizada');
            window.loadMiPerfil?.();
            // Refrescar avatar en topbar
            const img = document.querySelector('#userProfileBtn img');
            if (img) { img.src = data.fotoPerfil; img.style.display = ''; }
        } else window.showToast?.(data.error || 'Error subiendo foto', true);
    };

    // =======================================================
    // ESTADÍSTICAS DEL USUARIO
    // =======================================================
    window.loadMisEstadisticas = async function() {
        const content = document.getElementById('dynamicContent');
        if (!content) return;
        document.getElementById('userMenu').style.display = 'none';
        content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando estadísticas...</div>';
        try {
            const stats = await fetch('/api/usuario/estadisticas').then(r => r.json());
            content.innerHTML = `
            <div class="section">
                <div class="section-header"><h2>📊 Mis estadísticas</h2></div>
                <div class="stats-grid">
                    <div class="stat-card"><i class="fas fa-play-circle stat-icon"></i>
                        <div class="stat-number">${stats.totalReproducciones||0}</div><div class="stat-label">Reproducciones</div></div>
                    <div class="stat-card"><i class="fas fa-heart stat-icon" style="color:#e91e63"></i>
                        <div class="stat-number">${stats.totalFavoritos||0}</div><div class="stat-label">Favoritas</div></div>
                    <div class="stat-card"><i class="fas fa-clock stat-icon"></i>
                        <div class="stat-number">${stats.tiempoEscuchado||'0 min'}</div><div class="stat-label">Tiempo escuchado</div></div>
                </div>
                <div class="stats-row">
                    <div class="stats-panel">
                        <h3 style="margin-bottom:16px"><i class="fas fa-music" style="color:#1db954;margin-right:8px"></i>Mis canciones más escuchadas</h3>
                        <div class="top-list">
                            ${(stats.topCanciones||[]).map((c,i)=>`
                                <div class="top-item" onclick="playSong(${c.id})">
                                    <span class="top-rank">${i+1}</span>
                                    <div class="top-item-info"><div class="top-item-name">${c.nombre}</div>
                                    <div style="font-size:11px;color:#b3b3b3">${c.artista}</div></div>
                                    <span class="top-item-count">${c.veces}x</span>
                                </div>`).join('')}
                        </div>
                    </div>
                    <div class="stats-panel">
                        <h3 style="margin-bottom:16px"><i class="fas fa-microphone" style="color:#1db954;margin-right:8px"></i>Mis artistas</h3>
                        <div class="top-list">
                            ${(stats.topArtistas||[]).map((a,i)=>`
                                <div class="top-item" onclick="loadArtistaDetalle('${a.artista}')">
                                    <span class="top-rank">${i+1}</span>
                                    <div class="top-item-info"><div class="top-item-name">${a.artista}</div></div>
                                    <span class="top-item-count">${a.veces}x</span>
                                </div>`).join('')}
                        </div>
                    </div>
                </div>
            </div>`;
        } catch(e) { content.innerHTML = '<div class="loading">Error cargando estadísticas</div>'; }
    };

    // =======================================================
    // PANEL ADMIN
    // =======================================================
    window.loadPanelAdmin = async function() {
        if (!window.isAdmin) return;
        const content = document.getElementById('dynamicContent');
        if (!content) return;
        document.getElementById('userMenu').style.display = 'none';
        content.innerHTML = '<div class="loading"><i class="fas fa-spinner fa-spin"></i><br>Cargando panel...</div>';

        const [resumen, solicitudes, usuarios] = await Promise.all([
            fetch('/api/admin/resumen').then(r=>r.json()),
            fetch('/api/admin/solicitudes').then(r=>r.json()),
            fetch('/api/admin/usuarios').then(r=>r.json())
        ]);

        content.innerHTML = `
        <div class="section">
            <div class="section-header">
                <h2><i class="fas fa-shield-alt" style="color:#e74c3c;margin-right:8px;"></i>Panel de administración</h2>
            </div>

            <!-- Resumen -->
            <div class="stats-grid" style="margin-bottom:24px;">
                <div class="stat-card"><i class="fas fa-users stat-icon"></i>
                    <div class="stat-number">${resumen.totalUsuarios}</div><div class="stat-label">Usuarios totales</div></div>
                <div class="stat-card"><i class="fas fa-user-shield stat-icon" style="color:#e74c3c;"></i>
                    <div class="stat-number">${resumen.admins}</div><div class="stat-label">Admins</div></div>
                <div class="stat-card"><i class="fas fa-star stat-icon" style="color:#3498db;"></i>
                    <div class="stat-number">${resumen.colaboradores}</div><div class="stat-label">Colaboradores</div></div>
                <div class="stat-card"><i class="fas fa-clock stat-icon" style="color:#f39c12;"></i>
                    <div class="stat-number">${resumen.solicitudesPendientes}</div><div class="stat-label">Solicitudes pendientes</div></div>
            </div>

            <!-- Solicitudes pendientes -->
            ${solicitudes.length ? `
            <div style="background:#1a1a1a;border-radius:14px;padding:20px;margin-bottom:20px;">
                <h3 style="margin-bottom:16px;color:#f39c12;"><i class="fas fa-bell" style="margin-right:8px;"></i>
                    Solicitudes de colaborador (${solicitudes.length})</h3>
                ${solicitudes.map(u=>`
                    <div style="display:flex;align-items:center;gap:12px;padding:12px;background:#111;border-radius:10px;margin-bottom:8px;flex-wrap:wrap;">
                        <div style="flex:1;min-width:0;">
                            <div style="font-weight:600;">${u.nombreDisplay || u.username}</div>
                            <div style="font-size:12px;color:#b3b3b3;">${u.email}</div>
                            ${u.motivoSolicitud ? `<div style="font-size:12px;color:#b3b3b3;margin-top:4px;font-style:italic;">"${u.motivoSolicitud}"</div>` : ''}
                        </div>
                        <div style="display:flex;gap:8px;">
                            <button onclick="_adminAprobar(${u.id})" style="background:#1db954;color:#000;border:none;border-radius:8px;padding:6px 14px;font-size:12px;font-weight:700;cursor:pointer;">✓ Aprobar</button>
                            <button onclick="_adminRechazar(${u.id})" style="background:#e74c3c;color:#fff;border:none;border-radius:8px;padding:6px 14px;font-size:12px;font-weight:700;cursor:pointer;">✗ Rechazar</button>
                        </div>
                    </div>`).join('')}
            </div>` : ''}

            <!-- Lista de usuarios -->
            <div style="background:#1a1a1a;border-radius:14px;padding:20px;">
                <h3 style="margin-bottom:16px;"><i class="fas fa-users" style="color:#1db954;margin-right:8px;"></i>Todos los usuarios</h3>
                <table style="width:100%;border-collapse:collapse;font-size:13px;">
                    <thead><tr style="color:#b3b3b3;border-bottom:1px solid #333;">
                        <th style="text-align:left;padding:8px 4px;">Usuario</th>
                        <th style="text-align:left;padding:8px 4px;">Email</th>
                        <th style="text-align:left;padding:8px 4px;">Rol</th>
                        <th style="text-align:left;padding:8px 4px;">Estado</th>
                        <th style="text-align:left;padding:8px 4px;">Acciones</th>
                    </tr></thead>
                    <tbody>
                    ${usuarios.map(u=>`
                        <tr style="border-bottom:1px solid #222;">
                            <td style="padding:8px 4px;">${u.nombreDisplay || u.username}</td>
                            <td style="padding:8px 4px;color:#b3b3b3;">${u.email}</td>
                            <td style="padding:8px 4px;">
                                <select onchange="_adminCambiarRol(${u.id}, this.value)"
                                    style="background:#282828;color:#fff;border:1px solid #333;border-radius:6px;padding:3px 6px;font-size:11px;">
                                    ${['USER','COLABORADOR','ADMIN'].map(r=>`<option value="${r}" ${u.rol===r?'selected':''}>${r}</option>`).join('')}
                                </select>
                            </td>
                            <td style="padding:8px 4px;">
                                <span style="background:${u.activo?'#1db95422':'#e74c3c22'};color:${u.activo?'#1db954':'#e74c3c'};padding:2px 8px;border-radius:12px;font-size:11px;">
                                    ${u.activo?'Activo':'Inactivo'}
                                </span>
                            </td>
                            <td style="padding:8px 4px;">
                                <button onclick="_adminToggleActivo(${u.id}, ${!u.activo})"
                                    style="background:#282828;color:#fff;border:none;border-radius:6px;padding:3px 10px;font-size:11px;cursor:pointer;">
                                    ${u.activo?'Desactivar':'Activar'}
                                </button>
                            </td>
                        </tr>`).join('')}
                    </tbody>
                </table>
            </div>
        </div>`;
    };

    window._adminAprobar = async function(userId) {
        const res = await fetch(`/api/admin/solicitudes/${userId}/aprobar`, {method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});
        const d   = await res.json();
        if (d.ok) { window.showToast?.('✅ Colaborador aprobado'); window.loadPanelAdmin?.(); }
        else window.showToast?.(d.error||'Error', true);
    };

    window._adminRechazar = async function(userId) {
        const res = await fetch(`/api/admin/solicitudes/${userId}/rechazar`, {method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});
        const d   = await res.json();
        if (d.ok) { window.showToast?.('Solicitud rechazada'); window.loadPanelAdmin?.(); }
        else window.showToast?.(d.error||'Error', true);
    };

    window._adminCambiarRol = async function(userId, rol) {
        const res = await fetch(`/api/admin/usuarios/${userId}/rol`, {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({rol})});
        const d   = await res.json();
        if (d.ok) window.showToast?.(`✅ Rol cambiado a ${d.rol}`);
        else window.showToast?.(d.error||'Error', true);
    };

    window._adminToggleActivo = async function(userId, activo) {
        const res = await fetch(`/api/admin/usuarios/${userId}/estado`, {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({activo})});
        const d   = await res.json();
        if (d.ok) { window.showToast?.(`✅ Usuario ${activo?'activado':'desactivado'}`); window.loadPanelAdmin?.(); }
        else window.showToast?.(d.error||'Error', true);
    };

})();