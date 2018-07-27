package kr.ac.kw.coms.landmarks.server

import io.ktor.application.Application
import io.ktor.content.PartData
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.*
import org.amshove.kluent.*
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(JUnitPlatform::class)
class LandmarksSpek : Spek({
  fun TestApplicationRequest.jsonClient() {
    addHeader(HttpHeaders.ContentType, "application/json")
    addHeader(HttpHeaders.UserAgent, "landmarks-client")
  }
  fun TestApplicationCall.shouldSuccess() {
    response.status() `should be` HttpStatusCode.OK
    response.content!! `should contain` "success"
  }
  describe("landmarks server") {
    withTestApplication(Application::landmarksServer) {
      with(handleRequest(HttpMethod.Get, "/")) {
        it("should pass health test") {
          response.status() `should be` HttpStatusCode.OK
        }
      }

      val ident = getRandomString(10)
      val email = "$ident@grr.la"

      with(handleRequest(HttpMethod.Post, "auth/authentication") {
        setBody("""{"login":"$ident","password":"pass","email": "${email}","nick":"$ident"}""")
      }) {
        it("should reject invalid user-agent") {
          response.status() `should not be` HttpStatusCode.OK
          response.content?.`should not contain`("success")
        }
      }

      with(handleRequest(HttpMethod.Post, "auth/authentication") {
        jsonClient()
        setBody("""{"login":"$ident","password":"pass","email": "${email}","nick":"$ident"}""")
      }) {
        it("can handling registration") {
          shouldSuccess()
        }
      }

      var verifyCode: String?
      it("should store registration info") {
        verifyCode = transaction {
          Users.select { Users.email eq email }.first()[Users.verification]
        }
        verifyCode `should not be` null
      }
      with(handleRequest(HttpMethod.Post, "auth/login") {
        jsonClient()
        setBody("""{"login":"$ident", "password":"pass"}""")
      }) {
        it("can login") {
          shouldSuccess()
        }
      }

      val boundary: String = "-".repeat(30) + getRandomString(10)
      val gps0 = File("coord0.jpg").inputStream()
      with(handleRequest(HttpMethod.Put, "picture") {
        setBody(boundary, listOf(
          PartData.FormItem("latlon", {}, Headers.build {
            this[HttpHeaders.ContentDisposition] = """inline; name="latlon""""
          }),
          partFromFile(File("coord0.jpg"), "totoroo")
        ))
      }) {
        it("can receive picture") {
          shouldSuccess()
        }
      }
    }
  }
})

fun partFromFile(file: File, name: String? = null)
  : PartData.FileItem {
  val fis: FileInputStream = file.inputStream()
  return PartData.FileItem({fis}, {fis.close()}, Headers.build{
    val filename: String = ContentDisposition.Parameters.FileName
    val params: ArrayList<HeaderValueParam> = arrayListOf(HeaderValueParam(filename, file.name))
    name?.also { params.add(HeaderValueParam("name", it)) }
    val cd: ContentDisposition = ContentDisposition.File.withParameter(filename, file.name)
    this[HttpHeaders.ContentDisposition] = cd.disposition
    this[HttpHeaders.ContentType] = ContentType.defaultForFileExtension(".jpg").toString()
  })
}
