# review-scheduling

> 运行时说明：本规则由 Java 的 `ReviewOps` / `ReviewCore` 执行（前端通过 `reviewOp` 同步调用）；
> `app/review-core.js` 是同一规则的 JS 参考实现，只在 Node 对拍/普通浏览器里使用。

## 目的

维护复习调度：分桶、间隔、选词、按词库过滤。改动这部分逻辑前必须先读本文件。

## 数据契约

```json
{
  "currentDay": 20,
  "lastDate": "2026-10-08",
  "days": { "1": ["abate"], "21": ["mitigate"] },
  "sources": { "abate": "custom:file-TOEFL_abridged" },
  "steps": { "abate": 2 },
  "reviewedDay": 20,
  "reviewedWords": ["abate"]
}
```

- 桶号 N 的到期日 = 第 N+1 天；桶号 > currentDay 的为未来计划。
- 一个单词同一时间只存在于一个桶里。

## 硬约束

1. 答对间隔取 `INTERVALS = [2,4,7,15,30,60]`，step 递增封顶；
2. 答错 interval=1，step 清零；
3. 每轮最近 5 + 最远 5，最多 10 题；三轮一个循环；
4. 当天已复习的词不得再次出现；
5. 词库过滤只影响统计与选词，不改变单词所属桶；
6. 选词表的统计、出题、总结必须使用同一个过滤条件。

## 验收用例

- `currentDay=4`、b1 在第 2 桶，答对 → `to=5`、`interval=2`；
- 只勾 bankA → 出题集合 ⊆ bankA；
- 同一轮 12 个到期词 → 只出 10 个；
- 同一天重复打开软件 → 已复习记录保留，题目不重复。

## 已知坑

- 桶号与“第几天”差 1，展示时要 +1；
- 旧数据没有 sources，需按词库反推一次并写回；
- `reviewedWords` 只对当天有效，跨天要清空。
