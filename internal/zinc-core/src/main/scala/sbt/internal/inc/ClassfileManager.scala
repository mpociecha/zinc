package sbt
package internal
package inc

import sbt.io.IO
import java.io.File
import collection.mutable
import xsbti.compile.{ ExternalHooks, IncOptions, DeleteImmediatelyManagerType, TransactionalManagerType }

/**
 * During an incremental compilation run, a ClassfileManager deletes class files and is notified of generated class files.
 * A ClassfileManager can be used only once.
 */
trait ClassfileManager extends ExternalHooks.ClassFileManager {
  /**
   * Called once per compilation step with the class files to delete prior to that step's compilation.
   * The files in `classes` must not exist if this method returns normally.
   * Any empty ancestor directories of deleted files must not exist either.
   */
  def delete(classes: Iterable[File]): Unit

  /** Called once per compilation step with the class files generated during that step. */
  def generated(classes: Iterable[File]): Unit

  /** Called once at the end of the whole compilation run, with `success` indicating whether compilation succeeded (true) or not (false). */
  def complete(success: Boolean): Unit
}

object ClassfileManager {

  private case class WrappedClassfileManager(internal: ClassfileManager, external: Option[ClassfileManager])
    extends ClassfileManager {

    override def delete(classes: Iterable[File]): Unit = {
      external.foreach(_.delete(classes))
      internal.delete(classes)
    }

    override def complete(success: Boolean): Unit = {
      external.foreach(_.complete(success))
      internal.complete(success)
    }

    override def generated(classes: Iterable[File]): Unit = {
      external.foreach(_.generated(classes))
      internal.generated(classes)
    }
  }

  def getClassfileManager(options: IncOptions): ClassfileManager = {
    val internal =
      if (options.classfileManagerType.isDefined)
        options.classfileManagerType.get match {
          case _: DeleteImmediatelyManagerType => deleteImmediately()
          case m: TransactionalManagerType     => transactional(m.backupDirectory, m.logger)()
        }
      else deleteImmediately()

    val external = Option(options.externalHooks())
      .flatMap(ext => Option(ext.externalClassFileManager))
      .collect {
        case manager: ClassfileManager => manager
      }

    WrappedClassfileManager(internal, external)
  }

  /** Constructs a minimal ClassfileManager implementation that immediately deletes class files when requested. */
  val deleteImmediately: () => ClassfileManager = () => new ClassfileManager {
    def delete(classes: Iterable[File]): Unit = IO.deleteFilesEmptyDirs(classes)

    def generated(classes: Iterable[File]): Unit = ()

    def complete(success: Boolean): Unit = ()
  }

  @deprecated("Use overloaded variant that takes additional logger argument, instead.", "0.13.5")
  def transactional(tempDir0: File): () => ClassfileManager =
    transactional(tempDir0, sbt.util.Logger.Null)

  /** When compilation fails, this ClassfileManager restores class files to the way they were before compilation. */
  def transactional(tempDir0: File, logger: sbt.util.Logger): () => ClassfileManager = () => new ClassfileManager {
    val tempDir = tempDir0.getCanonicalFile
    IO.delete(tempDir)
    IO.createDirectory(tempDir)
    logger.debug(s"Created transactional ClassfileManager with tempDir = $tempDir")

    private[this] val generatedClasses = new mutable.HashSet[File]
    private[this] val movedClasses = new mutable.HashMap[File, File]

    private def showFiles(files: Iterable[File]): String = files.map(f => s"\t$f").mkString("\n")

    def delete(classes: Iterable[File]): Unit = {
      logger.debug(s"About to delete class files:\n${showFiles(classes)}")
      val toBeBackedUp = classes.filter(c => c.exists && !movedClasses.contains(c) && !generatedClasses(c))
      logger.debug(s"We backup classs files:\n${showFiles(toBeBackedUp)}")
      for (c <- toBeBackedUp) {
        movedClasses.put(c, move(c))
      }
      IO.deleteFilesEmptyDirs(classes)
    }

    def generated(classes: Iterable[File]): Unit = {
      logger.debug(s"Registering generated classes:\n${showFiles(classes)}")
      generatedClasses ++= classes
      ()
    }

    def complete(success: Boolean): Unit = {
      if (!success) {
        logger.debug("Rolling back changes to class files.")
        logger.debug(s"Removing generated classes:\n${showFiles(generatedClasses)}")
        IO.deleteFilesEmptyDirs(generatedClasses)
        logger.debug(s"Restoring class files: \n${showFiles(movedClasses.keys)}")
        for ((orig, tmp) <- movedClasses) IO.move(tmp, orig)
      }
      logger.debug(s"Removing the temporary directory used for backing up class files: $tempDir")
      IO.delete(tempDir)
    }

    def move(c: File): File = {
      val target = File.createTempFile("sbt", ".class", tempDir)
      IO.move(c, target)
      target
    }
  }
}
