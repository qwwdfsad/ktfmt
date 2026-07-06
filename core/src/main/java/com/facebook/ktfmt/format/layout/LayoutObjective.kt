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

// ================================================================================================
// RULES §1 — THE LAYOUT OBJECTIVE  (the "which layout is best?" decision, in one file)
//
// optofmt lays a region out by GLOBAL OPTIMIZATION: the engine ([NativeRenderer], in
// NativeEngine.kt) enumerates every legal combination of line breaks and keeps the ONE layout that
// scores lowest. This file defines what "scores lowest" means, and nothing else — so it is the one
// place to read, or change, to understand or tune how breaks are chosen.
//
// How the pieces fit:
//   • [Metrics]         — the handful of numbers measured about a finished layout (how far it
//                         overflows, how many lines, how deep it wraps).
//   • [LayoutObjective] — turns those numbers into a score; smaller is better. This is the knob.
//   • [Objectives]      — ready-made objectives, including the shipping default and a menu of
//                         alternatives drawn from other formatters to experiment with.
//
// To try a different criterion, pass a different [LayoutObjective] to [NativeRenderer] — see
// Formatter.prettyPrint, which passes [Objectives.DEFAULT]. The rest of the engine (candidate
// generation, the Pareto search, text emission) is entirely objective-agnostic.
//
// ONE INVARIANT (see [LayoutObjective] for the full "why"): an objective must be monotone
// non-decreasing in every [Metrics] field. All current metrics are also monotone in the open-line
// column, which is what lets the search prune safely; a metric that isn't (e.g. Knuth–Plass's
// per-line slack², which grows as a line gets shorter) cannot be added without weakening the search
// — see the note on [Objectives.PENALTY].
// ================================================================================================

/**
 * The metrics of a *finished* candidate layout (its still-open last line folded in as the final
 * line). Every field is ≥ 0 and "smaller is better". A [LayoutObjective] scores a layout from these.
 *
 * These five numbers are everything the objective gets to see; adding a new axis means adding a
 * field here (and computing it in the engine's `Layout` accumulators). Given a 100-column limit,
 * "overflow" of a line is `max(0, lineWidth - 100)`.
 */
class Metrics
internal constructor(
    /** The widest single line's overshoot past the column limit (RULES §1.1). */
    val worstOverflow: Int,
    /** How many lines overflow the column limit (RULES §1.2). */
    val overflowLines: Int,
    /** Summed overshoot across every overflowing line — an alternative to [worstOverflow]. */
    val totalOverflow: Int,
    /** Total number of lines (RULES §1.3). */
    val lines: Int,
    /** Deepest indentation any wrapped line starts at (RULES §1.4, "shallowest deepest break"). */
    val deepestIndent: Int,
    /**
     * How many introducer breaks the layout takes — a break right after an `=`/`:`/`->` that detaches
     * the introducer from its opener (RULES §3). Zero means every introducer stayed attached. Ranked
     * below overflow but above [lines] in [Objectives.DEFAULT], so §3-attachment wins whenever both
     * arrangements fit, while overflow still forces a break. Monotone and independent of the open-line
     * column (it counts already-taken forced breaks), so it is safe for the Pareto search.
     */
    val introducerBreaks: Int,
    /**
     * How many break-after-`=` introducer breaks are taken specifically for a CALL-CHAIN right-hand
     * side (RULES §3/§7). Unlike [introducerBreaks] this is ranked BELOW [lines] in
     * [Objectives.DEFAULT], so it only breaks a tie: when attaching a chain's receiver-through-first-
     * call to the introducer and breaking after it cost the same number of lines, §3 prefers the
     * attached one — but when attaching would need MORE lines (it would have to tear the first call's
     * arguments to fit), the lower line count wins first, so the chain breaks after `=` instead of
     * tearing (the commonConfiguration case). Monotone and column-independent, so Pareto-safe.
     */
    val chainIntroducerBreaks: Int,
)

/**
 * Scores a finished layout as a [DoubleArray] compared lexicographically (element 0 first), smaller
 * is better. Swap the objective handed to [NativeRenderer] to try different §1 criteria.
 *
 * The vector form is deliberately general — it subsumes the two shapes you'd want:
 * - a **single weighted score** (return length 1): `doubleArrayOf(2.0 * m.worstOverflow + m.lines)`,
 *   or use [Objectives.weighted]. Coefficients can be any non-negative doubles.
 * - **lexicographic priority tiers** (return length N): strict "this matters before that", exact —
 *   which a single scalar can't represent faithfully (big-weight encodings lose precision once the
 *   products exceed a double's 2^53 exact range, and our metrics reach a few thousand).
 * You can also mix: a weighted sub-score per tier.
 *
 * CONSTRAINT: the score must be monotone non-decreasing in every [Metrics] field — a layout that is
 * worse on one metric and no better on the rest must not score lower (so: non-negative weights).
 * The Pareto search prunes a candidate only when another is ≤ it on all metrics, so a non-monotone
 * objective could prune the very layout it would prefer. A non-monotone term is only safe as a pure
 * tiebreaker in a lower lexicographic slot than every monotone one.
 */
fun interface LayoutObjective {
  fun cost(m: Metrics): DoubleArray
}

/**
 * Ready-made objectives. [DEFAULT] is optofmt's shipping RULES §1; the rest are a menu of
 * alternatives — several mirror how other formatters choose breaks — to experiment with.
 */
object Objectives {
  /** Lexicographic tiers from metric values (helper for the priority-ordered objectives below). */
  private fun lex(vararg tiers: Int): DoubleArray = DoubleArray(tiers.size) { tiers[it].toDouble() }

  /**
   * The full RULES §1, in order: minimize the worst overflow, then the number of overflowing lines;
   * then (RULES §3) keep introducers attached — prefer the layout that detaches fewer `=`/`:`/`->`
   * introducers from their openers; then the total number of lines; then the shallowest deepest wrap
   * (§1.4 flatness tiebreak). §3 sits above line-count so `val x = object : Super(` stays attached
   * with its args wrapped rather than breaking after `:` to save a line, but below overflow so a
   * genuinely-too-wide attachment still breaks.
   */
  val DEFAULT =
      LayoutObjective {
        lex(
            it.worstOverflow,
            it.overflowLines,
            it.introducerBreaks,
            it.lines,
            it.chainIntroducerBreaks,
            it.deepestIndent)
      }

  /** RULES §1 without the §1.4 flatness tiebreak (worst overflow, overflowing lines, then lines). */
  val NO_FLATNESS_TIEBREAK = LayoutObjective { lex(it.worstOverflow, it.overflowLines, it.lines) }

  /** Knuth–Plass-flavored: minimize *summed* overflow rather than just the single worst line. */
  val TOTAL_OVERFLOW = LayoutObjective { lex(it.totalOverflow, it.overflowLines, it.lines) }

  /** Compactness-first: after avoiding overflow, prefer the fewest lines above all else. */
  val FEWEST_LINES = LayoutObjective { lex(it.worstOverflow, it.lines, it.overflowLines) }

  /**
   * clang-format's model: a single penalty sum, with overflow as a *soft* cost (per excess
   * character, à la clang's `PenaltyExcessCharacter`) rather than an absolute prohibition, plus a
   * small per-line cost. Unlike [DEFAULT], this will *accept* overflow when wrapping would cost more
   * lines — so a line a couple of columns over can win over a multi-line split. Both terms are
   * monotone in the metrics, so the Pareto search stays sound. Tune the weights to taste.
   *
   * NOTE: a Knuth–Plass "least-ragged" objective (minimize Σ of each line's slack²) is intentionally
   * NOT offered: slack² grows as a line's start column shrinks, i.e. it is anti-monotone in the
   * open-line column, which contradicts the pruning invariant (a shorter open line is always
   * treated as no worse for the future). It would silently yield suboptimal layouts here; TeX can
   * use it only because it keeps a full breakpoint DP with no such pruning.
   */
  val PENALTY = weighted(totalOverflow = 1000.0, lines = 1.0)

  /**
   * A single weighted score `Σ coefficient·metric` (all coefficients default to 0, i.e. ignored).
   * Handy for tuning tradeoffs by dialing numbers, e.g.
   * `Objectives.weighted(worstOverflow = 1000.0, overflowLines = 10.0, lines = 1.0)`. Keep every
   * coefficient ≥ 0 so the score stays monotone in the metrics (see [LayoutObjective]).
   */
  fun weighted(
      worstOverflow: Double = 0.0,
      overflowLines: Double = 0.0,
      totalOverflow: Double = 0.0,
      lines: Double = 0.0,
      deepestIndent: Double = 0.0,
  ): LayoutObjective = LayoutObjective {
    doubleArrayOf(
        worstOverflow * it.worstOverflow +
            overflowLines * it.overflowLines +
            totalOverflow * it.totalOverflow +
            lines * it.lines +
            deepestIndent * it.deepestIndent)
  }
}
