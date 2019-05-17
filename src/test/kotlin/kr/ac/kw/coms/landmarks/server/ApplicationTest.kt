package kr.ac.kw.coms.landmarks.server

import com.beust.klaxon.*
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.features.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import kotlinx.coroutines.*
import org.amshove.kluent.*
import org.jetbrains.spek.api.*
import org.jetbrains.spek.api.dsl.*
import org.junit.platform.runner.*
import org.junit.runner.*
import java.io.*
import java.net.*
import java.util.concurrent.*

fun TestContainer.blit(description: String, body: suspend TestBody.() -> Unit) {
  it(description) {
    runBlocking { body() }
  }
}

@RunWith(JUnitPlatform::class)
class LandmarksSpek : Spek({

  val server = embeddedServer(Netty, 8080, module = Application::landmarksServer)

  describe("landmarks server") {
    val basePath = "http://localhost:8080"
    val sysProxy: InetSocketAddress? = getSystemProxy()
    val http = HttpClient(OkHttp.create {
      url {
        host = "localhost"
        port = 8080
      }
      config {
        readTimeout(0, TimeUnit.SECONDS)
        writeTimeout(0, TimeUnit.SECONDS)
        connectTimeout(0, TimeUnit.SECONDS)
        sysProxy?.also {
          proxy(Proxy(Proxy.Type.HTTP, it))
        }
      }
    }) {
      install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
      }
    }

    beforeGroup {
      server.start()
    }

    fun HttpRequestBuilder.userAgent() {
      header("User-Agent", "landmarks-client")
    }

    fun HttpRequestBuilder.json(json: JsonBase) {
      contentType(ContentType.Application.Json)
      body = json.toJsonString()
    }

    blit("should pass health test") {
      val resp = http.get<String>(basePath)
      resp `should contain` "Hello"
    }

    val ident = getRandomString(10)
    val email = "$ident@grr.la"
    val regFields = json {
      obj(
        "login" to ident,
        "password" to "pass",
        "email" to email,
        "nick" to ident
      )
    }
    suspend fun HttpRequestBuilder.registrationReq () {
      method = HttpMethod.Post
      url.takeFrom("$basePath/auth/register")
      json(regFields)
    }

    blit("should reject invalid user-agent") {
      val call: HttpClientCall = http.call { registrationReq() }
      call.response.status `should be` HttpStatusCode.BadRequest
    }

    blit("can register") {
      val resp: String = http.post {
        userAgent()
        registrationReq()
      }
      resp `should contain` "success"
    }

    blit("can login") {
      val param = json { obj("login" to ident, "password" to "pass") }
      val result: String = http.post("$basePath/auth/login") {
        userAgent()
        json(param)
      }
      result `should contain` "success"
    }

    blit("can receive picture") {
      val str: String = http.request {
        method = HttpMethod.Put
        url.takeFrom("$basePath/picture")
        body = MultiPartFormDataContent(formData {
          append("lat", "3.3")
          append("lon", "3.0")
          append("address", "somewhere on earth")
          append("pic0", "coord0.jpg") {
            val bytes = File("coord0.jpg").readBytes()
            writeFully(bytes, 0, bytes.size)
          }
        })
      }
      str `should contain` "success"
    }

    afterGroup {
      server.stop(2, 2, TimeUnit.SECONDS)
    }
  }
})

private fun getSystemProxy(): InetSocketAddress? {
  val vmoUseProxy = "java.net.useSystemProxies"
  System.setProperty(vmoUseProxy, "true");
  val proxies = ProxySelector
    .getDefault().select(URI("http://localhost"))
  for (proxy: Proxy in proxies) {
    println("proxy: $proxy")
    return proxy.address() as InetSocketAddress
  }
  System.setProperty(vmoUseProxy, null);
  return null
}
