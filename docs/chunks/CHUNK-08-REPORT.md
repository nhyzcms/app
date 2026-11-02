# 区块 8 报告 · 网站侧配套（App 模式收尾）

日期：2026-11-01
仓库：**网站仓库** `nhyzcms/club-management-website`（本块按 ROADMAP 在网站仓库做）
分支：`feat/08-app-mode-finish` → PR [#266](https://github.com/nhyzcms/club-management-website/pull/266)
状态：**代码完成，待合并 + 真机回归**

---

## 1. 这一块在做什么

验收标准只有一句：**对照 FEATURE-MATRIX，每一行都有可达路径；手机端不存在"只能从桌面版进"的页面。**

到本块开工时，`/#/m` 已经覆盖了首页 / 新闻 / 互动 / 活动 / 教程 / 加入 / 任务 / 待办 /
审核台 / 通知 / 我的，但还有三类缺口：

| 缺口 | 后果 |
|---|---|
| 考试看板 / 课表 / 全景图 / 关于**没有任何入口** | 手机上根本走不到，等于功能不存在 |
| 壳版本过旧时**没有任何提示** | 老壳缺原生能力（如网页内相机授权）＝"点了没反应"，用户只会以为网站坏了 |
| SPA 入口（`index.html`）**没有缓存头** | WebView 按启发式缓存旧 HTML → 去要已被删掉的 contenthash chunk → 白屏，L0 热更新失效 |

---

## 2. 交付物

| # | 改动 | 文件 |
|---|---|---|
| 1 | **最低壳版本闸门**：解析 UA 里的 `versionCode` → `ok / outdated / unknown`；`outdated` 时全屏提示更新 + `/app-update/latest.apk` 直链 | 新增 `frontend/src/utils/shellVersion.ts`、`frontend/src/components/ShellVersionGate.tsx` + `.css`；挂在 `App.tsx` 根部 |
| 2 | **降级入口**：「我的」新增「更多」分组 —— 考试看板 `/exam`、课表 `/schedule`、全景图 `/panorama`、关于我们 `/about` | `frontend/src/pages/MobileMePage.tsx` |
| 3 | **弱网**：手机版首页封面改走 `cover_thumbnail_url` | `frontend/src/pages/MobileHomePage.tsx` |
| 4 | **L0 热更新前提**：`/` 与兜底路由 `never_cache` | `config/urls.py`、新增 `common/tests/test_spa_index_cache.py` |
| 5 | 单测 8 条 + README 补 App 模式约定与验收口径 | `frontend/src/utils/__tests__/shellVersion.test.ts`、`README.md` |

**新增依赖：0**；**新增权限：0**；**数据库变更：0**。

---

## 3. 关键决策

### 3.1 为什么闸门比 `versionCode` 而不是 `versionName`

`versionCode` 单调递增（Android 不允许降级安装），比大小即可；`versionName` 是给人看的三段式，
`0.10.0` vs `0.9.0` 这类比较要先写解析器再写比较器，纯属自找麻烦。

### 3.2 为什么闸门**可以不看**、也**不猜**

- 壳里留「暂时继续（部分功能可能不可用）」，且**不写 localStorage**（刷新即恢复）：
  闸门自己也可能误报（UA 被改、代理、厂商 WebView 怪癖），不能让它把用户锁死在门外；
- UA 里解析不出版本码时返回 `unknown` 并**放行**：宁可少提示一次，也不拿用户做实验。
  真正"不可跳过"的强制更新在壳侧（`app-manifest.json` 的 `minShellVersionCode`），那里有签名与清单校验兜底。

### 3.3 为什么这几页先不写 `/m` 版

考试看板、课表、全景图是重桌面交互（大表格 / WebGL），Django admin 更是。ROADMAP 的口径是
**"放不下的允许降级到桌面布局，但必须有入口、能操作"**，所以本块先把入口补齐，
按真实使用频率再逐页做移动版式。同理，新建新闻/任务的富文本编辑器在 6 寸屏不可用，
**明确不做**（写在 ROADMAP 里，避免以后被当成"忘了"）。

### 3.4 为什么 SPA 入口要 `never_cache` 而不是"短缓存"

`index.html` 只有 2.5 KB，回源代价可以忽略；而它引用的 JS/CSS 文件名带 contenthash，
**HTML 一旧，哈希就全对不上**。这类故障的表现是"打开一片白"，且只在发版后出现，
排查成本远高于每次回源 2.5 KB。

---

## 4. 验证记录（本地，2026-11-01）

| 项 | 结果 |
|---|---|
| `npx vitest run src/utils/__tests__/shellVersion.test.ts src/utils/__tests__/device.test.ts` | ✅ 12 passed（新增 8 条） |
| `npx tsc --noEmit` | ✅ 本次改动零报错（仓库内 `src/api/__tests__`、`src/pages/__tests__` 的 5 处 TS2352/TS2493 是**既有**问题，与本次无关） |
| `.venv/bin/python manage.py test common.tests.test_spa_index_cache common.tests.test_maintenance` | ✅ 13 passed |
| Playwright（390×844，壳 UA）老壳 `0.1.0 (Android; 1)` | ✅ 出现闸门（深浅色各一张）；点「暂时继续」→ 正常进首页 |
| Playwright 新壳 `0.2.0 (Android; 2)` | ✅ 不出现闸门 |
| 首页封面（stub：原图 `/media/news/full-cover.jpg`，缩略图 `/media/news/thumb-cover.jpg`） | ✅ `img.src = /media/news/thumb-cover.jpg` |
| 生产机 | ✅ 未在上面跑测试/构建；四路 CI 交给 GitHub |

截图（深浅色 + 点击「暂时继续」后 + 「更多」分组）在 PR #266 的评论区，文件落在
`docs/assets/pr-266/`。

---

## 5. 真机回归清单（8 条，管理员执行）

1. 用**已装的 v0.1.0**（若还留着）打开 App → 应看到「请更新 App」全屏提示；
   点「下载最新版 APK」→ 走壳的下载通道落盘，通知栏可点安装。
2. 同上，点「暂时继续」→ 能正常进首页；**杀掉 App 重开** → 提示应重新出现（证明没写死本地）。
3. 装 **v0.2.0** → 不再出现闸门。
4. 「我的」→「更多」→ 考试看板：能读到内容、能滚动，不白屏。
5. 同上 → 课表：同上。
6. 同上 → 全景图：**重点看 WebGL**（低端机纹理上限），能拖动能看全景；
   若黑屏，记录机型与 WebView 版本（这是 FEATURE-MATRIX 里已知的 ⚠️ 项）。
7. 同上 → 关于我们：能滚动、外链交浏览器。
8. 首页「最新动态」卡片：**确认封面加载的是缩略图**（开发者选项/抓包看 `/media/` 路径带 thumb），
   弱网（限速 500 KB/s）下首屏不再等原图。

> 顺带回归：`/#/m/news`、`/#/m/activity`、`/#/m/tasks`、`/#/m/inbox`、`/#/m/review`、
> `/#/m/notifications`、`/feedback`、`/v`（网页内扫码）—— 这些是本块之前就有的页面，
> 本块只动过首页封面一行，预期无回归。

---

## 6. 红线（下次动这块前先读）

- **`MIN_SHELL_VERSION_CODE` 是"把老用户挡在门外"的开关**：只在老壳会让网站真的出错时才提高它，
  并且提高前先确认 `app-update/manifest.json` 的 `latest` 已经指向一个能装的版本。
- **闸门的 URL 用相对路径** `/app-update/latest.apk`：换域名/端口不用改代码，
  但**部署时 nginx 的 `location /app-update/` 必须在**（否则提示里的按钮是 404）。
- **新增桌面页 = 必须挂入口**：要么做 `/#/m` 版本并在 `App.tsx` 的 `MobileAware` 登记改道，
  要么挂进 `MobileMePage` 的「更多」。没有入口的页面在 App 里等于不存在。
- **不要给 SPA 入口加缓存**：`never_cache` 是 L0 热更新的前提，`common/tests/test_spa_index_cache.py` 就是钉子。
