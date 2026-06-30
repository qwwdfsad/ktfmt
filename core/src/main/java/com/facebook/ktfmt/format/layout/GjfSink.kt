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

package com.facebook.ktfmt.format.layout

import com.facebook.ktfmt.format.FenceCommentsOp
import com.google.googlejavaformat.Doc
import com.google.googlejavaformat.OpsBuilder
import com.google.googlejavaformat.Output
import java.util.Optional
import com.google.googlejavaformat.Indent as GjfIndent

/**
 * A [LayoutSink] backed by google-java-format: it translates ktfmt-native layout vocabulary into
 * gjf ops on an [OpsBuilder]. This keeps the existing (gjf) pipeline behaving exactly as before
 * while the visitor speaks only native types — the adapter that makes gjf swappable.
 */
class GjfSink(private val builder: OpsBuilder) : LayoutSink {

  // Native BreakTags are identity tokens; each maps to one gjf BreakTag for the lifetime of a file.
  private val tags = HashMap<BreakTag, Output.BreakTag>()

  private fun gjf(tag: BreakTag): Output.BreakTag = tags.getOrPut(tag) { Output.BreakTag() }

  private fun gjf(fillMode: FillMode): Doc.FillMode =
      when (fillMode) {
        FillMode.UNIFIED -> Doc.FillMode.UNIFIED
        FillMode.INDEPENDENT -> Doc.FillMode.INDEPENDENT
        FillMode.FORCED -> Doc.FillMode.FORCED
      }

  private fun gjf(value: RealOrImaginary): Doc.Token.RealOrImaginary =
      when (value) {
        RealOrImaginary.REAL -> Doc.Token.RealOrImaginary.REAL
        RealOrImaginary.IMAGINARY -> Doc.Token.RealOrImaginary.IMAGINARY
      }

  private fun gjf(wanted: BlankLineWanted): OpsBuilder.BlankLineWanted =
      when (wanted) {
        BlankLineWanted.YES -> OpsBuilder.BlankLineWanted.YES
        BlankLineWanted.NO -> OpsBuilder.BlankLineWanted.NO
        BlankLineWanted.PRESERVE -> OpsBuilder.BlankLineWanted.PRESERVE
      }

  private fun gjf(indent: Indent): GjfIndent =
      when (indent) {
        is Indent.Const -> GjfIndent.Const.make(indent.n, 1)
        is Indent.If ->
            GjfIndent.If.make(gjf(indent.condition), gjf(indent.thenIndent), gjf(indent.elseIndent))
      }

  override fun open(plusIndent: Indent) = builder.open(gjf(plusIndent))

  override fun close() = builder.close()

  override fun token(
      text: String,
      realOrImaginary: RealOrImaginary,
      plusIndentCommentsBefore: Indent,
      breakAndIndentTrailingComment: Optional<Indent>,
  ) =
      builder.token(
          text,
          gjf(realOrImaginary),
          gjf(plusIndentCommentsBefore),
          breakAndIndentTrailingComment.map { gjf(it) },
      )

  override fun guessToken(token: String) = builder.guessToken(token)

  override fun space() = builder.space()

  override fun breakOp(
      fillMode: FillMode,
      flat: String,
      plusIndent: Indent,
      optTag: Optional<BreakTag>,
  ) = builder.breakOp(gjf(fillMode), flat, gjf(plusIndent), optTag.map { gjf(it) })

  override fun forcedBreak(plusIndent: Indent) = builder.forcedBreak(gjf(plusIndent))

  override fun blankLineWanted(wanted: BlankLineWanted) = builder.blankLineWanted(gjf(wanted))

  override fun sync(inputPosition: Int) = builder.sync(inputPosition)

  override fun markForPartialFormat() = builder.markForPartialFormat()

  override fun fenceComments() = builder.addAll(FenceCommentsOp.AS_LIST)
}
