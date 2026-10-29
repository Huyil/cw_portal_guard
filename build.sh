#!/usr/bin/env bash
# CW Portal Guard —— 纯 javac/jar 构建（不联网、不依赖 Gradle）
# 用法：bash build.sh
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-/mnt/c/Users/huyil/.gradle/jdks/azul_systems__inc_-17-amd64-windows.2}"
JAVAC="$JDK/bin/javac.exe"
JAR="$JDK/bin/jar.exe"
MC="/mnt/m/Minecraft/1.20.1/.minecraft"
MODS="$MC/versions/Cube Warper/mods"
CLIENT="$MC/libraries/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar"
IPJAR="$MODS/immersive-portals-3.0.7-all.jar"

[ -x "$JAVAC" ] || { echo "找不到 javac: $JAVAC"; exit 1; }
[ -f "$CLIENT" ] || { echo "找不到 client srg jar: $CLIENT"; exit 1; }
[ -f "$IPJAR" ] || { echo "找不到 immersive portals: $IPJAR"; exit 1; }

FORGE=$(ls -1 "$MC"/libraries/net/minecraftforge/forge/1.20.1-47.*/forge-1.20.1-*-universal.jar | sort -V | tail -1)
[ -f "$FORGE" ] || { echo "找不到 forge universal jar"; exit 1; }
EVENTBUS=$(ls -1 "$MC"/libraries/net/minecraftforge/eventbus/*/eventbus-*.jar | sort -V | tail -1)
MIXIN=$(ls -1 "$MC"/libraries/org/spongepowered/mixin/*/mixin-*.jar | sort -V | tail -1)
JAVAFML=$(ls -1 "$MC"/libraries/net/minecraftforge/javafmllanguage/*/javafmllanguage-*.jar | sort -V | tail -1)
FMLCORE=$(ls -1 "$MC"/libraries/net/minecraftforge/fmlcore/*/fmlcore-*.jar | sort -V | tail -1)
SLF4J=$(ls -1 "$MC"/libraries/org/slf4j/slf4j-api/*/slf4j-api-*.jar | sort -V | tail -1)
ASM=$(ls -1 "$MC"/libraries/org/ow2/asm/asm/*/asm-*.jar | sort -V | tail -1)
ASMTREE=$(ls -1 "$MC"/libraries/org/ow2/asm/asm-tree/*/asm-tree-*.jar | sort -V | tail -1)
# Configured/JEI 兼容补丁的编译依赖（运行时由 mixin plugin 做存在性门控）
CONFIGURED=$(ls -1 "$MODS"/configured-forge-*.jar 2>/dev/null | sort -V | tail -1)

[ -f "$ASM" ] || { echo "找不到 asm jar: $ASM"; exit 1; }
[ -f "$ASMTREE" ] || { echo "找不到 asm-tree jar: $ASMTREE"; exit 1; }
[ -f "$CONFIGURED" ] || { echo "找不到 configured jar: $CONFIGURED"; exit 1; }

CP="$CLIENT;$FORGE;$EVENTBUS;$MIXIN;$JAVAFML;$FMLCORE;$SLF4J;$ASM;$ASMTREE;$CONFIGURED;$IPJAR"

OUT="$PROJ/build/classes"
rm -rf "$OUT"; mkdir -p "$OUT"

# 收集源码
SRCS=$(find "$PROJ/src" -name '*.java' | sort)
win() { wslpath -w "$1" | sed "s|\\\\|/|g"; }
WINPROJ=$(win "$PROJ")
ARGS="$PROJ/build/javac.args"
{
  echo "-encoding UTF-8"
  echo "-proc:none"
  echo "-nowarn"
  echo "-d \"$WINPROJ/build/classes\""
  # 把三个 jar 的 windows 路径拼成 -cp
  CPWIN=""
  IFS=';' read -ra PARTS <<< "$CP"
  for p in "${PARTS[@]}"; do
    wp=$(win "$p")
    if [ -z "$CPWIN" ]; then CPWIN="$wp"; else CPWIN="$CPWIN;$wp"; fi
  done
  echo "-cp \"$CPWIN\""
  for s in $SRCS; do
    echo "\"$(win "$s")\""
  done
} > "$ARGS"

echo "[1/4] javac ..."
"$JAVAC" "@$(win "$ARGS")"

echo "[2/4] 复制资源 ..."
cp "$PROJ/src/META-INF/mods.toml" "$OUT/META-INF/mods.toml" 2>/dev/null || {
  mkdir -p "$OUT/META-INF"; cp "$PROJ/src/META-INF/mods.toml" "$OUT/META-INF/mods.toml"; }
cp "$PROJ/src/cw_portal_guard.mixins.json" "$OUT/cw_portal_guard.mixins.json"
cp "$PROJ/src/cw_portal_guard.configured.mixins.json" "$OUT/cw_portal_guard.configured.mixins.json"
cp "$PROJ/src/pack.mcmeta" "$OUT/pack.mcmeta"

echo "[3/4] 打包 ..."
DIST="$PROJ/dist"; mkdir -p "$DIST"
VER=$(grep -m1 '^version=' "$PROJ/src/META-INF/mods.toml" | cut -d'"' -f2)
JARNAME="cw_portal_guard-$VER.jar"
rm -f "$DIST"/cw_portal_guard-*.jar
"$JAR" --create --file "$(win "$DIST/$JARNAME")" \
       --manifest "$(win "$PROJ/src/MANIFEST.MF")" \
       -C "$(win "$OUT")" .

echo "[4/4] 完成: $DIST/$JARNAME"
ls -la "$DIST"
