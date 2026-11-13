'use strict';
(async () => {
  const estado = document.getElementById('estado');
  try {
    const respuesta = await fetch('version-ios.json', { cache: 'no-store' });
    if (!respuesta.ok) throw new Error('versión no disponible');
    const version = await respuesta.json();
    const ipa = new URL(version.ipa, location.href);
    if (ipa.protocol !== 'https:' || ipa.username || ipa.password) throw new Error('dirección inválida');
    estado.textContent = `Versión ${version.nombre} · compilación ${version.code}`;
    document.getElementById('notas').textContent = version.notas || '';
    document.getElementById('huella').textContent = version.sha256 ? `SHA-256: ${version.sha256}` : '';
    for (const nombre of ['sidestore', 'altstore']) {
      const enlace = document.getElementById(nombre);
      enlace.href = `${nombre}://install?url=${encodeURIComponent(ipa.href)}`;
      enlace.hidden = false;
    }
  } catch {
    estado.textContent = 'No se pudo consultar la versión. Puedes reintentar o descargar el IPA con el botón de abajo.';
  }
})();
