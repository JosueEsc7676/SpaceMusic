// Enlaces de invitación a SyncPlay: https://servidor/#/juntos/ID
// Un enlace recibido en un mensaje solo se abre si es de ESTE servidor (el de la cuenta):
// mismo esquema, host y puerto, sin usuario ni contraseña, ruta raíz y fragmento exacto.
// El servidor sigue comprobando que la persona pertenezca a ese SyncPlay.

/** Enlace para compartir el SyncPlay `id` (null si el id no es válido). */
export function enlaceSala(id, origen = location.origin) {
  const n = Number(id);
  if (!Number.isInteger(n) || n <= 0) return null;
  try { return new URL('/#/juntos/' + n, origen).href; } catch { return null; }
}

/** Id del SyncPlay al que invita `texto`, o null si no contiene una invitación válida de este servidor. */
export function salaDeInvitacion(texto, origen = location.origin) {
  if (!texto || typeof texto !== 'string') return null;
  let base;
  try { base = new URL(origen); } catch { return null; }
  for (const m of texto.matchAll(/https?:\/\/[^\s<>"']+/g)) {
    const candidato = m[0].replace(/[.,;!)]+$/, '');
    let u;
    try { u = new URL(candidato); } catch { continue; }
    if (u.protocol !== base.protocol || u.host.toLowerCase() !== base.host.toLowerCase()) continue;
    if (u.username || u.password || (u.pathname !== '/' && u.pathname !== '') || u.search) continue;
    const r = /^#\/juntos\/([1-9]\d{0,9})$/.exec(u.hash);
    if (r) return Number(r[1]);
  }
  return null;
}

/** Texto del mensaje de invitación (incluye el enlace). */
export const textoInvitacion = (id) => `Te invito a escuchar conmigo en SyncPlay\n${enlaceSala(id)}`;
