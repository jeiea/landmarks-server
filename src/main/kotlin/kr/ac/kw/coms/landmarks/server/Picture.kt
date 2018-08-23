package kr.ac.kw.coms.landmarks.server

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.json
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.request.receiveMultipart
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.put
import io.ktor.routing.route
import kotlinx.coroutines.experimental.runBlocking
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sql.rowset.serial.SerialBlob

fun Routing.picture() = route("/picture") {
  put("/") {
    val parts: MultiPartData = call.receiveMultipart()
    val sess = requireLogin()
    transaction {
      insertPicture(parts, sess)
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
      val pic = Picture.findById(id)
        ?: throw ValidException("Not found", HttpStatusCode.NotFound)
      pic.file.binaryStream.readBytes()
    }
    call.respondBytes(bytes)
  }

  get("/thumbnail/{id}") {
    val id = call.parameters["id"]?.toIntOrNull() ?: throw ValidException("id not valid")
    val bytes = transaction {
      val pic = Picture.findById(id)
        ?: throw ValidException("Not found", HttpStatusCode.NotFound)
      pic.thumbnail.binaryStream.readBytes()
    }
    call.respondBytes(bytes)
  }
}

fun insertPicture(parts: MultiPartData, sess: LMSession): InsertStatement<Number> {
  return Pictures.insert { stmt ->
    runBlocking {
      parts.forEachPart(assemblePicture(stmt, sess.userId))
    }
    stmt[owner] = EntityID(sess.userId, Users)
  }
}

private fun assemblePicture(stmt: InsertStatement<Number>, userId: Int):
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
        part.streamProvider().copyTo(it)
        stmt[Pictures.file] = SerialBlob(it.toByteArray())
      }
    }
  }

  part.dispose()
}
