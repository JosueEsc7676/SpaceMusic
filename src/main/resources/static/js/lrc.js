// Lector de letras LRC (compartido por la web y la TV)

/**
 * Convierte LRC en líneas: [{t, end, text, words?, interlude?}].
 * Soporta varias marcas por línea, [offset:], tiempos por palabra
 * (<mm:ss.xx>, formato "LRC mejorado") y detecta partes instrumentales.
 */
/** Líneas de créditos que algunos sitios ponen al inicio de la letra. */
const CREDITO = /^\s*(作词|作曲|编曲|制作人|作詞|編曲|製作人|词|曲|监制|混音|母带|和声|吉他|贝斯|鼓|录音|出品|发行|OP|SP|lyrics?( by)?|written by|writer|composed by|composer|music by|producers?|produced by|arranged by|arranger|mixed by|mastered by|letra|m[uú]sica|compositor(es)?|productor(es)?|arreglos?)\s*[:：]/i;
const esCredito = (t) => CREDITO.test(t);

export function parseLrc(lrc) {
  const T = (m, s, f) => parseInt(m, 10) * 60 + parseInt(s, 10) + (f ? parseInt(f, 10) / Math.pow(10, f.length) : 0);
  let offset = 0;
  const off = lrc.match(/\[offset:\s*([+-]?\d+)\s*]/i);
  if (off) offset = parseInt(off[1], 10) / 1000;
  const raw = [];
  for (const linea of lrc.split(/\r?\n/)) {
    const tags = [...linea.matchAll(/\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]/g)];
    if (!tags.length) continue;
    const cuerpo = linea.replace(/\[[^\]]*]/g, '');
    // Palabras con tiempo: <00:12.34>palabra
    const marcas = [...cuerpo.matchAll(/<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)/g)];
    const words = marcas.length >= 2
      ? marcas.map((m) => ({ t: T(m[1], m[2], m[3]) - offset, w: m[4] })).filter((x) => x.w.length)
      : null;
    const text = cuerpo.replace(/<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>/g, '').replace(/\s+/g, ' ').trim();
    if (esCredito(text)) continue; // "作词 : …", "Composed by: …", "Letra: …"

    for (const m of tags) raw.push({ t: T(m[1], m[2], m[3]) - offset, text, words });
  }
  raw.sort((a, b) => a.t - b.t);

  const out = [];
  const add = (l) => {
    const prev = out[out.length - 1];
    if (!l.text && prev && !prev.text) return; // vacías seguidas
    out.push(l);
  };
  if (raw.length && raw[0].t > 5) add({ t: 0, text: '', interlude: true });
  raw.forEach((l, i) => {
    const sig = raw[i + 1]?.t ?? l.t + 6;
    if (!l.text) { add({ t: l.t, text: '', interlude: true }); return; }
    add({ ...l, words: l.words || null });
    // Pausa larga sin letra: mostrar los puntos de interludio
    const dur = Math.min(sig - l.t, 3.5 + l.text.length * 0.09);
    if (sig - l.t > 9 && sig - (l.t + dur) > 4) add({ t: l.t + dur, text: '', interlude: true });
  });
  out.forEach((l, i) => { l.end = out[i + 1]?.t ?? l.t + 8; });
  return out;
}
