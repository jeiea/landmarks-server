package kr.ac.kw.coms.landmarks.server

import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline.ApplicationPhase.Call
import io.ktor.application.call
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.pipeline.PipelineContext
import io.ktor.request.header
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import io.ktor.sessions.set
import kr.ac.kw.coms.landmarks.client.LoginRep
import kr.ac.kw.coms.landmarks.client.ServerFault
import kr.ac.kw.coms.landmarks.client.ServerOK
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.*
import kotlin.streams.asSequence

data class LMSession(val userId: Int)

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
    val reg: LoginRep = call.receive()
    val isMissing: suspend (String, String?) -> Boolean = { name, field ->
      if (field == null) {
        call.respond(ServerFault("${name} field not found"))
        true
      } else false
    }

    if (isMissing("email", reg.email)) return@post
    if (isMissing("login", reg.login)) return@post
    if (isMissing("password", reg.password)) return@post
    if (isMissing("nick", reg.nick)) return@post

    val userAgent = context.request.headers["User-Agent"]
    if (userAgent != "landmarks-client") {
      call.respond(ServerFault("User-Agent should be landmarks-client"))
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
        call.respond(ServerFault("Already existing user"))
      } else {
        throw e
      }
      return@post
    }

    call.respond(ServerOK("success"))
  }

  get("/verification/{verKey}") {
    val verKey = call.parameters["verKey"] ?: ""
    if (verKey == "") {
      call.respond(
        HttpStatusCode.NotImplemented,
        ServerFault("verification key not present"))
      return@get
    }

    val verified = transaction {
      val target = Users.select { Users.verification eq verKey }.firstOrNull()
      if (target == null) suspend {
        call.respond(
          HttpStatusCode.NotFound,
          ServerFault("invalid verification key"))
        false
      }
      else suspend {
        Users.deleteWhere { Users.verification eq verKey }
        true
      }
    }
    if (!verified()) return@get

    call.respond(ServerOK("verification success"))
  }

  post("/login") {
    val param: LoginRep = call.receive()
    if (param.login == null) {
      call.respond(ValidException("login field missing"))
      return@post
    }
    if (param.password == null) {
      call.respond(ValidException("password field missing"))
      return@post
    }
    val user = transaction {
      User.find { Users.login eq param.login!! }.firstOrNull()
    }
    val hash = getSHA256(param.password!!)
    if (user == null || !user.passhash!!.contentEquals(hash)) {
      call.respond(ValidException("password incorrect"))
      return@post
    }
    call.sessions.set(LMSession(user.id.value))
    call.respond(LoginRep(user.id.value, user.login, email = user.email, nick = user.nick))
  }
}

internal fun PipelineContext<Unit, ApplicationCall>.requireLogin() =
  call.sessions.get<LMSession>()
    ?: throw ValidException("login required", HttpStatusCode.Unauthorized)
