(function () {
  'use strict';
  if (!window.ReviewCore) {
    console.warn('bridge-patch: ReviewCore 未加载，复习进度将使用 WebView 原生 localStorage');
    return;
  }
  var store = window.__reviewStorage;
  if (!store) {
    console.warn('bridge-patch: 缺少存储适配器，复习进度将使用 WebView 原生 localStorage');
    return;
  }
  var load = window.ReviewCore.load;
  var save = window.ReviewCore.save;

  // renderer.js 传进来的是 localStorage；这里换成 Java 持久化的存储适配器。
  window.ReviewCore.load = function (_ignoredStorage, today) {
    return load.call(window.ReviewCore, store, today);
  };
  window.ReviewCore.save = function (_ignoredStorage, state) {
    return save.call(window.ReviewCore, store, state);
  };
})();
