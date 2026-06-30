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

import java.util.Optional

/**
 * The vocabulary `KmpAstVisitor` uses to describe layout, expressed in ktfmt-owned types instead of
 * google-java-format's. This is the seam that decouples the visitor from gjf: a [LayoutSink] can be
 * backed by gjf ([GjfSink], which translates these calls into gjf ops) or by a native, gjf-free
 * engine. The method set mirrors the subset of `OpsBuilder` the visitor actually drives.
 */
interface LayoutSink {
  /** Open a level whose breaks indent by [plusIndent] relative to the enclosing level. */
  fun open(plusIndent: Indent)

  /** Close the most recently opened level. */
  fun close()

  /**
   * Emit a real source token [text]. [plusIndentCommentsBefore] indents comments that precede it;
   * [breakAndIndentTrailingComment], when present, forces a break + indent for a trailing comment.
   */
  fun token(
      text: String,
      realOrImaginary: RealOrImaginary,
      plusIndentCommentsBefore: Indent,
      breakAndIndentTrailingComment: Optional<Indent>,
  )

  /** Emit [token] only if it actually appears next in the source (e.g. an optional `;`/`,`). */
  fun guessToken(token: String)

  /** A non-breaking space. */
  fun space()

  /** A candidate line break; becomes [flat] when not taken, a newline + [plusIndent] when taken. */
  fun breakOp(
      fillMode: FillMode,
      flat: String,
      plusIndent: Indent,
      optTag: Optional<BreakTag> = Optional.empty(),
  )

  /** A break that is always taken. */
  fun forcedBreak(plusIndent: Indent = Indent.Const.ZERO)

  /** Request blank-line handling between the previous and next output. */
  fun blankLineWanted(wanted: BlankLineWanted)

  /** Synchronize emitted output with the source up to [inputPosition] (comment interleaving). */
  fun sync(inputPosition: Int)

  /** Mark a boundary at which partial (range) formatting may begin. */
  fun markForPartialFormat()

  /** Emit a fence that stops a leading comment from floating up into a parent level. */
  fun fenceComments()
}

/** How a [LayoutSink.breakOp] behaves when its enclosing level is broken. Mirrors gjf's FillMode. */
enum class FillMode {
  UNIFIED,
  INDEPENDENT,
  FORCED,
}

/** Whether an emitted token corresponds to real source. Mirrors gjf's RealOrImaginary. */
enum class RealOrImaginary {
  REAL,
  IMAGINARY,
}

/** Blank-line policy between two pieces of output. Mirrors gjf's BlankLineWanted. */
enum class BlankLineWanted {
  YES,
  NO,
  PRESERVE,
}

/** An identity token recording whether a particular [LayoutSink.breakOp] was broken, so a
 * conditional [Indent.If] can depend on it. Mirrors gjf's Output.BreakTag. */
class BreakTag

/**
 * An indentation amount for a level or break: either a constant, or a value conditional on whether
 * a [BreakTag]'s break was taken. Mirrors gjf's Indent (Const / If) but is gjf-free.
 */
sealed class Indent {
  class Const private constructor(val n: Int) : Indent() {
    companion object {
      @JvmField val ZERO: Const = Const(0)

      fun make(n: Int, indentMultiplier: Int): Const = Const(n * indentMultiplier)
    }
  }

  class If
  private constructor(val condition: BreakTag, val thenIndent: Indent, val elseIndent: Indent) :
      Indent() {
    companion object {
      fun make(condition: BreakTag, thenIndent: Indent, elseIndent: Indent): If =
          If(condition, thenIndent, elseIndent)
    }
  }
}
