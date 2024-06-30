package com.example

import com.example.protos.orders._
import cats.effect.IO
import io.grpc.Metadata

import java.io.ByteArrayOutputStream
import io.quartz.grpc.Utils
import fs2.Stream

//https://grpc.io/docs/what-is-grpc/core-concepts/

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

  override def lotsOfReplies(
      request: HelloRequest,
      ctx: Metadata
  ): fs2.Stream[IO, HelloReply] = {
    fs2.Stream.emits(
      Seq(
        HelloReply(Option("TZ: " + java.time.ZonedDateTime.now().toString())),
        HelloReply(Option("TZ: " + java.time.ZonedDateTime.now().toString())),
        HelloReply(Option("TZ: " + java.time.ZonedDateTime.now().toString())),
        HelloReply(Option("TZ: " + java.time.ZonedDateTime.now().toString()))
      )
    )
  }

}
