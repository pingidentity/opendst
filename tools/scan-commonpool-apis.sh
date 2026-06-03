#!/usr/bin/env bash
#
# Copyright 2026 Ping Identity Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# ---------------------------------------------------------------------------
# scan-commonpool-apis.sh
#
# Enumerates every class in a JDK runtime image that delegates work to the
# shared ForkJoinPool common pool (java.util.concurrent.ForkJoinPool.common).
#
# Why this matters for OpenDST: the common pool runs work on platform
# ForkJoinWorkerThreads, which escape the deterministic per-node scheduler and
# break reproducibility. Rather than touch the pool itself, OpenDST intercepts
# the *public APIs* that delegate to it (parallel streams, Arrays.parallelSort,
# CompletableFuture.*Async, ...) and makes them run collaboratively on the node.
# This script produces the authoritative list of those APIs to intercept.
#
# The set of common-pool users changes between JDK releases, so RE-RUN THIS on
# every JDK upgrade and reconcile the result against the implemented
# interceptors.
#
# A class is flagged when its constant pool references any of:
#   - ForkJoinPool.commonPool()
#   - ForkJoinPool.getCommonPoolParallelism()
#   - ForkJoinPool.asyncCommonPool()
# ForkJoinPool's own implementation classes are excluded (they define, not use,
# the pool).
#
# Usage:
#   tools/scan-commonpool-apis.sh [JAVA_HOME]
#
# JDK selection (first match wins):
#   1. the JAVA_HOME passed as $1
#   2. the JAVA_HOME environment variable
#   3. the JDK on PATH (via the location of `java`)
# ---------------------------------------------------------------------------

set -euo pipefail

# --- Locate the JDK ---------------------------------------------------------
java_home="${1:-${JAVA_HOME:-}}"
if [[ -z "${java_home}" ]]; then
    if command -v java >/dev/null 2>&1; then
        # Resolve <jdk>/bin/java -> <jdk>
        java_home="$(cd "$(dirname "$(readlink -f "$(command -v java)")")/.." && pwd)"
    else
        echo "error: no JDK found. Pass JAVA_HOME as the first argument or set \$JAVA_HOME." >&2
        exit 1
    fi
fi

jimage="${java_home}/bin/jimage"
modules="${java_home}/lib/modules"
for required in "${jimage}" "${modules}"; do
    if [[ ! -e "${required}" ]]; then
        echo "error: not a valid JDK image (missing ${required})." >&2
        exit 1
    fi
done

echo "Scanning JDK: ${java_home}" >&2
"${java_home}/bin/java" -version 2>&1 | sed 's/^/  /' >&2
echo >&2

# --- Extract and scan -------------------------------------------------------
workdir="$(mktemp -d "${TMPDIR:-/tmp}/opendst-fjp-scan.XXXXXX")"
trap 'rm -rf "${workdir}"' EXIT

echo "Extracting runtime image..." >&2
"${jimage}" extract --dir "${workdir}" "${modules}"

total="$(find "${workdir}" -name '*.class' | wc -l | tr -d ' ')"
echo "Scanning ${total} classes for common-pool references..." >&2
echo >&2

# Grep the constant pools for the common-pool entry points, drop ForkJoinPool's
# own implementation classes, and render module/package paths as FQNs.
#   <workdir>/java.base/java/util/Arrays.class -> java.base/java.util.Arrays
hits="$(
    grep -rla -E 'commonPool|getCommonPoolParallelism|asyncCommonPool' \
        "${workdir}" --include='*.class' 2>/dev/null \
        | grep -vE '/ForkJoinPool(\$|\.class)|/ForkJoinTask(\$|\.class)|/ForkJoinWorkerThread(\$|\.class)' \
        | sed -e "s#^${workdir}/##" -e 's#\.class$##' \
        | awk -F/ '{ mod=$1; sub(/^[^/]*\//, ""); gsub(/\//, "."); print mod "/" $0 }' \
        | sort -u
)"

if [[ -z "${hits}" ]]; then
    echo "No common-pool users found (unexpected). Check JDK version / grep patterns." >&2
    exit 1
fi

echo "Classes delegating to the ForkJoinPool common pool (intercept these APIs):"
echo "${hits}" | sed 's/^/  /'
echo
echo "Count: $(echo "${hits}" | wc -l | tr -d ' ')"
