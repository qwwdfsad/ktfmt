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

import fleet.org.jetbrains.kotlin.kmp.lexer.KtTokens
import fleet.org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/** Finds redundant semicolons in the new multiplatform parser's syntax tree. */
internal class RedundantSemicolonDetector {
  private val extraSemicolons = mutableListOf<KmpNode>()

  fun getRedundantSemicolonElements(): List<KmpNode> = extraSemicolons

  fun takeElement(element: KmpNode) {
    if (isExtraSemicolon(element)) {
      extraSemicolons += element
    }
  }

  private fun isLastConcreteChild(element: KmpNode): Boolean {
    val next = element.nextMeaningfulSibling()
    return next == null || next.type == KtTokens.RBRACE
  }

  private fun isExtraSemicolon(element: KmpNode): Boolean {
    if (element.type != KtTokens.SEMICOLON) return false

    val parent = element.parent() ?: return false
    if (parent.type == KtNodeTypes.STRING_TEMPLATE) return false

    if (parent.type == KtNodeTypes.ENUM_ENTRY) {
      val classBody = parent.parent() ?: return false
      // Terminating semicolon is redundant only when the entry is the last declaration.
      val decls =
          classBody.meaningfulChildren().filterNot {
            it.type == KtTokens.LBRACE || it.type == KtTokens.RBRACE
          }
      return decls.lastOrNull()?.startOffset == parent.startOffset
    }

    val prevConcrete = element.prevMeaningfulSibling()
    if (parent.type == KtNodeTypes.CLASS_BODY) {
      if (
          prevConcrete != null &&
              prevConcrete.type == KtNodeTypes.OBJECT_DECLARATION &&
              prevConcrete.isCompanionWithoutName() &&
              !isLastConcreteChild(element)
      ) {
        // Example: `class Foo { companion object ; init { } }`
        return false
      }

      val enumEntryList = KmpEnumEntryList.extractChildList(parent) ?: return true
      val declsEmpty =
          parent.meaningfulChildren().none {
            it.type != KtTokens.LBRACE &&
                it.type != KtTokens.RBRACE &&
                it.type != KtTokens.SEMICOLON
          }
      // Is not terminating semicolon or is terminating with no members.
      return enumEntryList.terminatingSemicolon?.startOffset != element.startOffset || declsEmpty
    }

    if (
        (prevConcrete?.type == KtNodeTypes.IF || prevConcrete?.type == KtNodeTypes.WHILE) &&
            prevConcrete.endsWithEmptyControlStructureBody()
    ) {
      return false
    }

    // Trailing-lambda syntax is too flexible; assume all semicolons followed by lambdas matter.
    val nextConcrete = element.nextMeaningfulSibling()
    val nextSiblingIsLambda =
        nextConcrete != null &&
            (nextConcrete.type == KtNodeTypes.LAMBDA_EXPRESSION ||
                (nextConcrete.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION &&
                    nextConcrete.meaningfulChildren().firstOrNull()?.type ==
                        KtNodeTypes.LAMBDA_EXPRESSION))

    return !nextSiblingIsLambda
  }
}

/** An unnamed `companion object`. */
internal fun KmpNode.isCompanionWithoutName(): Boolean {
  val modifierList = meaningfulChildren().firstOrNull { it.type == KtNodeTypes.MODIFIER_LIST }
  val isCompanion =
      modifierList?.children()?.any { it.text.toString() == "companion" } == true
  val hasName = meaningfulChildren().any { it.type == KtTokens.IDENTIFIER }
  return isCompanion && !hasName
}

/** Whether this control-structure node (`if`/`while`) ends with an empty body container. */
internal fun KmpNode.endsWithEmptyControlStructureBody(): Boolean {
  val last = meaningfulChildren().lastOrNull() ?: return false
  return (last.type == KtNodeTypes.BODY ||
      last.type == KtNodeTypes.THEN ||
      last.type == KtNodeTypes.ELSE) && last.text.isEmpty()
}
