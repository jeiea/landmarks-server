package kr.ac.kw.coms.landmarks.client

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import io.ktor.client.HttpClient
import io.ktor.client.call.receive
import io.ktor.client.engine.android.Android
import io.ktor.client.features.cookies.AcceptAllCookiesStorage
import io.ktor.client.features.cookies.HttpCookies
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.response.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.experimental.channels.ArrayChannel
import kotlinx.coroutines.experimental.channels.sendBlocking
import kotlinx.coroutines.experimental.delay
import kotlinx.io.InputStream
import kotlinx.io.core.writeFully
import java.io.File
import java.net.URLEncoder
import java.util.*
import kotlin.math.max

class Remote(base: HttpClient, val basePath: String = herokuUri) {

  val http: HttpClient
  val nominatimLastRequestMs = ArrayChannel<Long>(1)

  companion object {
    const val herokuUri = "https://landmarks-coms.herokuapp.com"
    private const val chromeAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/68.0.3440.59 Safari/537.36"
  }

  var profile: LoginRep? = null

  suspend inline fun <reified T> request(method: HttpMethod, url: String, builder: HttpRequestBuilder.() -> Unit = {}): T {
    val response: HttpResponse = http.request {
      this.method = method
      url(url)
      header("User-Agent", "landmarks-client")
      builder()
    }
    if (response.status.isSuccess()) {
      return response.call.receive()
    }
    throw response.call.receive<ServerFault>()
  }

  private suspend inline fun <reified T> get(url: String, builder: HttpRequestBuilder.() -> Unit = {}): T =
    request(HttpMethod.Get, url, builder)

  private suspend inline fun <reified T> post(url: String, builder: HttpRequestBuilder.() -> Unit = {}): T =
    request(HttpMethod.Post, url, builder)

  private suspend inline fun <reified T> put(url: String, builder: HttpRequestBuilder.() -> Unit = {}): T =
    request(HttpMethod.Put, url, builder)

  init {
    nominatimLastRequestMs.sendBlocking(0)
    http = base.config {
      install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
      }
      install(JsonFeature) {
      }
    }
  }

  constructor() : this(HttpClient(Android.create()), herokuUri)

  private fun HttpRequestBuilder.json(json: Any) {
    contentType(ContentType.Application.Json)
    body = json
  }

  suspend fun reverseGeocode(latitude: Double, longitude: Double): ReverseGeocodeResult {
    val last: Long = nominatimLastRequestMs.receive()
    delay(max(0, last + 1000 - Date().time))

    val ret: ReverseGeocodeResult = reverseGeocodeUnsafe(latitude, longitude)

    val next: Long = Date().time + 1000
    nominatimLastRequestMs.send(next)

    return ret
  }

  private suspend fun reverseGeocodeUnsafe(latitude: Double, longitude: Double): ReverseGeocodeResult {
    val json: String = get("https://nominatim.openstreetmap.org/reverse") {
      parameter("format", "json")
      parameter("accept-language", "ko,en")
      parameter("lat", latitude.toString())
      parameter("lon", longitude.toString())
      userAgent(chromeAgent)
    }
    val obj: JsonObject = Parser().parse(StringBuilder(json)) as JsonObject
    return ReverseGeocodeResult(obj)
  }

  suspend fun checkAlive(): Boolean {
    return try {
      get<Unit>("$basePath/")
      true
    } catch (e: Throwable) {
      false
    }
  }

  suspend fun resetAllDatabase() {
    put<Unit>("$basePath/maintenance/reset")
  }

  suspend fun register(ident: String, pass: String, email: String, nick: String) {
    val regFields = LoginRep(
      login = ident,
      password = pass,
      email = email,
      nick = nick
    )
    post<Unit>("$basePath/auth/register") {
      json(regFields)
    }
  }

  suspend fun login(ident: String, pass: String): LoginRep {
    profile = post("$basePath/auth/login") {
      json(LoginRep(login = ident, password = pass))
    }
    return profile!!
  }

  private fun filenameHeader(name: String): Headers {
    return headersOf(HttpHeaders.ContentDisposition, "filename=$name")
  }

  suspend fun uploadPicture(file: File, latitude: Float? = null, longitude: Float? = null, addr: String? = null): PictureRep {
    val filename: String = URLEncoder.encode(file.name, "UTF-8")
    return put("$basePath/picture") {
      body = MultiPartFormDataContent(formData {
        latitude?.also { append("lat", it.toString()) }
        longitude?.also { append("lon", it.toString()) }
        addr?.also { append("address", it) }
        append("pic0", filenameHeader(filename)) {
          writeFully(file.readBytes())
        }
      })
    }
  }

  suspend fun getRandomProblems(n: Int): List<PictureRep> {
    return get("$basePath/problem/random/$n")
  }

  suspend fun modifyPicture(info: PictureRep): PictureRep {
    return post("$basePath/picture/${info.id}")
  }

  suspend fun getPictureInfo(id: Int): PictureRep {
    return get("$basePath/picture/$id/info")
  }

  suspend fun deletePicture(id: Int) {
    TODO()
  }

  suspend fun getPicture(id: Int): InputStream {
    return get("$basePath/picture/$id")
  }

  suspend fun getPictureInfos(userId: Int): List<PictureRep> {
    return get("$basePath/picture/user/$userId")
  }

  suspend fun getMyPictureInfos(): List<PictureRep> {
    return getPictureInfos(profile!!.id!!)
  }


  suspend fun uploadCollection(collection: CollectionRep): CollectionRep {
    return put("$basePath/collection") {
      json(collection)
    }
  }

  suspend fun getCollections(ownerId: Int): List<CollectionRep> {
    return get("$basePath/collection/user/$ownerId")
  }

  suspend fun getMyCollections(): List<CollectionRep> {
    return getCollections(profile!!.id!!)
  }

  suspend fun modifyCollection(collection: CollectionRep): CollectionRep {
    return post("$basePath/collection/${collection.id}") {
      json(collection)
    }
  }

  suspend fun deleteCollection(id: Int) {
    TODO()
  }


}
