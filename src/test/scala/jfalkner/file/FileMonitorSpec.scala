package jfalkner.file

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import org.apache.commons.io.FileUtils
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification


class FileMonitorSpec extends Specification {

  // mock up a path based on: /pbi/collections/312/3120177/r54097_20160810_212523/1_A01/m54097_160810_212840.subreadset.xml
  val (inst, runTsName, well, movieTsName) = (3120177, "r54097_20160810_212523", "1_A01", "m54097_160810_212840")
  val mockPathName = s"$inst/$runTsName/$well"

  "DirectoryMonitor" should {
    "Correctly identify one file and respective manual overrides for include/exclude" in {
      withCleanup { (basePath, workPath) =>
        val (mockPath, mockSubreadSet) = mockData(basePath)
        val ms = new TestFileMonitor(basePath, workPath)
        // calculated status should be to ignore by default
        Set(Considered(mockSubreadSet, false, Reasons.NEEDS_MANUAL_OVERRIDE)) mustEqual ms.considerAll()
        // manual override to include the run
        ms.runLogger.log(new ManualOverride(runTsName))
        Set(Considered(mockSubreadSet, true, Reasons.Nil)) mustEqual ms.considerAll()
        // manual override to exclude the movie
        ms.movieLogger.log(new ManualOverride(movieTsName, include = false))
        Set(Considered(mockSubreadSet, false, Reasons.FILE_EXCLUDED)) mustEqual ms.considerAll()
        // manual override to exclude all movies in the run
        Thread.sleep(10)
        ms.runLogger.log(new ManualOverride(runTsName, include = false))
        Set(Considered(mockSubreadSet, false, Reasons.DIR_EXCLUDED)) mustEqual ms.considerAll()
        // manual override to include the movie
        Thread.sleep(10)
        ms.movieLogger.log(new ManualOverride(movieTsName))
        Set(Considered(mockSubreadSet, true, Reasons.Nil)) mustEqual ms.considerAll()
        // mimic a submit log and confirm the same movie won't multi-submit
        ms.submitLogger.logAll(ms.queue(ms.considerAll()).flatMap(_.toOption))
        Set(Considered(mockSubreadSet, false, Reasons.ALREADY_SUBMITTED)) mustEqual ms.considerAll()
        // now override a movie submit and show it submits again
        Thread.sleep(10)
        ms.movieLogger.log(new ManualOverride(movieTsName))
        Set(Considered(mockSubreadSet, true, Reasons.Nil)) mustEqual ms.considerAll()
      }
    }
    "Automatic mode works. No manual overrides required" in {
      withCleanup { (basePath, workPath) =>
        val (mockPath, mockSubreadSet) = mockData(basePath)
        val ms = new TestFileMonitor(basePath, workPath) {
          override val requireOverrides: Boolean = false
        }
        // calculated status should now be to include by default
        Set(Considered(mockSubreadSet, true, Reasons.Nil)) mustEqual ms.considerAll()
      }
    }
    "Exclude files based on the time window" in {
      withCleanup { (basePath, workPath) =>
        val beforeMovie = System.currentTimeMillis()
        val (mockPath, mockSubreadSet) = mockData(basePath)
        // wait at least a millisecond so the time window is valid
        Thread.sleep(1)
        val afterMovie = System.currentTimeMillis()
        // the movie should be excluded if the time window is after the movie's creation date
        val excludeIt = new TestFileMonitor(mockPath, workPath, afterMovie, afterMovie + 1)  {
          override val requireOverrides: Boolean = false
        }
        Set(Considered(mockSubreadSet, false, Reasons.TOO_OLD)) mustEqual excludeIt.considerAll()
      }
    }
    "Automatic mode still respects time window exclusions" in {
      withCleanup { (basePath, workPath) =>
        val beforeMovie = System.currentTimeMillis()
        val (mockPath, mockSubreadSet) = mockData(basePath)
        // wait at least a millisecond so the time window is valid
        Thread.sleep(1)
        val afterMovie = System.currentTimeMillis()
        // the movie should be excluded if the time window is after the movie's creation date
        val excludeIt = new TestFileMonitor(mockPath, workPath, afterMovie, afterMovie + 1)
        Set(Considered(mockSubreadSet, false, Reasons.TOO_OLD)) mustEqual excludeIt.considerAll()
        // the movie should be included if the time window meets it but not auto-
        val includeIt = new TestFileMonitor(basePath, workPath, beforeMovie, afterMovie + 1)
        Set(Considered(mockSubreadSet, false, Reasons.TOO_OLD)) mustEqual includeIt.considerAll()
      }
    }
    "Default app entry point returns expected status" in {
      withCleanup { (basePath, workPath) =>
        val (mockPath, mockSubreadSet) = mockData(basePath)
        Status(
          moviesFound = 1,
          autoQueueReady =0,
          skipping= 1,
          skipReasons = Set((Reasons.NEEDS_MANUAL_OVERRIDE, 1)),
          submits = 0,
          successfulJobs = 0,
          failedJobs = 0,
          failureReasons = Set()) mustEqual new TestFileMonitor(basePath, workPath).autoQueue()
      }
    }
  }

  def withCleanup(f: (Path, Path) => MatchResult[Any]): MatchResult[Any] = {
    val basePath = Files.createTempDirectory("DirMon")
    val workPath = Files.createTempDirectory("DirMonWork")
    try {
      f(basePath, workPath)
    }
    finally {
      Seq(basePath, workPath).foreach(p => FileUtils.deleteDirectory(p.toFile))
    }
  }

  def mockData(basePath: Path): (Path, Path) = {
    val mockPath = Files.createDirectories(basePath.resolve(mockPathName))
    val mockSubreadSet = Files.createFile(mockPath.resolve(s"$movieTsName.subreadset.xml"))
    (mockPath, mockSubreadSet)
  }

}

class TestFileMonitor(bd: Path, lp: Path, st: Long = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7), et: Long = System.currentTimeMillis()) extends FileMonitor {
  override val baseDir = bd
  override val logsPath = lp
  override val startTime = st
  override val endTime = et

  override def matches(p: File): Boolean = p.getName.endsWith(".subreadset.xml")

  override def trimFile(p: Path): String = p.getFileName.toString.stripSuffix(".subreadset.xml")

  override def trimDir(p: Path): String = p.toString.split("/").takeRight(3).head

}