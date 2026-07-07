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
   * §5: a sole call/constructor argument that must wrap does NOT collapse its openers. The outer call
   * breaks after `(`, the inner call hangs on its own indented line, and each `)` closes on its own
   * line — a single block-like argument, so the OUTER call takes no trailing comma (the inner list
   * commas normally, §14).
   */
  @Test
  fun `indent-economy`() =
      check(
          input = """fun f() { add(OverrideQueue(queueSettings.waitTime, queueSettings.firstToSolveWaitTime, queueSettings.featuredRunWaitTime, queueSettings.inProgressRunWaitTime, queueSettings.maxQueueSize, queueSettings.maxUntestedRun)) }""",
          expected = """fun f() {
    add(
        OverrideQueue(
            queueSettings.waitTime,
            queueSettings.firstToSolveWaitTime,
            queueSettings.featuredRunWaitTime,
            queueSettings.inProgressRunWaitTime,
            queueSettings.maxQueueSize,
            queueSettings.maxUntestedRun,
        )
    )
}""",
      )

  @Test
  fun `indent-economy-extra-1-idempotent`() =
      idempotent("""fun f() {
    registerHandler(
        buildHandler(
            aLongUnbreakableArgumentIdentifierDeliberatelySizedToOverflowTheColumnLimitNoMatterWhatXY,
        )
    )
}""")

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
   * §2: `receiver.run { … }` where the receiver is a call that WRAPS its arguments. The receiver
   * wraps and `.run {` breaks to its own line; the lambda body must then indent one level below `.run`
   * (§2), not sit at `.run`'s column. From Exposed Algorithms.kt:33-45. Regression: the trailing-lambda
   * body was one level too shallow — the native engine can't pick the body indent via an `Indent.If`
   * keyed on the chain break, so it always used the "hang" indent even when the chain broke. Fixed by
   * offering hang vs. broken as explicit candidates (the hang one forces the receiver flat).
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
            )
            .run {
                makeEncryptor()
                finalizeSetup()
            }
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
}
