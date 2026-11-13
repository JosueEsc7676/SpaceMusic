// Despertador: suena una playlist (o tus Me gusta) a la hora elegida y el volumen sube poco a poco.
// En la app de Android la alarma la programa el teléfono (suena aunque la app esté cerrada o la pantalla apagada).
// En el navegador solo suena si la página sigue abierta.
import { api } from './api.js';
import { esc, store, bus } from './util.js';
import { icon } from './icons.js';
import { player } from './player.js';
import { lib } from './state.js';
import { modal, toast, errorToast } from './ui.js';

const app = window.SpaceMusicApp;
const nativa = () => !!(app && app.programarAlarma);
const CLAVE = 'sm_alarma';
let reloj = null;

export const alarmaGuardada = () => store.get(CLAVE, null);

function proxima(hora) {
  const [h, m] = hora.split(':').map(Number);
  const d = new Date();
  d.setHours(h, m, 0, 0);
  if (d.getTime() <= Date.now() + 30000) d.setDate(d.getDate() + 1);
  return d.getTime();
}

const textoHora = (ms) => new Date(ms).toLocaleString('es', { weekday: 'long', hour: 'numeric', minute: '2-digit' });

function programar(a) {
  store.set(CLAVE, a);
  clearTimeout(reloj);
  if (nativa()) {
    try { app.programarAlarma(a.cuando, 'SpaceMusic: ' + (a.nombre || 'Despertador')); } catch { }
  } else {
    // Navegador: un temporizador mientras la página siga abierta (como mucho 24 h)
    reloj = setTimeout(() => sonar(), Math.max(0, a.cuando - Date.now()));
  }
  bus.emit('alarma');
}

export function cancelarAlarma() {
  store.set(CLAVE, null);
  clearTimeout(reloj);
  try { app?.cancelarAlarma?.(); } catch { }
  bus.emit('alarma');
}

async function cancionesDe(a) {
  if (a.fuente === 'favoritos') return api('/api/usuario/favoritos');
  const p = await api('/api/playlists/' + a.fuente);
  return p.canciones || [];
}

/** Suena la alarma: la lista elegida, con el volumen subiendo de casi nada al volumen guardado. */
export async function sonar() {
  const a = alarmaGuardada();
  if (!a) return;
  store.set(CLAVE, null); // una sola vez
  bus.emit('alarma');
  let lista = [];
  try { lista = await cancionesDe(a); } catch { }
  if (!lista.length) { toast('Despertador: la lista está vacía o no hay conexión', { error: true }); return; }
  if (a.aleatorio) lista = [...lista].sort(() => Math.random() - 0.5);
  const destino = player.volume > 0.05 ? player.volume : 0.7;
  player.setVolume(0.05);
  player.play(lista, 0, { type: 'alarma', label: 'Despertador' });
  const pasos = Math.max(1, (a.subida || 2) * 60);
  let n = 0;
  const subir = setInterval(() => {
    n++;
    player.setVolume(0.05 + (destino - 0.05) * Math.min(1, n / pasos));
    if (n >= pasos) clearInterval(subir);
  }, 1000);
  const m = modal({
    title: 'Buenos días',
    body: `<p style="font-size:18px">${icon('sun')} Son las ${new Date().toLocaleTimeString('es', { hour: 'numeric', minute: '2-digit' })}</p>
      <p class="muted">Suena: ${esc(a.nombre || 'tu música')}</p>`,
    actions: [{ label: '10 minutos más', value: 'pos' }, { label: 'Detener', value: 'stop', primary: true }],
  });
  const r = await m;
  clearInterval(subir);
  if (r === 'pos') {
    player.pause();
    player.setVolume(destino);
    programar({ ...a, cuando: Date.now() + 10 * 60000 });
    toast('Sonará otra vez en 10 minutos');
  } else if (r === 'stop') {
    player.pause();
    player.setVolume(destino);
  } else {
    player.setVolume(destino); // se cerró la ventana: sigue sonando al volumen normal
  }
}

/** Ventana para programar o cambiar el despertador. */
export function abrirDespertador() {
  const a = alarmaGuardada();
  const listas = [{ id: 'favoritos', nombre: 'Tus Me gusta' }, ...lib.playlists.map((p) => ({ id: String(p.id), nombre: p.nombre }))];
  const hora = a ? new Date(a.cuando).toTimeString().slice(0, 5) : '07:00';
  return modal({
    title: 'Despertador',
    body: `${a ? `<p class="ded-programada" style="margin-top:0">${icon('clock', 'sm')}<span>Sonará el <b>${esc(textoHora(a.cuando))}</b> con ${esc(a.nombre)}.</span></p>` : ''}
      <label class="field"><span>Hora</span><input type="time" data-hora value="${hora}" style="font-size:28px;padding:8px 12px"></label>
      <label class="field"><span>Música</span><select data-fuente>${listas.map((l) => `<option value="${esc(l.id)}" ${a?.fuente === l.id ? 'selected' : ''}>${esc(l.nombre)}</option>`).join('')}</select></label>
      <label class="field"><span>El volumen sube durante</span><select data-subida>${[1, 2, 5, 10].map((n) => `<option value="${n}" ${(a?.subida || 2) === n ? 'selected' : ''}>${n} ${n === 1 ? 'minuto' : 'minutos'}</option>`).join('')}</select></label>
      <label class="row" style="gap:8px;margin-top:8px"><input type="checkbox" data-aleatorio ${a?.aleatorio !== false ? 'checked' : ''}> En orden aleatorio</label>
      <p class="muted" style="font-size:13px;margin-top:12px">${nativa()
        ? 'Suena aunque la app esté cerrada o la pantalla apagada. Deja el volumen del teléfono alto.'
        : 'En el navegador solo suena si esta página sigue abierta. En la app de Android suena aunque esté cerrada.'}</p>`,
    actions: [a ? { label: 'Quitar despertador', value: 'quitar' } : { label: 'Cancelar', value: null }, { label: 'Guardar', value: 'ok', primary: true }],
    onAction: (v, m) => {
      if (v === 'quitar') { cancelarAlarma(); toast('Despertador quitado'); return; }
      if (v !== 'ok') return;
      const hora = m.querySelector('[data-hora]').value;
      if (!/^\d{2}:\d{2}$/.test(hora)) { toast('Elige una hora', { error: true }); return false; }
      const sel = m.querySelector('[data-fuente]');
      const nueva = {
        cuando: proxima(hora), fuente: sel.value, nombre: sel.options[sel.selectedIndex].text,
        subida: Number(m.querySelector('[data-subida]').value), aleatorio: m.querySelector('[data-aleatorio]').checked,
      };
      programar(nueva);
      toast('Despertador: ' + textoHora(nueva.cuando));
    },
  }).catch(errorToast);
}

/** Al abrir la app: la nativa avisa con smAlarma(); en el navegador se vuelve a poner el temporizador. */
export function iniciarAlarma() {
  window.smAlarma = () => { sonar().catch(() => { }); };
  const a = alarmaGuardada();
  if (!a) return;
  if (a.cuando < Date.now() - 30 * 60000) { store.set(CLAVE, null); return; } // vieja: ya pasó
  if (nativa()) return; // la tiene el teléfono
  reloj = setTimeout(() => sonar(), Math.max(0, a.cuando - Date.now()));
}
