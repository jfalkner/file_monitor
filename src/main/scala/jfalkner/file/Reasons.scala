package jfalkner.file

/**
  * Created by jfalkner on 9/6/16.
  */
object Reasons {
  val TOO_OLD = "Last modified time is too old"
  val TOO_NEW = "Last modified time is too recent"
  val ALREADY_SUBMITTED = "Already submitted to smrtlink"
  val NEEDS_MANUAL_OVERRIDE = "Valid to auto-queue but not listed on manual include list"
  val FILE_EXCLUDED = "File is excluded"
  val DIR_EXCLUDED = "Directory is excluded"
  val Nil = ""
}
