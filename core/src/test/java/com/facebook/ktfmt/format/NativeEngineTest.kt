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
            |    listenerCallback: EventListener
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
}
