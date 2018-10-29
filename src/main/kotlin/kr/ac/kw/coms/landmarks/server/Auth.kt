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
import kr.ac.kw.coms.landmarks.client.IdAccountForm
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import java.security.MessageDigest
import java.sql.SQLException
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
    .digest(("piezo$str").toByteArray())!!
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
        errorPage("$name field not found")
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
      val account = transaction {
        User.new {
          email = reg.email!!
          login = reg.login!!
          nick = reg.nick!!
          passhash = digest
          verification = getRandomString(10)
          nation = "KR"
        }
      }
      call.respond(toIdAccount(account))
    }
    catch (e: SQLException) {
      val msg: String = e.message ?: throw e
      if (!msg.contains("constraint")) {
        throw e
      }
      val guide = when {
        msg.contains("login") -> "Already existing id"
        msg.contains("nick") -> "Already existing nick"
        msg.contains("email") -> "Already existing email"
        else -> throw e
      }
      errorPage(guide)
    }
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
    call.respond(toIdAccount(user))
  }
}

private fun toIdAccount(user: User): IdAccountForm {
  val info = AccountForm(user.login, email = user.email, nick = user.nick)
  return IdAccountForm(user.id.value, info)
}

internal fun PipelineContext<Unit, ApplicationCall>.requireLogin() =
  call.sessions.get<LMSession>()
    ?: throw ValidException("login required", HttpStatusCode.Unauthorized)
