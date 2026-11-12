# example-card

## 目的

维护“例句 + 词卡”的生成契约与渲染回退策略。

## 输入输出契约

`generateSentences(words)` 返回数组：

```json
[{
  "word": "abate",
  "en": "The storm began to **abate** towards morning.",
  "zh": "暴风雨在凌晨开始**减弱**。",
  "pos": "vt.",
  "ipa": "[ə'beit]",
  "senses": [
    { "pos": "vt.", "meaning": "缓和,减弱,减少,废除" },
    { "pos": "vi.", "meaning": "缓和,减弱,减少" }
  ]
}]
```

- `en`：英文例句，目标词用 `**` 标出；
- `zh`：中文翻译，只把对应义项用 `**` 标出；
- `ipa`/`senses`：命中本地词典时由后端覆盖；否则用模型给的 `pos`/`ipa`/`meaning`。

## 硬约束

1. 模型必须返回 JSON（`response_format=json_object`）；
2. 模型返回非 JSON 时回退为整段文本渲染，页面不得空白；
3. 看着像 JSON 却解析不出例句时报“格式异常，请重试”，不得把 JSON 原文渲染到页面；
4. 前端先 HTML 转义，再把 `**` 替换为高亮标签；
5. 词卡优先使用 `TOEFL_abridged.txt` 的音标与义项。

## 验收用例

- 真实调用 3 个词：返回合法 JSON，`zh` 中只标对应义项；
- 返回 ```json 围栏：仍能解析；
- 缺 `zh` 字段：该条被过滤，不导致页面崩溃；
- 纯文本返回：按旧格式逐行渲染。

## 已知坑

- 中文义项里可能出现多个 `**`，只应有一个是目标义项；
- 音标沿用文件原写法，不做 IPA 转换。
