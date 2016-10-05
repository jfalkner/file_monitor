package jfalkner.file

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.TimeUnit

import jfalkner.logs.Logs

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

// Logic related to marshall/unmarshall-ing log entries
trait FileMonitor extends Logs {

  val baseDir: Path

  override lazy val ignoredDirs: Set[String] = Set[String]()
  val startTime: Long = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
  val endTime: Long = System.currentTimeMillis()


  // logs for persisting data
  val dirLogger = make[ManualOverride](".run.overrides.csv")
  val fileLogger = make[ManualOverride](".movie.overrides.csv")
  val submitLogger = make[Queued](".submits.csv")
  val consideredLogger = make[Considered](".considered.csv")

  // changing this to false enables automatic mode, where data is processed without requiring a movie or run override
  val requireOverrides = true

  def autoQueue(): Status = {
    // consider all files and dirs to find changes of interest
    val events = considerAll().toList.sortWith(_.lastModified.getEpochSecond > _.lastModified.getEpochSecond)
    // record all successfully queued jobs
    val submits = queue(events.filter(_.queue)).flatMap { _ match {
        case Success(submit) => Some(submit)
        case Failure(t) => {
          System.err.println(t.getMessage); None
        }
      }
    }
    // save all considered, queued and submitted jobs
    consideredLogger.clear()
    consideredLogger.logAll(events)
    submitLogger.logAll(submits)
    Seq(dirLogger, fileLogger, submitLogger, consideredLogger).foreach(_.squash)

    Status(
      events.size,
      events.filter(_.queue).size,
      events.filter(!_.queue).size,
      for (reason <- events.filter(!_.queue).toSet[Considered].map(_.reason)) yield (reason, events.filter(_.reason == reason).size),
      submits.size,
      submits.filter(_.error.isEmpty).size,
      submits.filter(!_.error.isEmpty).size,
      for (reason <- submits.toSet[Queued].map(_.error).filter(!_.isEmpty)) yield (reason, submits.filter(_.error == reason).size)
    )
  }

  def queue(all: Iterable[Considered]): Iterable[Try[Queued]] = all.map(c => Try(Queued(c.path)))

  // converts considered file path
  def trimFile(p: Path) : String = p.getFileName.toString

  def trimDir(p: Path) : String = p.getParent.getFileName.toString

  // helper map for movie and run overrides to show when they were last overriden
  lazy val overrideTime = (dirLogger.load ++ fileLogger.load).toList.sortWith(_.ts.toEpochMilli < _.ts.toEpochMilli).map(v => (v.tsName, v)).toMap.filter(_._2.include).map{case (k, v) => k -> v.ts.toEpochMilli}

  // helper map for last submission
  lazy val lastSubmit = submitLogger.load.toList.sortWith(_.ts.toEpochMilli < _.ts.toEpochMilli).map(v => (v.path, v.ts.toEpochMilli)).toMap

  // helper map for last directory override
  lazy val dirOverrides = dirLogger.load.toList.sortWith(_.ts.toEpochMilli < _.ts.toEpochMilli).map(v => (v.tsName, v.include)).toMap

  // helper map for last file override
  lazy val fileOverrides = fileLogger.load.toList.sortWith(_.ts.toEpochMilli < _.ts.toEpochMilli).map(v => (v.tsName, v.include)).toMap

  // inspects all possible movies and calculates what action to take
  def considerAll(): Iterable[Considered] = {
    // rules engine for determining if a movie should be queued and why
    def consider(p: Path): Considered = {
      val fileName = trimFile(p)
      val dirName = trimDir(p)
      val lastModified = Files.getLastModifiedTime(p).toMillis
      val lm = Instant.ofEpochMilli(lastModified)
      // check rules that don't require knowing the file's lastModified
      if (!dirOverrides.getOrElse(dirName, true) && !fileOverrides.getOrElse(fileName, false))
        Considered(p, false, lm, dirName, fileName, Reasons.DIR_EXCLUDED)
      else if (!fileOverrides.getOrElse(fileName, true))
        Considered(p, false, lm, dirName, fileName, Reasons.FILE_EXCLUDED)
      else {
        // need to submit if it hasn't submitted since it was made or if an override was made after the last submit
        val forceSubmit = lastSubmit.getOrElse(p, Long.MinValue) < overrideTime.getOrElse(dirName, Long.MinValue) ||
          lastSubmit.getOrElse(p, Long.MinValue) < overrideTime.getOrElse(fileName, Long.MinValue)
        if (lastModified < startTime && !forceSubmit)
          Considered(p, false, lm, dirName, fileName, Reasons.TOO_OLD)
        else if (lastModified > endTime && !forceSubmit)
          Considered(p, false, lm, dirName, fileName, Reasons.TOO_NEW)
        else if (lastModified < lastSubmit.getOrElse(p, Long.MinValue) && !forceSubmit)
          Considered(p, false, lm, dirName, fileName, Reasons.ALREADY_SUBMITTED)
        else if (dirOverrides.getOrElse(dirName, !requireOverrides) || fileOverrides.getOrElse(fileName, !requireOverrides))
          Considered(p, true, lm, dirName, fileName, Reasons.Nil)
        else
          Considered(p, false, lm, dirName, fileName, Reasons.NEEDS_MANUAL_OVERRIDE)
      }
    }

    for (f <- walkTree(baseDir.toFile) if matches(f)) yield consider(f.toPath)
  }

  // custom filter to include files or dirs to consider
  def matches(p: File): Boolean = true

  // nice recursive dir walking from https://rosettacode.org/wiki/Walk_a_directory/Recursively#Scala
  def walkTree(file: File): Iterable[File] = {
    val children = new Iterable[File] {
      def iterator = if (file.isDirectory) file.listFiles.iterator else Iterator.empty
    }
    Seq(file) ++: children.flatMap(walkTree(_))
  }

}

