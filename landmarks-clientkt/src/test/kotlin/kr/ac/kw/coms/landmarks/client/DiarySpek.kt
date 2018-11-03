package kr.ac.kw.coms.landmarks.client

import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.awaitAll
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.map
import kotlin.collections.mutableListOf


@RunWith(JUnitPlatform::class)
class DiarySpek : Spek({
  val client = newClient()

  describe("Upload diaries to heroku server") {
    blit("register") {
      try {
        client.register("diary", "dlfrl", "diary@kaka.om", "뿜")
      }
      catch (e: Exception) {
      }
      client.login("diary", "dlfrl")
    }

    lateinit var pictures: List<IdPictureInfo>
    xblit("get pictures") {
      pictures = client.getPictures(PictureQuery().apply {
        limit = 99999
        userFilter = UserFilter.Include(client.profile!!.id)
      })
    }

    val archive = File("../../landmarks-data/archive4")
    blit("uploads pictures") {
      val pics = archive.resolve("pic.tsv").bufferedReader().use {
        TsvReader(it).readAll().drop(1)
      }
      val tasks = mutableListOf<Deferred<IdPictureInfo>>()
      val picArchive = archive.resolve("files")
      for (vs: List<String> in pics) {
        val file: File = picArchive.resolve(vs[1])
        if (!file.exists()) {
          println("not exist: $file")
          continue
        }
        val lat = vs[2].toDouble()
        val lon = vs[3].toDouble()
        val addr = file.nameWithoutExtension.replace('_', ' ').replace("-mod", "")
        val info = PictureInfo(lat = lat, lon = lon, address = addr)
        tasks.add(GlobalScope.async { client.uploadPicture(info, file) })
      }
      pictures = tasks.awaitAll()
    }

    blit("uploads diaries") {
      val diaries = archive.resolve("diary.tsv").bufferedReader().use {
        TsvReader(it).readAll().drop(1)
      }
      val tasks = mutableListOf<Deferred<IdCollectionInfo>>()
      diaries.map { vs: List<String> ->
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.KOREAN)
        val begDate = LocalDate.parse(vs[0], formatter)
        val endDate = LocalDate.parse(vs[1], formatter)
        val title = vs[2]
        val isRoute = vs[3] == "O"
        val picIds = vs[4].trim().split(',').map(String::toInt)
        val text = vs[5]
        val remoteIds = ArrayList(picIds.map { pictures[it - 1].id })
        val col = CollectionInfo(title, text, remoteIds, isRoute = isRoute)
        tasks.add(GlobalScope.async { client.uploadCollection(col) })
      }
      tasks.awaitAll()
    }
  }
})
