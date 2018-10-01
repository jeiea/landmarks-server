package kr.ac.kw.coms.landmarks.server

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.locations.Locations
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.sessions.*
import kr.ac.kw.coms.landmarks.client.PictureRep
import kr.ac.kw.coms.landmarks.client.ServerFault
import kr.ac.kw.coms.landmarks.client.WithIntId
import org.jetbrains.exposed.sql.Random
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.PrintWriter
import java.io.StringWriter


fun main(args: Array<String>) {
  val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
  val server = embeddedServer(
    Netty, port, module = Application::landmarksServer,
    watchPaths = listOf("landmarks-serverkt"))
  server.start(wait = true)
}


data class ValidException(
  val msg: String,
  val code: HttpStatusCode = HttpStatusCode.BadRequest
) : Throwable()

fun Application.landmarksServer() {
  install(CallLogging)
  install(Compression)
  install(ConditionalHeaders)
  install(DefaultHeaders)
  install(Locations)
  install(StatusPages) {
    exception<ValidException> { cause ->
      call.respond(HttpStatusCode.BadRequest, ServerFault(cause.msg))
    }
    exception<Throwable> { cause ->
      val trace: String = stacktraceToString(cause)
      log.error(trace)
      val json = ServerFault(cause.message ?: "", trace)
      call.respond(HttpStatusCode.InternalServerError, json)
    }
  }
  install(Sessions) {
    cookie<LMSession>("SESSION", SessionStorageMemory()) {
      cookie.path = "/"
      cookie.httpOnly = true
    }
  }
  install(ContentNegotiation) {
    gson { }
  }

  dbInitialize()
  println("DB initialized. server running...")

  routing {

    trace {
      log.debug(it.buildText())
    }

    get("/") {
      call.respondText("Hello, client!")
    }
    put("/maintenance/reset") {
      resetTables()
      call.respondText("DB reset success")
    }

    authentication()
    picture()
    collection()
    problem()
  }
}

fun stacktraceToString(cause: Throwable): String {
  return StringWriter().also { sw ->
    cause.printStackTrace(PrintWriter(sw))
  }.toString()
}

fun getParamId(call: ApplicationCall): Int {
  return call.parameters["id"]?.toIntOrNull() ?: throw ValidException("id not valid")
}

fun notFoundPage(): Nothing {
  throw ValidException("Not found", HttpStatusCode.NotFound)
}

fun Route.problem() = route("/problem") {
  // Incomplete. There is no use at this time.
  put("/") { _ -> }

  get("/random/{n}") { _ ->
    val n: Int = call.parameters["n"]?.toIntOrNull() ?: 1
    val pics: List<WithIntId<PictureRep>> = transaction {
      val query = Pictures.selectAll().orderBy(Random()).limit(n)
      Picture.wrapRows(query).map { it.toIdPicture() }
    }
    call.respond(pics)
  }
}

