package com.example

import cats.effect.{IO, Resource, IOApp}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.routes.HttpRouteIO
import fs2.Stream
import io.quartz.http2.model.StatusCode
import io.quartz.http2.routes.WebFilter
import cats.syntax.all.catsSyntaxApplicativeByName
import ch.qos.logback.classic.Level
import io.grpc.ServerServiceDefinition
import com.example.protos.orders._
import scala.jdk.CollectionConverters._
import scalapb.GeneratedMessage
import io.grpc.Metadata
import io.quartz.grpc.MethodRefBase
import io.quartz.grpc.Router
import io.quartz.grpc.TraitMethodFinder

object param1 extends QueryParam("param1")
object param2 extends QueryParam("param2")

object MyApp extends IOApp {

  val service = new GreeterService();

  val filter: WebFilter = (request: Request) =>
    IO(
      Either.cond(
        !request.uri.getPath().endsWith("na.txt"),
        request.hdr("test_tid" -> "ABC123Z9292827"),
        Response
          .Error(StatusCode.Forbidden)
          .asText("Denied: " + request.uri.getPath())
      )
    )

  var HOME_DIR = "/Users/ostrygun/" // last slash is important!

  val R: HttpRouteIO = {
    case req @ POST -> Root / "com.example.protos.Greeter" / "LotsOfReplies" =>
      for {
        request <- req.body
        response <- IO(service._lotsOfReplies(request, null))
      } yield (Response
        .Ok()
        .asStream(response.flatMap(c => { println("1"); Stream.emits(c) }))
        .trailers(
          Headers(
            "grpc-status" -> "0",
            "grpc-message" -> "ok"
          )
        )
        .hdr("content-type" -> "application/grpc"))

    case req @ POST -> Root / "com.example.protos.Greeter" / "SayHello" =>
      for {
        request <- req.body
        io <- service._sayHello(request, null)

      } yield (Response
        .Ok()
        .trailers(
          Headers(
            "grpc-status" -> "0",
            "grpc-message" -> "ok",
            "content-type" -> "application/grpc"
          )
        )
        .hdr("content-type" -> "application/grpc"))
        .asStream(
          Stream.emits(io.toByteArray)
        )
  }

  def run(args: List[String]) = {

    val greeterService: Resource[IO, ServerServiceDefinition] =
      GreeterFs2Grpc.bindServiceResource[IO](new GreeterService)

    val T = greeterService.use { sd =>
      for {
        _ <- IO(QuartzH2Server.setLoggingLevel(Level.DEBUG))
          .whenA(args.find(_ == "--debug").isDefined)
        _ <- IO(QuartzH2Server.setLoggingLevel(Level.ERROR))
          .whenA(args.find(_ == "--error").isDefined)
        _ <- IO(QuartzH2Server.setLoggingLevel(Level.OFF))
          .whenA(args.find(_ == "--off").isDefined)
        _ <- IO(QuartzH2Server.setLoggingLevel(Level.TRACE))
          .whenA(args.find(_ == "--trace").isDefined)

        ctx <- QuartzH2Server.buildSSLContext(
          "TLSv1.3",
          "keystore.jks",
          "password"
        )
        grpcIO <- IO(
          Router[GreeterService](
            service,
            sd,
            TraitMethodFinder.getAllMethods[GreeterService]
          ).getIO
        )
        exitCode <- new QuartzH2Server(
          "localhost",
          8443,
          32000,
          Some(ctx)
        ).start(grpcIO, sync = false)
      } yield (exitCode)
    }

    T
  }
}
