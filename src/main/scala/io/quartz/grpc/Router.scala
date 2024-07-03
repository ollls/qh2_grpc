package io.quartz.grpc

import io.grpc.ServerMethodDefinition
import io.grpc.ServerServiceDefinition
import io.grpc.Metadata
import io.quartz.http2.model.{Headers, Request, Response}
import scala.jdk.CollectionConverters._
import cats.effect.IO
import io.quartz.http2.routes.HttpRoute
import io.quartz.http2.routes.WebFilter
import scalapb.GeneratedMessage
import fs2.Stream
import io.grpc.Status

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
        // grpc_request <- req.body
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
