package jfalkner.file

import java.nio.file.Path
import java.time.Instant

case class Status(moviesFound: Int, autoQueueReady: Int, skipping: Int, skipReasons: Set[(String, Int)], submits: Int, successfulJobs: Int, failedJobs: Int, failureReasons: Set[(String, Int)])

case class Considered(path: Path, queue: Boolean, reason: String)

case class Queued(path: Path, ts: Instant, error: String)

case class ManualOverride(tsName: String, ts: Instant = Instant.now(), user: String = "Auto", include: Boolean = true, desc: String = "")