package kr.ac.kw.coms.landmarks.server

import io.ktor.application.Application
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.request.contentType
import io.ktor.request.receive
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withTestApplication
import org.amshove.kluent.*
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
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
      val email = "12341234@grr.la"
      with(handleRequest(HttpMethod.Post, "auth/authentication") {
        addHeader(HttpHeaders.ContentType, "application/json")
        addHeader(HttpHeaders.UserAgent, "landmarks-client")
        setBody("""{"login":"tlogin","password":"pass","email": "${email}","nick":"tnick"}""")
      }){
        it("can handling registration") {
//          response.status() `should be` HttpStatusCode.OK
          response.content!! `should contain` "success"
        }
      }
      var verifyCode: String?
      it("should store registration info") {
        verifyCode = transaction { User.select { User.email eq email }.first()[User.verification]}
        verifyCode `should not be`  null
      }
      with(handleRequest(HttpMethod.Post, "auth/login") {
        addHeader(HttpHeaders.ContentType, "application/json")
        addHeader(HttpHeaders.UserAgent, "landmarks-client")
        setBody("""{"login":"tlogin", "password":"pass"}""")
      }){
        it("can login") {
//          response.status() `should be` HttpStatusCode.OK
          response.content!! `should contain` "success"
        }
      }
    }
  }
})
