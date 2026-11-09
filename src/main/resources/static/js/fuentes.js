// Tipografías de Google Fonts cargadas sin bloquear la página.
// Como <link> en el HTML, una hoja de estilos externa pendiente detiene el código de la app:
// sin internet la petición se quedaba colgada y la app se veía en negro.
const URL_FUENTES = 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&family=Manrope:wght@600;700;800&display=swap';
if (navigator.onLine !== false && !document.querySelector('link[data-fuentes]')) {
  const l = document.createElement('link');
  l.rel = 'stylesheet';
  l.href = URL_FUENTES;
  l.dataset.fuentes = '';
  document.head.appendChild(l);
}
