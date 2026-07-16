import groovy.json.JsonSlurper

// Verify the build mojo produced a self-contained JAR for the network-fault-test,
// then run the JAR and verify the simulation report.
File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

// Helper to avoid Power Assert dumping logContent
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

// Check instrumentation ran
check(logContent.contains("Instrumenting"), "OpenDST instrumentation was not found", logFile)

// Check the build mojo ran successfully
check(logContent.contains("Built self-contained JAR"), "Build mojo did not complete", logFile)

// Check the JAR file was created
def targetDir = new File(basedir, "target")
def jarFiles = targetDir.listFiles({ dir, name -> name.endsWith("-opendst.jar") } as FilenameFilter)
check(jarFiles != null && jarFiles.length == 1, "Expected exactly one *-opendst.jar in target/, found: ${jarFiles?.length ?: 0}", logFile)
File jarFile = jarFiles[0]
check(jarFile.length() > 0, "${jarFile.name} is empty", logFile)

// Verify JAR contents
import java.util.jar.JarFile
def jar = new JarFile(jarFile)
try {
    // Check manifest
    def manifest = jar.manifest
    check(manifest != null, "JAR has no manifest", logFile)
    def mainClass = manifest.mainAttributes.getValue("Main-Class")
    check(mainClass == "com.pingidentity.opendst.runner.Bootstrap", "Main-Class is wrong: " + mainClass, logFile)

    // Check essential entries exist
    check(jar.getEntry("META-INF/opendst/assertions.json") != null, "assertions.json missing", logFile)
    check(jar.getEntry("system/opendst-agent.jar") != null, "opendst-agent.jar missing", logFile)
    check(jar.getEntry("META-INF/opendst/deployment.json") != null, "deployment.json missing", logFile)

    // Check Bootstrap.class is at JAR root
    check(jar.getEntry("com/pingidentity/opendst/runner/Bootstrap.class") != null, "Bootstrap.class missing at JAR root", logFile)

    // Check runner JAR in system/ (Jackson/snakeyaml/picocli are shaded into runner)
    check(jar.getEntry("system/opendst-runner.jar") != null, "opendst-runner.jar missing from system/", logFile)

    // Check apps/ has instrumented application content
    def hasApps = jar.entries().any { it.name.startsWith("apps/") }
    check(hasApps, "No application content in apps/", logFile)

    // Check that instrumented classes exist under WEB-INF/classes/ (a directory, not a jar)
    def hasClasses = jar.entries().any { it.name.contains("WEB-INF/classes/") && it.name.endsWith(".class") }
    check(hasClasses, "No instrumented classes under WEB-INF/classes/ in apps/", logFile)

    // Verify assertions.json contains the expected assertions
    def assertionsEntry = jar.getEntry("META-INF/opendst/assertions.json")
    def assertionsJson = jar.getInputStream(assertionsEntry).text
    def assertions = new JsonSlurper().parseText(assertionsJson)
    check(assertions instanceof List, "assertions.json is not a list", logFile)

    def labels = assertions.collect { it.message }
    println "Assertions found: ${labels}"

    // Core reachable assertions that must always be present
    def requiredAssertions = [
        "server-restart",
        "server-deferred-bind",
        "server-constructor-bind",
        "server-normal-echo",
        "server-output-halfclose",
        "server-input-halfclose",
        "server-random-sequence",
        "client-direct-connect",
        "client-unbound",
        "client-open",
    ]
    for (a in requiredAssertions) {
        check(labels.contains(a), "assertion '${a}' not found in assertions.json: ${labels}", logFile)
    }
} finally {
    jar.close()
}

// ---- Phase 2: Run the built JAR and verify the simulation report ----

def workingDir = new File(basedir, "target/opendst-work")

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

println "Running: ${javaBin} -jar ${jarFile.absolutePath} --working-dir ${workingDir.absolutePath}"

def process = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                                 "--working-dir", workingDir.absolutePath,
                                 "--stagnation-limit", "200",
                                 "--stop", "all-pass")
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

// Capture output for debugging
def output = new StringBuilder()
process.inputStream.eachLine { line ->
    output.append(line).append("\n")
    println "[JAR] ${line}"
}

def exitCode = process.waitFor()

if (exitCode != 0) {
    println "JAR execution output:"
    println output.toString()
    assert false : "java -jar ${jarFile.name} failed with exit code ${exitCode}"
}

// Verify the report was produced
def reportFile = new File(workingDir, "report/report.json")
assert reportFile.exists() : "report.json was not created in ${reportFile.parentFile.absolutePath}"
assert reportFile.length() > 0 : "report.json is empty"

def report = new JsonSlurper().parseText(reportFile.text)

// Verify report structure
assert report.count != null : "report.count is missing"
assert report.count > 0 : "report.count should be > 0, got: ${report.count}"
assert report.duration != null : "report.duration is missing"
assert report.assertions != null : "report.assertions is missing"
assert report.assertions instanceof List : "report.assertions is not a list"

// Verify assertion results
def reportAssertions = report.assertions.collectEntries { [it.name, it.pass] }
println "Report assertions: ${reportAssertions}"

// No assertion should be in failed state.
// The simulation is configured with enough iterations for all reachable assertions to be hit,
// and all safety/liveness properties must hold under fault injection.
def failed = reportAssertions.findAll { name, pass -> pass == false }
assert failed.isEmpty() :
    "The following assertions failed: ${failed.keySet().join(', ')}"

// No runs should have failed — verify no type:fail lines in the output
check(!output.toString().contains("type:fail"), "Unexpected type:fail in output", logFile)

// Verify the simulation didn't run excessively — parse progress lines for run count.
// Progress lines look like: "progress     run:42 stagnation:3 passing:25/31"
def runCounts = (output.toString() =~ /progress\s+run:(\d+)/).collect { it[1] as int }
if (!runCounts.isEmpty()) {
    def maxRun = runCounts.max()
    println "Max run count from progress lines: ${maxRun}"
    check(maxRun <= 800, "Simulation ran too many iterations (${maxRun}), possible stagnation/stop-condition issue", logFile)
}

println "All verifications passed — network-fault-test JAR runs successfully and produces correct report."
