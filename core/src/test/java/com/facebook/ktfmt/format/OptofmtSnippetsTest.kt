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
        queueSettings.maxUntestedRun
    ))
}""",
      )

  @Test
  fun `indent-economy-extra-1-idempotent`() =
      idempotent("""fun f() {
    registerHandler(buildHandler(
        aLongUnbreakableArgumentIdentifierDeliberatelySizedToOverflowTheColumnLimitNoMatterWhatXY
    ))
}""")

  @Test
  fun `infix-attached`() =
      check(
          input = """val pair = orgInfo.id to OverrideOrganizations.Override(fullName = substituteRaw(fullName), displayName = substituteRaw(displayName))""",
          expected = """val pair = orgInfo.id to OverrideOrganizations.Override(
    fullName = substituteRaw(fullName),
    displayName = substituteRaw(displayName)
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
    outputHelp = "Path to new zip file"
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
   * §7: in a member-access chain, each subsequent `.call` sits on its own line — never filled
   * several per line — even when every call carries its own trailing lambda. Regression: the
   * grouped-lambda tail-attach path (for `}.join()`-style lambda-free tails) was filling these
   * lambda-bearing `.applyIf(…) { … }` calls, packing multiple per line and overflowing.
   */
  @Test
  fun `call-chain-of-trailing-lambdas`() =
      check(
          input =
              """
public fun Flow<ContestUpdate>.addComputedData(configure: ComputedDataConfig.() -> Unit = {}): Flow<ContestUpdate> {
    val config = ComputedDataConfig().apply(configure)
    return this.applyIf(config.autoCreateMissingGroups) { autoCreateMissingGroupsAndOrgs() }.applyIf(!config.submissionResultsAfterFreeze) { removeFrozenSubmissionsResults() }.applyIf(!config.submissionsAfterEnd) { removeAfterEndSubmissions() }.applyIf(config.unhideColorWhenSolved) { selectProblemColors() }.applyIf(config.propagateHidden) { hideHiddenGroupsTeams() }.applyIf(config.propagateHidden) { hideHiddenTeamsRuns() }.applyIf(config.propagateHidden) { hideHiddenProblemsRuns() }.applyIf(config.propagateRunMediaTemplates) { propagateRunMediaTemplates() }.applyIf(config.ioiScoreDifferences) { calculateScoreDifferences() }.applyIf(config.markSubmissionsAfterFirstOk) { markSubmissionAfterFirstOk() }.applyIf(config.firstToSolves) { addFirstToSolves() }.applyIf(config.replaceCommentaryTags) { processCommentaryTags() }.applyIf(config.autoFinalize) { autoFinalize() }
}""",
          expected =
              """public fun Flow<ContestUpdate>.addComputedData(
    configure: ComputedDataConfig.() -> Unit = {}
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
    private val subscriptionWaitingFlow: MutableStateFlow<Int>
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
    listenerCallback: EventListener
) {
    installListener()
}""",
      )

  @Test
  fun `elvis-wrap`() =
      check(
          input = """fun f(): PetType { return findPetTypes.find { it.name == text } ?: throw ParseException("type not found: " + text, 0) }""",
          expected = """fun f(): PetType {
    return findPetTypes.find { it.name == text }
        ?: throw ParseException("type not found: " + text, 0)
}""",
      )

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
}
