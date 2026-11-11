import type { PlayerUi, WebCommand } from '../protocol';
import type { Store } from './store';

/**
 * Lo que la interfaz nativa del reproductor puede hacer.
 * - Reproducir, pausar, buscar y saltar van directo al motor nativo (respuesta inmediata, también sin conexión).
 * - Las reglas de la cola (aleatorio, repetir, radio, quitar, mover) y Me gusta siguen en la web, que es la fuente
 *   de verdad de la cola y la sincroniza con el motor: así nunca hay dos colas distintas.
 */
export type PlayerActions = {
  server: string;
  ui: Store<PlayerUi>;
  token(): string | null;
  webReady(): boolean;
  isDownloaded(id: number): boolean;
  play(): void; pause(): void; seek(time: number): void;
  next(): void; previous(): void; jump(index: number): void;
  web(command: WebCommand, arg?: unknown): void;
};
