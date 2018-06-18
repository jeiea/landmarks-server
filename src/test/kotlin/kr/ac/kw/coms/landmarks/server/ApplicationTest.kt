package kr.ac.kw.coms.landmarks.server

import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import org.amshove.kluent.`should be false`
import org.amshove.kluent.`should be`
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith

@RunWith(JUnitPlatform::class)
class LandmarksSpek : Spek({
  describe("landmarks server") {
    withTestApplication(Application::landmarksServer) {
      with(handleRequest(HttpMethod.Get, "/")) {
        it("can process / request") {
          response.status() `should be` HttpStatusCode.OK
        }
      }
      with(handleRequest(HttpMethod.Get, "/index.html")) {
        it("doesn't have /index.html") {
          requestHandled.`should be false`()
        }
      }
    }
  }
})
