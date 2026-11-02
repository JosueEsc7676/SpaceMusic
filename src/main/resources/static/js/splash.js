// Pantalla de apertura: se oculta cuando la app ya pintó y la animación del logo terminó
let oculto = false;

/** Reemplaza el contenido del body sin quitar la pantalla de apertura (así su animación no se reinicia). */
export function pintarCuerpo(html) {
  for (const n of [...document.body.children]) if (n.id !== 'splash') n.remove();
  document.body.insertAdjacentHTML('afterbegin', html);
}

export function ocultarSplash() {
  const sp = document.getElementById('splash');
  if (!sp || oculto) return;
  oculto = true;
  const salir = () => { sp.classList.add('out'); setTimeout(() => sp.remove(), 600); };
  let anims = [];
  try { anims = sp.getAnimations({ subtree: true }).filter((a) => a.animationName !== 'spAuto'); } catch { }
  if (!anims.length) { setTimeout(salir, Math.max(0, 1700 - performance.now())); return; }
  // Espera a que el logo termine de dibujarse (con un tope por si algo se queda colgado)
  Promise.race([Promise.all(anims.map((a) => a.finished.catch(() => { }))), new Promise((r) => setTimeout(r, 3000))])
    .then(() => setTimeout(salir, 200));
}
