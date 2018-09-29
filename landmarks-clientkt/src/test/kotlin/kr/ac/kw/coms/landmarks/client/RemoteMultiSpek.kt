package kr.ac.kw.coms.landmarks.client

import org.amshove.kluent.`should be equal to`
import org.amshove.kluent.`should be true`
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.io.File

@RunWith(JUnitPlatform::class)
class RemoteMultiSpek : Spek({

  fun newClient(): Remote {
    return Remote(getTestClient(), "http://localhost:8080")
  }

  val client = newClient()
  describe("makes server testable") {
    val client = Remote(getTestClient(), "http://localhost:8080")

    blit("resets server and uploads small sample problems") {
      client.resetAllDatabase()
      client.register("", "", "a@b.c", "")
      client.login("", "")
      val meta = PictureRep(lat = 0f, lon = 0f, address = "")
      client.uploadPicture(meta, File("../data/archive1/도쿄_.jpg"))
    }

    xblit("resets server and uploads sample problems") {
      client.resetAllDatabase()
      client.register("", "", "a@b.c", "")
      client.login("", "")
      val archive = File("../data/archive1")
      val catalog = archive.resolve("catalog.tsv").readText()
      val meta: List<List<String>> = catalog.split('\n').map { it.split('\t') }
      for (picture: List<String> in meta) {
        val file: File = archive.resolve(picture[0])
        val addr = file.nameWithoutExtension.replace('_', ' ')
        val info = PictureRep(lat = 0f, lon = 0f, address = addr)
        client.uploadPicture(info, file)
      }
    }
  }

  describe("client can do some query") {
    xblit("does reverse geocoding") {
      val res: ReverseGeocodeResult = client.reverseGeocode(37.54567, 126.9944)
      res.country!! `should be equal to` "대한민국"
      res.detail!! `should be equal to` "서울특별시"
    }

    blit("checks server health") {
      client.checkAlive().`should be true`()
    }

    // for test!!!!
    blit("reset all DB") {
      client.resetAllDatabase()
    }
  }

  val validUsers = listOf(
    LoginRep(-1, "login", "password", "email", "nick"),
    LoginRep(-1, "user01", "fight!", "some@a.com", "헐크"),
    LoginRep(-1, "user02", "비밀번호한글?", "some@b.com", "냥냥"),
    LoginRep(-1, "user03", "fight!", "some@c.com", "음..")
  )

  val invalidUsers = listOf(
    // a field empty
    LoginRep(-1, "", "fight!", "some@d.com", "헐크"),
    LoginRep(-1, "user04", "", "some@d.com", "헐크"),
    LoginRep(-1, "user05", "fight!", "", "헐크"),
    LoginRep(-1, "user06", "fight!", "some@d.com", ""),

    // a field null
    LoginRep(-1, "", "fight!", "some@e.com", "헐크"),
    LoginRep(-1, "user04", "", "some@f.com", "헐크"),
    LoginRep(-1, "user05", "fight!", "", "헐크"),
    LoginRep(-1, "user06", "fight!", "some@g.com", ""),

    // duplicate fields
    LoginRep(-1, "user01", "fight!", "some@e.com", "ahh"),
    LoginRep(-1, "user07", "fight!", "some@a.com", "grr"),
    LoginRep(-1, "user08", "fight!", "some@h.com", "nick")
  )

  val clients = mutableListOf<Remote>()

  describe("client can register only if valid") {
    blit("registers valid users") {
      validUsers.forEach { rep ->
        client.register(rep.login!!, rep.password!!, rep.email!!, rep.nick!!)
      }
    }

    blit("login as valid users") {
      validUsers.forEach { rep ->
        val cl = newClient()
        val profile = cl.login(rep.login!!, rep.password!!)
        profile.login!! `should be equal to` rep.login!!
        profile.email!! `should be equal to` rep.email!!
        profile.nick!! `should be equal to` rep.nick!!
        clients.add(cl)
      }
    }

    blit("detects registration failure") {
      invalidUsers.forEach { rep ->
        try {
          client.register(rep.login!!, rep.password!!, rep.email!!, rep.nick!!)
          assert(false)
        } catch (e: ServerFault) {
          // server throws expected error
        }
      }
    }
  }

  describe("test uploading pictures") {
    val tsv: String = File("../data/archive1/catalog.tsv").readText()
    val catalog = tsv.split('\n').map { it.split('\t') }

    val pics = mutableListOf<PictureRep>()
    blit("uploads picture") {

      for (i in 0..3) {
        val gps = i.toFloat()
        val meta = PictureRep(lat = gps, lon = gps, address = "address$i")
        val pic = client.uploadPicture(meta, File("../data/coord$i.jpg"))
        pics.add(pic)
      }
    }

  }
})
