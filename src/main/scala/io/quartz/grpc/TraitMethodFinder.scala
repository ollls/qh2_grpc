package io.quartz.grpc

import cats.effect.IO
import scalapb.GeneratedMessage

import io.grpc.Metadata

import scala.quoted.*

import scala.collection.mutable

import fs2.Stream

class MethodRefBase[T]

case class MethodUnaryToUnary[T](
    value: T => (GeneratedMessage, Metadata) => IO[GeneratedMessage]
) extends MethodRefBase[T]

case class MethodUnaryToStream[T](
    value: T => (GeneratedMessage, Metadata) => Stream[IO, GeneratedMessage]
) extends MethodRefBase[T]

case class MethodStreamToUnary[T](
    value: T => (Stream[IO, GeneratedMessage], Metadata) => IO[GeneratedMessage]
) extends MethodRefBase[T]

case class MethodStreamToStream[T](
    value: T => (
        Stream[IO, GeneratedMessage],
        Metadata
    ) => Stream[IO, GeneratedMessage]
) extends MethodRefBase[T]

/*
Explanation on:  m.tree.asInstanceOf[DefDef].returnTpt.tpe <:< TypeRepr.of[IO[GeneratedMessage]]
Getting the tree of the method symbol (m.tree)
Casting it to DefDef (since we know it's a method definition)
Accessing the return type tree (returnTpt)
Getting the type from that tree (.tpe) */

object TraitMethodFinder {


  inline def getAllMethodsRef[T]: Map[String, MethodUnaryToUnary[T]] = ${
    getAllMethodsImplRef[T]
  }

  private def getAllMethodsImplRef[T: Type](using
      Quotes
  ): Expr[Map[String, MethodUnaryToUnary[T]]] = {
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val matchingMethods = tpe.typeSymbol.declarations.filter { m =>
      m.isDefDef &&
      m.paramSymss.flatten.size == 2 &&
      m.paramSymss.flatten.head.typeRef <:< TypeRepr.of[GeneratedMessage] &&
      m.tree.asInstanceOf[DefDef].returnTpt.tpe <:< TypeRepr
        .of[IO[GeneratedMessage]]
    }
    '{
      val methodMap = mutable.Map[String, MethodUnaryToUnary[T]]()
      ${
        Expr.ofSeq(matchingMethods.map { method =>
          val methodName = Expr(method.name)
          val reqType = method.paramSymss.flatten.head.typeRef
          '{
            methodMap.put(
              $methodName,
              MethodUnaryToUnary((obj: T) =>
                (messageParam: GeneratedMessage, metadataParam: Metadata) =>
                  ${
                    val castedParam = Typed(
                      '{ messageParam }.asTerm,
                      Inferred(reqType)
                    )
                    Apply(
                      Select('{ obj }.asTerm, method),
                      List(castedParam, '{ metadataParam }.asTerm)
                    ).asExprOf[IO[GeneratedMessage]]
                  }
              )
            )
          }
        })
      }
      methodMap.toMap
    }
  }

  inline def getAllMethodsStreamRef[T]: Map[String, MethodUnaryToStream[T]] = ${
    getAllMethodsImplStreamRef[T]
  }

  private def getAllMethodsImplStreamRef[T: Type](using
      Quotes
  ): Expr[Map[String, MethodUnaryToStream[T]]] = {
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val matchingMethods = tpe.typeSymbol.declarations.filter { m =>
      m.isDefDef &&
      m.paramSymss.flatten.size == 2 &&
      m.paramSymss.flatten.head.typeRef <:< TypeRepr.of[GeneratedMessage] &&
      m.tree.asInstanceOf[DefDef].returnTpt.tpe <:< TypeRepr
        .of[Stream[IO, GeneratedMessage]]
    }
    '{
      val methodMap = mutable.Map[String, MethodUnaryToStream[T]]()
      ${
        Expr.ofSeq(matchingMethods.map { method =>
          val methodName = Expr(method.name)
          val reqType = method.paramSymss.flatten.head.typeRef
          '{
            methodMap.put(
              $methodName,
              MethodUnaryToStream((obj: T) =>
                (messageParam: GeneratedMessage, metadataParam: Metadata) =>
                  ${
                    val castedParam = Typed(
                      '{ messageParam }.asTerm,
                      Inferred(reqType)
                    )
                    Apply(
                      Select('{ obj }.asTerm, method),
                      List(castedParam, '{ metadataParam }.asTerm)
                    ).asExprOf[Stream[IO, GeneratedMessage]]
                  }
              )
            )
          }
        })
      }
      methodMap.toMap
    }
  }

  inline def getAllMethodsStreamToUnary[T]
      : Map[String, MethodStreamToUnary[T]] = ${
    getAllMethodsImplStreamToUnary[T]
  }
  private def getAllMethodsImplStreamToUnary[T: Type](using
      Quotes
  ): Expr[Map[String, MethodStreamToUnary[T]]] = {
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val matchingMethods = tpe.typeSymbol.declarations.filter { m =>
      m.isDefDef &&
      m.paramSymss.flatten.size == 2 &&
      m.paramSymss.flatten.head.typeRef <:< TypeRepr
        .of[Stream[IO, GeneratedMessage]] &&
      m.tree.asInstanceOf[DefDef].returnTpt.tpe <:< TypeRepr
        .of[IO[GeneratedMessage]]
    }
    '{
      val methodMap = mutable.Map[String, MethodStreamToUnary[T]]()
      ${
        Expr.ofSeq(matchingMethods.map { method =>
          val methodName = Expr(method.name)
          val reqType = method.paramSymss.flatten.head.typeRef
          '{
            methodMap.put(
              $methodName,
              MethodStreamToUnary((obj: T) =>
                (
                    streamParam: Stream[IO, GeneratedMessage],
                    metadataParam: Metadata
                ) =>
                  ${
                    val castedParam = Typed(
                      '{ streamParam }.asTerm,
                      Inferred(reqType)
                    )
                    Apply(
                      Select('{ obj }.asTerm, method),
                      List(castedParam, '{ metadataParam }.asTerm)
                    ).asExprOf[IO[GeneratedMessage]]
                  }
              )
            )
          }
        })
      }
      methodMap.toMap
    }
  }

  inline def getAllMethodsStreamToStream[T]
      : Map[String, MethodStreamToStream[T]] = ${
    getAllMethodsImplStreamToStream[T]
  }

  private def getAllMethodsImplStreamToStream[T: Type](using Quotes): Expr[Map[String, MethodStreamToStream[T]]] = {
  import quotes.reflect.*
  val tpe = TypeRepr.of[T]
  val matchingMethods = tpe.typeSymbol.declarations.filter { m =>
    m.isDefDef &&
    m.paramSymss.flatten.size == 2 &&
    m.paramSymss.flatten.head.typeRef <:< TypeRepr.of[Stream[IO, GeneratedMessage]] &&
    m.paramSymss.flatten.last.typeRef <:< TypeRepr.of[Metadata] &&
    m.tree.asInstanceOf[DefDef].returnTpt.tpe <:< TypeRepr.of[Stream[IO, GeneratedMessage]]
  }
  '{
    val methodMap = mutable.Map[String, MethodStreamToStream[T]]()
    ${
      Expr.ofSeq(matchingMethods.map { method =>
        val methodName = Expr(method.name)
        val reqType = method.paramSymss.flatten.head.typeRef
        '{
          methodMap.put(
            $methodName,
            MethodStreamToStream((obj: T) =>
              (streamParam: Stream[IO, GeneratedMessage], metadataParam: Metadata) =>
                ${
                  val castedParam = Typed(
                    '{ streamParam }.asTerm,
                    Inferred(reqType)
                  )
                  Apply(
                    Select('{ obj }.asTerm, method),
                    List(castedParam, '{ metadataParam }.asTerm)
                  ).asExprOf[Stream[IO, GeneratedMessage]]
                }
            )
          )
        }
      })
    }
    methodMap.toMap
  }
}

}
