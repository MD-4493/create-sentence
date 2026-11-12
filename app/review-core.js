// 复习核心逻辑（纯函数，无 DOM 依赖，可在浏览器和 Node 中使用）
//
// 数据结构（localStorage 键 learnedDays）：
// {
//   currentDay: 20,            // 今天是"第 20 天"（每打开一次自然日 +1）
//   lastDate: "2026-10-08",    // 上次打开软件的日期，仅用来判断"是不是还是同一天"
//   days: { "1": ["word", ...], "21": [...] },  // 每个值是"计划在第 N 天复习"的词
//   sources: { "word": "cefr-a1" },             // 这个词是从哪个词库学的（复习时按词库筛）
//   steps: { "word": 2 },      // 这个词已经连续答对几次（决定下次间隔）
//   reviewedDay: 20,
//   reviewedWords: ["word"]    // 今天已经复习过的词（每天清空，避免同一天重复出题）
// }
// 一个单词同一时间只存在于一个"天"的数组里；移动单词 = 在各天数组间搬运。
// 数组号 ≤ currentDay-1 的才是"到期"，> currentDay 的是排给未来的。
(function (root) {
  const STORE_KEY = 'learnedDays';
  const LEGACY_KEY = 'learnedWords';
  const PER_SIDE = 5;      // 每轮：最近一天取 5 个 + 最远一天取 5 个 → 每轮最多 10 题
  const ROUNDS_PER_CYCLE = 3; // 三轮为一个循环（最多 30 题）
  // 答对后的间隔（天）：连续答对 1 次 → 2 天后；2 次 → 4 天后；…… 最多 60 天
  const INTERVALS = [2, 4, 7, 15, 30, 60];

  // ---------- Java 运行时委托 ----------
  // 在 JavaFX WebView 里（有 __javaBridge 时），所有调度计算都交给 Java 的 ReviewCore；
  // 下面的 JS 实现只在没有桥接的环境（Node 对拍、浏览器调试）里作为参考实现使用。
  let javaEngine = false;

  function javaBridge() {
    if (typeof window === 'undefined') return null;
    const bridge = window.__javaBridge;
    return bridge && typeof bridge.reviewOp === 'function' ? bridge : null;
  }

  function callJava(op, payload) {
    const bridge = javaBridge();
    if (!bridge) return null;
    try {
      const raw = bridge.reviewOp(op, JSON.stringify(payload));
      if (!raw) return null;
      const parsed = JSON.parse(String(raw));
      if (!javaEngine) {
        javaEngine = true;
        try { window.__reviewEngine = 'java'; } catch (e) { /* 忽略 */ }
      }
      return parsed;
    } catch (e) {
      console.warn('Java 复习调度调用失败，回退到 JS 实现:', e);
      return null;
    }
  }

  function replaceState(state, next) {
    if (!next) return;
    for (const key of Object.keys(state)) delete state[key];
    Object.assign(state, next);
  }

  function bankKey(state, word) {
    const id = state.sources[word];
    return (!id || id === 'unknown') ? '' : id;
  }

  function allowFromBanks(state, allowedBanks) {
    if (!Array.isArray(allowedBanks) || allowedBanks.length === 0) return null;
    return (w) => allowedBanks.includes(bankKey(state, w));
  }

  function localDateStr(d) {
    d = d || new Date();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${m}-${day}`;
  }

  // 旧版 learnedWords（带 lastSeen 时间戳）迁移到"第 N 天"结构
  function migrateLegacy(legacy) {
    const entries = Object.entries(legacy || {}).filter(([, v]) => v && v.lastSeen);
    if (entries.length === 0) return null;
    const dateOf = (iso) => localDateStr(new Date(iso));
    const dates = [...new Set(entries.map(([, v]) => dateOf(v.lastSeen)))].sort();
    const idx = new Map(dates.map((d, i) => [d, i + 1]));
    const days = {};
    for (const [w, v] of entries) {
      const n = idx.get(dateOf(v.lastSeen));
      (days[n] = days[n] || []).push(w);
    }
    return { currentDay: dates.length, lastDate: dates[dates.length - 1], days, steps: {}, sources: {} };
  }

  function sanitize(s) {
    if (!s || typeof s !== 'object') return null;
    const days = {};
    for (const [k, arr] of Object.entries(s.days || {})) {
      if (Array.isArray(arr)) days[k] = arr.filter(w => typeof w === 'string');
    }
    const steps = {};
    for (const [w, n] of Object.entries(s.steps || {})) {
      const v = Number(n);
      if (typeof w === 'string' && Number.isInteger(v) && v >= 0) steps[w] = v;
    }
    const sources = {};
    for (const [w, id] of Object.entries(s.sources || {})) {
      if (typeof id === 'string') sources[w] = id;
    }
    const cd = Number(s.currentDay);
    if (!Number.isInteger(cd) || cd < 1) return null;
    const reviewedWords = Array.isArray(s.reviewedWords)
      ? s.reviewedWords.filter(w => typeof w === 'string') : [];
    return {
      currentDay: cd, lastDate: String(s.lastDate || ''), days, steps, sources,
      reviewedDay: Number(s.reviewedDay) || 0, reviewedWords
    };
  }

  // 打开软件时调用：读取状态；如果已经不是同一天，则"新的一天"+1
  function load(storage, today) {
    today = today || localDateStr();
    let state = null;
    try { state = sanitize(JSON.parse(storage.getItem(STORE_KEY))); } catch (e) { state = null; }
    if (!state) {
      let legacy = null;
      try { legacy = JSON.parse(storage.getItem(LEGACY_KEY) || 'null'); } catch (e) { legacy = null; }
      state = migrateLegacy(legacy) || { currentDay: 1, lastDate: today, days: {} };
    }
    if (state.lastDate !== today) {
      state.currentDay += 1;
      state.lastDate = today;
    }
    // "已复习"记录只对当天有效：新的一天自动清空；同一天内重复打开软件则保留
    if (!state.steps) state.steps = {};
    if (!state.sources) state.sources = {};
    if (state.reviewedDay !== state.currentDay || !Array.isArray(state.reviewedWords)) {
      state.reviewedDay = state.currentDay;
      state.reviewedWords = [];
    }
    save(storage, state);
    return state;
  }

  function save(storage, state) {
    storage.setItem(STORE_KEY, JSON.stringify(state));
  }

  // 今天已复习过的词（持久化，重开软件也不会再出现）
  function reviewedSet(state) {
    return new Set(state.reviewedWords || []);
  }

  function markReviewed(state, word) {
    const res = callJava('markReviewed', { state: state, word: word });
    if (res && res.state) {
      replaceState(state, res.state);
      return;
    }
    if (!state.reviewedWords.includes(word)) state.reviewedWords.push(word);
  }

  function findDay(state, word) {
    for (const [k, arr] of Object.entries(state.days)) {
      if (arr.includes(word)) return Number(k);
    }
    return null;
  }

  function removeWord(state, word) {
    for (const k of Object.keys(state.days)) {
      state.days[k] = state.days[k].filter(w => w !== word);
    }
  }

  // 把单词放进第 day 天（先从原来的天里拿走）
  function placeWord(state, word, day) {
    removeWord(state, word);
    (state.days[day] = state.days[day] || []).push(word);
  }

  // 学习新词（造句环节）：单词记入"今天"，并记下它是从哪个词库学的。
  // bankId 只有在有新词时才有意义；同一个词从别的词库再学一次会更新归属。
  function markLearned(state, words, bankId) {
    if (!state.sources) state.sources = {};
    const res = callJava('markLearned', { state: state, words: words, bankId: bankId || null });
    if (res && res.state) {
      replaceState(state, res.state);
      return;
    }
    for (const w of words) {
      placeWord(state, w, state.currentDay);
      if (bankId) state.sources[w] = String(bankId);
    }
  }

  function learnedSet(state) {
    const set = new Set();
    for (const arr of Object.values(state.days)) arr.forEach(w => set.add(w));
    return set;
  }

  // 可复习的天：第 1 天 ~ 今天的前一天（今天刚学的词明天才复习）
  // allow：可选过滤函数（按词库筛选时传入），返回 false 的词不参与本次复习。
  function unreviewedIn(state, day, reviewed, exclude, allow) {
    return (state.days[day] || []).filter(w =>
      !reviewed.has(w) && !(exclude && exclude.has(w)) && (!allow || allow(w)));
  }

  // 实际最近的、仍有未复习单词的一天
  function nearestDay(state, reviewed, exclude, allow) {
    for (let d = state.currentDay - 1; d >= 1; d--) {
      if (unreviewedIn(state, d, reviewed, exclude, allow).length > 0) return d;
    }
    return null;
  }

  // 实际最远的、仍有未复习单词的一天
  function farthestDay(state, reviewed, exclude, allow) {
    for (let d = 1; d <= state.currentDay - 1; d++) {
      if (unreviewedIn(state, d, reviewed, exclude, allow).length > 0) return d;
    }
    return null;
  }

  function sample(arr, n, rng) {
    const a = arr.slice();
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    return a.slice(0, n);
  }

  // 选一轮的词：最近一天最多 5 个 + 最远一天最多 5 个（不够有几个选几个）→ 每轮最多 10 题
  // reviewed：本次打开软件期间已经复习过的词（不会重复选）
  // allowedBanks：可选词库 id 数组；null/空数组表示不筛选
  function pickRound(state, reviewed, rng, allowedBanks) {
    const res = callJava('pickRound', { state: state, allowedBanks: allowedBanks || null });
    if (Array.isArray(res)) return res;

    rng = rng || Math.random;
    const allow = allowFromBanks(state, allowedBanks);
    const picks = [];
    const chosen = new Set();

    const nd = nearestDay(state, reviewed, chosen, allow);
    if (nd !== null) {
      for (const w of sample(unreviewedIn(state, nd, reviewed, chosen, allow), PER_SIDE, rng)) {
        picks.push({ word: w, day: nd, side: 'near' });
        chosen.add(w);
      }
    }
    const fd = farthestDay(state, reviewed, chosen, allow);
    if (fd !== null) {
      for (const w of sample(unreviewedIn(state, fd, reviewed, chosen, allow), PER_SIDE, rng)) {
        picks.push({ word: w, day: fd, side: 'far' });
        chosen.add(w);
      }
    }
    return picks;
  }

  // 答对：按"连续答对次数"把词排到未来（2 → 4 → 7 → 15 → 30 → 60 天后再见）；
  // 答错：排到明天重来一次，并把连续答对次数清零。
  // 返回 { from, to, interval }：from 是原来的计划日，to 是新的计划日，interval 是间隔天数。
  // 注意：第 N 天的桶要到第 N+1 天才到期（今天学的词明天才复习），所以 to = 今天 + interval - 1。
  function applyResult(state, word, correct) {
    const res = callJava('applyResult', { state: state, word: word, correct: !!correct });
    if (res && res.state) {
      replaceState(state, res.state);
      return { from: res.from, to: res.to, interval: res.interval };
    }
    const from = findDay(state, word);
    const step = state.steps[word] || 0;
    let to, interval;
    if (correct) {
      interval = INTERVALS[Math.min(step, INTERVALS.length - 1)];
      state.steps[word] = Math.min(step + 1, INTERVALS.length - 1);
    } else {
      interval = 1;
      state.steps[word] = 0;
    }
    to = state.currentDay + interval - 1;
    placeWord(state, word, to);
    return { from, to, interval };
  }

  // 还剩多少个本次未复习的可复习单词（allowedBanks 可按词库过滤）
  function remainingCount(state, reviewed, allowedBanks) {
    const res = callJava('remaining', { state: state, allowedBanks: allowedBanks || null });
    if (res && typeof res.count === 'number') return res.count;

    const allow = allowFromBanks(state, allowedBanks);
    let n = 0;
    for (let d = 1; d <= state.currentDay - 1; d++) {
      n += unreviewedIn(state, d, reviewed, null, allow).length;
    }
    return n;
  }

  // 每个词库有多少个"已到期、今天还没复习"的词，返回 Map<bankId, count>
  function dueCounts(state) {
    const res = callJava('dueCounts', { state: state });
    if (res && res.counts && typeof res.counts === 'object') {
      return new Map(Object.entries(res.counts));
    }
    const counts = new Map();
    for (let d = 1; d <= state.currentDay - 1; d++) {
      for (const w of (state.days[d] || [])) {
        if (state.reviewedWords.includes(w)) continue;
        const id = bankKey(state, w);
        counts.set(id, (counts.get(id) || 0) + 1);
      }
    }
    return counts;
  }

  const api = {
    STORE_KEY, PER_SIDE, ROUNDS_PER_CYCLE, INTERVALS,
    localDateStr, load, save, reviewedSet, markReviewed, findDay, placeWord, markLearned, learnedSet,
    nearestDay, farthestDay, pickRound, applyResult, remainingCount, dueCounts, migrateLegacy
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ReviewCore = api;
})(typeof window !== 'undefined' ? window : globalThis);
