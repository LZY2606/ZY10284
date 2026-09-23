// RENDER_DIAGNOSTICS_FULL_TEXT

@Redacted
data class ValidRedacted(val secret: String)

@Redacted
enum class <!REDACTED_ERROR!>InvalidEnum<!> {
  A,
  B
}

data class ValidProperty(@Redacted val secret: String, val other: String)
