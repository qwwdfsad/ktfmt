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

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.SyntaxLanguage
import com.intellij.platform.syntax.element.SyntaxTokenTypes
import com.intellij.platform.syntax.tree.SyntaxNode
import com.intellij.platform.syntax.tree.parse as kmpParse
import com.intellij.platform.syntax.util.language.SyntaxElementLanguageProvider
import fleet.org.jetbrains.kotlin.kmp.lexer.KotlinLexer
import fleet.org.jetbrains.kotlin.kmp.lexer.KtTokens
import fleet.org.jetbrains.kotlin.kmp.parser.KotlinParser

/**
 * A persistent, navigable node of the parsed Kotlin tree.
 *
 * JetBrains' multiplatform parser produces a *lazy* [SyntaxNode] view whose `firstChild()` /
 * `nextSibling()` allocate a fresh wrapper (and recompute the element type) on every call. ktfmt
 * walks the tree many times per format (error check, import sort, redundant-element passes, the
 * tokenizer, and the visitor), so navigating that lazy view directly re-allocates the whole tree on
 * each walk. Instead we materialize it once (the single place that pays the lazy-navigation cost)
 * into this plain structure: children are a stored list, navigation is O(1) field access with no
 * allocation, and `meaningfulChildren` / `text` are cached.
 */
class KmpNode(
    @JvmField val type: SyntaxElementType,
    @JvmField val startOffset: Int,
    @JvmField val endOffset: Int,
    @JvmField val errorMessage: String?,
    private val code: String,
) {
  @JvmField var parent: KmpNode? = null
  @JvmField var indexInParent: Int = 0
  @JvmField val childList: ArrayList<KmpNode> = ArrayList()

  private var cachedText: String? = null
  private var cachedMeaningful: List<KmpNode>? = null

  val text: String
    get() {
      var t = cachedText
      if (t == null) {
        t = code.substring(startOffset, endOffset)
        cachedText = t
      }
      return t
    }

  fun parent(): KmpNode? = parent

  fun firstChild(): KmpNode? = if (childList.isEmpty()) null else childList[0]

  fun lastChild(): KmpNode? = if (childList.isEmpty()) null else childList[childList.size - 1]

  fun nextSibling(): KmpNode? {
    val p = parent ?: return null
    val i = indexInParent + 1
    return if (i < p.childList.size) p.childList[i] else null
  }

  fun prevSibling(): KmpNode? {
    val p = parent ?: return null
    return if (indexInParent > 0) p.childList[indexInParent - 1] else null
  }

  /** Direct children excluding whitespace and comments. Computed once and cached. */
  fun meaningfulChildrenCached(): List<KmpNode> {
    var m = cachedMeaningful
    if (m == null) {
      val kids = childList
      val result = ArrayList<KmpNode>(kids.size)
      for (i in kids.indices) {
        val c = kids[i]
        if (!c.isTrivia()) result.add(c)
      }
      m = result
      cachedMeaningful = m
    }
    return m
  }
}

/**
 * Thin wrapper around JetBrains' new multiplatform, IntelliJ-PSI-free Kotlin parser
 * (`org.jetbrains:kotlin-syntax`). It parses to the library's lazy [SyntaxNode] tree, then
 * materializes a persistent [KmpNode] tree (see [KmpNode]) which is the only thing ktfmt's
 * formatting passes and [KmpAstVisitor] consume.
 */
internal object KmpAst {
  private val KOTLIN_LANGUAGE = SyntaxLanguage("kotlin")

  // Single-language documents: every node inherits the parser's language, so the provider can be
  // empty.
  private val NO_LANGUAGE_PROVIDER = SyntaxElementLanguageProvider { emptySequence() }

  /**
   * Parses [code] and returns the root of the materialized tree.
   *
   * Parsed in script mode: unlike the regular file grammar, scripts permit top-level statements and
   * expressions, so a bare `"""...""".trimIndent()` snippet parses into a real expression tree
   * (matching the leniency of the old PSI parser) while ordinary declarations are unaffected.
   */
  fun parse(code: CharSequence): KmpNode = materialize(parseSyntax(code, isScript = true), code.toString())

  /**
   * Parses [code] using the regular file grammar (not script mode). This matches the structure the
   * PSI parser produces for ordinary `.kt` files, which the formatting visitor relies on.
   */
  fun parseFile(code: CharSequence): KmpNode =
      materialize(parseSyntax(code, isScript = false), code.toString())

  private fun parseSyntax(code: CharSequence, isScript: Boolean): SyntaxNode {
    val parser = KotlinParser(isScript = isScript, isLazy = false)
    return kmpParse(
        text = code,
        lexerFactory = { KotlinLexer() },
        parser = parser::parse,
        whitespaces = KtTokens.WHITESPACES,
        comments = KtTokens.COMMENTS,
        documentLanguage = KOTLIN_LANGUAGE,
        languageProvider = NO_LANGUAGE_PROVIDER,
        whitespaceOrCommentBindingPolicy = parser.whitespaceOrCommentBindingPolicy,
    )
  }

  /** Walks the lazy [SyntaxNode] tree exactly once, building the persistent [KmpNode] mirror. */
  private fun materialize(root: SyntaxNode, code: String): KmpNode {
    val node =
        KmpNode(
            type = root.type,
            startOffset = root.startOffset,
            endOffset = root.endOffset,
            // errorMessage is only needed for error nodes; computing it lazily-only avoids the cost
            // for the overwhelming majority of nodes.
            errorMessage = if (root.type == SyntaxTokenTypes.ERROR_ELEMENT) root.errorMessage else null,
            code = code,
        )
    var child = root.firstChild()
    var index = 0
    while (child != null) {
      val materialized = materialize(child, code)
      materialized.parent = node
      materialized.indexInParent = index
      node.childList.add(materialized)
      index++
      child = child.nextSibling()
    }
    return node
  }
}

/**
 * Returns the first error node (in document/pre-order), or null if the tree has no syntax errors.
 * Mirrors the PSI parser's `collectDescendantsOfType<PsiErrorElement>().firstOrNull()`.
 */
internal fun KmpNode.firstErrorOrNull(): KmpNode? {
  if (type == SyntaxTokenTypes.ERROR_ELEMENT) return this
  val kids = childList
  for (i in kids.indices) {
    kids[i].firstErrorOrNull()?.let {
      return it
    }
  }
  return null
}

/** Direct children of this node, in document order (including whitespace and comment leaves). */
internal fun KmpNode.children(): List<KmpNode> = childList

/** This node and all of its descendants, in pre-order. */
internal fun KmpNode.descendants(): List<KmpNode> {
  val result = ArrayList<KmpNode>()
  collectDescendantsInto(result)
  return result
}

private fun KmpNode.collectDescendantsInto(out: MutableList<KmpNode>) {
  val kids = childList
  for (i in kids.indices) {
    out.add(kids[i])
    kids[i].collectDescendantsInto(out)
  }
}

/** Whether any ancestor of this node has the given [type]. */
internal fun KmpNode.hasAncestorOfType(type: SyntaxElementType): Boolean {
  var node = parent()
  while (node != null) {
    if (node.type == type) return true
    node = node.parent()
  }
  return false
}

/** Whitespace or comment leaf. */
internal fun KmpNode.isTrivia(): Boolean =
    type in KtTokens.WHITESPACES || type in KtTokens.COMMENTS

/** Direct children excluding whitespace and comments, in document order. */
internal fun KmpNode.meaningfulChildren(): List<KmpNode> = meaningfulChildrenCached()

/** Next sibling that is neither whitespace nor a comment. */
internal fun KmpNode.nextMeaningfulSibling(): KmpNode? {
  var n = nextSibling()
  while (n != null && n.isTrivia()) n = n.nextSibling()
  return n
}

/** Previous sibling that is neither whitespace nor a comment. */
internal fun KmpNode.prevMeaningfulSibling(): KmpNode? {
  var n = prevSibling()
  while (n != null && n.isTrivia()) n = n.prevSibling()
  return n
}

/** The deepest last leaf, skipping trailing whitespace/comments (mirror of PSI helper). */
internal fun KmpNode.lastMeaningfulLeaf(): KmpNode {
  var child = lastChild()
  while (child != null) {
    if (child.isTrivia()) {
      child = child.prevSibling()
    } else {
      return child.lastMeaningfulLeaf()
    }
  }
  return this
}
