package fs2.io.uring.logging

import org.typelevel.log4cats.Logger
import cats._
import cats.implicits._
import cats.effect.kernel.Resource

object syntax {

  implicit class LogSyntax[F[_], E, A](fa: F[A])(implicit me: MonadError[F, E], logger: Logger[F]) {

    def log(success: A => String, error: E => String): F[A] = fa.attemptTap {
      case Left(e)  => logger.error(error(e))
      case Right(a) => logger.info(success(a))
    }

    def logError(error: E => String): F[A] = fa.attemptTap {
      case Left(e)  => logger.error(error(e))
      case Right(_) => ().pure[F]
    }

  }

  implicit class LogSyntaxForResource[F[_], E, A](r: Resource[F, A])(implicit me: MonadError[F, E], logger: Logger[F]) {

    def log(success: A => String, error: E => String): Resource[F, A] = r.attemptTap {
      case Left(e)  => Resource.eval(logger.error(error(e)))
      case Right(a) => Resource.eval(logger.info(success(a)))
    }

    def logError(error: E => String): Resource[F, A] = r.attemptTap {
      case Left(e)  => Resource.eval(logger.error(error(e)))
      case Right(a) => Resource.pure[F,A](a)  
    }

  }

}
