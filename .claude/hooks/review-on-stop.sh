#!/bin/bash
# Stop hook: review what changed since the last review with a headless Claude reviewer.
#
# Repos: $CLAUDE_REVIEW_REPOS (space separated), default this repo and a sibling ../sing-box checkout.
# State: the reviewed working-tree snapshot (a git tree id) per repo, so a change is reviewed once.
# Disable with CLAUDE_REVIEW_DISABLE=1. Model: $CLAUDE_REVIEW_MODEL (default opus[1m]).

set -u

input=$(cat)
if [ "$(jq -r '.stop_hook_active // false' <<<"$input")" = "true" ]; then
  exit 0
fi
[ "${CLAUDE_REVIEW_DISABLE:-0}" = "1" ] && exit 0
command -v claude >/dev/null || exit 0

hook_dir=$(cd "$(dirname "$0")" && pwd)
agent_file="$hook_dir/../agents/code-reviewer.md"
state_dir="${XDG_STATE_HOME:-$HOME/.local/state}/claude-review"
mkdir -p "$state_dir"

# the hook can be registered both in user and project settings; run one review at a time
exec 9>"$state_dir/lock"
flock 9

project=$(cd "$hook_dir/../.." && pwd)
repos=${CLAUDE_REVIEW_REPOS:-"$project $(dirname "$project")/sing-box"}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Snapshot the working tree, untracked files included, without touching the real index.
snapshot_tree() {
  local repo=$1 idx="$work/index"
  rm -f "$idx"
  GIT_INDEX_FILE="$idx" git -C "$repo" read-tree HEAD &&
    GIT_INDEX_FILE="$idx" git -C "$repo" add -A . 2>/dev/null &&
    GIT_INDEX_FILE="$idx" git -C "$repo" write-tree
}

diff_file="$work/changes.diff"
: >"$diff_file"
declare -A new_state
for repo in $repos; do
  git -C "$repo" rev-parse --git-dir >/dev/null 2>&1 || continue
  key=$(printf '%s' "$repo" | tr '/' '_')
  tree=$(snapshot_tree "$repo") || continue
  base=$(cat "$state_dir/$key" 2>/dev/null)
  [ -n "$base" ] || base=$(git -C "$repo" rev-parse 'HEAD^{tree}')
  [ "$base" = "$tree" ] && continue
  if ! git -C "$repo" cat-file -e "$base" 2>/dev/null; then
    base=$(git -C "$repo" rev-parse 'HEAD^{tree}')
    [ "$base" = "$tree" ] && continue
  fi
  {
    echo "### Repository: $repo (branch $(git -C "$repo" branch --show-current), HEAD $(git -C "$repo" rev-parse --short HEAD))"
    git -C "$repo" diff --stat "$base" "$tree"
    git -C "$repo" diff -M "$base" "$tree"
  } >>"$diff_file"
  new_state[$key]=$tree
done

[ -s "$diff_file" ] || exit 0

system_prompt=$(awk 'BEGIN{n=0} /^---$/ && n<2 {n++; next} n>=2' "$agent_file")
prompt="Review the following changes made since the last review. The full repositories are available
on disk at the paths shown, read whatever context you need. The diff is in $diff_file.

$(head -c 400000 "$diff_file")"

review=$(cd "$(dirname "$project")" || exit; CLAUDE_REVIEW_DISABLE=1 timeout "${CLAUDE_REVIEW_TIMEOUT:-1500}" claude -p \
  --model "${CLAUDE_REVIEW_MODEL:-opus[1m]}" \
  --append-system-prompt "$system_prompt" \
  --permission-mode dontAsk \
  --allowedTools Read Grep Glob WebSearch WebFetch \
    'Bash(git show:*)' 'Bash(git log:*)' 'Bash(git diff:*)' 'Bash(git blame:*)' 'Bash(git grep:*)' \
  --disallowedTools Agent Task Workflow Write Edit NotebookEdit \
  --setting-sources user \
  --output-format text \
  <<<"$prompt" 2>"$work/err")
status=$?

if [ $status -ne 0 ] || [ -z "$review" ]; then
  jq -n --arg m "Code review hook failed (exit $status): $(tail -c 500 "$work/err")" '{systemMessage: $m}'
  exit 0
fi

for key in "${!new_state[@]}"; do
  printf '%s\n' "${new_state[$key]}" >"$state_dir/$key"
done
printf '%s\n' "$review" >"$state_dir/last-review.txt"

if head -n 1 <<<"$review" | grep -q 'VERDICT: OK'; then
  jq -n '{systemMessage: "Code review: no issues found"}'
  exit 0
fi

jq -n --arg r "Automated code review found possible problems. Verify each finding, fix the real ones (or explain why not) before finishing:

$review" '{decision: "block", reason: $r}'
