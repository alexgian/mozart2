package org.mozartoz.bootcompiler
package transform

import scala.collection.mutable.ListBuffer

import oz._
import ast._
import symtab._

object DesugarFunctor extends Transformer with TreeDSL {
  var ByNeedDot: Option[Symbol] = None

  /* Find some symbols we need from the Base env */
  private def findThingsWeNeedInDecls(decls: List[Variable]) {
    for (Variable(symbol) <- decls) {
      symbol.name match {
        case "ByNeedDot" => ByNeedDot = Some(symbol)
        case _ => ()
      }
    }
  }

  override def transformStat(statement: Statement) = statement match {
    case LocalStatement(decls, _) =>
      findThingsWeNeedInDecls(decls)
      super.transformStat(statement)

    case _ =>
      super.transformStat(statement)
  }

  override def transformExpr(expression: Expression) = expression match {
    case LocalExpression(decls, _) =>
      findThingsWeNeedInDecls(decls)
      super.transformExpr(expression)

    case functor @ FunctorExpression(name, require, prepare,
        imports, define, exports) if (!require.isEmpty || !prepare.isEmpty) =>

      val innerFunctor = treeCopy.FunctorExpression(expression, name,
          Nil, None, imports, define, exports)

      val innerFunctorVar = Variable.newSynthetic()

      val outerImports = require

      val outerDefine = atPos(prepare.getOrElse(functor)) {
        val (prepareDecls, prepareStat) = prepare match {
          case Some(LocalStatement(decls, stat)) => (decls, stat)
          case None => (Nil, SkipStatement())
        }

        val allInnerDefineDecls = prepareDecls :+ innerFunctorVar

        LOCAL (allInnerDefineDecls:_*) IN {
          prepareStat ~
          (innerFunctorVar === innerFunctor)
        }
      }

      val outerExports = List(atPos(functor) {
        FunctorExport(Constant(OzAtom("inner")), innerFunctorVar)
      })

      val outerFunctor = treeCopy.FunctorExpression(expression, name+"<outer>",
          Nil, None, outerImports, Some(outerDefine), outerExports)

      // TODO This not quite right, need to apply this functor
      transformExpr(outerFunctor)

    case functor @ FunctorExpression(name, Nil, None,
        imports, define, exports) =>

      val importsRec = makeImportsRec(imports)
      val exportsRec = makeExportsRec(exports)
      val applyFun = makeApplyFun(define, imports, exports)

      val functorRec = atPos(functor) {
        Record(OzAtom("functor"), List(
            RecordField(OzAtom("import"), importsRec),
            RecordField(OzAtom("export"), exportsRec),
            RecordField(OzAtom("apply"), applyFun)))
      }

      transformExpr(functorRec)

    case _ =>
      super.transformExpr(expression)
  }

  def makeImportsRec(imports: List[FunctorImport]): Expression = {
    val resultFields = for {
      FunctorImport(module:Variable, aliases, location) <- imports
    } yield {
      val typeField = {
        val requiredFeatures =
          for (AliasedFeature(feat, _) <- aliases)
            yield feat.value

        RecordField(OzAtom("type"), OzList(requiredFeatures))
      }

      val fromField = location map { location =>
        RecordField(OzAtom("from"), OzAtom(location))
      }

      val info = Record(OzAtom("info"), List(typeField) ++ fromField)

      RecordField(OzAtom(module.symbol.name), info)
    }

    Record(OzAtom("import"), resultFields)
  }

  def makeExportsRec(exports: List[FunctorExport]): Expression = {
    val resultFields = for {
      FunctorExport(Constant(feature:OzFeature), _) <- exports
    } yield {
      RecordField(feature, OzAtom("value"))
    }

    Record(OzAtom("export"), resultFields)
  }

  def makeApplyFun(define: Option[LocalStatementOrRaw],
      imports: List[FunctorImport],
      exports: List[FunctorExport]): Expression = {
    val importsParam = Variable.newSynthetic("<Imports>", formal = true)

    val importedDecls = extractAllImportedDecls(imports)

    val (definedDecls, defineStat) = define match {
      case Some(LocalStatement(decls, stat)) => (decls, stat)
      case None => (Nil, SkipStatement())
    }

    val allDecls = importedDecls ++ definedDecls

    FUN("<Apply>", List(importsParam)) {
      LOCAL (allDecls:_*) IN {
        val statements = new ListBuffer[Statement]
        def exec(statement: Statement) = statements += statement

        // Load imported decls
        for (FunctorImport(module:Variable, aliases, _) <- imports) {
          exec(module === (importsParam dot OzAtom(module.symbol.name)))

          for (AliasedFeature(feature, Some(variable)) <- aliases) {
            exec(variable === (ByNeedDot.get callExpr (module, feature)))
          }
        }

        // Of course execute the actual define statements
        exec(defineStat)

        // Now compute the export record
        val exportFields = for {
          FunctorExport(feature, value) <- exports
        } yield {
          RecordField(feature, value)
        }

        val exportRec = Record(OzAtom("export"), exportFields)

        // Final body
        CompoundStatement(statements.toList) ~>
        exportRec
      }
    }
  }

  def extractAllImportedDecls(imports: List[FunctorImport]) = {
    val result = new ListBuffer[Variable]

    for (FunctorImport(module:Variable, aliases, _) <- imports) {
      result += module

      for (AliasedFeature(_, Some(variable:Variable)) <- aliases)
        result += variable
    }

    result.toList
  }
}
