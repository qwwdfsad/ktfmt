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

/**
 * Adds and removes elements that are not strictly needed in the code, such as semicolons and unused
 * imports.
 *
 * Consumes the new multiplatform parser's lightweight syntax tree ([KmpAst]) rather than PSI. The
 * caller may pass an already-parsed [root] to avoid re-parsing on the happy path.
 */
object RedundantElementManager {
  /** Remove extra semicolons and unused imports, if enabled in the [options] */
  fun dropRedundantElements(
      code: String,
      options: FormattingOptions,
      root: KmpNode = KmpAst.parse(code),
  ): String {
    val redundantImportDetector = RedundantImportDetector(enabled = options.removeUnusedImports)
    val redundantSemicolonDetector = RedundantSemicolonDetector()
    val trailingCommaDetector = TrailingCommas.Detector()

    redundantImportDetector.analyze(root)
    for (node in root.descendants()) {
      redundantSemicolonDetector.takeElement(node)
      if (options.trailingCommaManagementStrategy.removeRedundantTrailingCommas) {
        trailingCommaDetector.takeElement(node)
      }
    }

    val elementsToRemove =
        redundantSemicolonDetector.getRedundantSemicolonElements() +
            redundantImportDetector.getRedundantImportElements() +
            trailingCommaDetector.getTrailingCommaElements()
    if (elementsToRemove.isEmpty()) return code
    val result = StringBuilder(code)

    for (element in elementsToRemove.sortedByDescending { it.endOffset }) {
      // Don't insert extra newlines when the semicolon is already a line terminator
      val replacement =
          if (element.text.toString() == ";" && !element.nextSiblingContainsNewline()) {
            "\n"
          } else {
            ""
          }
      result.replace(element.startOffset, element.endOffset, replacement)
    }

    return result.toString()
  }

  fun addRedundantElements(
      code: String,
      options: FormattingOptions,
      root: KmpNode = KmpAst.parse(code),
  ): String {
    if (!options.manageTrailingCommas) return code
    return addRedundantElements(code, root)
  }

  private fun addRedundantElements(code: String, root: KmpNode): String {
    val trailingCommaSuggestor = TrailingCommas.Suggestor()
    for (node in root.descendants()) {
      trailingCommaSuggestor.takeElement(node)
    }

    val suggestionElements = trailingCommaSuggestor.getTrailingCommaSuggestions()
    if (suggestionElements.isEmpty()) return code
    val result = StringBuilder(code)

    for (element in suggestionElements.sortedByDescending { it.endOffset }) {
      result.insert(element.endOffset, ',')
    }

    return result.toString()
  }

  private fun KmpNode.nextSiblingContainsNewline(): Boolean {
    val next = nextSibling() ?: return false
    return next.type in KtTokens.WHITESPACES && next.text.contains('\n')
  }
}


