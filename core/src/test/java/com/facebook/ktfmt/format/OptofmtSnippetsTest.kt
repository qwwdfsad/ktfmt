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
