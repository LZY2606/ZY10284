import kotlin.test.assertEquals

data class CustomToString(val a: Int) {
  override fun toString(): String = "custom"
}

@Redacted
data class StillRedacted(val secret: String)

fun box(): String {
  assertEquals("custom", CustomToString(1).toString())
  assertEquals("StillRedacted(██)", StillRedacted("x").toString())
  return "OK"
}
