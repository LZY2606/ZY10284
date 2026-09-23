import kotlin.test.assertEquals

@Redacted
interface Sensitive

interface Base : Sensitive

data class User(val name: String, val ssn: String) : Sensitive

data class TransitiveUser(val secret: String) : Base

data object EmptyObject : Sensitive

fun box(): String {
  val user = User("Bob", "123-456-7890")
  assertEquals("User(name=██, ssn=██)", user.toString())

  val transitiveUser = TransitiveUser("shh")
  assertEquals("TransitiveUser(secret=██)", transitiveUser.toString())

  assertEquals("EmptyObject()", EmptyObject.toString())
  return "OK"
}
