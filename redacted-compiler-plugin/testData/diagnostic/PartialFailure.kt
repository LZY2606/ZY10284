// RENDER_DIAGNOSTICS_FULL_TEXT

data class ValidRedacted(@Redacted val a: Int)

class <!REDACTED_ERROR!>InvalidNonData<!>(@Redacted val b: Int)
