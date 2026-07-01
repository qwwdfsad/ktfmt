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
import fleet.org.jetbrains.kotlin.kmp.parser.KDocParseNodes
import fleet.org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/** Finds unused imports in the new multiplatform parser's syntax tree. */
internal class RedundantImportDetector(val enabled: Boolean) {
  companion object {
    private val OPERATORS =
        setOf(
            "unaryPlus", "unaryMinus", "not", "inc", "dec", "plus", "minus", "times", "div", "rem",
            "mod", "rangeTo", "contains", "get", "set", "invoke", "plusAssign", "minusAssign",
            "timesAssign", "divAssign", "remAssign", "modAssign", "equals", "compareTo", "iterator",
            "next", "hasNext", "and", "or", "getValue", "setValue", "provideDelegate", "assign")

    private val COMPONENT_OPERATOR_REGEX = Regex("component\\d+")
    private val KDOC_TAG_SKIP_FIRST_REFERENCE_REGEX = Regex("^@(param|property) (.+)", RegexOption.DOT_MATCHES_ALL)
  }

  private var thisPackage: String = ""
  private val usedReferences = OPERATORS.toMutableSet()
  private val candidates = mutableListOf<KmpNode>()

  /** Walk the whole file: collect the package, import candidates, and all used references. */
  fun analyze(root: KmpNode) {
    if (!enabled) return

    val packageDirective = root.children().firstOrNull { it.type == KtNodeTypes.PACKAGE_DIRECTIVE }
    thisPackage = packageDirective?.pathSegments()?.joinToString(".") ?: ""

    val importList = root.children().firstOrNull { it.type == KtNodeTypes.IMPORT_LIST }
    importList?.meaningfulChildren()?.forEach { directive ->
      if (directive.type != KtNodeTypes.IMPORT_DIRECTIVE) return@forEach
      val identifier = directive.importIdentifier() ?: return@forEach
      if (identifier !in OPERATORS && !COMPONENT_OPERATOR_REGEX.matches(identifier)) {
        candidates += directive
      }
    }

    for (top in root.children()) {
      if (top.type == KtNodeTypes.PACKAGE_DIRECTIVE || top.type == KtNodeTypes.IMPORT_LIST) continue
      for (node in listOf(top) + top.descendants()) {
        when (node.type) {
          KtNodeTypes.REFERENCE_EXPRESSION -> usedReferences += node.text.toString().trim('`')
          KDocParseNodes.KDOC_SECTION -> collectKdocReferences(node)
        }
      }
    }
  }

  private fun collectKdocReferences(section: KmpNode) {
    fun firstSegment(name: KmpNode): String =
        name.text.toString().trim().substringBefore('.').trim('[', ']', '`')

    for (child in section.meaningfulChildren()) {
      if (child.type == KDocParseNodes.KDOC_TAG) {
        val names = child.descendants().filter { it.type == KDocParseNodes.KDOC_NAME }.toList()
        val use =
            if (KDOC_TAG_SKIP_FIRST_REFERENCE_REGEX.matches(child.text.toString())) names.drop(1)
            else names
        use.forEach { usedReferences += firstSegment(it) }
      }
    }
    // Names directly under the section (not inside a tag).
    section
        .descendants()
        .filter { it.type == KDocParseNodes.KDOC_NAME && !it.hasAncestorOfType(KDocParseNodes.KDOC_TAG) }
        .forEach { usedReferences += firstSegment(it) }
  }

  fun getRedundantImportElements(): List<KmpNode> {
    if (!enabled) return emptyList()

    val identifierCounts = candidates.groupingBy { it.importIdentifier() }.eachCount()

    return candidates.filter { directive ->
      val identifier = directive.importIdentifier()
      val isUsed = identifier in usedReferences
      val segments = directive.pathSegments()
      // `import `foo.bar.baz`` parses as a single name containing dots.
      val isBracketEscapedPath = segments.size == 1 && segments[0].contains('.')
      val parentPackage = if (segments.size <= 1) "" else segments.dropLast(1).joinToString(".")
      val isFromThisPackage = !isBracketEscapedPath && parentPackage == thisPackage
      val hasAlias = directive.children().any { it.type == KtNodeTypes.IMPORT_ALIAS }
      val isOverload = (identifierCounts[identifier] ?: 0) > 1
      !isUsed || (isFromThisPackage && !hasAlias && !isOverload)
    }
  }
}

/**
 * The dotted name segments of a package/import directive, taken from the [REFERENCE_EXPRESSION]
 * leaves (so they are free of the inter-token whitespace the raw composite text may contain), with
 * backticks stripped.
 */
private fun KmpNode.pathSegments(): List<String> {
  val path =
      children().firstOrNull {
        it.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
            it.type == KtNodeTypes.REFERENCE_EXPRESSION
      } ?: return emptyList()
  return (listOf(path) + path.descendants())
      .filter { it.type == KtNodeTypes.REFERENCE_EXPRESSION }
      .map { it.text.toString().trim().trim('`') }
}

/** The import's short name (alias if present, else last path segment). */
private fun KmpNode.importIdentifier(): String? {
  // Star imports have no imported short name, so they are never cleanup candidates.
  if (children().any { it.type == KtTokens.MUL }) return null
  val alias =
      children()
          .firstOrNull { it.type == KtNodeTypes.IMPORT_ALIAS }
          ?.children()
          ?.lastOrNull { it.type == KtTokens.IDENTIFIER }
          ?.text
          ?.toString()
          ?.trim('`')
  if (alias != null) return alias
  val last = pathSegments().lastOrNull() ?: return null
  // `import `foo.bar.baz`` is a single name containing dots; match the last segment.
  val dot = last.lastIndexOf('.')
  return if (dot >= 0) last.substring(dot + 1) else last
}
