package org.omp4j.tree

import org.antlr.v4.runtime.atn._
import org.antlr.v4.runtime.tree._
import org.antlr.v4.runtime._

import org.omp4j.Config
import org.omp4j.grammar._

case class TopClass(ctx: Java8Parser.ClassDeclarationContext, parent: OMPClass, parser: Java8Parser)(implicit conf: Config, classMap: OMPFile.ClassMap) extends OMPClass(ctx, parent, parser) with Reflectable {
	
	override lazy val cunit: Java8Parser.CompilationUnitContext = cunit()

	protected def cunit(pt: ParserRuleContext = ctx): Java8Parser.CompilationUnitContext = {
		try {
			val cunit = pt.asInstanceOf[Java8Parser.CompilationUnitContext]
			cunit
		} catch {
			case e: Exception => cunit(pt.getParent)
		}
	}
}
