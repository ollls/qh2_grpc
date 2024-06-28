package io.quartz.grpc

import java.io.ByteArrayOutputStream
import io.grpc.Metadata
import cats.effect.IO


import scalapb.grpc.Marshaller
import scalapb.GeneratedMessage

import java.io.InputStream
import java.io.ByteArrayInputStream
import io.grpc.ServerMethodDefinition
import scala.quoted.*

import com.example.GreeterService

object Utils {

  inline def listMethods[T]: List[String] = ${ listMethodsImpl[T] }

  private def listMethodsImpl[T: Type](using Quotes): Expr[List[String]] = {
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    val methods = symbol.methodMembers
      .filter(m => !m.isClassConstructor && !m.isNoSymbol)
      .map(_.name)
      .toList

    Expr(methods)
  }

  inline def process01[svcT, ReqT <: GeneratedMessage, RespT <: GeneratedMessage](
      svc: svcT,
      d: ServerMethodDefinition[GeneratedMessage, GeneratedMessage],
      request: Array[Byte],
      ctx: Metadata
  ): IO[ByteArrayOutputStream] = {

    for {
      rm <- IO(d.getMethodDescriptor().getRequestMarshaller())
      req <- IO(rm.parse( new ByteArrayInputStream(extractRequest(request))))
      method <- IO(TraitMethodFinder.findMethod[svcT]("sayHello").get)
      response <- method(svc)(req, ctx)
      oS <- IO(outputStreamForResponse(response.serializedSize))
      _ <- IO(response.writeTo(oS))

    } yield (oS)

  }

  def extractRequest(protoBytes: Array[Byte]): Array[Byte] = {
    val incoming_size =
      java.nio.ByteBuffer.wrap(protoBytes.slice(1, 5)).getInt()
    protoBytes.slice(5, incoming_size + 1 + 4)
  }

  def outputStreamForResponse(serializedSize: Int): ByteArrayOutputStream = {
    val outputStream = new ByteArrayOutputStream()
    outputStream.writeBytes(
      java.nio.ByteBuffer
        .allocate(5)
        .put(0.byteValue)
        .putInt(serializedSize)
        .array()
    )

    outputStream
  }

}
