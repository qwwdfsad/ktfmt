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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * One test per code snippet in kotlin-format/report.html (generated from its snippets.py corpus):
 * formatting each snippet's input with the optofmt style must produce its documented optofmt
 * layout. Sub-cases that have no paste-able input are checked for idempotency of the documented
 * layout instead. GENERATED — regenerate from snippets.py if the corpus changes.
 */
@RunWith(JUnit4::class)
class OptofmtSnippetsTest {
  private fun format(code: String): String =
      Formatter.format(Formatter.OPTOFMT_FORMAT, code).trimEnd('\n')

  /** The snippet's input formats to exactly its documented optofmt layout. */
  private fun check(input: String, expected: String) {
    assertThat(format(input)).isEqualTo(expected.trimEnd('\n'))
  }

  /** The documented optofmt layout is stable (formatting it again yields itself). */
  private fun idempotent(layout: String) {
    val once = format(layout)
    assertThat(once).isEqualTo(layout.trimEnd('\n'))
    assertThat(format(once)).isEqualTo(once)
  }

  @Test
  fun `boolean-condition`() =
      check(
          input = """fun f() { if (unwrapped !== rootCause && unwrapped !== unwrappedCause && unwrapped !is CancellationException && seenExceptions.add(unwrapped)) { rootCause.addSuppressed(unwrapped) } }""",
          expected = """fun f() {
    if (
        unwrapped !== rootCause &&
        unwrapped !== unwrappedCause &&
        unwrapped !is CancellationException &&
        seenExceptions.add(unwrapped)
    ) {
        rootCause.addSuppressed(unwrapped)
    }
}""",
      )

  /**
   * §1/§2: an `if (cond) <non-block body>` whose condition text fits but whose *closing `)`* tips the
   * header one past the column limit must WRAP THE CONDITION (`if (` ⏎ cond ⏎ `) return …`), not leave
   * the `if (…))` header overflowing while only the body breaks onto its own line. §1 requires an
   * overflow that wrapping can eliminate to be eliminated. Regression: `emitKeywordWithCondition`
   * emitted the closing `)` OUTSIDE the wrappable condition level, so the level's fit test never saw
   * the `)` — a condition ending exactly at column 100 looked like it fit while the `)` overflowed at
   * 101.
   */
  @Test
  fun `if-condition-wraps-when-trailing-paren-overflows`() =
      check(
          input =
              """fun processChunk(chunk: List<Int>, chainAwarded: Set<Int>, award: Award): Boolean {
    if (chainAwarded.size + chunk.size > (award.chainLimit ?: Int.MAX_VALUE) && extraGuardFlagValue2) return false
    return true
}""",
          expected =
              """fun processChunk(chunk: List<Int>, chainAwarded: Set<Int>, award: Award): Boolean {
    if (
        chainAwarded.size + chunk.size > (award.chainLimit ?: Int.MAX_VALUE) && extraGuardFlagValue2
    ) return false
    return true
}""",
      )

  /**
   * §3 (via §1/§2): a `val x = buildList { … }` scoping-call initializer STAYS ATTACHED to `=` even
   * when a deeply nested body line's `if (…)` header would overflow — because that header wraps its
   * condition (see [if-condition-wraps-when-trailing-paren-overflows]) to reach zero overflow, so the
   * attached candidate no longer loses to break-after-`=` on §1's worst-overflow criterion. The exact
   * live-v3 AbstractScoreboardCalculator.kt:69 symptom: optofmt was breaking after `=` (and splitting
   * the inner condition awkwardly at a deeper indent) purely because the un-wrapped inner `if` header
   * carried a one-column overflow into the attached candidate.
   */
  @Test
  fun `scoping-call-initializer-stays-attached-when-inner-condition-wraps`() =
      check(
          input =
              """class C {
    fun f() {
        val awards = buildList {
            for (chain in chains) {
                if (chainAwarded.size + chunk.size > (award.chainLimit ?: Int.MAX_VALUE) && someFlag) return
            }
        }
    }
}""",
          expected =
              """class C {
    fun f() {
        val awards = buildList {
            for (chain in chains) {
                if (
                    chainAwarded.size + chunk.size > (award.chainLimit ?: Int.MAX_VALUE) && someFlag
                ) return
            }
        }
    }
}""",
      )

  /**
   * §4 vs §5: a SOLE call argument that is a `receiver.method { … }` chain whose lambda body is a
   * SINGLE expression is NOT block-like — it full-splits onto its own line with `)` on its own line,
   * rather than hanging the chain off the opener (which would split the chain receiver onto the
   * opener line and dangle `})`: `OverrideTeams(teams` ⏎ `.associateWith { … })`). From live-v3
   * AddGroupToTeams.kt:27. Regression: `isLambdaOrScopingFunction` classified any `receiver.method
   * { … }` as a hangable block regardless of whether its body was a real multi-line block.
   */
  @Test
  fun `sole-arg-chain-with-single-expr-lambda-full-splits-not-hangs`() =
      check(
          input =
              """class C {
    fun desugar(): TuningRule {
        return OverrideTeams(teams.associateWith { OverrideTeams.Override(extraGroups = listOf(id)) })
    }
}""",
          expected =
              """class C {
    fun desugar(): TuningRule {
        return OverrideTeams(
            teams.associateWith { OverrideTeams.Override(extraGroups = listOf(id)) },
        )
    }
}""",
      )

  /**
   * §5 boundary companion to [sole-arg-chain-with-single-expr-lambda-full-splits-not-hangs]: when the
   * scoping/chain sole argument's lambda body IS a genuine multi-statement block, it still hangs off
   * the call opener (the block has something to hang), keeping the body at a single indent.
   */
  @Test
  fun `sole-arg-scoping-lambda-with-block-body-still-hangs`() =
      check(
          input =
              """class C {
    fun f() {
        return computeResultFromInput(someInputValue.let { transformed -> transformed.doFirstThing(); transformed.doSecondThing() })
    }
}""",
          expected =
              """class C {
    fun f() {
        return computeResultFromInput(someInputValue.let { transformed ->
            transformed.doFirstThing()
            transformed.doSecondThing()
        })
    }
}""",
      )

  /**
   * §5 vs §4: a sole scoping-lambda argument whose body is a SINGLE expression that is nonetheless too
   * wide to fit inline even when the argument is full-split HUGS the call opener (§5 indent economy) —
   * the body must wrap either way, so hugging keeps it at a single indent instead of the +2 double
   * indent a full-split would give. This is the complement of
   * [sole-arg-chain-with-single-expr-lambda-full-splits-not-hangs] (whose body DOES fit inline when
   * split, so it stays full-split): the hug-vs-split choice is column-dependent and left to §1. From
   * kotlinx.coroutines Actor.kt:136 (`_channel.cancel(cause?.let { … })`).
   */
  @Test
  fun `sole-arg-scoping-lambda-single-expr-too-wide-to-fit-inline-hugs`() =
      check(
          input =
              """class C {
    override fun onCancelling(cause: Throwable?) {
        _channel.cancel(
            cause?.let {
                it as? CancellationException
                    ?: CancellationException("${'$'}classSimpleName was cancelled", it)
            },
        )
    }
}""",
          expected =
              """class C {
    override fun onCancelling(cause: Throwable?) {
        _channel.cancel(cause?.let {
            it as? CancellationException
                ?: CancellationException("${'$'}classSimpleName was cancelled", it)
        })
    }
}""",
      )

  /**
   * Idempotency guard for [sole-arg-scoping-lambda-single-expr-too-wide-to-fit-inline-hugs]: the same
   * call written with the lambda body on ONE source line must reach the SAME hugged form (the hug/split
   * decision is column-based, not source-whitespace-based, so it cannot flip between passes).
   */
  @Test
  fun `sole-arg-scoping-lambda-single-expr-one-line-source-still-hugs`() =
      check(
          input =
              """class C {
    override fun onCancelling(cause: Throwable?) {
        _channel.cancel(cause?.let { it as? CancellationException ?: CancellationException("${'$'}classSimpleName was cancelled", it) })
    }
}""",
          expected =
              """class C {
    override fun onCancelling(cause: Throwable?) {
        _channel.cancel(cause?.let {
            it as? CancellationException
                ?: CancellationException("${'$'}classSimpleName was cancelled", it)
        })
    }
}""",
      )

  @Test
  fun `indent-economy`() =
      check(
          input = """fun f() { add(OverrideQueue(queueSettings.waitTime, queueSettings.firstToSolveWaitTime, queueSettings.featuredRunWaitTime, queueSettings.inProgressRunWaitTime, queueSettings.maxQueueSize, queueSettings.maxUntestedRun)) }""",
          expected = """fun f() {
    add(OverrideQueue(
        queueSettings.waitTime,
        queueSettings.firstToSolveWaitTime,
        queueSettings.featuredRunWaitTime,
        queueSettings.inProgressRunWaitTime,
        queueSettings.maxQueueSize,
        queueSettings.maxUntestedRun,
    ))
}""",
      )

  @Test
  fun `indent-economy-extra-1-idempotent`() =
      idempotent("""fun f() {
    registerHandler(buildHandler(
        aLongUnbreakableArgumentIdentifierDeliberatelySizedToOverflowTheColumnLimitNoMatterWhatXY,
    ))
}""")

  /**
   * §5 (indent economy) on a MEMBER-ACCESS call: `rootClasses.addAll(listOf(…))` collapses the two
   * openers (`addAll(listOf(`) and stacks the closers (`))`) exactly like a bare `add(OverrideQueue(…))`
   * — the collapsed body sits at a SINGLE indent below the call line and `))` returns to the call's
   * column. Regression: the receiver (`rootClasses.`) routed the call through the chain-continuation
   * block, whose one indent level was left uncompensated in the collapse branch — so the inner call's
   * own break level doubled it, over-indenting the arguments (and `))`) by a level. From a
   * build.gradle.kts `tasks.register { rootClasses.addAll(listOf(…)) }` block.
   */
  @Test
  fun `indent-economy-collapse-on-member-access-call`() =
      check(
          input =
              """val generateApiTypeScript = tasks.register<TsInterfaceGeneratorTask>("generateApiTypeScript") {
    rootClasses.addAll(listOf(
            "org.icpclive.cds.api.ContestInfo",
            "org.icpclive.cds.api.RunInfo",
            "org.icpclive.cds.api.ScoreboardDiff",
            "org.icpclive.api.MainScreenEvent",
            "org.icpclive.api.QueueEvent",
            "org.icpclive.api.AnalyticsEvent",
            "org.icpclive.api.TickerEvent",
            "org.icpclive.api.SolutionsStatistic",
            "org.icpclive.api.ExternalTeamViewSettings",
            "org.icpclive.api.ObjectSettings",
            "org.icpclive.api.WidgetUsageStatistics",
            "org.icpclive.api.TimeLineRunInfo",
            "org.icpclive.api.AddTeamScoreRequest",
            "org.icpclive.api.InterestingTeam",
        ))
    fileName = "api"
}""",
          expected =
              """val generateApiTypeScript = tasks.register<TsInterfaceGeneratorTask>("generateApiTypeScript") {
    rootClasses.addAll(listOf(
        "org.icpclive.cds.api.ContestInfo",
        "org.icpclive.cds.api.RunInfo",
        "org.icpclive.cds.api.ScoreboardDiff",
        "org.icpclive.api.MainScreenEvent",
        "org.icpclive.api.QueueEvent",
        "org.icpclive.api.AnalyticsEvent",
        "org.icpclive.api.TickerEvent",
        "org.icpclive.api.SolutionsStatistic",
        "org.icpclive.api.ExternalTeamViewSettings",
        "org.icpclive.api.ObjectSettings",
        "org.icpclive.api.WidgetUsageStatistics",
        "org.icpclive.api.TimeLineRunInfo",
        "org.icpclive.api.AddTeamScoreRequest",
        "org.icpclive.api.InterestingTeam",
    ))
    fileName = "api"
}""",
      )

  @Test
  fun `infix-attached`() =
      check(
          input = """val pair = orgInfo.id to OverrideOrganizations.Override(fullName = substituteRaw(fullName), displayName = substituteRaw(displayName))""",
          expected = """val pair = orgInfo.id to OverrideOrganizations.Override(
    fullName = substituteRaw(fullName),
    displayName = substituteRaw(displayName),
)""",
      )

  /**
   * §3: when `val pair: T = a to Foo(…)` is too long to keep the whole `a to Foo(` unit on the `=`
   * line (~110 cols here), the INNERMOST introducer yields first: the assignment `=` stays attached
   * and the infix `to` breaks (`= orgInfo.id to` ⏎ `OverrideOrganizations.Override(`), the RHS opener
   * one indent in and its arguments one further in (§4). It must NOT break after `=` (keeping `=`
   * attached is preferred), nor split the sole call `OverrideOrganizations.Override(…)` as a chain
   * (`…Organizations\n.Override(` — a receiver-through-first-call is atomic, §7). Regressions fixed:
   * (a) optofmt used to split the receiver from its `.Override` call to save a line; (b) the sole
   * call's wrapped args used to under-indent (col 4, colliding with the opener) when the introducer
   * broke.
   */
  @Test
  fun `infix-attached-breaks-after-to-when-unit-too-long`() =
      check(
          input =
              """val pair: Pair<OrganizationId, OverrideOrganizations.Override> = orgInfo.id to OverrideOrganizations
    .Override(fullName = substituteRaw(fullName), displayName = substituteRaw(displayName))""",
          expected =
              """val pair: Pair<OrganizationId, OverrideOrganizations.Override> = orgInfo.id to
    OverrideOrganizations.Override(
        fullName = substituteRaw(fullName),
        displayName = substituteRaw(displayName),
    )""",
      )

  /**
   * §3: the supertype `:` is an introducer — it stays attached to the FIRST supertype even when the
   * primary-constructor parameter list above it wrapped. The remaining supertypes then go one-per-line
   * (§4). Regression: optofmt used to break after `:` (`) :\n    AbstractCoroutine(…)`).
   * From kotlinx.coroutines Broadcast.kt:67.
   */
  @Test
  fun `supertype-colon-attaches-first-after-wrapped-constructor`() =
      check(
          input =
              """private open class BroadcastCoroutine<E>(parentContext: CoroutineContext, protected val _channel: BroadcastChannel<E>, active: Boolean) : AbstractCoroutine<Unit>(parentContext, initParentJob = false, active = active), ProducerScope<E>, BroadcastChannel<E> by _channel {
    init { initParentJob(parentContext[Job]) }
}""",
          expected =
              """private open class BroadcastCoroutine<E>(
    parentContext: CoroutineContext,
    protected val _channel: BroadcastChannel<E>,
    active: Boolean,
) : AbstractCoroutine<Unit>(parentContext, initParentJob = false, active = active),
    ProducerScope<E>,
    BroadcastChannel<E> by _channel {
    init {
        initParentJob(parentContext[Job])
    }
}""",
      )

  /**
   * §3: `by` is an introducer just like `=`. When a property delegate is too long to fit, `by` stays
   * attached to the delegate expression and the delegate's own contents (its call arguments) wrap;
   * optofmt does NOT break after `by` into a fresh indented block. Regression: `by` used to drop the
   * delegate onto its own line.
   */
  @Test
  fun `delegate-by-introducer-attached`() =
      check(
          input =
              """val config: Configuration by provideDelegateForConfiguration(firstArgumentHere, secondArgumentHere, third)""",
          expected =
              """val config: Configuration by provideDelegateForConfiguration(
    firstArgumentHere,
    secondArgumentHere,
    third,
)""",
      )

  @Test
  fun `supertype-attached`() =
      check(
          input = """object ClicsArchiveCommand : DumpFileCommand(name = "clics-archive", help = "Dump CLICS contest archive (zip)", defaultFileName = "contest-archive.zip", outputHelp = "Path to new zip file") {
}""",
          expected = """object ClicsArchiveCommand : DumpFileCommand(
    name = "clics-archive",
    help = "Dump CLICS contest archive (zip)",
    defaultFileName = "contest-archive.zip",
    outputHelp = "Path to new zip file",
) {}""",
      )

  @Test
  fun `block-rhs`() =
      check(
          input = """fun f() {
val teamsAffected = when (val event = state.lastEvent) {
is CommentaryMessagesUpdate -> emptyList()
is InfoUpdate -> info.teams.keys.toList()
is RunUpdate -> {
lastSubmissionTime = maxOf(lastSubmissionTime, event.newInfo.time)
runsByTeamId.applyEvent(state)
}
}
}""",
          expected = """fun f() {
    val teamsAffected = when (val event = state.lastEvent) {
        is CommentaryMessagesUpdate -> emptyList()
        is InfoUpdate -> info.teams.keys.toList()
        is RunUpdate -> {
            lastSubmissionTime = maxOf(lastSubmissionTime, event.newInfo.time)
            runsByTeamId.applyEvent(state)
        }
    }
}""",
      )

  @Test
  fun `block-rhs-extra-1-idempotent`() =
      idempotent("""fun f() {
    val builder = if (!builders.isEmpty()) {
        builders.peek()
    } else {
        null
    }
}""")

  /**
   * §15 (with §3/§4): a non-block `if`/`else` value keeps each branch body ATTACHED to its keyword.
   * `= if (cond) this` attaches per §3; the `else` clause hangs on its own line but keeps its call
   * opener `else withHeader(` attached and wraps the call's arguments (§4) — it does not leave a
   * bare `else` with the body pushed onto a further-indented line. From ktor Authorization.withOAuth.
   */
  @Test
  fun `non-block-if-else-expression-body-attaches-branch-bodies`() =
      check(
          input =
              "public fun Authorization.withOAuth(token: Credential?): Authorization = " +
                  "if (token == null) this else withHeader(name = HttpHeaders.Authorization, " +
                  "value = Credential(displayValue = \"OAuth \${token.displayValue}\", " +
                  "value = \"OAuth \${token.value}\"))",
          expected =
              """public fun Authorization.withOAuth(token: Credential?): Authorization = if (token == null) this
    else withHeader(
        name = HttpHeaders.Authorization,
        value = Credential(
            displayValue = "OAuth ${'$'}{token.displayValue}",
            value = "OAuth ${'$'}{token.value}",
        ),
    )""",
      )

  /** §2/§3: the common shape — only the `else` clause wraps, staying one indent below the header. */
  @Test
  fun `non-block-if-else-expression-body-short-else-hangs-at-one-indent`() =
      check(
          input =
              "fun label(n: Int): String = if (n < 0) negativeValueLabelWhichIsQuiteLong " +
                  "else if (n == 0) zeroValueLabelHere else positiveValueLabelHere",
          expected =
              """fun label(n: Int): String = if (n < 0) negativeValueLabelWhichIsQuiteLong
    else if (n == 0) zeroValueLabelHere else positiveValueLabelHere""",
      )

  @Test
  fun `long-call-chain`() =
      check(
          input = """fun f() { val testDataDir: Path = Path.of("").absolute().parent.parent.resolve("tests").resolve("testData").resolve("loaders").relativeTo(Path.of("").absolute()) }""",
          expected = """fun f() {
    val testDataDir: Path = Path.of("")
        .absolute()
        .parent
        .parent
        .resolve("tests")
        .resolve("testData")
        .resolve("loaders")
        .relativeTo(Path.of("").absolute())
}""",
      )

  /**
   * §2: a `receiver.call(valueArg) { … }` (trailing lambda on a call that ALSO has value arguments)
   * hangs the lambda body one indent below the call line — same as a sole trailing lambda. From
   * kotlinx.coroutines AbstractCoroutineTest.kt:34. Regression: the hang candidate was wrongly
   * poisoned because the named value arg's introducer `emitAlt` had a forced break in its
   * (non-flattest) break-after-`=` arm, so §1 fell to a broken layout that over-indented the body.
   */
  @Test
  fun `call-with-value-arg-and-trailing-lambda-hangs`() =
      check(
          input =
              """fun f() {
    coroutine.invokeOnCompletion(onCancelling = true) {
        assertNull(it)
        expect(7)
    }
}""",
          expected =
              """fun f() {
    coroutine.invokeOnCompletion(onCancelling = true) {
        assertNull(it)
        expect(7)
    }
}""",
      )

  /**
   * The exact AbstractCoroutineTest.kt:34 case: the same `receiver.call(valueArg) { … }` nested one
   * level deeper (inside a method), where the lambda body must hang at one indent below the call line
   * (col 12) — not over-indent to col 16 as it did before the [containsUnflattenable] flattest-alt fix.
   */
  @Test
  fun `call-with-value-arg-and-trailing-lambda-hangs-nested`() =
      check(
          input =
              """
class AbstractCoroutineTest {
    fun testNotifications() {
        coroutine.invokeOnCompletion(onCancelling = true) {
            assertNull(it)
            expect(7)
        }
    }
}""",
          expected =
              """class AbstractCoroutineTest {
    fun testNotifications() {
        coroutine.invokeOnCompletion(onCancelling = true) {
            assertNull(it)
            expect(7)
        }
    }
}""",
      )

  /**
   * §7: in a member-access chain, the receiver-break is author-preserving — both the default
   * (receiver-through-first-call attached) and the receiver-on-its-own-line forms are legal. Here the
   * source keeps the receiver and its first call together on one line, so the default attachment is
   * applied: `return this.applyIf(…) { … }` stays on the introducer line and each subsequent `.call`
   * wraps one per line. (The trailing lambdas are incidental — the same holds for plain calls, see
   * [chain-receiver-attached-vs-broken-is-author-preserving].)
   */
  @Test
  fun `call-chain-of-trailing-lambdas-attached`() =
      check(
          input =
              """
public fun Flow<ContestUpdate>.addComputedData(configure: ComputedDataConfig.() -> Unit = {}): Flow<ContestUpdate> {
    val config = ComputedDataConfig().apply(configure)
    return this.applyIf(config.autoCreateMissingGroups) { autoCreateMissingGroupsAndOrgs() }.applyIf(!config.submissionResultsAfterFreeze) { removeFrozenSubmissionsResults() }.applyIf(!config.submissionsAfterEnd) { removeAfterEndSubmissions() }.applyIf(config.unhideColorWhenSolved) { selectProblemColors() }.applyIf(config.propagateHidden) { hideHiddenGroupsTeams() }.applyIf(config.propagateHidden) { hideHiddenTeamsRuns() }.applyIf(config.propagateHidden) { hideHiddenProblemsRuns() }.applyIf(config.propagateRunMediaTemplates) { propagateRunMediaTemplates() }.applyIf(config.ioiScoreDifferences) { calculateScoreDifferences() }.applyIf(config.markSubmissionsAfterFirstOk) { markSubmissionAfterFirstOk() }.applyIf(config.firstToSolves) { addFirstToSolves() }.applyIf(config.replaceCommentaryTags) { processCommentaryTags() }.applyIf(config.autoFinalize) { autoFinalize() }
}""",
          expected =
              """public fun Flow<ContestUpdate>.addComputedData(
    configure: ComputedDataConfig.() -> Unit = {},
): Flow<ContestUpdate> {
    val config = ComputedDataConfig().apply(configure)
    return this.applyIf(config.autoCreateMissingGroups) { autoCreateMissingGroupsAndOrgs() }
        .applyIf(!config.submissionResultsAfterFreeze) { removeFrozenSubmissionsResults() }
        .applyIf(!config.submissionsAfterEnd) { removeAfterEndSubmissions() }
        .applyIf(config.unhideColorWhenSolved) { selectProblemColors() }
        .applyIf(config.propagateHidden) { hideHiddenGroupsTeams() }
        .applyIf(config.propagateHidden) { hideHiddenTeamsRuns() }
        .applyIf(config.propagateHidden) { hideHiddenProblemsRuns() }
        .applyIf(config.propagateRunMediaTemplates) { propagateRunMediaTemplates() }
        .applyIf(config.ioiScoreDifferences) { calculateScoreDifferences() }
        .applyIf(config.markSubmissionsAfterFirstOk) { markSubmissionAfterFirstOk() }
        .applyIf(config.firstToSolves) { addFirstToSolves() }
        .applyIf(config.replaceCommentaryTags) { processCommentaryTags() }
        .applyIf(config.autoFinalize) { autoFinalize() }
}""",
      )

  /**
   * §7 companion: when the SAME all-trailing-lambda chain is written in the source with the receiver
   * already broken onto its own line (`return this` ⏎ `.applyIf(…)`), optofmt PRESERVES that — the
   * receiver stays alone on the introducer line and EVERY `.call { … }`, including the first, sits on
   * its own line at one indent. Idempotent with the attached variant's output kept apart.
   */
  @Test
  fun `call-chain-of-trailing-lambdas-receiver-broken-off-is-preserved`() =
      check(
          input =
              """
public fun Flow<ContestUpdate>.addComputedData(configure: ComputedDataConfig.() -> Unit = {}): Flow<ContestUpdate> {
    val config = ComputedDataConfig().apply(configure)
    return this
        .applyIf(config.autoCreateMissingGroups) { autoCreateMissingGroupsAndOrgs() }
        .applyIf(!config.submissionResultsAfterFreeze) { removeFrozenSubmissionsResults() }
        .applyIf(!config.submissionsAfterEnd) { removeAfterEndSubmissions() }
        .applyIf(config.autoFinalize) { autoFinalize() }
}""",
          expected =
              """public fun Flow<ContestUpdate>.addComputedData(
    configure: ComputedDataConfig.() -> Unit = {},
): Flow<ContestUpdate> {
    val config = ComputedDataConfig().apply(configure)
    return this
        .applyIf(config.autoCreateMissingGroups) { autoCreateMissingGroupsAndOrgs() }
        .applyIf(!config.submissionResultsAfterFreeze) { removeFrozenSubmissionsResults() }
        .applyIf(!config.submissionsAfterEnd) { removeAfterEndSubmissions() }
        .applyIf(config.autoFinalize) { autoFinalize() }
}""",
      )

  /**
   * §7: the receiver-break choice is author-preserving for ANY member-access chain (≥2 links), not
   * just chains with trailing lambdas. The default keeps the receiver through its first call attached
   * (`recv.first(…)` then a staircase); if the author broke the receiver onto its own line, that is
   * preserved and every `.call`, including the first, gets its own line. A single-call chain
   * (`Receiver.method(…)`) is atomic and stays whole regardless of source (verified by
   * [infix-attached-breaks-after-to-when-unit-too-long]).
   */
  @Test
  fun `chain-receiver-attached-vs-broken-is-author-preserving`() {
    // Default: source keeps `recv.first(…)` together → attached.
    check(
        input =
            """fun f() {
    val x = someReceiverObject.firstMethodCall(argOne).secondMethodCall(argTwo).thirdMethodCall(argThree)
}""",
        expected =
            """fun f() {
    val x = someReceiverObject.firstMethodCall(argOne)
        .secondMethodCall(argTwo)
        .thirdMethodCall(argThree)
}""",
    )
    // Preserved: source broke the receiver onto its own line → every `.call` on its own line.
    check(
        input =
            """fun f() {
    val x = someReceiverObject
        .firstMethodCall(argOne).secondMethodCall(argTwo).thirdMethodCall(argThree)
}""",
        expected =
            """fun f() {
    val x = someReceiverObject
        .firstMethodCall(argOne)
        .secondMethodCall(argTwo)
        .thirdMethodCall(argThree)
}""",
    )
  }

  /**
   * §7: the author broke the chain off before its first `.call`, but across a leading property run
   * (`users.users`) that stays on the receiver's line. The whole property navigation is the receiver:
   * it sits alone on the introducer's line and every `.call`, including the first (`.asSequence()`),
   * lands on its own line — NOT collapsed to `users.users.asSequence()` (the default
   * receiver-through-first-call attachment). This is the `breakBeforeFirstCall` companion to
   * [chain-receiver-attached-vs-broken-is-author-preserving], where the receiver was a bare
   * reference. From ICPC-live CATSDataSource.kt:163. Regression: the leading `.users` property was
   * pulled up with `.asSequence()` because `sourceBreaksAfterChainReceiver` only checked the break
   * before the very first `.member`, missing the break before the first `.call`.
   */
  @Test
  fun `chain-receiver-with-property-run-broken-before-first-call-is-preserved`() =
      check(
          input =
              """fun f() {
    val teamList: List<TeamInfo> = users.users
        .asSequence()
        .filter { team -> team.role == "in_contest" }
        .map { team -> TeamInfo(id = team.account_id.toTeamId(), organizationId = null) }
        .toList()
}""",
          expected =
              """fun f() {
    val teamList: List<TeamInfo> = users.users
        .asSequence()
        .filter { team -> team.role == "in_contest" }
        .map { team -> TeamInfo(id = team.account_id.toTeamId(), organizationId = null) }
        .toList()
}""",
      )

  /**
   * §5/§7: in an author-broken staircase, a lambda-free `.call()`/`.property` tail the author wrote
   * hugging the preceding trailing-lambda call's `}` (`}.toList()`) STAYS hugged instead of dropping
   * to its own line — the block-body hugging economy of §5. The hug is preserved per link from the
   * source (a fill break attaches it when it fits), so it is idempotent: a hugged tail re-reads as
   * hugged. A tail the author instead broke onto its own line is kept on its own line (verified by
   * [chain-receiver-with-property-run-broken-before-first-call-is-preserved], whose `.toList()` is on
   * its own line in the source). From ICPC-live CATSDataSource.kt:163.
   */
  @Test
  fun `chain-lambda-free-tail-hugs-closing-brace-when-author-hugged-it`() =
      check(
          input =
              """fun f() {
    val teamList: List<TeamInfo> = users.users
        .asSequence()
        .filter { team -> team.role == "in_contest" }
        .map { team ->
            TeamInfo(id = team.account_id.toTeamId(), fullName = team.name, organizationId = null)
        }.toList()
}""",
          expected =
              """fun f() {
    val teamList: List<TeamInfo> = users.users
        .asSequence()
        .filter { team -> team.role == "in_contest" }
        .map { team ->
            TeamInfo(id = team.account_id.toTeamId(), fullName = team.name, organizationId = null)
        }.toList()
}""",
      )

  /**
   * §7: a chain that breaks purely for length, whose receiver-through-first-call carries a SINGLE-
   * LINE trailing lambda (`file.takeIf { it.exists() }`), staircases each subsequent `.call` onto
   * its own line — it does NOT keep the lambda-free `?.inputStream()` packed on the intro line. The
   * `}`-hug economy (see [chain-lambda-free-tail-hugs-closing-brace-when-author-hugged-it]) applies
   * only when the grouped lambda actually renders multiline, leaving a `}` on its own line to hug;
   * a single-line lambda leaves no such brace. From ICPC-live Users.kt:49. Regression: `?.inputStream()`
   * used to hug `.takeIf { it.exists() }` on the first line while only `?.use` broke.
   */
  @Test
  fun `chain-with-single-line-lambda-first-call-staircases-subsequent-calls`() =
      check(
          input =
              """fun f() {
    file.takeIf { it.exists() }?.inputStream()?.use {
        Json.decodeFromStream<List<User>>(it).associateByTo(users, User::name)
    }
}""",
          expected =
              """fun f() {
    file.takeIf { it.exists() }
        ?.inputStream()
        ?.use {
            Json.decodeFromStream<List<User>>(it).associateByTo(users, User::name)
        }
}""",
      )

  /**
   * §7 with a lowercase receiver: the receiver-through-first-call stays on the introducer's line
   * regardless of the receiver's name/casing, then each subsequent `.step` wraps to its own line.
   * Matches report.md's `chain-lambda-steps`. Regression: the receiver `repository` was being
   * broken onto its own line (only short/uppercase/`this` receivers used to group).
   */
  @Test
  fun `chain-lambda-steps`() =
      check(
          input =
              """fun f() { val result = repository.query(Filter.byStatus(Status.ACTIVE)).paginate(pageNumber, pageSize).mapEach { it.toDto(includeMetadata = true) } }""",
          expected =
              """fun f() {
    val result = repository.query(Filter.byStatus(Status.ACTIVE))
        .paginate(pageNumber, pageSize)
        .mapEach { it.toDto(includeMetadata = true) }
}""",
      )

  /**
   * §7 + §1: `worker.execute(…) { … }.result` — the chain is multiline only because of the trailing
   * lambda's block body, not because it overflows. The receiver-through-first-call
   * (`worker.execute(…) {`) stays on one line and the lambda-free tail `.result` attaches to the
   * closing `}` (§1/§7 tail-attach), rather than exploding `worker` and `.result` onto their own
   * lines. From kotlinx.coroutines WorkerTest.kt:14.
   */
  @Test
  fun `chain-trailing-lambda-tail-property`() =
      check(
          input =
              """
class WorkerTest {
    fun testLaunchInWorker() {
        val worker = Worker.start()
        worker.execute(TransferMode.SAFE, {}) { runBlocking { launch {}.join(); delay(1) } }.result
        worker.requestTermination()
    }
}""",
          expected =
              """class WorkerTest {
    fun testLaunchInWorker() {
        val worker = Worker.start()
        worker.execute(TransferMode.SAFE, {}) {
            runBlocking {
                launch {}.join()
                delay(1)
            }
        }.result
        worker.requestTermination()
    }
}""",
      )

  /**
   * §2/§3: an expression body `= runBlocking { … }` whose header is too long to attach the opener
   * breaks after `=` and puts `runBlocking {` on its own line — the lambda body must then indent one
   * level deeper than `runBlocking` (§2 one level per step), not sit at the same column. From
   * kotlinx.coroutines WithTimeoutThreadDispatchTest.kt:56. Regression: the broken scoping-function
   * body was under-indented by one level (the native engine can't resolve the `Indent.If` on the
   * brace-break tag that the old code used).
   */
  @Test
  fun `scoping-function-expression-body-break-after-eq`() =
      check(
          input =
              """
class C {
    private fun checkCancellationDispatch(factory: (ThreadFactory) -> CoroutineDispatcher) = runBlocking { expect(1); finish(2) }
}""",
          expected =
              """class C {
    private fun checkCancellationDispatch(factory: (ThreadFactory) -> CoroutineDispatcher) =
        runBlocking {
            expect(1)
            finish(2)
        }
}""",
      )

  /**
   * §1: a type cast (`… as T`) whose left operand is a multiline scoping block (`apply { … }`) is
   * multiline because of the block's own body, not because the cast overflows — so `as T` stays
   * attached to the closing `}` (`} as T`) rather than dropping to its own line. From Exposed
   * AbstractQuery.kt:95. Regression: `as` was always breaking before the operator when the enclosing
   * level wrapped, dangling `as T` on its own line at the wrong indent.
   */
  @Test
  fun `type-cast-attaches-to-multiline-scoping-block`() =
      check(
          input =
              """
class AbstractQuery<T> {
    open fun withDistinct(value: Boolean = true): T = apply {
        require(distinctOn == null) { "DISTINCT cannot be used with the DISTINCT ON modifier." }
        distinct = value
    } as T
}""",
          expected =
              """class AbstractQuery<T> {
    open fun withDistinct(value: Boolean = true): T = apply {
        require(distinctOn == null) { "DISTINCT cannot be used with the DISTINCT ON modifier." }
        distinct = value
    } as T
}""",
      )

  /**
   * §7: when a chain's receiver is ITSELF a call (`QueryBuilder(false)`), that base call is already
   * "the first call" — so it stays attached to the `=` introducer (§7 "don't break after `=`") and
   * each subsequent `.call` (`.also { … }`, `.toString()`) breaks to its own line at one indent, all
   * at the same single level (no drift). From Exposed AdjustQueryTests.kt:61. Regression: the base
   * call was being grouped with its first `.member`, forcing a needless break after `=`.
   */
  @Test
  fun `chain-with-call-receiver-keeps-base-on-introducer`() =
      check(
          input =
              """
class C {
    fun testAdjustQueryColumnSet() {
        withCitiesAndUsers { cities, users, _ ->
            fun ColumnSet.repr(): String = QueryBuilder(false).also { this.describe(TransactionManager.current(), it) }.toString()
        }
    }
}""",
          expected =
              """class C {
    fun testAdjustQueryColumnSet() {
        withCitiesAndUsers { cities, users, _ ->
            fun ColumnSet.repr(): String = QueryBuilder(false)
                .also { this.describe(TransactionManager.current(), it) }
                .toString()
        }
    }
}""",
      )

  /**
   * §3/§7: a chain ending in a trailing-lambda call (`… .where { predicate }`) where the lambda call
   * is a LATER link (not applied directly to the receiver) keeps the receiver-through-first-call on
   * the `=` introducer line and breaks the trailing-lambda `.call` to its own line — rather than
   * hanging the lambda (which, being one line longer, would lose to a needless break after `=`). From
   * Exposed AdjustQueryTests.kt:109. Regression: the sole trailing lambda was hung block-like even
   * when it was a later link, forcing break-after-`=`.
   */
  @Test
  fun `chain-trailing-lambda-later-link-breaks-to-own-line`() =
      check(
          input =
              """
class AdjustQueryTests {
    fun testQueryOrWhere() {
        withCitiesAndUsers { cities, users, _ ->
            val queryAdjusted = (users innerJoin cities).select(users.name, cities.name).where { predicate }
        }
    }
}""",
          expected =
              """class AdjustQueryTests {
    fun testQueryOrWhere() {
        withCitiesAndUsers { cities, users, _ ->
            val queryAdjusted = (users innerJoin cities).select(users.name, cities.name)
                .where { predicate }
        }
    }
}""",
      )

  /**
   * §7: "receiver through its first call" spans a leading property/reference run — the first *call*
   * in `DMLTestsData.Users.id.count()` is `.count()`, so `DMLTestsData.Users.id.count()` stays on the
   * `=` introducer line and the next call (`.eq(…)`) breaks to its own line. From Exposed
   * AdjustQueryTests.kt:123. Regression: the leading property run grouped without the first call, so
   * `.count()` AND `.eq()` both broke (3 lines), losing to a needless break after `=` (2 lines).
   */
  @Test
  fun `chain-receiver-through-first-call-spans-property-run`() =
      check(
          input =
              """
class AdjustQueryTests {
    fun testAdjustQueryHaving() {
        withCitiesAndUsers { cities, users, _ ->
            val predicateHaving = DMLTestsData.Users.id.count().eq<Number, Long, Int>(DMLTestsData.Cities.id.max())
        }
    }
}""",
          expected =
              """class AdjustQueryTests {
    fun testAdjustQueryHaving() {
        withCitiesAndUsers { cities, users, _ ->
            val predicateHaving = DMLTestsData.Users.id.count()
                .eq<Number, Long, Int>(DMLTestsData.Cities.id.max())
        }
    }
}""",
      )

  /**
   * §7: `receiver.run { … }` where the receiver is a call that WRAPS its arguments. The receiver
   * wraps ONLY its own arguments (at a single indent), and the sole trailing-lambda call `.run {`
   * stays attached to the receiver's `)` (`AesBytesEncryptor(\n args,\n).run {`) — the lambda call
   * applies directly to the receiver-through-first-call, so it is not a "subsequent" chain link to
   * break onto its own line. The lambda body hangs one indent below the `.run {` line. From Exposed
   * Algorithms.kt:33-45. (Earlier the whole chain was wrapped in the chain's continuation block, so
   * the receiver's args drifted to two indents and `.run` broke onto its own line; §1 now prefers the
   * fewer-line attached layout.)
   */
  @Test
  fun `scoping-call-on-wrapping-receiver-indents-body`() =
      check(
          input =
              """
object Algorithms {
    fun AES_256_PBE_GCM(password: CharSequence, salt: CharSequence): Encryptor {
        return AesBytesEncryptor(password.toString(), salt, KeyGenerators.secureRandom(BLOCK_LEN), AesBytesEncryptor.CipherAlgorithm.GCM).run {
            makeEncryptor()
            finalizeSetup()
        }
    }
}""",
          expected =
              """object Algorithms {
    fun AES_256_PBE_GCM(password: CharSequence, salt: CharSequence): Encryptor {
        return AesBytesEncryptor(
            password.toString(),
            salt,
            KeyGenerators.secureRandom(BLOCK_LEN),
            AesBytesEncryptor.CipherAlgorithm.GCM,
        ).run {
            makeEncryptor()
            finalizeSetup()
        }
    }
}""",
      )

  /**
   * §7: a call whose arguments wrap, followed by a sole trailing-lambda call (`merge(a, b, c).collect
   * { … }`). The base call wraps ONLY its own arguments at a single indent, its `)` closes aligned
   * with the call, and `.collect {` stays attached to that `)` — the lambda applies directly to the
   * receiver-through-first-call, so it is not a "subsequent" `.call` to drop onto its own line. The
   * lambda body hangs one indent below. Regression: the args drifted to two indents (chain block +
   * arg list) and `.collect` broke onto its own line.
   */
  @Test
  fun `wrapping-call-with-sole-trailing-lambda-attaches`() =
      check(
          input =
              """fun f() {
    merge(flow.map { Update(it) }, triggerFlow.receiveAsFlow().conflate(), advancedPropsStateFlow.map { Trigger }).collect {
        handle(it)
    }
}""",
          expected =
              """fun f() {
    merge(
        flow.map { Update(it) },
        triggerFlow.receiveAsFlow().conflate(),
        advancedPropsStateFlow.map { Trigger },
    ).collect {
        handle(it)
    }
}""",
      )

  /**
   * The [scoping-call-on-wrapping-receiver-indents-body] fix must not regress a sole trailing lambda
   * whose receiver fits on one line: it still hangs block-like with the body one level below.
   */
  @Test
  fun `scoping-call-on-fitting-receiver-hangs`() =
      check(
          input =
              """fun f(): X = Foo(alpha, beta).apply { firstStatement(); secondStatement() }""",
          expected =
              """fun f(): X = Foo(alpha, beta).apply {
    firstStatement()
    secondStatement()
}""",
      )

  /**
   * §3: when an expression body's `=` must break (the signature is too long to attach the RHS), and
   * the RHS is a sole-trailing-lambda call chain (`Receiver.call(args) { … }`), the call sits at one
   * indent and its lambda body hangs ONE FURTHER level (body one indent past the call, `}` aligned
   * with the call). Regression: the body under-indented by a level (body at the call's own indent,
   * `}` a level shallower than the call). From kotlinx.coroutines ChannelSinkBenchmark.kt:46.
   */
  @Test
  fun `broken-eq-scoping-call-with-value-args-indents-body`() =
      check(
          input =
              """open class C {
    private fun Channel.Factory.range(start: Int, count: Int, context: CoroutineContext) =
        GlobalScope.produce(context) {
            for (i in start until (start + count)) send(i)
        }
}""",
          expected =
              """open class C {
    private fun Channel.Factory.range(start: Int, count: Int, context: CoroutineContext) =
        GlobalScope.produce(context) {
            for (i in start until (start + count)) send(i)
        }
}""",
      )

  /**
   * §4: a call with MULTIPLE lambda arguments (`Encryptor({ … }, { … }, { … })`) that doesn't fit on
   * one line splits one-item-per-line — never fills (several lambdas per line). From Exposed
   * Algorithms.kt:40-45. Regression: the §4 last-item-expansion path kept the leading args inline, but
   * with lambda leading args that produced a fill (`Encryptor({ … }, {` / body / `}, {` / …)).
   */
  @Test
  fun `call-with-multiple-lambda-args-splits-one-per-line`() =
      check(
          input =
              """fun f(): Encryptor {
    return Encryptor({ base64Encoder.encodeToString(encrypt(it.toByteArray())) }, { String(decrypt(base64Decoder.decode(it))) }, { inputLen -> base64EncodedLength(inputLen) })
}""",
          expected =
              """fun f(): Encryptor {
    return Encryptor(
        { base64Encoder.encodeToString(encrypt(it.toByteArray())) },
        { String(decrypt(base64Decoder.decode(it))) },
        { inputLen -> base64EncodedLength(inputLen) },
    )
}""",
      )

  /**
   * §4: last-item expansion (keep the LEADING args inline on the opener line and hang the trailing
   * lambda) applies only when the opener line actually FITS. When the leading arguments overflow it,
   * the call must fall back to one-argument-per-line (the lambda included) — NOT keep every leading
   * arg jammed onto a single over-long opener line. §1 (minimize overflow) picks between the two.
   */
  @Test
  fun `call-with-overflowing-leading-args-and-trailing-lambda-splits-one-per-line`() =
      check(
          input =
              """fun x() {
    configureDefaultConfigRouting(ServerCommand.cdsOptions.configDirectory.resolve("settings.json"), ServerCommand.cdsOptions.advancedJsonPath, ServerCommand.cdsOptions.visualConfigFile, ServerCommand.cdsOptions.customFieldsCsvPath, ServerCommand.cdsOptions.orgCustomFieldsCsvPath, {
        val principal = principal<ConverterAdminPrincipal>()
        if (principal?.confirmed == true) { adminContestInfoFlow } else { nonAdminContestInfoFlow }
    })
}""",
          expected =
              """fun x() {
    configureDefaultConfigRouting(
        ServerCommand.cdsOptions.configDirectory.resolve("settings.json"),
        ServerCommand.cdsOptions.advancedJsonPath,
        ServerCommand.cdsOptions.visualConfigFile,
        ServerCommand.cdsOptions.customFieldsCsvPath,
        ServerCommand.cdsOptions.orgCustomFieldsCsvPath,
        {
            val principal = principal<ConverterAdminPrincipal>()
            if (principal?.confirmed == true) {
                adminContestInfoFlow
            } else {
                nonAdminContestInfoFlow
            }
        },
    )
}""",
      )

  /**
   * §1/§7: when the receiver-through-first-call is too long to attach to the `=` line, §1 (minimize
   * overflow) forces the break after `=`; the chain then sits at a SINGLE indent — receiver and every
   * subsequent `.call` all one level below the `val` line, never a second level (§7 "do not add a
   * second indent level"). From Exposed AliasesTests.kt:120 (deeply nested, so `= receiver…select(…)`
   * overflows). This is the correct forced-break-after-`=` layout, NOT a bug — it is the same
   * single-indent form the `commonConfiguration` case established.
   */
  @Test
  fun `chain-forced-break-after-eq-stays-single-indent`() =
      check(
          input =
              """
class AliasesTests {
    fun aliasedQuery() {
        withTables(EntityTestsData.XTable, EntityTestsData.YTable) {
            val aliasedQuery = EntityTestsData.XTable.select(EntityTestsData.XTable.b1, aliasedExpression).groupBy(EntityTestsData.XTable.b1).alias("maxBoolean")
        }
    }
}""",
          expected =
              """class AliasesTests {
    fun aliasedQuery() {
        withTables(EntityTestsData.XTable, EntityTestsData.YTable) {
            val aliasedQuery =
                EntityTestsData.XTable.select(EntityTestsData.XTable.b1, aliasedExpression)
                .groupBy(EntityTestsData.XTable.b1)
                .alias("maxBoolean")
        }
    }
}""",
      )

  /**
   * §3/§7: when a chain RHS overflows the `=` line but its receiver-through-first-call *fits whole*,
   * keep the receiver on the `=` line (do NOT break after `=`) and wrap each subsequent `.call` one
   * per line. §7 forbids breaking after `=` to start the chain, so this attach-and-wrap form is
   * preferred over the fewer-lines break-after-`=` form. Contrast [chain-forced-break-after-eq-stays-
   * single-indent], where the receiver-first-call itself overflows and so must break after `=`.
   * From kotlinconf-app AdaptiveDetailLayout.kt:44.
   */
  @Test
  fun `chain-attaches-receiver-when-first-call-fits`() =
      check(
          input =
              """fun g() {
    val contentModifier = Modifier.verticalScroll(scrollState).padding(bottom = 24.dp).padding(bottomInsetPadding())
}""",
          expected =
              """fun g() {
    val contentModifier = Modifier.verticalScroll(scrollState)
        .padding(bottom = 24.dp)
        .padding(bottomInsetPadding())
}""",
      )

  /**
   * §2/§3: an ASSIGNMENT (`key = a + b`, not a `val` initializer) whose RHS is a `+`-concat keeps the
   * `=` attached (§3) and wraps the concat's continuation operand at ONE indent below the assignee —
   * NOT glued at the assignee's own column. Regression: an assignment is itself a BINARY_EXPRESSION in
   * the PSI, so the RHS concat saw a BINARY_EXPRESSION parent and took the mixed-operator ZERO-indent
   * path meant for `a - b * c`, dropping the second operand to the assignee's column with no
   * continuation indent. From ICPC-live BasicAuthKey.kt:23.
   */
  @Test
  fun `assignment-concat-rhs-wraps-operand-at-one-indent`() =
      check(
          input =
              """fun f() {
    key = "Basic " + Base64.getEncoder().encodeToString("${'$'}{creds.username}:${'$'}{creds.password}".toByteArray())
}""",
          expected =
              """fun f() {
    key = "Basic " +
        Base64.getEncoder().encodeToString("${'$'}{creds.username}:${'$'}{creds.password}".toByteArray())
}""",
      )

  /**
   * §2/§7: a block-bodied `object` expression used as a chain receiver (`object : X { … }.call()`)
   * keeps its body ONE level below the introducer, not two — the enclosing chain wrap must not push
   * the object body a second level deep. Regression: the body drifted to indent 12 (and `}` to 8).
   * From kotlinx.coroutines DisabledHandlerTest.kt:18.
   */
  @Test
  fun `object-expression-chain-receiver-body-indents-one-level`() =
      check(
          input =
              """class C {
    private val disabledDispatcher = object : Handler() {
        override fun sendMessageAtTime(msg: Message?, uptimeMillis: Long): Boolean {
            if (delegateToSuper) return super.sendMessageAtTime(msg, uptimeMillis)
            return false
        }
    }.asCoroutineDispatcher()
}""",
          expected =
              """class C {
    private val disabledDispatcher = object : Handler() {
        override fun sendMessageAtTime(msg: Message?, uptimeMillis: Long): Boolean {
            if (delegateToSuper) return super.sendMessageAtTime(msg, uptimeMillis)
            return false
        }
    }
        .asCoroutineDispatcher()
}""",
      )

  /**
   * §2/§6: a `+`-concatenation as a call argument forms a flat block — every operand at ONE shared
   * indent (one level below the call opener), never drifting a second level. And when the call is a
   * broken subsequent link of a chain (`…​.because("…" + "…")`), its argument list still indents one
   * level below THAT call (not the chain base). From kotlinx.coroutines AuxBuildConfiguration.kt:38-46.
   * Regression: the concat's second operand drifted to +2, and the chain call's args under-indented.
   */
  @Test
  fun `concat-argument-is-flat-block-in-chain`() =
      check(
          input =
              """
fun f() {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.kotlinx:core")).using(project(":core")).because("Because Kotlin compiler embeddable leaks coroutines into the runtime classpath, " + "triggering all sort of incompatible class changes errors")
    }
}""",
          expected =
              """fun f() {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.kotlinx:core"))
            .using(project(":core"))
            .because(
                "Because Kotlin compiler embeddable leaks coroutines into the runtime classpath, " +
                "triggering all sort of incompatible class changes errors",
            )
    }
}""",
      )

  /**
   * §2/§6: binary operators are treated as infix functions — a MIXED-operator arithmetic expression
   * (`a - b * c(…) - d`) forms a flat block where every operand sits at ONE shared indent, regardless
   * of operator precedence / how the parse tree nests. Regression: the higher-precedence `*`
   * sub-expression drifted its operand a SECOND level deep (`getProblemLooseScorePerMinute(…)` at +8
   * instead of +4), because a binary expression nested inside another binary expression opened its
   * own extra continuation indent. From a Codeforces scoring expression.
   */
  @Test
  fun `mixed-operator-expression-is-flat-block`() =
      check(
          input =
              """fun f() {
    val score = maxOf(maxScore * 3 / 10, ceil(maxScore - submission.relativeTimeSeconds.inWholeMinutes * getProblemLooseScorePerMinute(maxScore, contestLength.inWholeMinutes)) - 50 * wrongAttempts)
}""",
          expected =
              """fun f() {
    val score = maxOf(
        maxScore * 3 / 10,
        ceil(
            maxScore -
            submission.relativeTimeSeconds.inWholeMinutes *
            getProblemLooseScorePerMinute(maxScore, contestLength.inWholeMinutes),
        ) - 50 * wrongAttempts,
    )
}""",
      )

  /** §2/§6: the same flat-operand rule for a `+`-concat as a direct (non-chained) call argument. */
  @Test
  fun `concat-argument-is-flat-block-direct`() =
      check(
          input =
              """fun f() {
    check("Because Kotlin compiler embeddable leaks coroutines into the runtime classpath here now, " + "triggering all sort of incompatible class changes errors")
}""",
          expected =
              """fun f() {
    check(
        "Because Kotlin compiler embeddable leaks coroutines into the runtime classpath here now, " +
        "triggering all sort of incompatible class changes errors",
    )
}""",
      )

  /**
   * §12 (2026-07-06 rule): an argument-less annotation goes on its OWN line above a regular
   * (standalone) property — it stays inline only on a value parameter, parameter-property, or primary
   * constructor. Regression: `@JvmStatic`/`@Volatile` on a property used to stay inline (the old
   * `INLINE_MODIFIER_ANNOTATIONS` set applied to all declarations).
   */
  @Test
  fun `argumentless-annotation-on-regular-property-own-line`() =
      check(
          input =
              """class C {
    @JvmStatic val instance = create()
    @Volatile var running = false
}""",
          expected =
              """class C {
    @JvmStatic
    val instance = create()
    @Volatile
    var running = false
}""",
      )

  /**
   * §12: an argument-less annotation stays inline on a primary constructor and on parameter-properties;
   * only annotations that carry arguments (or ones on non-parameter declarations) break to their own
   * line.
   */
  @Test
  fun `argumentless-annotation-on-constructor-and-parameter-inline`() =
      check(
          input =
              """public class Scope<T> @PublishedApi internal constructor(@PublishedApi internal val flow: SharedFlow<T>, private val waiting: MutableStateFlow<Int>) {}""",
          expected =
              """public class Scope<T> @PublishedApi internal constructor(
    @PublishedApi internal val flow: SharedFlow<T>,
    private val waiting: MutableStateFlow<Int>,
) {}""",
      )

  /**
   * §9/§12: a type-use annotation is part of the type — it stays inline and glues to the parameter's
   * `:`, never dropped onto its own line. Regression: `@Composable () -> Unit` split into three lines
   * (`compactHeader:` / `@Composable` / `() -> Unit,`). From kotlinconf-app AdaptiveDetailLayout.kt:30.
   */
  @Test
  fun `type-use-annotation-stays-inline-with-type`() =
      check(
          input =
              """@Composable
fun AdaptiveDetailLayout(
    compactHeader: @Composable () -> Unit,
    compactContentHeader: @Composable ColumnScope.() -> Unit,
    onBack: () -> Unit,
) {}""",
          expected =
              """@Composable
fun AdaptiveDetailLayout(
    compactHeader: @Composable () -> Unit,
    compactContentHeader: @Composable ColumnScope.() -> Unit,
    onBack: () -> Unit,
) {}""",
      )

  /**
   * §12: an annotation that carries arguments goes on its OWN line above what it annotates — even on a
   * plain statement, not just a declaration. Here `@Suppress("…") while (…) {}` splits the annotation
   * onto its own line although the whole thing fits in 100 columns. From kotlinx.coroutines
   * BufferedChannel.kt:1411. Regression: a UNIFIED break kept it glued while it fit.
   */
  @Test
  fun `argument-carrying-annotation-on-statement-own-line`() =
      check(
          input =
              """fun f() {
    @Suppress("ControlFlowWithEmptyBody") while (bufferEndCounter <= globalIndex) {}
}""",
          expected =
              """fun f() {
    @Suppress("ControlFlowWithEmptyBody")
    while (bufferEndCounter <= globalIndex) {}
}""",
      )

  /**
   * §3/§12: when an argument-carrying annotation on a constructor parameter-property breaks onto its
   * own line (§12), the parameter's `= <default>` initializer must stay attached when the default
   * value fits (§3) — it must NOT break after `=`. Regression: the §12 annotation break made the whole
   * parameter level multi-line, and the greedy UNIFIED break on `=` fired off that, splitting a short
   * `= id.value` / `= null` onto its own line.
   */
  @Test
  fun `annotated-constructor-param-default-stays-attached`() =
      check(
          input =
              """@Serializable
public data class AccountInfo(
    val id: AccountId,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val username: String = id.value,
    val type: String,
    @Serializable(with = AccountCredentialSerializer::class) val password: Credential? = null,
    val name: String? = null,
)""",
          expected =
              """@Serializable
public data class AccountInfo(
    val id: AccountId,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val username: String = id.value,
    val type: String,
    @Serializable(with = AccountCredentialSerializer::class)
    val password: Credential? = null,
    val name: String? = null,
)""",
      )

  /**
   * §5/§12: an expression body whose RHS is a parenthesized expression that leads with an annotation
   * (`= (@OptIn(…) expr ?: …)`) wraps with collapse-opener / stack-closer: `(` opens a one-level body
   * indent, the `@OptIn` annotation takes its own line (§12), and `)` returns to the opener's indent.
   * Regression: the annotation used to glue to `(` and the body drifted to the outer (function) indent.
   * From Exposed AbstractQuery.kt:153.
   */
  @Test
  fun `annotated-parenthesized-expression-body-wraps-with-body-indent`() =
      check(
          input =
              """class C {
    fun isForUpdate(): Boolean = (@OptIn(InternalApi::class)
    forUpdate?.let { it != ForUpdateOption.NoForUpdateOption } ?: false)
}""",
          expected =
              """class C {
    fun isForUpdate(): Boolean = (
        @OptIn(InternalApi::class)
        forUpdate?.let { it != ForUpdateOption.NoForUpdateOption } ?: false
    )
}""",
      )

  /**
   * §13: a lambda's `->` never separates from its parameters: `{ continuation ->` stays on one line even
   * when the opener is too long — optofmt breaks earlier (here after `=`) rather than dropping `->`
   * onto its own line. From kotlinx.coroutines BufferedChannel.kt:129.
   */
  /**
   * §13: a lambda's parameters never separate from `{` either: `{ cont ->` stays whole even when the
   * opener is too long — optofmt breaks earlier (after `=`) rather than dropping the parameter to its
   * own line (`{\n    cont ->`). From kotlinx.coroutines BufferedChannel.kt:220.
   */
  @Test
  fun `lambda-params-never-separate-from-brace`() =
      check(
          input =
              """class C {
    internal open suspend fun sendBroadcast(element: E): Boolean = suspendCancellableCoroutine {
        cont ->
        check(onUndeliveredElement == null) { "msg" }
        sendImpl(element, SendBroadcast(cont))
    }
}""",
          expected =
              """class C {
    internal open suspend fun sendBroadcast(element: E): Boolean =
        suspendCancellableCoroutine { cont ->
            check(onUndeliveredElement == null) { "msg" }
            sendImpl(element, SendBroadcast(cont))
        }
}""",
      )

  @Test
  fun `lambda-arrow-never-separates-from-params`() =
      check(
          input =
              """class C {
    private suspend fun onClosedSend(element: E): Unit = suspendCancellableCoroutine { continuation ->
        onUndeliveredElement?.foo(element)?.let {
            it.addSuppressed(sendException)
            return@suspendCancellableCoroutine
        }
        continuation.resumeWithStackTrace(sendException)
    }
}""",
          expected =
              """class C {
    private suspend fun onClosedSend(element: E): Unit =
        suspendCancellableCoroutine { continuation ->
            onUndeliveredElement?.foo(element)?.let {
                it.addSuppressed(sendException)
                return@suspendCancellableCoroutine
            }
            continuation.resumeWithStackTrace(sendException)
        }
}""",
      )

  /**
   * §13: multiple lambda parameters are never split apart from one another. When a `{ p1, p2, p3 ->`
   * header is too long, optofmt wraps the enclosing call's arguments instead — the params stay on one
   * line. Regression: the params split one-per-line (`{ agenda,\n speakers,\n conferenceInfo ->`).
   * From kotlinconf-app AboutConferenceViewModel.kt:34.
   */
  @Test
  fun `lambda-params-stay-together-wrap-call-args-instead`() =
      check(
          input =
              """class C {
    val events: StateFlow<List<AboutConferenceEvent>> =
        combine(service.agenda, service.speakers, service.conferenceInfo) { agenda, speakers, conferenceInfo ->
            val speakersById = speakers.associateBy { it.id }
            speakersById
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}""",
          expected =
              """class C {
    val events: StateFlow<List<AboutConferenceEvent>> = combine(
            service.agenda,
            service.speakers,
            service.conferenceInfo,
        ) { agenda, speakers, conferenceInfo ->
            val speakersById = speakers.associateBy { it.id }
            speakersById
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}""",
      )

  /**
   * §9/§13: a typed lambda parameter (`{ cause: Throwable? -> … }`) keeps its type glued to the `:` —
   * the type is never pushed onto its own line. When the header overflows, optofmt wraps earlier
   * (breaks after `=`), never inside `cause: Throwable?`. Regression: the type split to a new line
   * (`{ cause:\n    Throwable? ->`). From kotlinx.coroutines Deprecated.kt:53.
   */
  @Test
  fun `typed-lambda-param-keeps-type-glued-to-colon`() =
      check(
          input =
              """internal fun consumesAll(vararg channels: ReceiveChannel<*>): CompletionHandler = { cause: Throwable? ->
    var exception: Throwable? = null
    exception?.let { throw it }
}""",
          expected =
              """internal fun consumesAll(vararg channels: ReceiveChannel<*>): CompletionHandler =
    { cause: Throwable? ->
        var exception: Throwable? = null
        exception?.let { throw it }
    }""",
      )

  @Test
  fun `trailing-lambda`() =
      check(
          input = """fun run() { executeWithRetryPolicy(maximumRetryCount, backoffStrategy, { requestContext: RequestContext -> requestContext.proceed() }) }""",
          expected = """fun run() {
    executeWithRetryPolicy(maximumRetryCount, backoffStrategy, { requestContext: RequestContext ->
        requestContext.proceed()
    })
}""",
      )

  @Test
  fun `compact-header`() =
      check(
          input = """public class SharedFlowSubscriptionScope<T> @PublishedApi internal constructor(@PublishedApi internal val flow: SharedFlow<T>, private val subscriptionWaitingFlow: MutableStateFlow<Int>) {
}""",
          expected = """public class SharedFlowSubscriptionScope<T> @PublishedApi internal constructor(
    @PublishedApi internal val flow: SharedFlow<T>,
    private val subscriptionWaitingFlow: MutableStateFlow<Int>,
) {}""",
      )

  @Test
  fun `comment-preservation`() =
      check(
          input = """/**
 * Ideally, all this information should be received from the contest system.
 * Unfortunately, in the real world, it is not always possible, or information
 * can be not fully correct or convenient to display.
 */
class ContestConfigOverrides""",
          expected = """/**
 * Ideally, all this information should be received from the contest system.
 * Unfortunately, in the real world, it is not always possible, or information
 * can be not fully correct or convenient to display.
 */
class ContestConfigOverrides""",
      )

  /**
   * §8/§14: a trailing `// note` on the LAST parameter of a wrapped list stays inline on that
   * parameter's line (after the §14 trailing comma) — it is not pushed onto its own line above the
   * closing `)`. Regression: the dropped source trailing comma prevented the trailing-comment flush
   * from reaching the comment, so it was emitted as a leading comment of `)` on its own line.
   */
  @Test
  fun `trailing-comment-on-last-parameter-stays-inline`() =
      check(
          input =
              """data class ContestConfig(
    val runIds: List<RunId>,
    @Required val priority: Int = 0,
    @Required val tags: List<String> = emptyList(), // todo: support tags in CLICS parser
)""",
          expected =
              """data class ContestConfig(
    val runIds: List<RunId>,
    @Required val priority: Int = 0,
    @Required val tags: List<String> = emptyList(), // todo: support tags in CLICS parser
)""",
      )

  @Test
  fun `grouped-declarations`() =
      check(
          input = """typealias TeamId = StrongId<TeamTag>
typealias RunId = StrongId<RunTag>
typealias MessageId = StrongId<MessageTag>""",
          expected = """typealias TeamId = StrongId<TeamTag>
typealias RunId = StrongId<RunTag>
typealias MessageId = StrongId<MessageTag>""",
      )

  @Test
  fun `long-parameter-list`() =
      check(
          input = """fun registerEventListener(eventType: EventType, listenerPriority: ListenerPriority, listenerCallback: EventListener) { installListener() }""",
          expected = """fun registerEventListener(
    eventType: EventType,
    listenerPriority: ListenerPriority,
    listenerCallback: EventListener,
) {
    installListener()
}""",
      )

  /**
   * §14: a comma list that stays on one line has NO trailing comma; the same list, when it must wrap
   * one-per-line, ends with a trailing comma after the final argument. The comma is a property of the
   * layout (added when wrapped, dropped when flat), so both forms are idempotent.
   */
  @Test
  fun `trailing-comma-only-when-list-wraps`() {
    check(input = """fun f() { call(alpha, beta, gamma) }""", expected = """fun f() {
    call(alpha, beta, gamma)
}""")
    check(
        input =
            """fun f() { callWithLongArgs(firstArgumentIsQuiteLong, secondArgumentIsQuiteLong, thirdArgumentIsQuiteLong, fourthArgument) }""",
        expected =
            """fun f() {
    callWithLongArgs(
        firstArgumentIsQuiteLong,
        secondArgumentIsQuiteLong,
        thirdArgumentIsQuiteLong,
        fourthArgument,
    )
}""")
  }

  /**
   * §4 custom formatting: a line break right after `(` forces a call's arguments one-per-line even
   * though they would fit on a single line; without the break the same call collapses. The forced
   * form carries a §14 trailing comma and is idempotent.
   */
  @Test
  fun `custom-expand-call-args-after-open-paren`() {
    check(input = """val x = foo(a, b, c)""", expected = """val x = foo(a, b, c)""")
    check(
        input = "val x = foo(\n    a, b, c)",
        expected =
            """val x = foo(
    a,
    b,
    c,
)""")
  }

  /**
   * §4 custom formatting: a line break right after `(` in a declaration's parameter list keeps the
   * parameters one-per-line even though they fit; removing the break collapses them.
   */
  @Test
  fun `custom-expand-parameter-list-after-open-paren`() {
    check(input = """fun f(a: Int, b: Int) {}""", expected = """fun f(a: Int, b: Int) {}""")
    check(
        input = "fun f(\n    a: Int, b: Int) {}",
        expected =
            """fun f(
    a: Int,
    b: Int,
) {}""")
  }

  /**
   * §13 custom formatting: a line break right after the lambda header (`->`, or `{` when there are
   * no parameters) keeps the body on its own line instead of collapsing to `{ … }`.
   */
  @Test
  fun `custom-expand-lambda-after-arrow-or-brace`() {
    check(input = """val x = items.map { it -> it.name }""", expected = """val x = items.map { it -> it.name }""")
    check(
        input = "val x = items.map { it ->\n    it.name }",
        expected =
            """val x = items.map { it ->
    it.name
}""")
    check(
        input = "val x = run {\n    doThing() }",
        expected =
            """val x = run {
    doThing()
}""")
  }

  @Test
  fun `elvis-wrap`() =
      check(
          input = """fun f(): PetType { return findPetTypes.find { it.name == text } ?: throw ParseException("type not found: " + text, 0) }""",
          expected = """fun f(): PetType {
    return findPetTypes.find { it.name == text }
        ?: throw ParseException("type not found: " + text, 0)
}""",
      )

  /**
   * §6: an elvis `?:` stays on the same line as its left-hand side when the whole expression fits
   * (§1); it only wraps (operator at the start of the continuation line) when it does not fit — see
   * [`elvis-wrap`]. The break is a candidate, never forced.
   */
  @Test
  fun `elvis-inline-when-fits`() {
    check(input = """val name = findName() ?: error("missing")""", expected = """val name = findName() ?: error("missing")""")
    check(
        input =
            """val displayName = lookupPreferredDisplayName(userId, localeSettings) ?: error("no display name is available for the requested user")""",
        expected =
            """val displayName = lookupPreferredDisplayName(userId, localeSettings)
    ?: error("no display name is available for the requested user")""")
  }

  @Test
  fun `annotation-placement`() =
      check(
          input = """@JvmName("other") fun testSomething() {}""",
          expected = """@JvmName("other")
fun testSomething() {}""",
      )

  @Test
  fun `accessor-placement`() =
      check(
          input = """val placeOfGetter: String get() = "hello"""",
          expected = """val placeOfGetter: String get() = "hello"""",
      )

  /**
   * §11: two adjacent properties whose single expression-bodied accessors optofmt pulls inline
   * (`val x: T get() = …`) are one-line declarations of the same kind, so they stay TIGHT — the
   * source break before each `get()` collapses and NO blank line is forced between them. Regression:
   * the blank-line rule judged one-line-ness from the source text (which split `get()` onto its own
   * line) and so hit the "property-with-accessor → force a blank" clause, inserting a spurious blank
   * between the collapsed one-liners.
   */
  @Test
  fun `inlined-accessor-properties-stay-tight`() =
      check(
          input =
              """public sealed interface CatsSettings {
    public val timeZone: TimeZone
        get() = TimeZone.of("Asia/Vladivostok")
    public val resultType: ContestResultType
        get() = ContestResultType.ICPC
}""",
          expected =
              """public sealed interface CatsSettings {
    public val timeZone: TimeZone get() = TimeZone.of("Asia/Vladivostok")
    public val resultType: ContestResultType get() = ContestResultType.ICPC
}""",
      )

  /**
   * §1/§3: an expression-bodied accessor stays attached to the property line even when its body is a
   * multi-line block — `val x: T get() = lock.withLock {` keeps `get()` on the property line and hangs
   * the block one indent in, rather than dropping `get()` onto its own line just because the body is
   * multi-line. From kotlinx.coroutines BroadcastChannel.kt:358.
   */
  @Test
  fun `block-valued-accessor-stays-on-property-line`() =
      check(
          input =
              """class C<E> {
    @Suppress("UNCHECKED_CAST")
    val valueOrNull: E?
        get() = lock.withLock {
            if (isClosedForReceive) null
            else if (lastConflatedElement === NO_ELEMENT) null
            else lastConflatedElement as E
        }
}""",
          expected =
              """class C<E> {
    @Suppress("UNCHECKED_CAST")
    val valueOrNull: E? get() = lock.withLock {
        if (isClosedForReceive) null
        else if (lastConflatedElement === NO_ELEMENT) null else lastConflatedElement as E
    }
}""",
      )

  /**
   * §2/§8: a comment on its own line before an accessor (`val x: T` / `// note` / `get() = …`) is a
   * leading child of the accessor. It must stay on its own line (§8, not pulled up onto the type
   * line) and the accessor sits ONE indent in under the property. Regression: optofmt pulled the
   * comment up onto the `Boolean` line and put `get()` at the property level (indent 4, not 8), and
   * the result was not even idempotent. From kotlinx.coroutines BroadcastChannel.kt:316.
   */
  @Test
  fun `commented-property-accessor-indents-one-level`() =
      check(
          input =
              """class C {
    override val isClosedForSend: Boolean
        // Protect by lock to synchronize with `close(..)` / `cancel(..)`.
        get() = lock.withLock { super.isClosedForSend }
}""",
          expected =
              """class C {
    override val isClosedForSend: Boolean
        // Protect by lock to synchronize with `close(..)` / `cancel(..)`.
        get() = lock.withLock { super.isClosedForSend }
}""",
      )

  @Test
  fun `control-flow-lambda`() =
      check(
          input = """fun lambdasWithReturns(nullableString: String?) { nullableString?.let { return } }""",
          expected = """fun lambdasWithReturns(nullableString: String?) {
    nullableString?.let { return }
}""",
      )

  @Test
  fun `when-comma-condition`() =
      check(
          input = """fun f(x: Int) {
when (x) {
0, 1 -> println("a or b")
}
}""",
          expected = """fun f(x: Int) {
    when (x) {
        0, 1 -> println("a or b")
    }
}""",
      )

  @Test
  fun `when-comma-condition-extra-1-idempotent`() =
      idempotent("""fun f() {
    when (it.resolvedCall.resultingDescriptor) {
        is LocalVariableDescriptor,
        is ValueParameterDescriptor,
        is ReceiverParameterDescriptor -> true
        else -> false
    }
}""")

  @Test
  fun `generic-type-arg-economy`() =
      check(
          input = """class C {
internal val pendingInitializationLambdas = IdentityHashMap<Entity<Any>, MutableList<(Entity<Any>) -> Unit>>()
}""",
          expected = """class C {
    internal val pendingInitializationLambdas =
        IdentityHashMap<Entity<Any>, MutableList<(Entity<Any>) -> Unit>>()
}""",
      )

  @Test
  fun `enum-entries-preserve-author-blank-lines`() =
      check(
          input = """enum class PenaltyRoundingMode {
  /** Round down. */
  @SerialName("down")
  EACH_SUBMISSION_DOWN_TO_MINUTE,

  /** Round up. */
  @SerialName("up")
  EACH_SUBMISSION_UP_TO_MINUTE,

  /** Sum down. */
  @SerialName("sum")
  SUM_DOWN_TO_MINUTE,
}""",
          expected = """enum class PenaltyRoundingMode {
    /** Round down. */
    @SerialName("down")
    EACH_SUBMISSION_DOWN_TO_MINUTE,

    /** Round up. */
    @SerialName("up")
    EACH_SUBMISSION_UP_TO_MINUTE,

    /** Sum down. */
    @SerialName("sum")
    SUM_DOWN_TO_MINUTE,
}""",
      )

  /**
   * §2/§7: a call with a property-run receiver (`context.generator.generateFile(…) { … }`) whose
   * value arguments wrap is still the atomic receiver-through-first-call — the trailing lambda hangs
   * ONE indent below the chain line, not two. The multi-call chain path used to wrap the whole chain
   * in a continuation block, pushing the lambda body (and its `}`) an extra indent too deep.
   */
  @Test
  fun `wrapping-call-on-property-run-receiver-trailing-lambda-body-single-indent`() =
      check(
          input =
              """fun f() {
    context.generator.generateFile(
        dependencies = Dependencies(true, obj.containingFile),
        packageName = "org.icpclive.clics.events",
        fileName = obj.eventName
    ) {
        handle(obj)
    }
}""",
          expected =
              """fun f() {
    context.generator.generateFile(
        dependencies = Dependencies(true, obj.containingFile),
        packageName = "org.icpclive.clics.events",
        fileName = obj.eventName,
    ) {
        handle(obj)
    }
}""",
      )

  /**
   * §7: a SOLE trailing-lambda tail applied directly to the receiver-through-first-call (whose own
   * first call carries a multiline lambda) stays ATTACHED to the `}` (`}.filterValues { … }`) when it
   * fits — it is not dropped onto its own line. Only a genuine multi-call chain (an intermediate
   * `.call` precedes the trailing lambda) puts each subsequent call on its own line (see
   * `call-chain-of-trailing-lambdas`).
   */
  @Test
  fun `sole-trailing-lambda-tail-after-grouped-lambda-call-attaches`() =
      check(
          input =
              """fun f() {
    val filteredRules = v.mapValues { (_, value) ->
        if (value is JsonObject) JsonObject(value) else value
    }.filterValues { it !is JsonNull }
}""",
          expected =
              """fun f() {
    val filteredRules = v.mapValues { (_, value) ->
        if (value is JsonObject) JsonObject(value) else value
    }.filterValues { it !is JsonNull }
}""",
      )

  /**
   * §5/§7: a sole trailing-lambda tail whose OWN lambda body is MULTILINE still hugs the grouped
   * receiver-first-call's `}` (`}.map {`) — the tail's `{`-header fits on the `}` line, so only its
   * body wraps below (at ONE indent, not two). This holds even when the author STAIRCASED the tail
   * onto its own line in source: the hug is the canonical form for this shape. From ICPC-live
   * CFDataSource.kt:57. Regression: (a) the fill-break run measured the tail's whole (multi-line)
   * flat width as unbreakably wide and dropped `.map` to its own line; (b) even when hugged, the
   * body drifted a second indent deep. Contrast `chain-with-single-line-lambda-first-call-...`, where
   * the receiver-first-call's lambda is single-line so the chain staircases per §7 instead.
   */
  @Test
  fun `sole-trailing-lambda-tail-with-multiline-body-hugs-even-when-source-staircased`() =
      check(
          input =
              """fun f() {
    val a = DataLoader.json<CFStatusWrapper<CFStandings>>(
        networkSettings = settings.network,
    ) {
        apiRequestUrl("contest.standings")
    }
        .map {
            it.unwrap()
        }
}""",
          expected =
              """fun f() {
    val a = DataLoader.json<CFStatusWrapper<CFStandings>>(
        networkSettings = settings.network,
    ) {
        apiRequestUrl("contest.standings")
    }.map {
        it.unwrap()
    }
}""",
      )

  /**
   * §7: a chain whose base receiver is ITSELF a trailing-lambda call (`flow { … }`) with a SOLE
   * trailing-lambda tail applied directly to it (`.none { … }`) keeps the tail ATTACHED to the base's
   * `}` (`}.none {`) and its own body at one indent — it does not drop `.none` onto its own line nor
   * drift the base body to a second indent. This is the same shape as `v.mapValues { … }.filterValues
   * { … }` (see `sole-trailing-lambda-tail-after-grouped-lambda-call-attaches`), but here the base is a
   * bare call rather than a `receiver.call`.
   */
  @Test
  fun `chain-with-base-call-and-sole-trailing-lambda-tail-attaches`() =
      check(
          input =
              """fun f() {
    val x = flow {
        emit(1)
        emit(2)
    }.none { it == 2 }
}""",
          expected =
              """fun f() {
    val x = flow {
        emit(1)
        emit(2)
    }.none { it == 2 }
}""",
      )

  /**
   * §5/§7: when such a chain (`flow { … }.none { … }`) is a call's SOLE argument, it is block-like and
   * hangs off the call opener (`assertFalse(flow {` … `})`) instead of being pushed onto its own line
   * with a leading `(` break and a trailing comma. (Original: a kotlinx.coroutines flow test.)
   */
  @Test
  fun `sole-block-like-chain-argument-hugs-call-opener`() =
      check(
          input =
              """@Test
fun testNoneShortCircuit() = runTest {
    assertFalse(flow {
        emit(1)
        emit(2)
        expectUnreached()
    }.none {
        it == 2
    })
}""",
          expected =
              """@Test
fun testNoneShortCircuit() = runTest {
    assertFalse(flow {
        emit(1)
        emit(2)
        expectUnreached()
    }.none {
        it == 2
    })
}""",
      )

  /**
   * §7 author preservation: a member-access chain the author wrote across multiple lines stays
   * staircased (one `.call` per line) even when it would fit on one line — the break after the
   * receiver-through-first-call (`Flowable.fromArray(1)` ⏎ `.onBackpressureDrop()`) is preserved rather
   * than collapsed. (Original: kotlinx.coroutines reactive BackpressureTest.)
   */
  @Test
  fun `author-staircased-chain-is-preserved-even-when-it-fits`() =
      check(
          input =
              """fun testBackpressureDropDirect() = runTest {
    expect(1)
    Flowable.fromArray(1)
        .onBackpressureDrop()
        .collect {
            assertEquals(1, it)
            expect(2)
        }
    finish(3)
}""",
          expected =
              """fun testBackpressureDropDirect() = runTest {
    expect(1)
    Flowable.fromArray(1)
        .onBackpressureDrop()
        .collect {
            assertEquals(1, it)
            expect(2)
        }
    finish(3)
}""",
      )

  /**
   * §7 author preservation: the same chain with one more link (`.asFlow()`) stays fully staircased —
   * every subsequent `.call` on its own line, never collapsed onto the receiver line.
   */
  @Test
  fun `author-staircased-chain-with-extra-link-stays-fully-broken`() =
      check(
          input =
              """fun testBackpressureDropFlow() = runTest {
    expect(1)
    Flowable.fromArray(1)
        .onBackpressureDrop()
        .asFlow()
        .collect {
            assertEquals(1, it)
            expect(2)
        }
    finish(3)
}""",
          expected =
              """fun testBackpressureDropFlow() = runTest {
    expect(1)
    Flowable.fromArray(1)
        .onBackpressureDrop()
        .asFlow()
        .collect {
            assertEquals(1, it)
            expect(2)
        }
    finish(3)
}""",
      )

  /**
   * §7: a chain the author wrote on ONE line and which fits stays on one line — the author-preservation
   * only KEEPS an existing staircase, it does not introduce one (contrast the two tests above).
   */
  @Test
  fun `one-line-chain-that-fits-stays-collapsed`() =
      check(
          input =
              """fun f() {
    val x = obj.foo(1)
        .bar(2)
        .baz(3)
    val y = obj.foo(1).bar(2).baz(3)
}""",
          expected =
              """fun f() {
    val x = obj.foo(1)
        .bar(2)
        .baz(3)
    val y = obj.foo(1).bar(2).baz(3)
}""",
      )

  /**
   * §3 author-preserving single-line RHS: when the author broke the line right after the introducer
   * and the WHOLE right-hand side fits on that one line, keep it there rather than pulling it back
   * onto the introducer's line and splitting the `if/else`. (Original: kotlinx.coroutines
   * Deferred.awaitAll.)
   */
  @Test
  fun `source-broken-if-else-expression-body-stays-on-one-line`() =
      check(
          input =
              """public suspend fun <T> awaitAll(vararg deferreds: Deferred<T>): List<T> =
    if (deferreds.isEmpty()) emptyList() else AwaitAll(deferreds).await()""",
          expected =
              """public suspend fun <T> awaitAll(vararg deferreds: Deferred<T>): List<T> =
    if (deferreds.isEmpty()) emptyList() else AwaitAll(deferreds).await()""",
      )

  /**
   * §7 author-preserving single-line RHS: a call-chain the author placed on its own line after `=`
   * stays whole rather than attaching the receiver-through-first-call and dropping the trailing
   * `.call` to its own line. (Original: kotlinx.coroutines ReactiveFlow.awaitFirstOrNull.)
   */
  @Test
  fun `source-broken-chain-expression-body-stays-on-one-line`() =
      check(
          input =
              """public suspend fun <T> Flow.Publisher<T>.awaitFirstOrNull(): T =
    FlowAdapters.toPublisher(this).awaitFirstOrNull()""",
          expected =
              """public suspend fun <T> Flow.Publisher<T>.awaitFirstOrNull(): T =
    FlowAdapters.toPublisher(this).awaitFirstOrNull()""",
      )

  /**
   * §2/§7: a call chain whose BASE receiver is itself a call that wraps its own arguments
   * (`flowOf(a, b, c).map { … }.toList()`) keeps those arguments at a SINGLE indent below the base
   * call line and closes its `)` at the base-call column — not a second level in. The enclosing chain
   * block supplies the one continuation indent, so the base call is emitted at a compensating negative
   * indent; the subsequent `.call`s wrap at that same single indent. Regression: the base call's args
   * drifted a second level deep (chain block + arg list) and its `)` sat at the chain-step column.
   */
  @Test
  fun `chain-with-wrapping-base-call-keeps-args-single-indent`() =
      check(
          input =
              """object AfterFirstOkTest {
    @Test
    fun simple() {
        TestData.run {
            val r = runBlocking {
                flowOf(
                    InfoUpdate(info),
                    RunUpdate(RunInfo("1".toRunId(), RunResult.ICPC(Verdict.Accepted), problemIdA, teamId1, 10.minutes, null)),
                    RunUpdate(RunInfo("2".toRunId(), RunResult.InProgress(1.0), problemIdA, teamId1, 11.minutes, null)),
                )
                    .markSubmissionAfterFirstOk()
                    .filterIsInstance<RunUpdate>()
                    .map {
                        when (val r = it.newInfo.result) {
                            is RunResult.ICPC -> r.isAfterFirstOk
                            is RunResult.IOI -> false
                            is RunResult.InProgress -> r.isAfterFirstOk
                        }
                    }
                    .toList()
            }
            assertEquals(listOf(false, true), r)
        }
    }
}""",
          expected =
              """object AfterFirstOkTest {
    @Test
    fun simple() {
        TestData.run {
            val r = runBlocking {
                flowOf(
                    InfoUpdate(info),
                    RunUpdate(RunInfo(
                        "1".toRunId(),
                        RunResult.ICPC(Verdict.Accepted),
                        problemIdA,
                        teamId1,
                        10.minutes,
                        null,
                    )),
                    RunUpdate(RunInfo(
                        "2".toRunId(),
                        RunResult.InProgress(1.0),
                        problemIdA,
                        teamId1,
                        11.minutes,
                        null,
                    )),
                )
                    .markSubmissionAfterFirstOk()
                    .filterIsInstance<RunUpdate>()
                    .map {
                        when (val r = it.newInfo.result) {
                            is RunResult.ICPC -> r.isAfterFirstOk
                            is RunResult.IOI -> false
                            is RunResult.InProgress -> r.isAfterFirstOk
                        }
                    }
                    .toList()
            }
            assertEquals(listOf(false, true), r)
        }
    }
}""",
      )

  /**
   * The single-line-RHS collapse is NOT forced: when the author wrote the RHS attached to the
   * introducer, optofmt keeps its default attach-and-wrap layout (the collapse only PRESERVES an
   * author's existing break — see [source-broken-chain-expression-body-stays-on-one-line]).
   */
  @Test
  fun `source-attached-chain-expression-body-keeps-default-attach`() =
      check(
          input =
              """public suspend fun <T> Flow.Publisher<T>.awaitFirstOrNull(): T = FlowAdapters.toPublisher(this).awaitFirstOrNull()""",
          expected =
              """public suspend fun <T> Flow.Publisher<T>.awaitFirstOrNull(): T = FlowAdapters.toPublisher(this)
    .awaitFirstOrNull()""",
      )

  /**
   * §1/§7: an expression body `= receiver.call(args) { … }.tail()` that must break after `=`, where
   * the receiver-through-first-call carries a MULTILINE trailing lambda and a lambda-free tail hugs
   * its closing `}`. The chain sits at a single indent (`comments.selectForSlug` on the break line),
   * the lambda body hangs ONE level below it, its `}` returns to the chain line, and `.executeAsList()`
   * hugs that `}`. Regression: as a broken introducer RHS the chain's continuation block sat AT the
   * receiver-line column (the introducer break moved the open column but not the level), so the mid-
   * chain grouped lambda's body/`}` — positioned by a block-relative negative indent calibrated for a
   * statement-shaped chain one level deeper — landed a level too shallow (body at the receiver column,
   * `}` at column 0). Fixed by wrapping this shape's broken candidate in a real indent level (see
   * [KmpAstVisitor.chainFirstCallHasMultilineTrailingLambda]).
   */
  @Test
  fun `broken-eq-chain-mid-lambda-tail-hugs-and-indents-body`() =
      check(
          input =
              """fun findCommentsForSlug(slug: Slug): List<Comment> =
    comments.selectForSlug(slug) { commentId, body, createdAt, updatedAt, username, bio, image ->
        Comment(
            commentId,
            createdAt,
            updatedAt,
            body,
            author = Profile(username.value, bio, image, following = false),
        )
    }.executeAsList()""",
          expected =
              """fun findCommentsForSlug(slug: Slug): List<Comment> =
    comments.selectForSlug(slug) { commentId, body, createdAt, updatedAt, username, bio, image ->
        Comment(
            commentId,
            createdAt,
            updatedAt,
            body,
            author = Profile(username.value, bio, image, following = false),
        )
    }.executeAsList()""",
      )

  /**
   * §5/§7 idempotency: the SAME chain as [broken-eq-chain-mid-lambda-tail-hugs-and-indents-body] but
   * with the grouped lambda's single body statement written on ONE line in the SOURCE. The lambda-free
   * tail `.executeAsList()` must still hug the lambda's closing `}` (`}.executeAsList()`), so the
   * output is a fixpoint — reformatting the tail-on-its-own-line rendering (which a naive layout would
   * produce first) converges immediately. Regression: [KmpAstVisitor.partLambdaRendersMultiline]
   * judged a single-statement body multiline ONLY from source newlines, so a body written on one line
   * (but too wide to fit) was classed single-line — the tail staircased onto its own line, then the
   * next pass (now multiline in source) hugged it. Fixed by also treating a body whose flat width
   * exceeds the column limit as multiline (a whitespace-invariant test, so both passes agree).
   */
  @Test
  fun `broken-eq-chain-mid-lambda-tail-hugs-when-body-source-single-line`() =
      check(
          input =
              "fun findCommentsForSlug(slug: Slug): List<Comment> = comments.selectForSlug(slug) " +
                  "{ commentId, body, createdAt, updatedAt, username, bio, image -> " +
                  "Comment(commentId, createdAt, updatedAt, body, " +
                  "author = Profile(username.value, bio, image, following = false)) }.executeAsList()",
          expected =
              """fun findCommentsForSlug(slug: Slug): List<Comment> =
    comments.selectForSlug(slug) { commentId, body, createdAt, updatedAt, username, bio, image ->
        Comment(
            commentId,
            createdAt,
            updatedAt,
            body,
            author = Profile(username.value, bio, image, following = false),
        )
    }.executeAsList()""",
      )

  /**
   * §6 (with §4/§5): an elvis `?:` whose right-hand operand hangs a MULTILINE trailing lambda
   * (`x ?: Foo(a).also { … }`) keeps the `?:` ATTACHED to its left-hand side and lets the lambda body
   * hang — the whole `lhs ?: rhs.call(args).also {` opener fits, so §6 ("elvis stays on the same line
   * when the expression fits") applies and it is one line shorter than breaking `?:` onto its own line.
   * Regression: the generic operator flat-block always broke the operator once the level was multiline
   * (which the hanging lambda body forces), so it never offered the attached-and-hang layout; §1 then
   * had only the fewer-columns-but-more-lines broken form to pick. From kotlinx.coroutines
   * AbstractSharedFlow.kt:30.
   */
  @Test
  fun `elvis-with-multiline-trailing-lambda-rhs-stays-attached`() =
      check(
          input =
              """fun f(): StateFlow<Int> {
    return _subscriptionCount ?: SubscriptionCountStateFlow(nCollectors).also { firstThing(); _subscriptionCount = it }
}""",
          expected =
              """fun f(): StateFlow<Int> {
    return _subscriptionCount ?: SubscriptionCountStateFlow(nCollectors).also {
        firstThing()
        _subscriptionCount = it
    }
}""",
      )

  /** §6 companion: the same holds for a `+` (end-of-line operator) with a multiline trailing lambda. */
  @Test
  fun `plus-with-multiline-trailing-lambda-rhs-stays-attached`() =
      check(
          input =
              """fun f(): X {
    return firstOperandHere + SubscriptionCountStateFlow(nCollectors).also { firstThing(); useIt(it) }
}""",
          expected =
              """fun f(): X {
    return firstOperandHere + SubscriptionCountStateFlow(nCollectors).also {
        firstThing()
        useIt(it)
    }
}""",
      )

  /**
   * §6 boundary: when the elvis right-hand operand's trailing lambda is a SINGLE line (no block to
   * hang) and the whole expression does not fit, the `?:` DOES wrap to the start of its own
   * continuation line (the canonical §6 null-fallback layout) — the attached-and-hang layout is offered
   * only for a multiline lambda (see [KmpAstVisitor.rhsHangsTrailingLambda]). From kotlinx.coroutines
   * JobSupport.kt:803.
   */
  @Test
  fun `elvis-with-single-line-trailing-lambda-rhs-breaks-operator`() =
      check(
          input =
              """class C {
    fun f() {
        while (true) {
            val causeException = causeExceptionCache ?: createCauseException(cause).also { causeExceptionCache = it }
        }
    }
}""",
          expected =
              """class C {
    fun f() {
        while (true) {
            val causeException = causeExceptionCache
                ?: createCauseException(cause).also { causeExceptionCache = it }
        }
    }
}""",
      )
}
