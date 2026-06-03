# tools/

Developer utilities for OpenDST. Not part of the build.

## scan-commonpool-apis.sh

Lists every class in a JDK runtime image that delegates work to the shared
`ForkJoinPool` common pool.

The common pool runs work on platform `ForkJoinWorkerThread`s, which escape
OpenDST's deterministic per-node scheduler. The chosen strategy is to leave the
pool untouched and instead intercept the **public APIs that delegate to it**
(parallel streams, `Arrays.parallelSort`, `CompletableFuture.*Async`, ...),
making them run collaboratively on the node. This script produces the
authoritative list of those APIs.

The set changes between JDK releases — **re-run on every JDK upgrade** and
reconcile against the implemented interceptors.

```sh
tools/scan-commonpool-apis.sh [JAVA_HOME]   # defaults to $JAVA_HOME, else java on PATH
```

As of JDK 25 the common-pool users are: `Stream.parallel()`/`parallelStream()`
(`java.util.stream.AbstractTask`), `Arrays.parallelSort`/`parallelPrefix`/
`parallelSetAll`, `ConcurrentHashMap` bulk ops, `BigInteger` parallel
multiply/square, `CompletableFuture.*Async`, `Flow.SubmissionPublisher`, and
`StructuredTaskScope`.
