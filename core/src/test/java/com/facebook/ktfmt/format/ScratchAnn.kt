package com.facebook.ktfmt.format

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ScratchAnn {
  @Test
  fun scratch() {
    val input = """class C {
    fun isForUpdate(): Boolean = (
        @OptIn(InternalApi::class)
        forUpdate?.let { it != ForUpdateOption.NoForUpdateOption }
            ?: false
        )
}
"""
    val out = Formatter.format(Formatter.OPTOFMT_FORMAT, input)
    throw RuntimeException("\n=====\n" + out + "=====")
  }
}
