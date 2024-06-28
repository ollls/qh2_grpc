package io.quartz.grpc

import cats.effect.IO
import scalapb.GeneratedMessage

import io.grpc.Metadata

import scala.quoted.*
import cats.effect.IO


import scala.quoted.*
import cats.effect.IO


/* 
Explanation on:  m.tree.asInstanceOf[DefDef].returnTpt.tpe <:< TypeRepr.of[IO[GeneratedMessage]]
Getting the tree of the method symbol (m.tree)
Casting it to DefDef (since we know it's a method definition)
Accessing the return type tree (returnTpt)
Getting the type from that tree (.tpe) */

object TraitMethodFinder:
  inline def findMethod[T](name: String): Option[T => (GeneratedMessage, Metadata) => IO[GeneratedMessage]] = 
    ${ findMethodImpl[T]('name) }

  private def findMethodImpl[T: Type](nameExpr: Expr[String])(using Quotes): Expr[Option[T => (GeneratedMessage, Metadata) => IO[GeneratedMessage]]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val methodName = nameExpr.valueOrAbort

    val methodOpt = tpe.typeSymbol.declarations.find { m => 
      m.isDefDef && 
      m.name == methodName && 
      m.paramSymss.flatten.size == 2 &&
      m.paramSymss.flatten.head.typeRef <:< TypeRepr.of[GeneratedMessage] //&&
      //m.paramSymss.flatten.last.typeRef =:= TypeRepr.of[Metadata] &&
      //m.tree.asInstanceOf[DefDef].returnTpt.tpe <:< TypeRepr.of[IO[GeneratedMessage]]
    }

    methodOpt match
      case Some(method) =>
        val reqType = method.paramSymss.flatten.head.typeRef
        '{
          Some((obj: T) => (messageParam: GeneratedMessage, metadataParam: Metadata) =>
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
        }
      case None =>
        '{ None }