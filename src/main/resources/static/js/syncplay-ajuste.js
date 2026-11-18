// Corrección de la sincronía de SyncPlay (sin dependencias: se prueba con node).
//
// Antes, si el reproductor iba 0,6 s atrasado saltaba a la posición del SyncPlay; con una conexión lenta el salto
// tardaba en volver a sonar, quedaba otra vez atrasado y volvía a saltar: el audio se cortaba a cada rato.
// Ahora:
//  - Al saltar se apunta un poco más adelante («adelanto»), y ese adelanto se aprende midiendo cuánto tardó
//    de verdad en volver a sonar.
//  - Después de un salto se espera antes de volver a corregir; si los saltos se repiten, se espera cada vez más.
//  - Los desfases medianos se corrigen solo con la velocidad (0,95–1,05), que no corta el audio.

export const SALTO = 0.8;          // s: desde aquí se corrige con un salto
const SALTO_FORZADO = 0.5;         // s: con un estado nuevo del servidor (alguien movió, puso otra canción…)
const EMPEZAR = 0.06, PARAR = 0.02; // s: margen para empezar/dejar de ajustar la velocidad (sin titubeos)

const limitar = (v, min, max) => Math.min(max, Math.max(min, v));

export function crearAjuste(adelanto = 0.15) {
  return { adelanto, esperaHasta: 0, saltos: [], corrigiendo: false, midiendo: false };
}

/**
 * Qué hacer con el desfase actual (posición local − posición del SyncPlay; positivo: vamos adelantados).
 * `ahora` en ms. Solo debe llamarse mientras suena de verdad (no mientras carga).
 * Devuelve { velocidad, saltar? }: con `saltar`, ir a (posición del SyncPlay + saltar).
 */
export function decidir(st, desfase, ahora, { forzar = false, puedeAcelerar = true } = {}) {
  const a = Math.abs(desfase);
  if (ahora < st.esperaHasta && !(forzar && a > SALTO_FORZADO)) return { velocidad: 1 };
  if (st.midiendo) {
    // Primera medida tras un salto: lo que quedó de desfase es lo que tardó en volver a sonar
    st.midiendo = false;
    if (a < 4) st.adelanto = limitar(st.adelanto - desfase * 0.8, 0, 3);
  }
  if (a > SALTO || (forzar && a > SALTO_FORZADO)) {
    st.saltos = st.saltos.filter((t) => ahora - t < 60000);
    st.saltos.push(ahora);
    st.esperaHasta = ahora + Math.min(15000, 2500 * st.saltos.length);
    st.midiendo = true;
    st.corrigiendo = false;
    return { velocidad: 1, saltar: st.adelanto };
  }
  if (st.corrigiendo ? a < PARAR : a > EMPEZAR) st.corrigiendo = !st.corrigiendo;
  if (!st.corrigiendo) return { velocidad: 1 };
  let v = limitar(1 - desfase * 0.6, 0.95, 1.05);
  if (v > 1 && !puedeAcelerar) v = 1;
  return { velocidad: Math.round(v * 200) / 200 };
}
