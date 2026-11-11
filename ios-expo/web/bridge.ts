// Se ejecuta dentro de la interfaz web móvil. El DOM y las pantallas siguen siendo los originales.
const w = window as any;
const send = (type: string, body: Record<string, unknown> = {}) =>
  w.ReactNativeWebView?.postMessage(JSON.stringify({ version: 1, type, ...body }));

function session() {
  try {
    const token = localStorage.getItem('sm_token');
    const user = JSON.parse(localStorage.getItem('sm_user') ?? 'null');
    send('session', { session: token && user ? { token, user } : null });
  } catch { /* El inicio de sesión de la web mostrará su propio error. */ }
}
session();
const originalSet = Storage.prototype.setItem;
const originalRemove = Storage.prototype.removeItem;
Storage.prototype.setItem = function (key, value) {
  originalSet.call(this, key, value);
  if (key === 'sm_user' || key === 'sm_token') queueMicrotask(session);
};
Storage.prototype.removeItem = function (key) {
  originalRemove.call(this, key);
  if (key === 'sm_user' || key === 'sm_token') queueMicrotask(session);
};

async function connect() {
  if (/\/(login|register|forgot-password|reset-password|verify-email)\.html$/.test(location.pathname)) {
    send('ready'); return;
  }
  const [{ player, sleep, prefs }, { bus }, offline] = await Promise.all([
    import(/* @vite-ignore */ `${location.origin}/js/player.js`),
    import(/* @vite-ignore */ `${location.origin}/js/util.js`),
    import(/* @vite-ignore */ `${location.origin}/js/offline.js`),
  ]);
  if (w.__smBridgeConnected) return;
  w.__smBridgeConnected = true;
  const audio: HTMLAudioElement = player.audio;
  const initial = { playing: player.playing, time: audio.currentTime || 0, duration: audio.duration || 0 };
  audio.pause(); audio.removeAttribute('src'); audio.load();
  const shadow = { ...initial, volume: player.muted ? 0 : player.volume ** 2, ended: false, src: '' };
  let nativeUpdate = false;
  let lastSave = 0;
  let clientRevision = 0;
  let lastSignature = '';
  const snapshot = (intent: 'sync' | 'load' | 'restore' = 'sync') => {
    if (nativeUpdate || !player.current) return;
    const signature = JSON.stringify([player.items, player.repeat, shadow.volume, player.context, sleep.until, sleep.endOfSong, prefs.autoRadio]);
    if (intent === 'sync' && signature === lastSignature) return;
    lastSignature = signature;
    if (intent !== 'restore') clientRevision++;
    send('queue', {
      intent,
      state: {
        clientRevision,
        items: player.items, index: player.index, time: Math.max(0, shadow.time), playing: shadow.playing,
        repeat: player.repeat, volume: Math.max(0, Math.min(1, shadow.volume)), context: player.context,
        sleepUntil: Number(sleep.until) || 0, sleepEnd: !!sleep.endOfSong, autoRadio: !!prefs.autoRadio,
      },
    });
  };
  Object.defineProperties(audio, {
    paused: { configurable: true, get: () => !shadow.playing },
    ended: { configurable: true, get: () => shadow.ended },
    duration: { configurable: true, get: () => shadow.duration },
    currentTime: { configurable: true, get: () => shadow.time, set: value => {
      shadow.time = Math.max(0, Number(value) || 0); send('seek', { time: shadow.time }); bus.emit('player:time');
    } },
    volume: { configurable: true, get: () => shadow.volume, set: value => {
      shadow.volume = Math.max(0, Math.min(1, Number(value) || 0)); send('volume', { volume: shadow.volume });
    } },
    src: { configurable: true, get: () => shadow.src, set: value => { shadow.src = String(value); } },
    buffered: { configurable: true, get: () => ({ length: shadow.duration > 0 ? 1 : 0, start: () => 0, end: () => shadow.time }) },
  });
  audio.play = async () => {
    shadow.playing = true; shadow.ended = false;
    send('play'); bus.emit('player:state');
  };
  audio.pause = () => { shadow.playing = false; send('pause'); bus.emit('player:state'); };
  audio.load = () => {};

  player._load = function (index: number, autoplay: boolean, startAt = 0) {
    this._finishListen(); this.index = index;
    const song = this.current; if (!song) return;
    this._listened = 0; this._scrobbled = false; this._lastTick = 0;
    this.buffering = autoplay;
    shadow.playing = autoplay; shadow.time = Math.max(0, startAt); shadow.duration = song.duracionSegundos || 0; shadow.ended = false;
    shadow.src = `${location.origin}/api/canciones/${song.id}/audio`;
    snapshot('load');
    bus.emit('player:song', song); bus.emit('player:queue'); bus.emit('player:state');
    this._save();
  };
  // La cola, radio y registro de escuchas los gestiona el motor nativo, incluso sin WebView activa.
  player._tick = () => {};
  player._extendWithRadio = async () => {};
  for (const event of ['player:queue', 'player:mode', 'prefs', 'sleep']) bus.on(event, () => snapshot());

  w.__smNativeStatus = (s: any) => {
    if ((s.clientRevision ?? 0) < clientRevision) return;
    clientRevision = s.clientRevision ?? 0;
    const nativeItems = s.items ?? player.items;
    if (!nativeItems[s.currentIndex]) return;
    nativeUpdate = true;
    try {
      const previous = player.current;
      const changedQueue = JSON.stringify(player.items.map((x: any) => [x.id, x._uid])) !== JSON.stringify(nativeItems.map((x: any) => [x.id, x._uid]));
      if (player.original) {
        const known = new Set(player.original.map((item: any) => item._uid));
        for (const item of nativeItems) if (!known.has(item._uid)) player.original.push(item);
      }
      player.items = nativeItems; player.index = s.currentIndex;
      shadow.time = s.currentTime || 0; shadow.duration = s.duration || player.current?.duracionSegundos || 0;
      shadow.playing = !!s.playing || (!!s.isBuffering && !!s.intendedPlaying);
      shadow.ended = !!s.didJustFinish && s.currentIndex === nativeItems.length - 1 && player.repeat === 'off';
      player.buffering = !!s.isBuffering;
      sleep.until = s.sleepUntil; sleep.endOfSong = s.sleepEnd;
      if (previous?._uid !== player.current?._uid || previous?.id !== player.current?.id) {
        bus.emit('player:song', player.current);
        bus.emit('player:queue');
      } else if (changedQueue) bus.emit('player:queue');
      bus.emit('player:state'); bus.emit('player:time'); bus.emit('sleep');
      if (Date.now() - lastSave > 5000) { lastSave = Date.now(); player._save(); }
    } finally { nativeUpdate = false; }
  };
  w.__smNativeError = (message: string) => bus.emit('toast', { msg: message, error: true });
  w.__smRestoreQueue = (saved: any) => {
    if (!saved?.items?.length || player.current) return;
    player.items = saved.items; player.context = saved.context; player.repeat = saved.repeat;
    player._load(saved.index, false, saved.time || 0);
  };
  const songFor = (id: number) => {
    const current = player.items.find((x: any) => x.id === id);
    if (current) return current;
    try {
      const user = JSON.parse(localStorage.getItem('sm_user') ?? 'null');
      return JSON.parse(localStorage.getItem(`sm_descargadas:${user?.id}`) ?? '{}')[id];
    } catch { return null; }
  };

  // El almacenamiento nativo sustituye al service worker para audio en WKWebView.
  const worker = w.__smWorker ?? new EventTarget() as any;
  const service = w.__smService ?? new EventTarget() as any;
  worker.postMessage = (m: any) => {
    if (m?.tipo === 'descargar-audio' || m?.tipo === 'guardar-audio') {
      setTimeout(() => {
        const id = Number(m.id || String(m.url).match(/canciones\/(\d+)/)?.[1]);
        const song = songFor(id);
        if (song) send('download', { url: new URL(m.url, location.origin).href, song, exportFile: false });
      }, 0);
    } else if (m?.tipo === 'quitar-descarga') send('removeDownload', { id: Number(m.id) });
    else if (m?.tipo === 'limpiar-usuario') send('clearDownloads');
    else if (m?.tipo === 'estado-offline') service.dispatchEvent(new MessageEvent('message', { data: { tipo: 'estado-offline', canciones: w.__smDownloadedCount || 0 } }));
  };
  service.controller = worker;
  service.ready = Promise.resolve({ active: worker });
  service.register = async () => ({ active: worker, waiting: null, update: async () => {}, addEventListener: () => {} });
  try { Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: service }); } catch { /* Se conserva el SW real si WebKit lo exige. */ }
  for (const pending of w.__smWorkerPending ?? []) worker.postMessage(pending);
  w.__smWorkerPending = [];
  w.__smDownloadResult = (id: number, ok: boolean, count: number) => {
    w.__smDownloadedCount = count;
    service.dispatchEvent(new MessageEvent('message', { data: { tipo: 'descarga', id, ok } }));
    bus.emit('descargas');
  };
  w.__smSetDownloads = (downloads: any[]) => {
    try {
      const user = JSON.parse(localStorage.getItem('sm_user') ?? 'null');
      if (!user?.id) return;
      const values = Object.fromEntries(downloads.map(d => [d.song.id, { ...d.song, _t: d.downloadedAt }]));
      localStorage.setItem(`sm_descargadas:${user.id}`, JSON.stringify(values));
      w.__smDownloadedCount = downloads.length; bus.emit('descargas');
    } catch { /* El estado nativo permanece disponible en Descargas del dispositivo. */ }
  };
  w.__smNativeToast = (text: string) => bus.emit('toast', { msg: text });
  if (typeof document !== 'undefined') {
    const addSettings = () => {
      const container = document.querySelector('.settings');
      if (!container || document.getElementById('sm-native-settings')) return;
      const panel = document.createElement('div'); panel.className = 'panel'; panel.id = 'sm-native-settings';
      const title = document.createElement('h3'); title.textContent = 'Este iPhone';
      const button = document.createElement('button'); button.className = 'btn'; button.textContent = 'Servidor y descargas del dispositivo';
      button.onclick = () => send('settings'); panel.append(title, button); container.append(panel);
    };
    new MutationObserver(addSettings).observe(document.body, { childList: true, subtree: true });
    addSettings();
  }
  w.__smDownloadFile = (url: string) => {
    setTimeout(() => {
      const id = Number(url.match(/canciones\/(\d+)/)?.[1]);
      const song = songFor(id);
      if (song) send('download', { url, song, exportFile: true });
    }, 0);
  };
  try { nativePlayer({ player, sleep, prefs, bus, offline }); }
  catch { if (typeof document !== 'undefined') document.getElementById?.('sm-native-player')?.remove(); } // Si falla la presentación nativa, la web conserva su reproductor.
  // Estado inicial del reproductor restaurado por la web, sin iniciar audio por sí solo.
  if (player.current) snapshot('restore');
  send('ready');
}
// El mini reproductor y "Reproduciendo ahora" son nativos: la web conserva la cola y sus reglas, pero cede la presentación.
function nativePlayer({ player, sleep, prefs, bus, offline }: any) {
  const doc = typeof document !== 'undefined' && typeof document.getElementById === 'function' ? document : null;
  const raf: (cb: () => void) => unknown = typeof requestAnimationFrame === 'function' ? requestAnimationFrame : cb => setTimeout(cb, 16);
  if (doc && !doc.getElementById('sm-native-player')) {
    const style = doc.createElement('style'); style.id = 'sm-native-player';
    // El hueco del reproductor web se conserva: el nativo se coloca exactamente encima y el contenido nunca queda tapado.
    style.textContent = '#playerBar{visibility:hidden!important;pointer-events:none!important}#playerBar *{visibility:hidden!important}.np,.drawer{display:none!important}';
    (doc.head ?? doc.documentElement).append(style);
  }
  let lib: any = null, songMenu: any = null;
  let frame = false, last = '';
  const report = () => {
    if (frame) return; frame = true;
    raf(() => {
      frame = false;
      const song = player.current, id = Number(song?.id) || 0;
      let bar: { top: number; left: number; width: number; height: number } | null = null;
      const el = doc?.getElementById('playerBar');
      if (el && song && getComputedStyle(el).display !== 'none') {
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) bar = { top: Math.round(r.top), left: Math.round(r.left), width: Math.round(r.width), height: Math.round(r.height) };
      }
      const ui = { songId: id, fav: !!(id && lib?.isFav?.(id)), shuffle: !!player.shuffle, repeat: ['off', 'all', 'one'].includes(player.repeat) ? player.repeat : 'off', autoRadio: !!prefs.autoRadio, bar };
      const json = JSON.stringify(ui);
      if (json !== last) { last = json; send('ui', { ui }); }
    });
  };
  const open = (tab?: unknown) => {
    if (!player.current) { bus.emit('toast', { msg: 'Reproduce una canción primero' }); return; }
    send('openPlayer', { tab: tab === 'letra' || tab === 'cola' ? tab : null });
  };
  void Promise.all([
    import(/* @vite-ignore */ `${location.origin}/js/state.js`).catch(() => ({})),
    import(/* @vite-ignore */ `${location.origin}/js/nowplaying.js`).catch(() => ({})),
    import(/* @vite-ignore */ `${location.origin}/js/ui.js`).catch(() => ({})),
  ]).then(([state, now, ui]: any[]) => {
    lib = state.lib ?? null; songMenu = ui.songMenu ?? null;
    if (now.np) { now.np.open = (tab?: string) => open(tab); now.np.toggle = (tab?: string) => open(tab); }
    if (now.queueDrawer) { now.queueDrawer.open = () => open('cola'); now.queueDrawer.toggle = () => open('cola'); }
    report();
  });
  w.__smCmd = (name: string, arg?: any) => {
    const song = player.current;
    const fail = () => bus.emit('toast', { msg: 'No se pudo completar la acción. Inténtalo de nuevo.', error: true });
    switch (name) {
      case 'shuffle': player.toggleShuffle(); break;
      case 'repeat': player.cycleRepeat(); break;
      case 'autoRadio': prefs.autoRadio = !!arg; prefs.save(); break;
      case 'fav': if (song && lib) Promise.resolve(lib.toggleFav(song.id)).catch(fail); break;
      case 'remove': player.removeAt(Number(arg)); break;
      case 'move': player.move(Number(arg?.from), Number(arg?.to)); break;
      case 'clear': player.clearUpcoming(); break;
      case 'sleep': sleep.set(arg === 'end' ? 'end' : Math.max(0, Number(arg) || 0)); break;
      case 'radio': if (song) Promise.resolve(player.startRadio(song)).catch(fail); break;
      case 'artist': if (song) location.hash = '#/artista/' + encodeURIComponent(song.artista); break;
      case 'album': if (song?.album) location.hash = '#/album/' + encodeURIComponent(song.album); break;
      case 'download': if (song && offline.descargar) offline.descargar([song], { archivo: false }); break;
      case 'removeDownload': if (song && offline.quitarDescarga) offline.quitarDescarga(song.id); break;
      case 'menu': if (song && songMenu && doc) songMenu(doc.body, song); break;
    }
    report();
  };
  for (const event of ['favs', 'player:song', 'player:mode', 'player:queue', 'prefs', 'sleep']) bus.on(event, report);
  if (typeof addEventListener === 'function') { addEventListener('resize', report); addEventListener('hashchange', report); }
  if (doc && typeof MutationObserver === 'function') new MutationObserver(report).observe(doc.body, { attributes: true, attributeFilter: ['class', 'style'], subtree: false });
  const bar = doc?.getElementById('playerBar');
  if (bar && typeof MutationObserver === 'function') new MutationObserver(report).observe(bar, { attributes: true, attributeFilter: ['class'] });
  (w.visualViewport as any)?.addEventListener?.('resize', report);
  report();
}
connect().catch(() => send('error',{ message: 'No se pudo conectar la interfaz con el audio nativo. Recarga la app.' }));

export {};
