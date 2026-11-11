import { transform } from 'esbuild';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
const source = await readFile(new URL('../web/bridge.ts', import.meta.url), 'utf8');
const { code } = await transform(source, { loader: 'ts', target: 'safari16.4', format: 'iife', minify: true });
await mkdir(new URL('../src/generated/', import.meta.url), { recursive: true });
await writeFile(new URL('../src/generated/bridge.ts', import.meta.url), `// Generado por scripts/build-bridge.mjs.\nexport const bridgeScript = ${JSON.stringify(code + '\ntrue;')};\n`);
