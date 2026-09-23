// MODULE: lib
// FILE: lib.kt
package lib

@Redacted
data class LibToken(val value: String)

// MODULE: app()(lib)
// FILE: app.kt
import kotlin.test.assertEquals
import lib.LibToken

data class AppToken(@Redacted val value: String)

fun box(): String {
  // Plans from different source sets/modules must each generate exactly one implementation.
  assertEquals("LibToken(██)", LibToken("lib-secret").toString())
  assertEquals("AppToken(value=██)", AppToken("app-secret").toString())
  return "OK"
}
