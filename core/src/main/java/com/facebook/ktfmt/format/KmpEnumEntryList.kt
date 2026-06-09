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
 * Model of a list of enum entries, built from the new multiplatform parser's syntax tree (the kmp
 * analog of [EnumEntryList]).
 *
 * Unlike PSI, the kmp tree bundles the separating/terminating `,` and `;` as the trailing children
 * of the last [KtNodeTypes.ENUM_ENTRY] rather than as siblings in the class body.
 */
class KmpEnumEntryList
private constructor(
    val enumEntries: List<KmpNode>,
    val trailingComma: KmpNode?,
    val terminatingSemicolon: KmpNode?,
) {
  companion object {
    fun extractParentList(enumEntry: KmpNode): KmpEnumEntryList =
        checkNotNull(extractChildList(checkNotNull(enumEntry.parent())))

    fun extractChildList(classBody: KmpNode): KmpEnumEntryList? {
      if (classBody.type != KtNodeTypes.CLASS_BODY) return null
      val clazz = classBody.parent() ?: return null
      if (clazz.type != KtNodeTypes.CLASS || !clazz.isEnumClass()) return null

      val enumEntries = classBody.meaningfulChildren().filter { it.type == KtNodeTypes.ENUM_ENTRY }

      if (enumEntries.isEmpty()) {
        val semicolon = classBody.children().firstOrNull { it.type == KtTokens.SEMICOLON }
        return KmpEnumEntryList(enumEntries, trailingComma = null, terminatingSemicolon = semicolon)
      }

      val lastEntryChildren = enumEntries.last().meaningfulChildren()
      var comma: KmpNode? = null
      var semicolon: KmpNode? = null
      when (lastEntryChildren.lastOrNull()?.type) {
        KtTokens.COMMA -> comma = lastEntryChildren.last()
        KtTokens.SEMICOLON -> {
          semicolon = lastEntryChildren.last()
          val prev = lastEntryChildren.getOrNull(lastEntryChildren.size - 2)
          if (prev?.type == KtTokens.COMMA) comma = prev
        }
        else -> {}
      }
      return KmpEnumEntryList(enumEntries, comma, semicolon)
    }
  }
}

/** Whether this CLASS node carries the `enum` modifier. */
internal fun KmpNode.isEnumClass(): Boolean {
  val modifierList =
      meaningfulChildren().firstOrNull { it.type == KtNodeTypes.MODIFIER_LIST } ?: return false
  return modifierList.children().any { it.firstChild() == null && it.text.toString() == "enum" }
}
