// 下载本项目所需的 Java 依赖到 java-port/lib（无需 Maven/Gradle）
//
// 说明：JavaFX 11+ 在 Maven Central 上的无分类器 jar 只是空壳（约 302 字节），
// 真正含类与原生库的是 -win 分类器 jar，因此这里只取 -win。
//
// 用法: node tools/fetch-deps.mjs
import { mkdir, writeFile, stat } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const JFX_VERSION = process.argv[2] || '21.0.5';
const JACKSON_VERSION = '2.17.2';
const here = dirname(fileURLToPath(import.meta.url));
const libDir = join(here, '..', 'lib');

const JFX = ['base', 'graphics', 'controls', 'media', 'web', 'swing'].map(m => ({
  name: `javafx-${m}-${JFX_VERSION}-win.jar`,
  min: 50 * 1024,
  url: `https://repo1.maven.org/maven2/org/openjfx/javafx-${m}/${JFX_VERSION}/javafx-${m}-${JFX_VERSION}-win.jar`
}));

const JACKSON = [
  ['jackson-core', 400 * 1024],
  ['jackson-databind', 1200 * 1024],
  ['jackson-annotations', 50 * 1024]
].map(([a, min]) => ({
  name: `${a}-${JACKSON_VERSION}.jar`,
  min,
  url: `https://repo1.maven.org/maven2/com/fasterxml/jackson/core/${a}/${JACKSON_VERSION}/${a}-${JACKSON_VERSION}.jar`
}));

async function download(url, dest, min) {
  for (let attempt = 1; attempt <= 4; attempt++) {
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length < min) throw new Error(`响应过小 (${buf.length} bytes)`);
      await writeFile(dest, buf);
      return buf.length;
    } catch (e) {
      if (attempt === 4) throw new Error(`${url}: ${e.message}`);
      console.warn(`  重试 ${attempt}/4: ${e.message}`);
      await new Promise(r => setTimeout(r, 800 * attempt));
    }
  }
}

await mkdir(libDir, { recursive: true });

for (const dep of [...JFX, ...JACKSON]) {
  const dest = join(libDir, dep.name);
  try {
    const s = await stat(dest);
    if (s.size >= dep.min) {
      console.log(`跳过 ${dep.name} (已存在 ${(s.size / 1048576).toFixed(1)} MB)`);
      continue;
    }
  } catch { /* 不存在则下载 */ }
  process.stdout.write(`下载 ${dep.name} ... `);
  const size = await download(dep.url, dest, dep.min);
  console.log(`${(size / 1048576).toFixed(1)} MB`);
}

console.log(`\n完成，依赖已就绪: ${libDir}`);
