// Pantalla de apertura: logo animado + sonido de inicio.
// Se oculta en cuanto la app está lista y la animación terminó. Si la app ya se abrió en
// esta sesión (por ejemplo al recargar), se salta la animación para que cargue al instante.
//
// Sonido de inicio: coloca el archivo en static/sonidos/inicio.mp3 (se reproduce solo si existe).
const SONIDO = '/sonidos/inicio.mp3';
let oculto = false;
let repetida = false;
try {
  repetida = sessionStorage.getItem('sm_splash') === '1';
  sessionStorage.setItem('sm_splash', '1');
} catch { }

const sp = document.getElementById('splash');
if (sp && repetida) sp.classList.add('quick');
if (sp && !repetida) sonarInicio();

function sonarInicio() {
  try {
    if (localStorage.getItem('sm_intro_sound') === 'false') return;
    const a = new Audio(SONIDO);
    a.volume = 0.75;
    // Los navegadores bloquean el sonido hasta el primer toque: si pasa, suena con el primer
    // toque o tecla de los primeros segundos (después ya no tendría sentido)
    a.play().catch(() => {
      const desde = performance.now();
      const eventos = ['pointerdown', 'keydown', 'touchstart'];
      const quitar = () => eventos.forEach((e) => window.removeEventListener(e, alTocar, true));
      function alTocar() {
        quitar();
        if (performance.now() - desde < 6000) a.play().catch(() => { });
      }
      eventos.forEach((e) => window.addEventListener(e, alTocar, true));
      setTimeout(quitar, 6000);
    });
  } catch { }
}

/** Reemplaza el contenido del body sin quitar la pantalla de apertura (así su animación no se reinicia). */
export function pintarCuerpo(html) {
  for (const n of [...document.body.children]) if (n.id !== 'splash') n.remove();
  document.body.insertAdjacentHTML('afterbegin', html);
}

export function ocultarSplash() {
  const el = document.getElementById('splash');
  if (!el || oculto) return;
  oculto = true;
  const salir = () => { el.classList.add('out'); setTimeout(() => el.remove(), 500); };
  if (repetida) { salir(); return; }
  let anims = [];
  try { anims = el.getAnimations({ subtree: true }).filter((a) => a.animationName !== 'spAuto' && a.animationName !== 'spGlow'); } catch { }
  if (!anims.length) { setTimeout(salir, Math.max(0, 1200 - performance.now())); return; }
  // Espera a que el logo termine de dibujarse (con un tope por si algo se queda colgado)
  Promise.race([Promise.all(anims.map((a) => a.finished.catch(() => { }))), new Promise((r) => setTimeout(r, 2200))])
    .then(() => setTimeout(salir, 120));
}
