package io.quartz.grpc

import java.io.ByteArrayOutputStream
import io.grpc.Metadata
import cats.effect.IO
import scalapb.GeneratedMessage
import java.io.ByteArrayInputStream
import io.grpc.ServerMethodDefinition
import io.grpc.MethodDescriptor.Marshaller
import scala.quoted.*
import scala.util.Try
import io.grpc.Status
import fs2.Chunk
import fs2.Stream
import java.nio.ByteBuffer

case class QH2GrpcError(val code: Status, val message: String)
    extends Exception(message)

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
      request: fs2.Stream[IO, Byte],
      ctx: Metadata
  ): IO[fs2.Stream[IO, Array[Byte]]] = {
    for {
      methodName0 <- IO(d.getMethodDescriptor().getBareMethodName())
      methodName <- IO(
        methodName0.substring(0, 1).toLowerCase() + methodName0.substring(1)
      )
      rm <- IO(d.getMethodDescriptor().getRequestMarshaller())

      method <- IO.fromOption(method_map.get(methodName))(
        new NoSuchElementException(
          s"Unexpected error: scala macro method Map: GRPC Service method not found: $methodName"
        )
      )
      outputStream <- IO(new ByteArrayOutputStream())
      response <- method match {
        case MethodUnaryToUnary[svcT](m) =>
          for {
            req0 <- request.compile.toVector
              .map(_.toArray)
            req <- IO(rm.parse(new ByteArrayInputStream(extractRequest(req0))))

            response <- m(svc)(req, ctx)
              .map(r => {
                r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
                outputStream.toByteArray
              })
              .map(fs2.Stream.emit[IO, Array[Byte]](_))
          } yield (response)

        case MethodUnaryToStream[svcT](m) =>
          for {
            req0 <- request.compile.toVector
              .map(_.toArray)
            req <- IO(rm.parse(new ByteArrayInputStream(extractRequest(req0))))

            response <- IO(m(svc)(req, ctx).map(r => {
              r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
              outputStream.toByteArray
            }))
          } yield (response)

        case MethodStreamToUnary[svcT](m) =>
          for {
            req <- IO(byteStreamToMessageStream(request, rm))
            response <- m(svc)(req, ctx)
              .map(r => {
                r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
                outputStream.toByteArray
              })
              .map(fs2.Stream.emit[IO, Array[Byte]](_))
          } yield (response)

        case MethodStreamToStream(m) =>
          for {
            req <- IO(byteStreamToMessageStream(request, rm))
            response <- IO(m(svc)(req, ctx).map(r => {
              r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
              outputStream.toByteArray
            }))
          } yield (response)
      }
    } yield (response)
  }

  def oneRequest(stream: fs2.Stream[IO, Byte]) = {
    for {
      sz <- sizeFromStream(stream)
      x <- stream.drop(1 + 4).chunkN(sz).compile.toList.map(_.head.toArray)
    } yield (x)
  }

  def sizeFromStream(s0: fs2.Stream[IO, Byte]): IO[Int] = {
    s0.take(1 + 4)
      .chunkN(5)
      .drop(1)
      .map(v => v.toByteBuffer.getInt())
      .compile
      .toList
      .map(_.head)
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

  /** Converts a stream of bytes into a stream of GeneratedMessage objects.
    *
    * This function processes a byte stream that contains serialized protocol
    * buffer messages. Each message in the stream is expected to be prefixed
    * with a 5-byte header: 1 byte for a marker or type indicator, followed by 4
    * bytes representing the message size.
    *
    * @param byteStream
    *   A Stream[IO, Byte] containing the serialized messages with headers.
    * @param rm
    *   A Marshaller[GeneratedMessage] used to parse the individual messages.
    * @return
    *   A Stream[IO, GeneratedMessage] of parsed protocol buffer messages.
    *
    * @note
    *   This function uses fs2's `scanChunks` for efficient processing of byte
    *   chunks. It accumulates bytes across chunk boundaries to ensure complete
    *   messages are parsed.
    */
  def byteStreamToMessageStream(
      byteStream: Stream[IO, Byte],
      rm: Marshaller[GeneratedMessage]
  ): Stream[IO, GeneratedMessage] = {

    def readSize(chunk: Chunk[Byte]): (Int, Chunk[Byte]) = {
      val size = chunk.drop(1).take(4).toArray
      val remaining = chunk.drop(5)
      (ByteBuffer.wrap(size).getInt, remaining)
    }

    byteStream.scanChunks[Chunk[Byte], Byte, GeneratedMessage](
      Chunk.empty[Byte]
    ) { (acc: Chunk[Byte], chunks: Chunk[Byte]) =>
      var current: Chunk[Byte] = acc ++ chunks
      var messages: Vector[GeneratedMessage] = Vector.empty
      var continue: Boolean = true

      while (continue && current.size >= 5) {
        val (size, remaining) = readSize(current)
        if (remaining.size >= size) {
          val (messageBytes, leftover) = remaining.splitAt(size)
          messages = messages :+ rm.parse(
            new ByteArrayInputStream(messageBytes.toArray)
          )
          current = leftover
        } else {
          continue = false
        }
      }

      (current, Chunk.from(messages))
    }
  }
}
