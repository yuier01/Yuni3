#!/bin/bash
# Yuni3 服务器端部署脚本
# 由 webhook 触发，或手动执行
# 默认从 GitHub Container Registry 拉取镜像；可传完整镜像名覆盖
# 用法: ./deploy.sh [image]

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE="${1:-ghcr.io/yuier01/yuni3:latest}"
PLUGINS_JSON="$DEPLOY_DIR/plugins.json"
PLUGIN_REPO="yuier01/Yuni3-plugin-repo"
PLUGIN_POM_URL="https://raw.githubusercontent.com/${PLUGIN_REPO}/master/pom.xml"
PLUGIN_LIST="/tmp/plugin-list.txt"

# GitHub 文件代理列表（按优先级尝试，腾讯云等国内服务器直连 GitHub 可能超时）
GHPROXIES=(
  "https://ghproxy.com/"
  "https://mirror.ghproxy.com/"
  ""
)

# ── 检查依赖 ─────────────────────────────────────────────────────────
command -v curl >/dev/null 2>&1 || { echo "需要安装 curl"; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "需要安装 unzip"; exit 1; }

# ── 准备目录 ─────────────────────────────────────────────────────────
mkdir -p "$DEPLOY_DIR/plugins" "$DEPLOY_DIR/data" "$DEPLOY_DIR/logs" "$DEPLOY_DIR/config"
chmod -R 755 "$DEPLOY_DIR/plugins"

# ── 生成待部署插件列表 ────────────────────────────────────────────────
# 唯一事实来源是 plugin-repo/pom.xml 的 <modules>：
# 新增插件时只需把模块加入 pom，部署脚本自动发现，不需要再维护 plugins.json。
echo "[deploy] 获取 plugin-repo 模块列表..."
: > "$PLUGIN_LIST"
POM_TMP="/tmp/yuni3-plugin-pom.xml"
pom_ok=0
for proxy in "${GHPROXIES[@]}"; do
  if curl -fsSL --connect-timeout 5 --max-time 30 "${proxy}${PLUGIN_POM_URL}" -o "$POM_TMP" \
      && grep -q '<modules>' "$POM_TMP"; then
    pom_ok=1
    break
  fi
done

if [ "$pom_ok" -eq 1 ]; then
  while read -r module; do
    module="$(echo "$module" | xargs)"
    [ -z "$module" ] && continue
    printf '%s %s %s\n' "$(basename "$module")" "$PLUGIN_REPO" "$module" >> "$PLUGIN_LIST"
  done < <(sed -n '/<modules>/,/<\/modules>/p' "$POM_TMP" \
             | grep '<module>' \
             | sed 's/.*<module>//;s/<\/module>.*//')
  rm -f "$POM_TMP"
else
  # 离线回退：只有 POM 拉取失败时才读取旧清单；正常情况下不再需要它。
  echo "[deploy] 无法获取 plugin-repo/pom.xml，回退到本地 plugins.json"
  command -v jq >/dev/null 2>&1 || { echo "回退需要 jq: apt install jq"; exit 1; }
  rm -f "$POM_TMP"
  [ -f "$PLUGINS_JSON" ] || { echo "没有可用的插件清单: $PLUGINS_JSON"; exit 1; }
  jq -r '.packages[] | "\(.module // .id) \(.repo) \(.module // .id)"' "$PLUGINS_JSON" \
    | awk '{ n=split($1,a,"/"); print a[n], $2, $3 }' > "$PLUGIN_LIST"
fi

if [ ! -s "$PLUGIN_LIST" ]; then
  echo "[deploy] 插件列表为空，终止部署"
  exit 1
fi
echo "[deploy] 待部署插件:"
sed 's/^/  /' "$PLUGIN_LIST"

# ── 下载插件 ─────────────────────────────────────────────────────────
echo "[deploy] 下载插件..."
# 写入临时文件避免管道中的 subshell 问题（& + wait 需要在同一 shell）

while read -r id repo module; do
  (
    # GitHub Release 的 tag / zip 名使用模块目录名；
    # 包 id 是展示/分类标识，可能与模块目录不同（例如 event-manage / eventmanage）。
    release_id="$(basename "$module")"
    base="https://github.com/${repo}/releases/download/${release_id}/${release_id}.zip"
    target="$DEPLOY_DIR/plugins/$release_id"
    ok=0
    for proxy in "${GHPROXIES[@]}"; do
      url="${proxy}${base}"
      if curl -fsSL --connect-timeout 5 --max-time 120 "$url" -o "/tmp/${release_id}.zip"; then
        mkdir -p "$target"
        # 保留插件自己的配置/数据文件，只清掉旧 jar，避免加载多个版本
        rm -f "$target"/*.jar
        unzip -o "/tmp/${release_id}.zip" -d "$DEPLOY_DIR/plugins/" >/dev/null 2>&1
        rm "/tmp/${release_id}.zip"
        echo "  $id ($release_id) OK"
        ok=1
        break
      fi
    done
    [ "$ok" -eq 0 ] && echo "  $id ($release_id) FAIL (所有代理均不可达)"
  ) &
done < "$PLUGIN_LIST"

wait
rm -f "$PLUGIN_LIST"
echo "[deploy] 插件下载完成"

# ── 启动 / 更新容器 ──────────────────────────────────────────────────
echo "[deploy] 从 GitHub Container Registry 拉取镜像 $IMAGE ..."
docker pull "$IMAGE"

if docker ps -q -f name=yuni3 | grep -q .; then
  echo "[deploy] 停止旧容器..."
  docker stop yuni3
  docker rm yuni3
fi

echo "[deploy] 启动容器..."
docker run -d \
  --name yuni3 \
  --restart unless-stopped \
  -p 11451:80 \
  -p 11452:11452 \
  -v "$DEPLOY_DIR/config:/app/config:ro" \
  -v "$DEPLOY_DIR/data:/app/data" \
  -v "$DEPLOY_DIR/logs:/app/logs" \
  -v "$DEPLOY_DIR/plugins:/app/plugins" \
  -v "/etc/localtime:/etc/localtime:ro" \
  -v "/etc/timezone:/etc/timezone:ro" \
  "$IMAGE"

echo "[deploy] 完成。日志: docker logs -f yuni3"
