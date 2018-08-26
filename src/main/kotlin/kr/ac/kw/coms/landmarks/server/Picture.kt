package kr.ac.kw.coms.landmarks.server

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonJson
import com.beust.klaxon.json
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.GpsDirectory
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
import kr.ac.kw.coms.landmarks.client.copyToSuspend
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayInputStream
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

  get("/user/{id}") { _ ->
    val ar = JsonArray<JsonObject>()
    transaction {
      ar.addAll(Picture.all().map {
        KlaxonJson().obj(
          "id" to it.id,
          "filename" to it.filename,
          "owner" to it.owner,
          "address" to it.address,
          "latit" to it.latit,
          "longi" to it.longi
        )
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

fun insertPicture(parts: MultiPartData, sess: LMSession): Picture {
  return Picture.new {
    owner = EntityID(sess.userId, Users)
    runBlocking {
      parts.forEachPart(assemblePicture(this@new, sess.userId))
    }
  }
}

private fun assemblePicture(record: Picture, userId: Int):
  suspend (PartData) -> Unit = fld@{ part ->

  sl@ when (part) {
    is PartData.FormItem -> fillFormField(record, part)
    is PartData.FileItem -> receivePictureFile(record, part, userId)
  }

  part.dispose()
}

fun fillFormField(record: Picture, part: PartData.FormItem) {
  when (part.name) {
    "lat" -> {
      if (record.latit != null) return
      record.latit = part.value.toFloatOrNull() ?: return
    }
    "lon" -> {
      if (record.longi != null) return
      record.longi = part.value.toFloatOrNull() ?: return
    }
    "address" -> {
      record.address = part.value
    }
  }
}

suspend fun receivePictureFile(record: Picture, part: PartData.FileItem, userId: Int) {
  record.filename = timeUidOriginalNameHash(part.originalFileName!!, userId)

  ByteArrayOutputStream().use { buffer ->
    part.streamProvider().use { it.copyToSuspend(buffer) }
    val ar = buffer.toByteArray()
    record.file = SerialBlob(ar)
    buffer.reset()

    Thumbnails.of(ByteArrayInputStream(ar))
      .size(200, 200)
      .toOutputStream(buffer)
    record.thumbnail = SerialBlob(buffer.toByteArray())
    buffer.reset()

    ImageMetadataReader
      .readMetadata(ByteArrayInputStream(ar))
      .getFirstDirectoryOfType(GpsDirectory::class.java)
      ?.geoLocation
      ?.also {
        record.latit = it.latitude.toFloat()
        record.longi = it.longitude.toFloat()
      }
  }
}

fun timeUidOriginalNameHash(path: String, userId: Int): String {
  val name = File(path)
  val time = System.currentTimeMillis()
  val uid = userId.hashCode()
  val hash = path.hashCode()
  val ext = name.extension
  return "$time-$uid-$hash.$ext"
}
