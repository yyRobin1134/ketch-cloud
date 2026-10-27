#!/usr/bin/env bash
# Commit and push the working tree, wait for the cloud-module check, then summarise its errors.
# usage: tools/ci.sh "commit message"
export PATH="$PATH:/c/Users/yy/tools/gh/bin"
cd "$(dirname "$0")/.." || exit 1
git add -A 2>/dev/null
git commit -q -m "$1

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" || true
GIT_TERMINAL_PROMPT=0 timeout 60 git pull -q --rebase 2>&1 | tail -1
GIT_TERMINAL_PROMPT=0 timeout 60 git push -q 2>&1 | tail -1
sleep 25
id=$(gh run list --workflow check-cloud.yml --limit 1 --json databaseId --jq '.[0].databaseId')
echo "$id" > /tmp/runid
timeout 400 gh run watch "$id" --interval 15 > /dev/null 2>&1
echo "result: $(gh run view "$id" --json status,conclusion --jq '.status+" "+.conclusion')"
gh run view "$id" --log-failed > /tmp/ci.log 2>&1
grep "e: file://" /tmp/ci.log | sed 's#^.*e: file:///home/runner/work/ketch-uc-source/ketch-uc-source/ketch/library/cloud/src/[A-Za-z]*/kotlin/##' | sort -u > /tmp/errs.txt
echo "errors: $(wc -l < /tmp/errs.txt)"
sed 's/:[0-9]*:[0-9]* .*//' /tmp/errs.txt | sort | uniq -c | sort -rn | head -30
grep -E "FAILED|tests completed" /tmp/ci.log | sed 's/^[^ ]*[[:space:]]*[^ ]*[[:space:]]*[0-9T:.Z-]* //' | head -10
