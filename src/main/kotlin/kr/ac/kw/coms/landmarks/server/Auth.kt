package kr.ac.kw.coms.landmarks.server

import io.ktor.application.ApplicationCallPipeline.ApplicationPhase.Call
import io.ktor.application.call
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.request.header
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.sessions.sessions
import io.ktor.sessions.set
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.*
import kotlin.streams.asSequence

// call.receive makes non-nullable field nullable.
// So non-nullable specifier is useless.
data class Register(
  val login: String?,
  val nick: String?,
  val password: String?,
  val email: String?
)

data class ErrorJson(val error: String)
data class SuccessJson(val msg: String)

fun getRandomString(length: Long): String {
  val source = "abcdefghijklmnopqrstuvwxyz0123456789"
  return Random().ints(length, 0, source.length)
    .asSequence()
    .map(source::get)
    .joinToString("")
}

fun getSHA256(str: String): ByteArray {
  return MessageDigest.getInstance("SHA-256")
    .digest(("piezo" + str).toByteArray())!!
}

fun Route.authentication() = route("/auth") {

  intercept(Call) {
    if (call.request.header(HttpHeaders.UserAgent) != "landmarks-client") {
      throw ValidException("landmarks-client agent required")
    }
  }

  post("/register") {
    val reg: Register = call.receive()
    val isMissing: suspend (String, String?) -> Boolean = { name, field ->
      if (field == null) {
        call.respond(ErrorJson("${name} field not found"))
        true
      } else false
    }

    if (isMissing("email", reg.email)) return@post
    if (isMissing("login", reg.login)) return@post
    if (isMissing("password", reg.password)) return@post
    if (isMissing("nick", reg.nick)) return@post

    val userAgent = context.request.headers["User-Agent"]
    if (userAgent != "landmarks-client") {
      call.respond(ErrorJson("User-Agent should be landmarks-client"))
    }

    val digest = getSHA256(reg.password!!)

    try {
      transaction {
        User.new {
          email = reg.email!!
          login = reg.login!!
          nick = reg.nick!!
          passhash = digest
          verification = getRandomString(10)
          nation = "KR"
        }
      }
    } catch (e: ExposedSQLException) {
      if (e.message?.contains("constraint failed") ?: false) {
        call.respond(ErrorJson("Already existing user"))
      } else {
        throw e
      }
      return@post
    }

    call.respond(SuccessJson("success"))
  }

  get("/verification/{verKey}") {
    val verKey = call.parameters["verKey"] ?: ""
    if (verKey == "") {
      call.respond(
        HttpStatusCode.NotImplemented,
        ErrorJson("verification key not present"))
      return@get
    }

    val verified = transaction {
      val target = Users.select { Users.verification eq verKey }.firstOrNull()
      if (target == null) suspend {
        call.respond(
          HttpStatusCode.NotFound,
          ErrorJson("invalid verification key"))
        false
      }
      else suspend {
        Users.deleteWhere { Users.verification eq verKey }
        true
      }
    }
    if (!verified()) return@get

    call.respond(SuccessJson("verification success"))
  }

  post("/login") {
    val param: Register = call.receive()
    if (param.login == null) {
      call.respond(ErrorJson("login field missing"))
      return@post
    }
    if (param.password == null) {
      call.respond(ErrorJson("password field missing"))
      return@post
    }
    val user = transaction {
      Users.select { Users.login eq param.login }
        .adjustSlice { slice(Users.id, Users.passhash) }
        .firstOrNull()
    }
    val hash = getSHA256(param.password)
    if (user == null || !user[Users.passhash]!!.contentEquals(hash)) {
      call.respond(ErrorJson("password incorrect"))
      return@post
    }
    call.sessions.set(LMSession(user[Users.id].value))
    call.respond(SuccessJson("login success"))
  }
}
