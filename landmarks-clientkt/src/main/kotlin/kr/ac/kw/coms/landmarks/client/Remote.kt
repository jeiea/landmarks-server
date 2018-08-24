package kr.ac.kw.coms.landmarks.client

import com.beust.klaxon.JsonBase
import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonJson
import com.beust.klaxon.Parser
import com.beust.klaxon.internal.firstNotNullResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.features.cookies.AcceptAllCookiesStorage
import io.ktor.client.features.cookies.HttpCookies
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.sendBlocking
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.io.jvm.javaio.toOutputStream
import java.io.File
import java.util.*
import kotlin.math.max

class Remote(base: HttpClient, val basePath: String) {

  val http: HttpClient
  val nominatimReqLimit = Channel<Long>(1)

  companion object {
    const val herokuUri = "https://landmarks-coms.herokuapp.com"
    private const val chromeAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/68.0.3440.59 Safari/537.36"
  }

  init {
    nominatimReqLimit.sendBlocking(0)
    http = base.config {
      install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
      }
      install(JsonFeature) {
        serializer = KlaxonSerializer()
      }
    }
  }

  constructor() : this(HttpClient(Android.create()), herokuUri)

  private fun HttpRequestBuilder.userAgent() {
    header("User-Agent", "landmarks-client")
  }

  private fun HttpRequestBuilder.json(json: JsonBase) {
    contentType(ContentType.Application.Json)
    body = json
  }

  suspend fun suspendForNominatimReqPerSecLimit() {
    val last: Long = nominatimReqLimit.receive()
    nominatimReqLimit.offer(Date().time)
    delay(max(0, last + 1000 - Date().time))
  }

  suspend fun reverseGeocode(latitude: Double, longitude: Double): Pair<String?, String?>? {
    suspendForNominatimReqPerSecLimit()

    val json: String = http.get("https://nominatim.openstreetmap.org/reverse") {
      parameter("format", "json")
      parameter("lat", latitude.toString())
      parameter("lon", longitude.toString())
      userAgent(chromeAgent)
    }
    val res = Parser().parse(StringBuilder(json)) as JsonObject
    val addr = res.obj("address") ?: return null
    val detail = listOf("city", "county", "town", "attraction").map(addr::string).firstNotNullResult { it }
    return addr.string("country") to detail
  }

  private fun throwIfFailure(resp: String) {
    if (!resp.contains("success")) {
      throw RuntimeException(resp)
    }
  }

  suspend fun checkAlive(): Boolean {
    val resp: String = http.get("$basePath/")
    return resp.contains("Hello")
  }

  suspend fun register(ident: String, pass: String, email: String, nick: String) {
    val regFields: JsonObject = KlaxonJson().obj(
      "login" to ident,
      "password" to pass,
      "email" to email,
      "nick" to nick
    )
    val resp: String = http.post("$basePath/auth/register") {
      userAgent()
      json(regFields)
    }
    throwIfFailure(resp)
  }

  suspend fun login(ident: String, pass: String) {
    val param: JsonObject = KlaxonJson().obj(
      "login" to ident,
      "password" to pass
    )
    val resp: String = http.post("$basePath/auth/login") {
      userAgent()
      json(param)
    }
    throwIfFailure(resp)
  }

  suspend fun uploadPic(file: File, latitude: Float? = null, longitude: Float? = null, addr: String? = null) {
    val resp: String = http.request {
      method = HttpMethod.Put
      url.takeFrom("$basePath/picture")
      body = MultiPartContent.build {
        latitude?.also { add("lat", it.toString()) }
        longitude?.also { add("lon", it.toString()) }
        addr?.also { add("address", it) }
        add("pic0", filename = file.name) {
          file.inputStream().copyTo(toOutputStream())
        }
      }
    }
    throwIfFailure(resp)
  }

  data class PictureRep(
    val id: Int,
    val owner: Int,
    val address: String,
    val lat: Float,
    val lon: Float,
    var file: ByteArray?
  )

  suspend fun getRandomProblem(): PictureRep {
    val pic: PictureRep = http.get("$basePath/problem/random")
    pic.file = http.get("$basePath/picture/${pic.id}")
    return pic
  }
}
