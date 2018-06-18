package kr.ac.kw.coms.landmarks.server

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.html.respondHtml
import io.ktor.http.ContentType
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.html.body
import kotlinx.html.p

fun main(args: Array<String>) {
  val server = embeddedServer(Netty, 8080, module = Application::landmarksServer)
  server.start(wait = true)
}

fun Application.landmarksServer() {
  install(DefaultHeaders)
  install(CallLogging)
  install(ConditionalHeaders)
  install(Compression)
  install(StatusPages) {

  }

  routing {
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
  }
}
