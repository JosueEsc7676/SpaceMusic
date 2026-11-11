import React, { memo } from 'react';
import { SvgXml } from 'react-native-svg';
import { paths, type IconName } from './icon-paths';

/** Mismos íconos que la web (.icon: trazo 2, extremos redondeados), dibujados como vectores nativos. */
export const Icon = memo(function Icon({ name, size = 24, color = '#fafafa', stroke = 2 }: { name: IconName; size?: number; color?: string; stroke?: number }) {
  const xml = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${stroke}" stroke-linecap="round" stroke-linejoin="round">${paths[name]}</svg>`;
  return <SvgXml xml={xml} width={size} height={size} color={color} />;
});
export type { IconName };
