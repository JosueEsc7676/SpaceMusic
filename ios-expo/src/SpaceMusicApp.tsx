import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, AppState, Image, KeyboardAvoidingView, Linking, Modal, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import * as Notifications from 'expo-notifications';
import * as SplashScreen from 'expo-splash-screen';
import { AudioEngine } from './audio';
import { bootstrap } from './bootstrap';
import { bridgeScript } from './generated/bridge';
import { normalizeServer, parseMessage, queueKeys, sameOrigin, webCommand, type PlayerTab, type PlayerUi, type Session, type Snapshot } from './protocol';
import type { PlayerActions } from './player/actions';
import { FullPlayer } from './player/FullPlayer';
import { MiniPlayer } from './player/MiniPlayer';
import { createStore, emptyPlayback, emptyUi, type Playback } from './player/store';
import { Storage, readServer, readSession, saveServer, saveSession } from './storage';
import { styles as s } from './styles';

void SplashScreen.preventAutoHideAsync().catch(() => {});
Notifications.setNotificationHandler({ handleNotification: async () => ({ shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false }) });
export default function App() { return <SafeAreaProvider><SpaceMusic /></SafeAreaProvider>; }

function SpaceMusic() {
  const web = useRef<WebView<{}>>(null);
  const storage = useRef(new Storage()).current;
  const engine = useRef<AudioEngine | null>(null);
  const account = useRef<Session | null>(null);
  const activeServer = useRef('');
  const [server, setServer] = useState('');
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [message, setMessage] = useState('');
  const [settings, setSettings] = useState(false);
  const [draft, setDraft] = useState('');
  const [webVersion, setWebVersion] = useState(0);
  const [offline, setOffline] = useState(false);
  const [, refreshDownloads] = useState(0);
  const playback = useRef(createStore<Playback>(emptyPlayback)).current;
  const uiStore = useRef(createStore<PlayerUi>(emptyUi)).current;
  const [playerOpen, setPlayerOpen] = useState(false);
  const [playerTab, setPlayerTab] = useState<PlayerTab>(null);
  const chain = useRef(Promise.resolve());
  const ready = useRef(false), visible = useRef(true), lastStatus = useRef(0);
  const lastQueueSent = useRef('');
  const loadTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const showNativeControls = useRef(false);
  showNativeControls.current = offline || failed;
  const [progress, setProgress] = useState(0);
  const inject = (script: string) => web.current?.injectJavaScript(`${script};true;`);
  const toast = (text: string) => { setMessage(text); setTimeout(() => setMessage(current => current === text ? '' : current), 8000); };

  useEffect(() => {
    let disposed = false;
    const audio = new AudioEngine(storage); engine.current = audio; audio.onError = toast;
    audio.onStatus = status => {
      if (disposed) return;
      const intended = status.playing || (status.isBuffering && status.intendedPlaying);
      playback.set({ items: status.items, count: status.items.length, index: status.currentIndex, time: status.currentTime, duration: status.duration,
        playing: intended, buffering: intended && (status.isBuffering || !status.isLoaded), sleepUntil: status.sleepUntil, sleepEnd: status.sleepEnd, context: audio.snapshot()?.context });
      if (ready.current && visible.current && Date.now() - lastStatus.current > 450) {
        lastStatus.current = Date.now();
        const signature = queueKeys(status.items).join('|');
        const payload = { ...status, items: signature !== lastQueueSent.current ? status.items : undefined };
        lastQueueSent.current = signature;
        inject(`window.__smNativeStatus?.(${JSON.stringify(payload)})`);
      }
    };
    storage.onChange = () => {
      audio.refreshDownloads();
      if (!disposed) refreshDownloads(v => v + 1);
      inject(`window.__smSetDownloads?.(${JSON.stringify(storage.downloads.map(d => ({ song: d.song, downloadedAt: d.downloadedAt })))})`);
    };
    storage.onError = toast;
    storage.onDownload = (id, ok) => inject(`window.__smDownloadResult?.(${id},${ok},${storage.downloads.length})`);
    void (async () => {
      try {
        const address = normalizeServer(await readServer()), saved = await readSession(address);
        await audio.init(); await storage.setScope(address, saved);
        audio.configure(address, saved); account.current = saved; activeServer.current = address;
        if (!disposed) { setSession(saved); setServer(address); setDraft(address); }
      } catch (error) { toast(error instanceof Error ? error.message : 'No se pudo iniciar la app.'); setFailed(true); }
      finally { await SplashScreen.hideAsync(); }
    })();
    const subscription = AppState.addEventListener('change', state => {
      visible.current = state === 'active';
      if (visible.current) { lastStatus.current = 0; audio.status(); }
      const snapshot = audio.snapshot(); if (snapshot) void storage.saveQueue(snapshot);
    });
    const notifications = Notifications.addNotificationResponseReceivedListener(response => {
      const route = response.notification.request.content.data?.route;
      if (typeof route === 'string' && /^#\/[\w\-/%?=&.]*$/.test(route)) inject(`location.hash=${JSON.stringify(route)}`);
    });
    return () => { disposed = true; if (loadTimer.current) clearTimeout(loadTimer.current); audio.destroy(); subscription.remove(); notifications.remove(); };
  }, []);

  async function receive(event: Pick<WebViewMessageEvent, 'nativeEvent'>) {
    if (server !== activeServer.current || !sameOrigin(event.nativeEvent.url, server)) return;
    const m = parseMessage(event.nativeEvent.data, server), audio = engine.current;
    if (!m || !audio) return;
    switch (m.type) {
      case 'session': {
        if (JSON.stringify(account.current) === JSON.stringify(m.session)) break;
        if (!m.session && account.current) { audio.stop(); resetPlayer(); await storage.clear(); }
        account.current = m.session;
        await saveSession(server, m.session); await storage.setScope(server, m.session);
        audio.configure(server, m.session); setSession(m.session);
        const current = audio.snapshot(); if (current) await audio.apply(current, 'sync');
        break;
      }
      case 'ready': {
        if (loadTimer.current) clearTimeout(loadTimer.current);
        ready.current = true; lastQueueSent.current = ''; setLoading(false);
        inject(`window.__smSetDownloads?.(${JSON.stringify(storage.downloads.map(d => ({ song: d.song, downloadedAt: d.downloadedAt })))})`);
        if (audio.snapshot()) { lastStatus.current = 0; audio.status(); }
        else { const saved = await storage.read<Snapshot | null>('queue', null); if (saved) inject(`window.__smRestoreQueue?.(${JSON.stringify(saved)})`); }
        break;
      }
      case 'settings': setDraft(server); setSettings(true); break;
      case 'queue':
        if (m.intent === 'restore' && audio.snapshot()) { audio.status(); break; }
        await audio.apply(m.state, m.intent === 'restore' ? 'load' : m.intent); break;
      case 'play': audio.play(); break;
      case 'pause': audio.pause(); break;
      case 'seek': await audio.seek(m.time); break;
      case 'volume': audio.volume(m.volume); break;
      case 'stop': audio.stop(); break;
      case 'download': if (account.current) storage.download(m.url, m.song, !!m.exportFile); break;
      case 'removeDownload': await storage.remove(m.id); break;
      case 'clearDownloads': await storage.clear(); break;
      case 'ui': uiStore.set(m.ui); break;
      case 'openPlayer': setPlayerTab(m.tab); setPlayerOpen(true); break;
      case 'error': toast(m.message); setLoading(false); setFailed(true); break;
      case 'notify': {
        let permission = await Notifications.getPermissionsAsync();
        if (!permission.granted) permission = await Notifications.requestPermissionsAsync();
        if (permission.granted) await Notifications.scheduleNotificationAsync({ content: { title: m.title, body: m.body, data: { route: m.route } }, trigger: null }); break;
      }
    }
  }
  function resetPlayer() { playback.set(emptyPlayback); uiStore.set(emptyUi); setPlayerOpen(false); }
  function reload() { uiStore.set({ bar: null }); ready.current = false; setFailed(false); setOffline(false); setLoading(true); setWebVersion(v => v + 1); }
  async function changeServer() {
    try {
      const address = normalizeServer(draft); if (address === server) { setSettings(false); return; }
      engine.current?.stop(); resetPlayer(); await saveServer(address);
      const saved = await readSession(address); await storage.setScope(address, saved);
      account.current = saved; engine.current?.configure(address, saved);
      activeServer.current = address; setServer(address); setSession(saved); setSettings(false); reload();
    } catch (error) { toast(error instanceof Error ? error.message : 'Dirección incorrecta'); }
  }
  const closePlayer = useCallback(() => setPlayerOpen(false), []);
  const openPlayer = useCallback(() => { setPlayerTab(null); setPlayerOpen(true); }, []);
  const actions = useMemo<PlayerActions>(() => {
    const webReady = () => ready.current && !!web.current && !showNativeControls.current;
    return {
      server, ui: uiStore, webReady,
      token: () => account.current?.token ?? null,
      isDownloaded: id => !!storage.local(id),
      play: () => engine.current?.play(), pause: () => engine.current?.pause(),
      seek: time => { void engine.current?.seek(time); },
      next: () => engine.current?.next(), previous: () => engine.current?.previous(), jump: index => engine.current?.jump(index),
      web: (command, arg) => { if (webReady()) inject(webCommand(command, arg)); },
    };
  }, [server]);
  return <SafeAreaView style={s.root} edges={['top', 'bottom']}>
    <StatusBar style="light" />
    {!!server && !failed && !offline && <View style={s.web}><WebView<{}> ref={web} key={`${server}:${webVersion}`} source={{ uri: server + '/' }} style={s.web}
      originWhitelist={[server]} javaScriptEnabled domStorageEnabled cacheEnabled sharedCookiesEnabled={false} thirdPartyCookiesEnabled={false}
      allowsInlineMediaPlayback mediaPlaybackRequiresUserAction={false} allowsBackForwardNavigationGestures contentInsetAdjustmentBehavior="never" automaticallyAdjustContentInsets={false}
      setSupportMultipleWindows={false} javaScriptCanOpenWindowsAutomatically={false} webviewDebuggingEnabled={__DEV__} allowsLinkPreview={false}
      injectedJavaScriptBeforeContentLoaded={bootstrap(session, server)} injectedJavaScript={bridgeScript}
      onMessage={event => {
        const captured = { nativeEvent: { ...event.nativeEvent } };
        chain.current = chain.current.then(() => receive(captured)).catch(() => toast('No se pudo completar la operación. Inténtalo de nuevo.'));
      }}
      onLoadStart={() => {
        ready.current = false; setLoading(true); uiStore.set({ bar: null });
        if (loadTimer.current) clearTimeout(loadTimer.current);
        loadTimer.current = setTimeout(() => { if (!ready.current) { setLoading(false); setFailed(true); } }, 25000);
      }} onLoadProgress={e => setProgress(e.nativeEvent.progress)} onLoadEnd={() => { if (ready.current) setLoading(false); }}
      onError={() => { setLoading(false); setFailed(true); }} onHttpError={e => { if (e.nativeEvent.statusCode >= 500) toast('El servidor no está respondiendo.'); }}
      onContentProcessDidTerminate={reload}
      onShouldStartLoadWithRequest={r => { if (r.url === 'about:blank' || sameOrigin(r.url, server)) return true; if (/^(https:|mailto:)/.test(r.url)) void Linking.openURL(r.url); return false; }} />
      <MiniPlayer playback={playback} actions={actions} mode="web" onOpen={openPlayer} /></View>}
    {loading && !failed && !offline && <View style={s.loading}>
      <Image source={require('../assets/spacemusic.png')} style={s.logo} /><Text style={s.brand}>SpaceMusic</Text><ActivityIndicator color="#b4a5ff" style={{ marginTop: 24 }} />
      <View style={s.progress}><View style={[s.progressFill, { width: `${Math.max(3, progress * 100)}%` }]} /></View>
      <Pressable onPress={() => { setSettings(true); setDraft(server); }}><Text style={s.link}>Configurar servidor</Text></Pressable>
    </View>}
    {(failed || offline) && <View style={s.panel}>
      <View style={s.header}><Text style={s.title}>{offline ? 'Tu música, sin conexión' : 'No se pudo conectar'}</Text><Pressable onPress={() => setSettings(true)}><Text style={s.link}>Servidor</Text></Pressable></View>
      <Text style={s.muted}>{offline ? 'Canciones guardadas en este iPhone.' : 'Revisa la conexión y que el servidor esté disponible en tu red o Tailscale.'}</Text>
      <Pressable style={s.button} onPress={reload}><Text style={s.buttonText}>Volver a conectar</Text></Pressable>
      <ScrollView contentContainerStyle={{ paddingVertical: 24, gap: 12 }}>
        {!storage.downloads.length && <Text style={s.muted}>Aún no hay canciones descargadas en esta cuenta.</Text>}
        {storage.downloads.map((d, index) => <Pressable key={d.song.id} style={s.song} onPress={() => {
          void engine.current?.apply({ items: storage.downloads.map(d => d.song), index, time: 0, playing: true, repeat: 'off', volume: 0.8, sleepUntil: 0, sleepEnd: false, autoRadio: false, context: { type: 'descargas', label: 'Descargas' } }, 'load');
        }}><View style={s.art}><Text style={{ color: '#b4a5ff', fontSize: 24 }}>♫</Text></View><View style={{ flex: 1 }}><Text style={s.songTitle} numberOfLines={1}>{d.song.nombre}</Text><Text style={s.muted} numberOfLines={1}>{d.song.artista}</Text></View><Text style={s.link}>▶</Text></Pressable>)}
      </ScrollView>
      <MiniPlayer playback={playback} actions={actions} mode="docked" onOpen={openPlayer} style={{ marginHorizontal: 0 }} />
    </View>}
    <FullPlayer visible={playerOpen} tab={playerTab} setTab={setPlayerTab} onClose={closePlayer} playback={playback} actions={actions} />
    {!!message && <Pressable style={s.toast} onPress={() => setMessage('')}><Text style={s.text}>{message}</Text></Pressable>}
    <Modal visible={settings} transparent animationType="slide" onRequestClose={() => setSettings(false)}><KeyboardAvoidingView behavior="padding" style={s.backdrop}><SafeAreaView style={s.sheet}>
      <View style={s.header}><Text style={s.title}>SpaceMusic</Text><Pressable onPress={() => setSettings(false)}><Text style={s.link}>Cerrar</Text></Pressable></View>
      <Text style={s.muted}>Servidor de tu biblioteca</Text><TextInput style={s.input} value={draft} onChangeText={setDraft} autoCapitalize="none" autoCorrect={false} keyboardType="url" placeholder="https://tu-servidor" placeholderTextColor="#64646f" />
      <Pressable style={s.button} onPress={() => void changeServer()}><Text style={s.buttonText}>Guardar y conectar</Text></Pressable>
      <Pressable style={s.secondary} onPress={() => { setSettings(false); setOffline(true); }}><Text style={s.text}>Abrir descargas de este iPhone · {storage.downloads.length}</Text></Pressable>
      <Pressable style={s.secondary} onPress={() => Alert.alert('Borrar descargas', 'Se eliminarán las canciones guardadas de esta cuenta en este iPhone.', [{ text: 'Cancelar', style: 'cancel' }, { text: 'Borrar', style: 'destructive', onPress: () => void storage.clear() }])}><Text style={{ color: '#ff6b6b' }}>Borrar descargas locales</Text></Pressable>
      <Text style={s.footnote}>Tus descargas se guardan por cuenta y permanecen en este iPhone hasta que las borres o cierres sesión.</Text>
    </SafeAreaView></KeyboardAvoidingView></Modal>
  </SafeAreaView>;
}
