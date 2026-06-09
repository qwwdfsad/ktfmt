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

import com.facebook.ktfmt.debughelpers.printOps
import com.facebook.ktfmt.format.RedundantElementManager.addRedundantElements
import com.facebook.ktfmt.format.RedundantElementManager.dropRedundantElements
import com.facebook.ktfmt.format.WhitespaceTombstones.indexOfWhitespaceTombstone
import com.facebook.ktfmt.kdoc.Escaping
import com.facebook.ktfmt.kdoc.KDocCommentsHelper
import com.google.common.collect.ImmutableList
import com.google.common.collect.Range
import com.google.googlejavaformat.Doc
import com.google.googlejavaformat.DocBuilder
import com.google.googlejavaformat.Newlines
import com.google.googlejavaformat.OpsBuilder
import com.google.googlejavaformat.java.FormatterException
import com.google.googlejavaformat.java.JavaOutput
import fleet.org.jetbrains.kotlin.kmp.lexer.KtTokens
import fleet.org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtilRt.convertLineSeparators

object Formatter {

  @JvmField
  val META_FORMAT =
      FormattingOptions(
          blockIndent = 2,
          continuationIndent = 4,
          trailingCommaManagementStrategy = TrailingCommaManagementStrategy.ONLY_ADD,
      )

  @JvmField
  val GOOGLE_FORMAT =
      FormattingOptions(
          blockIndent = 2,
          continuationIndent = 2,
      )

  /** A format that attempts to reflect https://kotlinlang.org/docs/coding-conventions.html. */
  @JvmField
  val KOTLINLANG_FORMAT =
      FormattingOptions(
          blockIndent = 4,
          continuationIndent = 4,
      )

  /**
   * format formats the Kotlin code given in 'code' and returns it as a string. This method is
   * accessed through Reflection.
   */
  @JvmStatic
  @Throws(FormatterException::class, ParseError::class)
  fun format(code: String): String = format(META_FORMAT, code)

  /**
   * format formats the Kotlin code given in 'code' with 'removeUnusedImports' and returns it as a
   * string. This method is accessed through Reflection.
   */
  @JvmStatic
  @Throws(FormatterException::class, ParseError::class)
  fun format(code: String, removeUnusedImports: Boolean): String =
      format(META_FORMAT.copy(removeUnusedImports = removeUnusedImports), code)

  /**
   * format formats the Kotlin code given in 'code' with the 'maxWidth' and returns it as a string.
   */
  @JvmStatic
  @Throws(FormatterException::class, ParseError::class)
  fun format(options: FormattingOptions, code: String): String {
    val (shebang, kotlinCode) =
        if (code.startsWith("#!")) {
          code.split("\n", limit = 2)
        } else {
          listOf("", code)
        }
    checkEscapeSequences(kotlinCode)

    val context = FormatterContext(convertLineSeparators(kotlinCode))
    checkParseError(context.code, context.tree)

    return context
        .transform { code, tree -> sortedAndDistinctImports(code, tree) }
        .transform { code, tree -> dropRedundantElements(code, options, tree) }
        .transform { code, tree -> addRedundantElements(code, options, tree) }
        .transform { code, tree -> prettyPrint(code, tree, options, lineSeparator = "\n") }
        .transform { code, tree -> addRedundantElements(code, options, tree) }
        .transform { code, _ -> MultilineStringFormatter(options.continuationIndent).format(code) }
        .code
        .let { convertLineSeparators(it, checkNotNull(Newlines.guessLineSeparator(kotlinCode))) }
        .let { if (shebang.isEmpty()) it else shebang + "\n" + it }
  }

  /** prettyPrint reflows 'code' using google-java-format's engine. */
  private fun prettyPrint(
      code: String,
      tree: KmpNode,
      options: FormattingOptions,
      lineSeparator: String,
  ): String {
    val kotlinInput = KotlinInput.fromKmp(code, tree)
    val javaOutput =
        JavaOutput(lineSeparator, kotlinInput, KDocCommentsHelper(lineSeparator, options.maxWidth))
    val builder = OpsBuilder(kotlinInput, javaOutput)
    KmpAstVisitor(options, builder, code).visitFile(tree)
    builder.sync(kotlinInput.text.length)
    builder.drain()
    val ops = builder.build()
    if (options.debuggingPrintOpsAfterFormatting) {
      printOps(ops)
    }
    val doc = DocBuilder().withOps(ops).build()
    doc.computeBreaks(javaOutput.commentsHelper, options.maxWidth, Doc.State(+0, 0))
    doc.write(javaOutput)
    javaOutput.flush()

    val tokenRangeSet =
        kotlinInput.characterRangesToTokenRanges(ImmutableList.of(Range.closedOpen(0, code.length)))
    return WhitespaceTombstones.replaceTombstoneWithTrailingWhitespace(
        JavaOutput.applyReplacements(code, javaOutput.getFormatReplacements(tokenRangeSet))
    )
  }

  private fun checkEscapeSequences(code: String) {
    var index = code.indexOfWhitespaceTombstone()
    if (index == -1) {
      index = Escaping.indexOfCommentEscapeSequences(code)
    }
    if (index != -1) {
      throw ParseError(
          "ktfmt does not support code which contains one of {\\u0003, \\u0004, \\u0005} character" +
              "; escape it",
          StringUtil.offsetToLineColumn(code, index),
      )
    }
  }

  /**
   * Detects syntax errors using the lightweight multiplatform parser and reports the first one as a
   * [ParseError], mirroring what the old PSI `Parser.parse` did (script grammar, first error in
   * document order, 0-based line/column).
   */
  private fun checkParseError(code: String, tree: KmpNode) {
    val error = tree.firstErrorOrNull() ?: return
    throw ParseError(
        error.errorMessage ?: "Syntax error",
        StringUtil.offsetToLineColumn(code, error.startOffset),
    )
  }

  private fun sortedAndDistinctImports(code: String, tree: KmpNode): String {
    // Consume the new multiplatform parser's lightweight syntax tree instead of PSI.
    val importList =
        tree.children().firstOrNull { it.type == KtNodeTypes.IMPORT_LIST } ?: return code
    val imports = importList.children().filter { it.type == KtNodeTypes.IMPORT_DIRECTIVE }.toList()
    if (imports.isEmpty()) {
      return code
    }

    val commentList = mutableListOf<KmpNode>()
    // Find non-import elements; comments are moved, in order, to the top of the import list. Other
    // non-import elements throw a ParseError.
    for (element in importList.children()) {
      when {
        element.type == KtTokens.EOL_COMMENT || element.type == KtTokens.BLOCK_COMMENT ->
            commentList.add(element)
        element.type == KtNodeTypes.IMPORT_DIRECTIVE || element.type in KtTokens.WHITESPACES -> {}
        else ->
            throw ParseError(
                "Imports not contiguous: " + element.text,
                StringUtil.offsetToLineColumn(code, element.startOffset),
            )
      }
    }
    fun canonicalText(importDirective: KmpNode): String {
      val parts = importDirective.children().toList()
      val fqName =
          parts
              .firstOrNull {
                it.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
                    it.type == KtNodeTypes.REFERENCE_EXPRESSION
              }
              ?.text
              ?.toString()
              ?.replace("`", "")
      val alias =
          parts.firstOrNull { it.type == KtNodeTypes.IMPORT_ALIAS }?.text?.toString()?.replace("`", "")
      val isAllUnder = parts.any { it.type == KtTokens.MUL }
      return fqName + " " + alias + " " + if (isAllUnder) "*" else ""
    }

    val sortedImports = imports.sortedBy(::canonicalText).distinctBy(::canonicalText)
    val importsWithComments = commentList + sortedImports

    val body = importsWithComments.joinToString(separator = "\n") { imprt -> imprt.text }
    /*
     * Kludge: idempotent formatting.
     * This step optimizes the following goal -- producing **identical** code for already formatted
     * code, as it's important for PSI-reuse.
     * There is exactly one case where this step should add trailing newline -- when an inline
     * comment follows the last import statement. We check for that (note it gives false positives for `/* // */`
     * which is acceptable -- later prettyPrint step will fix that) and avoid extra-append when it is redundant.
     */
    val needsTerminator = body.lastIndexOf('\n').let { it >= 0 && body.indexOf("//", it + 1) >= 0 }
    return code.replaceRange(
        importList.startOffset,
        importList.endOffset,
        if (needsTerminator) body + "\n" else body,
    )
  }
}
