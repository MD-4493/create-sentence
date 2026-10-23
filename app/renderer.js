// 渲染进程：UI 交互逻辑 + 词库管理 + 学习记录
let currentBank = null;
let currentWords = [];
let selectedWords = new Set();
let userPickedKeywords = [];

const $ = (id) => document.getElementById(id);

// ============ 学习记录（按"第 N 天"分桶，见 review-core.js） ============
const state = ReviewCore.load(localStorage);

function markLearned(words) {
  ReviewCore.markLearned(state, words);
  ReviewCore.save(localStorage, state);
}

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function updateDayBadge() {
  $('dayBadge').textContent = `今天是第 ${state.currentDay} 天`;
}

// 忙碌按钮：JavaFX WebView（WebKit）在按钮"变窄"时会把旧宽度的底色留在原处，
// 而 disabled 的透明度变化会加重这个问题。做法是忙碌期间锁住按钮宽度，
// 让文案变短也不会改变按钮尺寸；styles.css 里按钮也只过渡颜色、不过渡 opacity。
async function setBusy(btn, busyLabel, task) {
  const idleLabel = btn.textContent;
  const width = btn.getBoundingClientRect().width;
  btn.style.minWidth = `${width}px`;
  btn.disabled = true;
  btn.textContent = busyLabel;
  try {
    return await task();
  } finally {
    btn.textContent = idleLabel;
    btn.disabled = false;
    btn.style.minWidth = '';
  }
}

// ============ UI 工具 ============
function showSection(id) {
  $(id).classList.remove('hidden');
  $(id).scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function hideSection(id) {
  $(id).classList.add('hidden');
}

// 把 **...** 变成高亮：默认是英文例句里的目标词，cls 用来给中文对应义项换颜色。
function markBold(text, cls) {
  return escapeHtml(text).replace(/\*\*([^*]+)\*\*/g, (m, inner) =>
    cls ? `<span class="${cls}">${inner}</span>` : `<strong>${inner}</strong>`);
}

// 旧格式（整段文本）的兜底渲染，保证模型没按 JSON 返回时页面也不会空。
function legacySentencesHtml(text) {
  return text
    .split('\n')
    .map(line => {
      const trimmed = line.trim();
      if (!trimmed) return '';
      if (trimmed === '---') return '<hr>';
      return `<div class="line">${markBold(trimmed)}</div>`;
    })
    .join('');
}

// 词卡：优先用本地词典（TOEFL_abridged）的多条义项，没有词典条目时退回模型给的 pos/meaning。
function wordCardHtml(s) {
  const senses = Array.isArray(s.senses) && s.senses.length
    ? s.senses
    : (s.pos || s.meaning) ? [{ pos: s.pos || '', meaning: s.meaning || '' }] : [];

  const head = [
    s.word ? `<span class="wc-word">${escapeHtml(s.word)}</span>` : '',
    s.ipa ? `<span class="wc-ipa">${escapeHtml(s.ipa)}</span>` : ''
  ].join('');

  const body = senses
    .map(x => `<span class="wc-sense">${x.pos ? `<span class="wc-pos">${escapeHtml(x.pos)}</span>` : ''
      }${escapeHtml(x.meaning || '')}</span>`)
    .join('');

  return `<div class="word-card">`
    + (head ? `<div class="wc-head">${head}</div>` : '')
    + (body ? `<div class="wc-senses">${body}</div>` : '')
    + `</div>`;
}

// 例句卡片：英文（目标词高亮）+ 中文（对应义项标蓝）+ 词卡（词性/音标/释义）
function renderSentences(data) {
  const list = $('sentencesList');

  if (!Array.isArray(data)) {
    list.innerHTML = legacySentencesHtml(String(data));
    return;
  }

  list.innerHTML = data.map(s => `
    <div class="sentence-item">
      <div class="sentence-en">${markBold(s.en || '')}</div>
      <div class="sentence-zh">${markBold(s.zh || '', 'zh-hit')}</div>
      ${wordCardHtml(s)}
    </div>`).join('');
}

function renderFeedback(text) {
  const html = text
    .split('\n')
    .map(line => {
      const trimmed = line.trim();
      if (!trimmed) return '';
      const titleMatch = trimmed.match(/^【(.+?)】$/);
      if (titleMatch) {
        return `<h3 class="feedback-title">${titleMatch[1]}</h3>`;
      }
      const processed = trimmed.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
      return `<div class="line">${processed}</div>`;
    })
    .join('');
  $('feedbackContent').innerHTML = html;
}

// ============ 词库加载 ============
async function loadBanks(preferId) {
  try {
    const data = await window.api.getWordBanks();
    const select = $('bankSelect');
    select.innerHTML = data.banks.map(b =>
      `<option value="${escapeHtml(b.id)}">${escapeHtml(b.name)}</option>`
    ).join('');
    if (data.banks.length > 0) {
      const target = data.banks.find(b => b.id === preferId) || data.banks[0];
      select.value = target.id;
      await loadBank(target.id);
    }
  } catch (e) {
    alert('加载词库失败: ' + e.message);
    console.error(e);
  }
}

async function loadBank(bankId) {
  try {
    const data = await window.api.getWords(bankId);
    currentBank = data;
    currentWords = data.words;
    selectedWords.clear();

    $('bankInfo').textContent = `共 ${data.words.length} 个词`;
    $('deleteBankBlock').classList.toggle('hidden', !String(bankId).startsWith('custom:'));
    renderWordList($('wordSearch').value);
    updateSelectedCount();
  } catch (e) {
    alert('加载词库失败: ' + e.message);
    console.error(e);
  }
}

// ============ 单词列表渲染 ============
function renderWordList(filter) {
  const learned = ReviewCore.learnedSet(state);
  const list = $('wordList');
  const f = (filter || '').toLowerCase().trim();
  const filtered = currentWords.filter(w => w.toLowerCase().includes(f));

  if (filtered.length === 0) {
    list.innerHTML = '<div class="empty">没找到匹配单词</div>';
    updateLearnedStats(0, 0);
    return;
  }

  const learnedInBank = filtered.filter(w => learned.has(w)).length;
  updateLearnedStats(learnedInBank, filtered.length);

  list.innerHTML = filtered.map(w => {
    const isLearned = learned.has(w);
    const isSelected = selectedWords.has(w);
    return `
      <label class="word-item ${isLearned ? 'learned' : ''} ${isSelected ? 'selected' : ''}" title="${escapeHtml(w)}">
        <input type="checkbox" data-word="${escapeHtml(w)}" ${isSelected ? 'checked' : ''}>
        <span class="word-text">${escapeHtml(w)}</span>
        ${isLearned ? '<span class="learned-mark" title="已学">✓</span>' : ''}
      </label>
    `;
  }).join('');

  list.querySelectorAll('input[type="checkbox"]').forEach(cb => {
    cb.addEventListener('change', (e) => {
      const w = e.target.dataset.word;
      if (e.target.checked) selectedWords.add(w);
      else selectedWords.delete(w);
      e.target.parentElement.classList.toggle('selected', e.target.checked);
      updateSelectedCount();
    });
  });
}

function updateLearnedStats(learnedCount, totalCount) {
  $('learnedStats').textContent = `本列表已学 ${learnedCount} / ${totalCount}`;
}

function updateSelectedCount() {
  $('selectedCount').textContent = selectedWords.size;
}

// ============ 事件绑定 ============
$('bankSelect').addEventListener('change', (e) => {
  loadBank(e.target.value);
});

$('wordSearch').addEventListener('input', (e) => {
  renderWordList(e.target.value);
});

$('selectAllBtn').addEventListener('click', () => {
  document.querySelectorAll('#wordList input[type="checkbox"]').forEach(cb => {
    cb.checked = true;
    selectedWords.add(cb.dataset.word);
    cb.parentElement.classList.add('selected');
  });
  updateSelectedCount();
});

$('deselectAllBtn').addEventListener('click', () => {
  document.querySelectorAll('#wordList input[type="checkbox"]').forEach(cb => {
    cb.checked = false;
    cb.parentElement.classList.remove('selected');
  });
  selectedWords.clear();
  updateSelectedCount();
});

$('randomPickBtn').addEventListener('click', () => {
  const candidates = currentWords.filter(w => !selectedWords.has(w));
  const shuffled = [...candidates].sort(() => Math.random() - 0.5);
  const pick = shuffled.slice(0, 5);
  for (const w of pick) {
    selectedWords.add(w);
  }
  renderWordList($('wordSearch').value);
  updateSelectedCount();
});

$('generateBtn').addEventListener('click', async () => {
  if (selectedWords.size === 0) {
    alert('请先选词');
    return;
  }

  const words = Array.from(selectedWords);
  userPickedKeywords = words;

  try {
    await setBusy($('generateBtn'), '生成中…', async () => {
      const result = await window.api.generateSentences(words);
      renderSentences(result);

      showSection('step2');
      showSection('step3');
      $('keywordsList').textContent = words.join(' · ');

      $('userSentence').value = '';
      hideSection('step4');
    });
  } catch (e) {
    alert('生成失败：' + e.message);
    console.error(e);
  }
});

$('correctBtn').addEventListener('click', async () => {
  const sentence = $('userSentence').value.trim();

  if (!sentence) {
    alert('请先输入一个句子');
    return;
  }

  try {
    await setBusy($('correctBtn'), '检查中…', async () => {
      const feedback = await window.api.correctSentence(sentence, userPickedKeywords);
      renderFeedback(feedback);
      showSection('step4');

      // 标记这些词为"已学"
      markLearned(userPickedKeywords);
      // 重新渲染单词列表（更新已学标记）
      renderWordList($('wordSearch').value);
    });
  } catch (e) {
    alert('检查失败：' + e.message);
    console.error(e);
  }
});

// 快捷键
$('wordSearch').addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    $('generateBtn').click();
  }
});
$('userSentence').addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    $('correctBtn').click();
  }
});

// ============ 复习板块 ============
const REVIEW_LETTERS = ['A', 'B', 'C', 'D'];
const review = {
  reviewed: ReviewCore.reviewedSet(state),   // 今天已复习过的词（存在 localStorage，重开软件也保留）
  round: 0,              // 当前循环中的第几轮（1~3）
  picks: [],
  queue: [],             // 当前轮的题目
  idx: 0,
  cycleCorrect: 0,
  cycleTotal: 0
};

function shuffle(arr) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function reviewShow(part) {
  for (const id of ['reviewLoading', 'reviewError', 'reviewQuiz', 'reviewSummary']) {
    $(id).classList.toggle('hidden', id !== part);
  }
}

function enterLearning() {
  $('reviewSection').classList.add('hidden');
  $('learnArea').classList.remove('hidden');
  renderWordList($('wordSearch').value);
  updateDayBadge();
}

function startReviewCycle() {
  review.round = 0;
  review.cycleCorrect = 0;
  review.cycleTotal = 0;
  startReviewRound();
}

async function startReviewRound() {
  review.round++;
  review.picks = ReviewCore.pickRound(state, review.reviewed);

  // 没有可复习的词了
  if (review.picks.length === 0) {
    review.round--;
    showReviewSummary();
    return;
  }

  $('reviewRoundLabel').textContent = `· 第 ${review.round} / ${ReviewCore.ROUNDS_PER_CYCLE} 轮`;
  reviewShow('reviewLoading');

  try {
    const words = review.picks.map(p => p.word);
    const questions = await window.api.generateReviewQuestions(words);
    const byWord = new Map(questions.map(q => [q.word.toLowerCase().trim(), q]));

    review.queue = review.picks
      .map(p => ({ pick: p, q: byWord.get(p.word.toLowerCase().trim()) }))
      .filter(item => item.q);

    if (review.queue.length === 0) throw new Error('没有生成出有效的题目');
    review.idx = 0;
    showReviewQuestion();
  } catch (e) {
    console.error(e);
    review.round--;
    showReviewError(e.message);
  }
}

function showReviewError(msg) {
  const box = $('reviewError');
  box.innerHTML = `复习题目生成失败：${escapeHtml(msg)}<br>
    <button id="reviewRetryBtn" class="primary">重试</button>
    <button id="reviewErrSkipBtn" class="secondary">跳过复习</button>`;
  reviewShow('reviewError');
  $('reviewRetryBtn').addEventListener('click', startReviewRound);
  $('reviewErrSkipBtn').addEventListener('click', enterLearning);
}

function showReviewQuestion() {
  const { pick, q } = review.queue[review.idx];

  // 桶号 N 表示"第 N 天学/排的"，实际到期是第 N+1 天
  const dueDay = pick.day + 1;
  const overdue = state.currentDay - dueDay;
  $('reviewProgress').textContent =
    `第 ${review.round} 轮 · ${review.idx + 1} / ${review.queue.length}　（计划第 ${dueDay} 天复习`
    + (overdue > 0 ? `，已逾期 ${overdue} 天）` : '）');
  $('reviewSentence').innerHTML = escapeHtml(q.sentence)
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');

  const options = shuffle([
    { text: q.meaning, correct: true },
    ...q.distractors.map(t => ({ text: t, correct: false }))
  ]);

  $('reviewOptions').innerHTML = options.map((o, i) => `
    <button class="review-option" data-correct="${o.correct}">
      ${REVIEW_LETTERS[i]}. ${escapeHtml(o.text)}
    </button>`).join('');

  $('reviewOptions').querySelectorAll('.review-option').forEach(btn => {
    btn.addEventListener('click', () => answerReview(btn));
  });

  $('reviewResult').classList.add('hidden');
  $('reviewNextBtn').classList.add('hidden');
  reviewShow('reviewQuiz');
}

function answerReview(btn) {
  const { pick, q } = review.queue[review.idx];
  const correct = btn.dataset.correct === 'true';

  $('reviewOptions').querySelectorAll('.review-option').forEach(b => {
    b.disabled = true;
    if (b.dataset.correct === 'true') b.classList.add('correct');
  });
  if (!correct) btn.classList.add('wrong');

  // 调度单词：答对按连续答对次数拉长间隔；答错排到明天重来
  const { to, interval } = ReviewCore.applyResult(state, pick.word, correct);
  ReviewCore.save(localStorage, state);
  review.reviewed.add(pick.word);
  ReviewCore.markReviewed(state, pick.word);
  ReviewCore.save(localStorage, state);
  review.cycleTotal++;
  if (correct) review.cycleCorrect++;

  const moveText = correct
    ? `<span class="wi-ok">✔ 答对了</span>　下次复习：第 ${to + 1} 天（${interval} 天后）`
    : `<span class="wi-bad">✘ 答错了</span>　下次复习：明天（第 ${to + 1} 天）再巩固一次`;

  $('reviewResult').innerHTML = `
    <div>
      <span class="wi-word">${escapeHtml(pick.word)}</span>
      ${q.pos ? `<span class="wi-pos">${escapeHtml(q.pos)}</span>` : ''}
      <span class="wi-meaning">${escapeHtml(q.meaning)}</span>
    </div>
    <div class="wi-move">${moveText}</div>`;
  $('reviewResult').classList.remove('hidden');

  const isLast = review.idx === review.queue.length - 1;
  let nextText = '下一个';
  if (isLast) {
    nextText = review.round < ReviewCore.ROUNDS_PER_CYCLE
      ? `进入第 ${review.round + 1} 轮` : '完成本次复习';
  }
  $('reviewNextBtn').textContent = nextText;
  $('reviewNextBtn').classList.remove('hidden');
}

$('reviewNextBtn').addEventListener('click', () => {
  if (review.idx < review.queue.length - 1) {
    review.idx++;
    showReviewQuestion();
  } else if (review.round < ReviewCore.ROUNDS_PER_CYCLE) {
    startReviewRound();
  } else {
    showReviewSummary();
  }
});

function showReviewSummary() {
  const remaining = ReviewCore.remainingCount(state, review.reviewed);
  const done = review.cycleTotal;

  const head = done > 0
    ? `<div class="big">本次复习完成：答对 ${review.cycleCorrect} / ${done}</div>`
    : `<div class="big">今天没有需要复习的单词了</div>`;
  const sub = remaining > 0
    ? `还有 ${remaining} 个词可以继续复习，也可以直接去学习新单词。`
    : '所有已学单词都复习过了，去学新单词吧！';

  $('reviewSummary').innerHTML = `
    <div class="review-summary-box">${head}<div class="small">${sub}</div></div>
    <div class="review-summary-actions">
      ${remaining > 0 ? '<button id="reviewContinueBtn" class="secondary">继续复习</button>' : ''}
      <button id="reviewToLearnBtn" class="primary">学习新单词</button>
    </div>`;
  $('reviewRoundLabel').textContent = '';
  reviewShow('reviewSummary');

  if (remaining > 0) $('reviewContinueBtn').addEventListener('click', startReviewCycle);
  $('reviewToLearnBtn').addEventListener('click', enterLearning);
}

$('skipReviewBtn').addEventListener('click', enterLearning);


// ============ 词库扩充 ============
$('addMyWordsBtn').addEventListener('click', async () => {
  const text = $('myWordsInput').value;
  if (!text.trim()) return alert('请先输入单词');
  try {
    const r = await window.api.addCustomWords(text);
    $('myWordsInput').value = '';
    await loadBanks(r.id);
    alert(`已加入生词本：新增 ${r.added} 个，共 ${r.total} 个`);
  } catch (e) {
    alert('添加失败：' + e.message);
  }
});

$('importBankBtn').addEventListener('click', async () => {
  try {
    const r = await window.api.importWordBank();
    if (!r) return;
    await loadBanks(r.id);
    alert(`已导入「${r.name}」：共 ${r.total} 个词`);
  } catch (e) {
    alert('导入失败：' + e.message);
  }
});

$('aiBankBtn').addEventListener('click', async () => {
  const desc = $('aiBankDesc').value.trim();
  if (!desc) return alert('请先描述你想要的词库');
  try {
    await setBusy($('aiBankBtn'), '生成中…', async () => {
      const r = await window.api.generateWordBank(desc, $('aiBankCount').value);
      await loadBanks(r.id);
      alert(`已生成「${r.name}」：共 ${r.total} 个词`);
    });
  } catch (e) {
    alert('生成失败：' + e.message);
  }
});

$('deleteBankBtn').addEventListener('click', async () => {
  const id = $('bankSelect').value;
  if (!id.startsWith('custom:')) return;
  if (!confirm('确定删除这个自定义词库吗？（学习记录不受影响）')) return;
  try {
    await window.api.deleteCustomBank(id);
    await loadBanks();
  } catch (e) {
    alert('删除失败：' + e.message);
  }
});

// ============ 初始化 ============
updateDayBadge();
loadBanks();

// 每次打开软件先复习：第 1 天 ~ 昨天 里有单词才进入复习，否则直接学习
if (ReviewCore.remainingCount(state, review.reviewed) > 0) {
  $('reviewSection').classList.remove('hidden');
  startReviewCycle();
} else {
  enterLearning();
}
