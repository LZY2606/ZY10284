// MODULE: lib
// FILE: Lib.kt
package lib

import dev.zacsweers.redacted.annotations.Redacted

@Redacted
data class Secret(val value: String)

// MODULE: main(lib)
// FILE: main.kt
import kotlin.test.assertEquals
import lib.Secret

fun box(): String {
  assertEquals("Secret(██)", Secret("x").toString())
  return "OK"
}
