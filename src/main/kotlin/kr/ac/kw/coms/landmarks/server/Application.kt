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
import kr.ac.kw.coms.landmarks.client.IdPictureInfo
import kr.ac.kw.coms.landmarks.client.ServerFault
import org.jetbrains.exposed.sql.Random
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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

//class Profiling(configuration: Configuration) {
//  val prop = configuration.prop // get snapshot of config into immutable property
//
//  class Configuration {
//    var prop = "value" // mutable property
//  }
//
//  // implement ApplicationFeature in a companion object
//  companion object Feature :
//    ApplicationFeature<ApplicationCallPipeline, Profiling.Configuration, Profiling> {
//    // create unique key for the feature
//    override val key = AttributeKey<Profiling>("Profiling")
//
//    // implement installation script
//    override fun install(
//      pipeline: ApplicationCallPipeline,
//      configure: Configuration.() -> Unit
//    ): Profiling {
//
//      // run configuration script
//      val configuration = Profiling.Configuration().apply(configure)
//
//      // create a feature
//      val feature = Profiling(configuration)
//
//      // intercept a pipeline
//      pipeline.intercept(Monitoring) {
//        // call a feature
//      }
//      return feature
//    }
//  }
//}

val periodMinFormat = PeriodFormatterBuilder()
  .appendMinutes()
  .appendSuffix(":")
  .printZeroAlways()
  .appendSeconds()
  .appendPrefix(".")
  .appendMillis3Digit()
  .toFormatter()

fun Application.landmarksServer() {
  install(CallLogging) {
  }
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
    requireLogin()
    val n: Int = call.parameters["n"]?.toIntOrNull() ?: 1
    val pics: List<IdPictureInfo> = transaction {
      val query = Pictures.selectAll().orderBy(Random()).limit(n)
      Picture.wrapRows(query).map { it.toIdPicture() }
    }
    call.respond(pics)
  }
}

