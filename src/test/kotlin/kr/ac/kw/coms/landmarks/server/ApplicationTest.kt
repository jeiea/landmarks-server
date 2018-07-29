package kr.ac.kw.coms.landmarks.server

import awaitStringResponse
import awaitStringResult
import com.beust.klaxon.JsonObject
import com.beust.klaxon.json
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.DataPart
import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.core.Method
import com.github.kittinunf.fuel.core.Request
import io.ktor.application.Application
import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.experimental.runBlocking
import org.amshove.kluent.`should be equal to`
import org.amshove.kluent.`should contain`
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.TestBody
import org.jetbrains.spek.api.dsl.TestContainer
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.io.File
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
    val http = FuelManager()

    fun get(path: String, param: List<Pair<String, Any?>>? = null): Request {
      return http.request(Method.GET, path, param)
    }

    fun post(path: String, param: List<Pair<String, Any?>>? = null): Request {
      return http.request(Method.POST, path, param)
    }

    fun post(path: String, json: JsonObject): Request {
      return http
        .request(Method.POST, path)
        .header("Content-Type" to "application/json")
        .body(json.toJsonString())
    }

    beforeGroup {
      server.start()
      // TBD it is useless. it only affects to debug mode. I don't know why.
      System.setProperty("java.net.useSystemProxies", "true");
      ProxySelector.getDefault().select(URI("http://localhost")).also {
        for (proxy: Proxy in it) {
          http.basePath = basePath
          http.proxy = proxy
          println("proxy: $proxy")
        }
      }
    }

    blit("should pass health test") {
      val result = get("/").awaitStringResult().get()
      result `should contain` "Hello"
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
    val registrationReq by lazy {
      post("/auth/register", regFields)
    }

    blit("should reject invalid user-agent") {
      val (_, resp, _) =
        registrationReq.awaitStringResponse()
      resp.statusCode `should be equal to` 400
    }

    blit("can register") {
      val result = registrationReq
        .header("User-Agent" to "landmarks-client")
        .awaitStringResult()
      result.get() `should contain` "success"
    }

    blit("can login") {
      val json = json { obj("login" to ident, "password" to "pass") }
      val result = post("/auth/login", json)
        .header("User-Agent" to "landmarks-client")
        .awaitStringResult()
      result.get() `should contain` "success"
    }

    blit("can receive picture") {
      val result = http
        .request(Method.PUT, "/picture", listOf("latlon" to "3,3"))
        .header("Content-Type" to "multipart/form-data; boundary=a93ah3FoeKDl09")
        .apply { type = Request.Type.UPLOAD }
        .dataParts { request, url ->
          listOf(DataPart(File("coord0.jpg")))
        }.awaitStringResult()
      result.get() `should contain` "success"
    }

    afterGroup {
      server.stop(2, 2, TimeUnit.SECONDS)
    }
  }
})
