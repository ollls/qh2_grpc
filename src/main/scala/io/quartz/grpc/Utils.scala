package io.quartz.grpc

import java.io.ByteArrayOutputStream
import io.grpc.Metadata
import cats.effect.IO
import scalapb.GeneratedMessage
import java.io.ByteArrayInputStream
import io.grpc.ServerMethodDefinition
import scala.quoted.*

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

  inline def process01[
      svcT,
      ReqT <: GeneratedMessage,
      RespT <: GeneratedMessage
  ](
      svc: svcT,
      d: ServerMethodDefinition[GeneratedMessage, GeneratedMessage],
      method_map: Map[String, io.quartz.grpc.MethodRefBase[svcT]],
      request: Array[Byte],
      ctx: Metadata
  ): IO[fs2.Stream[IO, Array[Byte]]] = {

    for {

      methodName0 <- IO(d.getMethodDescriptor().getBareMethodName())
      methodName <- IO(
        methodName0.substring(0, 1).toLowerCase() + methodName0.substring(1)
      )

      rm <- IO(d.getMethodDescriptor().getRequestMarshaller())
      req <- IO(rm.parse(new ByteArrayInputStream(extractRequest(request))))
      method <- IO(
        method_map
          .get(methodName)
          .get
      )
      outputStream <- IO(new ByteArrayOutputStream())
      response <- method match {
        case MethodRef[svcT](m) =>
          m(svc)(req, ctx)
            .map(r => {
              r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
              outputStream.toByteArray
            })
            .map(fs2.Stream.emit[IO, Array[Byte]](_))

        case MethodStreamRef[svcT](m) =>
          IO {
            m(svc)(req, ctx).map(r => {
              r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
              outputStream.toByteArray()
            })
          }
      }
    } yield (response)

  }

  def extractRequest(protoBytes: Array[Byte]): Array[Byte] = {
    val incoming_size =
      java.nio.ByteBuffer.wrap(protoBytes.slice(1, 5)).getInt()
    protoBytes.slice(5, incoming_size + 1 + 4)
  }

  def sizeResponse(
      serializedSize: Int,
      i: ByteArrayOutputStream
  ): ByteArrayOutputStream = {
    i.reset()
    i.writeBytes(
      java.nio.ByteBuffer
        .allocate(5)
        .put(0.byteValue)
        .putInt(serializedSize)
        .array()
    )
    i
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
