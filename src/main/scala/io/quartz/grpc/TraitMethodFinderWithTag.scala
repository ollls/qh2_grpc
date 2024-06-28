package io.quartz.grpc

import scala.quoted.*
import cats.effect.IO

import scala.reflect.ClassTag
import scalapb.GeneratedMessage

import io.grpc.Metadata

object TraitMethodFinderWithTag:
  inline def findMethod(clazz: Class[_], name: String): Option[Any => (GeneratedMessage, Metadata) => IO[GeneratedMessage]] = 
    ${ findMethodImpl('clazz, 'name) }

  private def findMethodImpl(clazzExpr: Expr[Class[_]], nameExpr: Expr[String])(using Quotes): Expr[Option[Any => (GeneratedMessage, Metadata) => IO[GeneratedMessage]]] =
    import quotes.reflect.*

    def classToTypeRepr(clazz: Expr[Class[_]])(using Quotes): TypeRepr =
      clazz.asTerm.tpe.dealias

    val tpe = classToTypeRepr(clazzExpr)
    
    nameExpr.value match
      case Some(methodName) =>
        val methodOpt = tpe.typeSymbol.declarations.find { m => 
          m.isDefDef && 
          m.name == methodName && 
          m.paramSymss.flatten.size == 2 &&
          m.paramSymss.flatten.head.typeRef <:< TypeRepr.of[GeneratedMessage] //&&
          //m.paramSymss.flatten.last.typeRef =:= TypeRepr.of[Metadata] &&
          ///m.tree.asInstanceOf[DefDef].returnTpt.tpe <:< TypeRepr.of[IO[GeneratedMessage]]
        }

        methodOpt match
          case Some(method) =>
            val reqType = method.paramSymss.flatten.head.typeRef
            '{
              Some((obj: Any) => (messageParam: GeneratedMessage, metadataParam: Metadata) =>
                ${ 
                  val castedParam = Typed(
                    '{ messageParam }.asTerm,
                    Inferred(reqType)
                  )
                  Apply(
                    Select(Typed('{ obj }.asTerm, Inferred(tpe)), method),
                    List(castedParam, '{ metadataParam }.asTerm)
                  ).asExprOf[IO[GeneratedMessage]]
                }
              )
            }
          case None =>
            '{ None }
      case None =>
        report.error("Method name must be a compile-time constant")
        '{ None }
