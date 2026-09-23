import kotlin.test.assertEquals

data class CustomToString(val value: String) {
  override fun toString(): String = "Custom($value)"
}

@Redacted
data class RedactedData(val secret: String)

fun box(): String {
  // A custom toString on an unannotated class must be left untouched.
  assertEquals("Custom(hello)", CustomToString("hello").toString())
  assertEquals("RedactedData(██)", RedactedData("secret").toString())
  return "OK"
}
