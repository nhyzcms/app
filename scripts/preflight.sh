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

# 注：下面每个 python 检查都带 2>&1。python 的 sys.exit("消息") 是写到 stderr 的，
# 不重定向就会丢掉最关键的“哪一行、什么原因”（区块 2 才发现，之前只有空消息）。

# ── 1. XML：必须能解析，且注释里不能出现连续两个短横线 （AAPT 会直接中断构建）──
while IFS= read -r f; do
  out=$(python3 - "$f" 2>&1 <<'PY'
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

# ── 1b. Kotlin 块注释：里面不能出现「斜杠 + 星号」（Kotlin 块注释会嵌套，会把整个文件吞掉）──
#   区块 2 踩过：KDoc 里写了 MIME 通配示例 → CI 报 “Unclosed comment” +
#   MainActivity 里 shouldOfferCapture “Unresolved reference”，排查很久。
while IFS= read -r f; do
  out=$(python3 - "$f" 2>&1 <<'PY'
import sys
p = sys.argv[1]
raw = open(p, encoding="utf-8").read()
OP = "/" + "*"
CL = "*" + "/"
i, n = 0, len(raw)
while i < n:
    c = raw[i]
    nxt = raw[i + 1] if i + 1 < n else ""
    if c == "/" and nxt == "/":              # 行注释：跳到行尾（行注释不会嵌套，安全）
        k = raw.find("\n", i)
        i = n if k == -1 else k + 1
    elif c == "/" and nxt == "*":            # 块注释
        j = raw.find(CL, i + 2)
        if j == -1:
            sys.exit("块注释未闭合（可能是嵌套的斜杠加星号，或漏了结束符）")
        if OP in raw[i + 2:j]:
            line = raw[:i].count("\n") + 1
            sys.exit("第 %d 行起的块注释里出现了斜杠加星号：Kotlin 块注释会嵌套，会把文件剩余部分全吞掉" % line)
        i = j + 2
    elif c == '"':                            # 字符串字面量（含三引号）整体跳过，里面的符号不算
        if raw[i:i + 3] == '"""':
            k = raw.find('"""', i + 3)
            i = n if k == -1 else k + 3
        else:
            i += 1
            while i < n and raw[i] != '"':
                i += 2 if raw[i] == "\\" else 1
            i += 1
    elif c == "'":                            # 字符字面量
        i += 1
        while i < n and raw[i] != "'":
            i += 2 if raw[i] == "\\" else 1
        i += 1
    else:
        i += 1
PY
) || err "$f：$out"
done < <(find app/src/main/java app/src/test/java -name '*.kt' 2>/dev/null | sort)

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
#
# 【重要】代码模式只在“非注释行”里搜。
#   我们习惯在注释里写“这里绝不能调 handler.proceed()”这类警示语，
#   直接全文 grep 会让自己的警示注释触发规则（区块 1、区块 2 各踩过一次）。
code_grep() {
  local pattern="$1"
  local path="${2:-app/src/main/java}"
  grep -rn --include='*.kt' -E "$pattern" "$path" 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' \
    || true
}

if [ -n "$(code_grep 'setWebViewClient\(null\)')" ]; then
  err "setWebViewClient 不接受 null（API 35 参数是 @NonNull），请改用空实现或直接不设"
fi
if [ -n "$(code_grep '\bsafeBrowsingEnabled[[:space:]]*=')" ]; then
  err "WebSettings.safeBrowsingEnabled 没有 getter，不能用 Kotlin 属性语法，请改用 setSafeBrowsingEnabled(...)"
fi
if [ -n "$(code_grep 'addJavascriptInterface')" ]; then
  err "禁止 addJavascriptInterface（安全文档第 4 节：桥接只能走 WebViewCompat.addWebMessageListener 并按 origin 限定）"
fi
if [ -n "$(code_grep '\.proceed\(\)')" ]; then
  err "onReceivedSslError 里绝不能调 handler.proceed()（安全文档第 2 节：TLS 失败必须显式失败）"
fi

# 3.1 权限声明：读取媒体/外部存储一律禁止；写外部存储只允许指定 maxSdkVersion=28
out=$(python3 - app/src/main/AndroidManifest.xml 2>&1 <<'PY'
import re, sys
raw = open(sys.argv[1], encoding="utf-8").read()
problems = []
declared = set()
for block in re.findall(r'<uses-permission\b[^>]*>', raw, re.S):
    m = re.search(r'android:name="([^"]+)"', block)
    short = m.group(1).rsplit(".", 1)[-1] if m else ""
    declared.add(short)
    if short in ("READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION") \
            or short.startswith("READ_MEDIA_"):
        problems.append("禁止声明 " + short + "（文件选择走系统 SAF，不需要它）")
    elif short == "WRITE_EXTERNAL_STORAGE":
        max_sdk = re.search(r'android:maxSdkVersion="([^"]+)"', block)
        if not max_sdk or max_sdk.group(1) != "28":
            problems.append('WRITE_EXTERNAL_STORAGE 必须带 android:maxSdkVersion="28"'
                            "（只在 Android 8/9 上把下载写进公共目录，高版本不得生效）")
    elif short == "REQUEST_DELETE_PACKAGES":
        problems.append("禁止声明 REQUEST_DELETE_PACKAGES：壳不卸载任何应用")
# 必需权限（区块 3）：安装自己下载的更新包必须声明它，否则安装 Intent 会被系统直接拒绝。
for need in ("REQUEST_INSTALL_PACKAGES",):
    if need not in declared:
        problems.append("必须声明 " + need + "（安装自己下载的更新包需要）")
if problems:
    sys.exit("；".join(problems))
print("权限声明检查通过")
PY
) || err "AndroidManifest.xml：$out"

# 3.2 FileProvider 三处必须一致（不一致时只有真机运行才会报 SecurityException）
if grep -rq "getUriForFile" app/src/main/java; then
  grep -qF 'android:authorities="${applicationId}.fileprovider"' app/src/main/AndroidManifest.xml \
    || err "用了 getUriForFile，但清单里没有 ${applicationId}.fileprovider 这套 authority"
  grep -rq "BuildConfig.APPLICATION_ID" app/src/main/java/com/nhyzcms/clubapp/shell \
    || err "FileProvider authority 必须用 BuildConfig.APPLICATION_ID 拼（debug 变体带 .debug 后缀，写死包名会在运行时炸）"
  [ -f app/src/main/res/xml/file_paths.xml ] \
    || err "用了 FileProvider，但缺 app/src/main/res/xml/file_paths.xml"
fi

# 3.3 落盘位置必须统一由 DownloadPolicy 判定（写公共目录在 API ≤ 28 上需要写权限）
if grep -rq "setDestinationInExternalPublicDir" app/src/main/java; then
  grep -rq "DownloadPolicy.destination" app/src/main/java/com/nhyzcms/clubapp/MainActivity.kt \
    || err "落盘位置必须用 DownloadPolicy.destination() 统一判定，不要在下载器里写死"
  grep -rq "Build.VERSION.SDK_INT" app/src/main/java/com/nhyzcms/clubapp/MainActivity.kt \
    || err "落盘位置必须按 Build.VERSION.SDK_INT 分支（Android 8/9 无写权限时 pub 目录会 SecurityException）"
fi

# 3.5 更新流程（区块 3）：安装包必须走 FileProvider + 授权 flag，且必须校验 sha256
if [ -d app/src/main/java/com/nhyzcms/clubapp/shell/update ]; then
  if [ -z "$(code_grep 'FLAG_GRANT_READ_URI_PERMISSION' app/src/main/java/com/nhyzcms/clubapp/shell/update)" ]; then
    err "更新安装必须带 FLAG_GRANT_READ_URI_PERMISSION，否则系统安装器读不到 content URI"
  fi
  if [ -z "$(code_grep 'getUriForFile' app/src/main/java/com/nhyzcms/clubapp/shell/update)" ]; then
    err "安装更新必须通过 FileProvider（getUriForFile）交出一个文件的 content URI，不得用 file URI"
  fi
  if [ -z "$(code_grep 'ApkDigest\.matches\(' app/src/main/java/com/nhyzcms/clubapp/shell/update)" ]; then
    err "更新包必须做 sha256 校验（ApkDigest.matches），不得把未校验的文件交给安装器"
  fi
  if [ -n "$(code_grep 'DIRECTORY_DOWNLOADS' app/src/main/java/com/nhyzcms/clubapp/shell/update)" ]; then
    err "更新包不得写公共下载目录（语义不同且在 API 26~28 还要权限）；请留在 cacheDir/update"
  fi
  if [ -z "$(grep -n 'UPDATE_ALLOWED_HOSTS' app/src/main/java/com/nhyzcms/clubapp/shell/AppConfig.kt 2>/dev/null)" ]; then
    err "更新主机白名单必须写在 AppConfig.UPDATE_ALLOWED_HOSTS（唯一真源），不要在更新代码里另起一套"
  fi
fi

# 3.6 禁止用 raw string 承载「以引号开头/结尾」的 JSON 文本
#   区块 3 踩过：MiniJsonTest 里用 raw string 写了一段以引号开头、以引号结尾的 JSON，
#   正确写法是首尾各四个引号（四个连续引号组成一个内容引号 + 一个结束符）；
#   少写一个结尾引号 → JSON 字符串未闭合 → 解析返回 null → 断言在下一行失败，
#   报错只说 AssertionError，根因极难定位。改成用普通字符串拼 JSON 就没有这种歧义。
if [ -n "$(code_grep '""""' app/src/main/java; code_grep '""""' app/src/test/java)" ]; then
  err "源码里出现了连续四个引号（raw string 内容以引号开头/结尾）：改用普通字符串拼 JSON，避免首尾引号配对写错"
fi

# 3.4 密钥 / 本地配置不得入库
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
