#!/usr/bin/env bash
# 提交前静态预检（不需要 Android SDK 与 JDK）。
#
# 为何存在：本项目没有本地 Android 构建环境，只能靠 CI 编译；
#   但“靠静态检查就能发现的错误”不应该占用一轮 CI。
#   这个脚本把踩过的坑固化成规则（每踩一个新坑，就往下面加一条）。
#
# 用法： bash scripts/preflight.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0
err() { echo "[预检][失败] $*"; fail=1; }

# ── 1. XML：必须能解析，且注释里不能出现连续两个短横线 （AAPT 会直接中断构建）──
while IFS= read -r f; do
  out=$(python3 - "$f" <<'PY'
import sys, xml.dom.minidom
p = sys.argv[1]
raw = open(p, encoding="utf-8").read()
try:
    xml.dom.minidom.parseString(raw)
except Exception as e:
    sys.exit(f"XML 语法错误：{e}")
i = raw.find("<!--")
while i != -1:
    j = raw.find("-->", i + 4)
    if j == -1:
        sys.exit("XML 注释未闭合")
    if "--" in raw[i + 4:j]:
        sys.exit("XML 注释里出现了连续两个短横线")
    i = raw.find("<!--", j + 3)
PY
) || err "$f：$out"
done < <(find app/src/main -name '*.xml' | sort)

# ── 2. Kotlin 里引用的 R.xxx 必须在资源里真实存在 ──
python3 - <<'PY' || fail=1
import os, re, sys
res = "app/src/main/res"
src = "app/src/main/java"
errors = []

def values_names(xml_dir_glob):
    names = set()
    for root, _dirs, files in os.walk(res):
        if xml_dir_glob not in root.replace(os.sep, "/"):
            continue
        for f in files:
            if not f.endswith(".xml"):
                continue
            text = open(os.path.join(root, f), encoding="utf-8").read()
            names.update(re.findall(r'<(?:string|color|dimen|style|integer|bool)\s+name="([^"]+)"', text))
    return names

def file_names(subdir):
    out = set()
    base = os.path.join(res, subdir)
    if not os.path.isdir(base):
        return out
    for root, _dirs, files in os.walk(base):
        for f in files:
            out.add(f.split(".")[0])
    return out

ids = set()
for root, _dirs, files in os.walk(res):
    for f in files:
        if f.endswith(".xml"):
            ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', open(os.path.join(root, f), encoding="utf-8").read()))

known = {
    "string": values_names("values"),
    "color": values_names("values"),
    "dimen": values_names("values"),
    "style": values_names("values"),
    "bool": values_names("values"),
    "integer": values_names("values"),
    "id": ids,
    "layout": file_names("layout"),
    "drawable": file_names("drawable"),
    "xml": file_names("xml"),
    "mipmap": file_names("mipmap-anydpi-v26") | file_names("mipmap-anydpi-v33"),
}

for root, _dirs, files in os.walk(src):
    for f in files:
        if not f.endswith(".kt"):
            continue
        path = os.path.join(root, f)
        text = open(path, encoding="utf-8").read()
        for typ, name in re.findall(r'R\.(id|layout|string|color|drawable|mipmap|xml|style|dimen|bool|integer)\.([A-Za-z0-9_]+)', text):
            pool = known.get(typ)
            if pool is not None and name not in pool:
                errors.append(f"{path}: R.{typ}.{name} 在资源里不存在")

if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"R.* 引用检查通过（资源总数：{sum(len(v) for v in known.values())}）")
PY

# ── 3. 已知陷阱（每踩一次就加一条）──────────────────────────────────────

# 3.1 WebView 安全基线（区块 1 踩过 / 安全文档第 2、4 节）
if grep -rn "setWebViewClient(null)" app/src/main/java >/dev/null 2>&1; then
  err "setWebViewClient 不接受 null（API 35 参数是 @NonNull），请改用空实现或直接不设"
fi
if grep -rnE "\bsafeBrowsingEnabled[[:space:]]*=" app/src/main/java >/dev/null 2>&1; then
  err "WebSettings.safeBrowsingEnabled 没有 getter，不能用 Kotlin 属性语法，请改用 setSafeBrowsingEnabled(...)"
fi
if grep -rn "addJavascriptInterface" app/src/main/java >/dev/null 2>&1; then
  err "禁止 addJavascriptInterface（安全文档第 4 节：桥接只能走 WebViewCompat.addWebMessageListener 并按 origin 限定）"
fi
if grep -rn "\.proceed()" app/src/main/java >/dev/null 2>&1; then
  err "onReceivedSslError 里绝不能调 handler.proceed()（安全文档第 2 节：TLS 失败必须显式失败）"
fi

# 3.2 存储/媒体类权限一个都不要（安全文档第 2、7 节；区块 2 定调）
if grep -nE '<uses-permission[^>]*(READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|READ_MEDIA_|MANAGE_EXTERNAL_STORAGE|ACCESS_MEDIA_LOCATION)' \
     app/src/main/AndroidManifest.xml >/dev/null 2>&1; then
  err "清单里出现了存储/媒体类权限：文件选择走系统 SAF、下载走 DownloadManager 即可，不需要它们（docs/SECURITY.md 第 2 节）"
fi

# 3.3 FileProvider 三处必须一致（不一致时只有真机运行才会报 SecurityException）
if grep -rq "getUriForFile" app/src/main/java; then
  grep -qF 'android:authorities="${applicationId}.fileprovider"' app/src/main/AndroidManifest.xml \
    || err "用了 getUriForFile，但清单里没有 android:authorities=\"\${applicationId}.fileprovider\""
  grep -rq "BuildConfig.APPLICATION_ID" app/src/main/java/com/nhyzcms/clubapp/shell \
    || err "FileProvider authority 必须用 BuildConfig.APPLICATION_ID 拼（debug 变体带 .debug 后缀，写死包名会在运行时炸）"
  [ -f app/src/main/res/xml/file_paths.xml ] \
    || err "用了 FileProvider，但缺 app/src/main/res/xml/file_paths.xml"
fi

# 3.4 写公共下载目录必须按 SDK 版本分支（API 26~28 会 SecurityException）
if grep -rq "setDestinationInExternalPublicDir" app/src/main/java; then
  grep -rq "Build.VERSION.SDK_INT" app/src/main/java/com/nhyzcms/clubapp/shell/DownloadStarter.kt \
    || err "setDestinationInExternalPublicDir 必须按 Build.VERSION.SDK_INT 分支（见 shell/DownloadPolicy）"
fi

# 3.5 密钥 / 本地配置不得入库
for f in $(find . -name '*.jks' -o -name '*.keystore' -o -name 'local.properties' | grep -v '^\./\.git/'); do
  err "仓库里出现了密钥/本地配置类文件：$f"
done

# ── 结果 ─────────────────────────────────────────────────────────────
if [ "$fail" -eq 0 ]; then
  echo "[预检] 全部通过 ✅"
else
  echo "[预检] 存在失败项 ❌"
fi
exit "$fail"
