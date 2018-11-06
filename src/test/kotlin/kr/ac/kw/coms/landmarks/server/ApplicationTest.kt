package kr.ac.kw.coms.landmarks.server

import com.beust.klaxon.JsonBase
import com.beust.klaxon.json
import io.ktor.application.Application
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.call
import io.ktor.client.engine.apache.Apache
import io.ktor.client.engine.config
import io.ktor.client.features.cookies.AcceptAllCookiesStorage
import io.ktor.client.features.cookies.HttpCookies
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.append
import io.ktor.client.request.forms.formData
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.url
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.`should be`
import org.amshove.kluent.`should contain`
import org.apache.http.HttpHost
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.TestBody
import org.jetbrains.spek.api.dsl.TestContainer
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit

fun TestContainer.blit(description: String, body: suspend TestBody.() -> Unit) {
  it(description) {
    runBlocking { body() }
  }
}

@RunWith(JUnitPlatform::class)
class LandmarksSpek : Spek({

  val server =
    embeddedServer(Netty, 8080, module = Application::landmarksServer)

  describe("landmarks server") {
    val basePath = "http://localhost:8080"
    val proxy: InetSocketAddress? = getSystemProxy()
    val http = HttpClient(Apache.config {
      url {
        host = "localhost"
        port = 8080
      }
      proxy?.also {
        customizeClient {
          setProxy(HttpHost(it.hostName, it.port))
        }
      }
      socketTimeout = 0
      connectTimeout = 0
      connectionRequestTimeout = 0
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
