// Check opendst can find the bug
File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

def dstLines = logContent.readLines().findAll { it.contains("OpenDST testing") }
assert dstLines.size() == 2 : "The string 'OpenDST Testing' was not found on exactly two different lines"

def matchingLines = logContent.readLines().findAll { it.contains("Bug reached!") }
assert matchingLines.size() == 2 : "The string 'Bug reached!' was not found on exactly two different lines"
