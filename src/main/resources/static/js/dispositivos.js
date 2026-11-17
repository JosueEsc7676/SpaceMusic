// «Dispositivos»: dónde suena la música, tus otros dispositivos (control remoto) y acceso a SyncPlay.
// Cada plataforma ofrece solo lo que de verdad permite:
//  - app Android: el selector de salida de medios del sistema (puente SpaceMusicApp);
//  - navegador: selectAudioOutput / setSinkId donde existan, y transmitir (Remote Playback) si el navegador lo ofrece;
//  - en el resto, la salida la elige el sistema (Bluetooth, audífonos…) y aquí se explica cómo.
import { esc, bus } from './util.js';
import { icon } from './icons.js';
import { modal, toast, errorToast } from './ui.js';
import { player, fijarSalida } from './player.js';

const app = window.SpaceMusicApp;
const ICONO = { telefono: 'speaker', audifonos: 'headphones', bluetooth: 'headphones', remota: 'devices', navegador: 'speaker' };

function salidaNativa() {
  try { return JSON.parse(app.salidaAudio()); } catch { return null; }
}

/** Salidas que el navegador deja ver (sin pedir permisos de micrófono: si no hay nombres, no se listan). */
async function salidasNavegador() {
  if (typeof player.audio.setSinkId !== 'function' || !navigator.mediaDevices?.enumerateDevices) return [];
  try {
    const l = await navigator.mediaDevices.enumerateDevices();
    return l.filter((d) => d.kind === 'audiooutput' && d.label);
  } catch { return []; }
}

function filaSalida(nombre, tipo, extra = '') {
  return `<div class="disp-actual">${icon(ICONO[tipo] || 'speaker')}<span class="ellipsis"><b>${esc(nombre)}</b>${extra ? `<small class="muted">${extra}</small>` : ''}</span></div>`;
}

async function htmlSalida() {
  if (app?.salidaAudio) {
    const s = salidaNativa() || { nombre: 'Este teléfono', tipo: 'telefono' };
    return `${filaSalida(s.nombre, s.tipo, 'Salida actual')}
      ${app.elegirSalida ? `<button class="btn primary disp-btn" data-elegir-nativo>${icon('devices', 'sm')} Cambiar dispositivo</button>
      <p class="muted disp-nota">Teléfono, audífonos, Bluetooth y transmisión: los muestra Android.</p>` : ''}`;
  }
  const lista = await salidasNavegador();
  const actual = player.audio.sinkId || '';
  const nombreActual = lista.find((d) => d.deviceId === actual)?.label || (actual ? 'Otra salida' : 'Salida predeterminada del sistema');
  let html = filaSalida(nombreActual, 'navegador', 'Salida actual');
  if (lista.length > 1) {
    html += `<div class="option-list disp-lista" role="listbox" aria-label="Salidas disponibles">${lista.map((d) => `<button class="option ${d.deviceId === actual || (!actual && d.deviceId === 'default') ? 'sel' : ''}" data-sink="${esc(d.deviceId)}" role="option">
      <div class="ph">${icon('speaker')}</div><div class="t ellipsis">${esc(d.label)}</div></button>`).join('')}</div>`;
  }
  if (navigator.mediaDevices?.selectAudioOutput) html += `<button class="btn disp-btn" data-elegir-web>${icon('devices', 'sm')} Elegir otra salida</button>`;
  if (player.audio.remote && !app) html += `<button class="btn disp-btn" data-remota>${icon('devices', 'sm')} Transmitir a otro dispositivo</button>`;
  if (!lista.length && !navigator.mediaDevices?.selectAudioOutput) {
    html += `<p class="muted disp-nota">Este navegador usa la salida del sistema. Para cambiar a audífonos o Bluetooth, elígelos en los ajustes de sonido del equipo${player.audio.remote ? ' o transmite' : ''}.</p>`;
  }
  return html;
}

async function htmlSyncPlay() {
  const j = await import('./juntos.js');
  const sala = j.salaActiva();
  return `<button class="disp-sync" data-ir="${sala ? '#/juntos/' + sala.id : '#/juntos'}">
      <span class="disp-sync-ico">${icon('headphones')}</span>
      <span class="ellipsis"><b>SyncPlay</b><small class="muted">${sala ? (sala.anfitrion ? 'Tu SyncPlay está activo' : 'Estás en un SyncPlay') : 'Inicia SyncPlay o únete con tus amigos'}</small></span>${icon('right', 'sm')}</button>
    <div class="row wrap disp-sync-acc">
      ${!sala || sala.anfitrion ? `<button class="btn sm" data-sync-crear>${icon('plus', 'sm')} ${sala ? 'Abrir mi SyncPlay' : 'Iniciar SyncPlay'}</button>
      <button class="btn sm" data-sync-invitar>${icon('send', 'sm')} Invitar a un amigo</button>` : ''}
      ${sala ? `<button class="btn sm" data-sync-compartir>${icon('share', 'sm')} Compartir enlace</button>` : ''}
      <a class="btn sm ghost" href="#/juntos" data-cerrar-ir>Mis SyncPlay</a></div>`;
}

export async function abrirDispositivos() {
  let quitarAviso = null, quitarLista = null;
  await modal({
    title: 'Dispositivos',
    body: `<div class="disp">
      <h4 class="disp-h">Escuchando en</h4><div data-salida><p class="muted">Buscando…</p></div>
      <h4 class="disp-h disp-h-fila">Tus otros dispositivos <button class="btn sm ghost" data-renombrar title="Cambiar el nombre de este dispositivo">Este: <span data-mi-nombre></span></button></h4>
      <div data-otros><p class="muted">Buscando…</p></div>
      <h4 class="disp-h">Escuchar en compañía</h4><div data-sync><p class="muted">Cargando…</p></div></div>`,
    actions: [{ label: 'Listo', value: null }],
    onOpen: async (m, cerrar) => {
      const pintarSalida = async () => { m.querySelector('[data-salida]').innerHTML = await htmlSalida(); };
      // Android avisa cuando cambia la salida (se conectan audífonos, Bluetooth…)
      if (app?.salidaAudio) { window.smSalida = () => { if (m.isConnected) pintarSalida(); }; quitarAviso = () => { window.smSalida = null; }; }
      const remoto = await import('./remoto.js');
      const pintarOtros = (l) => {
        const box = m.querySelector('[data-otros]');
        if (box) box.innerHTML = remoto.htmlOtros(l.filter((d) => d.id !== remoto.miDispositivo()));
        const n = m.querySelector('[data-mi-nombre]');
        if (n) n.textContent = remoto.miNombre();
      };
      quitarLista = bus.on('remoto:lista', (l) => { if (m.isConnected) pintarOtros(l); });
      m.addEventListener('click', async (e) => {
        const b = e.target.closest('button, a');
        if (!b) return;
        try {
          if (b.matches('[data-remoto]')) {
            cerrar(null);
            // Como Spotify Connect: si aquí suena música, se pasa a ese dispositivo y sigues controlándola desde aquí
            const d = remoto.otrosDispositivos().find((x) => x.id === b.dataset.remoto);
            if (d && player.playing) await remoto.pasarA(d);
            else remoto.controlar(b.dataset.remoto);
          } else if (b.matches('[data-renombrar]')) {
            await remoto.renombrar();
            pintarOtros(await remoto.cargarDispositivos());
          } else if (b.matches('[data-elegir-nativo]')) {
            const r = app.elegirSalida();
            if (!r) toast('Este Android no tiene selector de salida: conecta los audífonos o el Bluetooth desde Ajustes', { ms: 5000 });
            setTimeout(pintarSalida, 1500);
          } else if (b.matches('[data-elegir-web]')) {
            const d = await navigator.mediaDevices.selectAudioOutput();
            await fijarSalida(d.deviceId);
            toast('Sonando en ' + (d.label || 'la salida elegida'));
            pintarSalida();
          } else if (b.matches('[data-sink]')) {
            await fijarSalida(b.dataset.sink === 'default' ? '' : b.dataset.sink);
            pintarSalida();
          } else if (b.matches('[data-remota]')) {
            await player.audio.remote.prompt();
          } else if (b.matches('[data-ir]')) {
            cerrar(null); location.hash = b.dataset.ir;
          } else if (b.matches('[data-cerrar-ir]')) {
            cerrar(null);
          } else if (b.matches('[data-sync-crear]')) {
            const j = await import('./juntos.js');
            const d = await j.crearSala();
            cerrar(null); location.hash = '#/juntos/' + d.id;
          } else if (b.matches('[data-sync-invitar]')) {
            cerrar(null);
            (await import('./juntos.js')).invitarAmigo();
          } else if (b.matches('[data-sync-compartir]')) {
            const j = await import('./juntos.js');
            j.compartirEnlace(j.salaActiva()?.id);
          }
        } catch (err) {
          if (err?.name === 'NotAllowedError' || err?.name === 'AbortError') return; // el usuario canceló
          if (err?.name === 'NotSupportedError') { toast('No hay otro dispositivo disponible para transmitir'); return; }
          errorToast(err);
        }
      });
      await Promise.all([pintarSalida(), htmlSyncPlay().then((h) => { m.querySelector('[data-sync]').innerHTML = h; }),
        remoto.cargarDispositivos().then(pintarOtros)]);
    },
  });
  quitarAviso?.();
  quitarLista?.();
}
