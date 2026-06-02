import groovy.json.JsonSlurper

// Verify thread-related determinism guards:
// 1. Platform thread shutdown hooks (e.g. JUL's LogManager$Cleaner) must be skipped
// 2. Thread subclasses are rewritten to extend SimulatorThread and run correctly
// 3. java.util.Timer is redirected to SimulatorTimer (virtual worker, not TimerThread)

File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

def check(boolean condition, String message, File file) {
    if (!condition) {
        println "Verification failed: ${message}"
        def lines = file.readLines()
        def start = Math.max(0, lines.size() - 100)
        println "--- build.log tail ---"
        lines[start..-1].each { println it }
        println "----------------------"
        assert false : message
    }
}

check(logContent.contains("Instrumenting"), "OpenDST instrumentation was not found", logFile)
check(logContent.contains("Built self-contained JAR"), "Build mojo did not complete", logFile)

def targetDir = new File(basedir, "target")
def jarFiles = targetDir.listFiles({ dir, name -> name.endsWith("-opendst.jar") } as FilenameFilter)
check(jarFiles != null && jarFiles.length == 1,
      "Expected exactly one *-opendst.jar in target/, found: ${jarFiles?.length ?: 0}", logFile)
File jarFile = jarFiles[0]

// Verify opendst-patch.jar is in the output JAR
import java.util.jar.JarFile
def jar = new JarFile(jarFile)
try {
    check(jar.getEntry("system/opendst-patch.jar") != null,
          "opendst-patch.jar missing from system/ in output JAR", logFile)
} finally {
    jar.close()
}

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

// ---- Phase 1: Run the simulation with replay to verify determinism ----

def workingDir = new File(basedir, "target/opendst-work")

println "Phase 1: Running simulation..."
def p1 = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                                 "--working-dir", workingDir.absolutePath,
                                 "--stagnation-limit", "50",
                                 "--replay-probability", "0.5",
                                 "--stop", "any-fail")
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

def p1Output = new StringBuilder()
p1.inputStream.eachLine { line ->
    p1Output.append(line).append("\n")
    println "[Phase1] ${line}"
}

def p1Exit = p1.waitFor()
check(p1Exit == 0, "Expected exit code 0 (no failures), got: ${p1Exit}", logFile)

// Verify report
def reportFile = new File(workingDir, "report/report.json")
assert reportFile.exists() : "report.json was not created"
assert reportFile.length() > 0 : "report.json is empty"

def report = new JsonSlurper().parseText(reportFile.text)
assert report.count > 0 : "report.count should be > 0"

def reportAssertions = report.assertions.collectEntries { [it.name, it] }
println "Report assertions: ${reportAssertions.keySet()}"

// All assertions should pass
def failed = reportAssertions.findAll { name, entry -> entry.pass == false }
assert failed.isEmpty() : "The following assertions failed: ${failed.keySet().join(', ')}"

// Verify expected assertions were reached
def expectedAssertions = [
    "shutdown-hook-completed",
    "worker-run",
    "worker-identity",
    "worker-instanceof",
    "worker-joined",
    "special-worker-run",
    "special-worker-instanceof",
    "special-joined",
    "simple-run",
    "simple-joined",
    "timer-is-timer",
    "timer-oneshot-fired",
    "timer-worker-virtual",
    "timer-recurring-fired",
    "timer-cancelled",
    "all-done",
]
def labels = report.assertions.collect { it.name }
for (a in expectedAssertions) {
    check(labels.contains(a), "assertion '${a}' not found in report: ${labels}", logFile)
}

assert reportAssertions.containsKey("no internal error") :
    "assertion 'no internal error' not found in report"
assert reportAssertions["no internal error"].pass == true :
    "Non-determinism detected — 'no internal error' failed."

// ---- Phase 2: Replay a plan and verify the shutdown hook guard fires ----
// Phase 1 only writes plan files (signals stream over stdout, never to disk),
// so to inspect the shutdown-hook guard message we replay one plan; --plan
// echoes every signal line to stdout for direct inspection.

def plansDir = new File(workingDir, "report/plans")
check(plansDir.exists() && plansDir.isDirectory(), "Plans directory does not exist", logFile)

def planFiles = plansDir.listFiles({ dir, name -> name.endsWith(".plan.json") } as FilenameFilter)
check(planFiles != null && planFiles.length > 0, "No plan files found", logFile)

File planFile = planFiles[0]
println "Phase 2: Replaying plan ${planFile.name} to verify guard fires..."

def replayWorkingDir = new File(basedir, "target/replay-work")
def p2 = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                             "--working-dir", replayWorkingDir.absolutePath,
                             "--plan", planFile.absolutePath)
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

def p2Output = new StringBuilder()
p2.inputStream.eachLine { line ->
    p2Output.append(line).append("\n")
    println "[Phase2] ${line}"
}

def p2Exit = p2.waitFor()
check(p2Exit == 0, "Replay failed with exit code ${p2Exit}", logFile)
check(p2Output.toString().contains("Replay complete."),
      "Replay output does not contain 'Replay complete.'", logFile)

// The replay stdout contains JSON lifecycle events. Verify the guards fired.

// Shutdown hook guard: LogManager$Cleaner platform thread hook was skipped
check(p2Output.toString().contains("platform thread shutdown hook skipped"),
      "Guard did not fire — expected 'platform thread shutdown hook skipped' in replay output", logFile)
check(p2Output.toString().contains("LogManager\$Cleaner"),
      "Expected LogManager\$Cleaner hook class in replay output", logFile)

// Thread start guard: VirtualThread-unblocker is pre-initialized in premain(), so no
// "platform thread started" events should appear — their absence proves the warmup works.
check(!p2Output.toString().contains("platform thread started"),
      "Unexpected 'platform thread started' in replay — VirtualThread warmup may not be working", logFile)

println "All verifications passed."
