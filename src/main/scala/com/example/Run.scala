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
import io.grpc.Metadata
import io.quartz.grpc.MethodRef
import io.quartz.grpc.MethodRefBase
import io.grpc.Status
//import io.quartz.http2.routes.WebFilter

import io.quartz.grpc.Utils
import io.quartz.grpc.TraitMethodFinder

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

  class Router[T](
      service: T,
      d: ServerServiceDefinition,
      method_map: Map[String, MethodRefBase[T]],
      filter: WebFilter = (r0: Request) => IO(Right(r0))
  ) {

    // TODO -bin suffix we do later
    def headersToMetadata(hdr: Headers): Metadata = {
      hdr.tbl.foldRight(new Metadata)((pair, m) => {
        if (
          pair._1.startsWith(":") == false && pair._1
            .startsWith("grpc") == false
        ) {
          m.put(
            Metadata.Key.of(pair._1, Metadata.ASCII_STRING_MARSHALLER),
            pair._2.mkString(",")
          ); m
        } else m
      })
    }

    def getIO: HttpRoute = { req =>
      for {
        fR <- filter(req)
        response <- fR match {
          // provide io.grpc.Status/message in response's trailers
          case Left(response) => IO(Some(response))
          case Right(request) => post_getIO(request)
        }
      } yield (response)

    }

    private def post_getIO: HttpRoute = { req =>
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

          result: Option[fs2.Stream[IO, Array[Byte]]] <- methodDefOpt match {
            case None => IO(None)
            case Some(serverMethodDef) =>
              Utils
                .process01[T, GeneratedMessage, GeneratedMessage](
                  service,
                  serverMethodDef,
                  method_map: Map[String, MethodRefBase[T]],
                  grpc_request,
                  //null
                  headersToMetadata(req.headers)
                )
                .map(c => Some(c))
          }
        } yield (result.map(stream =>
          Response
            .Ok()
            .trailers(
              Headers(
                "grpc-status" -> Status.OK.getCode().value().toString(),
                "grpc-message" -> "ok",
                "content-type" -> "application/grpc"
              )
            )
            .hdr("content-type" -> "application/grpc")
            .asStream(stream.flatMap(Stream.emits(_)))
        ))

      }

    }
  }

  def run(args: List[String]) = {

    val greeterService: Resource[IO, ServerServiceDefinition] =
      GreeterFs2Grpc.bindServiceResource[IO](new GreeterService)

    val mmap = TraitMethodFinder.getAllMethodsRef[GreeterService]
    val mmap2 = TraitMethodFinder.getAllMethodsStreamRef[GreeterService]

    println("Methods: " + mmap.size)
    println("Methods: " + mmap2.size)

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
        grpcIO <- IO(Router[GreeterService](service, sd, mmap ++ mmap2).getIO)
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
