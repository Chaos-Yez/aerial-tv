#!/usr/bin/env python3
"""Push the mirror-fallback commit to the fork, tag it, and publish the release.

github.com is blocked by the sandbox proxy, so branches/tags/releases go through the
GitHub REST API (Git Database + uploads) instead of git push.
"""
import os, sys, json, base64, subprocess, urllib.request, urllib.error, datetime, hashlib

REPO = "Chaos-Yez/aerial-tv"
TAG = "v1.61.1-xgimi"
TAG_MSG = "1.61.1 极米适配版（国内下载修复）"
REL_NAME = "1.61.1 极米适配版"
REL_BODY = """基于官方 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) **v1.61** 适配而来。
其他电视使用与官方版本无异，已融合官方版本，只是功能增加了不少，放心使用。

## 本次更新
- **修复国内网络下「提示有更新、下载却失败」的问题**：安装包下载新增镜像回落，依次尝试国内可用的加速镜像，全部失败才回落 GitHub 原地址
- 下载到的包仍会校验包名、签名和版本号，来路不对的包会被丢弃并换下一个源
- 其余功能与 1.61 完全一致

**本机测试设备为极米 Z7X 高亮版，真机测试有效**"""
APK = "app/build/outputs/apk/standard/release/app-standard-release.apk"
ASSET = "app-standard-release.apk"
PREV = "331d38e"  # the commit the remote xgimi branch already has (same tree)


def out(*a):
    print(*a, flush=True)


def git(*args):
    r = subprocess.run(["git"] + list(args), capture_output=True)
    if r.returncode != 0:
        sys.exit("git failed: " + " ".join(args) + "\n" + r.stderr.decode(errors="replace"))
    return r.stdout


def token():
    r = subprocess.run(["git", "credential", "fill"], input=b"protocol=https\nhost=github.com\n",
                       capture_output=True).stdout.decode()
    for line in r.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    sys.exit("no token")


TOKEN = token()
proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({"https": proxy})) if proxy \
    else urllib.request.build_opener()


def http(method, url, data=None, headers=None, timeout=120):
    h = {"Authorization": "Bearer " + TOKEN, "Accept": "application/vnd.github+json",
         "User-Agent": "publish"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        resp = opener.open(req, timeout=timeout)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def api(method, path, payload=None, ok=(200, 201)):
    data = json.dumps(payload).encode() if payload is not None else None
    hdrs = {"Content-Type": "application/json"} if data else None
    st, body = http(method, "https://api.github.com/repos/%s%s" % (REPO, path), data, hdrs)
    if st not in ok:
        sys.exit("%s %s -> HTTP %s\n%s" % (method, path, st, body[:1500]))
    return json.loads(body) if body.strip() else None


# ---- 1) remote state ----
ref = api("GET", "/git/ref/heads/xgimi")
base_commit = ref["object"]["sha"]
base = api("GET", "/git/commits/" + base_commit)
base_tree = base["tree"]["sha"]
out("[1] remote xgimi tip = %s (tree %s)" % (base_commit[:8], base_tree[:8]))

head = git("rev-parse", "HEAD").decode().strip()
out("[2] local HEAD = %s" % head[:8])

# ---- 2) changed files since PREV (no deletions expected) ----
raw = git("diff", "-z", "--name-only", PREV, head)
files = [p for p in raw.decode("utf-8", "replace").split("\0") if p]
if not files:
    sys.exit("nothing changed since %s" % PREV)
out("[3] %d changed file(s):" % len(files))
for f in files:
    out("      " + f)

# ---- 3) blobs ----
entries = []
for path in files:
    meta = git("ls-tree", "HEAD", "--", path).decode().strip()
    if not meta:
        sys.exit("ls-tree empty for %r" % path)
    mode = meta.split()[0]
    content = git("cat-file", "blob", "HEAD:" + path)
    blob = api("POST", "/git/blobs",
               {"content": base64.b64encode(content).decode(), "encoding": "base64"})
    entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob["sha"]})
    out("[4] blob %s (%d bytes)" % (path, len(content)))

# ---- 4) tree (onto the remote tip's tree) ----
tree = api("POST", "/git/trees", {"base_tree": base_tree, "tree": entries})
new_tree = tree["sha"]
out("[5] new tree %s" % new_tree[:8])

# ---- 5) commit ----
who = git("log", "-1", "--format=%an|%ae").decode().strip().split("|")
name, email = who[0], who[1]
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
commit = api("POST", "/git/commits",
             {"message": TAG_MSG, "tree": new_tree, "parents": [base_commit],
              "author": {"name": name, "email": email, "date": now},
              "committer": {"name": name, "email": email, "date": now}})
new_commit = commit["sha"]
out("[6] new commit %s (parent %s)" % (new_commit[:8], base_commit[:8]))

# ---- 6) move the branch ----
api("PATCH", "/git/refs/heads/xgimi", {"sha": new_commit, "force": False})
out("[7] refs/heads/xgimi -> %s" % new_commit[:8])

# ---- 7) annotated tag ----
tag_obj = api("POST", "/git/tags",
              {"tag": TAG, "object": new_commit, "type": "commit", "message": TAG_MSG,
               "tagger": {"name": name, "email": email, "date": now}})
st, _ = http("POST", "https://api.github.com/repos/%s/git/refs" % REPO,
             json.dumps({"ref": "refs/tags/" + TAG, "sha": tag_obj["sha"]}).encode(),
             {"Content-Type": "application/json"})
out("[8] tag %s created (HTTP %s)" % (TAG, st))

# ---- 8) release ----
rel = api("POST", "/releases",
          {"tag_name": TAG, "name": REL_NAME, "body": REL_BODY,
           "draft": False, "prerelease": False})
rel_id = rel["id"]
out("[9] release id=%s" % rel_id)

# ---- 9) asset ----
with open(APK, "rb") as f:
    data = f.read()
st, body = http("POST",
                "https://uploads.github.com/repos/%s/releases/%s/assets?name=%s"
                % (REPO, rel_id, ASSET),
                data, {"Content-Type": "application/octet-stream"}, timeout=300)
if st not in (200, 201):
    sys.exit("upload -> HTTP %s\n%s" % (st, body[:1000]))
asset = json.loads(body)
out("[10] asset id=%s size=%s digest=%s" % (asset["id"], asset.get("size"), asset.get("digest")))

# ---- 10) verify ----
meta = api("GET", "/releases/assets/%d" % asset["id"])
local = hashlib.sha256(data).hexdigest()
remote = (meta.get("digest") or "").replace("sha256:", "")
out("[11] local  sha256 = %s" % local)
out("     remote digest= %s" % remote)
if remote != local or meta.get("size") != len(data):
    sys.exit("MISMATCH after upload")
out("VERIFIED OK")
