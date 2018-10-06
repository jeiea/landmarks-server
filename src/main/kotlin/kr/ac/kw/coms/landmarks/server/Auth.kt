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
import kr.ac.kw.coms.landmarks.client.AccountForm
import kr.ac.kw.coms.landmarks.client.ServerOK
import kr.ac.kw.coms.landmarks.client.WithIntId
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
      errorPage("landmarks-client agent required")
    }
  }

  post("/register") {
    val reg: AccountForm = call.receive()
    fun throwIfMissing(name: String, field: String?) {
      if (field.isNullOrBlank()) {
        errorPage("${name} field not found")
      }
    }

    throwIfMissing("email", reg.email)
    throwIfMissing("login", reg.login)
    throwIfMissing("password", reg.password)
    throwIfMissing("nick", reg.nick)

    val userAgent = context.request.headers["User-Agent"]
    if (userAgent != "landmarks-client") {
      errorPage("User-Agent should be landmarks-client")
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
      val msg: String = e.message ?: throw e
      if (!msg.contains("constraint failed")) {
        throw e
      }
      val guide: String = when {
        msg.contains("login") -> "Already exising id"
        msg.contains("nick") -> "Already exising nick"
        msg.contains("email") -> "Already exising email"
        else -> throw e
      }
      throw ValidException(guide)
    }

    call.respond(ServerOK("success"))
  }

  get("/verification/{verKey}") {
    val verKey = call.parameters["verKey"] ?: ""
    if (verKey == "") {
      errorPage("verification key not present")
    }

    transaction {
      val target = Users.select { Users.verification eq verKey }.firstOrNull()
      if (target == null) {
        errorPage("invalid verification key")
      }
      else {
        Users.deleteWhere { Users.verification eq verKey }
      }
    }
  }

  post("/login") {
    val param: AccountForm = call.receive()
    if (param.login == null) {
      errorPage("login field missing")
    }
    if (param.password == null) {
      errorPage("password field missing")
    }
    val user = transaction {
      User.find { Users.login eq param.login!! }.firstOrNull()
    }
    val hash = getSHA256(param.password!!)
    if (user == null || !user.passhash!!.contentEquals(hash)) {
      errorPage("password incorrect")
    }
    call.sessions.set(LMSession(user.id.value))
    val info = AccountForm(user.login, email = user.email, nick = user.nick)
    call.respond(WithIntId(user.id.value, info))
  }
}

internal fun PipelineContext<Unit, ApplicationCall>.requireLogin() =
  call.sessions.get<LMSession>()
    ?: throw ValidException("login required", HttpStatusCode.Unauthorized)
