package kr.ac.kw.coms.landmarks.server

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonJson
import com.beust.klaxon.json
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.locations.Locations
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.sessions.*
import kr.ac.kw.coms.landmarks.client.ServerFault
import kr.ac.kw.coms.landmarks.client.ServerOK
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.Random
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
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
    cookie<LMSession>("SESSION", storage = SessionStorageMemory())
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

fun Route.problem() = route("/problem") {
  // Incomplete. There is no use at this time.
  put("/") { _ -> }

  get("/random/{n}") { _ ->
    val n: Int = call.parameters["n"]?.toIntOrNull() ?: 1
    val ar = JsonArray<JsonObject>()
    val rows = transaction {
      Pictures.selectAll().orderBy(Random()).limit(n).forEach {
        ar.add(JsonObject(mapOf(
          "id" to it[Pictures.id].value,
          "address" to it[Pictures.address],
          "lon" to it[Pictures.longi],
          "lat" to it[Pictures.latit]
        )))
      }
    }
    call.respond(ar)
  }
}

data class CollectionMeta(
  val title: String?,
  val parent: Int?,
  val isRoute: Boolean?
)

fun Routing.collection() = route("/collection") {

  put("/") {
    val sessId = requireLogin().userId
    val json: CollectionMeta = call.receive()

    val id = transaction {
      Collection.new {
        created = DateTime.now()
        title = json.title ?: ""
        isRoute = json.isRoute ?: false
        owner = EntityID(sessId, Users)
        parent = EntityID(json.parent, Collections)
      }.id.value
    }
    call.respond(id)
  }

  post("/{id}") {
    requireLogin()
    val colId = call.parameters["id"]?.toIntOrNull() ?: throw ValidException("malformed id")
    val json: CollectionMeta = call.receive()

    transaction {
      val col = Collection.get(colId)
      if (json.title != null)
        col.title = json.title
      if (json.isRoute != null)
        col.isRoute = json.isRoute
      if (json.parent != null)
        col.parent = EntityID(json.parent, Collections)
    }
    call.respond(ServerOK("update success"))
  }

  get("/user/{id?}") { _ ->
    val paramId = call.parameters["id"]?.toInt()
    val sessId = call.sessions.get<LMSession>()?.userId
    val id = paramId ?: sessId ?: throw ValidException("id not specified")
    val ar = JsonArray<JsonObject>()
    transaction {
      Collection.find { Collections.owner eq id }.forEach {
        ar.add(json {
          obj(
            "id" to it.id,
            "title" to it.title,
            "created" to it.created,
            "isRoute" to it.isRoute,
            "parent" to it.parent
          )
        })
      }
    }
    call.respond(ar)
  }
}

