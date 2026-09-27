#!/data/data/com.termux/files/usr/bin/bash
# 用真实 1.21.1 + NeoForge 当 classpath 直接跑生产代码的纯逻辑验证。
# 用法：bash tmp/run-verify.sh tmp/v6 T6
set -e
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MCJAR=$HOME/.gradle/caches/ng_execute/9bc8e6615382df49e4de688d85fb7ec0f6fa267d9cea7f2a890fffc35f730d24/outputs.jar
# 注意 1：只取 modules-2 下的 jar。gradle 发行版目录里的 slf4j 是 instrumented 版，
#          加进来会报 org.gradle.internal.classpath.Instrumented 找不到。
# 注意 2：想让被测代码里 Projector.LOGGER 能跑（会静态加载 ProjectorConfig），
#          必须带上 NeoForge 与 fancymodloader 的 loader jar（IConfigSpec 在里面）。
EXTRA=$(find $HOME/.gradle/caches/modules-2 -name "*.jar" 2>/dev/null \
  | grep -vE "sources|javadoc" \
  | grep -E "logging|fastutil|datafixerupper|brigadier|guava|commons-lang3|slf4j|log4j|authlib|jsr5|annotations|netty|gson|jopt|oshi|joml|lwjgl|neoforge|fancymodloader" \
  | grep -vE "night-config/(toml|json)" \
  | tr '\n' ':')
# night-config（nightconfig）的 jar 名是 core-3.8.3.jar / toml-3.8.3.jar，按库名 grep 不到，
# 必须按路径补进来：ProjectorConfig 的静态初始化要用它（T17 要读配置项范围）。
NIGHTCONF=$(find $HOME/.gradle/caches/modules-2/files-2.1/com.electronwill.night-config \
  -name "*.jar" 2>/dev/null | grep -vE "sources|javadoc" | tr '\n' ':')
CP="$MCJAR:${EXTRA}${NIGHTCONF}build/classes/java/main"
./gradlew build --offline -q 2>&1 | grep -E "error:" -A3 || true
DIR=$1; CLS=$2
# 先删旧 class：否则编译失败时会跑到上一轮的产物，测试「假通过」
rm -rf "$DIR"/*.class
if ! javac -encoding UTF-8 -cp "$CP" -d "$DIR" "$DIR/$CLS.java" > "$DIR/.javac.log" 2>&1; then
  echo "== COMPILE FAILED =="
  grep -E "error" "$DIR/.javac.log" | head -5
  exit 1
fi
java -Dfile.encoding=UTF-8 -cp "$CP:$DIR" "$CLS"
