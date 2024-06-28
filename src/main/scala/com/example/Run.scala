package com.example

import cats.effect.{IO, Resource, IOApp}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.http2.routes.HttpRoute
import fs2.Stream
import io.quartz.http2.model.StatusCode
import io.quartz.http2.routes.WebFilter
import cats.syntax.all.catsSyntaxApplicativeByName
import io.quartz.MyLogger._
import ch.qos.logback.classic.Level
import io.grpc.ServerServiceDefinition
import com.example.protos.orders._
import java.io.ByteArrayOutputStream
import io.grpc.ServerMethodDefinition
import scala.jdk.CollectionConverters._
import scalapb.GeneratedMessage

import io.quartz.grpc.Utils

object param1 extends QueryParam("param1")
object param2 extends QueryParam("param2")

object MyApp extends IOApp {

  val service = new GreeterService();

  // val greeterService: Resource[IO, ServerServiceDefinition] =
  //  GreeterFs2Grpc.bindServiceResource[IO](new GreeterService)

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


  class Router(d: ServerServiceDefinition) {

    def getIO: HttpRoute = { req =>
      {
        for {
          grpc_request <- req.body
          methodDefOpt <- IO(
            d.getMethods()
              .asScala
              .find(p => {
                "/" + p.getMethodDescriptor().getFullMethodName() == req.path
              })
              .map(mD =>
                mD.asInstanceOf[ServerMethodDefinition[
                  GeneratedMessage,
                  GeneratedMessage
                ]]
              )
          )

          result: Option[ByteArrayOutputStream] <- methodDefOpt match {
            case None => IO(None)
            case Some(serverMethodDef) =>
              Utils
                .process01[GreeterService, GeneratedMessage, GeneratedMessage](
                  service,
                  serverMethodDef,
                  grpc_request,
                  null
                )
                .map(c => Some(c))
          }
        } yield (result.map(bytes =>
          Response
            .Ok()
            .trailers(
              Headers(
                "grpc-status" -> "0",
                "grpc-message" -> "ok",
                "content-type" -> "application/grpc"
              )
            )
            .hdr("content-type" -> "application/grpc")
            .asStream(Stream.emits(bytes.toByteArray()))
        ))

      }

    }
  }

  def run(args: List[String]) /*: IO[ExitCode]*/ = {

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
        grpcIO <- IO(Router(sd).getIO)
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
