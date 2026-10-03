#!/bin/sh
# Compile main + tests into ./out. Uses javac if present, else the jdk.compiler module (works on a plain JRE image).
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir out
FILES=$(find src -name '*.java')
if command -v javac >/dev/null 2>&1; then javac -encoding UTF-8 -d out $FILES
else java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d out $FILES; fi
echo "built -> out/"
