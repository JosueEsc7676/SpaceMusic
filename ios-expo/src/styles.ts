import { StyleSheet } from 'react-native';
export const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' }, web: { flex: 1, backgroundColor: '#000' },
  loading: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: '#000', alignItems: 'center', justifyContent: 'center' },
  logo: { width: 86, height: 86, borderRadius: 22 }, brand: { color: '#fafafa', fontSize: 28, fontWeight: '800', marginTop: 18, letterSpacing: -1 },
  progress: { width: 160, height: 3, backgroundColor: '#1b1b21', marginTop: 24, borderRadius: 2, overflow: 'hidden' }, progressFill: { height: 3, backgroundColor: '#b4a5ff' },
  link: { color: '#b4a5ff', fontSize: 14, fontWeight: '600', paddingVertical: 12 },
  panel: { flex: 1, padding: 24 }, header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 16 },
  title: { color: '#fafafa', fontSize: 23, fontWeight: '800', letterSpacing: -0.7, flexShrink: 1 }, text: { color: '#fafafa', fontSize: 15 }, muted: { color: '#a3a3ad', fontSize: 14, lineHeight: 21 },
  button: { backgroundColor: '#f5f5f7', borderRadius: 24, padding: 15, alignItems: 'center', marginTop: 20 }, buttonText: { color: '#000', fontWeight: '700', fontSize: 15 },
  secondary: { paddingVertical: 18, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#242428' },
  toast: { position: 'absolute', bottom: 24, left: 20, right: 20, backgroundColor: '#242428', padding: 16, borderRadius: 16 },
  song: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 62 }, art: { width: 52, height: 52, backgroundColor: '#1b1b21', borderRadius: 8, alignItems: 'center', justifyContent: 'center' }, songTitle: { color: '#fafafa', fontSize: 15, fontWeight: '600' },
  settingsShortcut: { position: 'absolute', width: 64, height: 12, alignSelf: 'center', bottom: 0, justifyContent: 'center', alignItems: 'center' }, settingsGrip: { width: 28, height: 3, borderRadius: 2, backgroundColor: '#64646f' },
  backdrop: { flex: 1, backgroundColor: '#0008', justifyContent: 'flex-end' }, sheet: { backgroundColor: '#121216', padding: 24, borderTopLeftRadius: 24, borderTopRightRadius: 24 },
  input: { color: '#fafafa', backgroundColor: '#1b1b21', borderRadius: 12, padding: 16, fontSize: 15, marginTop: 16 }, footnote: { color: '#64646f', fontSize: 12, lineHeight: 18, marginTop: 20 },
});
