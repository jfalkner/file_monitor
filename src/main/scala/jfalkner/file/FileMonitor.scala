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
  val runLogger = make[ManualOverride](".run.overrides.csv")
  val movieLogger = make[ManualOverride](".movie.overrides.csv")
  val submitLogger = make[Queued](".submits.csv")
  val consideredLogger = make[Considered](".considered.csv")

  def autoQueue(): Status = {
    // consider all files and dirs to find changes of interest
    val events = considerAll()
    // record all successfully queued jobs
    val submits = queue(events.filter(_.queue)).flatMap { _ match {
        case Success(submit) => Some(submit)
        case Failure(t) => {
          System.err.println(t.getMessage); None
        }
      }
    }
    // save all considered, queued and submitted jobs
    consideredLogger.logAll(events)
    submitLogger.logAll(submits)
    Seq(runLogger, movieLogger, submitLogger, consideredLogger).foreach(_.squash)

    Status(
      events.size,
      events.filter(_.queue).size,
      events.filter(!_.queue).size,
      for (reason <- events.toSet[Considered].map(_.reason)) yield (reason, events.filter(_.reason == reason).size),
      submits.size,
      submits.filter(_.error.isEmpty).size,
      submits.filter(!_.error.isEmpty).size,
      for (reason <- submits.toSet[Queued].map(_.error).filter(!_.isEmpty)) yield (reason, submits.filter(_.error == reason).size)
    )
  }

  def queue(all: Iterable[Considered]): Iterable[Try[Queued]] = all.map(c => Try(Queued(c.path, Instant.now(), "")))

  // converts considered file path
  def trimFile(p: Path) : String = p.getFileName.toString

  def trimDir(p: Path) : String = p.getParent.getFileName.toString

  // inspects all possible movies and calculates what action to take
  def considerAll(): Iterable[Considered] = {

    // map last smrtlink submission attempt to avoid resubmission
    val lastSubmit = submitLogger.load.toList.sortWith(_.ts.getNano < _.ts.getNano).map(v => (v.path, v.ts.toEpochMilli)).toMap
    val dirOverrides = runLogger.load.toList.sortWith(_.ts.toEpochMilli < _.ts.toEpochMilli).map(v => (v.tsName, v.include)).toMap
    val fileOverrides = movieLogger.load.toList.sortWith(_.ts.toEpochMilli < _.ts.toEpochMilli).map(v => (v.tsName, v.include)).toMap
    // map timing of latest override -- sort then map to only have latest, then convert map to tsName -> ts
    val overrideTime = (runLogger.load ++ movieLogger.load).toList.sortWith(_.ts.getNano < _.ts.getNano).map(v => (v.tsName, v)).toMap.filter(_._2.include).map{case (k, v) => k -> v.ts.toEpochMilli}

    // rules engine for determining if a movie should be queued and why
    def consider(p: Path): Considered = {
      val fileName = trimFile(p)
      val dirName = trimDir(p)
      // check rules that don't require knowing the file's lastModified
      if (!dirOverrides.getOrElse(dirName, true) && !fileOverrides.getOrElse(fileName, false))
        Considered(p, false, Reasons.DIR_EXCLUDED)
      else if (!fileOverrides.getOrElse(fileName, true))
        Considered(p, false, Reasons.FILE_EXCLUDED)
      else {
        // check rules that need the more expensive file attrs, namely lastModified
        val lastModified = Files.getLastModifiedTime(p).toMillis
        // need to submit if it hasn't submitted since it was made or if an override was made after the last submit
        val forceSubmit = lastSubmit.getOrElse(p, Long.MinValue) < overrideTime.getOrElse(dirName, Long.MinValue) ||
          lastSubmit.getOrElse(p, Long.MinValue) < overrideTime.getOrElse(fileName, Long.MinValue)
        if (lastModified < startTime && !forceSubmit)
          Considered(p, false, Reasons.TOO_OLD)
        else if (lastModified > endTime && !forceSubmit)
          Considered(p, false, Reasons.TOO_NEW)
        else if (lastModified < lastSubmit.getOrElse(p, Long.MinValue) && !forceSubmit)
          Considered(p, false, Reasons.ALREADY_SUBMITTED)
        else if (dirOverrides.getOrElse(dirName, false) || fileOverrides.getOrElse(fileName, false))
          Considered(p, true, Reasons.Nil)
        else
          Considered(p, false, Reasons.NEEDS_MANUAL_OVERRIDE)
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

