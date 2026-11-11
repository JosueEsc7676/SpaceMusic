import React, { memo, useEffect, useMemo, useRef, useState } from 'react';
import { ActionSheetIOS, ActivityIndicator, Alert, Animated, FlatList, Modal, Pressable, ScrollView, Share, StyleSheet, Switch, Text, useWindowDimensions, View, type ScrollViewInstance } from 'react-native';
import { Image } from 'expo-image';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { PlayerTab, Song } from '../protocol';
import { Icon, type IconName } from './Icon';
import { activeLine } from './lrc';
import { useLyrics, type LyricsState } from './lyrics';
import { Seek } from './Seek';
import { contextTitle, coverUrl, type Playback, type Store } from './store';
import { useStore } from './hooks';
import type { PlayerActions } from './actions';

export function useDelayed(flag: boolean, ms = 600) {
  const [value, setValue] = useState(false);
  useEffect(() => { if (!flag) { setValue(false); return; } const t = setTimeout(() => setValue(true), ms); return () => clearTimeout(t); }, [flag, ms]);
  return value;
}

export function FullPlayer({ visible, tab, setTab, onClose, playback, actions }: {
  visible: boolean; tab: PlayerTab; setTab: (t: PlayerTab) => void; onClose: () => void; playback: Store<Playback>; actions: PlayerActions;
}) {
  return <Modal visible={visible} animationType="slide" presentationStyle="pageSheet" onRequestClose={onClose}>
    {visible && <Screen tab={tab} setTab={setTab} onClose={onClose} playback={playback} actions={actions} />}
  </Modal>;
}

function Screen({ tab, setTab, onClose, playback, actions }: { tab: PlayerTab; setTab: (t: PlayerTab) => void; onClose: () => void; playback: Store<Playback>; actions: PlayerActions }) {
  const p = useStore(playback), ui = useStore(actions.ui);
  const insets = useSafeAreaInsets(), { width } = useWindowDimensions();
  const song = p.items[p.index];
  const [body, setBody] = useState(0);
  const lyrics = useLyrics(actions.server, actions.token(), song?.id);
  const synced = lyrics.status === 'ready' && !!lyrics.lyrics?.synced.length;
  useEffect(() => { if (!song) onClose(); }, [song, onClose]);
  if (!song) return <View style={s.root} />;
  const art = coverUrl(actions.server, song.portadaUrl, 600);
  const fav = ui.songId === song.id && ui.fav;
  const web = actions.webReady();
  const artSize = Math.max(120, Math.min(width - 48, body - 16));
  const sleeping = p.sleepUntil > Date.now() || p.sleepEnd;

  const more = () => {
    if (!web) { Alert.alert('Sin conexión', 'Estas opciones necesitan conexión con el servidor.'); return; }
    const downloaded = actions.isDownloaded(song.id);
    const options: [string, () => void][] = [
      [fav ? 'Quitar de Me gusta' : 'Agregar a Me gusta', () => actions.web('fav')],
      ['Iniciar radio', () => actions.web('radio')],
      ['Ir al artista', () => { onClose(); actions.web('artist'); }],
      ...(song.album && song.album !== 'Single' ? [['Ir al álbum', () => { onClose(); actions.web('album'); }] as [string, () => void]] : []),
      [downloaded ? 'Quitar descarga de este iPhone' : 'Descargar en este iPhone', () => actions.web(downloaded ? 'removeDownload' : 'download')],
      ['Agregar a playlist, enviar y más…', () => { onClose(); setTimeout(() => actions.web('menu'), 350); }],
    ];
    const remove = options.findIndex(o => o[0].startsWith('Quitar descarga'));
    ActionSheetIOS.showActionSheetWithOptions({ title: song.nombre, message: song.artista, options: [...options.map(o => o[0]), 'Cancelar'], cancelButtonIndex: options.length, destructiveButtonIndex: remove >= 0 ? remove : undefined },
      i => options[i]?.[1]());
  };
  const sleepMenu = () => {
    if (!web) return;
    const choices: [string, number | 'end'][] = [['15 minutos', 15], ['30 minutos', 30], ['45 minutos', 45], ['1 hora', 60], ['Al terminar la canción', 'end']];
    const labels = [...choices.map(c => c[0]), ...(sleeping ? ['Desactivar temporizador'] : []), 'Cancelar'];
    ActionSheetIOS.showActionSheetWithOptions({ title: 'Temporizador para dormir', options: labels, cancelButtonIndex: labels.length - 1, destructiveButtonIndex: sleeping ? choices.length : undefined },
      i => { if (i < choices.length) actions.web('sleep', choices[i][1]); else if (sleeping && i === choices.length) actions.web('sleep', 0); });
  };

  return <View style={s.root}>
    {art ? <Image source={{ uri: art }} style={StyleSheet.absoluteFill} contentFit="cover" blurRadius={70} cachePolicy="memory-disk" transition={400} /> : null}
    <View style={[StyleSheet.absoluteFill, s.tint]} />
    <View style={[s.content, { paddingBottom: Math.max(insets.bottom, 12) }]}>
      <View style={s.grabber} />
      <View style={s.header}>
        <IconButton name="down" label="Cerrar" onPress={onClose} />
        <View style={s.headerText}>
          <Text style={s.from} numberOfLines={1} maxFontSizeMultiplier={1.3}>{contextTitle(p.context?.type)}</Text>
          {!!p.context?.label && <Text style={s.fromLabel} numberOfLines={1} maxFontSizeMultiplier={1.3}>{p.context.label}</Text>}
        </View>
        <IconButton name="more" label="Más opciones" onPress={more} />
      </View>

      <View style={s.body} onLayout={e => setBody(e.nativeEvent.layout.height)}>
        {tab === null
          ? <View style={s.artWrap}><Artwork uri={art} size={artSize} playing={p.playing} /></View>
          : <>
            <View style={s.compact}>
              <Cover uri={coverUrl(actions.server, song.portadaUrl, 128)} size={52} radius={8} />
              <View style={s.flexText}><Text style={s.compactTitle} numberOfLines={1}>{song.nombre}</Text><Text style={s.artist} numberOfLines={1}>{song.artista}</Text></View>
              {web && <IconButton name={fav ? 'heartFill' : 'heart'} label={fav ? 'Quitar de Me gusta' : 'Me gusta'} onPress={() => actions.web('fav')} color={fav ? '#ff6b8b' : '#fff'} />}
            </View>
            <View style={s.panel}>
              {tab === 'letra' ? <LyricsPanel state={lyrics} time={p.time} onSeek={t => { actions.seek(t); if (!p.playing) actions.play(); }} />
                : <QueuePanel playback={p} actions={actions} />}
            </View>
          </>}
      </View>

      {tab === null && <View style={s.info}>
        <View style={s.titleRow}>
          <View style={s.flexText}>
            <Text style={s.title} numberOfLines={1} maxFontSizeMultiplier={1.35}>{song.nombre}</Text>
            <Pressable onPress={() => { if (web) { onClose(); actions.web('artist'); } }} hitSlop={6} accessibilityRole="link">
              <Text style={s.artistBig} numberOfLines={1} maxFontSizeMultiplier={1.35}>{song.artista}</Text>
            </Pressable>
          </View>
          {web && <IconButton name={fav ? 'heartFill' : 'heart'} label={fav ? 'Quitar de Me gusta' : 'Me gusta'} onPress={() => actions.web('fav')} color={fav ? '#ff6b8b' : '#fff'} size={28} />}
        </View>
        {synced && <MiniLyric state={lyrics} time={p.time} onPress={() => setTab('letra')} />}
      </View>}

      <View style={s.controlsArea}>
        <Seek time={p.time} duration={p.duration || song.duracionSegundos || 0} onSeek={actions.seek} />
        <Controls playback={p} actions={actions} width={width} />
        <View style={s.toolbar}>
          <ToolButton name="moon" label="Temporizador para dormir" active={sleeping} onPress={sleepMenu} disabled={!web} badge={p.sleepUntil > Date.now() ? `${Math.ceil((p.sleepUntil - Date.now()) / 60000)}′` : p.sleepEnd ? 'fin' : ''} />
          <ToolButton name="send" label="Compartir" onPress={() => void Share.share({ message: `${song.nombre} · ${song.artista}` }).catch(() => {})} />
          <ToolButton name="quote" label="Letra" active={tab === 'letra'} onPress={() => setTab(tab === 'letra' ? null : 'letra')} />
          <ToolButton name="list" label="A continuación" active={tab === 'cola'} onPress={() => setTab(tab === 'cola' ? null : 'cola')} />
        </View>
      </View>
    </View>
  </View>;
}

const Artwork = memo(function Artwork({ uri, size, playing }: { uri?: string; size: number; playing: boolean }) {
  const scale = useRef(new Animated.Value(playing ? 1 : 0.88)).current;
  useEffect(() => { Animated.spring(scale, { toValue: playing ? 1 : 0.88, useNativeDriver: true, friction: 7 }).start(); }, [playing, scale]);
  return <Animated.View style={[s.artShadow, { width: size, height: size, transform: [{ scale }] }]}>
    <Cover uri={uri} size={size} radius={14} />
  </Animated.View>;
});

export const Cover = memo(function Cover({ uri, size, radius }: { uri?: string; size: number; radius: number }) {
  return <View style={{ width: size, height: size, borderRadius: radius, overflow: 'hidden', backgroundColor: '#1b1b21', alignItems: 'center', justifyContent: 'center' }}>
    {uri ? <Image source={{ uri }} style={{ width: size, height: size }} contentFit="cover" cachePolicy="memory-disk" transition={150} recyclingKey={uri} />
      : <Text style={{ color: '#b4a5ff', fontSize: Math.max(14, size * 0.4) }}>♫</Text>}
  </View>;
});

function Controls({ playback: p, actions, width }: { playback: Playback; actions: PlayerActions; width: number }) {
  const ui = useStore(actions.ui), web = actions.webReady();
  const waiting = useDelayed(p.buffering && p.playing);
  const big = Math.min(76, Math.max(60, width * 0.19));
  return <View style={s.controls}>
    <View style={s.ctrlSlot}><IconButton name="shuffle" label={ui.shuffle ? 'Desactivar aleatorio' : 'Activar aleatorio'} onPress={() => actions.web('shuffle')} disabled={!web} color={ui.shuffle ? '#b4a5ff' : '#fff'} size={22} dot={ui.shuffle} /></View>
    <View style={s.ctrlSlot}><IconButton name="rew" label="Anterior" onPress={actions.previous} size={34} box={60} /></View>
    <View style={s.ctrlSlot}>
      <Pressable onPress={p.playing ? actions.pause : actions.play} accessibilityRole="button" accessibilityLabel={p.playing ? 'Pausar' : 'Reproducir'}
        style={({ pressed }) => [s.play, { width: big, height: big, borderRadius: big / 2, transform: [{ scale: pressed ? 0.94 : 1 }] }]}>
        {waiting ? <ActivityIndicator color="#000" /> : <Icon name={p.playing ? 'pause' : 'play'} size={big * 0.42} color="#000" />}
      </Pressable>
    </View>
    <View style={s.ctrlSlot}><IconButton name="fwd" label="Siguiente" onPress={actions.next} size={34} box={60} /></View>
    <View style={s.ctrlSlot}>
      <IconButton name="repeat" label={{ off: 'Repetir desactivado', all: 'Repetir todo', one: 'Repetir esta canción' }[ui.repeat]} onPress={() => actions.web('repeat')} disabled={!web} color={ui.repeat !== 'off' ? '#b4a5ff' : '#fff'} size={22} dot={ui.repeat !== 'off'} />
      {ui.repeat === 'one' && <Text style={s.repeatOne} pointerEvents="none">1</Text>}
    </View>
  </View>;
}

function IconButton({ name, label, onPress, color = '#fff', size = 24, box = 44, disabled, dot }: { name: IconName; label: string; onPress: () => void; color?: string; size?: number; box?: number; disabled?: boolean; dot?: boolean }) {
  return <Pressable onPress={onPress} disabled={disabled} hitSlop={4} accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }}
    style={({ pressed }) => [{ width: box, height: box, alignItems: 'center', justifyContent: 'center', opacity: disabled ? 0.35 : pressed ? 0.55 : 1 }]}>
    <Icon name={name} size={size} color={color} />
    {dot && <View style={s.dot} />}
  </Pressable>;
}
function ToolButton({ name, label, onPress, active, disabled, badge }: { name: IconName; label: string; onPress: () => void; active?: boolean; disabled?: boolean; badge?: string }) {
  return <Pressable onPress={onPress} disabled={disabled} accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ selected: !!active, disabled }}
    style={({ pressed }) => [s.tool, active && s.toolActive, { opacity: disabled ? 0.35 : pressed ? 0.6 : 1 }]}>
    <Icon name={name} size={22} color={active ? '#000' : 'rgba(255,255,255,.85)'} />
    {!!badge && <Text style={[s.badge, active && { color: '#000' }]} maxFontSizeMultiplier={1.2}>{badge}</Text>}
  </Pressable>;
}

function MiniLyric({ state, time, onPress }: { state: LyricsState; time: number; onPress: () => void }) {
  const lines = state.lyrics?.synced ?? [];
  const i = activeLine(lines, time + 0.2);
  const text = i >= 0 ? lines[i].text : lines.find(l => l.text)?.text ?? '';
  return <Pressable onPress={onPress} style={s.miniLyric} accessibilityRole="button" accessibilityLabel="Ver la letra completa">
    <Text style={[s.miniLyricText, i < 0 && { opacity: 0.45 }]} numberOfLines={2} maxFontSizeMultiplier={1.25}>{text || '• • •'}</Text>
  </Pressable>;
}

function LyricsPanel({ state, time, onSeek }: { state: LyricsState & { retry?: () => void }; time: number; onSeek: (t: number) => void }) {
  const scroll = useRef<ScrollViewInstance>(null);
  const ys = useRef<number[]>([]), viewport = useRef(0), userUntil = useRef(0);
  const lines = state.lyrics?.synced ?? [];
  const active = activeLine(lines, time + 0.25);
  useEffect(() => {
    if (active < 0 || Date.now() < userUntil.current) return;
    const y = ys.current[active];
    if (y !== undefined) scroll.current?.scrollTo({ y: Math.max(0, y - viewport.current * 0.28), animated: true });
  }, [active]);
  if (state.status === 'loading' || state.status === 'idle') return <View style={s.center}><ActivityIndicator color="#fff" /></View>;
  if (state.status === 'error') return <View style={s.center}><Text style={s.emptyText}>No se pudo cargar la letra.</Text>{state.retry && <Pressable onPress={state.retry} style={s.pill}><Text style={s.pillText}>Reintentar</Text></Pressable>}</View>;
  if (!state.lyrics) return <View style={s.center}><Icon name="quote" size={34} color="rgba(255,255,255,.45)" /><Text style={s.emptyText}>Esta canción no tiene letra todavía.</Text></View>;
  if (!lines.length) return <ScrollView contentContainerStyle={s.plainBox} showsVerticalScrollIndicator={false}><Text style={s.plain} maxFontSizeMultiplier={1.5}>{state.lyrics.plain}</Text></ScrollView>;
  return <ScrollView ref={scroll} style={s.lyrics} contentContainerStyle={[s.lyricsBox, { paddingBottom: Math.max(120, viewport.current * 0.6) }]} showsVerticalScrollIndicator={false}
    onLayout={e => { viewport.current = e.nativeEvent.layout.height; }} onScrollBeginDrag={() => { userUntil.current = Date.now() + 3500; }}
    onMomentumScrollEnd={() => { userUntil.current = Date.now() + 2500; }} scrollEventThrottle={64}>
    {lines.map((l, i) => <Pressable key={`${i}:${l.t}`} onLayout={e => { ys.current[i] = e.nativeEvent.layout.y; }} onPress={() => { userUntil.current = 0; onSeek(l.t + 0.05); }}
      accessibilityRole="button" accessibilityLabel={l.text || 'Interludio'} style={s.lineBox}>
      <Text style={[s.line, l.interlude && s.interlude, { opacity: i === active ? 1 : i < active ? 0.28 : 0.4 }]} maxFontSizeMultiplier={1.4}>{l.interlude ? '• • •' : l.text}</Text>
    </Pressable>)}
  </ScrollView>;
}

function QueuePanel({ playback: p, actions }: { playback: Playback; actions: PlayerActions }) {
  const ui = useStore(actions.ui), web = actions.webReady();
  const current = p.items[p.index];
  const upcoming = useMemo(() => p.items.slice(p.index + 1, p.index + 201).map((song, k) => ({ song, index: p.index + 1 + k })), [p.items, p.count, p.index]);
  const rest = p.items.length - (p.index + 1) - upcoming.length;
  const options = (song: Song, index: number) => {
    if (!web) { actions.jump(index); return; }
    const choices: [string, () => void][] = [
      ['Reproducir ahora', () => actions.jump(index)],
      ...(index > p.index + 1 ? [['Reproducir a continuación', () => actions.web('move', { from: index, to: p.index + 1 })] as [string, () => void], ['Subir', () => actions.web('move', { from: index, to: index - 1 })] as [string, () => void]] : []),
      ...(index < p.items.length - 1 ? [['Bajar', () => actions.web('move', { from: index, to: index + 1 })] as [string, () => void]] : []),
      ['Quitar de la cola', () => actions.web('remove', index)],
    ];
    ActionSheetIOS.showActionSheetWithOptions({ title: song.nombre, message: song.artista, options: [...choices.map(c => c[0]), 'Cancelar'], cancelButtonIndex: choices.length, destructiveButtonIndex: choices.length - 1 }, i => choices[i]?.[1]());
  };
  return <FlatList data={upcoming} keyExtractor={x => `${x.song.id}:${x.song._uid ?? x.index}`} style={s.queue} contentContainerStyle={s.queueBox}
    initialNumToRender={14} windowSize={7} removeClippedSubviews
    ListHeaderComponent={<>
      <View style={s.radioRow}>
        <Icon name="radio" size={22} color="#b4a5ff" />
        <Text style={s.radioText} numberOfLines={2} maxFontSizeMultiplier={1.3}>Seguir con canciones parecidas</Text>
        <Switch value={ui.autoRadio} onValueChange={v => actions.web('autoRadio', v)} disabled={!web} trackColor={{ true: '#b4a5ff' }} />
      </View>
      {current && <><Text style={s.qLabel}>Sonando ahora</Text><QueueRow song={current} server={actions.server} current onPress={p.playing ? actions.pause : actions.play} /></>}
      <View style={s.qHead}>
        <Text style={[s.qLabel, s.flexText]} numberOfLines={1}>{upcoming.length ? `A continuación${p.context?.label ? ` · de ${p.context.label}` : ''}` : ui.autoRadio ? 'Al terminar seguirá la radio con canciones parecidas' : 'No hay más canciones'}</Text>
        {web && upcoming.length > 0 && <Pressable onPress={() => Alert.alert('Vaciar la cola', 'Se quitarán las canciones que siguen.', [{ text: 'Cancelar', style: 'cancel' }, { text: 'Vaciar', style: 'destructive', onPress: () => actions.web('clear') }])} hitSlop={8}><Text style={s.clear}>Vaciar</Text></Pressable>}
      </View>
    </>}
    ListFooterComponent={rest > 0 ? <Text style={s.qLabel}>y {rest} más…</Text> : undefined}
    renderItem={({ item }) => <QueueRow song={item.song} server={actions.server} onPress={() => actions.jump(item.index)} onLongPress={() => options(item.song, item.index)}
      onRemove={web ? () => actions.web('remove', item.index) : undefined} />} />;
}

const QueueRow = memo(function QueueRow({ song, server, current, onPress, onLongPress, onRemove }: { song: Song; server: string; current?: boolean; onPress: () => void; onLongPress?: () => void; onRemove?: () => void }) {
  return <Pressable onPress={onPress} onLongPress={onLongPress} style={({ pressed }) => [s.qRow, current && s.qRowCurrent, pressed && { opacity: 0.6 }]}
    accessibilityRole="button" accessibilityLabel={`${song.nombre}, ${song.artista}`} accessibilityHint={onLongPress ? 'Mantén pulsado para mover o quitar' : undefined}>
    <Cover uri={coverUrl(server, song.portadaUrl, 96)} size={48} radius={8} />
    <View style={s.flexText}>
      <Text style={s.qTitle} numberOfLines={1} maxFontSizeMultiplier={1.35}>{song.nombre}</Text>
      <Text style={s.qArtist} numberOfLines={1} maxFontSizeMultiplier={1.35}>{song.artista}</Text>
    </View>
    {onRemove && <Pressable onPress={onRemove} hitSlop={8} style={s.qRemove} accessibilityRole="button" accessibilityLabel={`Quitar ${song.nombre} de la cola`}>
      <Icon name="close" size={18} color="rgba(255,255,255,.6)" />
    </Pressable>}
  </Pressable>;
});

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0b0b0e' },
  tint: { backgroundColor: 'rgba(0,0,0,.52)' },
  content: { flex: 1 },
  grabber: { alignSelf: 'center', width: 38, height: 5, borderRadius: 3, backgroundColor: 'rgba(255,255,255,.35)', marginTop: 8 },
  header: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 12, marginTop: 4 },
  headerText: { flex: 1, minWidth: 0, alignItems: 'center', paddingHorizontal: 6 },
  from: { color: 'rgba(255,255,255,.6)', fontSize: 12.5, fontWeight: '500' },
  fromLabel: { color: '#fff', fontSize: 14.5, fontWeight: '700', marginTop: 1 },
  body: { flex: 1, minHeight: 0, overflow: 'hidden' },
  artWrap: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  artShadow: { borderRadius: 14, shadowColor: '#000', shadowOpacity: 0.5, shadowRadius: 24, shadowOffset: { width: 0, height: 14 } },
  compact: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 24, paddingTop: 10, paddingBottom: 6 },
  compactTitle: { color: '#fff', fontSize: 16, fontWeight: '700' },
  artist: { color: 'rgba(255,255,255,.62)', fontSize: 14, marginTop: 1 },
  flexText: { flex: 1, minWidth: 0 },
  panel: { flex: 1, minHeight: 0, overflow: 'hidden' },
  info: { paddingHorizontal: 24, paddingTop: 14 },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  title: { color: '#fff', fontSize: 22, fontWeight: '800', letterSpacing: -0.4 },
  artistBig: { color: 'rgba(255,255,255,.62)', fontSize: 18, marginTop: 2 },
  miniLyric: { marginTop: 10, minHeight: 50, justifyContent: 'center' },
  miniLyricText: { color: '#fff', fontSize: 18, fontWeight: '800', lineHeight: 23 },
  controlsArea: { paddingHorizontal: 24, paddingTop: 10 },
  controls: { flexDirection: 'row', alignItems: 'center', marginTop: 6 },
  ctrlSlot: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  play: { backgroundColor: '#fff', alignItems: 'center', justifyContent: 'center', shadowColor: '#000', shadowOpacity: 0.35, shadowRadius: 12, shadowOffset: { width: 0, height: 4 } },
  repeatOne: { position: 'absolute', right: '22%', top: 6, color: '#b4a5ff', fontSize: 10, fontWeight: '800' },
  dot: { position: 'absolute', bottom: 4, width: 4, height: 4, borderRadius: 2, backgroundColor: '#b4a5ff' },
  toolbar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 12, paddingHorizontal: 4 },
  tool: { minWidth: 48, height: 40, borderRadius: 20, paddingHorizontal: 12, flexDirection: 'row', gap: 4, alignItems: 'center', justifyContent: 'center' },
  toolActive: { backgroundColor: 'rgba(255,255,255,.9)' },
  badge: { color: '#cfc6ff', fontSize: 12, fontWeight: '700' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12, paddingHorizontal: 32 },
  emptyText: { color: 'rgba(255,255,255,.7)', fontSize: 15, textAlign: 'center' },
  pill: { paddingHorizontal: 18, paddingVertical: 10, borderRadius: 20, backgroundColor: 'rgba(255,255,255,.14)' },
  pillText: { color: '#fff', fontWeight: '700' },
  lyrics: { flex: 1 },
  lyricsBox: { paddingHorizontal: 24, paddingTop: 16 },
  lineBox: { paddingVertical: 8 },
  line: { color: '#fff', fontSize: 27, lineHeight: 34, fontWeight: '800', letterSpacing: -0.4 },
  interlude: { fontSize: 22, letterSpacing: 4 },
  plainBox: { paddingHorizontal: 24, paddingVertical: 16 },
  plain: { color: 'rgba(255,255,255,.88)', fontSize: 20, lineHeight: 30, fontWeight: '700' },
  queue: { flex: 1 },
  queueBox: { paddingHorizontal: 16, paddingBottom: 24 },
  radioRow: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 14, marginTop: 6, borderRadius: 14, backgroundColor: 'rgba(255,255,255,.08)' },
  radioText: { flex: 1, minWidth: 0, color: '#fff', fontSize: 15.5, fontWeight: '600' },
  qHead: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  qLabel: { color: 'rgba(255,255,255,.6)', fontSize: 13, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.8, paddingTop: 18, paddingBottom: 8, paddingHorizontal: 4 },
  clear: { color: '#b4a5ff', fontSize: 15, fontWeight: '600', paddingTop: 18, paddingBottom: 8 },
  qRow: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 7, paddingHorizontal: 4, borderRadius: 12, minHeight: 62 },
  qRowCurrent: { backgroundColor: 'rgba(255,255,255,.07)' },
  qTitle: { color: '#fff', fontSize: 15.5, fontWeight: '600' },
  qArtist: { color: 'rgba(255,255,255,.6)', fontSize: 13.5, marginTop: 2 },
  qRemove: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
});
