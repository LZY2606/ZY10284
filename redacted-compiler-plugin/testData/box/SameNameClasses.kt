// FILE: a/Duplicate.kt
package a

import dev.zacsweers.redacted.annotations.Redacted

@Redacted
data class Duplicate(val secret: String)

// FILE: b/Duplicate.kt
package b

import dev.zacsweers.redacted.annotations.Redacted

@Redacted
data class Duplicate(val id: Int, val secret: String)

// FILE: main.kt
import kotlin.test.assertEquals

fun box(): String {
  val one = a.Duplicate("hidden")
  assertEquals("Duplicate(██)", one.toString())

  val two = b.Duplicate(42, "hidden")
  assertEquals("Duplicate(██)", two.toString())
  return "OK"
}
