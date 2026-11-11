import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View, type ViewStyle } from 'react-native';
import type { Rect } from '../protocol';
import type { PlayerActions } from './actions';
import { Cover, useDelayed } from './FullPlayer';
import { Icon } from './Icon';
import { coverUrl, type Playback, type Store } from './store';
import { useStore } from './hooks';

/**
 * Mini reproductor nativo. Con la web cargada ocupa exactamente el hueco que la web reserva para su barra
 * (`rect`), así el contenido de las listas siempre termina por encima de él y de la barra inferior.
 */
export function MiniPlayer({ playback, actions, mode, onOpen, style }: { playback: Store<Playback>; actions: PlayerActions; mode: 'web' | 'docked'; onOpen: () => void; style?: ViewStyle }) {
  const p = useStore(playback), ui = useStore(actions.ui);
  const rect: Rect | null | undefined = mode === 'web' ? ui.bar : undefined;
  const waiting = useDelayed(p.buffering && p.playing);
  const song = p.items[p.index];
  if (!song || rect === null) return null;
  const duration = p.duration || song.duracionSegundos || 0;
  const ratio = duration > 0 ? Math.max(0, Math.min(1, p.time / duration)) : 0;
  const fav = ui.songId === song.id && ui.fav, web = actions.webReady();
  const place: ViewStyle | undefined = rect ? { position: 'absolute', top: rect.top, left: rect.left, width: rect.width, height: rect.height } : undefined;
  return <View style={[s.bar, place, !rect && s.docked, style]}>
    <Pressable style={s.main} onPress={onOpen} accessibilityRole="button" accessibilityLabel={`${song.nombre}, ${song.artista}. Abrir reproductor`}>
      <Cover uri={coverUrl(actions.server, song.portadaUrl, 128)} size={44} radius={8} />
      <View style={s.text}>
        <Text style={s.title} numberOfLines={1} maxFontSizeMultiplier={1.25}>{song.nombre}</Text>
        <Text style={s.artist} numberOfLines={1} maxFontSizeMultiplier={1.25}>{song.artista}</Text>
      </View>
    </Pressable>
    {web && <Pressable onPress={() => actions.web('fav')} style={s.btn} hitSlop={4} accessibilityRole="button" accessibilityLabel={fav ? 'Quitar de Me gusta' : 'Me gusta'}>
      <Icon name={fav ? 'heartFill' : 'heart'} size={22} color={fav ? '#ff6b8b' : '#fff'} />
    </Pressable>}
    <Pressable onPress={p.playing ? actions.pause : actions.play} style={s.btn} hitSlop={4} accessibilityRole="button" accessibilityLabel={p.playing ? 'Pausar' : 'Reproducir'}>
      {waiting ? <ActivityIndicator color="#fff" /> : <Icon name={p.playing ? 'pause' : 'play'} size={24} color="#fff" />}
    </Pressable>
    <Pressable onPress={actions.next} style={s.btn} hitSlop={4} accessibilityRole="button" accessibilityLabel="Siguiente">
      <Icon name="fwd" size={24} color="#fff" />
    </Pressable>
    <View style={s.track} pointerEvents="none"><View style={[s.fill, { width: `${ratio * 100}%` }]} /></View>
  </View>;
}

const s = StyleSheet.create({
  bar: { flexDirection: 'row', alignItems: 'center', paddingLeft: 8, paddingRight: 4, borderRadius: 12, overflow: 'hidden', backgroundColor: '#24242c' },
  docked: { height: 64, marginHorizontal: 8, marginBottom: 8 },
  main: { flex: 1, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: 10, alignSelf: 'stretch' },
  text: { flex: 1, minWidth: 0 },
  title: { color: '#fafafa', fontSize: 15, fontWeight: '700' },
  artist: { color: '#a3a3ad', fontSize: 13, marginTop: 1 },
  btn: { width: 42, height: 42, alignItems: 'center', justifyContent: 'center' },
  track: { position: 'absolute', left: 10, right: 10, bottom: 0, height: 2, borderRadius: 1, backgroundColor: 'rgba(255,255,255,.14)' },
  fill: { height: 2, backgroundColor: '#fff', borderRadius: 1 },
});
