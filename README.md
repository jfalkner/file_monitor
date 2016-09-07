# Directory Monitor

Scala API for inspecting a directory for file changes and invoking an
arbitrary `queue` operation on changed files or dirs. State is kept so
that files are queued only once. A rules engine also exists to include
or exclude files based on a file modification time window or manual 
overrides.

## Usage

Add the version tag of this repo directly in SBT.

```
lazy val directory_monitor = RootProject(uri("https://github.com/jfalkner/directory_monitor.git#v0.0.3"))
lazy val root = project in file(".") dependsOn directory_monitor
```

Use the `DirectoryMonitor` trait and make some custom logs based on a prefix.

```
class Example extends DirectoryMonitor {

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

See the tests for examples of setting the per-dir or per-file overrides.