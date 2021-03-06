package org.omp4j.preprocessor

import org.antlr.v4.runtime._
import org.antlr.v4.runtime.tree.SyntaxTree
import org.omp4j.Config
import org.omp4j.directive._
import org.omp4j.exception._
import org.omp4j.extractor.Inheritor
import org.omp4j.grammar._
import org.omp4j.tree._

import scala.collection.JavaConverters._
import scala.collection.mutable.Stack

/** Visitor that captures used variables in the directive body and prefixes them with context variable name.
 *
 * @param rewriter ANTLR rewriter
 * @param ompFile class hierarchy model
 * @param currentDirective the directive that is translated
 * @param parentContextName context variable name of the parent directive
 * @param parentCaptured captured variables by the parent directive
 * @param conf configuration context
 */
class TranslationVisitor(rewriter: TokenStreamRewriter, ompFile: OMPFile, currentDirective: Directive, parentContextName: String = "", parentCaptured: Set[OMPVariable] = Set())(implicit conf: Config) extends Java8BaseVisitor[Unit] {

	/** Stack of nested classes (Class name, isLocal) */
	private val clStack = scala.collection.mutable.Stack[OMPClass]() ++ Inheritor.getParentClasses(currentDirective.ctx, ompFile).reverse

	/** Last visited class when directive was discovered */
	private val directiveClass: OMPClass = clStack.head

	/** Set of local variables */
	private val locals = Inheritor.getPossiblyInheritedLocals(currentDirective.ctx)

	/** Set of parameters */
	private val params = Inheritor.getPossiblyInheritedParams(currentDirective.ctx)

	/** Set of variables to be added to context*/
	private var captured = Set[OMPVariable]()

	/** Does 'this' keyword appears in parallel statement? */
	private var capturedThis = false

	/** Union of private and firstfrivate attribute variables */
	private val privateVars = currentDirective.privateVars ++ currentDirective.firstPrivateVars

	/** Get proper extension to variable given.
	 *
 	 * @param c the variable
	 * @return empty string if variable is neither private nor firstprivate; the proper array suffix otherwise
	 */
	def extension(c: OMPVariable) = {
		if (privateVars contains c.name) s"[${currentDirective.executor}.getThreadNum()]"
		else ""
	}

	/** Name of OMPContext variable */
	private def contextName = currentDirective match {
		case null => throw new RuntimeException("Not existing directive context name required")
		case _    => currentDirective.contextVar
	}

	/** captured getters (since mutability, it can't be accessed publicly) */
	def getCaptured = captured -- parentCaptured

	/** capturedThis getters (since mutability, it can't be accessed publicly) */
	def getCapturedThis = capturedThis

	/** directiveClass getters (since mutability, it can't be accessed publicly) */
	def getDirectiveClass = directiveClass

	/** Try to find parent variable and after fail search ordinary variable
	  *
	  * @param id variable name
	  * @param locals currently known local variables
	  * @param params currently known parameter variables
	  * @param ompClass class model
	  * @return tuple of current context variable name and variable model
	  * @throws RuntimeException if context variable name is not specified
	  * @throws NoSuchElementException if variable not found
	  */
	private def findVariable(id: String, locals: Set[OMPVariable], params: Set[OMPVariable], ompClass: OMPClass): (String, OMPVariable) = {
		try {
			(parentContextName, OMPVariable.find(id, parentCaptured))
		} catch {
			case e: NoSuchElementException => (contextName, OMPVariable(id, locals, params, directiveClass))
		}
	}

	/** Get a list of tokens matching to the context given
 	  *
 	  * @param ctx the context
	  * @return the list of tokens
	  */
	private def getContextTokens(ctx: SyntaxTree): List[Token] = {
		val interval = ctx.getSourceInterval
		val tokenStream = rewriter.getTokenStream

		val toks = for {i <- interval.a to interval.b} yield tokenStream.get(i)
		toks.toList
	}


	/** Handle class stack */
	/** Move in class model and invokes the passed function.
	  *
	  * @param ctx class context
	  * @param f function to be invoked
	  * @tparam T type extending ParserRuleContext
	  * @throws ParseException if `ctx` doesn't match any directive
	  */
	private def handleStack[T <: ParserRuleContext](ctx: T, f: (T) => Unit) = {
		ompFile.classMap.get(ctx) match {
			case Some(c) => 
				clStack.push(c)
				f(ctx)
				clStack.pop
			case None => throw new ParseException("Unexpected error - class not found")
		}
	}

	override def visitClassDeclaration(ctx: Java8Parser.ClassDeclarationContext): Unit = {
		// Class declaration requires stack handling
		handleStack(ctx, super.visitClassDeclaration)
	}

	override def visitClassBody(ctx: Java8Parser.ClassBodyContext) = {
		// Class body may require stack handling
		if (
			ctx.parent.isInstanceOf[Java8Parser.ClassInstanceCreationExpressionContext] ||
			ctx.parent.isInstanceOf[Java8Parser.ClassInstanceCreationExpression_lf_primaryContext] ||
			ctx.parent.isInstanceOf[Java8Parser.ClassInstanceCreationExpression_lfno_primaryContext]) {

			handleStack(ctx, super.visitClassBody)
		} else {
			super.visitClassBody(ctx)
		}
	}

	/** Based on functions given, this method fetches the left-most "token" */
	private def getLeftName[T, S](
		top: T,
		topF: (T) => S,
		bottomF: (S) => S,
		topId: (T) => String,
		bottomId: (S) => String): String = {

		def getRec(under: S): String = {
			if (bottomF(under) == null) bottomId(under)
			else getRec(bottomF(under))
		}

		if (top == null) throw new NoSuchElementException
		else if (topF(top) == null) topId(top)
		else getRec(topF(top))
	}

	override def visitExpressionName(ctx: Java8Parser.ExpressionNameContext) = {
		// Capture variables/fields
		try {

			val id = getLeftName[Java8Parser.ExpressionNameContext, Java8Parser.AmbiguousNameContext](
				ctx,
				_.ambiguousName,
				_.ambiguousName,
				_.Identifier.getText,
				_.Identifier.getText
			)

			if (! (Inheritor.getDirectiveLocals(ctx, currentDirective).map(_.arrayLessName) contains id)) {
				try {
					val (realCtxName, v) = findVariable(id, locals, params, directiveClass)

					val tkns = getContextTokens(ctx)
					if (tkns.head.getText == id) {
						rewriter.replace(tkns.head, s"$realCtxName.${v.fullName}${extension(v)}")
					} else {
						rewriter.replace(ctx.start, ctx.stop, s"$realCtxName.${v.fullName}${extension(v)}")
					}

					captured += v
				} catch {
					case e: NoSuchElementException => ; // local (ok)
				}
			}
		} catch {
			// This should never happen, but just in case we log it
			case e: NoSuchElementException => conf.logger.log(s"IAE: ${e.getMessage}")
		}

		super.visitExpressionName(ctx)
	}

	override def visitMethodInvocation(ctx: Java8Parser.MethodInvocationContext) = {
		// Translate method invocation (caller and params)

		if (ctx.primary != null && ctx.primary.getText == "this") {
			if (clStack.head == directiveClass) {
				// handle only the '.' as 'this' will be handled automatically later on
				val dot = getContextTokens(ctx)(1)
				rewriter.delete(dot)
			}
		} else if (ctx.typeName != null) {

			val id = getLeftName[Java8Parser.TypeNameContext, Java8Parser.PackageOrTypeNameContext](
				ctx.typeName,
				_.packageOrTypeName,
				_.packageOrTypeName,
				_.Identifier.getText,
				_.Identifier.getText
			)

			if (! (Inheritor.getDirectiveLocals(ctx, currentDirective).map(_.arrayLessName) contains id)) {
				try {
					val (realCtxName, v) = findVariable(id, locals, params, directiveClass)
					val firstToken = getContextTokens(ctx).head
					rewriter.replace(firstToken, s"$realCtxName.${v.fullName}${extension(v)}")

					captured += v
				} catch {
					case e: NoSuchElementException => ; // local (ok)
				}
			}
		}

		super.visitMethodInvocation(ctx)
	}

	override def visitPrimary(ctx: Java8Parser.PrimaryContext) = {
		// Handle primary if no-array expression occures

		if (ctx.primaryNoNewArray_lfno_primary == null) {
			/* Users should never provide input containing this obscure input. If they do so, the bahavior is unspecified */
		}
		else {
			val first = ctx.primaryNoNewArray_lfno_primary
			val seconds: List[Java8Parser.PrimaryNoNewArray_lf_primaryContext] =
				if (ctx.primaryNoNewArray_lf_primary != null) ctx.primaryNoNewArray_lf_primary.asScala.toList
				else List[Java8Parser.PrimaryNoNewArray_lf_primaryContext]()

			handlePrimary(ctx, first, seconds)
		}
		super.visitPrimary(ctx)
	}

	private def handlePrimary(ctx: Java8Parser.PrimaryContext, first: Java8Parser.PrimaryNoNewArray_lfno_primaryContext, seconds: List[Java8Parser.PrimaryNoNewArray_lf_primaryContext]) = {
		// Translate this primary

		// is primary expression of method invocation
		val isMI = first.parent.parent.isInstanceOf[Java8Parser.MethodInvocationContext]
		try {

			// primary starts with 'this'
			if (first.getText == "this") {

				// only for first-class expressions
				if (clStack.head == directiveClass) {

					// 'this' as a standalone
					if (seconds.size == 0) {
						if (isMI) {
							rewriter.delete(first.start, first.stop)
						} else {
							rewriter.replace(first.start, first.stop, s"$contextName.THAT")
							capturedThis = true
						}
					} else {
						// 'this' in a tandem
						val next = seconds.head
						if (next.fieldAccess_lf_primary != null) {
							val id = next.fieldAccess_lf_primary.Identifier.getText
							try {
								// try to rewrite var name (if captured)
								val v = OMPVariable.findField(id, directiveClass)
								rewriter.replace(first.start, first.stop, contextName)
								rewriter.replace(next.start, next.stop, s".${v.fullName}${extension(v)}")

								captured += v   // ??
							} catch {
								case e: NoSuchElementException => ; // local (ok)
							}
						} else if (next.methodInvocation_lf_primary != null) {
							// getting rid of 'this.'
							rewriter.delete(first.start, first.stop)

							val dot = getContextTokens(ctx)(1)
							rewriter.delete(dot)
						}

						capturedThis = true
					}
				}


			} else {
				if (first.fieldAccess_lfno_primary != null) {

					val id = getLeftName[Java8Parser.TypeNameContext, Java8Parser.PackageOrTypeNameContext](
					first.fieldAccess_lfno_primary.typeName,
					_.packageOrTypeName,
					_.packageOrTypeName,
					_.Identifier.getText,
					_.Identifier.getText)

					if (! (Inheritor.getDirectiveLocals(ctx, currentDirective).map(_.arrayLessName) contains id)) {
						try {
							val (realCtxName, v) = findVariable(id, locals, params, directiveClass)
							rewriter.replace(first.start, first.stop, s"$realCtxName.${v.fullName}${extension(v)}")
						} catch {
							case e: NoSuchElementException => ; // local (ok)
						}
					}

				} else if (first.methodInvocation_lfno_primary != null) {
					val mip = first.methodInvocation_lfno_primary
					if (mip.methodName != null) {
						// simple method call
					} else if (mip.typeName != null) {

						val id = getLeftName[Java8Parser.TypeNameContext, Java8Parser.PackageOrTypeNameContext](
							first.methodInvocation_lfno_primary.typeName,
							_.packageOrTypeName,
							_.packageOrTypeName,
							_.Identifier.getText,
							_.Identifier.getText)

						if (! (Inheritor.getDirectiveLocals(ctx, currentDirective).map(_.arrayLessName) contains id)) {
							try {
								val (realCtxName, v) = findVariable(id, locals, params, directiveClass)
								val firstToken = getContextTokens(first).head
								rewriter.replace(firstToken, s"$realCtxName.${v.fullName}${extension(v)}")
								captured += v

							} catch {
								case e: NoSuchElementException => ; // local (ok)
							}
						}
					} else {
						// This should never happen. The behavior is unspecified
					}
				}
			}
		} catch {
			// this should never happen, but just in case we rethrow
			case e: Exception => throw new ParseException("Unepected exception in handlePrimary", e)
		}

	}
}
