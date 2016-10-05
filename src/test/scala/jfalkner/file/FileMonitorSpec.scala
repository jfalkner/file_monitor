package jfalkner.file

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.TimeUnit

import org.apache.commons.io.FileUtils
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

import scala.util.Try


class FileMonitorSpec extends Specification {

  // mock up a path based on: /pbi/collections/312/3120177/r54097_20160810_212523/1_A01/m54097_160810_212840.subreadset.xml
  val (inst, runTsName, well, movieTsName) = (3120177, "r54097_20160810_212523", "1_A01", "m54097_160810_212840")
  val mockPathName = s"$inst/$runTsName/$well"
  // second example: /pbi/collections/312/3120177/r54097_20160810_212523/2_A01/m54097_160810_231631.subreadset.xml
  val (instB, runTsNameB, wellB, movieTsNameB) = (3120177, "r54097_20160810_212523", "2_A01", "m54097_160810_231631")
  val mockPathNameB = s"$instB/$runTsNameB/$wellB"

  def considerMeta(ms: TestFileMonitor, mockSubreadSet: Path) =
    (ms.trimDir(mockSubreadSet), ms.trimFile(mockSubreadSet), Files.getLastModifiedTime(mockSubreadSet).toInstant)

  "DirectoryMonitor" should {
    "Correctly identify one file and respective manual overrides for include/exclude" in {
      withCleanup { (basePath, workPath) =>
        val (mockPath, mockSubreadSet) = mockData(basePath)
        def ms = new TestFileMonitor(basePath, workPath)
        val (d, f, lm) = considerMeta(ms, mockSubreadSet)
        def consider(include: Boolean, reason: String = Reasons.Nil) = Considered(mockSubreadSet, include, lm, d, f, reason)
        // calculated status should be to ignore by default
        Set(consider(false, Reasons.NEEDS_MANUAL_OVERRIDE)) mustEqual ms.considerAll()
        // manual override to include the run
        ms.dirLogger.log(new ManualOverride(runTsName))
        Set(consider(true, Reasons.Nil)) mustEqual ms.considerAll()
        // manual override to exclude the movie
        ms.fileLogger.log(new ManualOverride(movieTsName, include = false))
        Set(consider(false, Reasons.FILE_EXCLUDED)) mustEqual ms.considerAll()
        // manual override to exclude all movies in the run
        Thread.sleep(10)
        ms.dirLogger.log(new ManualOverride(runTsName, include = false))
        Set(consider(false, Reasons.DIR_EXCLUDED)) mustEqual ms.considerAll()
        // manual override to include the movie
        Thread.sleep(10)
        ms.fileLogger.log(new ManualOverride(movieTsName))
        Set(consider(true, Reasons.Nil)) mustEqual ms.considerAll()
        // mimic a submit log and confirm the same movie won't multi-submit
        ms.submitLogger.logAll(ms.queue(ms.considerAll()).flatMap(_.toOption))
        Set(consider(false, Reasons.ALREADY_SUBMITTED)) mustEqual ms.considerAll()
        // now override a movie submit and show it submits again
        Thread.sleep(10)
        ms.fileLogger.log(new ManualOverride(movieTsName))
        Set(consider(true, Reasons.Nil)) mustEqual ms.considerAll()
      }
    }
    "Automatic mode works. No manual overrides required" in {
      withCleanup { (basePath, workPath) =>
        val (mockPath, mockSubreadSet) = mockData(basePath)
        val ms = new TestFileMonitor(basePath, workPath) {
          override val requireOverrides: Boolean = false
        }
        val (d, f, lm) = considerMeta(ms, mockSubreadSet)
        // calculated status should now be to include by default
        Set(Considered(mockSubreadSet, true, lm, d, f, Reasons.Nil)) mustEqual ms.considerAll()
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
        val (d, f, lm) = considerMeta(excludeIt, mockSubreadSet)
        Set(Considered(mockSubreadSet, false, lm, d, f, Reasons.TOO_OLD)) mustEqual excludeIt.considerAll()
      }
    }
    "Automatic mode still respects time window exclusions" in {
      withCleanup { (basePath, workPath) =>
        val beforeMovie = System.currentTimeMillis()
        val (mockPath, mockSubreadSet) = mockData(basePath)
        val (d, f, lm) = considerMeta(new TestFileMonitor(mockPath, workPath), mockSubreadSet)
        // wait at least a millisecond so the time window is valid
        Thread.sleep(1)
        val afterMovie = System.currentTimeMillis()
        // the movie should be excluded if the time window is after the movie's creation date
        val excludeIt = new TestFileMonitor(mockPath, workPath, afterMovie, afterMovie + 1)
        Set(Considered(mockSubreadSet, false, lm, d, f, Reasons.TOO_OLD)) mustEqual excludeIt.considerAll()
        // the movie should be included if the time window meets it but not auto-
        val includeIt = new TestFileMonitor(basePath, workPath, beforeMovie, afterMovie + 1)
        Set(Considered(mockSubreadSet, false, lm, d, f, Reasons.TOO_OLD)) mustEqual includeIt.considerAll()
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
    "Confirm clear() works so that old entries can be removed" in {
      withCleanup { (basePath, workPath) =>
        val (_, mockSubreadSet) = mockData(basePath)
        def ms = new TestFileMonitor(basePath, workPath)
        val (d, f, lm) = considerMeta(ms, mockSubreadSet)
        val (a, b) = (
          Considered(mockSubreadSet, false, lm, d, f, Reasons.TOO_OLD),
          Considered(mockSubreadSet, true, lm, d, f, Reasons.Nil))
        ms.consideredLogger.log(a)
        ms.consideredLogger.log(b)
        // confirm two entries appear
        ms.consideredLogger.load() mustEqual Set(a, b)
        // clear old data
        ms.consideredLogger.clear()
        ms.consideredLogger.logAll(Seq(b))
        ms.consideredLogger.load() mustEqual Set(b)
      }
    }
    "Considered files are sorted so newest run first" in {
      withCleanup { (basePath, workPath) =>
        val (_, mockSubreadSetA) = mockData(basePath)
        val (_, mockSubreadSetB) = mockData(basePath, mockPathNameB, movieTsNameB)
        def consider(mockSubreadSet: Path, lastModified: Instant) : Considered = {
          val (d, f, _) = considerMeta(new TestFileMonitor(basePath, workPath), mockSubreadSet)
          Considered(mockSubreadSet, true, lastModified, d, f, Reasons.Nil)
        }
        val a = consider(mockSubreadSetA, Instant.now())
        val b = consider(mockSubreadSetB, a.lastModified.plusSeconds(1))
        def ms = new TestFileMonitor(basePath, workPath) {
          override def considerAll(): Iterable[Considered] = Seq(a, b)

          override def queue(all: Iterable[Considered]): Iterable[Try[Queued]] = {
            all mustEqual Seq(b, a)
            super.queue(all)
          }
        }
        Status(
          moviesFound = 2,
          autoQueueReady = 2,
          skipping= 0,
          skipReasons = Set(),
          submits = 2,
          successfulJobs = 2,
          failedJobs = 0,
          failureReasons = Set()) mustEqual ms.autoQueue()
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

  def mockData(basePath: Path, mpn: String = mockPathName, movie: String = movieTsName): (Path, Path) = {
    val mockPath = Files.createDirectories(basePath.resolve(mpn))
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