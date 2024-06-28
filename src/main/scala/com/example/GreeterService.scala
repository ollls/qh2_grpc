package com.example

import com.example.protos.orders._
import cats.effect.IO
import io.grpc.Metadata

import java.io.ByteArrayOutputStream
import io.quartz.grpc.Utils

class GreeterService extends GreeterFs2Grpc[IO, Metadata] {


  def _sayHello(
      request: Array[Byte],
      ctx: Metadata
  ): IO[ByteArrayOutputStream] = {
    for {
      response <- sayHello(
        HelloRequest.parseFrom(Utils.extractRequest(request)),
        ctx
      )
      outputStream <- IO(Utils.outputStreamForResponse(response.serializedSize))
      _ <- IO(
        response.writeTo(outputStream)
      )
    } yield (outputStream)
  }

  override def sayHello(
      request: HelloRequest,
      ctx: Metadata
  ): IO[HelloReply] = {
    val zdt = java.time.ZonedDateTime.now()
    IO(HelloReply(Option("TZ: " + zdt.toString())))
  }

}
