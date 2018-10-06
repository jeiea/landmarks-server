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
import kr.ac.kw.coms.landmarks.client.PictureInfo
import kr.ac.kw.coms.landmarks.client.ServerFault
import kr.ac.kw.coms.landmarks.client.WithIntId
import org.jetbrains.exposed.sql.Random
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction


fun main(args: Array<String>) {
  val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
  val server = embeddedServer(
    Netty, port, module = Application::landmarksServer,
    watchPaths = listOf("landmarks-serverkt"))
  server.start(wait = true)
}


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

fun Route.problem() = route("/problem") {
  // Incomplete. There is no use at this time.
  put("/") { _ -> }

  get("/random/{n}") { _ ->
    val n: Int = call.parameters["n"]?.toIntOrNull() ?: 1
    val pics: List<WithIntId<PictureInfo>> = transaction {
      val query = Pictures.selectAll().orderBy(Random()).limit(n)
      Picture.wrapRows(query).map { it.toIdPicture() }
    }
    call.respond(pics)
  }
}

