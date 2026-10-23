// 生成"对拍"数据：直接用项目原有 review-core.js 跑一遍固定场景，输出 JSON。
// Java 侧的 SelfTest 会用同样的场景重放并逐字段比对，确保移植没有走样。
// 用法: node tools/parity-dump.mjs > ../out/parity.json
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const core = require('../app/review-core.js');

const memory = {
  data: {},
  getItem(k) { return Object.prototype.hasOwnProperty.call(this.data, k) ? this.data[k] : null; },
  setItem(k, v) { this.data[k] = String(v); }
};

const rngSeq = [0.2, 0.8, 0.5, 0.1];
let ri = 0;
const rng = () => rngSeq[(ri++) % rngSeq.length];

function stateOf(s) {
  const days = {};
  Object.keys(s.days).sort((a, b) => Number(a) - Number(b)).forEach(k => { days[k] = s.days[k].slice(); });
  return { currentDay: s.currentDay, lastDate: s.lastDate, days };
}

let s = core.load(memory, '2026-01-01');
const load1 = stateOf(s);

core.markLearned(s, ['alpha', 'beta', 'gamma']);
const learned = stateOf(s);

s = core.load(memory, '2026-01-02');
const load2 = stateOf(s);

core.markLearned(s, ['delta']);
s = core.load(memory, '2026-01-02');

const moves = [];
moves.push(core.applyResult(s, 'alpha', true));
moves.push(core.applyResult(s, 'beta', false));

s = core.load(memory, '2026-01-03');
moves.push(core.applyResult(s, 'delta', true));

const remaining = core.remainingCount(s, new Set());
const picks = core.pickRound(s, new Set(), rng);

const out = {
  load1,
  learned,
  load2,
  moves,
  remaining,
  picks,
  finalDays: stateOf(s).days
};

process.stdout.write(JSON.stringify(out));
