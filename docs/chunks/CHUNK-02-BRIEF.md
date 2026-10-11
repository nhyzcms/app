# 区块 2 启动清单（CHUNK-02-BRIEF）

> **这个文件是给“下一个对话 / 下一位维护者”看的入口。**
> 读完它（配合 [ROADMAP](../ROADMAP.md) 与 [FEATURE-MATRIX](../FEATURE-MATRIX.md)）
> 就能直接开工，不依赖任何人口头交代。

---

## 0. 一句话现状（2026-10-11）

| 项 | 状态 |
|---|---|
| 仓库 / 默认分支 | `nhyzcms/app` / `main` |
| `main` 位置 | `9908a20`（内容与已验证的 `ac34926` **逐字节一致**） |
| 区块 1（整体骨架） | ✅ 已完成并合并（见 [CHUNK-01-REPORT](CHUNK-01-REPORT.md)） |
| 分支 | 仅 `main`（历史分支已清理） |
| CI | ✅ 已启用且绿：静态预检 → debug APK；打 tag 自动发 Release |
| 已有产物 | artifact `app-debug-apk`（待用户真机验收） |
| 当前限制 | 文件上传/拍照/下载**尚未实现**（就是区块 2 要做的） |

---

## 1. 在**新对话**里怎么开场

直接粘下面这段给助手即可（已在仓库里，不依赖聊天记录）：

~~~text
继续 nhyzcms/app 项目（南汇一中传媒社的 Android 壳），现在开始区块 2。

先读：README.md、docs/ROADMAP.md 的“区块 2”、docs/chunks/CHUNK-02-BRIEF.md、
docs/FEATURE-MATRIX.md、docs/ARCHITECTURE.md 第 5 节。

规矩：从 main 拉 feat/02-xxx 分支 → 改 → 本地跑 scripts/preflight.sh → 开 PR；
不要在生产服务器上跑测试或构建（它只有 2 核 2G，只做只读校验，可克隆到 /tmp 看仓库）。
完成后给我 docs/chunks/CHUNK-02-REPORT.md。
~~~

若助手说“找不到上下文”，就把上面这份文件内容贴给它——它已包含全部必需信息。

---

## 2. 区块 2 的目标

> **让网页里每一个功能入口，在 App 内都能真正跑起来**（不能因为壳缺能力而残缺）。

现状：网页里点“选择文件”“拍照上传”“下载附件”都没反应——这是区块 1 故意留的空白，
不是 bug。区块 2 就是把它们接上。

---

## 3. 交付物清单

| # | 能力 | 对应网站功能 |
|---|---|---|
| 1 | `onShowFileChooser` 文件选择（文档/图片/多选） | 新闻配图、全景图导入、加入申请附件、教程附件 |
| 2 | 相机拍照直传（FileProvider + 临时文件清理） | 活动/新闻现场图、头像 |
| 3 | 运行时权限（相机；Android 13+ 的 `READ_MEDIA_IMAGES`） | 上传类功能 |
| 4 | `DownloadListener` → `DownloadManager`（带会话 Cookie） | 审核导出、成绩表、附件下载 |
| 5 | 长按图片保存 / 分享（`ACTION_SEND`）/ 外部打开 | 全局 |
| 6 | 上传体积前置提示（站点 nginx 限制 **20M**） | 全景图原图、附件 |
| 7 | `docs/chunks/CHUNK-02-REPORT.md` + 必要时补充 preflight 规则 | 交付文档 |

---

## 4. 关键技术事实（避免重新摸索）

1. **上传走 tus**（响应头 `tus-resumable: 1.0.0`，drf-tus）。
   👉 壳**不要**自己实现 tus 协议：只要把用户选的文件交给网页的 `<input type="file">`，
   前端的 tus 客户端会完成分块上传。壳要做的是“打开选择器并回传 URI”。
2. **上传上限 20M**（nginx `client_max_body_size`）——选文件后先判大小，超限在本地提示，
   不要让用户白传一半才报 413。
3. **会话是 Cookie**（WebView CookieJar）。下载受权限保护的文件时，
   要用 `CookieManager.getInstance().getCookie(url)` 把 Cookie 放进 `DownloadManager.Request` 的请求头。
4. **下载目录**：交给 `DownloadManager` 默认公共目录，**不要**申请 `WRITE_EXTERNAL_STORAGE`。
5. **FileProvider** authority 必须用 `${applicationId}.fileprovider`
   （debug 变体带 `.debug` 后缀，写死包名会导致 debug 拍照挂掉）。
6. **`onDestroy` 里不能给 `setWebViewClient` 传 null**（API 35 是 `@NonNull`），
   已改为空实现切回调；后续加代码不要回退。
7. 网页通过 UA 里的 `NhyzClubApp/<version> (Android; <code>)` 识别 App（见 ARCHITECTURE §5.1），
   新增原生能力时**不必**改网站，但若要网站根据能力分流，要和网站侧一起改。

---

## 5. 验收标准

- [ ] 对照 [FEATURE-MATRIX](../FEATURE-MATRIX.md)：第 **5、6、9、12、13、16、19、20、21、22、23、24** 行从 ⏳ 变为 ✅（并标注验证日期/机型）
- [ ] 真机实测三件事：**上传一张图片成功**、**下载一个受权限保护的文件成功（通知栏可见）**、**拍照上传成功**
- [ ] `bash scripts/preflight.sh` 通过；CI 绿
- [ ] 新坑已写进 `scripts/preflight.sh`（错误只能犯一次）

---

## 6. 开发约定（硬规矩）

| 规矩 | 说明 |
|---|---|
| 分支 | 从 `main` 拉 `feat/02-xxx` 或 `fix/02-xxx` |
| 提 PR | 改完必须开 PR 等人工审阅，不直接推 `main` |
| 提交前 | 跑 `bash scripts/preflight.sh`（5 秒，能挡下 XML/资源/已知陷阱类错误） |
| 构建 | 本地/服务器都没 Android SDK，**编译交给 CI**；不要在生产服务器跑 Gradle 或测试 |
| CI 报错怎么读 | Actions 原始日志需仓库管理员权限；本项目失败时会用 `scripts/ci-extract-errors.sh` 把关键报错**贴到 PR 评论**，直接看评论即可 |
| 密钥 | `*.jks` / `keystore.properties` / `local.properties` 一律不入库（preflight 会拦） |
| 日志 | 不打印完整 URL、Cookie、token；只记 host（参见 `MainActivity` 里的 `hostOf`） |

---

## 7. 需要用户配合的事

| 时机 | 需要用户做什么 |
|---|---|
| 区块 2 验收 | 真机安装并测试上传/下载/拍照（助手无法在安卓设备上跑） |
| 区块 3 | 服务器加一个 nginx `location /app-update/`（只读静态目录），发布更新清单用 |
| 区块 7 | 生成签名 keystore、配 4 个 GitHub Secrets |
| 全程 | 确认包名/应用名/图标品牌色/起始页（见 CHUNK-01-REPORT §8） |

---

## 8. 不在这块做的事（别越界）

- ❌ 应用内自动更新（区块 3）
- ❌ 下拉刷新/启动页/沉浸式状态栏（区块 4）
- ❌ 消息角标与通知（区块 5）
- ❌ 弱网优化与缓存策略（区块 6）
- ❌ 网站侧 App 模式 UI（区块 8，在网站仓库做）
- ❌ 单元测试体系（区块 9；但新增的纯函数建议顺手配上）
