package kr.ac.kw.coms.landmarks.client

import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.io.File


@RunWith(JUnitPlatform::class)
class UploadSpek : Spek({
  describe("makes server testable") {
    val client = Remote(getTestClient(), "http://localhost:8080")

    blit("resets server and uploads small sample problems") {
      client.resetAllDatabase()
      client.register("", "", "a@b.c", "")
      client.login("", "")
      client.uploadPicture(File("../data/archive1/도쿄_.jpg"), 0f, 0f, "")
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
        client.uploadPicture(file, 0f, 0f, addr)
      }
    }
  }
})