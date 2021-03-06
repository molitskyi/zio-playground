package io.github.socializator

import org.http4s.implicits._
import org.http4s.HttpApp
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.CORS
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.putStrLn
import zio.interop.catz._
import cats.effect.{ExitCode => CatsExitCode}
import io.github.socializator.generated.server.pets.PetsResource
import zio.logging._
import zio.config.{config, Config}
import io.github.socializator.configuration._
import io.github.socializator.logging.AppLogging
import io.github.socializator.database._
import io.github.socializator.controller.PetsApi
import doobie.util.transactor.Transactor
import io.github.socializator.Layers

object Launch extends zio.App {
  // Clock is implicitly converted to cats.effect.IO.timer needed for http4s
  type AppEnvironment = Layers.AppEnv with Clock with Blocking
  type AppTask[A]     = RIO[AppEnvironment, A]

  override def run(args: List[String]): ZIO[ZEnv, Nothing, zio.ExitCode] = {

    val program = for {
      appConfig <- config[AppConfig]
      _         <- migrateDatabaseSchema(appConfig.database)
      _ <- log.info(
        s"Starting server at http://${appConfig.api.host}:${appConfig.api.port} ..."
      )
      val httpApp = (
          PetsApi.routes[AppEnvironment]
      ).orNotFound
      server <- runHttpServer(httpApp, appConfig.api)
    } yield server

    program
      .provideSomeLayer[ZEnv](
        Layers.live.AppLayer
      )
      .exitCode
  }

  private def runHttpServer[R <: Clock](httpApp: HttpApp[RIO[R, *]], apiConfig: ApiConfig): ZIO[R, Throwable, Unit] = {
    type HttpAppTask[A] = RIO[R, A]

    ZIO.runtime[R].flatMap { implicit rts =>
      BlazeServerBuilder[HttpAppTask]
        .bindHttp(apiConfig.port, apiConfig.host)
        .withHttpApp(CORS(httpApp))
        .serve
        .compile[HttpAppTask, HttpAppTask, CatsExitCode]
        .drain
    }
  }
}
