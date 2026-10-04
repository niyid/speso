#!/bin/sh
# Two JVMs: Params are static, so the anchored (publisher) oracle needs its own process.
set -e
cd "$(dirname "$0")"
[ -d out ] || ./build.sh
java -cp out com.techducat.speso.SelfTest
java -cp out com.techducat.speso.AnchoredTest
java -cp out com.techducat.speso.DeflationTest
java -cp out com.techducat.speso.ToolsTest
