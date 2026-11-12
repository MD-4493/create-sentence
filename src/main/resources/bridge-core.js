(function () {
  'use strict';
  if (window.__sentenceBridge) return;
  window.__sentenceBridge = true;

  var attached = false;
  var seq = 0;
  var pending = Object.create(null);
  var queued = [];
  var errors = [];
  window.__bridgeErrors = errors;

  function recordError(message) {
    errors.push(String(message));
    if (errors.length > 30) errors.shift();
  }

  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = 'c' + (++seq);
      pending[id] = { resolve: resolve, reject: reject };
      var payload;
      try {
        payload = JSON.stringify(args === undefined ? null : args);
      } catch (e) {
        delete pending[id];
        reject(new Error('参数无法序列化: ' + e.message));
        return;
      }
      if (attached && window.__javaBridge) {
        try {
          window.__javaBridge.call(id, method, payload);
        } catch (e) {
          delete pending[id];
          reject(new Error(String(e)));
        }
      } else {
        queued.push([id, method, payload]);
      }
    });
  }

  window.__bridgeResolve = function (id, ok, payload) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) {
      var value = payload;
      try { value = JSON.parse(payload); } catch (e) { /* 非 JSON 就原样返回 */ }
      p.resolve(value);
    } else {
      recordError(payload);
      p.reject(new Error(payload));
    }
  };

  // ---------- 与 preload.js 完全一致的 9 个方法 ----------
  window.api = {
    generateSentences: function (words) { return call('generateSentences', words); },
    correctSentence: function (sentence, keywords) {
      return call('correctSentence', { sentence: sentence, keywords: keywords });
    },
    getWordBanks: function () { return call('getWordBanks'); },
    getWords: function (bankId) { return call('getWords', bankId); },
    generateReviewQuestions: function (words) { return call('generateReviewQuestions', words); },
    addCustomWords: function (text) { return call('addCustomWords', text); },
    importWordBank: function () { return call('importWordBank'); },
    generateWordBank: function (description, count) {
      return call('generateWordBank', { description: description, count: count });
    },
    deleteCustomBank: function (bankId) { return call('deleteCustomBank', bankId); }
  };

  // ---------- 持久化：替代 localStorage ----------
  // Java 启动时会把 review-state.json 的内容放在 __bridgeStorageSnapshot 里，
  // 这里做成同步内存存储，写入后再异步落盘，保证 renderer 里的同步 API 语义不变。
  var data = Object.create(null);
  try {
    var snapshot = window.__bridgeStorageSnapshot;
    if (typeof snapshot === 'string') snapshot = JSON.parse(snapshot);
    snapshot = snapshot || {};
    Object.keys(snapshot).forEach(function (k) { data[k] = String(snapshot[k]); });
  } catch (e) {
    recordError('storage snapshot 解析失败: ' + e.message);
  }

  var dirty = Object.create(null);

  function persist(key) {
    var value = Object.prototype.hasOwnProperty.call(data, key) ? data[key] : null;
    if (attached && window.__javaBridge) {
      try {
        window.__javaBridge.putStorage(key, value);
        return;
      } catch (e) {
        recordError('存储写入失败: ' + e);
      }
    }
    dirty[key] = value;
  }

  var store = {
    getItem: function (key) {
      return Object.prototype.hasOwnProperty.call(data, key) ? String(data[key]) : null;
    },
    setItem: function (key, value) {
      data[String(key)] = String(value);
      persist(String(key));
    },
    removeItem: function (key) {
      delete data[String(key)];
      persist(String(key));
    },
    clear: function () {
      Object.keys(data).forEach(function (k) { delete data[k]; persist(k); });
    },
    key: function () { return null; },
    length: 0
  };
  window.__reviewStorage = store;

  // 在 about:blank 页面里访问原生 localStorage 会抛 SecurityError，
  // 这里直接把 window.localStorage 换成 Java 存储，renderer.js 无需改动。
  try {
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get: function () { return store; }
    });
  } catch (e) {
    recordError('localStorage 替身安装失败: ' + e);
  }

  window.__bridgeAttach = function () {
    if (attached) return;
    attached = true;
    Object.keys(dirty).forEach(function (k) {
      try {
        window.__javaBridge.putStorage(k, dirty[k]);
      } catch (e) {
        recordError('存储写入失败: ' + e);
      }
    });
    dirty = Object.create(null);
    var list = queued;
    queued = [];
    list.forEach(function (item) {
      try {
        window.__javaBridge.call(item[0], item[1], item[2]);
      } catch (e) {
        recordError('调用失败: ' + e);
      }
    });
  };

  // ---------- alert / confirm / console ----------
  window.alert = function (message) {
    try {
      if (attached) window.__javaBridge.alert(String(message));
      else console.log('[alert] ' + message);
    } catch (e) {
      console.log('[alert] ' + message);
    }
  };

  window.confirm = function (message) {
    try {
      return attached ? !!window.__javaBridge.confirm(String(message)) : true;
    } catch (e) {
      return true;
    }
  };

  ['log', 'info', 'warn', 'error'].forEach(function (level) {
    var original = console[level];
    console[level] = function () {
      var text = Array.prototype.map.call(arguments, function (a) {
        try {
          if (typeof a === 'string') return a;
          if (a instanceof Error) return (a.stack || (a.name + ': ' + a.message));
          return JSON.stringify(a);
        } catch (e) {
          return String(a);
        }
      }).join(' ');
      try {
        if (attached) window.__javaBridge.log(level, text);
      } catch (e) { /* 忽略日志回传失败 */ }
      original.apply(console, arguments);
    };
  });

  window.addEventListener('error', function (event) {
    recordError((event.message || 'script error') + ' @' + (event.filename || '') + ':' + (event.lineno || 0));
  });

  // ---------- 冒烟测试用（只在 --smoke-test 时注入） ----------
  if (window.__smokeMode) window.__smokeState = function () {
    function byId(id) { return document.getElementById(id); }
    function visible(id) {
      var el = byId(id);
      return !!(el && !el.classList.contains('hidden'));
    }
    var select = byId('bankSelect');
    return JSON.stringify({
      title: document.title,
      apiReady: !!(window.api && typeof window.api.getWordBanks === 'function'),
      reviewCore: !!(window.ReviewCore && window.ReviewCore.STORE_KEY === 'learnedDays'),
      bridgeAttached: attached,
      bankOptions: select ? select.options.length : -1,
      bankValue: select ? select.value : '',
      dayBadge: (byId('dayBadge') || {}).textContent || '',
      reviewVisible: visible('reviewSection'),
      reviewSetupVisible: visible('reviewSetup'),
      reviewReviewedBanks: (function () {
        var boxes = document.querySelectorAll('#reviewBankList input[type="checkbox"]');
        var checked = 0;
        for (var i = 0; i < boxes.length; i++) if (boxes[i].checked) checked++;
        return boxes.length + '/' + checked;
      })(),
      reviewQuizVisible: visible('reviewQuiz'),
      reviewEngine: (typeof window.__reviewEngine === 'string') ? window.__reviewEngine : 'js',
      reviewErrorVisible: visible('reviewError'),
      reviewLoadingVisible: !byId('reviewLoading').classList.contains('hidden'),
      learnVisible: visible('learnArea'),
      wordItems: document.querySelectorAll('#wordList .word-item').length,
      errors: errors.slice(0)
    });
  };
})();
