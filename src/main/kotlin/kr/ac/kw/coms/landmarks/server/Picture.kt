package kr.ac.kw.coms.landmarks.server

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.GpsDirectory
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.request.receive
import io.ktor.request.receiveMultipart
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.routing.*
import kotlinx.coroutines.experimental.runBlocking
import kr.ac.kw.coms.landmarks.client.IdPictureInfo
import kr.ac.kw.coms.landmarks.client.PictureInfo
import kr.ac.kw.coms.landmarks.client.copyToSuspend
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.Blob
import javax.imageio.ImageIO
import javax.sql.rowset.serial.SerialBlob

fun Routing.picture() = route("/picture") {

  put("/") {
    val parts: MultiPartData = call.receiveMultipart()
    val sess: LMSession = requireLogin()
    val pic: IdPictureInfo = transaction {
      val uid = EntityID(sess.userId, Users)
      insertPicture(parts, uid).toIdPicture()
    }
    call.respond(pic)
  }

  get("/user/{id}") { _ ->
    val ar = mutableListOf<IdPictureInfo>()
    val calleeId: Int = getParamId(call)
    val callerId: Int = requireLogin().userId
    ar.addAll(transaction {
      Picture.find {
        isGrantedTo(callerId) and (Pictures.owner eq calleeId)
      }.map(Picture::toIdPicture)
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

  get("/info/{id}") { _ ->
    val id: Int = getParamId(call)
    val userId: Int = requireLogin().userId
    val pic: IdPictureInfo? = transaction {
      Picture.find {
        isGrantedTo(userId) and (Pictures.id eq id)
      }.firstOrNull()?.toIdPicture()
    }
    if (pic == null || pic.data.run { uid != userId && isPublic }) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.respond(pic.data)
    }
  }

  post("/info/{id}") { _ ->
    val id: Int = getParamId(call)
    val userId: Int = requireLogin().userId
    val info: PictureInfo = call.receive()
    val pic: IdPictureInfo? = transaction {
      val pic = Picture.find {
        isGrantedTo(userId) and (Pictures.id eq id)
      }.firstOrNull() ?: notFoundPage()
      pic.address = info.address
      pic.latit = info.lat
      pic.longi = info.lon
      pic.public = info.isPublic
      pic.toIdPicture()
    }
    if (pic == null || pic.data.run { uid != userId && isPublic }) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.respond(pic.data)
    }
  }

  get("/thumbnail/{id}") {
    val id: Int = getParamId(call)
    val desireWidth: Int = getIntParam(call, "width")
    val desireHeight: Int = getIntParam(call, "height")
    val userId: Int = requireLogin().userId
    val bytes = transaction {
      val pic = Picture.find {
        isGrantedTo(userId) and (Pictures.id eq id)
      }.firstOrNull() ?: notFoundPage()

      val level: Int = getThumbnailLevel(pic.width, pic.height, desireWidth, desireHeight)
      val fitThumbnail: Blob = when (level) {
        0 -> pic.file
        1 -> pic.thumbnail1
        2 -> pic.thumbnail2
        3 -> pic.thumbnail3
        else -> pic.thumbnail4
      }
      fitThumbnail.binaryStream.readBytes()
    }
    call.respondBytes(bytes)
  }
}

private fun getThumbnailLevel(
  picWidth: Int, picHeight: Int,
  desireWidth: Int, desireHeight: Int): Int {

  var width = picWidth
  var height = picHeight
  for (i in 0..3) {
    width /= 2
    height /= 2
    if (width < desireWidth || height < desireHeight) {
      return i
    }
  }
  return 4
}

fun SqlExpressionBuilder.isGrantedTo(userId: Int): Op<Boolean> {
  return (Pictures.isPublic eq true) or (Pictures.owner eq userId)
}

fun insertPicture(parts: MultiPartData, uid: EntityID<Int>): Picture {
  return Picture.new {
    owner = uid
    public = true
    runBlocking {
      parts.forEachPart(assemblePicture(this@new, uid.value))
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

    ar.inputStream().use {
      val img = ImageIO.read(ar.inputStream())
      record.width = img.width
      record.height = img.height
    }

    fun scaleOf(scale: Double): SerialBlob {
      buffer.reset()
      Thumbnails
        .of(ByteArrayInputStream(ar))
        .scale(scale)
        .toOutputStream(buffer)
      return SerialBlob(buffer.toByteArray())
    }
    record.thumbnail1 = scaleOf(0.5)
    record.thumbnail2 = scaleOf(0.25)
    record.thumbnail3 = scaleOf(0.125)
    record.thumbnail4 = scaleOf(0.0625)

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
