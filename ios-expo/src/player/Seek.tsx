import React, { useMemo, useRef, useState } from 'react';
import { PanResponder, StyleSheet, Text, View } from 'react-native';
import { fmtTime } from './store';

const clamp = (v: number) => Math.max(0, Math.min(1, v));

/** Barra de posición: arrastre con área táctil de 44 pt, tiempos tabulares y control por VoiceOver. */
export function Seek({ time, duration, onSeek }: { time: number; duration: number; onSeek: (time: number) => void }) {
  const [drag, setDrag] = useState<number | null>(null);
  const width = useRef(1), start = useRef(0), latest = useRef({ duration, onSeek, drag: null as number | null });
  latest.current.duration = duration; latest.current.onSeek = onSeek;
  const set = (v: number | null) => { latest.current.drag = v; setDrag(v); };
  const responder = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => latest.current.duration > 0,
    onMoveShouldSetPanResponder: () => latest.current.duration > 0,
    onPanResponderTerminationRequest: () => false,
    onShouldBlockNativeResponder: () => true,
    onPanResponderGrant: e => { start.current = e.nativeEvent.locationX; set(clamp(start.current / width.current)); },
    onPanResponderMove: (_, g) => set(clamp((start.current + g.dx) / width.current)),
    onPanResponderRelease: () => { const v = latest.current.drag; if (v !== null) latest.current.onSeek(v * latest.current.duration); set(null); },
    onPanResponderTerminate: () => set(null),
  }), []);
  const ratio = drag ?? (duration > 0 ? clamp(time / duration) : 0);
  const shown = drag !== null ? drag * duration : time;
  return <View>
    <View style={s.hit} {...responder.panHandlers} onLayout={e => { width.current = Math.max(1, e.nativeEvent.layout.width); }}
      accessible accessibilityRole="adjustable" accessibilityLabel="Posición" accessibilityValue={{ text: `${fmtTime(time)} de ${fmtTime(duration)}` }}
      accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
      onAccessibilityAction={e => onSeek(Math.max(0, Math.min(duration, time + (e.nativeEvent.actionName === 'increment' ? 10 : -10))))}>
      <View style={[s.rail, drag !== null && s.railActive]} pointerEvents="none">
        <View style={[s.fill, { width: `${ratio * 100}%` }]} />
      </View>
      <View pointerEvents="none" style={[s.knob, drag !== null && s.knobActive, { left: `${ratio * 100}%` }]} />
    </View>
    <View style={s.times} pointerEvents="none">
      <Text style={s.time} maxFontSizeMultiplier={1.3}>{fmtTime(shown)}</Text>
      <Text style={s.time} maxFontSizeMultiplier={1.3}>-{fmtTime(Math.max(0, duration - shown))}</Text>
    </View>
  </View>;
}

const s = StyleSheet.create({
  hit: { height: 32, justifyContent: 'center' },
  rail: { height: 5, borderRadius: 3, backgroundColor: 'rgba(255,255,255,.22)', overflow: 'hidden' },
  railActive: { height: 8, borderRadius: 4 },
  fill: { height: '100%', backgroundColor: 'rgba(255,255,255,.92)' },
  knob: { position: 'absolute', width: 14, height: 14, marginLeft: -7, borderRadius: 7, backgroundColor: '#fff', top: 9 },
  knobActive: { width: 22, height: 22, marginLeft: -11, borderRadius: 11, top: 5 },
  times: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 2 },
  time: { color: 'rgba(255,255,255,.62)', fontSize: 12.5, fontVariant: ['tabular-nums'], fontWeight: '500' },
});
