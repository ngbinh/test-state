package teststate.selenium

import org.openqa.selenium.WebDriver
import scala.concurrent.duration.Duration
import teststate.data.Id
import teststate.typeclass.ExecutionModel
import Internal._

/** Access to a specific tab in a browser.
  *
  * Ensure that you call `.withSeleniumTab` on your TestState DSL when using this.
  *
  * Note: This is mutable.
  */
trait Tab[+D <: WebDriver] {

  /** Focus the tab and perform action within it */
  def use[A](f: D => A): A

  /** Monadic version of [[use()]].
    *
    * @param lockWait Maximum time to wait attempting to acquire the tab lock.
    * @param lockRetry If the lock cannot be acquired, how long to wait before trying again.
    */
  def useM[M[_], A](f: D => M[A], lockWait: Duration, lockRetry: Duration)
                   (implicit EM: ExecutionModel[M]): M[A]

  /** Close the tab.
    *
    * @return whether this call was effective.
    *         Specifically, true if this call closed the tab, false if the tab was already closed.
    */
  def closeTab(): Boolean

  def aroundFirstUse(around: D => Tab.ProcMod): this.type
  def aroundEachUse (around: D => Tab.ProcMod): this.type
  def afterClose    (callback: D => Unit): this.type

  final def beforeFirstUse(f: D => Unit): this.type = aroundFirstUse(d => Tab.ProcMod.before(f(d)))
  final def afterFirstUse (f: D => Unit): this.type = aroundFirstUse(d => Tab.ProcMod.after (f(d)))
  final def beforeEachUse (f: D => Unit): this.type = aroundEachUse (d => Tab.ProcMod.before(f(d)))
  final def afterEachUse  (f: D => Unit): this.type = aroundEachUse (d => Tab.ProcMod.after (f(d)))
}

object Tab {

  def apply[D <: WebDriver](driver: D,
                            mutex: Mutex,
                            tabSupport: TabSupport[D])
                           (rootTab: tabSupport.TabHandle): Tab[D] =
    new Tab[D] {
      import tabSupport.TabHandle

      private var tab        = Option.empty[TabHandle]
      private var closed     = false
      private var onFirstUse = Option.empty[D => ProcMod]
      private var onEachUse  = Option.empty[D => ProcMod]
      private var afterClose = doNothing1: D => Unit

      private def prepareWithoutLocking(): D = {
        implicit def d = driver

        if (closed)
          throw new TabAlreadyClosed()

        tab match {
          case Some(t) =>
            tabSupport.activate(t)
          case None =>
            val t = tabSupport.open(rootTab)
            tab = Some(t)
            tabSupport.activate(t)
            onFirstUse.foreach { f =>
              val m = f(d)
              val g = m[Id, Unit](doNothing0)
              g()
            }
        }

        d
      }

      override def use[A](f: D => A): A =
        mutex {
          val d = prepareWithoutLocking()
          onEachUse match {
            case None =>
              f(d)
            case Some(around) =>
              val m = around(d)
              val g = m[Id, A](() => f(d))
              g()
          }
        }

      override def useM[M[_], A](f: D => M[A], lockWait: Duration, lockRetry: Duration)
                                (implicit EM: ExecutionModel[M]): M[A] = {
        val f2: D => M[A] = { d =>
          onEachUse match {
            case None =>
              f(d)
            case Some(around) =>
              val m = around(d)
              val g = m(() => f(d))
              g()
          }
        }
        def driver = EM.point(prepareWithoutLocking())
        mutex.monadic(EM.flatMap(driver)(f2), lockWait, lockRetry)
      }

      override def closeTab(): Boolean =
        mutex {
          val affect = !closed
          if (affect) {
            implicit def d = driver
            for (t <- tab) {
              tabSupport.activate(t)
              tabSupport.closeActive()
            }
            closed = true
            tab = None
            afterClose(d)
          }
          affect
        }

      override def aroundFirstUse(around: D => Tab.ProcMod): this.type =
        mutex {
          onFirstUse = Some(mergeProcMods(onFirstUse, around))
          this
        }

      override def aroundEachUse(around: D => Tab.ProcMod): this.type =
        mutex {
          onEachUse = Some(mergeProcMods(onEachUse, around))
          this
        }

      override def afterClose(callback: D => Unit): this.type =
        mutex {
          afterClose = afterClose >> callback
          this
        }
  }

  // ===================================================================================================================

  trait ProcMod { outer =>
    def apply[F[_], A](proc: () => F[A])(implicit EM: ExecutionModel[F]): () => F[A]

    final def andThen(inner: ProcMod): ProcMod =
      new ProcMod {
        override def apply[F[_], A](proc: () => F[A])(implicit EM: ExecutionModel[F]): () => F[A] =
          outer(inner(proc))
      }
  }

  object ProcMod {
    def before(f: => Unit): ProcMod =
      new ProcMod {
        override def apply[F[_], A](proc: () => F[A])(implicit EM: ExecutionModel[F]): () => F[A] = () => {
          def before = EM.point(f)
          EM.flatMap(before)(_ => proc())
        }
      }

    def after(f: => Unit): ProcMod =
      new ProcMod {
        override def apply[F[_], A](proc: () => F[A])(implicit EM: ExecutionModel[F]): () => F[A] = () => {
          def after = EM.point(f)
          EM.flatMap(proc())(a => EM.map(after)(_ => a))
        }
      }
  }
}

final class TabAlreadyClosed() extends RuntimeException("You cannot use a tab after you've closed it.")
