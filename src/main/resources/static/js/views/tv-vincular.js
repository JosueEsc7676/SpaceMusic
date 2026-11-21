// Iniciar sesión en una TV desde este teléfono o computadora: con el QR (abre #/tv/CÓDIGO), escribiendo el
// código que muestra la TV o tocando la TV que aparece «en tu misma red wifi».
import { api, session } from '../api.js';
import { esc } from '../util.js';
import { icon } from '../icons.js';
import { toast, errorToast } from '../ui.js';
import { setTitle } from '../router.js';

const limpiar = (c) => String(c || '').replace(/[^A-Za-z0-9]/g, '').toUpperCase().slice(0, 6);

export async function tvVincularView(el, params) {
  setTitle('Iniciar sesión en la TV');
  const codigo = limpiar(params.codigo);
  if (!codigo) return pintarFormulario(el);
  el.innerHTML = '<div class="tvv"><p class="muted">Buscando la TV…</p></div>';
  let tv;
  try { tv = await api('/api/auth/tv/vinculo/' + codigo); } catch (e) { return pintarFormulario(el, e.message); }
  const u = session.user || {};
  el.innerHTML = `<div class="tvv">
    <div class="tvv-ico">${icon('tv')}</div>
    <h1 class="page-title">¿Iniciar sesión en la TV?</h1>
    <p class="tvv-tv"><b>${esc(tv.nombre)}</b> · código ${esc(codigo.slice(0, 3))} ${esc(codigo.slice(3))}</p>
    <p class="muted">La TV entrará con tu cuenta <b>@${esc(u.username || '')}</b>. Solo apruébalo si la TV está frente a ti.</p>
    <div class="row wrap tvv-acc"><button class="btn primary" data-si>${icon('check', 'sm')} Sí, iniciar sesión</button>
      <a class="btn" href="#/">Cancelar</a></div></div>`;
  el.querySelector('[data-si]').onclick = async (e) => {
    e.currentTarget.disabled = true;
    try {
      await api(`/api/auth/tv/vinculo/${codigo}/aprobar`, { method: 'POST' });
      el.querySelector('.tvv').innerHTML = `<div class="tvv-ico ok">${icon('checkCircle')}</div><h1 class="page-title">Listo</h1>
        <p class="muted">La TV ya entró con tu cuenta. Puedes controlarla desde «Dispositivos».</p>
        <div class="row wrap tvv-acc"><a class="btn primary" href="#/">Ir al inicio</a></div>`;
    } catch (err) { errorToast(err); e.currentTarget.disabled = false; }
  };
}

function pintarFormulario(el, error = '') {
  el.innerHTML = `<div class="tvv">
    <div class="tvv-ico">${icon('tv')}</div>
    <h1 class="page-title">Iniciar sesión en la TV</h1>
    <p class="muted">Abre SpaceMusic en la TV y escribe aquí el código que aparece en pantalla.</p>
    <form class="tvv-form" data-f><input class="input" data-c maxlength="7" placeholder="ABC 123" autocomplete="off" autocapitalize="characters" aria-label="Código de la TV">
      <button class="btn primary">Continuar</button></form>
    ${error ? `<p class="tvv-error">${esc(error)}</p>` : ''}
    <div data-cerca></div></div>`;
  el.querySelector('[data-f]').onsubmit = (e) => {
    e.preventDefault();
    const c = limpiar(el.querySelector('[data-c]').value);
    if (c.length === 6) location.hash = '#/tv/' + c; else toast('El código tiene 6 letras y números');
  };
  api('/api/auth/tv/cercanas').then((l) => {
    const box = el.querySelector('[data-cerca]');
    if (!box || !l?.length) return;
    box.innerHTML = `<h3 class="juntos-h">${icon('wifi', 'sm')} En tu misma red wifi</h3>${l.map((t) =>
      `<a class="tvv-cerca" href="#/tv/${esc(t.codigo)}">${icon('tv')}<span><b>${esc(t.nombre)}</b><small class="muted">Esperando para iniciar sesión</small></span>${icon('right', 'sm')}</a>`).join('')}`;
  }).catch(() => { });
}

/** Al abrir la app: si una TV de tu misma red espera para iniciar sesión, se ofrece hacerlo con un toque. */
let ultimaRevision = 0;
const avisadas = new Set();
export function vigilarTvCercanas() {
  const revisar = () => {
    if (document.hidden || !session.token || Date.now() - ultimaRevision < 20000) return;
    ultimaRevision = Date.now();
    api('/api/auth/tv/cercanas').then((l) => {
      const t = (l || []).find((x) => !avisadas.has(x.codigo));
      if (!t || location.hash.startsWith('#/tv')) return;
      avisadas.add(t.codigo);
      toast(`${t.nombre} está en tu wifi esperando para iniciar sesión`, { ms: 12000, action: { label: 'Iniciar sesión', fn: () => { location.hash = '#/tv/' + t.codigo; } } });
    }).catch(() => { });
  };
  setTimeout(revisar, 3000);
  document.addEventListener('visibilitychange', revisar);
  setInterval(revisar, 30000);
}
