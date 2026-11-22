// Modo fiesta (página de invitados, sin cuenta): buscar canciones del servidor, proponerlas y votar.
// La TV pone a sonar la más votada. Cada invitado tiene un identificador al azar guardado en su teléfono.
const token = decodeURIComponent(location.hash.slice(1)).replace(/[^a-z0-9]/gi, '').toLowerCase();
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const leer = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const guardar = (k, v) => { try { localStorage.setItem(k, v); } catch { } };

let invitado = leer('sm_fiesta_invitado', '');
if (!/^[A-Za-z0-9_-]{8,64}$/.test(invitado)) {
  invitado = 'i' + Array.from(crypto.getRandomValues(new Uint8Array(12)), (b) => b.toString(16).padStart(2, '0')).join('');
  guardar('sm_fiesta_invitado', invitado);
}
const base = `/api/fiesta/publica/${encodeURIComponent(token)}`;
let estado = null, resultados = [], busqueda = '', terminado = false;

async function pedir(ruta, opciones = {}) {
  const r = await fetch(base + ruta, { ...opciones, headers: { 'Content-Type': 'application/json' } });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) { const e = new Error(d.error || d.message || 'No se pudo completar'); e.status = r.status; throw e; }
  return d;
}

let avisoT;
function aviso(t) {
  const a = $('#aviso');
  a.textContent = t;
  a.classList.add('ver');
  clearTimeout(avisoT);
  avisoT = setTimeout(() => a.classList.remove('ver'), 2600);
}

const portada = (c, t = 120) => c?.portadaUrl ? `<img src="${esc(c.portadaUrl)}${c.portadaUrl.includes('?') ? '&' : '?'}t=${t}" alt="" loading="lazy">` : '<img alt="">';

function pintar() {
  if (terminado) { $('#app').innerHTML = '<div class="fin"><h1>La fiesta terminó</h1><p>Gracias por la música 🎶</p></div>'; return; }
  if (!estado) return;
  const enfocado = document.activeElement?.id;
  const props = estado.propuestas || [];
  $('#app').innerHTML = `<header><img src="/img/icon.svg" alt=""><b>SpaceMusic</b></header>
    <h1>La fiesta de ${esc(estado.anfitrion)}</h1>
    <p class="sub">Busca una canción y propónla. La más votada suena después de la actual. Puedes proponer hasta 3.</p>
    ${estado.sonando ? `<div class="ahora">${portada(estado.sonando)}<span style="min-width:0"><small>Sonando ahora</small><b>${esc(estado.sonando.nombre)}</b><small class="s">${esc(estado.sonando.artista)}</small></span></div>` : ''}
    <input class="nombre" id="nombre" placeholder="Tu nombre (opcional)" maxlength="24" value="${esc(leer('sm_fiesta_nombre', ''))}" autocomplete="nickname">
    <form class="buscar" id="f"><input id="q" placeholder="Buscar canción o artista" value="${esc(busqueda)}" autocomplete="off" enterkeyhint="search"></form>
    <div id="res">${resultados.map((c) => `<div class="fila">${portada(c)}<span style="min-width:0"><b>${esc(c.nombre)}</b><small>${esc(c.artista)}</small></span>
      <button class="proponer" data-p="${c.id}">Proponer</button></div>`).join('') || (busqueda.length >= 2 ? '<p class="vacio">Sin resultados.</p>' : '')}</div>
    <h2>Lo más votado</h2>
    ${props.map((p) => `<div class="fila">${portada(p.cancion)}<span style="min-width:0"><b>${esc(p.cancion.nombre)}</b><small>${esc(p.cancion.artista)} · ${esc(p.de)}</small></span>
      <button class="voto ${p.voto ? 'si' : ''}" data-v="${p.id}" aria-pressed="${!!p.voto}">♥ ${p.votos}</button></div>`).join('') || '<p class="vacio">Todavía nadie propone canciones. ¡Sé el primero!</p>'}`;
  if (enfocado === 'q') { const q = $('#q'); q.focus(); q.setSelectionRange(q.value.length, q.value.length); }
}

let buscarT;
document.addEventListener('input', (e) => {
  if (e.target.id === 'nombre') guardar('sm_fiesta_nombre', e.target.value.trim());
  if (e.target.id !== 'q') return;
  busqueda = e.target.value.trim();
  clearTimeout(buscarT);
  buscarT = setTimeout(async () => {
    if (busqueda.length < 2) { resultados = []; pintar(); return; }
    try { resultados = await pedir('/buscar?q=' + encodeURIComponent(busqueda)); } catch (err) { aviso(err.message); }
    pintar();
  }, 350);
});
document.addEventListener('submit', (e) => { e.preventDefault(); document.activeElement?.blur(); });
document.addEventListener('click', async (e) => {
  const p = e.target.closest('[data-p]'), v = e.target.closest('[data-v]');
  try {
    if (p) {
      p.disabled = true;
      estado = await pedir('/proponer', { method: 'POST', body: JSON.stringify({ cancionId: Number(p.dataset.p), invitado, nombre: leer('sm_fiesta_nombre', '') }) });
      aviso('¡Propuesta! Pide a tus amigos que la voten');
      resultados = []; busqueda = '';
      pintar();
    } else if (v) {
      estado = await pedir(`/votar/${v.dataset.v}?`, { method: 'POST', body: JSON.stringify({ invitado }) });
      pintar();
    }
  } catch (err) { aviso(err.message); if (p) p.disabled = false; }
});

async function actualizar() {
  if (document.activeElement?.id === 'q' && resultados.length) return setTimeout(actualizar, 4000); // no molestar mientras busca
  try { estado = await pedir('?invitado=' + encodeURIComponent(invitado)); pintar(); }
  catch (err) { if (err.status === 404) { terminado = true; pintar(); return; } }
  setTimeout(actualizar, 4000);
}

if (!token) { terminado = true; pintar(); } else actualizar();
