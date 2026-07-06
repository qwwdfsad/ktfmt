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
 * Reliability gate over the kotlin-format `snippets.py` corpus (the documented optofmt layouts).
 * `optofmt_corpus.json` is dumped from that file (`id`, `input`, `optofmt`); regenerate it with:
 *
 *     python3 -c "import sys; sys.path.insert(0,'.../kotlin-format'); import snippets, json; \
 *       out=[]; [ (out.append({'id':e['id'],'input':e.get('input'),'optofmt':e.get('optofmt')}) or \
 *       [out.append({'id':e['id']+'-extra-'+str(i+1),'input':x.get('input'),'optofmt':x.get('optofmt')}) \
 *       for i,x in enumerate(e.get('extra',[]))]) for e in snippets.SNIPPETS]; \
 *       json.dump(out, open('core/src/test/resources/optofmt_corpus.json','w'), indent=0)"
 *
 * Each entry with an `input` must format to its `optofmt`; input-less sub-examples must be idempotent.
 * RULES.md is the source of truth: where a corpus example encodes buggy-optofmt output it is listed in
 * [KNOWN_DIVERGENCES] with the reason, and excluded from the assertion (but still recorded).
 */
@RunWith(JUnit4::class)
class OptofmtCorpusGateTest {
  private fun fmt(s: String) = Formatter.format(Formatter.OPTOFMT_FORMAT, s).trimEnd('\n')

  @Test
  fun `optofmt matches the documented corpus`() {
    val json =
        OptofmtCorpusGateTest::class.java.getResourceAsStream("/optofmt_corpus.json")!!
            .readBytes()
            .decodeToString()
    val entries = parse(json)
    assertThat(entries.size).isAtLeast(40)
    val failures = StringBuilder()
    // Every entry must be accounted for: either asserted or an explicit KNOWN_DIVERGENCE. This makes
    // "all snippets are covered" a checked invariant — a new snippet (after regenerating the resource)
    // that optofmt doesn't yet match will fail here rather than be silently skipped.
    val checked = mutableListOf<String>()
    for (e in entries) {
      val id = e["id"]!!
      if (id in KNOWN_DIVERGENCES) continue
      val expected = e["optofmt"]
      if (expected == null) {
        failures.append("[$id] has no 'optofmt' in the corpus — cannot check\n\n"); continue
      }
      val exp = expected.trimEnd('\n')
      val input = e["input"]
      checked.add(id)
      val actual =
          try {
            if (input != null) fmt(input) else fmt(exp)
          } catch (t: Throwable) {
            failures.append("[$id] CRASH: ${t.message?.take(120)}\n\n"); continue
          }
      if (actual != exp) {
        val kind = if (input != null) "format(input)" else "idempotency"
        failures.append(
            "[$id] $kind mismatch\n--- expected ---\n$exp\n--- actual ---\n$actual\n\n")
      }
    }
    // Coverage: exactly every non-divergence entry was checked (no silent skips).
    val divergencesPresent = entries.count { it["id"] in KNOWN_DIVERGENCES }
    assertThat(checked.size).isEqualTo(entries.size - divergencesPresent)
    // Guard against a stale divergence id (a renamed/removed snippet leaving a dangling exclusion).
    val allIds = entries.mapNotNull { it["id"] }.toSet()
    assertThat(allIds).containsAtLeastElementsIn(KNOWN_DIVERGENCES)
    if (failures.isNotEmpty()) throw AssertionError("optofmt corpus divergences:\n\n$failures")
  }

  private companion object {
    /**
     * Corpus entries optofmt does NOT yet match. The first two are genuine optofmt gaps (a sub-level
     * "fits" while its containing line overflows, so §1 wrongly avoids wrapping it — the same class the
     * chain re-architecture solved, pending the same treatment for these constructs). The last is a
     * buggy-expected example: RULES §6 does not specify `as`-cast wrap direction, so the corpus's
     * break-before-`as` is not derivable from the rules (flag for snippets.py, per "rules win").
     */
    val KNOWN_DIVERGENCES =
        setOf(
            "infix-attached-extra-1", // §3/§6 long infix RHS mis-wrapped as a call chain — TODO
            "block-rhs-extra-6", // §3 `= when (long subject) {` breaks after `=` — TODO
            "supertype-by-delegation-attached", // buggy-expected: `as`-wrap direction not in RULES
        )
  }

  // ---- tiny JSON reader for our string-only dump ----
  private fun parse(json: String): List<Map<String, String?>> {
    val res = mutableListOf<Map<String, String?>>()
    var i = 0
    fun skipWs() { while (i < json.length && json[i].isWhitespace()) i++ }
    fun readStr(): String {
      i++ // opening quote
      val sb = StringBuilder()
      while (json[i] != '"') {
        if (json[i] == '\\') {
          i++
          sb.append(
              when (json[i]) {
                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                else -> json[i]
              })
        } else sb.append(json[i])
        i++
      }
      i++
      return sb.toString()
    }
    skipWs(); i++ // [
    while (true) {
      skipWs()
      if (json[i] == ']') break
      i++ // {
      val m = mutableMapOf<String, String?>()
      while (true) {
        skipWs()
        if (json[i] == '}') { i++; break }
        val key = readStr(); skipWs(); i++; skipWs() // key :
        m[key] = if (json.startsWith("null", i)) { i += 4; null } else readStr()
        skipWs(); if (json[i] == ',') i++
      }
      res.add(m)
      skipWs(); if (json[i] == ',') i++
    }
    return res
  }
}
