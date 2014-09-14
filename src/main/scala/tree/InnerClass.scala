package org.omp4j.tree

import org.antlr.v4.runtime.atn._
import org.antlr.v4.runtime.tree._
import org.antlr.v4.runtime._

import org.omp4j.Config
import org.omp4j.grammar._

/** The inner class representation (with FQN; reflectable) */
case class InnerClass(ctx: Java8Parser.ClassDeclarationContext, parent: OMPClass, parser: Java8Parser)(implicit conf: Config, classMap: OMPFile.ClassMap) extends OMPClass(ctx, parent, parser) with Reflectable