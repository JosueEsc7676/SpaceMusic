// Código QR propio (sin bibliotecas): modo byte, corrección de errores M, versiones 1 a 10 (hasta ~210 caracteres).
// Basado en el estándar ISO/IEC 18004. Uso: qrSvg('https://…') → '<svg …>'.

// [códigos de corrección por bloque, [[bloques, datos por bloque], …]] para el nivel M
const TABLA_M = [null,
  [10, [[1, 16]]], [16, [[1, 28]]], [26, [[1, 44]]], [18, [[2, 32]]], [24, [[2, 43]]],
  [16, [[4, 27]]], [18, [[4, 31]]], [22, [[2, 38], [2, 39]]], [22, [[3, 36], [2, 37]]], [26, [[4, 43], [1, 44]]]];
const ALINEACION = [null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50]];

const datosDe = (v) => TABLA_M[v][1].reduce((s, [n, d]) => s + n * d, 0);

function mul(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11d); z ^= ((y >>> i) & 1) * x; }
  return z;
}
function divisor(grado) {
  const r = new Array(grado - 1).fill(0).concat([1]);
  let raiz = 1;
  for (let i = 0; i < grado; i++) {
    for (let j = 0; j < r.length; j++) { r[j] = mul(r[j], raiz); if (j + 1 < r.length) r[j] ^= r[j + 1]; }
    raiz = mul(raiz, 2);
  }
  return r;
}
function resto(datos, div) {
  const r = div.map(() => 0);
  for (const b of datos) {
    const f = b ^ r.shift();
    r.push(0);
    div.forEach((c, i) => { r[i] ^= mul(c, f); });
  }
  return r;
}

/** Matriz del QR (true = oscuro) para un texto. */
export function qrMatriz(texto) {
  const bytes = [...new TextEncoder().encode(texto)];
  let v = 1;
  while (v <= 10 && Math.ceil((4 + (v < 10 ? 8 : 16) + bytes.length * 8) / 8) > datosDe(v)) v++;
  if (v > 10) throw new Error('Texto demasiado largo para el QR');

  // Bits: modo byte, largo, datos, fin y relleno
  const bits = [];
  const poner = (val, n) => { for (let i = n - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
  poner(4, 4);
  poner(bytes.length, v < 10 ? 8 : 16);
  bytes.forEach((b) => poner(b, 8));
  const capacidad = datosDe(v) * 8;
  poner(0, Math.min(4, capacidad - bits.length));
  while (bits.length % 8) bits.push(0);
  for (let p = 0xec; bits.length < capacidad; p ^= 0xec ^ 0x11) poner(p, 8);
  const cw = [];
  for (let i = 0; i < bits.length; i += 8) cw.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0));

  // Bloques con su corrección y entrelazado
  const [ec, grupos] = TABLA_M[v];
  const div = divisor(ec);
  const bloques = [];
  let k = 0;
  for (const [n, d] of grupos) for (let i = 0; i < n; i++) { const dat = cw.slice(k, k + d); k += d; bloques.push({ dat, ec: resto(dat, div) }); }
  const final = [];
  const maxDat = Math.max(...bloques.map((b) => b.dat.length));
  for (let i = 0; i < maxDat; i++) bloques.forEach((b) => { if (i < b.dat.length) final.push(b.dat[i]); });
  for (let i = 0; i < ec; i++) bloques.forEach((b) => final.push(b.ec[i]));

  // Matriz y patrones fijos
  const n = 17 + 4 * v;
  const m = Array.from({ length: n }, () => new Array(n).fill(false));
  const fija = Array.from({ length: n }, () => new Array(n).fill(false));
  const set = (x, y, oscuro) => { m[y][x] = oscuro; fija[y][x] = true; };
  for (let i = 0; i < n; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
  const buscador = (cx, cy) => {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
      if (x >= 0 && x < n && y >= 0 && y < n) set(x, y, d !== 2 && d !== 4);
    }
  };
  buscador(3, 3); buscador(n - 4, 3); buscador(3, n - 4);
  const al = ALINEACION[v], u = al.length - 1;
  al.forEach((a, i) => al.forEach((b, j) => {
    if ((i === 0 && j === 0) || (i === 0 && j === u) || (i === u && j === 0)) return;
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(a + dx, b + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }));
  const formato = (mascara) => {
    const dato = (0 << 3) | mascara; // nivel M = 00
    let r = dato;
    for (let i = 0; i < 10; i++) r = (r << 1) ^ ((r >>> 9) * 0x537);
    const b = ((dato << 10) | r) ^ 0x5412;
    const bit = (i) => ((b >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) set(8, i, bit(i));
    set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) set(n - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) set(8, n - 15 + i, bit(i));
    set(8, n - 8, true);
  };
  formato(0);
  if (v >= 7) {
    let r = v;
    for (let i = 0; i < 12; i++) r = (r << 1) ^ ((r >>> 11) * 0x1f25);
    const b = (v << 12) | r;
    for (let i = 0; i < 18; i++) {
      const o = ((b >>> i) & 1) === 1, a = n - 11 + (i % 3), c = Math.floor(i / 3);
      set(a, c, o); set(c, a, o);
    }
  }

  // Datos en zigzag desde la esquina inferior derecha
  let i = 0;
  for (let der = n - 1; der >= 1; der -= 2) {
    if (der === 6) der = 5;
    for (let vert = 0; vert < n; vert++) for (let j = 0; j < 2; j++) {
      const x = der - j, sube = ((der + 1) & 2) === 0, y = sube ? n - 1 - vert : vert;
      if (!fija[y][x] && i < final.length * 8) { m[y][x] = ((final[i >>> 3] >>> (7 - (i & 7))) & 1) === 1; i++; }
    }
  }

  // Máscara con menos penalización
  const MASCARAS = [
    (x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0, (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0, (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0];
  const aplicar = (f) => { for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (!fija[y][x] && f(x, y)) m[y][x] = !m[y][x]; };
  const penalizacion = () => {
    let p = 0, oscuros = 0;
    for (let y = 0; y < n; y++) {
      let cf = 1, cc = 1;
      for (let x = 0; x < n; x++) {
        if (m[y][x]) oscuros++;
        if (x > 0) {
          if (m[y][x] === m[y][x - 1]) { cf++; if (cf === 5) p += 3; else if (cf > 5) p++; } else cf = 1;
          if (m[x][y] === m[x - 1][y]) { cc++; if (cc === 5) p += 3; else if (cc > 5) p++; } else cc = 1;
        }
        if (x > 0 && y > 0 && m[y][x] === m[y - 1][x] && m[y][x] === m[y][x - 1] && m[y][x] === m[y - 1][x - 1]) p += 3;
      }
    }
    return p + Math.floor(Math.abs(oscuros * 20 - n * n * 10) / (n * n)) * 10;
  };
  let mejor = 0, mejorP = Infinity;
  for (let k2 = 0; k2 < 8; k2++) {
    aplicar(MASCARAS[k2]); formato(k2);
    const p = penalizacion();
    if (p < mejorP) { mejorP = p; mejor = k2; }
    aplicar(MASCARAS[k2]);
  }
  aplicar(MASCARAS[mejor]); formato(mejor);
  return m;
}

/** El QR como SVG (negro sobre blanco, con margen de 4 módulos). */
export function qrSvg(texto, { margen = 4 } = {}) {
  const m = qrMatriz(texto), n = m.length, t = n + margen * 2;
  let d = '';
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (m[y][x]) d += `M${x + margen} ${y + margen}h1v1h-1z`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${t} ${t}" shape-rendering="crispEdges"><rect width="${t}" height="${t}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}
