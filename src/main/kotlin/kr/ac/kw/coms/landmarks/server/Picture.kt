package kr.ac.kw.coms.landmarks.server

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.GpsDirectory
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.pipeline.PipelineContext
import io.ktor.request.receiveMultipart
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.put
import io.ktor.routing.route
import kotlinx.coroutines.experimental.runBlocking
import kr.ac.kw.coms.landmarks.client.PictureRep
import kr.ac.kw.coms.landmarks.client.copyToSuspend
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sql.rowset.serial.SerialBlob

fun Routing.picture() = route("/picture") {

  put("/") {
    val parts: MultiPartData = call.receiveMultipart()
    val sess: LMSession = requireLogin()
    val pic: Picture = transaction {
      insertPicture(parts, sess)
    }
    call.respond(pic.toPictureRep())
  }

  get("/user/{id}") { _ ->
    val ar = arrayListOf<PictureRep>()
    val calleeId: Int = getParamId(call)
    val callerId: Int = requireLogin().userId
    ar.addAll(transaction {
      Picture.find {
        isGrantedTo(callerId) and (Pictures.owner eq calleeId)
      }.map(Picture::toPictureRep)
    })
    call.respond(ar)
  }

  get("/{id}") {
    val id: Int = getParamId(call)
    val userId: Int = requireLogin().userId
    val bytes = transaction {
      val p = Picture.find { isGrantedTo(userId) and (Pictures.id eq id) }.firstOrNull()
        p ?: notFoundPage()
      p.file.binaryStream.readBytes()
    }
    call.respondBytes(bytes)
  }

  get("/{id}/info") { _ ->
    val id: Int = getParamId(call)
    val userId: Int = requireLogin().userId
    val pic: PictureRep? = transaction {
      Picture.find {
        isGrantedTo(userId) and (Pictures.id eq id)
      }.firstOrNull()?.toPictureRep()
    }
    if (pic == null || pic.owner != userId && !pic.isPublic) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.respond(pic)
    }
  }

  get("/thumbnail/{id}") {
    val id: Int = getParamId(call)
    val userId: Int = requireLogin().userId
    val bytes = transaction {
      val pic = Picture.find {
        isGrantedTo(userId) and (Pictures.id eq id)
      }.firstOrNull() ?: throw ValidException("Not found", HttpStatusCode.NotFound)
      pic.thumbnail.binaryStream.readBytes()
    }
    call.respondBytes(bytes)
  }
}

fun SqlExpressionBuilder.isGrantedTo(userId: Int): Op<Boolean> {
  return (Pictures.public eq true) or (Pictures.owner eq userId)
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

  when (part) {
    is PartData.FormItem -> fillFormField(record, part)
    is PartData.FileItem -> receivePictureFile(record, part, userId)
  }
  record.created = DateTime.now()

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
    "isPublic" -> {
      record.public = part.value.toBoolean()
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

    getLatLon(ar)?.also { (lat, lon) ->
      record.latit = lat
      record.longi = lon
    }
  }
}

fun getLatLon(ar: ByteArray): Pair<Float, Float>? {
  return ImageMetadataReader
    .readMetadata(ByteArrayInputStream(ar))
    .getFirstDirectoryOfType(GpsDirectory::class.java)
    ?.geoLocation?.run {
    Pair(latitude.toFloat(), longitude.toFloat())
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
