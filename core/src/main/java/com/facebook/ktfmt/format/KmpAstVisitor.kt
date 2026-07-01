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

import com.google.googlejavaformat.Doc
import com.google.googlejavaformat.Indent
import com.google.googlejavaformat.Indent.Const.ZERO
import com.google.googlejavaformat.OpsBuilder
import com.google.googlejavaformat.Output.BreakTag
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
    private val builder: OpsBuilder,
    private val code: String,
) {
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
            isFirst -> OpsBuilder.BlankLineWanted.NO
            // Adjacent top-level properties preserve the author's spacing (no forced blank line).
            child.type == KtNodeTypes.PROPERTY && prev?.type == KtNodeTypes.PROPERTY ->
                OpsBuilder.BlankLineWanted.PRESERVE
            else -> OpsBuilder.BlankLineWanted.YES
          })
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
            builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
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
            builder.breakOp(Doc.FillMode.UNIFIED, " ", expressionBreakIndent)
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
          } else {
            builder.breakOp(Doc.FillMode.UNIFIED, " ", expressionBreakIndent)
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
        block(blockIndent) {
          for (component in components) {
            builder.forcedBreak()
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
        builder.breakOp(Doc.FillMode.UNIFIED, " ", expressionBreakIndent)
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
          builder.breakOp(Doc.FillMode.INDEPENDENT, "", expressionBreakIndent)
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
            builder.breakOp(Doc.FillMode.INDEPENDENT, " ", expressionBreakIndent)
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
          } else {
            block(expressionBreakIndent) {
              builder.breakOp(Doc.FillMode.INDEPENDENT, " ", ZERO)
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
    builder.breakOp(Doc.FillMode.INDEPENDENT, " ", expressionBreakIndent, Optional.of(breakToExpr))
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
      if (onlyAnnotationsSoFar) {
        builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
      } else {
        builder.space()
      }
    }
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
    builder.token("{", Doc.Token.RealOrImaginary.REAL, blockIndent, Optional.of(blockIndent))
    val children =
        node.meaningfulChildren().filter {
          it.type != KtTokens.LBRACE &&
              it.type != KtTokens.RBRACE &&
              it.type != KtTokens.SEMICOLON // handled via guessToken(";")
        }
    if (children.isNotEmpty()) {
      block(blockIndent) {
        builder.forcedBreak()
        builder.blankLineWanted(OpsBuilder.BlankLineWanted.PRESERVE)
        emitChildren(children)
      }
      builder.forcedBreak()
      builder.blankLineWanted(OpsBuilder.BlankLineWanted.NO)
    }
    builder.token("}", Doc.Token.RealOrImaginary.REAL, blockIndent, Optional.empty())
  }

  private fun visitBlockExpression(node: KmpNode) {
    emitBracedBlock(node) { statements ->
      var first = true
      builder.guessToken(";")
      for (statement in statements) {
        builder.forcedBreak()
        if (!first) builder.blankLineWanted(OpsBuilder.BlankLineWanted.PRESERVE)
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
          builder.breakOp(Doc.FillMode.UNIFIED, " ", expressionBreakIndent)
          visit(superTypes)
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
      if (hasConstructorKeyword) builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
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
    block(expressionBreakIndent) { visitEachCommaSeparated(entries) }
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
          builder.breakOp(Doc.FillMode.UNIFIED, "", ZERO)
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
          builder.blankLineWanted(OpsBuilder.BlankLineWanted.YES)
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
            prev == null -> OpsBuilder.BlankLineWanted.PRESERVE
            prev.type != KtNodeTypes.PROPERTY -> OpsBuilder.BlankLineWanted.YES
            prev.meaningfulChildren().any { it.type == KtNodeTypes.PROPERTY_ACCESSOR } ->
                OpsBuilder.BlankLineWanted.YES
            curr.type == KtNodeTypes.PROPERTY -> OpsBuilder.BlankLineWanted.PRESERVE
            else -> OpsBuilder.BlankLineWanted.YES
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
      if (options.manageTrailingCommas) {
        block(expressionBreakIndent) {
          builder.breakOp(Doc.FillMode.UNIFIED, "", ZERO)
          visit(condition)
          builder.breakOp(Doc.FillMode.UNIFIED, "", expressionBreakNegativeIndent)
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
        builder.breakOp(Doc.FillMode.INDEPENDENT, " ", expressionBreakIndent)
        block(expressionBreakIndent) {
          fenceComments()
          visit(thenBody)
        }
      }

      if (node.keywordText("else") != null) {
        if (thenBody?.type == KtNodeTypes.BLOCK) builder.space()
        else builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
        block(ZERO) {
          emit("else")
          val elseBody = node.child(KtNodeTypes.ELSE)?.meaningfulChildren()?.firstOrNull()
          if (elseBody?.type == KtNodeTypes.BLOCK || elseBody?.type == KtNodeTypes.IF) {
            builder.space()
            block(ZERO) { visit(elseBody) }
          } else {
            builder.breakOp(Doc.FillMode.INDEPENDENT, " ", expressionBreakIndent)
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
      builder.token("{", Doc.Token.RealOrImaginary.REAL, blockIndent, Optional.of(blockIndent))
      val entries = kids.filter { it.type == KtNodeTypes.WHEN_ENTRY }
      entries.forEachIndexed { index, entry ->
        block(blockIndent) {
          if (index != 0) builder.blankLineWanted(OpsBuilder.BlankLineWanted.PRESERVE)
          builder.forcedBreak()
          val entryKids = entry.meaningfulChildren()
          val arrowIdx = entryKids.indexOfFirst { it.type == KtTokens.ARROW }
          block(ZERO) {
            if (entry.keywordText("else") != null) {
              emit("else")
            } else {
              val conditions =
                  entryKids.take(if (arrowIdx >= 0) arrowIdx else entryKids.size).filter {
                    it.type == KtNodeTypes.WHEN_CONDITION_EXPRESSION ||
                        it.type == KtNodeTypes.WHEN_CONDITION_IN_RANGE ||
                        it.type == KtNodeTypes.WHEN_CONDITION_IS_PATTERN
                  }
              conditions.forEachIndexed { i, condition ->
                visit(condition)
                builder.guessToken(",")
                if (i != conditions.lastIndex) builder.forcedBreak()
              }
            }
            val guard = entry.child(KtNodeTypes.WHEN_ENTRY_GUARD)
            if (guard != null) {
              builder.space()
              emitKeywordWithCondition(
                  "if",
                  guard.meaningfulChildren().lastOrNull(),
                  surroundConditionWithParens = false)
            }
          }
          val body = if (arrowIdx >= 0) entryKids.getOrNull(arrowIdx + 1) else null
          val lastCondition =
              entryKids
                  .take(if (arrowIdx >= 0) arrowIdx else entryKids.size)
                  .lastOrNull {
                    it.type == KtNodeTypes.WHEN_CONDITION_EXPRESSION ||
                        it.type == KtNodeTypes.WHEN_CONDITION_IN_RANGE ||
                        it.type == KtNodeTypes.WHEN_CONDITION_IS_PATTERN
                  }
          val hasTrailingComma =
              lastCondition != null &&
                  entryKids.any {
                    it.type == KtTokens.COMMA && it.startOffset > lastCondition.startOffset
                  }
          if (hasTrailingComma) builder.forcedBreak() else builder.space()
          emit("->")
          if (body?.type == KtNodeTypes.BLOCK || body?.type == KtNodeTypes.LAMBDA_EXPRESSION) {
            builder.space()
            visit(body)
          } else {
            block(expressionBreakIndent) {
              builder.breakOp(Doc.FillMode.INDEPENDENT, " ", ZERO)
              visit(body)
            }
          }
          builder.guessToken(";")
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
    visitEachCommaSeparated(
        list = args,
        hasTrailingComma = node.hasTrailingCommaAfter(args.lastOrNull()),
        prefix = "<",
        postfix = ">",
        wrapInBlock = !options.manageTrailingCommas)
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
      builder.breakOp(Doc.FillMode.INDEPENDENT, "", ZERO)
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
      builder.breakOp(Doc.FillMode.UNIFIED, "", expressionBreakIndent)
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
        builder.breakOp(Doc.FillMode.INDEPENDENT, " ", expressionBreakIndent)
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
      builder.breakOp(Doc.FillMode.INDEPENDENT, " ", expressionBreakIndent)
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
      builder.breakOp(Doc.FillMode.UNIFIED, " ", expressionBreakIndent)
    } else {
      // `is` / `!is`: break only in argument-like positions.
      val parentType = node.parent()?.type
      if (parentType == KtNodeTypes.VALUE_ARGUMENT ||
          parentType == KtNodeTypes.PARENTHESIZED ||
          parentType == KtNodeTypes.BODY ||
          parentType == KtNodeTypes.CONDITION) {
        builder.breakOp(Doc.FillMode.UNIFIED, " ", expressionBreakIndent)
      } else {
        builder.space()
      }
    }
    if (op != null) emit(op.text.toString())
    builder.breakOp(Doc.FillMode.INDEPENDENT, " ", expressionBreakIndent)
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
          postfix = ")")
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
          if (i != 0) builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
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
        else -> builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
      }
      visit(base)
    }
  }

  private fun visitTypeConstraintList(node: KmpNode) {
    block(expressionBreakIndent) {
      builder.breakOp(Doc.FillMode.INDEPENDENT, " ", ZERO)
      emit("where")
      block(expressionBreakIndent) {
        builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
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
        builder.breakOp(Doc.FillMode.UNIFIED, " ", expressionBreakIndent)
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
          builder.breakOp(Doc.FillMode.UNIFIED, "", ZERO)
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
          builder.breakOp(Doc.FillMode.UNIFIED, "", ZERO)
          for (entry in node.meaningfulChildren().filter { it.type == KtNodeTypes.ANNOTATION_ENTRY }) {
            if (!first) builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
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
            builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
          }
          visit(type)
        }
      }
      val default = node.initializer()
      if (default != null) {
        builder.space()
        emit("=")
        builder.breakOp(Doc.FillMode.UNIFIED, " ", expressionBreakIndent)
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

    // Collect a left-associative run of the same operator.
    val parts = ArrayDeque<KmpNode>()
    var current: KmpNode? = node
    while (current?.type == KtNodeTypes.BINARY_EXPRESSION && current.binaryOperator() == opText) {
      parts.addFirst(current)
      current = current.binaryLeft()
    }

    val leftMost = parts.first()
    visit(leftMost.binaryLeft())
    for (part in parts) {
      val isFirst = part === leftMost
      val pop = part.binaryOperator() ?: ""
      when (pop) {
        "..",
        "..<" -> {
          if (isFirst) builder.open(expressionBreakIndent)
          emit(pop)
        }
        "?:" -> {
          if (isFirst) builder.open(expressionBreakIndent)
          builder.breakOp(Doc.FillMode.UNIFIED, " ", ZERO)
          emit(pop)
          builder.space()
        }
        else -> {
          builder.space()
          if (isFirst) builder.open(expressionBreakIndent)
          emit(pop)
          val fillMode =
              if (part.child(KtNodeTypes.OPERATION_REFERENCE)?.hasLineBreakingCommentBefore() == true)
                  Doc.FillMode.INDEPENDENT
              else Doc.FillMode.UNIFIED
          builder.breakOp(fillMode, " ", ZERO)
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
    block(lambdaIndent) {
      var brokeBeforeBrace: BreakTag? = null
      block(negativeLambdaIndent) {
        visit(callee)
        block(argumentsIndent) {
          if (typeArgumentList != null) block(ZERO) { visit(typeArgumentList) }
          if (argumentList != null) brokeBeforeBrace = visitValueArgumentListInternal(argumentList)
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

  private fun visitValueArgumentListInternal(list: KmpNode): BreakTag? {
    sync(list)
    val arguments = list.meaningfulChildren().filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
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
    val isSingleUnnamedLambda =
        arguments.size == 1 &&
            arguments.first().argumentExpression()?.type == KtNodeTypes.LAMBDA_EXPRESSION &&
            arguments.first().child(KtNodeTypes.VALUE_ARGUMENT_NAME) == null

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
      wrapInBlock = !options.manageTrailingCommas
      breakBeforePostfix = options.manageTrailingCommas && !hasEmptyParens
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
    val indent = if (hasArgName && !isLambda) expressionBreakIndent else ZERO
    block(indent, isEnabled = wrapInBlock) {
      if (hasArgName && !isLambda) builder.breakOp(Doc.FillMode.INDEPENDENT, " ", ZERO)
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
          builder.breakOp(Doc.FillMode.INDEPENDENT, " ", ZERO)
        }
        emit("->")
      }
    }
    if (hasParams || hasArrow || hasStatements || hasComments) {
      builder.breakOp(Doc.FillMode.UNIFIED, " ", bracePlusZeroIndent)
    }
    if (hasStatements) {
      builder.breakOp(Doc.FillMode.UNIFIED, "", bracePlusBlockIndent)
      block(bracePlusBlockIndent) {
        builder.blankLineWanted(OpsBuilder.BlankLineWanted.NO)
        val shouldForceMultiline =
            options.preserveLambdaBreaks &&
                functionLiteral.descendants().any {
                  it.type in KtTokens.WHITESPACES && it.text.contains('\n')
                }
        val single =
            !shouldForceMultiline &&
                statements.size == 1 &&
                statements.first().type != KtNodeTypes.RETURN
        if (single) {
          block(ZERO) { visit(statements[0]) }
          builder.guessToken(";")
        } else {
          var first = true
          builder.guessToken(";")
          for (s in statements) {
            builder.forcedBreak()
            if (!first) builder.blankLineWanted(OpsBuilder.BlankLineWanted.PRESERVE)
            first = false
            block(ZERO) { visit(s) }
            builder.guessToken(";")
          }
        }
        builder.breakOp(Doc.FillMode.UNIFIED, " ", bracePlusZeroIndent)
      }
    } else if (hasComments) {
      builder.breakOp(Doc.FillMode.UNIFIED, "", bracePlusBlockIndent)
      block(bracePlusBlockIndent) {
        fenceComments()
        builder.blankLineWanted(OpsBuilder.BlankLineWanted.NO)
        blockComments.forEachIndexed { i, c ->
          if (i > 0) builder.forcedBreak()
          emit(c.text.toString())
        }
        builder.breakOp(Doc.FillMode.UNIFIED, " ", bracePlusZeroIndent)
      }
    }
    if (hasParams || hasArrow || hasStatements || hasComments) {
      builder.breakOp(Doc.FillMode.UNIFIED, "", bracePlusZeroIndent)
    }
    block(bracePlusZeroIndent) {
      fenceComments()
      builder.token("}", Doc.Token.RealOrImaginary.REAL, blockIndent, Optional.empty())
    }
  }

  private fun visitQualifiedExpression(node: KmpNode) {
    sync(node)
    val receiver = node.qualifiedReceiver()
    when {
      receiver?.type == KtNodeTypes.STRING_TEMPLATE -> {
        block(expressionBreakIndent) {
          visit(receiver)
          builder.breakOp(Doc.FillMode.UNIFIED, "", ZERO)
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
    block(expressionBreakIndent) {
      val nameTag = genSym()
      for ((index, part) in parts.withIndex()) {
        if (part.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
            part.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) {
          builder.breakOp(Doc.FillMode.UNIFIED, "", ZERO, Optional.of(nameTag))
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
              val argsIndentElse = if (index == parts.size - 1) ZERO else expressionBreakIndent
              val lambdaIndentElse = if (isTrailingLambda) expressionBreakNegativeIndent else ZERO
              val negLambdaIndentElse = if (isTrailingLambda) expressionBreakIndent else ZERO
              visitCallElement(
                  callee = null,
                  typeArgumentList = selector.child(KtNodeTypes.TYPE_ARGUMENT_LIST),
                  argumentList = selector.child(KtNodeTypes.VALUE_ARGUMENT_LIST),
                  lambdaArguments =
                      selector.meaningfulChildren().filter { it.type == KtNodeTypes.LAMBDA_ARGUMENT },
                  argumentsIndent = Indent.If.make(nameTag, expressionBreakIndent, argsIndentElse),
                  lambdaIndent = Indent.If.make(nameTag, ZERO, lambdaIndentElse),
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
      builder.breakOp(Doc.FillMode.UNIFIED, "", expressionBreakIndent)
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
      breakBeforePostfix: Boolean = options.manageTrailingCommas,
  ): BreakTag? {
    val breakAfterLastElement = hasTrailingComma || (postfix != null && breakBeforePostfix)
    val nameTag = if (breakAfterLastElement) null else genSym()

    if (prefix != null) {
      emit(prefix)
      if (breakAfterPrefix) {
        builder.breakOp(Doc.FillMode.UNIFIED, "", ZERO, Optional.ofNullable(nameTag))
      }
    }

    val breakType = if (hasTrailingComma) Doc.FillMode.FORCED else Doc.FillMode.UNIFIED
    fun emitComma() {
      emit(",")
      builder.breakOp(breakType, " ", ZERO)
    }

    val indent = if (leadingBreak) ZERO else expressionBreakNegativeIndent
    block(indent, isEnabled = wrapInBlock) {
      if (leadingBreak) builder.breakOp(breakType, "", ZERO)
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
          builder.token(postfix, Doc.Token.RealOrImaginary.REAL, expressionBreakIndent, Optional.empty())
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
      if (!first) builder.blankLineWanted(OpsBuilder.BlankLineWanted.PRESERVE)
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
    builder.token(token, Doc.Token.RealOrImaginary.REAL, ZERO, Optional.empty())
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
    builder.addAll(FenceCommentsOp.AS_LIST)
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
