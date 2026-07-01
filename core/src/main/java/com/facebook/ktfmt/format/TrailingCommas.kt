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

/**
 * Detects trailing commas, or elements that should have trailing commas, in the new multiplatform
 * parser's syntax tree.
 */
object TrailingCommas {

  class Detector {
    private val trailingCommas = mutableListOf<KmpNode>()

    fun getTrailingCommaElements(): List<KmpNode> = trailingCommas

    fun takeElement(element: KmpNode) {
      if (isTrailingComma(element)) {
        trailingCommas += element
      }
    }

    private fun isTrailingComma(element: KmpNode): Boolean {
      if (element.type != KtTokens.COMMA) return false
      val parent = element.parent() ?: return false
      return extractManagedList(parent)?.trailingComma?.startOffset == element.startOffset
    }
  }

  class Suggestor {
    private val suggestionElements = mutableListOf<KmpNode>()

    fun getTrailingCommaSuggestions(): List<KmpNode> = suggestionElements

    fun takeElement(element: KmpNode) {
      when (element.type) {
        // Only suggest on the container, not the entries themselves.
        KtNodeTypes.ENUM_ENTRY,
        KtNodeTypes.WHEN_ENTRY -> return
        KtNodeTypes.VALUE_PARAMETER_LIST -> {
          val parent = element.parent()
          if (parent?.type == KtNodeTypes.FUNCTION_LITERAL &&
              parent.parent()?.type == KtNodeTypes.LAMBDA_EXPRESSION) {
            return // Never add trailing commas to lambda param lists
          }
        }
        KtNodeTypes.CLASS_BODY -> {
          KmpEnumEntryList.extractChildList(element)?.also {
            if (it.terminatingSemicolon != null) {
              return // Never add a trailing comma when there is already a terminating semicolon
            }
          }
        }
      }

      val list = extractManagedList(element) ?: return
      if (!element.text.contains('\n')) {
        return // Only suggest trailing commas where there is already a line break
      }
      if (list.items.size <= 1) {
        return // Never insert commas to single-element lists
      }
      if (list.trailingComma != null) {
        return // Never insert a comma if there already is one
      }

      suggestionElements.add(list.items.last().lastMeaningfulLeaf())
    }
  }

  private class ManagedList(val items: List<KmpNode>, val trailingComma: KmpNode?)

  private fun extractManagedList(element: KmpNode): ManagedList? =
      when (element.type) {
        KtNodeTypes.VALUE_ARGUMENT_LIST,
        KtNodeTypes.VALUE_PARAMETER_LIST,
        KtNodeTypes.TYPE_ARGUMENT_LIST,
        KtNodeTypes.TYPE_PARAMETER_LIST,
        KtNodeTypes.COLLECTION_LITERAL_EXPRESSION -> bracketedList(element)
        KtNodeTypes.WHEN_ENTRY -> whenEntryList(element)
        KtNodeTypes.ENUM_ENTRY ->
            KmpEnumEntryList.extractParentList(element).let { ManagedList(it.enumEntries, it.trailingComma) }
        KtNodeTypes.CLASS_BODY ->
            KmpEnumEntryList.extractChildList(element)?.let {
              ManagedList(it.enumEntries, it.trailingComma)
            }
        else -> null
      }

  /** Items are the composite (non-leaf) children; the trailing comma is the comma after the last. */
  private fun bracketedList(node: KmpNode): ManagedList {
    val kids = node.meaningfulChildren()
    val items = kids.filter { it.firstChild() != null && it.type != KtTokens.COMMA }
    return ManagedList(items, trailingCommaAfter(kids, items.lastOrNull()))
  }

  private fun whenEntryList(node: KmpNode): ManagedList {
    val kids = node.meaningfulChildren()
    val arrowIndex = kids.indexOfFirst { it.type == KtTokens.ARROW }
    val beforeArrow = if (arrowIndex >= 0) kids.subList(0, arrowIndex) else kids
    val items = beforeArrow.filter { it.firstChild() != null && it.type != KtTokens.COMMA }
    return ManagedList(items, trailingCommaAfter(beforeArrow, items.lastOrNull()))
  }

  private fun trailingCommaAfter(siblings: List<KmpNode>, lastItem: KmpNode?): KmpNode? {
    if (lastItem == null) return null
    return siblings.firstOrNull { it.type == KtTokens.COMMA && it.startOffset > lastItem.startOffset }
  }
}
