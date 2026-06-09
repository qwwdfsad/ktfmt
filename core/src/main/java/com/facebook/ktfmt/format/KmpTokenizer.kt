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
import java.util.regex.Pattern
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.lexer.KtTokens as PsiKtTokens

/**
 * SPIKE: the kmp-tree analog of [Tokenizer]. Walks the new multiplatform parser's lightweight syntax
 * tree and produces the same `List<KotlinTok>` that [Tokenizer] produces from PSI, so the existing
 * google-java-format [com.google.googlejavaformat.OpsBuilder] engine can be driven without PSI.
 *
 * Mirrors [Tokenizer]'s rules: string templates and comments are emitted as single toks, whitespace
 * is split into newline/space runs, and every other leaf becomes one tok. `kind` is unused
 * downstream (always `EOF`), exactly as in [Tokenizer].
 */
object KmpTokenizer {
  private val WHITESPACE_NEWLINE_REGEX: Pattern = Pattern.compile("\\R|( )+")

  /**
   * Returns the toks (excluding the synthetic EOF) and the number of numbered (isToken) toks. [root]
   * is the already-parsed syntax tree for [code], reused to avoid re-parsing.
   */
  fun tokenize(code: String, root: KmpNode = KmpAst.parse(code)): Pair<List<KotlinTok>, Int> {
    val toks = mutableListOf<KotlinTok>()
    var index = 0

    fun walk(node: KmpNode) {
      val type = node.type
      val text = node.text.toString()
      when {
        type in KtTokens.COMMENTS -> {
          if (text.startsWith("/*") && !text.endsWith("*/")) {
            throw ParseError(
                "Unclosed comment", StringUtil.offsetToLineColumn(code, node.startOffset))
          }
          // Block comments inside statement-less lambda bodies are treated as tokens so the visitor
          // can position them with proper break structure (mirrors PSI Tokenizer).
          val parent = node.parent()
          val inLambdaBody =
              parent?.type == KtNodeTypes.BLOCK &&
                  parent.parent()?.type == KtNodeTypes.FUNCTION_LITERAL
          val treatAsToken =
              text.startsWith("/*") && inLambdaBody && parent!!.meaningfulChildren().isEmpty()
          toks.add(KotlinTok(index, text, text, node.startOffset, 0, treatAsToken, PsiKtTokens.EOF))
          index++
        }
        type == KtNodeTypes.STRING_TEMPLATE -> {
          toks.add(
              KotlinTok(
                  index,
                  WhitespaceTombstones.replaceTrailingWhitespaceWithTombstone(text),
                  text,
                  node.startOffset,
                  0,
                  true,
                  PsiKtTokens.EOF))
          index++
        }
        node.firstChild() == null -> { // leaf token
          if (type in KtTokens.WHITESPACES) {
            val matcher = WHITESPACE_NEWLINE_REGEX.matcher(text)
            while (matcher.find()) {
              val piece = matcher.group()
              toks.add(
                  KotlinTok(
                      -1,
                      code.substring(
                          node.startOffset + matcher.start(), node.startOffset + matcher.end()),
                      piece,
                      node.startOffset + matcher.start(),
                      0,
                      false,
                      PsiKtTokens.EOF))
            }
          } else if (text.isNotEmpty()) {
            toks.add(KotlinTok(index, text, text, node.startOffset, 0, true, PsiKtTokens.EOF))
            index++
          }
        }
        else -> for (child in node.children()) walk(child)
      }
    }

    walk(root)
    return toks to index
  }
}
