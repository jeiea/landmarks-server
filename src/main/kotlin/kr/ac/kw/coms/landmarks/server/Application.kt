package kr.ac.kw.coms.landmarks.server

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.locations.Locations
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.sessions.SessionStorageMemory
import io.ktor.sessions.Sessions
import io.ktor.sessions.cookie
import io.ktor.util.AttributeKey
import kr.ac.kw.coms.landmarks.client.ProfileInfo
import kr.ac.kw.coms.landmarks.client.ServerFault
import org.jetbrains.exposed.sql.select
import org.joda.time.DateTime
import org.joda.time.Period
import org.joda.time.format.PeriodFormatterBuilder


fun main(args: Array<String>) {
  val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
  val server = embeddedServer(
    Netty, port, module = Application::landmarksServer,
    watchPaths = listOf("landmarks-serverkt")
  )
  server.start(wait = true)
}

val periodMinFormat = PeriodFormatterBuilder()
  .appendMinutes()
  .appendSuffix(":")
  .printZeroAlways()
  .appendSeconds()
  .appendPrefix(".")
  .appendMillis3Digit()
  .appendSuffix("s")
  .toFormatter()

fun Application.landmarksServer() {
  install(CallLogging)
  install(Compression) {
    gzip {
      priority = 1.0
      minimumSize(1024)
    }
  }
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

  landmarksDb
  println("DB initialized. server running...")

  val att = AttributeKey<DateTime>("prof")
  routing {

    //    trace { log.debug(it.buildText()) }
    environment.monitor.subscribe(Routing.RoutingCallStarted) {
      it.request.uri
      val now = DateTime.now()
      it.attributes.put(att, now)
    }
    environment.monitor.subscribe(Routing.RoutingCallFinished) {
      val beg = it.attributes.get(att)
      val end = DateTime.now()
      val dur = Period(end.millis - beg.millis)
      val period = periodMinFormat.print(dur)
      log.info("$period: ${it.request.uri}")
    }

    get("/") {
      call.respondText("Hello, client!")
    }

    authentication()
    profile()
    picture()
    collection()
    maintenance()
  }
}

fun Route.maintenance() = route("/maintenance") {
  get("/reset") {
    resetTables()
    call.respondText("DB reset success")
  }

  get("/push") {
    pushTables()
    call.respondText("DB push success")
  }

  get("/pop") {
    popTables()
    call.respondText("DB pop success")
  }
}

fun Route.profile() = get("/profile") {
  val uid = requireLogin().userId
  val profile = transaction {
    val collCount = Collections.select { Collections.owner eq uid }.count()
    val picCount = Pictures.select { Pictures.owner eq uid }.count()
    val registered = Users
      .select { Users.id eq uid }
      .adjustSlice { slice(Users.registered) }
      .first()[Users.registered]
      .toDate()
    ProfileInfo(collCount, picCount, registered)
  }
  call.respond(profile)
}