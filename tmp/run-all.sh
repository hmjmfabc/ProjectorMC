#!/data/data/com.termux/files/usr/bin/bash
# 交付前跑一遍全部验证套件（默认包的走 run-verify.sh；带包的单独用同一套 classpath）
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0
run() { # $1=dir $2=class
  out=$(bash tmp/run-verify.sh "tmp/$1" "$2" 2>&1)
  line=$(echo "$out" | grep -E "^== " | tail -1)
  [ -z "$line" ] && line="NO RESULT"
  echo "$2: $line"
  echo "$out" | grep -E "❌" | head -5
  case "$line" in *FAILED*) fail=$((fail+1));; "NO RESULT") fail=$((fail+1));; esac
}
run vt T;  run v2 T2; run v3 T3; run v4 T4; run v4 T5
run v6 T6; run v7 T7; run v8 T8; run v9 T9; run v10 T10
run v11 T11; run v12 T12; run v13 T13; run v14 T14; run v15 T15
run v16 T16; run v17 T17; run v18 T18; run v19 T19; run v20 T20; run v22 T22

# 带包名的三个（T21 下载状态机 / T23 媒体复用）
MCJAR=$HOME/.gradle/caches/ng_execute/9bc8e6615382df49e4de688d85fb7ec0f6fa267d9cea7f2a890fffc35f730d24/outputs.jar
EXTRA=$(find $HOME/.gradle/caches/modules-2 -name "*.jar" 2>/dev/null | grep -vE "sources|javadoc" \
  | grep -E "logging|fastutil|datafixerupper|brigadier|guava|commons-lang3|slf4j|log4j|authlib|jsr5|annotations|netty|gson|jopt|oshi|joml|lwjgl|neoforge|fancymodloader" \
  | grep -vE "night-config/(toml|json)" | tr '\n' ':')
NIGHTCONF=$(find $HOME/.gradle/caches/modules-2/files-2.1/com.electronwill.night-config \
  -name "*.jar" 2>/dev/null | grep -vE "sources|javadoc" | tr '\n' ':')
CP="$MCJAR:${EXTRA}${NIGHTCONF}build/classes/java/main"
pkg() { # $1=dir $2=fqcn
  mkdir -p "tmp/$1/out"
  javac -encoding UTF-8 -proc:none -cp "$CP" -d "tmp/$1/out" "tmp/$1/${2##*.}.java" 2>&1 | grep -E "error" | head -3
  out=$(java -Dfile.encoding=UTF-8 -cp "$CP:tmp/$1/out" "$2" 2>&1)
  line=$(echo "$out" | grep -E "^== " | tail -1)
  [ -z "$line" ] && line="NO RESULT"
  echo "$(basename "$2"): $line"
  echo "$out" | grep -E "❌" | head -5
  case "$line" in *FAILED*) fail=$((fail+1));; "NO RESULT") fail=$((fail+1));; esac
}
pkg v21 top.hmjmfabc.projector.client.media.T21
pkg v23 top.hmjmfabc.projector.client.media.T23
pkg v24 top.hmjmfabc.projector.client.media.T24
pkg v25 top.hmjmfabc.projector.client.media.T25
pkg v26 top.hmjmfabc.projector.client.media.T26

echo "--- 失败套件数: $fail ---"
