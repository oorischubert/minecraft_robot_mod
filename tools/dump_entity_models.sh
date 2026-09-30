#!/usr/bin/env bash
# Regenerates src/main/resources/assets/minebot/camera/entity_models.json (the mob shapes the server-side robot
# camera draws) from the game's own entity model code. Run it from anywhere after a Minecraft update, once
# ./gradlew build has prepared Loom's mapped Minecraft jars.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-./jdk-21.0.10+7/Contents/Home}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-.gradle-home}"
maven="$GRADLE_USER_HOME/caches/fabric-loom/minecraftMaven/net/minecraft"
client=$(find "$maven/minecraft-clientonly" -name "*yarn*.jar" ! -name "*sources*" | head -1)
common=$(find "$maven/minecraft-common" -name "*yarn*.jar" ! -name "*sources*" | head -1)

# The game's libraries, newest version of each, without Fabric itself.
libraries=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1" -name "*.jar" ! -name "*sources*" ! -name "*javadoc*" \
    ! -path "*net.fabricmc*" ! -path "*fabric-api*" | python3 -c '
import re, sys
best = {}
for line in sys.stdin:
    path = line.strip()
    parts = path.split("/")
    i = parts.index("files-2.1")
    group, artifact, version = parts[i + 1], parts[i + 2], parts[i + 3]
    key = (group, artifact, re.sub(r"[0-9.]+", "", parts[-1].replace(version, "")))
    rank = tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", version))
    if key not in best or rank > best[key][0]:
        best[key] = (rank, path)
print(":".join(entry[1] for entry in best.values()))
')

classpath="$client:$common:$libraries"
out=build/tools/entity-models
mkdir -p "$out"
"$JAVA_HOME/bin/javac" -proc:none -nowarn -d "$out" -cp "$classpath" tools/DumpEntityModels.java 2>&1 | grep -v "EnvType\|^Note\|warning" || true
"$JAVA_HOME/bin/java" -cp "$out:$classpath" DumpEntityModels > src/main/resources/assets/minebot/camera/entity_models.json
echo "Wrote src/main/resources/assets/minebot/camera/entity_models.json" >&2
