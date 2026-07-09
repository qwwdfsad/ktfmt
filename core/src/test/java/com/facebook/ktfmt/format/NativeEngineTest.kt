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

/** Formats Kotlin through the experimental gjf-free native engine (no google-java-format). */
@RunWith(JUnit4::class)
class NativeEngineTest {
  private val opts = Formatter.OPTOFMT_FORMAT

  private fun fmt(code: String) = Formatter.format(opts, code)

  @Test
  fun statementsIndentAndCoalesceBreaks() {
    assertThat(fmt("fun f() { val x = 1\nval y = 2 }"))
        .isEqualTo(
            """
            |fun f() {
            |    val x = 1
            |    val y = 2
            |}
            """
                .trimMargin() + "\n")
  }

  @Test
  fun leadingAndStandaloneCommentsPreserved() {
    assertThat(fmt("// header\nfun f() {\n// inside\nval x = 1\n}"))
        .isEqualTo(
            """
            |// header
            |fun f() {
            |    // inside
            |    val x = 1
            |}
            """
                .trimMargin() + "\n")
  }

  @Test
  fun trailingCommentsStayInline() {
    assertThat(fmt("fun f() {\nval x = 1 // first\nval y = 2 // second\n}"))
        .isEqualTo(
            """
            |fun f() {
            |    val x = 1 // first
            |    val y = 2 // second
            |}
            """
                .trimMargin() + "\n")
  }

  @Test
  fun redundantSemicolonsDropped() {
    assertThat(fmt("val a = 1; val b = 2")).isEqualTo("val a = 1\nval b = 2\n")
  }

  @Test
  fun trailingCommaDroppedAndDeclarationStaysCompact() {
    assertThat(fmt("class A(val x: Int, val y: Int,) // cls"))
        .isEqualTo("class A(val x: Int, val y: Int) // cls\n")
  }

  @Test
  fun kdocProseIsNotReflowed() {
    assertThat(fmt("/**\n * KDoc here.\n * Second line.\n */\nclass A"))
        .isEqualTo(
            """
            |/**
            | * KDoc here.
            | * Second line.
            | */
            |class A
            """
                .trimMargin() + "\n")
  }

  @Test
  fun longParameterListWraps() {
    assertThat(
            fmt(
                "fun registerEventListener(eventType: EventType, listenerPriority: ListenerPriority, listenerCallback: EventListener) { installListener() }"))
        .isEqualTo(
            """
            |fun registerEventListener(
            |    eventType: EventType,
            |    listenerPriority: ListenerPriority,
            |    listenerCallback: EventListener,
            |) {
            |    installListener()
            |}
            """
                .trimMargin() + "\n")
  }

  @Test
  fun nestedClassMemberThatFitsStaysCompact() {
    assertThat(fmt("class A { fun g(): Int { return compute(a, b, c) } }"))
        .isEqualTo(
            """
            |class A {
            |    fun g(): Int {
            |        return compute(a, b, c)
            |    }
            |}
            """
                .trimMargin() + "\n")
  }

  /**
   * §15: a genuine two-branch value `if`/`else` that fits stays entirely on one line — the branches
   * are never split just because one of them is a call.
   */
  @Test
  fun twoBranchIfExpressionThatFitsStaysInline() {
    assertThat(fmt("fun f() { val x = if (c) a else b }"))
        .isEqualTo(
            """
            |fun f() {
            |    val x = if (c) a else b
            |}
            """
                .trimMargin() + "\n")
  }

  /**
   * §15 (with §3/§4): when a two-branch value `if`/`else` must wrap, each branch body stays ATTACHED
   * to its keyword and wraps its OWN contents — the short `then` value rides the `if` line, and the
   * `else` call keeps its opener on the `else` line and wraps its arguments one-per-line (§4). We
   * never push a body onto a fresh indented line leaving a bare `if (c)` / `else`. This is the
   * reported diff's shape.
   */
  @Test
  fun twoBranchIfExpressionAttachesBodiesAndWrapsElseContents() {
    assertThat(
            fmt(
                "fun f() {\nif (cond) shortThenValue else buildResultObject(firstArgumentHere, " +
                    "secondArgumentHere, thirdArgumentHere, fourthArgumentHere, fifthArgument)\n}"))
        .isEqualTo(
            """
            |fun f() {
            |    if (cond) shortThenValue
            |    else buildResultObject(
            |        firstArgumentHere,
            |        secondArgumentHere,
            |        thirdArgumentHere,
            |        fourthArgumentHere,
            |        fifthArgument,
            |    )
            |}
            """
                .trimMargin() + "\n")
  }

  /**
   * §15 fallback: a branch body that can neither attach (it would overflow) nor wrap its own
   * contents (it is a plain reference, not a call) breaks onto its own line one indent deeper — so
   * `if (c) short` stays attached while the unwrappable `else` value drops down. Both bodies still
   * hug their keyword whenever they can; only the one that cannot moves.
   */
  @Test
  fun twoBranchIfExpressionBreaksOnlyTheUnwrappableBody() {
    assertThat(
            fmt(
                "fun f() { val x = if (someCondition) shortValue else " +
                    "someVeryLongUnwrappableElseValueThatDefinitelyExceedsTheColumnLimitForSureReallyTrulyYes }"))
        .isEqualTo(
            """
            |fun f() {
            |    val x = if (someCondition) shortValue
            |        else
            |            someVeryLongUnwrappableElseValueThatDefinitelyExceedsTheColumnLimitForSureReallyTrulyYes
            |}
            """
                .trimMargin() + "\n")
  }

  /**
   * §15 scope: an `else if` chain is NOT a two-branch value `if` — each `if (c) v` clause stays
   * compact and only the `else` boundaries wrap, per §1 (breaking every branch would be worse).
   */
  @Test
  fun elseIfChainKeepsClausesCompact() {
    assertThat(
            fmt(
                "fun f() { val x = if (firstConditionHere) firstValue else if (secondConditionHere) " +
                    "secondValue else theFallbackValueThatIsQuiteLongIndeedYesVeryMuchSoReally }"))
        .isEqualTo(
            """
            |fun f() {
            |    val x = if (firstConditionHere) firstValue
            |        else if (secondConditionHere) secondValue
            |        else theFallbackValueThatIsQuiteLongIndeedYesVeryMuchSoReally
            |}
            """
                .trimMargin() + "\n")
  }
}
