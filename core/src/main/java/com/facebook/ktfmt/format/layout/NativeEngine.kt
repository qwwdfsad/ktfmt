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
 * A gjf-free layout engine: a [LayoutSink] that records the visitor's emissions into a small
 * document IR, plus a renderer that lays it out and produces the formatted text directly — no
 * google-java-format on this path.
 *
 * This is the experimental engine that makes ktfmt independent of gjf in principle. It is the
 * engine used by the `optofmt` style ([com.facebook.ktfmt.format.FormattingOptions.optofmt]); no
 * other style uses it. It handles structural layout (levels, breaks with fill modes, indentation,
 * blank lines), comment interleaving (leading/trailing/standalone, KDoc not reflowed), and optional
 * punctuation. Still missing vs. gjf: partial/range formatting, and `KotlinInput` still implements
 * gjf's `Input` — the remaining work before gjf could be removed entirely.
 */
// ---- Document IR -------------------------------------------------------------------------------

internal sealed class NDoc

/** A nesting level whose taken breaks indent by [plusIndent] beyond the enclosing level. */
internal class NLevel(val plusIndent: Indent) : NDoc() {
  val children = ArrayList<NDoc>()
}

/** A candidate break (or, when [fillMode] is FORCED, a mandatory one). */
internal class NBreak(
    val fillMode: FillMode,
    val flat: String,
    val plusIndent: Indent,
    val tag: BreakTag?,
    // RULES §3: this break sits right after an introducer (`=`, `:`, `->`) — taking it detaches the
    // introducer from its opener. The objective penalizes taken introducer breaks below overflow but
    // above line-count, so an attach-and-wrap layout beats a break-after-introducer one whenever both
    // fit, but overflow still forces the break. See [Metrics.introducerBreaks].
    val introducer: Boolean = false,
    // RULES §3/§7: this break-after-`=` detaches a CALL-CHAIN right-hand side from its introducer.
    // Ranked BELOW line-count (unlike [introducer]) so it only breaks a tie in favor of attaching —
    // see [Metrics.chainIntroducerBreaks].
    val chainIntroducer: Boolean = false,
    // A FORCED break that is purely the chain's own inter-`.call` structure (§7), NOT a semantically
    // required break. It is safe to flatten (`a().b()` reads fine on one line), so [NFlat] may suppress
    // it — unlike a nested lambda-body statement break or an EOL comment, which must keep their lines.
    val chainStructural: Boolean = false,
    // RULES §14: text emitted at the END of the line this break closes, but ONLY when the break is
    // taken (the list wrapped) — a trailing comma. When the break is not taken (list stays flat) the
    // prefix vanishes, so a single-line list carries no comma. Flat width ignores it, so it never
    // pushes a list that fits into wrapping.
    val brokenPrefixText: String = "",
) : NDoc()

/** Literal text — a source token or a single space. */
internal class NText(val text: String) : NDoc()

/** A comment. An [eol] (`//`) comment must end its line, so it forces its level to break unless it
 * is the last thing in the level (where the following structural break ends the line). */
internal class NComment(val text: String, val eol: Boolean) : NDoc()

/** A blank-line request between surrounding output. */
internal class NBlank(val wanted: BlankLineWanted) : NDoc()

/**
 * Competing candidate layouts for the same content. The §1 optimizer renders whichever [alts]
 * entry has the lowest [LayoutObjective] cost at its position — this is how optofmt "considers
 * several formatting options and picks the best one." Each alternative is a fully-built subtree
 * (typically produced by [NativeSink.capture]); they share their inner content but arrange the
 * surrounding breaks differently (e.g. `= rhs` vs. break-after-`=`).
 */
internal class NAlt(val alts: List<NDoc>) : NDoc()

/**
 * A subtree forced to lay out flat (no internal breaks taken), used as one branch of an [NAlt] whose
 * validity depends on the child fitting on one line. If the flat form overflows, this candidate's
 * §1 cost carries that overflow, so the optimizer rejects it in favor of a sibling that wraps — e.g.
 * a chain's "hang the trailing lambda on the receiver's line" layout is only legal while the receiver
 * itself is single-line; wrapping it there would mis-indent the lambda body (see the trailing-lambda
 * chain handling in the visitor).
 */
internal class NFlat(val child: NDoc) : NDoc()

/**
 * A layout-transparent marker wrapping an introducer's right-hand side (`= <rhs>`, a named argument's
 * value, …). It changes nothing about how [child] lays out or renders; it only tags the completed
 * lines that fall INSIDE the RHS so the objective can count them ([Metrics.rhsWrapLines]). This lets
 * §1 prefer breaking after the introducer to keep the whole RHS on one clean line over attaching the
 * introducer and wrapping the RHS internally (a split `if/else`, a staircased chain), whenever both
 * arrangements otherwise tie — see the `brokenFlat` candidate in [KmpAstVisitor.emitIntroducerRhs].
 */
internal class NRhsBody(val child: NDoc) : NDoc()

/** A source leaf — a real token or a comment — with its position, used by [NativeSink] to track
 * token offsets and interleave comments. */
class SourceLeaf(val start: Int, val end: Int, val text: String, val isComment: Boolean)

/**
 * Records the visitor's [LayoutSink] calls into an [NLevel] tree, interleaving source comments at
 * the correct positions using a cursor over the source leaves.
 *
 * The visitor never emits comments; like gjf, they are pulled from the source. By advancing a
 * cursor over the source tokens as the visitor emits them, the sink knows each token's position,
 * so it can place a *leading* comment on its own line before the next token, and a *trailing*
 * same-line comment (`code // note`) right after the token that precedes it — before the break that
 * ends the line. Forced breaks are buffered ([pendingForced]) so a trailing comment slots in ahead
 * of them.
 */
class NativeSink(
    private val code: String = "",
    leaves: List<SourceLeaf> = emptyList(),
) : LayoutSink {
  internal val root = NLevel(Indent.Const.ZERO)
  private val stack = ArrayDeque<NLevel>().apply { addLast(root) }
  private val leaves = leaves.sortedBy { it.start }
  private var idx = 0
  private var lastTokenEnd = 0
  private var pendingForced: NBreak? = null

  private fun cur() = stack.last()

  private fun resolvePending() {
    pendingForced?.let { cur().children.add(it) }
    pendingForced = null
  }

  private fun add(doc: NDoc) {
    resolvePending()
    cur().children.add(doc)
  }

  /** True if there is no newline in the source between [a] and [b] (so a comment at [b] is on the
   * same line as code ending at [a]). */
  private fun sameLine(a: Int, b: Int): Boolean {
    var i = a
    while (i < b && i < code.length) {
      if (code[i] == '\n') return false
      i++
    }
    return true
  }

  /** Ensure the next comment starts on a fresh line: if nothing already forces a break and the
   * current line has content (e.g. a preceding trailing comment), force one. Prevents a leading
   * comment from being glued onto the previous comment/token. Returns whether the comment will sit
   * on its own line — false only when it is glued inline after a blank separator (e.g. the §9 space
   * after annotations), in which case it behaves like a trailing comment. */
  private fun ensureFreshLineForComment(): Boolean {
    if (pendingForced != null) return true
    val last = cur().children.lastOrNull() ?: return true
    if (!(last is NText && last.text.isBlank())) {
      pendingForced = NBreak(FillMode.FORCED, "", Indent.Const.ZERO, null)
      return true
    }
    return false
  }

  /** Emit leading comments at the cursor (each on its own line) up to [beforeOffset]. */
  private fun flushLeadingComments(beforeOffset: Int) {
    while (idx < leaves.size && leaves[idx].isComment && leaves[idx].start < beforeOffset) {
      val c = leaves[idx++]
      val text = c.text.trimEnd()
      val onOwnLine = ensureFreshLineForComment()
      resolvePending()
      cur().children.add(NComment(text, eol = text.startsWith("//")))
      lastTokenEnd = c.end
      // Force a break after only when the comment occupies its own line (or is an EOL `//`, which
      // must end its line). A block comment glued inline after a blank separator behaves like a
      // trailing comment — forcing a break there is what made a `/*…*/` between inlined annotations
      // and the declaration flip layout between passes (non-idempotent).
      if (onOwnLine || text.startsWith("//")) {
        pendingForced = NBreak(FillMode.FORCED, "", Indent.Const.ZERO, null)
      } else {
        // Inline block comment glued after a separator: keep a space before the next token, so it
        // renders `…*/ open` regardless of whether the comment came in as leading or trailing.
        cur().children.add(NText(" "))
      }
    }
  }

  /** Emit comments at the cursor that sit on the current line, inline after the preceding token. */
  private fun flushTrailingComments() {
    while (idx < leaves.size && leaves[idx].isComment && sameLine(lastTokenEnd, leaves[idx].start)) {
      val c = leaves[idx++]
      val text = c.text.trimEnd()
      cur().children.add(NText(" "))
      cur().children.add(NComment(text, eol = text.startsWith("//")))
      lastTokenEnd = c.end
      // An EOL comment must end its line: a break must follow. It is buffered (pendingForced) and
      // resolved by whatever is emitted next — at that thing's level, NOT this (possibly nested,
      // already-closing) one — so it never forces a compact enclosing declaration to split, but a
      // following comment/token still lands on a fresh line.
      if (text.startsWith("//")) pendingForced = NBreak(FillMode.FORCED, "", Indent.Const.ZERO, null)
    }
  }

  /**
   * Advance the cursor past the next real token the visitor just emitted ([expected]), keeping the
   * cursor aligned with the source so comment placement stays correct.
   *
   * The visitor's token stream isn't guaranteed 1:1 with source leaves (generics, compound
   * operators, string templates, synthesized/optional tokens), so a naive one-step advance
   * desyncs and cascades. This resynchronizes by text: if the cursor isn't already on [expected],
   * it scans a bounded window for it, flushing any comments passed (as leading) and skipping
   * non-matching real leaves. If [expected] isn't found nearby, it assumes a synthesized token and
   * leaves the cursor put.
   */
  private fun consumeRealToken(expected: String) {
    // Skip a source trailing comma the visitor intentionally dropped (optofmt §4).
    while (idx < leaves.size && !leaves[idx].isComment && leaves[idx].text == "," && expected != ",") {
      lastTokenEnd = leaves[idx].end
      idx++
    }
    if (idx < leaves.size && !leaves[idx].isComment && leaves[idx].text == expected) {
      lastTokenEnd = leaves[idx].end
      idx++
      return
    }
    // Resync to the next matching source token. The visitor emits tokens in source order, so the
    // correct leaf is the first occurrence of [expected] at/after the cursor; scanning to it (no
    // arbitrary window) avoids a silent give-up that would desync the cursor and misplace/drop
    // later comments. If [expected] never appears (a genuinely synthesized token), leave the cursor
    // put and let the caller emit the text — the cursor stays aligned for the next real token.
    var look = idx
    while (look < leaves.size && !(leaves[look].text == expected && !leaves[look].isComment)) {
      look++
    }
    if (look < leaves.size) {
      // Consume up to the match: flush comments as leading, drop intervening (mismatched) tokens.
      while (idx < look) {
        val l = leaves[idx++]
        if (l.isComment) {
          resolvePending()
          cur().children.add(NComment(l.text.trimEnd(), eol = l.text.startsWith("//")))
          pendingForced = NBreak(FillMode.FORCED, "", Indent.Const.ZERO, null)
        }
        lastTokenEnd = l.end
      }
      lastTokenEnd = leaves[look].end
      idx = look + 1
    }
  }

  /** Flush remaining comments at end of input. */
  fun finish() {
    flushLeadingComments(code.length + 1)
    resolvePending()
  }

  override fun open(plusIndent: Indent) {
    resolvePending()
    val level = NLevel(plusIndent)
    cur().children.add(level)
    stack.addLast(level)
  }

  override fun close() {
    // Don't resolve a pending break here: a break buffered inside a level that is closing belongs
    // to the NEXT content (at the outer level), not to this one. Resolving it here would force the
    // (possibly compact) closing level to break — e.g. an inline declaration with a trailing
    // comment. It bubbles out and is resolved by the next emission.
    stack.removeLast()
  }

  // ---- Candidate-layout support (RULES §1) -----------------------------------------------------

  /**
   * Builds [build] into a detached subtree (NOT appended to the current level) and returns it.
   * Used to construct competing candidate layouts for [NAlt]. The source-token cursor advances
   * exactly once for the content built here, so a captured subtree may be reused across several
   * [NAlt] alternatives without consuming the source twice.
   */
  internal fun capture(build: () -> Unit): NDoc {
    val tmp = NLevel(Indent.Const.ZERO)
    val savedPending = pendingForced
    pendingForced = null
    stack.addLast(tmp)
    build()
    resolvePending() // settle any trailing buffered break inside the captured subtree
    stack.removeLast()
    pendingForced = savedPending
    return tmp
  }

  /** Append an already-built subtree to the current level. */
  internal fun appendSubtree(node: NDoc) = add(node)

  /** Append an already-built subtree forced to lay out flat (see [NFlat]). */
  internal fun appendFlatSubtree(node: NDoc) = add(NFlat(node))

  /** Append an already-built introducer RHS subtree, tagging its internal wrap lines (see [NRhsBody]). */
  internal fun appendRhsBodySubtree(node: NDoc) = add(NRhsBody(node))

  /**
   * Emit literal text WITHOUT advancing the source-token cursor. Unlike [token]/`emit`, this does not
   * try to match (and consume) a source leaf, so it is safe for punctuation synthesized between
   * already-captured subtrees — e.g. the `,` separators when composing a supertype list from
   * per-entry captures whose inter-entry commas were already consumed during capture.
   */
  internal fun literal(text: String) = add(NText(text))

  /** Offer competing candidate layouts; the renderer keeps the lowest-§1-cost one (see [NAlt]). */
  internal fun emitAlt(alternatives: List<NDoc>) = add(NAlt(alternatives))

  override fun token(
      text: String,
      realOrImaginary: RealOrImaginary,
      plusIndentCommentsBefore: Indent,
      breakAndIndentTrailingComment: Optional<Indent>,
  ) {
    // Any comments at the cursor precede this token, so they are leading.
    flushLeadingComments(Int.MAX_VALUE)
    consumeRealToken(text)
    add(NText(text))
    flushTrailingComments()
  }

  // Optional punctuation: emit it iff it's actually present in the source (keeping the cursor
  // aligned). Truly-redundant semicolons were already stripped upstream (dropRedundantElements), so
  // any `;` still here is needed (e.g. the enum entry/member separator), as is any `,`.
  override fun guessToken(token: String) {
    if (idx < leaves.size && !leaves[idx].isComment && leaves[idx].text == token) {
      lastTokenEnd = leaves[idx].end
      idx++
      add(NText(token))
      flushTrailingComments()
    }
  }

  override fun space() = add(NText(" "))

  override fun breakOp(
      fillMode: FillMode,
      flat: String,
      plusIndent: Indent,
      optTag: Optional<BreakTag>,
  ) = add(NBreak(fillMode, flat, plusIndent, optTag.orElse(null)))

  /**
   * A break that, when TAKEN (the list wrapped), emits [brokenPrefix] — a trailing "," — at the end
   * of the line it closes; when not taken (list stays flat) the prefix vanishes. Used for the closing
   * break of a multi-line comma list (RULES §14). Flat width ignores the prefix, so it never turns a
   * list that fits into a wrapped one.
   */
  internal fun brokenPrefixBreak(fillMode: FillMode, plusIndent: Indent, brokenPrefix: String) =
      add(NBreak(fillMode, "", plusIndent, null, brokenPrefixText = brokenPrefix))

  /**
   * Handle a source trailing comma the visitor drops (optofmt §4/§14) that is immediately followed by
   * a SAME-LINE `// note` on the last list item. Because the comma is never emitted via [token], the
   * usual [flushTrailingComments] (which stops at the orphaned comma) can't reach the comment, so it
   * would otherwise be swept up later as a *leading* comment of the closing `)` and pushed onto its own
   * line. This emits the `,` (§14 trailing comma, iff [withComma]) then the comment inline
   * (`emptyList(), // note`), advancing the cursor past both, and returns true. The caller then emits a
   * plain forced closing break. Returns false (cursor untouched) when there is no such same-line
   * trailing comment — a standalone comment on its own line stays leading, handled by the closer.
   */
  internal fun emitDroppedTrailingCommaComment(withComma: Boolean): Boolean {
    var j = idx
    val sawComma = j < leaves.size && !leaves[j].isComment && leaves[j].text == ","
    if (sawComma) j++
    if (j >= leaves.size || !leaves[j].isComment || !sameLine(lastTokenEnd, leaves[j].start)) {
      // No dropped-comma-then-same-line-comment here. (A last item with a same-line comment but NO
      // source trailing comma has already had its comment flushed inline during its own emission — a
      // separate, rarer case not handled here.)
      return false
    }
    if (withComma) add(NText(","))
    val c = leaves[j]
    val text = c.text.trimEnd()
    cur().children.add(NText(" "))
    cur().children.add(NComment(text, eol = text.startsWith("//")))
    lastTokenEnd = c.end
    idx = j + 1
    return true
  }

  override fun forcedBreak(plusIndent: Indent) {
    resolvePending()
    pendingForced = NBreak(FillMode.FORCED, "", plusIndent, null)
  }

  /**
   * A forced break that DETACHES an introducer from its opener (§3): the "break after `=`/`:`/`->`"
   * arrangement of an [emitAlt] introducer pair. Tagged so the objective can prefer the attached
   * arrangement whenever both fit (see [NBreak.introducer] / [Metrics.introducerBreaks]).
   */
  internal fun forcedIntroducerBreak(plusIndent: Indent) {
    resolvePending()
    pendingForced = NBreak(FillMode.FORCED, "", plusIndent, null, introducer = true)
  }

  /**
   * Like [forcedIntroducerBreak] but for a CALL-CHAIN right-hand side (§3/§7): ranked below line
   * count, so it only prefers attaching when that costs the same number of lines — see
   * [NBreak.chainIntroducer] / [Metrics.chainIntroducerBreaks].
   */
  internal fun forcedChainIntroducerBreak(plusIndent: Indent) {
    resolvePending()
    pendingForced = NBreak(FillMode.FORCED, "", plusIndent, null, chainIntroducer = true)
  }

  /** A forced break that is only the chain's own inter-`.call` structure (§7); safe to flatten inside
   * an [NFlat] hang candidate (see [NBreak.chainStructural]). */
  internal fun forcedChainStructuralBreak(plusIndent: Indent) {
    resolvePending()
    pendingForced = NBreak(FillMode.FORCED, "", plusIndent, null, chainStructural = true)
  }

  override fun blankLineWanted(wanted: BlankLineWanted) {
    // Resolve PRESERVE against the source *now* (a blank is kept iff the author left one at the
    // cursor), so the request no longer depends on the current formatting. Without this the native
    // engine treated PRESERVE as NO, so a run of one-line declarations that was multi-line in the
    // source (blank forced via YES on pass 1) collapsed to one-line and lost its blanks on pass 2 —
    // non-idempotent. Resolving to YES/NO from the source makes both passes agree.
    val resolved =
        when (wanted) {
          BlankLineWanted.PRESERVE -> if (sourceHasBlankAtCursor()) BlankLineWanted.YES else BlankLineWanted.NO
          else -> wanted
        }
    add(NBlank(resolved))
  }

  /** True if the source between the last consumed token and the next leaf contains a blank line. */
  private fun sourceHasBlankAtCursor(): Boolean {
    val next = if (idx < leaves.size) leaves[idx].start else code.length
    var newlines = 0
    var i = lastTokenEnd
    while (i < next && i < code.length) {
      if (code[i] == '\n' && ++newlines >= 2) return true
      i++
    }
    return false
  }

  override fun sync(inputPosition: Int) = flushLeadingComments(inputPosition)

  override fun markForPartialFormat() {}

  override fun fenceComments() {}
}

// The RULES §1 layout objective — [Metrics], [LayoutObjective], and the ready-made [Objectives] —
// lives in its own file, LayoutObjective.kt, so it can be read/changed in isolation. This engine is
// objective-agnostic: it takes a [LayoutObjective] and keeps the lowest-scoring layout.

/**
 * Lays out an [NativeSink]'s document and returns the formatted text.
 *
 * Output goes through a line buffer (like gjf's Output): a taken break ends the current line, but
 * consecutive breaks with no content between them coalesce into a single newline — a blank line is
 * produced only when explicitly requested via [BlankLineWanted.YES]. This is what keeps the many
 * adjacent forced breaks the visitor emits (block opener + first statement, etc.) from turning into
 * spurious blank lines.
 *
 * [objective] is the RULES §1 optimization criterion (default [Objectives.DEFAULT]); pass a
 * different one to experiment.
 */
class NativeRenderer(
    private val maxWidth: Int,
    private val objective: LayoutObjective = Objectives.DEFAULT,
) {
  private companion object {
    const val BIG = 100_000
    const val FRONTIER_CAP = 64
  }

  private val committed = StringBuilder()
  private val lineBuf = StringBuilder()
  private var curIndent = 0
  private var pendingBlank = false
  private val taken = HashMap<BreakTag, Boolean>()
  // Memoizes flat widths by node identity: the optimizer measures a level's flat width and recurses
  // into child levels which would otherwise re-measure the same subtrees (O(n^2) on deep nesting).
  // The IR is immutable during rendering, so caching by identity is safe.
  private val widthCache = java.util.IdentityHashMap<NDoc, Int>()
  // Memoizes the Pareto frontier of layouts of a node at a given (startCol, indent). The DP visits
  // the same subtree at the same column from many parents/candidates; without this the search is
  // exponential. The IR is immutable during rendering, so caching by node identity is safe.
  private val layoutMemo = java.util.IdentityHashMap<NDoc, HashMap<Long, List<Layout>>>()

  fun render(sink: NativeSink): String {
    // RULES §1: lay out the whole document by global optimization. `layouts` returns the
    // Pareto-optimal set of candidate layouts (over every legal break-position combination); we keep
    // the one with the lowest §1 cost and replay its emit thunk to produce the text.
    val best = layouts(sink.root, startCol = 0, indent = 0).minWithOrNull(byCost)
    best?.emit?.invoke()
    flushLine()
    return committed.toString().split("\n").joinToString("\n") { it.trimEnd() }.trimEnd('\n') + "\n"
  }

  private fun emitText(s: String) {
    // Drop whitespace at the start of a line: indentation is supplied by the line's indent, so a
    // stray leading space (e.g. a separator space emitted right after a forced break) is spurious.
    if (lineBuf.isEmpty() && s.isBlank()) return
    lineBuf.append(s)
  }

  /** End the current line (if it has content) and set the indent for the next one. Coalesces when
   * the current line is empty, so adjacent breaks don't create blank lines. */
  private fun breakTo(indent: Int) {
    if (lineBuf.isNotEmpty()) {
      flushLine()
    }
    curIndent = indent
  }

  private fun flushLine() {
    if (lineBuf.isEmpty()) return
    if (pendingBlank) {
      committed.append("\n")
      pendingBlank = false
    }
    committed.append(" ".repeat(curIndent)).append(lineBuf).append("\n")
    lineBuf.setLength(0)
  }

  private fun Indent.eval(): Int =
      when (this) {
        is Indent.Const -> n
        is Indent.If -> (if (taken[condition] == true) thenIndent else elseIndent).eval()
      }

  /** Flat (single-line) width of a doc; [BIG] if it can't be a single line (forced break, or an
   * EOL comment that isn't the level's last child). Memoized by node identity. */
  private fun flatWidth(doc: NDoc): Int =
      widthCache.getOrPut(doc) { computeFlatWidth(doc) }

  private fun computeFlatWidth(doc: NDoc): Int =
      when (doc) {
        is NText -> doc.text.length
        // A comment contributes no width to break decisions: code must not be re-broken just
        // because a trailing comment makes the line long (gjf does the same; it also keeps this
        // idempotent). An EOL comment still forces a break via the not-last check above.
        is NComment -> 0
        is NBreak -> if (doc.fillMode == FillMode.FORCED) BIG else doc.flat.length
        is NBlank -> 0
        // Flat, an alternative uses its flattest candidate (the one that can sit on one line).
        is NAlt -> doc.alts.minOf { flatWidth(it) }
        is NFlat -> flatWidth(doc.child)
        is NRhsBody -> flatWidth(doc.child)
        is NLevel -> {
          var w = 0
          var sawEolComment = false
          for (c in doc.children) {
            // An EOL comment forces a break if anything but trailing spaces follows it.
            if (sawEolComment && !(c is NText && c.text.isBlank())) return BIG
            if (c is NComment && c.eol) sawEolComment = true
            w += flatWidth(c)
            if (w >= BIG) return BIG
          }
          w
        }
      }

  /** True if [doc] contains anything that cannot be laid out flat: an EOL (`//`) comment (must end
   * its line) or a semantically-required FORCED break — a nested lambda-body statement break, etc.
   * A [NBreak.chainStructural] break is excluded (the chain's own `.call` structure flattens fine).
   * An [NFlat] wrapping such content is not a valid candidate. */
  private fun containsUnflattenable(doc: NDoc): Boolean =
      when (doc) {
        is NComment -> doc.eol
        is NBreak -> doc.fillMode == FillMode.FORCED && !doc.chainStructural
        is NLevel -> doc.children.any { containsUnflattenable(it) }
        // Only the FLATTEST alternative is used when flattening (see [flatLayout]/[appendFlat]), so a
        // forced break in a *non-flattest* alt (e.g. the break-after-`=` arm of a named argument's
        // introducer emitAlt, whose attached arm is chosen when flat) does not make this unflattenable.
        is NAlt -> doc.alts.minByOrNull { flatWidth(it) }?.let { containsUnflattenable(it) } ?: false
        is NFlat -> containsUnflattenable(doc.child)
        is NRhsBody -> containsUnflattenable(doc.child)
        else -> false
      }

  /** Flat width of children[from] up to (not including) the next break — used to decide whether an
   * INDEPENDENT (fill) break must fire. */
  private fun segmentWidth(children: List<NDoc>, from: Int): Int {
    var w = 0
    var i = from
    while (i < children.size && children[i] !is NBreak) {
      w += flatWidth(children[i])
      if (w >= BIG) return BIG
      i++
    }
    return w
  }

  // ==============================================================================================
  // RULES §1 — THE LAYOUT OBJECTIVE, by global optimization.
  //
  // This is the true §1 optimizer: instead of greedily deciding each level flat-or-broken in
  // isolation, it enumerates *every legal combination* of break positions and keeps the layout
  // with the lowest [objective] cost. It does this efficiently via a memoized dynamic program over
  // (node, startColumn, indent): [layouts] returns the Pareto-optimal frontier of candidate
  // layouts for a node, and parents compose child frontiers, pruning dominated candidates so the
  // 2^N space of break decisions collapses to polynomial work.
  //
  // The legal break-position choices the search explores are:
  //   - per level: laid out flat (no break) or broken — UNIFIED breaks are all-or-nothing per a
  //     level (RULES §4: a list is compact or fully split, never filled), so they move together;
  //   - per [NAlt]: which competing candidate subtree to use.
  // These are the rule-relevant axes, and the search enumerates *all combinations* of them. FORCED
  // breaks are always taken; an EOL comment forces the following break. INDEPENDENT breaks are
  // fill (a local last resort: taken only when the run up to the next break won't fit) rather than
  // a free line-count axis — optimizing them for fewest lines would break call chains and
  // expressions just to save a line, against RULES §5/§7.
  //
  // The objective itself lives at the top of this file ([LayoutObjective]/[Metrics]/[Objectives]);
  // it is injected via the constructor. Everything below is objective-agnostic.
  // ==============================================================================================

  /**
   * One candidate layout of some node, laid out starting at a fixed column. The metric fields
   * aggregate the *completed* (closed) lines; [lastCol] is the column at the end of the still-open
   * last line (which a following sibling continues). [emit] replays this exact layout into the
   * output buffer — so the rendered text always matches the costed layout. These fields are exactly
   * the axes the Pareto search prunes on and the objective scores (once finalized via [finish]).
   */
  private class Layout(
      val worst: Int, // max overflow among completed lines
      val overLines: Int, // count of completed lines that overflow
      val overSum: Int, // summed overflow among completed lines
      val lines: Int, // count of completed lines
      val deepestIndent: Int, // deepest start-indent among wrapped (broken) lines
      val lastCol: Int, // end column of the still-open last line
      val introBreaks: Int, // count of taken introducer breaks (RULES §3, see NBreak.introducer)
      val chainIntroBreaks: Int, // count of taken chain-RHS introducer breaks (RULES §3/§7)
      val rhsWrapLines: Int, // completed lines that fall INSIDE an introducer RHS (see NRhsBody)
      val emit: () -> Unit,
  )

  /** Fold a layout's open last line in and expose it to the objective as finished [Metrics]. */
  private fun finish(l: Layout): Metrics {
    val openOverflow = (l.lastCol - maxWidth).coerceAtLeast(0)
    return Metrics(
        worstOverflow = maxOf(l.worst, openOverflow),
        overflowLines = l.overLines + (if (openOverflow > 0) 1 else 0),
        totalOverflow = l.overSum + openOverflow,
        lines = l.lines + 1,
        deepestIndent = l.deepestIndent,
        introducerBreaks = l.introBreaks,
        chainIntroducerBreaks = l.chainIntroBreaks,
        rhsWrapLines = l.rhsWrapLines,
    )
  }

  /** This layout's objective score (lower is better; compared element-by-element via [byCost]). */
  private fun costOf(l: Layout): DoubleArray = objective.cost(finish(l))

  /** Orders layouts by their objective score, lexicographically over the score vector. */
  private val byCost =
      Comparator<Layout> { x, y ->
        val a = costOf(x)
        val b = costOf(y)
        for (i in 0 until minOf(a.size, b.size)) {
          val c = a[i].compareTo(b[i])
          if (c != 0) return@Comparator c
        }
        a.size - b.size
      }

  /**
   * The Pareto frontier of candidate layouts of [doc] starting at [startCol] within enclosing
   * [indent]. A candidate is kept unless another candidate is at least as good on *all four* of
   * (worst overflow, overflowing lines, completed lines, open-line column) — i.e. no worse for the
   * future. Memoized by (node, startCol, indent).
   */
  private fun layouts(doc: NDoc, startCol: Int, indent: Int): List<Layout> =
      when (doc) {
        is NText -> listOf(appendText(leaf(startCol) {}, doc.text))
        // Comments are 0-width for break decisions (code must not be re-broken just because a
        // trailing comment makes the line long; also keeps formatting idempotent), but their text
        // is still emitted.
        is NComment -> listOf(leaf(startCol) { emitText(doc.text) })
        is NBlank -> listOf(leaf(startCol) { if (doc.wanted == BlankLineWanted.YES) pendingBlank = true })
        is NBreak -> {
          val a0 = leaf(startCol) {}
          if (doc.fillMode == FillMode.FORCED) listOf(breakTaken(a0, doc, indent))
          else listOf(breakTaken(a0, doc, indent), breakNotTaken(a0, doc))
        }
        is NAlt -> memo(doc, startCol, indent) { pareto(doc.alts.flatMap { layouts(it, startCol, indent) }) }
        // Forced flat: only the single-line layout is offered, so its cost carries any overflow. But
        // if the child CANNOT be flat (it contains a forced break or a non-last EOL `//` comment,
        // flatWidth == BIG), flattening it would be wrong — it would swallow the comment's mandatory
        // line break (commenting out whatever follows). Poison the candidate (worst = BIG) so §1
        // rejects it in favor of a sibling that wraps.
        is NFlat -> {
          val fl = flatLayout(doc.child, startCol)
          // An EOL `//` comment MUST end its line; flattening it would swallow the break and comment
          // out whatever follows. Poison the flat candidate (worst = BIG) so §1 rejects it for a
          // sibling that wraps. (Plain forced *layout* breaks are safe to flatten — the hang preamble
          // relies on that — so only comments poison, not `flatWidth == BIG`.)
          if (!containsUnflattenable(doc.child)) listOf(fl)
          else listOf(
              Layout(BIG, fl.overLines + 1, fl.overSum + BIG, fl.lines, fl.deepestIndent, fl.lastCol,
                  fl.introBreaks, fl.chainIntroBreaks, fl.rhsWrapLines, fl.emit))
        }
        // Layout-transparent: lay the child out exactly as-is, but tag the lines that fall inside
        // the RHS (its completed lines) so the objective can prefer a single-line RHS (see NRhsBody).
        is NRhsBody ->
            layouts(doc.child, startCol, indent).map { c ->
              Layout(
                  c.worst, c.overLines, c.overSum, c.lines, c.deepestIndent, c.lastCol,
                  c.introBreaks, c.chainIntroBreaks, c.rhsWrapLines + c.lines, c.emit)
            }
        is NLevel ->
            memo(doc, startCol, indent) {
              // A level can be flat unless it contains a forced break or a not-last EOL comment
              // (flatWidth returns BIG then). Its flat cost accounts for multiline strings.
              val canFlat = flatWidth(doc) < BIG
              val flat = if (canFlat) flatLayout(doc, startCol) else null
              val candidates = ArrayList<Layout>()
              if (flat != null) candidates.add(flat)
              // RULES §1: "don't wrap if it fits." A level with no overflowing physical line is never
              // broken — the global search only explores broken layouts for regions that must wrap.
              // (This is also what keeps a small group like a chain's receiver-through-first-call
              // attached: it fits, so it is never split to save lines elsewhere — see RULES §7.)
              val fits = flat != null && finish(flat).worstOverflow == 0
              if (!fits) candidates.addAll(brokenLayouts(doc, startCol, indent))
              pareto(candidates)
            }
      }

  /**
   * The broken-layout frontier of [level]: walk its children left-to-right, branching the frontier
   * at every free choice (INDEPENDENT break taken/not, child-level flat/broken, [NAlt] candidate)
   * and Pareto-pruning after each step so the search stays polynomial.
   */
  private fun brokenLayouts(level: NLevel, startCol: Int, indent: Int): List<Layout> {
    val base = indent + level.plusIndent.eval()
    var frontier = listOf(leaf(startCol) {})
    var forceBreak = false // an EOL comment just before requires the next break to be taken
    val kids = level.children
    for (i in kids.indices) {
      val k = kids[i]
      val next = ArrayList<Layout>(frontier.size * 2)
      when (k) {
        is NBlank ->
            for (a in frontier) next.add(extend(a, a.lastCol) {
              if (k.wanted == BlankLineWanted.YES) pendingBlank = true
            })
        is NText -> for (a in frontier) next.add(appendText(a, k.text))
        is NComment -> for (a in frontier) next.add(extend(a, a.lastCol) { emitText(k.text) })
        is NLevel,
        is NAlt,
        is NFlat,
        is NRhsBody ->
            for (a in frontier) for (b in layouts(k, a.lastCol, base)) next.add(combine(a, b))
        is NBreak -> {
          // UNIFIED breaks fire together with their level (§4 all-or-nothing); FORCED always fire;
          // an EOL comment forces the next break. An INDEPENDENT break is fill: it fires only when
          // the content up to the next break would overflow the line — a deterministic local choice,
          // so it adds no branching to the global search.
          val alwaysFires =
              k.fillMode == FillMode.FORCED || k.fillMode == FillMode.UNIFIED || forceBreak
          val runWidth = if (k.fillMode == FillMode.INDEPENDENT) segmentWidth(kids, i + 1) else 0
          for (a in frontier) {
            val fires = alwaysFires || a.lastCol + k.flat.length + runWidth > maxWidth
            next.add(if (fires) breakTaken(a, k, base) else breakNotTaken(a, k))
          }
        }
      }
      forceBreak = k is NComment && k.eol
      frontier = pareto(next)
    }
    return frontier
  }

  // ---- Layout constructors (compose candidates; their emit thunks chain so render == cost) ------

  private fun leaf(lastCol: Int, emit: () -> Unit): Layout =
      Layout(0, 0, 0, 0, 0, lastCol, 0, 0, 0, emit)

  /** [a] followed by zero-break content ending at [newLastCol]; carries [a]'s completed lines. */
  private fun extend(a: Layout, newLastCol: Int, emit: () -> Unit): Layout =
      Layout(
          a.worst, a.overLines, a.overSum, a.lines, a.deepestIndent, newLastCol, a.introBreaks,
          a.chainIntroBreaks, a.rhsWrapLines) {
            a.emit()
            emit()
          }

  /**
   * [a] followed by a text token. A *multiline* token (a `"""…"""` string literal, whose text
   * contains newlines) is not one wide line: it occupies several physical lines whose widths are
   * fixed by the source. Measuring it as `text.length` (as a single line) would fabricate a giant
   * unbreakable line and poison the §1 "worst overflow" objective — so we account for each physical
   * line: the first joins the open line, the interior lines close at their own widths, and the last
   * becomes the new open line. Emit is unchanged (the token renders its own newlines).
   *
   * Interior and last lines are measured with their leading whitespace stripped. A `"""…"""` that is
   * trimmed (`.trimIndent()`/`.trimMargin()`) has its continuation lines *reindented* to the code
   * column by [MultilineStringFormatter] as a later pass — so their leading whitespace in the input
   * is transient and depends on the previous formatting. Measuring the invariant content width
   * (not the input indentation) keeps the layout decision — and therefore the result — idempotent,
   * and it can't over-measure a continuation line into a false overflow the formatter can't fix.
   */
  private fun appendText(a: Layout, text: String): Layout {
    if ('\n' !in text) return extend(a, a.lastCol + text.length) { emitText(text) }
    val parts = text.split('\n')
    var worst = a.worst
    var overLines = a.overLines
    var overSum = a.overSum
    var lines = a.lines
    fun close(width: Int) {
      val o = width - maxWidth
      if (o > 0) {
        overLines++
        overSum += o
        if (o > worst) worst = o
      }
      lines++
    }
    close(a.lastCol + parts.first().length) // first physical line ends where the open line was
    for (i in 1 until parts.size - 1) close(parts[i].trimStart().length) // reindented later
    return Layout(
        worst, overLines, overSum, lines, a.deepestIndent, parts.last().trimStart().length,
        a.introBreaks, a.chainIntroBreaks, a.rhsWrapLines) {
          a.emit()
          emitText(text)
        }
  }

  /**
   * The layout of [doc] with no layout breaks taken (its "flat" form). Not simply
   * `startCol + flatWidth`: a multiline string makes the flat form span several physical lines, so
   * we walk the tree accounting for that via [appendText]. Emit is [appendFlat]. Comments stay
   * 0-width for the objective (as in [flatWidth]) but are still emitted by [appendFlat].
   */
  private fun flatLayout(doc: NDoc, startCol: Int): Layout {
    var acc = leaf(startCol) {}
    fun walk(d: NDoc) {
      when (d) {
        is NText -> acc = appendText(acc, d.text)
        is NComment -> {} // 0-width for the objective
        is NBreak -> acc = extend(acc, acc.lastCol + d.flat.length) {} // not taken
        is NBlank -> {}
        is NLevel -> d.children.forEach { walk(it) }
        is NAlt -> walk(d.alts.minByOrNull { flatWidth(it) }!!)
        is NFlat -> walk(d.child)
        is NRhsBody -> walk(d.child)
      }
    }
    walk(doc)
    return Layout(
        acc.worst, acc.overLines, acc.overSum, acc.lines, acc.deepestIndent, acc.lastCol,
        acc.introBreaks, acc.chainIntroBreaks, acc.rhsWrapLines) {
          appendFlat(doc)
        }
  }

  /** [a] followed by a fully-laid-out child subtree [b] (measured starting at [a.lastCol]). */
  private fun combine(a: Layout, b: Layout): Layout =
      Layout(
          worst = maxOf(a.worst, b.worst),
          overLines = a.overLines + b.overLines,
          overSum = a.overSum + b.overSum,
          lines = a.lines + b.lines,
          deepestIndent = maxOf(a.deepestIndent, b.deepestIndent),
          lastCol = b.lastCol,
          introBreaks = a.introBreaks + b.introBreaks,
          chainIntroBreaks = a.chainIntroBreaks + b.chainIntroBreaks,
          rhsWrapLines = a.rhsWrapLines + b.rhsWrapLines,
          emit = { a.emit(); b.emit() },
      )

  /** [a] then a taken break: closes the line ending at [a.lastCol], opens a new one at the break's
   * indent. */
  private fun breakTaken(a: Layout, brk: NBreak, base: Int): Layout {
    val newIndent = maxOf(base + brk.plusIndent.eval(), 0)
    // §14: a taken break may emit a trailing "," at the end of the line it closes, so that line is
    // one column wider than the open column reached so far.
    val closedCol = a.lastCol + brk.brokenPrefixText.length
    val over = (closedCol - maxWidth).coerceAtLeast(0)
    return Layout(
        worst = maxOf(a.worst, over),
        overLines = a.overLines + (if (over > 0) 1 else 0),
        overSum = a.overSum + over,
        lines = a.lines + 1,
        deepestIndent = maxOf(a.deepestIndent, newIndent),
        lastCol = newIndent,
        introBreaks = a.introBreaks + (if (brk.introducer) 1 else 0),
        chainIntroBreaks = a.chainIntroBreaks + (if (brk.chainIntroducer) 1 else 0),
        rhsWrapLines = a.rhsWrapLines,
        emit = {
          a.emit()
          if (brk.tag != null) taken[brk.tag] = true
          if (brk.brokenPrefixText.isNotEmpty()) emitText(brk.brokenPrefixText)
          breakTo(newIndent)
        },
    )
  }

  /** [a] then a break that is not taken: its flat text stays on the open line. */
  private fun breakNotTaken(a: Layout, brk: NBreak): Layout =
      Layout(
          a.worst, a.overLines, a.overSum, a.lines, a.deepestIndent, a.lastCol + brk.flat.length,
          a.introBreaks, a.chainIntroBreaks, a.rhsWrapLines, {
            a.emit()
            if (brk.tag != null) taken[brk.tag] = false
            emitText(brk.flat)
          })

  /**
   * Keep only non-dominated candidates. [a] dominates [b] when it is no worse on EVERY tracked
   * metric — the completed-line metrics plus [lastCol] (the future) — and, for exact ties, comes
   * first. Because these are all the axes any objective can read (via [finish]) and each composes
   * monotonically, dropping dominated candidates is safe for any monotone [objective] — so the
   * pruning need not know which objective is in use.
   */
  private fun pareto(list: List<Layout>): List<Layout> {
    if (list.size <= 1) return list
    val out = ArrayList<Layout>(list.size)
    for (j in list.indices) {
      val b = list[j]
      var dominated = false
      for (i in list.indices) {
        if (i == j) continue
        val a = list[i]
        if (a.worst <= b.worst &&
            a.overLines <= b.overLines &&
            a.overSum <= b.overSum &&
            a.lines <= b.lines &&
            a.deepestIndent <= b.deepestIndent &&
            a.introBreaks <= b.introBreaks &&
            a.chainIntroBreaks <= b.chainIntroBreaks &&
            a.rhsWrapLines <= b.rhsWrapLines &&
            a.lastCol <= b.lastCol) {
          val strict =
              a.worst < b.worst ||
                  a.overLines < b.overLines ||
                  a.overSum < b.overSum ||
                  a.lines < b.lines ||
                  a.deepestIndent < b.deepestIndent ||
                  a.introBreaks < b.introBreaks ||
                  a.chainIntroBreaks < b.chainIntroBreaks ||
                  a.rhsWrapLines < b.rhsWrapLines ||
                  a.lastCol < b.lastCol
          if (strict || i < j) {
            dominated = true
            break
          }
        }
      }
      if (!dominated) out.add(b)
    }
    // Safety net: a pathological input could still leave a wide frontier after pruning. Keep the
    // most promising candidates by objective score so the search stays bounded. In practice the
    // Pareto filter alone holds the frontier well under this cap.
    if (out.size > FRONTIER_CAP) return out.sortedWith(byCost).subList(0, FRONTIER_CAP)
    return out
  }

  private inline fun memo(doc: NDoc, startCol: Int, indent: Int, compute: () -> List<Layout>): List<Layout> =
      layoutMemo.getOrPut(doc) { HashMap() }.getOrPut(startCol * 1_000_000L + indent) { compute() }

  // ==============================================================================================
  // Mechanics: emit flat (single-line) content. Breaks emit through the Layout thunks above.
  // ==============================================================================================

  /** Append a node's flat rendering (no breaks taken) into the current line. */
  private fun appendFlat(doc: NDoc) {
    when (doc) {
      is NText -> emitText(doc.text)
      is NComment -> emitText(doc.text)
      is NBreak -> emitText(doc.flat) // never FORCED here (flatWidth would have been BIG)
      is NBlank -> {}
      is NLevel -> doc.children.forEach { appendFlat(it) }
      is NAlt -> appendFlat(doc.alts.minByOrNull { flatWidth(it) }!!)
      is NFlat -> appendFlat(doc.child)
      is NRhsBody -> appendFlat(doc.child)
    }
  }
}
