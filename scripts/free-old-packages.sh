#!/usr/bin/env bash
# GitHub Packages monorepo cut-over helper. Requires a PAT with delete:packages (+read:packages).
#
#   export GH_DELETE_TOKEN=ghp_xxx
#   ./free-old-packages.sh                 # DRY-RUN: list what the two steps would touch
#   ./free-old-packages.sh --apply         # 1) delete OLD-repo packages  2) purge partial VERSION from monorepo
#
# Two things it does (both needed after a partially-failed monorepo deploy):
#   1. Deletes every Maven package still OWNED BY the old per-repo repos (frees the name so the
#      monorepo can claim it). GitHub binds a package name to ONE repo; publishing from another
#      returns 422 until the old package is deleted.
#   2. Deletes version $VERSION from packages already owned by the MONOREPO, so a clean full
#      redeploy of that (immutable, released) version does not 422 with "version already exists".
#
# NOTE: deletion is permanent (all versions for step 1; just $VERSION for step 2).
set -euo pipefail
ORG=garganttua
MONOREPO=garganttua                                   # the new owning repo
OLD_REPOS=" garganttua-core garganttua-api garganttua-events "
VERSION=3.0.0-ALPHA05                                 # version being (re)published
API=https://api.github.com
TOKEN=${GH_DELETE_TOKEN:?set GH_DELETE_TOKEN to a PAT with delete:packages}
APPLY=${1:-}
hdr=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")

# --- paginated listing: "name<space>ownerRepo" for every maven package (fixes single-page bug) ---
list_packages() {
  local page=1
  while :; do
    local body; body=$(curl -s "${hdr[@]}" "$API/orgs/$ORG/packages?package_type=maven&per_page=100&page=$page")
    local out; out=$(printf '%s' "$body" | python3 -c 'import sys,json
d=json.load(sys.stdin); print(len(d))
for p in d: print(p["name"],(p.get("repository") or {}).get("name",""))')
    printf '%s\n' "$out" | tail -n +2
    [ "$(printf '%s\n' "$out" | head -1)" -lt 100 ] && break
    page=$((page+1))
  done
}

del_pkg() { curl -s -o /dev/null -w "%{http_code}" -X DELETE "${hdr[@]}" "$API/orgs/$ORG/packages/maven/$1"; }
del_ver() { # $1=pkg -> if VERSION is present: delete it, or (if it's the ONLY version) delete the whole package
             #          (GitHub returns 400 on deleting a package's last version — must delete the package)
  local info; info=$(curl -s "${hdr[@]}" "$API/orgs/$ORG/packages/maven/$1/versions" \
    | python3 -c "import sys,json
vs=json.load(sys.stdin)
vid=next((str(v['id']) for v in vs if v['name']=='$VERSION'),'')
print(vid, len(vs))")
  local vid; vid=$(printf '%s' "$info" | cut -d' ' -f1)
  local cnt; cnt=$(printf '%s' "$info" | cut -d' ' -f2)
  [ -z "$vid" ] && { echo "SKIP(no $VERSION)"; return; }
  if [ "$cnt" = "1" ]; then echo "whole-pkg $(del_pkg "$1")"          # only version -> delete package
  else curl -s -o /dev/null -w "%{http_code}" -X DELETE "${hdr[@]}" "$API/orgs/$ORG/packages/maven/$1/versions/$vid"; fi
}

pkgs=$(list_packages)

echo "### STEP 1 — free names still owned by old repos ($OLD_REPOS)"
printf '%s\n' "$pkgs" | while read -r name repo; do
  case "$OLD_REPOS" in *" $repo "*)
    if [ "$APPLY" = "--apply" ]; then echo "DELETE pkg $name (was $repo) -> HTTP $(del_pkg "$name")"
    else echo "would delete pkg: $name  (owned by $repo)"; fi ;;
  esac
done

echo "### STEP 2 — purge partial $VERSION from monorepo-owned packages"
printf '%s\n' "$pkgs" | while read -r name repo; do
  if [ "$repo" = "$MONOREPO" ]; then
    if [ "$APPLY" = "--apply" ]; then echo "DELETE $name@$VERSION -> $(del_ver "$name")"
    else echo "would purge version: $name@$VERSION (if present)"; fi
  fi
done
echo "done. Then re-run the full 'mvn -B -ntp deploy'."
