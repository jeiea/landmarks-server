package kr.ac.kw.coms.landmarks.server

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.json
import io.ktor.application.*
import io.ktor.content.MultiPartData
import io.ktor.content.PartData
import io.ktor.content.forEachPart
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.html.respondHtml
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.locations.Locations
import io.ktor.network.util.ioCoroutineDispatcher
import io.ktor.pipeline.PipelineContext
import io.ktor.request.receive
import io.ktor.request.receiveMultipart
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.sessions.*
import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.withContext
import kotlinx.coroutines.experimental.yield
import kotlinx.html.body
import kotlinx.html.p
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.sql.rowset.serial.SerialBlob

fun main(args: Array<String>) {
  val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
  val server = embeddedServer(
    Netty, port, module = Application::landmarksServer,
    watchPaths = listOf("landmarks-serverkt"))
  server.start(wait = true)
}

data class LMSession(val userId: Int)

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
      call.respond(HttpStatusCode.BadRequest, ErrorJson(cause.msg))
    }
    exception<Throwable> { cause ->
      call.respond(HttpStatusCode.InternalServerError, cause.toString())
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
      call.respondText("Hello, world!", ContentType.Text.Html)
    }
    get("/a") {
      call.respondHtml {
        body {
          p {
          }
        }
      }
    }

    authentication()
    picture()
    collection()
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
    val sessId = requireLogin().userId
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
    call.respond(SuccessJson("update success"))
  }

  get("/user/{id?}") {
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

private fun PipelineContext<Unit, ApplicationCall>.requireLogin() =
  call.sessions.get<LMSession>()
    ?: throw ValidException("login required", HttpStatusCode.Unauthorized)

suspend fun InputStream.copyToSuspend(
  out: OutputStream,
  bufferSize: Int = DEFAULT_BUFFER_SIZE,
  yieldSize: Int = 4 * 1024 * 1024,
  dispatcher: CoroutineDispatcher = ioCoroutineDispatcher
): Long {
  return withContext(dispatcher) {
    val buffer = ByteArray(bufferSize)
    var bytesCopied = 0L
    var bytesAfterYield = 0L
    while (true) {
      val bytes = read(buffer).takeIf { it >= 0 } ?: break
      out.write(buffer, 0, bytes)
      if (bytesAfterYield >= yieldSize) {
        yield()
        bytesAfterYield %= yieldSize
      }
      bytesCopied += bytes
      bytesAfterYield += bytes
    }
    return@withContext bytesCopied
  }
}

fun Routing.picture() = route("/picture") {
  put("/") {
    val parts: MultiPartData = call.receiveMultipart()
    val sess = requireLogin()
    transaction {
      Pictures.insert {
        runBlocking {
          parts.forEachPart(makePicture(it, sess.userId))
        }
        it[Pictures.owner] = EntityID(sess.userId, Users)
      }
    }

    call.respond(SuccessJson("Upload success"))
  }

  get("/user/{id}") {
    val ar = JsonArray<JsonObject>()
    transaction {
      ar.addAll(Picture.all().map {
        json {
          obj(
            "id" to it.id,
            "filename" to it.filename,
            "owner" to it.owner,
            "address" to it.address,
            "latit" to it.latit,
            "longi" to it.longi
          )
        }
      })
    }
    call.respond(ar.toJsonString())
  }

  get("/{id}") {
    val id = call.parameters["id"]?.toIntOrNull() ?: throw ValidException("id not valid")
    val bytes = transaction {
      val pic = Picture.findById(id) ?: throw ValidException("Not found", HttpStatusCode.NotFound)
      pic.file.binaryStream.readBytes()
    }
    call.respondBytes(bytes)
  }

  get("/thumbnail/{id}") {
    val id = call.parameters["id"]?.toIntOrNull() ?: throw ValidException("id not valid")
    val bytes = transaction {
      val pic = Picture.findById(id) ?: throw ValidException("Not found", HttpStatusCode.NotFound)
      pic.thumbnail.binaryStream.readBytes()
    }
    call.respondBytes(bytes)
  }
}

private fun Transaction.makePicture(stmt: InsertStatement<Number>, userId: Int):
  suspend (PartData) -> Unit = fld@{ part ->

  when (part) {
    is PartData.FormItem -> {
      when (part.name) {
        "lat" -> {
          stmt[Pictures.latit] = part.value.toFloatOrNull() ?: return@fld
        }
        "lon" -> {
          stmt[Pictures.longi] = part.value.toFloatOrNull() ?: return@fld
        }
        "address" -> {
          stmt[Pictures.address] = part.value
        }
      }
    }
    is PartData.FileItem -> {
      val name = File(part.originalFileName)
      stmt[Pictures.filename] =
        "${System.currentTimeMillis()}-" +
        "${userId.hashCode()}-" +
        "${part.originalFileName!!.hashCode()}.${name.extension}"
      ByteArrayOutputStream().use {
        Thumbnails.of(part.streamProvider())
          .size(200, 200)
          .toOutputStream(it)
        stmt[Pictures.thumbnail] = SerialBlob(it.toByteArray())
      }
      ByteArrayOutputStream().use {
        part.streamProvider().copyToSuspend(it)
        stmt[Pictures.file] = SerialBlob(it.toByteArray())
      }
    }
  }
}

