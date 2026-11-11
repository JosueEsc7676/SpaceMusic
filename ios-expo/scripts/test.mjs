import { transform } from 'esbuild';
import { readdir, readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
const files = (await readdir(new URL('../tests/', import.meta.url))).filter(f => f.endsWith('.test.ts'));
for (const dir of ['src', 'src/player', 'tests', 'web', 'shared']) await mkdir(new URL(`../.test-build/${dir}/`, import.meta.url), { recursive: true });
const sources = ['src/protocol.ts', 'src/bootstrap.ts', 'src/audio.ts', 'src/player/lrc.ts', 'src/player/store.ts', ...['player', 'util', 'api', 'icons'].map(n => `shared/${n}.js`), ...((await readdir(new URL('../tests/', import.meta.url))).filter(f => f.endsWith('.ts')).map(f => `tests/${f}`))];
for (const file of sources) {
  const input = file.startsWith('shared/') ? new URL(`../../src/main/resources/static/js/${file.slice(7)}`, import.meta.url) : new URL(`../${file}`, import.meta.url);
  let source = await readFile(input, 'utf8');
  if (file === 'src/audio.ts') source = source.replace(/from ['"](?:expo-audio|react-native)['"]/g, "from '../tests/native-port'");
  const { code } = await transform(source, { loader: 'ts', format: 'esm', target: 'node22' });
  const linked = code.replace(/from (["'])(\.[^"']+)\1/g, (_, quote, path) => `from ${quote}${path.replace(/\.(?:ts|js)$/, '')}.mjs${quote}`);
  await writeFile(new URL(`../.test-build/${file.replace(/\.(?:ts|js)$/, '.mjs')}`, import.meta.url), linked);
}
await copyFile(new URL('../web/bridge.ts', import.meta.url), new URL('../.test-build/web/bridge.ts', import.meta.url));
const result = spawnSync(process.execPath, ['--test', ...files.map(f => `.test-build/tests/${f.replace(/\.ts$/, '.mjs')}`)], { stdio: 'inherit' });
process.exitCode = result.status ?? 1;
