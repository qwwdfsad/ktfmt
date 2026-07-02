/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.ktfmt.format

import com.facebook.ktfmt.format.layout.BlankLineWanted
import com.facebook.ktfmt.format.layout.BreakTag
import com.facebook.ktfmt.format.layout.FillMode
import com.facebook.ktfmt.format.layout.Indent
import com.facebook.ktfmt.format.layout.Indent.Const.Companion.ZERO
import com.facebook.ktfmt.format.layout.LayoutSink
import com.facebook.ktfmt.format.layout.RealOrImaginary
import fleet.org.jetbrains.kotlin.kmp.lexer.KtTokens
import fleet.org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import java.util.Optional

/**
 * The PSI-free formatting visitor: ktfmt's `prettyPrint` engine driver.
 *
 * Walks the new multiplatform parser's lightweight [KmpNode] tree (see [KmpAst]) and emits ops
 * into the google-java-format [OpsBuilder] engine, so Kotlin is formatted without any IntelliJ PSI.
 * Anything without a dedicated handler falls through to [visitGeneric], which recurses and emits leaf
 * tokens in order so the formatter never crashes.
 */
internal class KmpAstVisitor(
    private val options: FormattingOptions,
    private val builder: LayoutSink,
    private val code: String,
) {
  // optofmt §9: argument-less annotations that read as modifiers stay inline with the modifier run;
  // every other annotation goes on its own line above the declaration. This is the inline set.
  private val INLINE_MODIFIER_ANNOTATIONS =
      setOf(
          "PublishedApi",
          "JvmStatic",
          "JvmField",
          "JvmSynthetic",
          "JvmInline",
          "JvmOverloads",
          "JvmDefault",
          "JvmDefaultWithoutCompatibility",
          "Volatile",
          "Transient",
          "Synchronized",
          "Strictfp",
      )

  private val expressionBreakIndent: Indent.Const = Indent.Const.make(options.continuationIndent, 1)
  private val expressionBreakNegativeIndent: Indent.Const =
      Indent.Const.make(-options.continuationIndent, 1)
  private val blockIndent: Indent.Const = Indent.Const.make(options.blockIndent, 1)
  private val blockPlusExpressionBreakIndent: Indent.Const =
      Indent.Const.make(options.blockIndent + options.continuationIndent, 1)
  private val doubleExpressionBreakIndent: Indent.Const =
      Indent.Const.make(options.continuationIndent, 2)

  private fun genSym(): BreakTag = BreakTag()

  fun visitFile(file: KmpNode) {
    builder.markForPartialFormat()
    // Parsed in script mode (lenient), so top-level declarations/statements are wrapped in a
    // SCRIPT > BLOCK. Flatten that so they format like ordinary file top-level elements.
    val topLevel = mutableListOf<KmpNode>()
    for (child in file.children()) {
      if (child.type == KtNodeTypes.SCRIPT) {
        child.children().firstOrNull { it.type == KtNodeTypes.BLOCK }?.children()?.forEach {
          topLevel.add(it)
        }
      } else {
        topLevel.add(child)
      }
    }
    var isFirst = true
    var prev: KmpNode? = null
    for (child in topLevel) {
      if (child.text.isBlank() || child.type in KtTokens.COMMENTS) continue
      builder.blankLineWanted(
          when {
            isFirst -> BlankLineWanted.NO
            // optofmt §11: a run of consecutive same-kind *one-line* declarations stays tight (the
            // author's spacing is preserved, collapsing multiple blanks to one); a blank line is
            // forced between declarations of different kinds, and between multi-line declarations
            // even of the same kind. ktfmt forces a blank between every pair.
            options.optofmt &&
                child.type == prev?.type &&
                isOneLineDeclaration(child) &&
                prev?.let { isOneLineDeclaration(it) } == true ->
                BlankLineWanted.PRESERVE
            // Adjacent top-level properties preserve the author's spacing (no forced blank line).
            child.type == KtNodeTypes.PROPERTY && prev?.type == KtNodeTypes.PROPERTY ->
                BlankLineWanted.PRESERVE
            else -> BlankLineWanted.YES
          })
      // Flush leading comments after the blank-line request (so the blank precedes the comment,
      // which hugs its declaration) but before the declaration's tokens. Sync to the first real
      // token, not the node start, since the node range can include leading comment trivia.
      builder.sync(firstRealTokenOffset(child))
      visit(child)
      // Declarations self-terminate with a break; bare statements/expressions need one here.
      builder.guessToken(";")
      builder.forcedBreak()
      isFirst = false
      prev = child
    }
    builder.markForPartialFormat()
  }

  private fun visit(node: KmpNode?) {
    if (node == null) return
    when (node.type) {
      KtNodeTypes.PACKAGE_DIRECTIVE -> visitPackageDirective(node)
      KtNodeTypes.IMPORT_LIST -> visitImportList(node)
      KtNodeTypes.IMPORT_DIRECTIVE -> visitImportDirective(node)
      KtNodeTypes.SCRIPT -> visitScript(node)
      KtNodeTypes.PROPERTY -> visitProperty(node)
      KtNodeTypes.FUN -> visitNamedFunction(node)
      KtNodeTypes.CLASS,
      KtNodeTypes.OBJECT_DECLARATION -> visitClassOrObject(node)
      KtNodeTypes.CLASS_BODY -> visitClassBody(node)
      KtNodeTypes.PRIMARY_CONSTRUCTOR -> visitPrimaryConstructor(node)
      KtNodeTypes.SUPER_TYPE_LIST -> visitSuperTypeList(node)
      KtNodeTypes.BLOCK -> visitBlockExpression(node)
      KtNodeTypes.RETURN -> visitReturn(node)
      KtNodeTypes.IF -> visitIfExpression(node)
      KtNodeTypes.WHEN -> visitWhenExpression(node)
      KtNodeTypes.IS_EXPRESSION -> visitTypeOperatorExpression(node)
      KtNodeTypes.BINARY_WITH_TYPE -> visitTypeOperatorExpression(node)
      KtNodeTypes.COLLECTION_LITERAL_EXPRESSION -> visitCollectionLiteral(node)
      KtNodeTypes.WHEN_CONDITION_IS_PATTERN -> visitWhenConditionIsPattern(node)
      KtNodeTypes.WHEN_CONDITION_IN_RANGE -> visitWhenConditionInRange(node)
      KtNodeTypes.TYPE_CONSTRAINT_LIST -> visitTypeConstraintList(node)
      KtNodeTypes.TYPE_CONSTRAINT -> visitTypeConstraint(node)
      KtNodeTypes.FUNCTION_TYPE -> visitFunctionType(node)
      KtNodeTypes.LABELED_EXPRESSION -> visitLabeledExpression(node)
      KtNodeTypes.ANNOTATED_EXPRESSION -> visitAnnotatedExpression(node)
      KtNodeTypes.FILE_ANNOTATION_LIST -> visitFileAnnotationList(node)
      KtNodeTypes.CONTEXT_RECEIVER_LIST -> visitContextReceiverList(node)
      KtNodeTypes.ANNOTATION -> visitAnnotation(node)
      KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY -> visitDestructuringEntry(node)
      KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY -> visitDelegatedSuperTypeEntry(node)
      KtNodeTypes.SUPER_TYPE_CALL_ENTRY -> visitSuperTypeCallEntry(node)
      KtNodeTypes.INTERSECTION_TYPE -> visitIntersectionType(node)
      KtNodeTypes.OBJECT_LITERAL ->
          visit(node.child(KtNodeTypes.OBJECT_DECLARATION))
      KtNodeTypes.FOR -> visitForExpression(node)
      KtNodeTypes.WHILE -> visitWhileExpression(node)
      KtNodeTypes.DO_WHILE -> visitDoWhileExpression(node)
      KtNodeTypes.TRY -> visitTryExpression(node)
      KtNodeTypes.PREFIX_EXPRESSION -> visitPrefixExpression(node)
      KtNodeTypes.POSTFIX_EXPRESSION -> visitPostfixExpression(node)
      KtNodeTypes.PARENTHESIZED -> visitParenthesized(node)
      KtNodeTypes.ANNOTATION_ENTRY -> visitAnnotationEntry(node)
      KtNodeTypes.TYPE_PARAMETER_LIST -> visitTypeParameterList(node)
      KtNodeTypes.TYPE_PARAMETER -> visitTypeParameter(node)
      KtNodeTypes.VALUE_PARAMETER -> visitParameter(node)
      KtNodeTypes.TYPE_ARGUMENT_LIST -> visitTypeArgumentList(node)
      KtNodeTypes.USER_TYPE -> visitUserType(node)
      KtNodeTypes.TYPE_PROJECTION -> visitTypeProjection(node)
      KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION -> visitCallableReference(node)
      KtNodeTypes.DESTRUCTURING_DECLARATION -> visitDestructuringDeclaration(node)
      KtNodeTypes.CLASS_INITIALIZER -> visitClassInitializer(node)
      KtNodeTypes.TYPEALIAS -> visitTypeAlias(node)
      KtNodeTypes.SECONDARY_CONSTRUCTOR -> visitSecondaryConstructor(node)
      KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL -> visitConstructorDelegationCall(node)
      KtNodeTypes.THROW -> {
        sync(node)
        emit("throw")
        builder.space()
        visit(node.meaningfulChildren().lastOrNull())
      }
      KtNodeTypes.BINARY_EXPRESSION -> visitBinaryExpression(node)
      KtNodeTypes.CALL_EXPRESSION -> visitCallExpression(node)
      KtNodeTypes.VALUE_ARGUMENT -> visitArgument(node)
      KtNodeTypes.LAMBDA_EXPRESSION -> visitLambdaExpressionInternal(node, brokeBeforeBrace = null)
      KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
      KtNodeTypes.SAFE_ACCESS_EXPRESSION -> visitQualifiedExpression(node)
      KtNodeTypes.ARRAY_ACCESS_EXPRESSION -> visitArrayAccessExpression(node)
      KtNodeTypes.VALUE_PARAMETER_LIST -> visitParameterList(node)
      KtNodeTypes.MODIFIER_LIST -> visitModifierList(node)
      KtNodeTypes.TYPE_REFERENCE -> visitGeneric(node)
      KtNodeTypes.STRING_TEMPLATE -> {
        sync(node)
        emit(WhitespaceTombstones.replaceTrailingWhitespaceWithTombstone(node.text.toString()))
      }
      KtNodeTypes.REFERENCE_EXPRESSION -> {
        sync(node)
        emit(node.text.toString())
      }
      else -> visitGeneric(node)
    }
  }

  /** Fallback: emit leaf tokens in order so the engine stays in sync; recurse composites. */
  private fun visitGeneric(node: KmpNode) {
    if (node.firstChild() == null) {
      if (!node.isTrivia() && node.text.isNotEmpty()) {
        sync(node)
        emit(node.text.toString())
      }
      return
    }
    sync(node)
    for (child in node.children()) {
      if (child.type == KtNodeTypes.STRING_TEMPLATE) emit(child.text.toString()) else visit(child)
    }
  }

  // ---- Declarations ----------------------------------------------------------------------------

  private fun visitProperty(node: KmpNode) {
    sync(node)
    block(ZERO) {
      visit(node.child(KtNodeTypes.MODIFIER_LIST))
      block(ZERO) {
        block(ZERO) {
          val valOrVar = node.keywordText("val") ?: node.keywordText("var")
          if (valOrVar != null) {
            emit(valOrVar)
            builder.space()
          }
          node.child(KtNodeTypes.TYPE_PARAMETER_LIST)?.let {
            visit(it)
            builder.space()
          }
          val receiver = node.functionReceiverType()
          if (receiver != null) {
            visit(receiver)
            emit(".")
          }
          node.identifierLeaf()?.let { emit(it.text.toString()) }
        }
        // The declared type is the TYPE_REFERENCE after the name (not the extension receiver).
        val type = node.propertyType()
        block(expressionBreakIndent, isEnabled = node.identifierLeaf() != null) {
          if (type != null) {
            emit(":")
            builder.breakOp(FillMode.UNIFIED, " ", ZERO)
            visit(type)
          }
        }
        val delegate = node.child(KtNodeTypes.PROPERTY_DELEGATE)
        val initializer = node.initializer()
        if (delegate != null) {
          val delegateExpr = delegate.meaningfulChildren().lastOrNull()
          builder.space()
          emit("by")
          if (isLambdaOrScopingFunction(delegateExpr)) {
            builder.space()
            visit(delegateExpr)
          } else {
            builder.breakOp(FillMode.UNIFIED, " ", expressionBreakIndent)
            block(expressionBreakIndent) {
              fenceComments()
              visit(delegateExpr)
            }
          }
        } else if (initializer != null) {
          builder.space()
          emit("=")
          if (isLambdaOrScopingFunction(initializer)) {
            visitLambdaOrScopingFunction(initializer)
          } else if (options.optofmt) {
            emitIntroducerRhs(initializer) { visit(initializer) }
          } else {
            builder.breakOp(FillMode.UNIFIED, " ", expressionBreakIndent)
            block(expressionBreakIndent) {
              fenceComments()
              visit(initializer)
            }
          }
        }
      }
      visit(node.child(KtNodeTypes.TYPE_CONSTRAINT_LIST)) // `where T : X`
      // Backing field and accessors, in source order.
      val components =
          node.meaningfulChildren()
              .filter {
                it.type == KtNodeTypes.PROPERTY_ACCESSOR || it.type == KtNodeTypes.BACKING_FIELD
              }
              .sortedBy { it.startOffset }
      if (components.isNotEmpty()) {
        // optofmt §1: a single trivial expression-bodied accessor (`val x: T get() = …`) stays on
        // the declaration line when it fits, instead of being force-broken onto its own line. We
        // emit a soft break so the engine keeps it inline if it fits and wraps it otherwise.
        val keepAccessorInline =
            options.optofmt &&
                components.size == 1 &&
                components[0].type == KtNodeTypes.PROPERTY_ACCESSOR &&
                components[0].expressionBody() != null &&
                components[0].child(KtNodeTypes.BLOCK) == null
        block(blockIndent) {
          for (component in components) {
            if (keepAccessorInline) builder.breakOp(FillMode.UNIFIED, " ", ZERO)
            else builder.forcedBreak()
            builder.guessToken(";")
            block(ZERO) {
              if (component.type == KtNodeTypes.BACKING_FIELD) visitBackingField(component)
              else visitPropertyAccessor(component)
            }
          }
        }
      }
    }
    builder.guessToken(";")
    if (node.parent()?.type != KtNodeTypes.WHEN) builder.forcedBreak()
  }

  private fun visitBackingField(node: KmpNode) {
    sync(node)
    block(ZERO) {
      visit(node.child(KtNodeTypes.MODIFIER_LIST))
      emit("field")
      val type = node.child(KtNodeTypes.TYPE_REFERENCE)
      if (type != null) {
        emit(":")
        builder.space()
        visit(type)
      }
      val initializer = node.initializer()
      if (initializer != null) {
        builder.space()
        emit("=")
        builder.breakOp(FillMode.UNIFIED, " ", expressionBreakIndent)
        block(expressionBreakIndent) { visit(initializer) }
      }
    }
  }

  private fun visitNamedFunction(node: KmpNode) {
    sync(node)
    block(ZERO) {
      visitFunctionLikeExpression(
          modifierList = node.child(KtNodeTypes.MODIFIER_LIST),
          keyword = "fun",
          typeParameters = node.child(KtNodeTypes.TYPE_PARAMETER_LIST),
          receiverTypeReference = node.functionReceiverType(),
          name = node.identifierLeaf()?.text?.toString(),
          parameterList = node.child(KtNodeTypes.VALUE_PARAMETER_LIST),
          bodyExpression = node.child(KtNodeTypes.BLOCK) ?: node.expressionBody(),
          typeOrDelegationCall = node.returnTypeReference(),
          typeConstraintList = node.child(KtNodeTypes.TYPE_CONSTRAINT_LIST),
      )
    }
  }

  private fun visitPropertyAccessor(node: KmpNode) {
    sync(node)
    block(ZERO) {
      visitFunctionLikeExpression(
          modifierList = node.child(KtNodeTypes.MODIFIER_LIST),
          keyword = node.keywordText("get") ?: node.keywordText("set"),
          typeParameters = null,
          receiverTypeReference = null,
          name = null,
          parameterList = node.child(KtNodeTypes.VALUE_PARAMETER_LIST),
          bodyExpression = node.child(KtNodeTypes.BLOCK) ?: node.expressionBody(),
          typeOrDelegationCall = node.returnTypeReference(),
      )
    }
  }

  private fun visitFunctionLikeExpression(
      modifierList: KmpNode?,
      keyword: String?,
      typeParameters: KmpNode?,
      receiverTypeReference: KmpNode?,
      name: String?,
      parameterList: KmpNode?,
      bodyExpression: KmpNode?,
      typeOrDelegationCall: KmpNode?,
      typeConstraintList: KmpNode? = null,
  ) {
    fun emitTypeOrDelegationCall(emitBody: () -> Unit) {
      if (typeOrDelegationCall != null) {
        block(ZERO) {
          if (typeOrDelegationCall.type == KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL) builder.space()
          emit(":")
          emitBody()
        }
      }
    }

    val forceTrailingBreak = name != null
    block(ZERO, isEnabled = forceTrailingBreak) {
      if (modifierList != null) visitModifierList(modifierList)
      if (keyword != null) emit(keyword)
      if (typeParameters != null) {
        builder.space()
        block(ZERO) { visit(typeParameters) }
      }
      if (name != null || receiverTypeReference != null) builder.space()
      block(ZERO) {
        if (receiverTypeReference != null) {
          visit(receiverTypeReference)
          builder.breakOp(FillMode.INDEPENDENT, "", expressionBreakIndent)
          emit(".")
        }
        if (name != null) emit(name)
      }

      val params = parameterList?.meaningfulChildren()?.filter { it.type == KtNodeTypes.VALUE_PARAMETER } ?: emptyList()
      val emptyParens =
          parameterList != null &&
              params.isEmpty() &&
              parameterList.children().none { it.type in KtTokens.COMMENTS }
      if (emptyParens) {
        block(ZERO) {
          emit("(")
          emit(")")
          emitTypeOrDelegationCall {
            builder.breakOp(FillMode.INDEPENDENT, " ", expressionBreakIndent)
            block(expressionBreakIndent) { visit(typeOrDelegationCall) }
          }
        }
      } else {
        block(expressionBreakIndent) {
          if (parameterList != null) {
            val lastParam = params.lastOrNull()
            val hasTrailingComma =
                lastParam != null &&
                    parameterList.meaningfulChildren().any {
                      it.type == KtTokens.COMMA && it.startOffset > lastParam.startOffset
                    }
            visitEachCommaSeparated(
                list = params,
                hasTrailingComma = hasTrailingComma,
                prefix = "(",
                postfix = ")",
                wrapInBlock = false,
                breakBeforePostfix = true,
            )
          }
          emitTypeOrDelegationCall {
            builder.space()
            block(expressionBreakNegativeIndent) { visit(typeOrDelegationCall) }
          }
        }
      }
      if (typeConstraintList != null) visit(typeConstraintList)
      if (bodyExpression?.type == KtNodeTypes.BLOCK) {
        builder.space()
        visitBlockExpression(bodyExpression)
      } else if (bodyExpression != null) {
        builder.space()
        block(ZERO) {
          emit("=")
          if (isLambdaOrScopingFunction(bodyExpression)) {
            visitLambdaOrScopingFunction(bodyExpression)
          } else if (options.optofmt) {
            // optofmt §1/§3/§7: an expression body's `=` is an introducer, exactly like a property
            // initializer's `=` (see visitProperty).
            emitIntroducerRhs(bodyExpression) { visit(bodyExpression) }
          } else {
            block(expressionBreakIndent) {
              builder.breakOp(FillMode.INDEPENDENT, " ", ZERO)
              block(ZERO) { visit(bodyExpression) }
            }
          }
        }
      }
      builder.guessToken(";")
    }
    if (forceTrailingBreak) builder.forcedBreak()
  }

  private fun isLambdaOrScopingFunction(node: KmpNode?): Boolean {
    var carry = node ?: return false
    // A leading comment causes weird indentation; don't treat as a scoping function.
    var prev = carry.prevSibling()
    while (prev != null && prev.type in KtTokens.WHITESPACES) prev = prev.prevSibling()
    if (prev != null && prev.type in KtTokens.COMMENTS) return false
    if ((carry.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
        carry.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) &&
        carry.qualifiedReceiver()?.type == KtNodeTypes.REFERENCE_EXPRESSION) {
      carry = carry.qualifiedSelector() ?: return false
    }
    if (carry.type == KtNodeTypes.CALL_EXPRESSION) {
      val lambdaArg = carry.meaningfulChildren().firstOrNull { it.type == KtNodeTypes.LAMBDA_ARGUMENT }
      if (carry.child(KtNodeTypes.VALUE_ARGUMENT_LIST) == null &&
          lambdaArg != null &&
          carry.child(KtNodeTypes.TYPE_ARGUMENT_LIST) == null) {
        carry = lambdaArg.argumentExpression() ?: return false
      } else {
        return false
      }
    }
    if (carry.type == KtNodeTypes.LABELED_EXPRESSION) {
      carry = carry.meaningfulChildren().lastOrNull { it.type != KtNodeTypes.LABEL_QUALIFIER } ?: return false
    }
    return carry.type == KtNodeTypes.LAMBDA_EXPRESSION
  }

  private fun visitLambdaOrScopingFunction(node: KmpNode?) {
    val breakToExpr = genSym()
    builder.breakOp(FillMode.INDEPENDENT, " ", expressionBreakIndent, Optional.of(breakToExpr))
    var carry = node ?: return
    if ((carry.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
        carry.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) &&
        carry.qualifiedReceiver()?.type == KtNodeTypes.REFERENCE_EXPRESSION) {
      visit(carry.qualifiedReceiver())
      emit(carry.operationSignValue())
      carry = carry.qualifiedSelector() ?: return
    }
    if (carry.type == KtNodeTypes.CALL_EXPRESSION) {
      visit(carry.meaningfulChildren().firstOrNull())
      builder.space()
      carry =
          carry.meaningfulChildren().firstOrNull { it.type == KtNodeTypes.LAMBDA_ARGUMENT }
              ?.argumentExpression() ?: return
    }
    if (carry.type == KtNodeTypes.LABELED_EXPRESSION) {
      visit(carry.child(KtNodeTypes.LABEL_QUALIFIER))
      carry = carry.meaningfulChildren().lastOrNull { it.type != KtNodeTypes.LABEL_QUALIFIER } ?: return
    }
    if (carry.type == KtNodeTypes.LAMBDA_EXPRESSION) {
      visitLambdaExpressionInternal(carry, brokeBeforeBrace = breakToExpr)
    }
  }

  private fun visitModifierList(list: KmpNode) {
    sync(list)
    // §9 scopes the "annotation-with-args on its own line" treatment to annotations *directly above
    // a declaration*. This same modifier-list walk also runs for parameter modifier lists (a value
    // parameter, a `catch` parameter, a lambda parameter), where §9's own example keeps the
    // annotation inline (`@PublishedApi internal val flow: …`). So only force the own-line break for
    // declaration modifier lists, not parameter ones.
    val isParameterModifiers = list.parent()?.type == KtNodeTypes.VALUE_PARAMETER
    // optofmt §9: an annotation goes on its OWN line if it carries arguments (`@JvmName("other")`,
    // `@Suppress("…")`, `@Deprecated(...)`) or is a non-modifier-like annotation (`@Test`); only
    // argument-less *modifier-like* annotations (`@PublishedApi`, `@JvmStatic`, …) stay inline.
    fun annotationNeedsOwnLine(child: KmpNode) =
        child.type == KtNodeTypes.ANNOTATION_ENTRY &&
            (child.child(KtNodeTypes.VALUE_ARGUMENT_LIST) != null ||
                annotationShortName(child) !in INLINE_MODIFIER_ANNOTATIONS)
    // Once ANY annotation on the declaration needs its own line, put EVERY annotation on its own line
    // — never mix own-line and inline annotations on one declaration (that leaves an argument-carrying
    // annotation glued to a preceding `@JvmStatic`, contradicting §9). A lone modifier-like annotation
    // with no such sibling still stays inline. ktfmt keeps everything inline. (Not for parameter
    // modifier lists — §9 keeps a parameter's annotations inline, e.g. `@PublishedApi internal val`.)
    val breakEveryAnnotation =
        options.optofmt &&
            !isParameterModifiers &&
            list.meaningfulChildren().any { annotationNeedsOwnLine(it) }
    var onlyAnnotationsSoFar = true
    for (child in list.meaningfulChildren()) {
      if (child.type == KtNodeTypes.CONTEXT_RECEIVER_LIST) {
        visitContextReceiverList(child) // emits its own forcedBreak
        continue
      }
      if (child.firstChild() == null) {
        // A modifier keyword token (private, const, override, ...).
        onlyAnnotationsSoFar = false
        emit(child.text.toString())
      } else {
        // An annotation entry (or context list); not fully ported yet.
        visit(child)
      }
      val ownLineAnnotation = breakEveryAnnotation && child.type == KtNodeTypes.ANNOTATION_ENTRY
      if (ownLineAnnotation) {
        builder.forcedBreak()
      } else if (onlyAnnotationsSoFar && !options.optofmt) {
        builder.breakOp(FillMode.UNIFIED, " ", ZERO)
      } else {
        // optofmt §9: the modifier run (and argument-less annotations) stays on the declaration
        // line; only the parameter list wraps when the header is too long. A non-breaking space
        // prevents the modifiers from splitting apart. ktfmt drops each onto its own line.
        builder.space()
      }
    }
  }

  /**
   * The short (unqualified) name of an annotation entry — `@kotlin.jvm.JvmStatic` -> `JvmStatic`,
   * `@field:Volatile` -> `Volatile`, `@JvmName("x")` -> `JvmName`. Null if it has no callee.
   */
  /**
   * True if [node] is a member-access call chain (`a.b().c()`). RULES §7 governs how such a chain
   * wraps — the receiver through its first call stays together, then each `.call` breaks to its own
   * line — so the `=` introducer before it must NOT be §3-attachment-penalized: breaking after `=`
   * to keep the receiver-through-first-call intact has to stay competitive. Otherwise the penalty
   * would attach `= this.foo(` and tear the first call's arguments to make it fit, splitting the §7
   * unit (see AbstractLincheckTest.commonConfiguration).
   */
  private fun isCallChain(node: KmpNode?): Boolean =
      node?.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
          node?.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION

  /**
   * optofmt §1/§3: emit an introducer's right-hand side ([rhsExpr], built by [buildRhs]) as two
   * competing candidate layouts and let the optimizer keep the lower-§1-cost one. Used for every
   * `= rhs` / `name = rhs` introducer (property init, assignment, expression body, named argument):
   *   - attached — `= rhs` on the introducer's line, the RHS wrapping its own contents (call args,
   *     `+` concat, chain) at one indent (§7: keep the receiver-through-first-call on the line);
   *   - broken — break after the introducer, the RHS on the next line at one indent.
   *
   * The RHS subtree is built once (its source-token cursor advances once) and shared.
   *
   * The broken candidate wraps a non-chain RHS in a `block(expressionBreakIndent)` and breaks to ZERO
   * *inside* it, rather than breaking by [expressionBreakIndent] with no enclosing block. In the
   * native engine a break only moves the open column; a following sibling level still computes its
   * indent from the *enclosing* level's base — so without the wrapper a call's argument list (or a
   * concat's operands) would indent from the introducer's base and land one level too shallow (the
   * call opener and its args on the same column, closer dedented past the opener). A call-chain RHS
   * already supplies its own single-indent block (see [emitQualifiedExpression]) and must NOT be
   * double-wrapped, and its break stays unpenalized so §7 keeps the receiver-through-first-call
   * intact rather than tearing it to attach.
   */
  private fun emitIntroducerRhs(rhsExpr: KmpNode?, buildRhs: () -> Unit) {
    val sink = builder as com.facebook.ktfmt.format.layout.NativeSink
    val rhs = sink.capture(buildRhs)
    val attached = sink.capture { sink.space(); sink.appendSubtree(rhs) }
    val broken =
        sink.capture {
          if (isCallChain(rhsExpr)) {
            sink.forcedBreak(expressionBreakIndent)
            sink.appendSubtree(rhs)
          } else {
            block(expressionBreakIndent) {
              sink.forcedIntroducerBreak(ZERO)
              sink.appendSubtree(rhs)
            }
          }
        }
    sink.emitAlt(listOf(attached, broken))
  }

  private fun annotationShortName(entry: KmpNode): String? {
    val callee =
        entry.meaningfulChildren().firstOrNull { it.type == KtNodeTypes.CONSTRUCTOR_CALLEE }
            ?: return null
    return callee.text.toString().substringBefore('<').substringAfterLast('.').trim()
  }

  private fun visitParameterList(node: KmpNode) {
    val kids = node.meaningfulChildren()
    val params = kids.filter { it.type == KtNodeTypes.VALUE_PARAMETER }
    val lastParam = params.lastOrNull()
    val hasTrailingComma =
        lastParam != null &&
            kids.any { it.type == KtTokens.COMMA && it.startOffset > lastParam.startOffset }
    if (params.isEmpty()) {
      emit("(")
      emit(")")
      return
    }
    visitEachCommaSeparated(
        list = params,
        hasTrailingComma = hasTrailingComma,
        prefix = "(",
        postfix = ")",
        wrapInBlock = false,
        breakBeforePostfix = true,
    )
  }

  // ---- Statements / blocks ---------------------------------------------------------------------

  private fun emitBracedBlock(node: KmpNode, emitChildren: (List<KmpNode>) -> Unit) {
    sync(node)
    builder.token("{", RealOrImaginary.REAL, blockIndent, Optional.of(blockIndent))
    val children =
        node.meaningfulChildren().filter {
          it.type != KtTokens.LBRACE &&
              it.type != KtTokens.RBRACE &&
              it.type != KtTokens.SEMICOLON // handled via guessToken(";")
        }
    // A comment-only block has no meaningful children but still needs a body so the comment lands
    // on its own indented line. gjf handles this via the brace token's comment params; the native
    // engine relies on the body level + an explicit sync to flush the comment inside it.
    val rbrace = node.children().lastOrNull { it.type == KtTokens.RBRACE }
    val commentOnlyBody =
        options.optofmt &&
            children.isEmpty() &&
            node.children().any { it.type in KtTokens.COMMENTS }
    if (children.isNotEmpty() || commentOnlyBody) {
      block(blockIndent) {
        builder.forcedBreak()
        builder.blankLineWanted(BlankLineWanted.PRESERVE)
        emitChildren(children)
        // Flush any comment sitting between the last child and `}` INSIDE the body level, so it lands
        // at the body indent. Without this the comment stays pending until the `}` token is emitted —
        // by then the body level has closed, so it renders one level too shallow (at the brace indent).
        // §8: optofmt owns comment indentation, so reindent it to the body level regardless of source.
        if (options.optofmt && rbrace != null) builder.sync(rbrace.startOffset)
      }
      builder.forcedBreak()
      builder.blankLineWanted(BlankLineWanted.NO)
    }
    builder.token("}", RealOrImaginary.REAL, blockIndent, Optional.empty())
  }

  private fun visitBlockExpression(node: KmpNode) {
    emitBracedBlock(node) { statements ->
      var first = true
      builder.guessToken(";")
      for (statement in statements) {
        builder.forcedBreak()
        if (!first) builder.blankLineWanted(BlankLineWanted.PRESERVE)
        first = false
        block(ZERO) { visit(statement) }
        builder.guessToken(";")
      }
    }
  }

  private fun visitClassOrObject(node: KmpNode) {
    sync(node)
    val name = node.identifierLeaf()
    val body = node.child(KtNodeTypes.CLASS_BODY)
    block(ZERO) {
      visit(node.child(KtNodeTypes.MODIFIER_LIST))
      val keyword =
          node.meaningfulChildren().firstOrNull {
            it.type != KtNodeTypes.MODIFIER_LIST && it.firstChild() == null
          }
      if (keyword != null) emit(keyword.text.toString())
      if (name != null) {
        builder.space()
        emit(name.text.toString())
        visit(node.child(KtNodeTypes.TYPE_PARAMETER_LIST))
      }
      visit(node.child(KtNodeTypes.PRIMARY_CONSTRUCTOR))
      val superTypes = node.child(KtNodeTypes.SUPER_TYPE_LIST)
      if (superTypes != null) {
        builder.space()
        block(ZERO) {
          emit(":")
          if (options.optofmt) {
            // optofmt §1/§3: offer BOTH legal arrangements of `: <supertypes>` and let the optimizer
            // keep whichever has the lower §1 cost:
            //   attached — `: DumpFileCommand(` on the header line, only the supertype's own argument
            //              list wrapping (RULES §3 supertype attachment; wins when the header fits);
            //   broken   — break after `:`, the supertype whole on the next line at one indent (wins
            //              when attaching would overflow the header, e.g. a long primary constructor).
            // Attaching is not applied unconditionally: §1 (minimize worst overflow) must be able to
            // override §3 when the attached header would overflow. The subtree is built once, shared.
            val sink = builder as com.facebook.ktfmt.format.layout.NativeSink
            val supers = sink.capture { visit(superTypes) }
            val attached = sink.capture { sink.space(); sink.appendSubtree(supers) }
            val broken =
                sink.capture {
                  sink.forcedIntroducerBreak(expressionBreakIndent)
                  sink.appendSubtree(supers)
                }
            sink.emitAlt(listOf(attached, broken))
          } else {
            builder.breakOp(FillMode.UNIFIED, " ", expressionBreakIndent)
            visit(superTypes)
          }
        }
      }
      val typeConstraintList = node.child(KtNodeTypes.TYPE_CONSTRAINT_LIST)
      if (typeConstraintList != null) {
        // `where` after a delegated supertype (`: Bar by bar`) must break onto its own line,
        // otherwise `by bar where ...` is ambiguous on re-parse.
        val lastSuperType =
            superTypes
                ?.meaningfulChildren()
                ?.lastOrNull {
                  it.type == KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY ||
                      it.type == KtNodeTypes.SUPER_TYPE_CALL_ENTRY ||
                      it.type == KtNodeTypes.SUPER_TYPE_ENTRY
                }
        if (lastSuperType?.type == KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY) {
          builder.forcedBreak(expressionBreakIndent)
        }
        visit(typeConstraintList)
        builder.space()
      } else if (body != null) {
        builder.space()
      }
      visit(body)
    }
    if (name != null) builder.forcedBreak()
  }

  private fun visitPrimaryConstructor(node: KmpNode) {
    sync(node)
    block(ZERO) {
      val hasConstructorKeyword = node.keywordText("constructor") != null
      // optofmt §9: keep the explicit `constructor` (and its modifiers) attached to the class
      // header rather than letting it wrap to its own line.
      if (hasConstructorKeyword) {
        if (options.optofmt) builder.space()
        else builder.breakOp(FillMode.UNIFIED, " ", ZERO)
      }
      visitFunctionLikeExpression(
          modifierList = node.child(KtNodeTypes.MODIFIER_LIST),
          keyword = if (hasConstructorKeyword) "constructor" else null,
          typeParameters = null,
          receiverTypeReference = null,
          name = null,
          parameterList = node.child(KtNodeTypes.VALUE_PARAMETER_LIST),
          bodyExpression = null,
          typeOrDelegationCall = null,
      )
    }
  }

  private fun visitSuperTypeList(node: KmpNode) {
    sync(node)
    val entries =
        node.meaningfulChildren().filter {
          it.type == KtNodeTypes.SUPER_TYPE_ENTRY ||
              it.type == KtNodeTypes.SUPER_TYPE_CALL_ENTRY ||
              it.type == KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY
        }
    // optofmt §3/§2: keep the supertype list WHOLE on the `:` line (no break points, items joined by
    // ", "), so the introducer stays attached. When the header overflows, a supertype's own
    // constructor argument list wraps (§4) at one indent — the list itself is not a §4 wrap point, so
    // it is never split one-per-line and `:` is never left dangling. (Matches the single-supertype
    // case, just extended to several supertypes.)
    if (options.optofmt) {
      entries.forEachIndexed { i, entry ->
        if (i != 0) {
          emit(",")
          builder.space()
        }
        visit(entry)
      }
    } else {
      block(expressionBreakIndent) { visitEachCommaSeparated(entries) }
    }
  }

  private fun visitSuperTypeCallEntry(node: KmpNode) {
    sync(node)
    visitCallElement(
        callee = node.meaningfulChildren().firstOrNull(),
        typeArgumentList = node.child(KtNodeTypes.TYPE_ARGUMENT_LIST),
        argumentList = node.child(KtNodeTypes.VALUE_ARGUMENT_LIST),
        lambdaArguments = node.meaningfulChildren().filter { it.type == KtNodeTypes.LAMBDA_ARGUMENT },
    )
  }

  private fun visitClassBody(body: KmpNode) {
    val isEnum = body.parent()?.isEnumClass() == true
    emitBracedBlock(body) { children ->
      val members = children.filter { it.type != KtNodeTypes.ENUM_ENTRY }
      if (isEnum) {
        val entries = children.filter { it.type == KtNodeTypes.ENUM_ENTRY }
        block(ZERO) {
          builder.breakOp(FillMode.UNIFIED, "", ZERO)
          for (entry in entries) {
            visitEnumEntry(entry)
            if (entry.meaningfulChildren().any { it.type == KtTokens.COMMA }) {
              emit(",")
              builder.forcedBreak()
            }
          }
        }
        builder.guessToken(";")
        if (members.isNotEmpty()) {
          builder.forcedBreak()
          builder.blankLineWanted(BlankLineWanted.YES)
        }
      }
      emitMembers(members)
    }
  }

  private fun emitMembers(members: List<KmpNode>) {
    var prev: KmpNode? = null
    for (curr in members) {
      val blankLineBetweenMembers =
          when {
            prev == null -> BlankLineWanted.PRESERVE
            // optofmt §11: a run of consecutive same-kind *one-line* members stays tight — the
            // author's spacing is preserved (no blank forced between one-line abstract funs, etc.),
            // mirroring the top-level declaration rule. A blank is still forced between different
            // kinds and around multi-line members (handled by the clauses below). ktfmt forces a
            // blank between every pair.
            options.optofmt &&
                curr.type == prev.type &&
                isOneLineDeclaration(curr) &&
                isOneLineDeclaration(prev) -> BlankLineWanted.PRESERVE
            prev.type != KtNodeTypes.PROPERTY -> BlankLineWanted.YES
            prev.meaningfulChildren().any { it.type == KtNodeTypes.PROPERTY_ACCESSOR } ->
                BlankLineWanted.YES
            curr.type == KtNodeTypes.PROPERTY -> BlankLineWanted.PRESERVE
            else -> BlankLineWanted.YES
          }
      builder.blankLineWanted(blankLineBetweenMembers)
      block(ZERO) { visit(curr) }
      builder.guessToken(";")
      builder.forcedBreak()
      prev = curr
    }
  }

  private fun visitEnumEntry(node: KmpNode) {
    sync(node)
    block(ZERO) {
      visit(node.child(KtNodeTypes.MODIFIER_LIST))
      node.identifierLeaf()?.let { emit(it.text.toString()) }
      // Superclass constructor args: `A(1)`.
      val args =
          node.child(KtNodeTypes.INITIALIZER_LIST)
              ?.descendants()
              ?.firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT_LIST }
      if (args != null) block(expressionBreakIndent) { visitValueArgumentListInternal(args) }
      // Enum-entry class body: `B { ... }`.
      node.child(KtNodeTypes.CLASS_BODY)?.let {
        builder.space()
        visit(it)
      }
    }
  }

  private fun emitKeywordWithCondition(
      keyword: String,
      condition: KmpNode?,
      surroundConditionWithParens: Boolean = true,
  ) {
    if (condition == null) {
      emit(keyword)
      return
    }
    block(ZERO) {
      emit(keyword)
      builder.space()
      if (surroundConditionWithParens) emit("(")
      if (options.manageTrailingCommas || options.optofmt) {
        // optofmt §2: when the condition wraps, break after `(`, lay every operand at one shared
        // indent, and put the closing `)` on its own line — no continuation drift.
        block(expressionBreakIndent) {
          builder.breakOp(FillMode.UNIFIED, "", ZERO)
          visit(condition)
          builder.breakOp(FillMode.UNIFIED, "", expressionBreakNegativeIndent)
        }
      } else {
        block(ZERO) { visit(condition) }
      }
    }
    if (surroundConditionWithParens) emit(")")
  }

  private fun visitIfExpression(node: KmpNode) {
    sync(node)
    block(ZERO) {
      emitKeywordWithCondition("if", node.child(KtNodeTypes.CONDITION))

      val thenBody = node.child(KtNodeTypes.THEN)?.meaningfulChildren()?.firstOrNull()
      if (thenBody?.type == KtNodeTypes.BLOCK) {
        builder.space()
        block(ZERO) { visit(thenBody) }
      } else {
        builder.breakOp(FillMode.INDEPENDENT, " ", expressionBreakIndent)
        block(expressionBreakIndent) {
          fenceComments()
          visit(thenBody)
        }
      }

      if (node.keywordText("else") != null) {
        if (thenBody?.type == KtNodeTypes.BLOCK) builder.space()
        else builder.breakOp(FillMode.UNIFIED, " ", ZERO)
        block(ZERO) {
          emit("else")
          val elseBody = node.child(KtNodeTypes.ELSE)?.meaningfulChildren()?.firstOrNull()
          if (elseBody?.type == KtNodeTypes.BLOCK || elseBody?.type == KtNodeTypes.IF) {
            builder.space()
            block(ZERO) { visit(elseBody) }
          } else {
            builder.breakOp(FillMode.INDEPENDENT, " ", expressionBreakIndent)
            block(expressionBreakIndent) { visit(elseBody) }
          }
        }
      }
    }
  }

  private fun visitWhenExpression(node: KmpNode) {
    sync(node)
    val kids = node.meaningfulChildren()
    val lpar = kids.indexOfFirst { it.type == KtTokens.LPAR }
    val subject = if (lpar >= 0) kids.getOrNull(lpar + 1)?.takeIf { it.type != KtTokens.RPAR } else null
    block(ZERO) {
      emitKeywordWithCondition("when", subject)
      builder.space()
      builder.token("{", RealOrImaginary.REAL, blockIndent, Optional.of(blockIndent))
      val entries = kids.filter { it.type == KtNodeTypes.WHEN_ENTRY }
      entries.forEachIndexed { index, entry ->
        block(blockIndent) {
          if (index != 0) builder.blankLineWanted(BlankLineWanted.PRESERVE)
          builder.forcedBreak()
          val entryKids = entry.meaningfulChildren()
          val arrowIdx = entryKids.indexOfFirst { it.type == KtTokens.ARROW }
          val isElse = entry.keywordText("else") != null
          val conditions =
              entryKids.take(if (arrowIdx >= 0) arrowIdx else entryKids.size).filter {
                it.type == KtNodeTypes.WHEN_CONDITION_EXPRESSION ||
                    it.type == KtNodeTypes.WHEN_CONDITION_IN_RANGE ||
                    it.type == KtNodeTypes.WHEN_CONDITION_IS_PATTERN
              }
          val guard = entry.child(KtNodeTypes.WHEN_ENTRY_GUARD)
          val body = if (arrowIdx >= 0) entryKids.getOrNull(arrowIdx + 1) else null
          val lastCondition = conditions.lastOrNull()
          val hasTrailingComma =
              lastCondition != null &&
                  entryKids.any {
                    it.type == KtTokens.COMMA && it.startOffset > lastCondition.startOffset
                  }
          val bodyIsBlockLike =
              body?.type == KtNodeTypes.BLOCK || body?.type == KtNodeTypes.LAMBDA_EXPRESSION

          fun emitConditionsAndGuard() {
            if (isElse) {
              emit("else")
            } else {
              conditions.forEachIndexed { i, condition ->
                visit(condition)
                builder.guessToken(",")
                if (i != conditions.lastIndex) {
                  if (options.optofmt) builder.breakOp(FillMode.UNIFIED, " ", ZERO)
                  else builder.forcedBreak()
                }
              }
            }
            if (guard != null) {
              builder.space()
              emitKeywordWithCondition(
                  "if",
                  guard.meaningfulChildren().lastOrNull(),
                  surroundConditionWithParens = false)
            }
          }

          fun emitArrowAndBody() {
            if (hasTrailingComma) builder.forcedBreak() else builder.space()
            emit("->")
            if (bodyIsBlockLike) {
              builder.space()
              visit(body)
            } else if (options.optofmt) {
              // optofmt §1/§3: the branch `->` is an introducer, like `=`. Offer both arrangements
              // and let §1 choose: attach the body to the `->` line (keeping a block-valued body such
              // as `if (…) {` / `when {` opener attached, and any body that fits) or, when attaching
              // would overflow, break after `->` with the body at one indent. Same treatment as an
              // expression-body `=` (see visitFunctionLikeDeclaration) and a block-valued RHS (§3).
              val sink = builder as com.facebook.ktfmt.format.layout.NativeSink
              val rhs = sink.capture { visit(body) }
              val attached = sink.capture { sink.space(); sink.appendSubtree(rhs) }
              val broken =
                  sink.capture {
                    sink.forcedBreak(expressionBreakIndent)
                    sink.appendSubtree(rhs)
                  }
              sink.emitAlt(listOf(attached, broken))
            } else {
              block(expressionBreakIndent) {
                builder.breakOp(FillMode.INDEPENDENT, " ", ZERO)
                visit(body)
              }
            }
            builder.guessToken(";")
          }

          // optofmt §4: comma-separated conditions split one-per-line iff the *whole entry* doesn't
          // fit, never as a half-packed fill. For an expression body we put the conditions and the
          // `-> body` in one level so the §1 optimizer measures them together; ktfmt (and a
          // block/lambda body, whose own breaks are independent) keep the conditions in their own
          // level.
          if (options.optofmt && !bodyIsBlockLike) {
            block(ZERO) {
              emitConditionsAndGuard()
              emitArrowAndBody()
            }
          } else {
            block(ZERO) { emitConditionsAndGuard() }
            emitArrowAndBody()
          }
        }
        builder.forcedBreak()
      }
      emit("}")
    }
  }

  private fun visitUserType(node: KmpNode) {
    sync(node)
    for (child in node.meaningfulChildren()) {
      when {
        child.type == KtNodeTypes.TYPE_ARGUMENT_LIST ->
            block(expressionBreakIndent) { visit(child) }
        child.type == KtTokens.DOT -> emit(".")
        else -> visit(child)
      }
    }
  }

  private fun visitTypeArgumentList(node: KmpNode) {
    sync(node)
    val args = node.meaningfulChildren().filter { it.type == KtNodeTypes.TYPE_PROJECTION }
    // optofmt: a generic type-argument list is NOT a §4 wrappable list — it is kept whole (no break
    // points), and an overflowing declaration wraps after its `=`/`:` introducer instead (see snippet
    // `generic-type-arg-economy`). `breakable = false` stops the optimizer from tearing `Foo<A, B>`
    // across lines just to attach an introducer (RULES §3/§4).
    visitEachCommaSeparated(
        list = args,
        hasTrailingComma = node.hasTrailingCommaAfter(args.lastOrNull()),
        prefix = "<",
        postfix = ">",
        wrapInBlock = !options.manageTrailingCommas,
        breakable = !options.optofmt)
  }

  private fun visitTypeProjection(node: KmpNode) {
    sync(node)
    if (node.meaningfulChildren().any { it.type == KtTokens.MUL }) {
      emit("*")
      return
    }
    // Variance (`in`/`out`) is carried in a modifier list.
    visit(node.child(KtNodeTypes.MODIFIER_LIST))
    visit(node.child(KtNodeTypes.TYPE_REFERENCE))
  }

  private fun visitCallableReference(node: KmpNode) {
    sync(node)
    val kids = node.meaningfulChildren()
    val ccIndex = kids.indexOfFirst { it.type == KtTokens.COLONCOLON }
    val beforeColonColon = if (ccIndex >= 0) kids.subList(0, ccIndex) else emptyList()
    // Receiver expression (composite), then an optional nullability `?`.
    beforeColonColon.firstOrNull { it.firstChild() != null }?.let { visit(it) }
    if (beforeColonColon.any { it.firstChild() == null && it.text.toString() == "?" }) emit("?")
    block(expressionBreakIndent) {
      emit("::")
      builder.breakOp(FillMode.INDEPENDENT, "", ZERO)
      visit(kids.getOrNull(ccIndex + 1))
    }
  }

  private fun visitDestructuringDeclaration(node: KmpNode) {
    sync(node)
    val valOrVar = node.keywordText("val") ?: node.keywordText("var")
    if (valOrVar != null) {
      emit(valOrVar)
      builder.space()
    }
    val entries =
        node.meaningfulChildren().filter {
          it.type == KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY
        }
    val hasTrailingComma = node.hasTrailingCommaAfter(entries.lastOrNull())
    block(ZERO) {
      emit("(")
      builder.breakOp(FillMode.UNIFIED, "", expressionBreakIndent)
      block(expressionBreakIndent) {
        visitEachCommaSeparated(entries, hasTrailingComma = hasTrailingComma, wrapInBlock = true)
      }
    }
    emit(")")
    val initializer = node.initializer()
    if (initializer != null) {
      builder.space()
      emit("=")
      if (hasTrailingComma) {
        builder.space()
      } else {
        builder.breakOp(FillMode.INDEPENDENT, " ", expressionBreakIndent)
      }
      block(expressionBreakIndent, !hasTrailingComma) {
        fenceComments()
        visit(initializer)
      }
    }
  }

  private fun visitTypeAlias(node: KmpNode) {
    sync(node)
    block(ZERO) {
      visit(node.child(KtNodeTypes.MODIFIER_LIST))
      emit("typealias")
      builder.space()
      node.identifierLeaf()?.let { emit(it.text.toString()) }
      visit(node.child(KtNodeTypes.TYPE_PARAMETER_LIST))
      builder.space()
      emit("=")
      builder.breakOp(FillMode.INDEPENDENT, " ", expressionBreakIndent)
      block(expressionBreakIndent) {
        visit(node.child(KtNodeTypes.TYPE_REFERENCE))
        visit(node.child(KtNodeTypes.TYPE_CONSTRAINT_LIST))
        builder.guessToken(";")
      }
      builder.forcedBreak()
    }
  }

  private fun visitClassInitializer(node: KmpNode) {
    sync(node)
    emit("init")
    builder.space()
    visit(node.child(KtNodeTypes.BLOCK))
  }

  private fun visitSecondaryConstructor(node: KmpNode) {
    sync(node)
    block(ZERO) {
      visitFunctionLikeExpression(
          modifierList = node.child(KtNodeTypes.MODIFIER_LIST),
          keyword = "constructor",
          typeParameters = null,
          receiverTypeReference = null,
          name = null,
          parameterList = node.child(KtNodeTypes.VALUE_PARAMETER_LIST),
          bodyExpression = node.child(KtNodeTypes.BLOCK),
          typeOrDelegationCall =
              node.child(KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL)?.takeIf { it.text.isNotBlank() },
      )
    }
  }

  private fun visitConstructorDelegationCall(node: KmpNode) {
    sync(node)
    block(ZERO) {
      val ref = node.meaningfulChildren().firstOrNull()
      emit(if (ref?.text?.toString()?.startsWith("super") == true) "super" else "this")
      visitCallElement(
          callee = null,
          typeArgumentList = node.child(KtNodeTypes.TYPE_ARGUMENT_LIST),
          argumentList = node.child(KtNodeTypes.VALUE_ARGUMENT_LIST),
          lambdaArguments = emptyList(),
      )
    }
  }

  /** `a is Int`, `b !is Int`, `a as String`, `a as? String`. */
  private fun visitTypeOperatorExpression(node: KmpNode) {
    sync(node)
    val kids = node.meaningfulChildren()
    val left = kids.firstOrNull()
    val op = node.child(KtNodeTypes.OPERATION_REFERENCE)
    val typeRef = node.child(KtNodeTypes.TYPE_REFERENCE)
    val openBeforeLeft =
        left?.type != KtNodeTypes.DOT_QUALIFIED_EXPRESSION &&
            left?.type != KtNodeTypes.SAFE_ACCESS_EXPRESSION
    if (openBeforeLeft) builder.open(ZERO)
    visit(left)
    if (!openBeforeLeft) builder.open(ZERO)
    if (node.type == KtNodeTypes.BINARY_WITH_TYPE) {
      // `as` / `as?` always breaks before the operator.
      builder.breakOp(FillMode.UNIFIED, " ", expressionBreakIndent)
    } else {
      // `is` / `!is`: break only in argument-like positions.
      val parentType = node.parent()?.type
      if (parentType == KtNodeTypes.VALUE_ARGUMENT ||
          parentType == KtNodeTypes.PARENTHESIZED ||
          parentType == KtNodeTypes.BODY ||
          parentType == KtNodeTypes.CONDITION) {
        builder.breakOp(FillMode.UNIFIED, " ", expressionBreakIndent)
      } else {
        builder.space()
      }
    }
    if (op != null) emit(op.text.toString())
    builder.breakOp(FillMode.INDEPENDENT, " ", expressionBreakIndent)
    block(expressionBreakIndent) { visit(typeRef) }
    builder.close()
  }

  private fun visitFunctionType(node: KmpNode) {
    sync(node)
    node.child(KtNodeTypes.CONTEXT_RECEIVER_LIST)?.let {
      handleContextReceiverList(it)
      builder.space()
    }
    val receiver = node.child(KtNodeTypes.FUNCTION_TYPE_RECEIVER)
    if (receiver != null) {
      visit(receiver.child(KtNodeTypes.TYPE_REFERENCE) ?: receiver)
      emit(".")
    }
    block(expressionBreakIndent) {
      val paramList = node.child(KtNodeTypes.VALUE_PARAMETER_LIST)
      val params =
          paramList?.meaningfulChildren()?.filter { it.type == KtNodeTypes.VALUE_PARAMETER }
              ?: emptyList()
      visitEachCommaSeparated(
          params,
          hasTrailingComma = paramList?.hasTrailingCommaAfter(params.lastOrNull()) ?: false,
          prefix = "(",
          postfix = ")",
          // A function TYPE's parameter list is part of a type, not a §4 wrappable list — keep it
          // whole so the optimizer can't tear it to attach an introducer (RULES §3/§4).
          breakable = !options.optofmt)
    }
    builder.space()
    emit("->")
    builder.space()
    // The return type is the last TYPE_REFERENCE child (after the ARROW).
    val returnType = node.meaningfulChildren().lastOrNull { it.type == KtNodeTypes.TYPE_REFERENCE }
    block(expressionBreakIndent) { visit(returnType) }
  }

  private fun visitLabeledExpression(node: KmpNode) {
    sync(node)
    val label = node.child(KtNodeTypes.LABEL_QUALIFIER)
    visit(label)
    val base = node.meaningfulChildren().lastOrNull { it.type != KtNodeTypes.LABEL_QUALIFIER }
    if (base?.type != KtNodeTypes.LAMBDA_EXPRESSION) builder.space()
    visit(base)
  }

  private fun visitAnnotatedExpression(node: KmpNode) {
    sync(node)
    block(ZERO) {
      val annotations = node.meaningfulChildren().filter { it.type == KtNodeTypes.ANNOTATION_ENTRY }
      block(ZERO) {
        annotations.forEachIndexed { i, ann ->
          if (i != 0) builder.breakOp(FillMode.UNIFIED, " ", ZERO)
          visit(ann)
        }
      }
      val base = node.meaningfulChildren().lastOrNull { it.type != KtNodeTypes.ANNOTATION_ENTRY }
      when {
        (base?.type == KtNodeTypes.BINARY_EXPRESSION ||
            base?.type == KtNodeTypes.BINARY_WITH_TYPE) &&
            node.parent()?.type == KtNodeTypes.BLOCK -> builder.forcedBreak()
        base?.type == KtNodeTypes.LAMBDA_EXPRESSION -> builder.space()
        base?.type == KtNodeTypes.RETURN -> builder.forcedBreak()
        else -> builder.breakOp(FillMode.UNIFIED, " ", ZERO)
      }
      visit(base)
    }
  }

  private fun visitTypeConstraintList(node: KmpNode) {
    block(expressionBreakIndent) {
      builder.breakOp(FillMode.INDEPENDENT, " ", ZERO)
      emit("where")
      block(expressionBreakIndent) {
        builder.breakOp(FillMode.UNIFIED, " ", ZERO)
        sync(node)
        val constraints = node.meaningfulChildren().filter { it.type == KtNodeTypes.TYPE_CONSTRAINT }
        visitEachCommaSeparated(constraints, wrapInBlock = false)
      }
    }
  }

  private fun visitTypeConstraint(node: KmpNode) {
    sync(node)
    val kids = node.meaningfulChildren()
    visit(kids.firstOrNull()) // subject name
    builder.space()
    emit(":")
    builder.space()
    visit(node.child(KtNodeTypes.TYPE_REFERENCE))
  }

  private fun visitCollectionLiteral(node: KmpNode) {
    sync(node)
    val items =
        node.meaningfulChildren().filterNot {
          it.type == KtTokens.COMMA ||
              it.firstChild() == null // brackets
        }
    block(expressionBreakIndent) {
      visitEachCommaSeparated(
          items,
          hasTrailingComma = node.hasTrailingCommaAfter(items.lastOrNull()),
          prefix = "[",
          postfix = "]",
          wrapInBlock = true)
    }
  }

  private fun visitWhenConditionIsPattern(node: KmpNode) {
    sync(node)
    emit(if (node.text.toString().trimStart().startsWith("!is")) "!is" else "is")
    builder.space()
    visit(node.child(KtNodeTypes.TYPE_REFERENCE))
  }

  private fun visitWhenConditionInRange(node: KmpNode) {
    sync(node)
    emit(if (node.text.toString().trimStart().startsWith("!in")) "!in" else "in")
    builder.space()
    visit(node.meaningfulChildren().lastOrNull())
  }

  private fun visitForExpression(node: KmpNode) {
    sync(node)
    block(ZERO) {
      emit("for")
      builder.space()
      emit("(")
      visit(node.child(KtNodeTypes.VALUE_PARAMETER))
      builder.space()
      emit("in")
      block(ZERO) {
        builder.breakOp(FillMode.UNIFIED, " ", expressionBreakIndent)
        block(expressionBreakIndent) { visit(node.child(KtNodeTypes.LOOP_RANGE)) }
      }
      emit(")")
      builder.space()
      visit(node.child(KtNodeTypes.BODY)?.meaningfulChildren()?.firstOrNull())
    }
  }

  private fun visitWhileExpression(node: KmpNode) {
    sync(node)
    emitKeywordWithCondition("while", node.child(KtNodeTypes.CONDITION))
    builder.space()
    visit(node.child(KtNodeTypes.BODY)?.meaningfulChildren()?.firstOrNull())
  }

  private fun visitDoWhileExpression(node: KmpNode) {
    sync(node)
    emit("do")
    builder.space()
    val body = node.child(KtNodeTypes.BODY)?.meaningfulChildren()?.firstOrNull()
    if (body != null) {
      visit(body)
      builder.space()
    }
    emitKeywordWithCondition("while", node.child(KtNodeTypes.CONDITION))
  }

  private fun visitTryExpression(node: KmpNode) {
    sync(node)
    emit("try")
    builder.space()
    visit(node.child(KtNodeTypes.BLOCK))
    for (catch in node.meaningfulChildren().filter { it.type == KtNodeTypes.CATCH }) {
      builder.space()
      emit("catch")
      builder.space()
      block(ZERO) {
        emit("(")
        block(expressionBreakIndent) {
          builder.breakOp(FillMode.UNIFIED, "", ZERO)
          val catchParam =
              catch.child(KtNodeTypes.VALUE_PARAMETER_LIST)?.child(KtNodeTypes.VALUE_PARAMETER)
          visit(catchParam)
          builder.guessToken(",")
        }
      }
      emit(")")
      builder.space()
      visit(catch.child(KtNodeTypes.BLOCK))
    }
    node.child(KtNodeTypes.FINALLY)?.let { fin ->
      builder.space()
      emit("finally")
      builder.space()
      visit(fin.child(KtNodeTypes.BLOCK))
    }
  }

  private fun visitPrefixExpression(node: KmpNode) {
    sync(node)
    block(ZERO) {
      val op = node.child(KtNodeTypes.OPERATION_REFERENCE)
      val base = node.meaningfulChildren().lastOrNull { it.type != KtNodeTypes.OPERATION_REFERENCE }
      val opText = op?.text?.toString() ?: ""
      emit(opText)
      // `+ +a`, `! !a`: separate adjacent identical-leading unary operators.
      val baseOp = base?.takeIf { it.type == KtNodeTypes.PREFIX_EXPRESSION }?.binaryOperator()
      if (baseOp != null && opText.isNotEmpty() && opText.last() == baseOp.firstOrNull()) {
        builder.space()
      }
      visit(base)
    }
  }

  private fun visitPostfixExpression(node: KmpNode) {
    sync(node)
    block(ZERO) {
      val base = node.meaningfulChildren().firstOrNull { it.type != KtNodeTypes.OPERATION_REFERENCE }
      val opText = node.meaningfulChildren().last().text.toString()
      visit(base)
      // `a!! !!`: separate adjacent identical postfix operators so `!!!!` is not re-lexed.
      val baseOp = base?.takeIf { it.type == KtNodeTypes.POSTFIX_EXPRESSION }?.binaryOperator()
      if (baseOp != null && opText.isNotEmpty() && baseOp.lastOrNull() == opText.first()) {
        builder.space()
      }
      emit(opText)
    }
  }

  private fun visitParenthesized(node: KmpNode) {
    sync(node)
    emit("(")
    visit(node.meaningfulChildren().firstOrNull { it.type != KtTokens.LPAR && it.type != KtTokens.RPAR })
    emit(")")
  }

  private fun visitAnnotationEntry(node: KmpNode) {
    sync(node)
    if (node.meaningfulChildren().any { it.firstChild() == null && it.text.toString() == "@" }) {
      emit("@")
    }
    val target = node.child(KtNodeTypes.ANNOTATION_TARGET)
    if (target != null) {
      emit(target.text.toString())
      emit(":")
    }
    val callee = node.meaningfulChildren().firstOrNull { it.type == KtNodeTypes.CONSTRUCTOR_CALLEE }
    visitCallElement(
        callee = callee,
        typeArgumentList = null,
        argumentList = node.child(KtNodeTypes.VALUE_ARGUMENT_LIST),
        lambdaArguments = emptyList(),
    )
  }

  private fun visitAnnotation(node: KmpNode) {
    sync(node)
    block(ZERO) {
      emit("@")
      val target = node.child(KtNodeTypes.ANNOTATION_TARGET)
      if (target != null) {
        emit(target.text.toString())
        emit(":")
      }
      block(expressionBreakIndent) {
        emit("[")
        block(ZERO) {
          var first = true
          builder.breakOp(FillMode.UNIFIED, "", ZERO)
          for (entry in node.meaningfulChildren().filter { it.type == KtNodeTypes.ANNOTATION_ENTRY }) {
            if (!first) builder.breakOp(FillMode.UNIFIED, " ", ZERO)
            first = false
            visit(entry)
          }
        }
      }
      emit("]")
    }
    builder.forcedBreak()
  }

  private fun visitDestructuringEntry(node: KmpNode) {
    sync(node)
    visit(node.child(KtNodeTypes.MODIFIER_LIST))
    node.identifierLeaf()?.let { emit(it.text.toString()) }
    val type = node.child(KtNodeTypes.TYPE_REFERENCE)
    if (type != null) {
      emit(":")
      builder.space()
      visit(type)
    }
  }

  private fun visitDelegatedSuperTypeEntry(node: KmpNode) {
    sync(node)
    visit(node.child(KtNodeTypes.TYPE_REFERENCE))
    builder.space()
    emit("by")
    builder.space()
    visit(node.meaningfulChildren().lastOrNull())
  }

  private fun visitIntersectionType(node: KmpNode) {
    sync(node)
    for (child in node.meaningfulChildren()) {
      if (child.type == KtTokens.AND) {
        builder.space()
        emit("&")
        builder.space()
      } else {
        visit(child)
      }
    }
  }

  private fun visitFileAnnotationList(node: KmpNode) {
    sync(node)
    for (entry in node.meaningfulChildren().filter { it.type == KtNodeTypes.ANNOTATION_ENTRY }) {
      visit(entry)
      builder.forcedBreak()
    }
  }

  private fun visitContextReceiverList(node: KmpNode) {
    handleContextReceiverList(node)
    builder.forcedBreak()
  }

  private fun handleContextReceiverList(node: KmpNode) {
    sync(node)
    emit("context")
    val receivers =
        node.meaningfulChildren().filter {
          it.type == KtNodeTypes.CONTEXT_RECEIVER || it.type == KtNodeTypes.VALUE_PARAMETER
        }
    visitEachCommaSeparated(
        receivers,
        prefix = "(",
        postfix = ")",
        breakAfterPrefix = false,
        breakBeforePostfix = false)
  }

  private fun visitTypeParameterList(node: KmpNode) {
    sync(node)
    val params = node.meaningfulChildren().filter { it.type == KtNodeTypes.TYPE_PARAMETER }
    block(expressionBreakIndent) {
      visitEachCommaSeparated(
          list = params,
          hasTrailingComma = node.hasTrailingCommaAfter(params.lastOrNull()),
          prefix = "<",
          postfix = ">",
          wrapInBlock = !options.manageTrailingCommas)
    }
  }

  private fun visitTypeParameter(node: KmpNode) {
    sync(node)
    visit(node.child(KtNodeTypes.MODIFIER_LIST))
    node.identifierLeaf()?.let { emit(it.text.toString()) }
    val bound = node.child(KtNodeTypes.TYPE_REFERENCE)
    if (bound != null) {
      builder.space()
      emit(":")
      builder.space()
      visit(bound)
    }
  }

  private fun visitParameter(node: KmpNode) {
    sync(node)
    val destructuring = node.child(KtNodeTypes.DESTRUCTURING_DECLARATION)
    if (destructuring != null) {
      block(ZERO) {
        visit(destructuring)
        val type = node.child(KtNodeTypes.TYPE_REFERENCE)
        if (type != null) {
          emit(":")
          builder.space()
          visit(type)
        }
      }
      return
    }
    block(ZERO) {
      visit(node.child(KtNodeTypes.MODIFIER_LIST))
      val valOrVar = node.keywordText("val") ?: node.keywordText("var")
      if (valOrVar != null) {
        emit(valOrVar)
        builder.space()
      }
      val name = node.identifierLeaf()
      if (name != null) emit(name.text.toString())
      val type = node.child(KtNodeTypes.TYPE_REFERENCE)
      block(expressionBreakIndent, isEnabled = name != null) {
        if (type != null) {
          // A bare type (function-type parameter like `Int`) has no name and no colon.
          if (name != null) {
            emit(":")
            builder.breakOp(FillMode.UNIFIED, " ", ZERO)
          }
          visit(type)
        }
      }
      val default = node.initializer()
      if (default != null) {
        builder.space()
        emit("=")
        builder.breakOp(FillMode.UNIFIED, " ", expressionBreakIndent)
        block(expressionBreakIndent) { visit(default) }
      }
    }
  }

  private fun visitReturn(node: KmpNode) {
    sync(node)
    emit("return")
    visit(node.child(KtNodeTypes.LABEL_QUALIFIER)) // `return@label`, no space
    val returned =
        node.meaningfulChildren().firstOrNull {
          it.text.toString() != "return" && it.type != KtNodeTypes.LABEL_QUALIFIER
        }
    if (returned != null) {
      builder.space()
      visit(returned)
    }
    builder.guessToken(";")
  }

  // ---- Expressions -----------------------------------------------------------------------------

  private val assignmentOps = setOf("=", "+=", "-=", "*=", "/=", "%=")

  /**
   * Infix operators that optofmt §3 treats as introducers — kept attached to their right-hand side
   * so only the RHS wraps (`key to Override(`). Deliberately narrow: general word operators such as
   * `and`/`or`/`shl`/`in`/`until` and user-defined `infix fun`s are *not* introducers and must wrap
   * normally, otherwise a long chain of them could never break and would overflow the column limit.
   */
  private val infixIntroducers = setOf("to")

  private fun visitBinaryExpression(node: KmpNode) {
    sync(node)
    val opText = node.binaryOperator()
    if (opText == null) {
      visitGeneric(node)
      return
    }

    if (opText in assignmentOps && isLambdaOrScopingFunction(node.binaryRight())) {
      visit(node.binaryLeft())
      builder.space()
      emit(opText)
      visitLambdaOrScopingFunction(node.binaryRight())
      return
    }

    // optofmt §1/§3: an assignment (`job = launch(args) { … }`, `x += foo()`) keeps the `=` introducer
    // attached to its RHS opener, exactly like a `val`/property initializer (see [visitProperty]).
    // Offer both arrangements and let §1 pick: attached wins when the RHS opener fits the `=` line
    // (the common case — a call whose trailing lambda merely wraps its own body); break-after-`=` wins
    // only when attaching would overflow. The generic operator path below instead breaks after `=`
    // whenever the RHS is multiline (e.g. a trailing-lambda body), which violates §3.
    val rhsExpr = node.binaryRight()
    if (options.optofmt && opText in assignmentOps && rhsExpr != null) {
      visit(node.binaryLeft())
      builder.space()
      emit(opText)
      emitIntroducerRhs(rhsExpr) { visit(rhsExpr) }
      return
    }

    // Collect a left-associative run of the same operator.
    val parts = ArrayDeque<KmpNode>()
    var current: KmpNode? = node
    while (current?.type == KtNodeTypes.BINARY_EXPRESSION && current.binaryOperator() == opText) {
      parts.addFirst(current)
      current = current.binaryLeft()
    }

    // optofmt §3: an introducer infix call (`a to b`) stays attached to its right-hand side, which
    // wraps its own contents at a single indent. Restricted to a known introducer set and to a
    // single (non-chained) operator, so long chains of other word operators still wrap normally.
    val isAttachedInfix = options.optofmt && opText in infixIntroducers && parts.size == 1
    // optofmt §2: when the operator chain is the condition of an `if`/`while`, the surrounding
    // parenthesis-break already supplies the one indent level, so the operands break at ZERO to
    // avoid a second (drifting) continuation indent. Elsewhere (`val x = a && b`, an elvis in
    // `return`) the chain supplies its own single indent.
    val operandIndent =
        if (isAttachedInfix ||
            (options.optofmt && node.parent()?.type == KtNodeTypes.CONDITION))
            ZERO
        else expressionBreakIndent

    val leftMost = parts.first()
    visit(leftMost.binaryLeft())
    for (part in parts) {
      val isFirst = part === leftMost
      val pop = part.binaryOperator() ?: ""
      when (pop) {
        "..",
        "..<" -> {
          if (isFirst) builder.open(operandIndent)
          emit(pop)
        }
        "?:" -> {
          if (isFirst) builder.open(operandIndent)
          builder.breakOp(FillMode.UNIFIED, " ", ZERO)
          emit(pop)
          builder.space()
        }
        else -> {
          builder.space()
          if (isFirst) builder.open(operandIndent)
          emit(pop)
          if (isAttachedInfix) {
            // Keep the infix introducer on the same line as its right-hand side.
            builder.space()
          } else {
            val fillMode =
                if (part.child(KtNodeTypes.OPERATION_REFERENCE)?.hasLineBreakingCommentBefore() ==
                    true)
                    FillMode.INDEPENDENT
                else FillMode.UNIFIED
            builder.breakOp(fillMode, " ", ZERO)
          }
        }
      }
      visit(part.binaryRight())
    }
    builder.close()
  }

  private fun visitCallExpression(node: KmpNode) {
    sync(node)
    visitCallElement(
        callee = node.meaningfulChildren().firstOrNull(),
        typeArgumentList = node.child(KtNodeTypes.TYPE_ARGUMENT_LIST),
        argumentList = node.child(KtNodeTypes.VALUE_ARGUMENT_LIST),
        lambdaArguments = node.meaningfulChildren().filter { it.type == KtNodeTypes.LAMBDA_ARGUMENT },
    )
  }

  private fun visitCallElement(
      callee: KmpNode?,
      typeArgumentList: KmpNode?,
      argumentList: KmpNode?,
      lambdaArguments: List<KmpNode>,
      argumentsIndent: Indent = expressionBreakIndent,
      lambdaIndent: Indent = ZERO,
      negativeLambdaIndent: Indent = ZERO,
  ) {
    // optofmt §5 (indent economy): when this call's only argument is itself a call that must wrap,
    // collapse the two openers onto one line and stack the closers (`add(OverrideQueue(` … `))`),
    // so the nested groups share a single body indent instead of staircasing. We do this by
    // suppressing this call's own argument indent and emitting the inner call transparently (no
    // leading/trailing breaks); the inner call's parentheses then supply the one break level.
    val argList =
        if (options.optofmt) argumentList?.meaningfulChildren().orEmpty().filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
        else emptyList()
    val collapseSoleCall = options.optofmt && isCollapsibleSoleCall(argList)
    // §4 last-item expansion also needs the call's own argument indent suppressed: the hugging
    // lambda supplies the single body indent, exactly like a trailing lambda outside the parens.
    val hugLastLambda = options.optofmt && isLastArgUnnamedLambda(argList)
    val effectiveArgumentsIndent = if (collapseSoleCall || hugLastLambda) ZERO else argumentsIndent
    block(lambdaIndent) {
      var brokeBeforeBrace: BreakTag? = null
      block(negativeLambdaIndent) {
        visit(callee)
        block(effectiveArgumentsIndent) {
          if (typeArgumentList != null) block(ZERO) { visit(typeArgumentList) }
          if (argumentList != null)
              brokeBeforeBrace =
                  visitValueArgumentListInternal(argumentList, transparent = collapseSoleCall)
        }
      }
      if (lambdaArguments.size > 1) {
        throw ParseError(
            "Maximum one trailing lambda is allowed",
            org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil.offsetToLineColumn(
                code, lambdaArguments[1].startOffset))
      }
      if (lambdaArguments.isNotEmpty()) {
        builder.space()
        visitArgumentInternal(lambdaArguments.first(), wrapInBlock = false, brokeBeforeBrace)
      }
    }
  }

  /** A declaration that occupies a single source line (used by optofmt §11 grouping). */
  private fun isOneLineDeclaration(node: KmpNode): Boolean = !node.text.contains('\n')

  /**
   * True when [args] is exactly one unnamed argument that is itself a call with a non-empty
   * argument list — the shape optofmt §5 collapses (`outer(inner(args))`).
   */
  private fun isCollapsibleSoleCall(args: List<KmpNode>): Boolean {
    val sole = args.singleOrNull() ?: return false
    if (sole.child(KtNodeTypes.VALUE_ARGUMENT_NAME) != null) return false
    val expr = sole.argumentExpression() ?: return false
    if (expr.type != KtNodeTypes.CALL_EXPRESSION) return false
    val innerArgs = expr.child(KtNodeTypes.VALUE_ARGUMENT_LIST) ?: return false
    return innerArgs.meaningfulChildren().any { it.type == KtNodeTypes.VALUE_ARGUMENT }
  }

  /** True when [args]'s final argument is an unnamed lambda and there is at least one arg before
   * it — the shape optofmt §4 expands in place. */
  private fun isLastArgUnnamedLambda(args: List<KmpNode>): Boolean {
    if (args.size < 2) return false
    val last = args.last()
    return last.argumentExpression()?.type == KtNodeTypes.LAMBDA_EXPRESSION &&
        last.child(KtNodeTypes.VALUE_ARGUMENT_NAME) == null
  }

  private fun visitValueArgumentListInternal(
      list: KmpNode,
      transparent: Boolean = false,
  ): BreakTag? {
    sync(list)
    val arguments = list.meaningfulChildren().filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
    if (transparent) {
      // §5: emit `( innerCall )` with no breaks and no indent of our own; the inner call wraps
      // itself, so its openers join ours and its closers stack against ours.
      return visitEachCommaSeparated(
          arguments,
          hasTrailingComma = false,
          wrapInBlock = false,
          breakBeforePostfix = false,
          leadingBreak = false,
          prefix = "(",
          postfix = ")",
          breakAfterPrefix = false,
      )
    }

    // optofmt §4 (last-item expansion): when the final argument is an unnamed lambda, keep the
    // leading arguments inline on the opener line and let the lambda expand in place, with the
    // closing `)` stacked against the lambda's `}` (`call(a, b, { x ->` … `})`). ktfmt instead
    // explodes every argument. (Falling back to one-arg-per-line when the opener line itself
    // overflows would require the global optimizer; here the opener is kept inline.)
    if (options.optofmt && isLastArgUnnamedLambda(arguments)) {
      emit("(")
      val leading = arguments.dropLast(1)
      leading.forEachIndexed { i, arg ->
        if (i != 0) {
          emit(",")
          builder.space()
        }
        visitArgument(arg)
      }
      emit(",")
      builder.space()
      // wrapInBlock = false so the lambda body sits at one indent (like a hugging trailing lambda),
      // not two.
      visitArgumentInternal(arguments.last(), wrapInBlock = false, brokeBeforeBrace = null)
      emit(")")
      return null
    }
    // Parens are "empty" only if there is nothing — not even a comment — between them.
    val hasEmptyParens =
        arguments.isEmpty() &&
            list.children().none { it.type == KtTokens.COMMA || it.type in KtTokens.COMMENTS }
    val lastArg = arguments.lastOrNull()
    val hasTrailingComma =
        lastArg != null &&
            list.meaningfulChildren().any {
              it.type == KtTokens.COMMA && it.startOffset > lastArg.startOffset
            }
    // §4 last-item expansion for a SINGLE argument: keep the call opener + the argument's own opener
    // on one line and let the trailing lambda block hang (`call(x?.let {` … `})`), instead of fully
    // splitting the sole argument one-per-line. ktfmt (gjf path) only hangs a *bare* lambda argument;
    // optofmt additionally hangs an argument that is a scoping-function call ending in a lambda
    // (`cause?.let {…}`, `x?.also {…}`, `run {…}`) — a very common Kotlin shape — via the same path.
    // §4/§5: a sole unnamed argument that is itself block-like — a lambda, a scoping-function call
    // (`x?.let {…}`), or an anonymous object (`object : Subscription {…}`) — hangs its block off the
    // call opener (`onSubscribe(object : Subscription {` … `})`) with the closers stacked, instead of
    // being pushed onto its own line and closed on another (which would drift the body a level and
    // dangle the `)`). ktfmt only hangs a bare lambda; optofmt additionally hangs the scoping-call and
    // object-expression shapes, which are just as block-like.
    val soleArgExpr = arguments.singleOrNull()?.takeIf { it.child(KtNodeTypes.VALUE_ARGUMENT_NAME) == null }?.argumentExpression()
    val isSingleUnnamedLambda =
        soleArgExpr != null &&
            (soleArgExpr.type == KtNodeTypes.LAMBDA_EXPRESSION ||
                (options.optofmt &&
                    (isLambdaOrScopingFunction(soleArgExpr) ||
                        soleArgExpr.type == KtNodeTypes.OBJECT_LITERAL)))

    val wrapInBlock: Boolean
    val breakBeforePostfix: Boolean
    val leadingBreak: Boolean
    val breakAfterPrefix: Boolean
    if (isSingleUnnamedLambda) {
      wrapInBlock = true
      breakBeforePostfix = false
      leadingBreak = !hasEmptyParens && hasTrailingComma
      breakAfterPrefix = false
    } else {
      // optofmt §4: a comma list is compact or fully one-per-line, never half-packed. Keeping the
      // arg breaks in a single (unwrapped) level makes them all break together when the call
      // doesn't fit, instead of filling several args onto a wrapped line.
      wrapInBlock = !options.manageTrailingCommas && !options.optofmt
      // optofmt §4: a fully split argument list closes on its own line even without a trailing
      // comma.
      breakBeforePostfix = (options.manageTrailingCommas || options.optofmt) && !hasEmptyParens
      leadingBreak = !hasEmptyParens
      breakAfterPrefix = !hasEmptyParens
    }
    return visitEachCommaSeparated(
        arguments,
        hasTrailingComma,
        wrapInBlock = wrapInBlock,
        breakBeforePostfix = breakBeforePostfix,
        leadingBreak = leadingBreak,
        prefix = "(",
        postfix = ")",
        breakAfterPrefix = breakAfterPrefix,
    )
  }

  private fun visitArgument(argument: KmpNode) {
    visitArgumentInternal(argument, wrapInBlock = true, brokeBeforeBrace = null)
  }

  private fun visitArgumentInternal(
      argument: KmpNode,
      wrapInBlock: Boolean,
      brokeBeforeBrace: BreakTag?,
  ) {
    sync(argument)
    val nameNode = argument.child(KtNodeTypes.VALUE_ARGUMENT_NAME)
    val exprNode = argument.argumentExpression()
    val hasArgName = nameNode != null
    val isLambda = exprNode?.type == KtNodeTypes.LAMBDA_EXPRESSION
    if (hasArgName) {
      visit(nameNode)
      builder.space()
      emit("=")
      if (isLambda) builder.space()
    }
    // optofmt §3/§6: keep the `name =` introducer attached to its value — the first operand stays on
    // the `=` line when it fits and the value wraps its own contents (a `+` concat at a single indent,
    // a call's args one-per-line) — rather than breaking after `=`. Offer both arrangements and let §1
    // pick (attach unless the opener overflows). The plain-fill path below instead breaks after `=`
    // whenever the value's flat form is long, even when the first operand would fit (§6), and drifts
    // the value a second indent level (§2). Same treatment as [visitProperty]/assignments.
    if (options.optofmt && hasArgName && !isLambda && exprNode != null) {
      val hasStar = argument.meaningfulChildren().any { it.type == KtTokens.MUL }
      emitIntroducerRhs(exprNode) {
        if (hasStar) emit("*")
        visit(exprNode)
      }
      return
    }
    val indent = if (hasArgName && !isLambda) expressionBreakIndent else ZERO
    block(indent, isEnabled = wrapInBlock) {
      if (hasArgName && !isLambda) builder.breakOp(FillMode.INDEPENDENT, " ", ZERO)
      if (argument.meaningfulChildren().any { it.type == KtTokens.MUL }) emit("*")
      if (isLambda) visitLambdaExpressionInternal(exprNode!!, brokeBeforeBrace) else visit(exprNode)
    }
  }

  private fun visitLambdaExpressionInternal(lambda: KmpNode, brokeBeforeBrace: BreakTag?) {
    sync(lambda)
    val functionLiteral = lambda.child(KtNodeTypes.FUNCTION_LITERAL) ?: return visitGeneric(lambda)
    val flKids = functionLiteral.meaningfulChildren()
    val paramList = flKids.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
    val valueParams = paramList?.meaningfulChildren()?.filter { it.type == KtNodeTypes.VALUE_PARAMETER } ?: emptyList()
    val hasParams = valueParams.isNotEmpty()
    val hasArrow = flKids.any { it.type == KtTokens.ARROW }
    val bodyBlock = flKids.firstOrNull { it.type == KtNodeTypes.BLOCK }
    val statements =
        bodyBlock?.meaningfulChildren()?.filter {
          it.type != KtTokens.LBRACE &&
              it.type != KtTokens.RBRACE &&
              it.type != KtTokens.SEMICOLON // handled via guessToken(";")
        } ?: emptyList()
    val hasStatements = statements.isNotEmpty()
    val blockComments =
        bodyBlock?.children()?.filter { it.type in KtTokens.COMMENTS && it.text.startsWith("/*") }
            ?.toList() ?: emptyList()
    // Any comment (line or block) triggers the break structure; line comments are emitted by the
    // engine against the closing brace, exactly as PSI does.
    val hasComments = bodyBlock?.children()?.any { it.type in KtTokens.COMMENTS } ?: false

    fun ifBrokeBeforeBrace(onTrue: Indent, onFalse: Indent): Indent =
        if (brokeBeforeBrace == null) onFalse else Indent.If.make(brokeBeforeBrace, onTrue, onFalse)

    val bracePlusBlockIndent = ifBrokeBeforeBrace(blockPlusExpressionBreakIndent, blockIndent)
    val bracePlusExpressionIndent =
        ifBrokeBeforeBrace(doubleExpressionBreakIndent, expressionBreakIndent)
    val bracePlusZeroIndent = ifBrokeBeforeBrace(expressionBreakIndent, ZERO)

    emit("{")
    if (hasParams || hasArrow) {
      builder.space()
      block(bracePlusExpressionIndent) { visitEachCommaSeparated(valueParams) }
      block(bracePlusBlockIndent) {
        if (paramList?.hasTrailingCommaAfter(valueParams.lastOrNull()) == true) {
          emit(",")
          builder.forcedBreak()
        } else if (hasParams) {
          builder.breakOp(FillMode.INDEPENDENT, " ", ZERO)
        }
        emit("->")
      }
    }
    if (hasParams || hasArrow || hasStatements || hasComments) {
      builder.breakOp(FillMode.UNIFIED, " ", bracePlusZeroIndent)
    }
    if (hasStatements) {
      builder.breakOp(FillMode.UNIFIED, "", bracePlusBlockIndent)
      block(bracePlusBlockIndent) {
        builder.blankLineWanted(BlankLineWanted.NO)
        val shouldForceMultiline =
            options.preserveLambdaBreaks &&
                functionLiteral.descendants().any {
                  it.type in KtTokens.WHITESPACES && it.text.contains('\n')
                }
        val single =
            !shouldForceMultiline &&
                statements.size == 1 &&
                // optofmt §1: a one-statement lambda that fits stays inline even when the statement
                // is a control-flow jump (`?.let { return }`); ktfmt force-expands the `return`.
                (options.optofmt || statements.first().type != KtNodeTypes.RETURN)
        if (single) {
          block(ZERO) { visit(statements[0]) }
          builder.guessToken(";")
        } else {
          var first = true
          builder.guessToken(";")
          for (s in statements) {
            builder.forcedBreak()
            if (!first) builder.blankLineWanted(BlankLineWanted.PRESERVE)
            first = false
            block(ZERO) { visit(s) }
            builder.guessToken(";")
          }
        }
        builder.breakOp(FillMode.UNIFIED, " ", bracePlusZeroIndent)
      }
    } else if (hasComments) {
      builder.breakOp(FillMode.UNIFIED, "", bracePlusBlockIndent)
      block(bracePlusBlockIndent) {
        fenceComments()
        builder.blankLineWanted(BlankLineWanted.NO)
        if (options.optofmt) {
          // The native engine interleaves comments from the source cursor: a same-line comment was
          // already emitted inline right after `{`, and any remaining ones flush here (body-indented)
          // via the sync. Emitting them explicitly (the gjf path below) would DUPLICATE them, since
          // the cursor emits them too — and the duplication compounds every reformat.
          val rbrace = bodyBlock?.children()?.lastOrNull { it.type == KtTokens.RBRACE }
          if (rbrace != null) builder.sync(rbrace.startOffset)
        } else {
          blockComments.forEachIndexed { i, c ->
            if (i > 0) builder.forcedBreak()
            emit(c.text.toString())
          }
        }
        builder.breakOp(FillMode.UNIFIED, " ", bracePlusZeroIndent)
      }
    }
    if (hasParams || hasArrow || hasStatements || hasComments) {
      builder.breakOp(FillMode.UNIFIED, "", bracePlusZeroIndent)
    }
    block(bracePlusZeroIndent) {
      fenceComments()
      builder.token("}", RealOrImaginary.REAL, blockIndent, Optional.empty())
    }
  }

  private fun visitQualifiedExpression(node: KmpNode) {
    sync(node)
    val receiver = node.qualifiedReceiver()
    when {
      receiver?.type == KtNodeTypes.STRING_TEMPLATE -> {
        block(expressionBreakIndent) {
          visit(receiver)
          builder.breakOp(FillMode.UNIFIED, "", ZERO)
          emit(node.operationSignValue())
          visit(node.qualifiedSelector())
        }
      }
      receiver?.type == KtNodeTypes.WHEN -> {
        block(ZERO) {
          visit(receiver)
          emit(node.operationSignValue())
          visit(node.qualifiedSelector())
        }
      }
      else -> emitQualifiedExpression(node)
    }
  }

  private class GroupingInfo {
    var groupOpenCount = 0
    var shouldCloseGroup = false
  }

  private fun emitQualifiedExpression(expression: KmpNode) {
    val parts = breakIntoParts(expression)
    val useBlockLikeLambdaStyle = parts.last().isLambdaPart() && parts.count { it.isLambdaPart() } == 1
    val groupingInfos = computeGroupingInfo(parts, useBlockLikeLambdaStyle)
    // optofmt §1/§7: a trailing lambda on a call that stays grouped on the receiver's intro line
    // (`recv.first(x) { … }.tail()`) forces the whole chain multiline via the lambda's own body
    // breaks — even though the chain itself is not "too long for one line". Its trailing `.calls`
    // should stay attached to the lambda's `}` when they fit (`}.join()`), and only split one-per-line
    // when they genuinely overflow (§7). We achieve this by emitting the breaks *after* such a part as
    // INDEPENDENT (fill) rather than UNIFIED: in the forced-broken chain level, a fill break fires only
    // when the run to the next break won't fit, so the tail attaches when it fits and breaks when it
    // doesn't. `groupedLambdaEnd` is the index of that call part (−1 if none).
    val groupedLambdaEnd =
        if (!options.optofmt) -1
        else
            parts.indices.lastOrNull { index ->
              val part = parts[index]
              (part.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
                  part.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) &&
                  index != parts.size - 1 &&
                  groupingInfos[index].shouldCloseGroup &&
                  part.qualifiedSelector()?.let { sel ->
                    sel.type == KtNodeTypes.CALL_EXPRESSION &&
                        sel.meaningfulChildren().any { it.type == KtNodeTypes.LAMBDA_ARGUMENT }
                  } == true
            } ?: -1
    block(expressionBreakIndent) {
      val nameTag = genSym()
      for ((index, part) in parts.withIndex()) {
        if (part.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
            part.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) {
          if (index > groupedLambdaEnd && groupedLambdaEnd >= 0) {
            // Tail `.call` after a grouped multiline lambda: fill, so it attaches to `}` when it fits.
            builder.breakOp(FillMode.INDEPENDENT, "", ZERO)
          } else {
            builder.breakOp(FillMode.UNIFIED, "", ZERO, Optional.of(nameTag))
          }
        }
        repeat(groupingInfos[index].groupOpenCount) { builder.open(ZERO) }
        when (part.type) {
          KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
          KtNodeTypes.SAFE_ACCESS_EXPRESSION -> {
            emit(part.operationSignValue())
            val selector = part.qualifiedSelector()
            if (selector?.type != KtNodeTypes.CALL_EXPRESSION) {
              visit(selector)
              if (groupingInfos[index].shouldCloseGroup) builder.close()
            } else {
              visit(selector.meaningfulChildren().firstOrNull()) // callee name
              if (groupingInfos[index].shouldCloseGroup) builder.close()
              val isTrailingLambda = useBlockLikeLambdaStyle && index == parts.size - 1
              if (isTrailingLambda) builder.close()
              // optofmt §2/§7: a trailing lambda on a NON-final call part that stays grouped on the
              // receiver's intro line (`GlobalScope.launch(…) { … }.join()`) would otherwise stack the
              // chain's continuation indent (+1) under the lambda's own block indent (+1), drifting the
              // body a second level right and the `}` one level too deep. When the chain breaks, cancel
              // the chain indent for this call element so the body sits exactly one level below the
              // receiver line and the `}` at the receiver's indent. (A non-grouped part sits on its own
              // broken `.call` line, where the body is already one level below it — no pullback.)
              val midChainLambdaGrouped = index == groupedLambdaEnd
              val argsIndentElse = if (index == parts.size - 1) ZERO else expressionBreakIndent
              val lambdaIndentElse = if (isTrailingLambda) expressionBreakNegativeIndent else ZERO
              val negLambdaIndentElse = if (isTrailingLambda) expressionBreakIndent else ZERO
              // A grouped mid-chain lambda always forces the chain to break (its body carries forced
              // breaks), so the pullback can be an unconditional constant: -1 level cancels the chain's
              // continuation indent for this call element. It can't be an `Indent.If` on [nameTag] here
              // — the native engine evaluates a level's indent at cost time, but the `.call` break that
              // sets [nameTag] is walked *after* this part, so [nameTag] is unset when this indent is
              // measured/captured.
              val lambdaIndent =
                  if (midChainLambdaGrouped) expressionBreakNegativeIndent
                  else Indent.If.make(nameTag, ZERO, lambdaIndentElse)
              visitCallElement(
                  callee = null,
                  typeArgumentList = selector.child(KtNodeTypes.TYPE_ARGUMENT_LIST),
                  argumentList = selector.child(KtNodeTypes.VALUE_ARGUMENT_LIST),
                  lambdaArguments =
                      selector.meaningfulChildren().filter { it.type == KtNodeTypes.LAMBDA_ARGUMENT },
                  argumentsIndent = Indent.If.make(nameTag, expressionBreakIndent, argsIndentElse),
                  lambdaIndent = lambdaIndent,
                  negativeLambdaIndent = Indent.If.make(nameTag, ZERO, negLambdaIndentElse),
              )
            }
          }
          KtNodeTypes.ARRAY_ACCESS_EXPRESSION -> {
            visitArrayAccessBrackets(part)
            builder.close()
          }
          KtNodeTypes.POSTFIX_EXPRESSION -> {
            emit(part.meaningfulChildren().last().text.toString())
            builder.close()
          }
          else -> visit(part)
        }
      }
    }
  }

  private fun breakIntoParts(expression: KmpNode): List<KmpNode> {
    val parts = ArrayDeque<KmpNode>()
    var node: KmpNode? = expression
    while (node != null) {
      parts.addFirst(node)
      node =
          when (node.type) {
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION -> node.qualifiedReceiver()
            KtNodeTypes.ARRAY_ACCESS_EXPRESSION,
            KtNodeTypes.POSTFIX_EXPRESSION -> node.meaningfulChildren().firstOrNull()
            else -> null
          }
    }
    return parts.toList()
  }

  private fun computeGroupingInfo(
      parts: List<KmpNode>,
      useBlockLikeLambdaStyle: Boolean,
  ): List<GroupingInfo> {
    val groupingInfos = List(parts.size) { GroupingInfo() }
    var lastIndexToOpen = 0
    for ((index, part) in parts.withIndex()) {
      when (part.type) {
        KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
        KtNodeTypes.SAFE_ACCESS_EXPRESSION -> {
          val receiver = part.qualifiedReceiver()
          val previous =
              if (receiver != null &&
                  (receiver.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
                      receiver.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION))
                  receiver.qualifiedSelector()
              else receiver
          val current = part.qualifiedSelector()
          if (lastIndexToOpen == 0 &&
              current != null &&
              previous != null &&
              shouldGroupPartWithPrevious(parts, part, index, previous, current)) {
            groupingInfos[0].groupOpenCount++
            groupingInfos[index].shouldCloseGroup = true
          } else {
            lastIndexToOpen = index
          }
        }
        KtNodeTypes.ARRAY_ACCESS_EXPRESSION,
        KtNodeTypes.POSTFIX_EXPRESSION -> groupingInfos[lastIndexToOpen].groupOpenCount++
        else -> {}
      }
    }
    if (useBlockLikeLambdaStyle) groupingInfos[0].groupOpenCount++
    return groupingInfos
  }

  private fun shouldGroupPartWithPrevious(
      parts: List<KmpNode>,
      part: KmpNode,
      index: Int,
      previous: KmpNode,
      current: KmpNode,
  ): Boolean {
    val isDotQualified = part.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION
    if (index == 1 && previous.text.length < options.continuationIndent) return true
    if (previous.type == KtNodeTypes.SUPER_EXPRESSION || previous.type == KtNodeTypes.THIS_EXPRESSION)
        return true
    if (previous.type == KtNodeTypes.REFERENCE_EXPRESSION &&
        current.type == KtNodeTypes.REFERENCE_EXPRESSION &&
        isDotQualified)
        return true
    val currentFirst = current.text.toString().firstOrNull()
    if (currentFirst?.isUpperCase() == true &&
        current.type == KtNodeTypes.REFERENCE_EXPRESSION &&
        isDotQualified)
        return true
    if (current.type == KtNodeTypes.CALL_EXPRESSION &&
        previous.type != KtNodeTypes.CALL_EXPRESSION &&
        previous.text.toString().firstOrNull()?.isUpperCase() == true)
        return true
    return current.type == KtNodeTypes.CALL_EXPRESSION &&
        previous.type != KtNodeTypes.CALL_EXPRESSION &&
        index == parts.indices.last
  }

  private fun visitArrayAccessExpression(node: KmpNode) {
    sync(node)
    val arrayExpr = node.meaningfulChildren().firstOrNull()
    if (arrayExpr?.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
        arrayExpr?.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) {
      emitQualifiedExpression(node)
    } else {
      visit(arrayExpr)
      visitArrayAccessBrackets(node)
    }
  }

  private fun visitArrayAccessBrackets(node: KmpNode) {
    // Index expressions are the composite children of INDICES (excluding the `[` `]` `,` leaves).
    val indicesNode = node.child(KtNodeTypes.INDICES)
    val indices = indicesNode?.meaningfulChildren()?.filter { it.firstChild() != null } ?: emptyList()
    block(ZERO) {
      emit("[")
      builder.breakOp(FillMode.UNIFIED, "", expressionBreakIndent)
      block(expressionBreakIndent) {
        visitEachCommaSeparated(
            indices,
            hasTrailingComma = indicesNode?.hasTrailingCommaAfter(indices.lastOrNull()) ?: false,
            wrapInBlock = true)
      }
    }
    emit("]")
  }

  // ---- Comma-separated lists (mirror of KotlinInputAstVisitor.visitEachCommaSeparated) ----------

  private fun visitEachCommaSeparated(
      list: List<KmpNode>,
      hasTrailingComma: Boolean = false,
      wrapInBlock: Boolean = true,
      leadingBreak: Boolean = true,
      prefix: String? = null,
      postfix: String? = null,
      breakAfterPrefix: Boolean = true,
      // optofmt §4: a fully split list puts its closing delimiter on its own line even though no
      // trailing comma is emitted; ktfmt only breaks before the closer when it manages commas.
      breakBeforePostfix: Boolean = options.manageTrailingCommas || options.optofmt,
      // When false, the list is emitted WHOLE — no break points at all, items joined by ", ". Used for
      // constructs that RULES §4 does not treat as wrappable (a generic type-argument list): the
      // optimizer must not tear them across lines; an overflowing declaration wraps elsewhere.
      breakable: Boolean = true,
  ): BreakTag? {
    // optofmt §4: never emit a trailing comma, and don't let a source one force the split — ignore
    // it entirely so the list is laid out compact-or-fully-split on its own merits. Only on the
    // optofmt (native engine) path only: on the gjf path every source token must be emitted (gjf
    // matches the input token stream), so dropping the comma there would throw.
    val hasTrailingComma = hasTrailingComma && !options.optofmt
    val breakAfterLastElement =
        breakable && (hasTrailingComma || (postfix != null && breakBeforePostfix))
    val nameTag = if (breakAfterLastElement) null else genSym()

    if (prefix != null) {
      emit(prefix)
      if (breakAfterPrefix && breakable) {
        builder.breakOp(FillMode.UNIFIED, "", ZERO, Optional.ofNullable(nameTag))
      }
    }

    val breakType = if (hasTrailingComma) FillMode.FORCED else FillMode.UNIFIED
    fun emitComma() {
      emit(",")
      if (breakable) builder.breakOp(breakType, " ", ZERO) else builder.space()
    }

    val indent = if (leadingBreak) ZERO else expressionBreakNegativeIndent
    block(indent, isEnabled = wrapInBlock) {
      if (leadingBreak && breakable) builder.breakOp(breakType, "", ZERO)
      var first = true
      for (value in list) {
        if (!first) emitComma()
        first = false
        visit(value)
      }
      if (hasTrailingComma) emitComma()
    }

    if (breakAfterLastElement) {
      builder.breakOp(breakType, "", expressionBreakNegativeIndent)
    }

    if (postfix != null) {
      if (breakAfterLastElement) {
        block(expressionBreakNegativeIndent) {
          fenceComments()
          builder.token(postfix, RealOrImaginary.REAL, expressionBreakIndent, Optional.empty())
        }
      } else {
        emit(postfix)
      }
    }
    return nameTag
  }

  // ---- Preamble (unchanged) --------------------------------------------------------------------

  private fun visitScript(script: KmpNode) {
    val block = script.children().firstOrNull { it.type == KtNodeTypes.BLOCK } ?: return
    var first = true
    for (child in block.children()) {
      if (child.text.isBlank() || child.type in KtTokens.COMMENTS) continue
      builder.forcedBreak()
      if (!first) builder.blankLineWanted(BlankLineWanted.PRESERVE)
      visit(child)
      first = false
    }
  }

  private fun visitPackageDirective(directive: KmpNode) {
    sync(directive)
    if (directive.text.isBlank()) return
    emit("package")
    builder.space()
    emitDottedName(directive)
    builder.guessToken(";")
    builder.forcedBreak()
  }

  private fun visitImportList(importList: KmpNode) {
    sync(importList)
    for (directive in importList.children()) {
      if (directive.type == KtNodeTypes.IMPORT_DIRECTIVE) visitImportDirective(directive)
    }
  }

  private fun visitImportDirective(directive: KmpNode) {
    sync(directive)
    emit("import")
    builder.space()
    emitDottedName(directive)
    if (directive.children().any { it.type == KtTokens.MUL }) {
      emit(".")
      emit("*")
    }
    val alias = directive.children().firstOrNull { it.type == KtNodeTypes.IMPORT_ALIAS }
    if (alias != null) {
      val aliasName = alias.children().lastOrNull { it.type == KtTokens.IDENTIFIER }
      builder.space()
      emit("as")
      builder.space()
      emit(aliasName!!.text.toString())
    }
    builder.guessToken(";")
    builder.forcedBreak()
  }

  private fun emitDottedName(directive: KmpNode) {
    val path =
        directive.children().firstOrNull {
          it.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
              it.type == KtNodeTypes.REFERENCE_EXPRESSION
        } ?: return
    val names =
        (listOf(path) + path.descendants()).filter {
          it.type == KtNodeTypes.REFERENCE_EXPRESSION
        }
    var first = true
    for (name in names) {
      if (!first) emit(".")
      first = false
      emit(name.text.toString())
    }
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private fun emit(token: String) {
    builder.token(token, RealOrImaginary.REAL, ZERO, Optional.empty())
  }

  /** Offset of the first non-comment, non-whitespace leaf in [node] (its first real token). */
  private fun firstRealTokenOffset(node: KmpNode): Int {
    if (node.firstChild() == null) return node.startOffset
    for (child in node.children()) {
      if (child.text.isBlank() || child.type in KtTokens.COMMENTS) continue
      return firstRealTokenOffset(child)
    }
    return node.startOffset
  }

  private fun sync(node: KmpNode) {
    builder.sync(node.startOffset)
  }

  private fun block(plusIndent: Indent, isEnabled: Boolean = true, body: () -> Unit) {
    if (isEnabled) builder.open(plusIndent)
    body()
    if (isEnabled) builder.close()
  }

  private fun fenceComments() {
    builder.fenceComments()
  }
}

// ---- KmpNode extraction helpers -------------------------------------------------------------

private fun KmpNode.child(type: com.intellij.platform.syntax.SyntaxElementType): KmpNode? =
    meaningfulChildren().firstOrNull { it.type == type }

/** The first leaf with the given keyword [text] (e.g. `val`, `var`). */
private fun KmpNode.keywordText(text: String): String? =
    meaningfulChildren()
        .firstOrNull { it.firstChild() == null && it.text.toString() == text }
        ?.text
        ?.toString()

/** The declaration's own name identifier (a direct leaf child, not one nested in a type). */
private fun KmpNode.identifierLeaf(): KmpNode? =
    meaningfulChildren().firstOrNull { it.type == KtTokens.IDENTIFIER && it.firstChild() == null }

/** The initializer expression: the meaningful child after the `=` token. */
private fun KmpNode.initializer(): KmpNode? {
  val kids = meaningfulChildren()
  val eq = kids.indexOfFirst { it.type == KtTokens.EQ }
  return if (eq >= 0) kids.getOrNull(eq + 1) else null
}

/** A function's receiver type (`fun Foo.bar()`): a TYPE_REFERENCE before the name identifier. */
private fun KmpNode.functionReceiverType(): KmpNode? {
  // The receiver type (`Foo` in `fun Foo.bar()` or `val Foo.bar`) is the TYPE_REFERENCE immediately
  // followed by a `.` sibling. The declared/return type's TYPE_REFERENCE is not followed by a dot
  // (anonymous functions, `val x: Foo`, `val x: Foo.Bar` whose dot is inside the type reference).
  return meaningfulChildren().firstOrNull {
    it.type == KtNodeTypes.TYPE_REFERENCE && it.nextMeaningfulSibling()?.type == KtTokens.DOT
  }
}

/** A property's declared type: the TYPE_REFERENCE after the name identifier. */
private fun KmpNode.propertyType(): KmpNode? {
  val kids = meaningfulChildren()
  val nameIdx = kids.indexOfFirst { it.type == KtTokens.IDENTIFIER && it.firstChild() == null }
  val after = if (nameIdx >= 0) kids.subList(nameIdx + 1, kids.size) else kids
  return after.firstOrNull { it.type == KtNodeTypes.TYPE_REFERENCE }
}

/** A function's return type: a TYPE_REFERENCE that appears after the parameter list. */
private fun KmpNode.returnTypeReference(): KmpNode? {
  val kids = meaningfulChildren()
  val paramsIdx = kids.indexOfFirst { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
  if (paramsIdx < 0) return null
  return kids.drop(paramsIdx + 1).firstOrNull { it.type == KtNodeTypes.TYPE_REFERENCE }
}

/** A function's expression body: the meaningful child after the `=` token. */
private fun KmpNode.expressionBody(): KmpNode? = initializer()

/** The expression of a value/lambda argument (ignoring its name, `=`, and spread `*`). */
private fun KmpNode.argumentExpression(): KmpNode? =
    meaningfulChildren().lastOrNull {
      it.type != KtNodeTypes.VALUE_ARGUMENT_NAME && it.type != KtTokens.EQ && it.type != KtTokens.MUL
    }

/** Whether a COMMA appears after [lastItem] among this node's children (a trailing comma). */
private fun KmpNode.hasTrailingCommaAfter(lastItem: KmpNode?): Boolean {
  if (lastItem == null) return false
  return meaningfulChildren().any {
    it.type == KtTokens.COMMA && it.startOffset > lastItem.startOffset
  }
}

/** A line comment, or an own-line block comment, immediately precedes this node. */
private fun KmpNode.hasLineBreakingCommentBefore(): Boolean {
  var prev = prevSibling()
  while (prev != null && prev.type in KtTokens.WHITESPACES) prev = prev.prevSibling()
  if (prev == null || prev.type !in KtTokens.COMMENTS) return false
  if (prev.text.toString().startsWith("//")) return true
  val before = prev.prevSibling()
  return before != null && before.type in KtTokens.WHITESPACES && before.text.contains('\n')
}

private fun KmpNode.binaryOperator(): String? =
    meaningfulChildren().firstOrNull { it.type == KtNodeTypes.OPERATION_REFERENCE }?.text?.toString()

private fun KmpNode.binaryLeft(): KmpNode? = meaningfulChildren().firstOrNull()

private fun KmpNode.binaryRight(): KmpNode? = meaningfulChildren().lastOrNull()

/** The receiver of a dot-/safe-qualified expression (its first meaningful child). */
private fun KmpNode.qualifiedReceiver(): KmpNode? = meaningfulChildren().firstOrNull()

/** The selector of a dot-/safe-qualified expression (its last meaningful child). */
private fun KmpNode.qualifiedSelector(): KmpNode? = meaningfulChildren().lastOrNull()

private fun KmpNode.operationSignValue(): String =
    if (type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) "?." else "."

/** Whether this chain part is a call carrying a trailing lambda. */
private fun KmpNode.isLambdaPart(): Boolean {
  val call =
      when (type) {
        KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
        KtNodeTypes.SAFE_ACCESS_EXPRESSION ->
            qualifiedSelector()?.takeIf { it.type == KtNodeTypes.CALL_EXPRESSION }
        KtNodeTypes.CALL_EXPRESSION -> this
        else -> null
      }
  return call?.meaningfulChildren()?.any { it.type == KtNodeTypes.LAMBDA_ARGUMENT } == true
}
