# 造句学英语 · Java 版（JavaFX WebView）

窗口里放一个 JavaFX `WebView`，加载 `app/` 里的前端文件（`index.html` / `styles.css` /
`review-core.js` / `renderer.js`），Java 侧负责原来 Electron IPC 的那些事：

1. 注入桥接脚本，提供 `window.api`（原 `preload.js` 的 9 个方法）；
2. 用 Java 持久化替代 localStorage（复习进度写 `review-state.json`）；
3. 本地词典（`TOEFL_abridged.txt`）为例句词卡提供音标/词性/释义。

旧版的 Electron 实现已移到仓库外（本地 `D:\DSH_gen\electron-legacy`），不再随本仓库维护。

## 目录

```
Create Sentence/              仓库根目录（也是运行/打包时的项目根）
├── app/                      前端资源，打包时原样复制进 dist\...\app
│   ├── index.html  styles.css  renderer.js  review-core.js
│   ├── word-banks/           内置词库（4 个）
│   ├── TOEFL_abridged.txt    本地词典：word [音标] 词性. 释义
│   └── .env / .env.example   DeepSeek key（.env 不进 git）
├── src/main/java/com/sentence/
├── src/main/resources/       bridge-core.js / bridge-patch.js
├── lib/                      JavaFX + Jackson jar（不进 git）
├── out/                      编译产物、截图、测试数据（不进 git）
├── tools/
│   ├── fetch-deps.mjs        下载依赖到 lib/
│   └── parity-dump.mjs       用 app/review-core.js 生成对拍数据
├── build.ps1  run.ps1  test.ps1  package.ps1  start-java.bat
└── README.md
```

## 快速开始

```powershell
cd "D:\DSH_gen\Create Sentence"

# 1) 一次性下载依赖（JavaFX + Jackson，约 43 MB，缓存在 lib/）
node tools/fetch-deps.mjs

# 2) 编译
powershell -ExecutionPolicy Bypass -File build.ps1

# 3) 运行（加载 app/ 下的前端文件）
powershell -ExecutionPolicy Bypass -File run.ps1

# 或者直接双击 start-java.bat
```

环境要求：JDK 21（已验证 Temurin 21.0.12）。不需要 Maven / Gradle / Node 运行时
（只有下载依赖的脚本用 Node）。

`run.ps1` 会把 `--app-root=<项目根>\app` 传给程序；`.env` 就从 `app\.env` 读。
要把 `.env` 放在别处，也可以放 `%APPDATA%\create-sentence\.env`。

### 打包成不需要 JDK 的独立程序

```powershell
node tools/fetch-deps.mjs
powershell -ExecutionPolicy Bypass -File package.ps1
```

输出 `out\dist\CreateSentence\CreateSentence.exe`（约 180 MB，内含 Java 运行时 + JavaFX + 前端资源）。
exe 默认不带控制台；排查启动问题时可加 `-Console` 生成带控制台的版本。

打包会把 `app/` 下的 `index.html`、`styles.css`、`renderer.js`、`review-core.js`、
`TOEFL_abridged.txt`、`word-banks/` 复制到 `app\`（与 `app.jar` 同级），`Settings` 从 jar 所在目录找
`index.html`。

- `.env` 放两份位置之一：`out\dist\CreateSentence\app\.env`，或 `%APPDATA%\create-sentence\.env`。
- 用的是 JDK 自带的 `jpackage`，`app-image` 不需要 WiX；要 `.msi` 安装包才需要。
- 验证状态：带控制台版本已实际运行并通过冒烟测试；无控制台 exe 在本仓库的沙箱里
  无法启动 GUI 子系统进程，没能自动验证，首次拿到请双击确认一次。

## 一键测试

```powershell
powershell -ExecutionPolicy Bypass -File test.ps1
```

会依次执行：

1. 编译
2. 用 `app/review-core.js` 生成对拍数据（`tools/parity-dump.mjs`）
3. Java 自测 79 项：词表解析、宽松 JSON、emoji 转义、复习算法（含与 JS 逐字段对拍、按词库筛选）、
   自定义词库、内置词库、旧版学习记录导入、DeepSeek 请求/响应/错误映射
4. WebView 冒烟测试（学习界面），截图 `out/smoke.png`
5. WebView 冒烟测试（复习界面 + 真实大模型出题），截图 `out/smoke-review.png`
6. 真实 DeepSeek 调用一次（验证 key / 端点 / 模型名，消耗极少 tokens）

也可以用真实数据只跑冒烟测试：

```powershell
powershell -ExecutionPolicy Bypass -File run.ps1 --smoke-test --data-dir="%APPDATA%\create-sentence" --snapshot="out\smoke.png"
```

## 代码结构

```
src/main/java/com/sentence/
├── App.java               JavaFX 入口 + WebView + 页面注入 + 冒烟测试
├── JsBridge.java          preload.js 的 9 个方法 + 对话框 + 异步回传 + 词典补充
├── HtmlSupport.java       emoji（非 BMP 字符）转数字实体
├── Settings.java          appRoot / 数据目录 / .env 读取
├── Json.java              Jackson 封装
├── Fx.java                FX 线程工具
├── LiveTest.java          真实 LLM 连通性检查
├── SelfTest.java          自测（86 项）
├── CliArgs.java           命令行参数
├── llm/
│   ├── DeepSeekClient.java  HttpClient 直连 OpenAI 兼容接口
│   ├── JsonLoose.java       对应 parseJsonLoose
│   ├── Prompts.java         三段提示词
│   └── LlmException.java
├── review/
│   ├── ReviewCore.java      复习算法（运行时由 Java 执行）
│   ├── ReviewOps.java       前端同步调用入口：pickRound / applyResult / dueCounts…
│   ├── ReviewState.java
│   ├── ReviewStore.java     review-state.json 持久化
│   └── LegacyImporter.java  从旧版 LevelDB 抢救学习记录
└── words/
    ├── WordParser.java         对应 parseWords
    ├── CustomBankStore.java    custom-banks.json（与原版共用）
    ├── WordBankRepository.java 内置 + 自定义词库
    └── Dictionary.java         TOEFL_abridged.txt 解析（音标/词性/释义）
```

## 复习调度规则

- **天数怎么涨**：每个自然日第一次打开软件 +1 天（`currentDay`）。中间停几天不学，天数也只会 +1，
  不按真实间隔累积。
- **每个词放在哪**：状态里是 `days: { 桶号: [单词...] }`，一个词只在一个桶里。
  桶号 N 表示"第 N 天排进去的"，实际**到期日是第 N+1 天**（今天学的词明天才复习）。
- **词库归属**：`sources: { 单词: 词库id }`，造句学新词时记下当时选的词库；
  老数据没有这个字段，启动时按各词库的词表反推一次，推不出的归到「未分类（历史词）」。
- **答对**：按"连续答对次数"拉长间隔 —— **2 / 4 / 7 / 15 / 30 / 60 天**（`INTERVALS`），
  排到 `currentDay + interval - 1` 这个桶；连对次数 +1（封顶第 6 档，之后每 60 天一次）。
- **答错**：排到明天重来一次（`interval = 1`），连续答对次数清零。
- **复习前先选词表**：打开软件先显示各词库的"到期词数"，勾选要复习的词库、点「开始复习」
  才调用 AI 出题；选择会记住（`reviewBankSelection`）。这样可以避开不想复习的词库（比如
  以前用简单词练手留下的基础词），也不浪费题目。
- **每天复习哪些**：从"已到期"（桶号 ≤ 今天 -1）且属于所选词库的词里，每轮取
  **最近到期的一天最多 5 个 + 最久没复习的一天最多 5 个**，每轮最多 **10 题**，
  三轮一个循环（最多 30 题/天）。当天已经复习过的词记在 `reviewedWords`，同一天不会重复出题；
  没排上的词留在原计划日，下次打开按"逾期"继续。
- **新学的词**：只进入"今天"这个桶，明天开始第一次复习。

> 早期版本把答对的词统一挪到"今天的前一天"，导致所有词天天都到期、第一轮里全是复习过的词；
> 现在改成上面的间隔递增后，一个词只会在 2、4、7、15、30、60 天后回来。

### 运行时分工（Java 与 JS）

复习调度在运行时由 **Java** 执行，调用链是：

```
renderer.js → review-core.js → window.__javaBridge.reviewOp(op, payload)
            → JsBridge.reviewOp → ReviewOps.handle → ReviewCore
```

每次操作把状态 JSON 传给 Java，Java 计算后返回新状态（`pickRound` 返回题目、`applyResult` 返回新桶与间隔），
前端只负责渲染和把状态写回 `review-state.json`。

`app/review-core.js` 里仍保留一份等价的 JS 实现，只在没有 Java 桥接的环境（Node 对拍脚本、普通浏览器调试）
作为参考实现使用：`tools/parity-dump.mjs` 用它生成黄金数据，`SelfTest` 再让 Java 实现逐字段复现。
冒烟测试里的 `reviewEngine` 字段会显示当前实际使用的是 `java` 还是 `js`。

## 数据兼容

- **自定义词库**：读写 `%APPDATA%\create-sentence\custom-banks.json`，与原 Electron 版同一份文件。
- **学习记录**：首次运行会从 `%APPDATA%\create-sentence\Local Storage\leveldb` 自动导入
  `learnedDays` / `learnedWords`（只读扫描，取最后一次写入的值），随后保存为
  `%APPDATA%\create-sentence\review-state.json`。
- ⚠️ 新旧两版不要在进度上交叉使用：Java 版写入的是 `review-state.json`，
  Electron 版读的是浏览器 localStorage。确定用哪一版后就别来回切。

## 与 Electron 版的已知差异

| 项 | 说明 |
|---|---|
| emoji | WebView 无彩色 emoji 字体，📚🔍🎲 等显示为单色符号（Segoe UI Symbol） |
| 运行时 | 需要 JRE 21；Electron 版自带运行时但体积大 487 MB |
| 页面地址 | WebView 用 `loadContent` + `<base href>` 加载，`document.location` 是 `about:blank`（不影响功能） |
| 滚动条 | 使用 WebView 自带滚动条，与 Chromium 外观略有差异 |
| 音标 | 词卡用 `TOEFL_abridged.txt` 原样音标（如 `[ə'beit]`），不是 IPA |

## 调试

```powershell
# 打印页面加载状态、桥接调用载荷、存储快照
powershell -ExecutionPolicy Bypass -File run.ps1 --debug
```

如果 `app/index.html` 的结构被改（例如脚本标签重命名），注入会退化：
`renderer.js` / `review-core.js` 找不到时会打印警告，复习进度回退到 WebView 原生存储。

### 改脚本的注意事项

`*.ps1` 请保持**纯 ASCII**（注释也用英文）。Windows PowerShell 5.1 会把无 BOM 的脚本按
ANSI 解码，中文注释可能把下一行"注释掉"，典型症状是 `--app-root=` 变空、启动时报
「找不到 index.html」。程序本身也做了兜底：没有 `--app-root` 时会依次尝试
`当前目录/index.html`、`当前目录/app/index.html`（及它们的上一级）。
