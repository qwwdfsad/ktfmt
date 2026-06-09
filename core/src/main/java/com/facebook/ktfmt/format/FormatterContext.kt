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


/**
 * Carries the source through the formatting pipeline. Each pass consumes plain source text and the
 * lightweight multiplatform syntax tree ([KmpAst]) for that text, and returns new text.
 *
 * The tree is parsed once per distinct source and reused by every pass that does not change the
 * code: when a pass returns its input unchanged, [transform] keeps the same context (and the same
 * already-parsed tree) instead of re-parsing. This avoids redundant parses on the happy path (e.g.
 * already-formatted files, where most passes are no-ops). The heavy IntelliJ PSI parser is never
 * touched on the format path.
 */
internal class FormatterContext(@JvmField val code: String) {

  /** The kmp syntax tree for [code], parsed once and shared across passes that don't change it. */
  @JvmField val tree: KmpNode = KmpAst.parse(code)

  inline fun transform(block: (String, KmpNode) -> String): FormatterContext {
    val newCode = block(code, tree)
    return if (newCode == code) this else FormatterContext(newCode)
  }
}
