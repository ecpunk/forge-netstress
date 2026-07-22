#!/usr/bin/env bash
# Build script for forge_netstress (Forge 1.20.1 plain event-handler mod).
#
# Compiles with plain javac against the jars of an existing Forge 1.20.1
# installation — no Gradle, no network access. Point FORGE_LIBS at the
# `libraries` directory of any Forge 1.20.1-47.4.x installation:
#   - a Forge server install:            <server>/libraries
#   - a CurseForge client:               .../curseforge/minecraft/Install/libraries
#   - a vanilla-launcher Forge install:  .minecraft/libraries
#
# Usage:
#   FORGE_LIBS=/path/to/libraries [FORGE_VERSION=1.20.1-47.4.18] ./build.sh
#
# Packaging: plain jar, no Mixin (pure event-handler mod — no MixinConfigs
# manifest attribute, no mixins.json).
set -euo pipefail

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

LIBS="${FORGE_LIBS:-}"
if [ -z "$LIBS" ] || [ ! -d "$LIBS" ]; then
  echo "FATAL: set FORGE_LIBS to a Forge 1.20.1 installation's 'libraries' directory." >&2
  echo "  e.g. FORGE_LIBS=/path/to/libraries ./build.sh" >&2
  exit 1
fi
FORGE_VERSION="${FORGE_VERSION:-1.20.1-47.4.18}"

SRC_JAVA="$PROJ_DIR/src/main/java"
SRC_RES="$PROJ_DIR/src/main/resources"
BUILD_CLASSES="$PROJ_DIR/build/classes"
DIST_DIR="$PROJ_DIR/dist"
JAR_NAME="forge_netstress-0.1.jar"

rm -rf "$BUILD_CLASSES"
mkdir -p "$BUILD_CLASSES" "$DIST_DIR"

# --- Locate a library jar by its Maven-layout directory, any version ------
find_lib() { # find_lib <relative-dir-glob> <jar-glob>
  local hit
  hit=$(find "$LIBS" -type f -path "*/$1/*" -name "$2" 2>/dev/null | sort | tail -1)
  if [ -z "$hit" ]; then
    echo "FATAL: could not find $2 under $LIBS/**/$1/" >&2
    exit 1
  fi
  echo "$hit"
}

# --- Deobfuscate vanilla game classes to official names --------------------
# This mod's server-side handler walks the real player list (MinecraftServer
# -> PlayerList -> ServerPlayer) and its command (/netstress) is registered
# via the vanilla command layer (Commands / CommandSourceStack /
# SharedSuggestionProvider, RegisterCommandsEvent's dispatcher), unlike the
# forge-netleak-fix mod (github.com/ecpunk/forge-netleak-fix) which only ever touches Forge's own
# already-official-named API surface (SimpleChannel/NetworkEvent). Forge's
# distributed jars carry those pure
# vanilla members under SRG names (m_xxxx_/f_xxxx_) on disk; the "official"
# Mojang names only exist after a remap pass that ForgeGradle normally does
# for you. We do that pass ourselves, offline, using the deobfuscation
# tooling and mapping file Forge already ships in this same libraries tree
# (net/minecraftforge/ForgeAutoRenamingTool + the per-version mappings.txt,
# which is Mojang's official<->obfuscated ProGuard-format mapping). Source
# is the "slim" jar (pure vanilla game classes, pre-SRG, single-letter
# obfuscated names) — reversing that mapping gets us straight to official
# names without needing a separate obf<->SRG table.
SLIM_JAR="$(find_lib 'net/minecraft/server' '*-slim.jar')"
MC_MAPPINGS="$(find_lib 'net/minecraft/server' '*-mappings.txt')"
FART_JAR="$(find_lib 'net/minecraftforge/ForgeAutoRenamingTool' 'ForgeAutoRenamingTool-*-all.jar')"
OFFICIAL_MC_JAR="$PROJ_DIR/build/mc-official-$FORGE_VERSION.jar"

echo "== deobfuscating vanilla game classes (FART) =="
echo "   slim jar:  $SLIM_JAR"
echo "   mappings:  $MC_MAPPINGS"
java -jar "$FART_JAR" \
  --input "$SLIM_JAR" \
  --output "$OFFICIAL_MC_JAR" \
  --map "$MC_MAPPINGS" \
  --reverse \
  --ids-fix \
  --src-fix \
  --ann-fix \
  --record-fix \
  >/dev/null
echo "   -> $OFFICIAL_MC_JAR"

# --- Assemble compile classpath from the Forge installation ---------------
CP_JARS=(
  # Deobfuscated vanilla game classes (ServerPlayer, MinecraftServer,
  # PlayerList, FriendlyByteBuf, ResourceLocation, ...) — listed first so it
  # wins over any same-named SRG-named class also present in the jars below.
  "$OFFICIAL_MC_JAR"
  # Forge itself (SimpleChannel, NetworkEvent, PacketDistributor, TickEvent,
  # @Mod, IEventBus, etc.)
  # NOTE: must be the -universal jar: net.minecraftforge.network.* is only
  # present there.
  "$LIBS/net/minecraftforge/forge/$FORGE_VERSION/forge-$FORGE_VERSION-universal.jar"
  # Forge's server-side patch jar (kept for completeness; not needed for any
  # symbol we actually reference now that vanilla classes come from
  # OFFICIAL_MC_JAR above, but harmless to have on the classpath).
  "$LIBS/net/minecraftforge/forge/$FORGE_VERSION/forge-$FORGE_VERSION-server.jar"
  "$(find_lib 'net/minecraftforge/javafmllanguage' 'javafmllanguage-*.jar')"
  "$(find_lib 'net/minecraftforge/fmlcore' 'fmlcore-*.jar')"
  "$(find_lib 'net/minecraftforge/fmlloader' 'fmlloader-*.jar')"
  "$(find_lib 'net/minecraftforge/eventbus' 'eventbus-*.jar')"
  "$(find_lib 'net/minecraftforge/forgespi' 'forgespi-*.jar')"
  # net.minecraftforge.api.distmarker.Dist / @OnlyIn (used by DistExecutor
  # and the client-only @EventBusSubscriber annotation).
  "$(find_lib 'net/minecraftforge/mergetool' 'mergetool-*-api.jar')"
  # Netty (ByteBuf — FriendlyByteBuf extends it; needed to resolve
  # inherited writeBytes/readBytes/writeByteArray/readByteArray even though
  # we never import io.netty directly).
  "$(find_lib 'io/netty/netty-buffer' 'netty-buffer-*.jar')"
  "$(find_lib 'io/netty/netty-common' 'netty-common-*.jar')"
  # Brigadier (CommandDispatcher, LiteralArgumentBuilder, StringArgumentType,
  # CommandContext, SuggestionProvider — NetStressCommand's /netstress
  # registration is built directly on this).
  "$(find_lib 'com/mojang/brigadier' 'brigadier-*.jar')"
  # Logging (LogUtils / slf4j)
  "$(find_lib 'com/mojang/logging' 'logging-*.jar')"
  "$(find_lib 'org/slf4j/slf4j-api' 'slf4j-api-*.jar')"
)

for j in "${CP_JARS[@]}"; do
  if [ ! -f "$j" ]; then
    echo "FATAL: expected jar not found: $j" >&2
    echo "       (is FORGE_VERSION=$FORGE_VERSION correct for this install?)" >&2
    exit 1
  fi
done

CP="$(IFS=:; echo "${CP_JARS[*]}")"

# --- Compile --------------------------------------------------------------
echo "== javac =="
mapfile -t SOURCES < <(find "$SRC_JAVA" -name '*.java')
javac \
  --release 17 \
  -proc:none \
  -cp "$CP" \
  -d "$BUILD_CLASSES" \
  "${SOURCES[@]}"

echo "== compiled classes =="
find "$BUILD_CLASSES" -name '*.class'

# --- Assemble jar ---------------------------------------------------------
echo "== packaging jar =="
STAGE="$PROJ_DIR/build/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"

# classes
cp -r "$BUILD_CLASSES"/. "$STAGE"/

# resources: everything under src/main/resources EXCEPT META-INF/MANIFEST.MF
# (fed to `jar` via -m so it merges with the tool's own Manifest-Version
# header instead of being copied byte for byte).
mkdir -p "$STAGE/META-INF"
find "$SRC_RES" -mindepth 1 | while read -r f; do
  rel="${f#$SRC_RES/}"
  if [ "$rel" = "META-INF/MANIFEST.MF" ]; then
    continue
  fi
  if [ -d "$f" ]; then
    mkdir -p "$STAGE/$rel"
  else
    mkdir -p "$STAGE/$(dirname "$rel")"
    cp "$f" "$STAGE/$rel"
  fi
done

rm -f "$DIST_DIR/$JAR_NAME"
jar cfm "$DIST_DIR/$JAR_NAME" "$SRC_RES/META-INF/MANIFEST.MF" -C "$STAGE" .

echo "== jar built: $DIST_DIR/$JAR_NAME =="
echo "== jar contents =="
jar tf "$DIST_DIR/$JAR_NAME"

echo "== MANIFEST.MF as packaged =="
unzip -p "$DIST_DIR/$JAR_NAME" META-INF/MANIFEST.MF

echo "== class file version check (must be 61 = Java 17) =="
CLASS_FILE=$(find "$BUILD_CLASSES" -name '*.class' | head -1)
javap -v "$CLASS_FILE" | grep -m1 "major version"

echo "== build complete =="
