# File Monitor

Scala API for inspecting a directory recursively for new files and
invoking an arbitrary `queue` operation on changed files or dirs. This 
was originally built to support a DNA sequencing workflow where the 
sequencers would periodically save data to a NFS location and users
wanted to detect new files and auto-generate analyses and reports.
 
Main features:
 
- State is kept so that files are queued only once.
- Files can be matched by an arbitrary directory and/or file matcher
- Rules engine to control customize what runs when
  - By default, no files are included to aid with testing
  - Include or exclude files based on a file modification time window
  - "Automatic" mode can be enable to queue files that match the time window
  - Manual overrides for flagging files or entire directories that should be run.
- Priority queuing
  - Most recent files are queued first by default. Ensures new data processes first
    even if old needs re-analysis.
  - Most recent, un-run manual override will be queued before everything else.
  

## Usage

Add the version tag of this repo directly in SBT.

```
lazy val directory_monitor = RootProject(uri("https://github.com/jfalkner/directory_monitor.git#v0.0.10"))
lazy val root = project in file(".") dependsOn directory_monitor
```

Use the `FileMonitor` trait and make some custom logs based on a prefix.

```
class Example extends DirectoryMonitor {

  // automatically queue files that match the time window
  override val requireOverrides = false

  // consider only files in the last 24 hours
  override startTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
  override endTime = System.currentTimeMillis()
  
  // consider only files that end with a special suffix
  override def matches(p: File): Boolean = p.getName.endsWith(".subreadset.xml")
  
  override def queue(c: Considered): Queued = {
    // any custom logic you want -- println is just an example
    println("Queuing File: " + c.path)
    
    return super.queue(c)
  }
}
```


## Tests and Code Coverage

See the tests for examples of setting the per-dir or per-file overrides.

Code coverage reports can be run with the following.

```
# Run test and code coverage and make HTML report
sbt clean coverage test coverageReport
...
[info] Written HTML coverage report [/Users/jfalkner/tokeep/git/jfalkner/file_monitor/target/scala-2.11/scoverage-report/index.html]
[info] Statement coverage.: 90.77%
[info] Branch coverage....: 92.86%
[info] Coverage reports completed
[info] All done. Coverage was [90.77%]
[success] Total time: 2 s, completed Oct 4, 2016 9:54:01 PM
```

You'll notice this is a well tested (90%+ coverage) and succinct
codebase (~130 lines of code).
