// 复习核心逻辑（纯函数，无 DOM 依赖，可在浏览器和 Node 中使用）
//
// 数据结构（localStorage 键 learnedDays）：
// {
//   currentDay: 20,            // 今天是"第 20 天"（每打开一次自然日 +1）
//   lastDate: "2026-10-08",    // 上次打开软件的日期，仅用来判断"是不是还是同一天"
//   days: { "1": ["word", ...], "21": [...] },  // 每个值是"计划在第 N 天复习"的词
//   steps: { "word": 2 },      // 这个词已经连续答对几次（决定下次间隔）
//   reviewedDay: 20,
//   reviewedWords: ["word"]    // 今天已经复习过的词（每天清空，避免同一天重复出题）
// }
// 一个单词同一时间只存在于一个"天"的数组里；移动单词 = 在各天数组间搬运。
// 数组号 ≤ currentDay-1 的才是"到期"，> currentDay 的是排给未来的。
(function (root) {
  const STORE_KEY = 'learnedDays';
  const LEGACY_KEY = 'learnedWords';
  const PER_SIDE = 3;      // 每轮：最近一天取 3 个 + 最远一天取 3 个
  const ROUNDS_PER_CYCLE = 3; // 三轮为一个循环
  // 答对后的间隔（天）：连续答对 1 次 → 2 天后；2 次 → 4 天后；…… 最多 60 天
  const INTERVALS = [2, 4, 7, 15, 30, 60];

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
    return { currentDay: dates.length, lastDate: dates[dates.length - 1], days, steps: {} };
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
    const cd = Number(s.currentDay);
    if (!Number.isInteger(cd) || cd < 1) return null;
    const reviewedWords = Array.isArray(s.reviewedWords)
      ? s.reviewedWords.filter(w => typeof w === 'string') : [];
    return {
      currentDay: cd, lastDate: String(s.lastDate || ''), days, steps,
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

  // 学习新词（造句环节）：单词记入"今天"
  function markLearned(state, words) {
    for (const w of words) placeWord(state, w, state.currentDay);
  }

  function learnedSet(state) {
    const set = new Set();
    for (const arr of Object.values(state.days)) arr.forEach(w => set.add(w));
    return set;
  }

  // 可复习的天：第 1 天 ~ 今天的前一天（今天刚学的词明天才复习）
  function unreviewedIn(state, day, reviewed, exclude) {
    return (state.days[day] || []).filter(w => !reviewed.has(w) && !(exclude && exclude.has(w)));
  }

  // 实际最近的、仍有未复习单词的一天
  function nearestDay(state, reviewed, exclude) {
    for (let d = state.currentDay - 1; d >= 1; d--) {
      if (unreviewedIn(state, d, reviewed, exclude).length > 0) return d;
    }
    return null;
  }

  // 实际最远的、仍有未复习单词的一天
  function farthestDay(state, reviewed, exclude) {
    for (let d = 1; d <= state.currentDay - 1; d++) {
      if (unreviewedIn(state, d, reviewed, exclude).length > 0) return d;
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

  // 选一轮的词：最近一天最多 3 个 + 最远一天最多 3 个（不够有几个选几个）
  // reviewed：本次打开软件期间已经复习过的词（不会重复选）
  function pickRound(state, reviewed, rng) {
    rng = rng || Math.random;
    const picks = [];
    const chosen = new Set();

    const nd = nearestDay(state, reviewed, chosen);
    if (nd !== null) {
      for (const w of sample(unreviewedIn(state, nd, reviewed, chosen), PER_SIDE, rng)) {
        picks.push({ word: w, day: nd, side: 'near' });
        chosen.add(w);
      }
    }
    const fd = farthestDay(state, reviewed, chosen);
    if (fd !== null) {
      for (const w of sample(unreviewedIn(state, fd, reviewed, chosen), PER_SIDE, rng)) {
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

  // 还剩多少个本次未复习的可复习单词
  function remainingCount(state, reviewed) {
    let n = 0;
    for (let d = 1; d <= state.currentDay - 1; d++) n += unreviewedIn(state, d, reviewed).length;
    return n;
  }

  const api = {
    STORE_KEY, PER_SIDE, ROUNDS_PER_CYCLE, INTERVALS,
    localDateStr, load, save, reviewedSet, markReviewed, findDay, placeWord, markLearned, learnedSet,
    nearestDay, farthestDay, pickRound, applyResult, remainingCount, migrateLegacy
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ReviewCore = api;
})(typeof window !== 'undefined' ? window : globalThis);
