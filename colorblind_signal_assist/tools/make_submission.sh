#!/usr/bin/env bash
# 공모전 제출용 '익명' 저장소를 만든다 (블라인드 심사: 성명·연락처·소속·계정정보 노출 금지).
#
#   tools/make_submission.sh <팀명> [출력 폴더] [추가로 검사할 개인정보 단어...]
#
# 하는 일
#   1) release APK 빌드 -> apk/<팀명>_모두의운전석_release.apk
#   2) 이 프로젝트 파일만 새 폴더로 복사 (기존 git 기록은 가져가지 않음)
#   3) 새 git 저장소를 만들고 작성자를 팀명으로 커밋 (실명·이메일이 커밋에 남지 않음)
#   4) 현재 PC 의 git 이름/이메일과 추가 단어가 파일에 남아 있는지 검사
#
# 그 다음: 개인정보가 드러나지 않는 GitHub 계정(또는 조직)에 빈 저장소를 만들고 push 한다.
set -euo pipefail

TEAM="${1:?사용법: tools/make_submission.sh <팀명> [출력 폴더] [검사할 단어...]}"
OUT="${2:-../submission_${TEAM}}"
shift $(( $# >= 2 ? 2 : 1 ))
APP="모두의운전석"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"

if [ -n "$(ls -A "$OUT" 2>/dev/null)" ]; then
  echo "출력 폴더가 비어 있지 않습니다: $OUT" >&2; exit 1
fi

echo "▶ release APK 빌드"
( cd "$HERE" && ./gradlew -q :app:assembleRelease )

echo "▶ 프로젝트 복사 -> $OUT"
( cd "$HERE" && git -c core.quotePath=false ls-files -z -co --exclude-standard . ) | while IFS= read -r -d '' f; do
  # 팀원 이름이 들어 있는 접수용 정리본은 익명 제출본에 넣지 않는다
  case "$f" in apk/*|docs/팀구성_*) continue ;; esac
  mkdir -p "$OUT/$(dirname "$f")"
  cp -p "$HERE/$f" "$OUT/$f"
done
mkdir -p "$OUT/apk"
cp "$HERE/app/build/outputs/apk/release/app-release.apk" "$OUT/apk/${TEAM}_${APP}_release.apk"
sed -i "s/팀명_${APP}_release.apk/${TEAM}_${APP}_release.apk/g" "$OUT/README.md"

echo "▶ 개인정보 검사"
WORDS=("$@")
for w in "$(git config user.name || true)" "$(git config user.email || true)"; do
  [ -n "$w" ] && WORDS+=("$w") && WORDS+=("${w%@*}")
done
FOUND=0
# .docx/.pptx 는 압축 파일이라 grep 이 못 읽는다 -> 풀어서 본문까지 검사
DOCX_TXT="$(mktemp -d)"
find "$OUT" -name '*.docx' -o -name '*.pptx' | while read -r f; do
  unzip -p "$f" 'word/*.xml' 'ppt/*.xml' 'docProps/*.xml' 2>/dev/null | sed 's/<[^>]*>//g' > "$DOCX_TXT/$(basename "$f").txt"
done
for w in "${WORDS[@]}"; do
  [ -z "$w" ] && continue
  case "$w" in noreply|*noreply*) continue ;; esac   # 일반 단어 (개인정보 아님)
  if grep -rIl --exclude-dir=.git -F -- "$w" "$OUT" >/dev/null 2>&1; then
    echo "  ⚠ '$w' 발견:"; grep -rIl --exclude-dir=.git -F -- "$w" "$OUT" | sed 's/^/     /'; FOUND=1
  fi
  if grep -rlF -- "$w" "$DOCX_TXT" >/dev/null 2>&1; then
    echo "  ⚠ '$w' 발견 (문서 본문):"; grep -rlF -- "$w" "$DOCX_TXT" | sed 's#.*/#     #; s/\.txt$//'; FOUND=1
  fi
done
rm -rf "$DOCX_TXT"
[ "$FOUND" = 0 ] && echo "  ✓ 검사한 단어가 파일에 없습니다"

echo "▶ 새 git 저장소 (작성자: $TEAM)"
( cd "$OUT" && git init -q && git add -A && \
  git -c user.name="$TEAM" -c user.email="${TEAM}@users.noreply.github.com" \
      commit -q -m "${APP}: 차량용 접근성 운전 보조 앱 (공모전 제출본)" )

cat <<MSG

완료: $OUT
  APK: apk/${TEAM}_${APP}_release.apk
다음 단계:
  1. 실명·학교·회사가 드러나지 않는 GitHub 계정/조직에 빈 저장소 생성 (저장소 이름도 익명으로)
  2. cd "$OUT" && git remote add origin <저장소 주소> && git push -u origin HEAD:main
  3. GitHub 프로필(이름·사진·소개)에도 개인정보가 없는지 확인
MSG
