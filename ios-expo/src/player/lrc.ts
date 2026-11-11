// Lector LRC equivalente a /js/lrc.js de la web (marcas múltiples, [offset:], créditos e interludios).
export type LyricLine = { t: number; end: number; text: string; interlude?: boolean };

const CREDITO = /^\s*(作词|作曲|编曲|制作人|作詞|編曲|製作人|词|曲|监制|混音|母带|和声|吉他|贝斯|鼓|录音|出品|发行|OP|SP|lyrics?( by)?|written by|writer|composed by|composer|music by|producers?|produced by|arranged by|arranger|mixed by|mastered by|letra|m[uú]sica|compositor(es)?|productor(es)?|arreglos?|(mixing |mastering |recording )?engineer|remix(ed by)?|remezcla)[一-鿿]{0,4}\s*[:：]/i;

export function parseLrc(lrc: string): LyricLine[] {
  const T = (m: string, s: string, f?: string) => parseInt(m, 10) * 60 + parseInt(s, 10) + (f ? parseInt(f, 10) / Math.pow(10, f.length) : 0);
  const off = lrc.match(/\[offset:\s*([+-]?\d+)\s*]/i);
  const offset = off ? parseInt(off[1], 10) / 1000 : 0;
  const raw: { t: number; text: string }[] = [];
  for (const linea of lrc.split(/\r?\n/)) {
    const tags = [...linea.matchAll(/\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]/g)];
    if (!tags.length) continue;
    const text = linea.replace(/\[[^\]]*]/g, '').replace(/<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>/g, '').replace(/\s+/g, ' ').trim();
    if (CREDITO.test(text)) continue;
    for (const m of tags) raw.push({ t: T(m[1], m[2], m[3]) - offset, text });
  }
  raw.sort((a, b) => a.t - b.t);
  const out: LyricLine[] = [];
  const add = (l: Omit<LyricLine, 'end'>) => {
    const prev = out[out.length - 1];
    if (!l.text && prev && !prev.text) return;
    out.push({ ...l, end: 0 });
  };
  if (raw.length && raw[0].t > 5) add({ t: 0, text: '', interlude: true });
  raw.forEach((l, i) => {
    const sig = raw[i + 1]?.t ?? l.t + 6;
    if (!l.text) { add({ t: l.t, text: '', interlude: true }); return; }
    add(l);
    const dur = Math.min(sig - l.t, 3.5 + l.text.length * 0.09);
    if (sig - l.t > 9 && sig - (l.t + dur) > 4) add({ t: l.t + dur, text: '', interlude: true });
  });
  out.forEach((l, i) => { l.end = out[i + 1]?.t ?? l.t + 8; });
  return out;
}

/** Índice de la línea activa (búsqueda binaria; la letra puede tener cientos de líneas). */
export function activeLine(lines: LyricLine[], time: number): number {
  let lo = 0, hi = lines.length - 1, found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].t <= time) { found = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return found;
}
