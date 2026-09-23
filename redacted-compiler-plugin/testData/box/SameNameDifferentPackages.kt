// FILE: a/Holder.kt
package a

@Redacted
data class Holder(val secret: String)

// FILE: b/Holder.kt
package b

data class Holder(@Redacted val secret: String)

// FILE: main.kt
import kotlin.test.assertEquals

fun box(): String {
  // Same simple name in different packages must not share a plan.
  assertEquals("Holder(██)", a.Holder("a-secret").toString())
  assertEquals("Holder(secret=██)", b.Holder("b-secret").toString())
  return "OK"
}
