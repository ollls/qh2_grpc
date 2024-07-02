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
import io.quartz.grpc.MethodRefBase
import io.grpc.Status
import io.quartz.grpc.QH2GrpcError

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

    def headersToMetadata(hdr: Headers): Metadata = {
      val excludedPrefixes = Set(":", "grpc-")
      val excludedHeaders = Set("content-type", "user-agent")

      hdr.tbl.foldRight(new Metadata) {
        case ((key, value), m) => {
          val lowerKey = key.toLowerCase
          if (
            !excludedPrefixes.exists(lowerKey.startsWith) && !excludedHeaders
              .contains(lowerKey)
          ) {
            if (!key.endsWith("-bin")) {
              value.foreach { v =>
                m.put(
                  Metadata.Key.of(lowerKey, Metadata.ASCII_STRING_MARSHALLER),
                  v
                )
              }
            } else {
              value.foreach { v =>
                val base64string = java.util.Base64.getDecoder.decode(v)
                m.put(
                  Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER),
                  base64string
                )
              }
            }
          }
          m
        }
      }
    }

    def getIO: HttpRoute = { req =>
      for {
        fR <- filter(req)
        response <- fR match {
          // provide io.grpc.Status/message in response's trailers
          case Left(response) => IO(Some(response))
          case Right(request) =>
            post_getIO(request).handleError {
              case QH2GrpcError(status, message) => {
                Some(
                  Response
                    .Ok()
                    .hdr("content-type" -> "application/grpc")
                    .trailers(
                      Headers(
                        "grpc-status" -> status.getCode.value().toString(),
                        "grpc-message" -> message.toString()
                      )
                    )
                    .asStream(
                      Stream.emits(
                        Utils.outputStreamForResponse(0).toByteArray()
                      )
                    )
                )
              }
              case e: Throwable =>
                Some(
                  Response
                    .Ok()
                    .hdr("content-type" -> "application/grpc")
                    .trailers(
                      Headers(
                        "grpc-status" -> Status.INTERNAL
                          .getCode()
                          .value()
                          .toString(),
                        "grpc-message" -> e.getMessage
                      )
                    )
                    .asStream(
                      Stream.emits(
                        Utils.outputStreamForResponse(0).toByteArray()
                      )
                    )
                )
            }
        }
      } yield (response)

    }

    private def post_getIO: HttpRoute = { req =>
      {
        for {
          //grpc_request <- req.body
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
                  req.stream,
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
