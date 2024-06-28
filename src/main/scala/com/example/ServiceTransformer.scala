package com.example

import scala.quoted.*
import cats.effect.IO
import io.grpc.Metadata
import java.io.ByteArrayOutputStream

object ServiceTransformer {
  inline def transform[T]: Any = ${ transformImpl[T] }

  def transformImpl[T: Type](using Quotes): Expr[Any] = {
    import quotes.reflect.*

    def getTypeString(tpe: TypeRepr): String = tpe.show(using Printer.TypeReprCode)

    val tpe = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    val newMethods = symbol.declarations.flatMap { decl =>
      decl match {
        case method: Symbol if method.isDefDef =>
          method.tree match {
            case DefDef(name, paramss, returnTpt, _) =>
              paramss match {
                case List(params: List[ValDef]) if params.size >= 2 =>
                  val requestParam = params.head
                  val ctxParam = params.last
                  val requestParamType = getTypeString(requestParam.tpt.tpe)
                  val ctxParamType = getTypeString(ctxParam.tpt.tpe)
                  val returnType = getTypeString(returnTpt.tpe)

                  val newMethodName = s"_$name"

                  val newMethodDef = 
                    s"""
                    def $newMethodName(
                      request: Array[Byte],
                      ctx: $ctxParamType
                    ): IO[ByteArrayOutputStream] = {
                      for {
                        deserializedRequest <- IO($requestParamType.parseFrom(Utils.extractRequest(request)))
                        response <- $name(deserializedRequest, ctx)
                        outputStream <- IO(Utils.outputStreamForResponse(response.serializedSize))
                        _ <- IO(response.writeTo(outputStream))
                      } yield outputStream
                    }
                    """

                  val originalMethodDef = 
                    s"""
                    override def $name(
                      ${requestParam.name}: $requestParamType,
                      ${ctxParam.name}: $ctxParamType
                    ): $returnType = 
                      super.$name(${requestParam.name}, ${ctxParam.name})
                    """

                  List(newMethodDef, originalMethodDef)
                case _ => Nil
              }
            case _ => Nil
          }
        case _ => Nil
      }
    }

    val newClassName = s"${symbol.name}Transformed"
    
    val classDef = 
      s"""
      class $newClassName extends ${tpe.show} {
        ${newMethods.mkString("\n")}
      }
      """

    Expr(classDef)
  }
}