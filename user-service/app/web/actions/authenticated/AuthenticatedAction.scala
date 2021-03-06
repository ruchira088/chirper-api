package web.actions.authenticated

import com.ruchij.shared.monads.MonadicUtils.recoverWith
import com.ruchij.shared.web.requests.SessionTokenExtractor
import exceptions.InvalidCredentialsException
import javax.inject.Inject
import play.api.mvc.{ActionBuilderImpl, BodyParsers, Request, Result}
import scalaz.std.scalaFuture.futureInstance
import services.authentication.AuthenticationService
import web.responses.ResponseCreator

import scala.concurrent.{ExecutionContext, Future}

class AuthenticatedAction @Inject()(
  parser: BodyParsers.Default,
  sessionTokenExtractor: SessionTokenExtractor,
  authenticationService: AuthenticationService
)(implicit executionContext: ExecutionContext)
    extends ActionBuilderImpl(parser) {

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] =
    recoverWith(ResponseCreator.exceptionMapper) {
      for {
        sessionToken <- sessionTokenExtractor
          .token(request)
          .fold[Future[String]](Future.failed(InvalidCredentialsException))(Future.successful)
        user <- authenticationService.getUserFromSessionToken(sessionToken)
        result <- block(AuthenticatedRequest(user, request))
      } yield result
    }
}
