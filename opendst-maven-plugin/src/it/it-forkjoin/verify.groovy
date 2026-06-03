import groovy.json.JsonSlurper

// Verify that ForkJoinPool compute paths are deterministic under simulation when the common
// pool is configured for parallelism=0 (caller-runs): parallel streams, invoke(), and
// fork()/join() must run on the simulated caller (no ForkJoinWorkerThread), produce correct
// results, preserve determinism, and never escape a platform thread.

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

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

// ---- Phase 1: Run the simulation (common pool parallelism=0) ----

def workingDir = new File(basedir, "target/opendst-work")

println "Phase 1: Running simulation ..."
def p1 = new ProcessBuilder(javaBin,
                                 "-jar", jarFile.absolutePath,
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

def reportFile = new File(workingDir, "report/report.json")
assert reportFile.exists() : "report.json was not created"
assert reportFile.length() > 0 : "report.json is empty"

def report = new JsonSlurper().parseText(reportFile.text)
assert report.count > 0 : "report.count should be > 0"

def reportAssertions = report.assertions.collectEntries { [it.name, it] }
println "Report assertions: ${reportAssertions.keySet()}"

def failed = reportAssertions.findAll { name, entry -> entry.pass == false }
assert failed.isEmpty() : "The following assertions failed: ${failed.keySet().join(', ')}"

def expectedAssertions = [
    "intstream-correct",
    "parallelstream-correct",
    "streams-caller-ran",
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

// ---- Phase 2: Replay a plan and verify no platform thread escaped ----

def plansDir = new File(workingDir, "report/plans")
check(plansDir.exists() && plansDir.isDirectory(), "Plans directory does not exist", logFile)

def planFiles = plansDir.listFiles({ dir, name -> name.endsWith(".plan.json") } as FilenameFilter)
check(planFiles != null && planFiles.length > 0, "No plan files found", logFile)

File planFile = planFiles[0]
println "Phase 2: Replaying plan ${planFile.name} ..."

def replayWorkingDir = new File(basedir, "target/replay-work")
def p2 = new ProcessBuilder(javaBin,
                             "-jar", jarFile.absolutePath,
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

// No ForkJoinWorkerThread should have escaped onto a real platform thread.
check(!p2Output.toString().contains("platform thread started"),
      "Unexpected 'platform thread started' — a ForkJoinPool worker escaped the simulation", logFile)

println "All verifications passed."
