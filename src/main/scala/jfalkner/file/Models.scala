package jfalkner.file

import java.nio.file.Path
import java.time.Instant

case class Status(moviesFound: Int, autoQueueReady: Int, skipping: Int, skipReasons: Set[(String, Int)], submits: Int, successfulJobs: Int, failedJobs: Int, failureReasons: Set[(String, Int)])

case class Considered(path: Path, queue: Boolean, lastModified: Instant, dir: String, file: String, reason: String)

case class Queued(path: Path, id: String = "", ts: Instant = Instant.now(), error: String = "")

case class ManualOverride(tsName: String, ts: Instant = Instant.now(), user: String = "Auto", include: Boolean = true, desc: String = "")