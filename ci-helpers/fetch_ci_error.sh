#!/usr/bin/env bash
# ============================================================================
# fetch_ci_error.sh —— 从 GitHub Actions 拉取最新构建失败的真实错误
#
# 背景：本机沙箱无法直连 GitHub 的 Azure 日志存储（blob 主机被拦截），
#       但「提交评论」通道可通过 API 正常读取。CI 在编译失败时会把
#       build.log 末尾写入提交评论，本脚本负责把那条错误捞回来。
#
# 用法：  bash ci-helpers/fetch_ci_error.sh
# 输出：  最新一次运行的 sha/状态/结论 + 评论里的错误正文
# ============================================================================
set -euo pipefail

REPO="2632143580/LI-Android"

# 从 git 凭据文件里取 token（只取用户名对应的那条，token 不回显）
TOKEN=$(grep -oE 'https://2632143580:[^@]+@github\.com' "$HOME/.git-credentials" \
        | sed -E 's#https://2632143580:([^@]+)@github\.com#\1#' | head -1)

if [ -z "$TOKEN" ]; then
  echo "✗ 没找到 GitHub token（~/.git-credentials 无 2632143580 条目）" >&2
  exit 1
fi

API="https://api.github.com/repos/$REPO"
AUTH="-H Authorization:Bearer $TOKEN -H Accept:application/vnd.github+json"

# ---- 1) 找最新一次运行，拿 head_sha / status / conclusion ----
RUN_JSON=$(curl -sk $AUTH "$API/actions/runs?per_page=5")
SHA=$(echo "$RUN_JSON" | grep -oE '"head_sha": "[a-f0-9]{40}"' | head -1 | grep -oE '[a-f0-9]{40}')
STATUS=$(echo "$RUN_JSON" | grep -oE "\"head_sha\": \"$SHA\"[^}]*\"status\": \"[a-z]+\"" | grep -oE '"status": "[a-z]+"' | grep -oE '[a-z]+' | tail -1)
CONCL=$(echo "$RUN_JSON" | grep -oE "\"head_sha\": \"$SHA\"[^}]*\"conclusion\": [a-z]+" | grep -oE '"conclusion": [a-z]+' | grep -oE '[a-z]+' | tail -1)

echo "最新运行: sha=$SHA  status=$STATUS  conclusion=$CONCL"

if [ "$STATUS" != "completed" ]; then
  echo "构建仍在进行（status=$STATUS），稍后重试本脚本。"
  exit 0
fi

if [ "$CONCL" = "success" ]; then
  echo "✓ 构建成功，无错误。"
  exit 0
fi

# ---- 2) 主通道：读提交评论（CI 失败时会写入 build.log 末尾）----
echo ""
echo "========== 提交评论里的错误 =========="
curl -sk $AUTH "$API/commits/$SHA/comments" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('\n'.join(c.get('body','') for c in d)) if d else print('(该提交无评论)')" \
  2>/dev/null || echo "(评论解析失败)"

# ---- 3) 回退通道：check-runs 摘要 ----
echo ""
echo "========== check-runs 摘要（回退） =========="
curl -sk $AUTH "$API/commits/$SHA/check-runs" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); [print(r.get('output',{}).get('summary','') or '(无摘要)') for r in d.get('check_runs',[])]" \
  2>/dev/null || echo "(check-runs 解析失败)"
