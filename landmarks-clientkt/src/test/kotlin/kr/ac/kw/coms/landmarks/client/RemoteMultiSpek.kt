package kr.ac.kw.coms.landmarks.client

import kotlinx.coroutines.*
import org.amshove.kluent.*
import org.junit.platform.runner.*
import org.junit.runner.*
import org.spekframework.spek2.*
import org.spekframework.spek2.style.specification.*
import java.io.*
import java.util.*

@RunWith(JUnitPlatform::class)
class RemoteMultiSpek : Spek({

  val client = newClient()

  val validUsers = listOf(
    AccountForm("login", "password", "email", "nick"),
    AccountForm("user01", "pass", "some@a.com", "헐크"),
    AccountForm("user02", "pass", "some@b.com", "바바리안"),
    AccountForm("user03", "pass", "some@c.com", "김삿갓"),
    AccountForm("user04", "pass", "some@d.com", "우비")
  )

  val invalidUsers = listOf(
    // a field empty
    AccountForm("", "fight!", "some@e.com", "헐크"),
    AccountForm("user11", "", "some@f.com", "헐크"),
    AccountForm("user12", "fight!", "", "헐크"),
    AccountForm("user13", "fight!", "some@h.com", ""),

    // a field null
    AccountForm(null, "fight!", "some@i.com", "헐크"),
    AccountForm("user11", null, "some@j.com", "헐크"),
    AccountForm("user12", "fight!", null, "헐크"),
    AccountForm("user13", "fight!", "some@k.com", null),

    // duplicate fields
    AccountForm("user01", "fight!", "some@l.com", "ahh"),
    AccountForm("user07", "fight!", "email", "grr"),
    AccountForm("user08", "fight!", "some@m.com", "nick")
  )

  val clients = mutableListOf<Remote>()

  describe("client can register only if valid") {
    beforeGroup {
      runBlocking {
        client.checkAlive().`should be true`()
        client.resetAllDatabase()
      }
    }

    blit("registers valid users") {
      validUsers.forEach { rep ->
        client.register(rep.login!!, rep.password!!, rep.email!!, rep.nick!!)
      }
    }

    blit("login as valid users") {
      validUsers.forEach { rep ->
        val cl = newClient()
        val profile = cl.login(rep.login!!, rep.password!!).data
        profile.login!! `should be equal to` rep.login!!
        profile.email!! `should be equal to` rep.email!!
        profile.nick!! `should be equal to` rep.nick!!
        clients.add(cl)
      }
    }

    blit("detects registration failure") {
      invalidUsers.forEach { rep ->
        invoking {
          runBlocking {
            client.register(rep.login!!, rep.password!!, rep.email!!, rep.nick!!)
          }
        } `should throw` Exception::class
      }
    }
  }

  val userPics = mutableListOf<MutableList<IdPictureInfo>>()
  val meta = mutableListOf<List<String>>()
  describe("test picture features with multiple users") {

    blit("uploads pictures") {
      val archive = File("../../landmarks-data/archive4")
      meta.addAll(archive.resolve("pic.tsv").bufferedReader().use {
        TsvReader(it).readAll().drop(28)
      })
      val picArchive = archive.resolve("files")
      val tasks = mutableListOf<Deferred<IdPictureInfo>>()
      for ((idx: Int, vs: List<String>) in meta.withIndex()) {
        val file: File = picArchive.resolve(vs[1])
        if (!file.exists()) {
          println("not exist: $file")
          continue
        }
        val lat = vs[2].toDouble()
        val lon = vs[3].toDouble()
        val addr = file.nameWithoutExtension.replace('_', ' ').replace("-mod", "")
        val info = PictureInfo(lat = lat, lon = lon, address = addr)
        tasks.add(GlobalScope.async {
          clients[idx % clients.size].uploadPicture(info, file)
        })
      }
      tasks.awaitAll()
    }

    blit("test valid access") {
      for ((index, value) in clients.withIndex()) {
        val pics = value.getPictures(PictureQuery().apply {
          limit = 1000
          userFilter = UserFilter.Include(value.profile!!.id)
        })
        val mine = meta.size / clients.size +
          if (index < meta.size % clients.size) 1 else 0
        pics.size `should be equal to` mine
        userPics.add(pics)

        val notMine = meta.size - mine
        val otherPics = value.getPictures(PictureQuery().apply {
          limit = 1000
          userFilter = UserFilter.Exclude(value.profile!!.id)
        })
        otherPics.size `should be equal to` notMine
      }
    }
  }

  describe("test collection features with multiple users") {
    blit("upload collections") {
      val ids = userPics.flatten().map { it.id }.toMutableList()
      val collIds = mutableListOf<Int>()
      var cnt = 1
      clients.zip(0..9).forEach { (cl, i) ->
        (1..4).forEach { j ->
          ids.shuffle()
          val coll = CollectionInfo(
            title = "diary $i-$j",
            text = "설명 $cnt 번째",
            images = ArrayList(ids.take(8)),
            isPublic = true,
            isRoute = true,
            likes = i,
            liking = true
          )
          val res = cl.uploadCollection(coll)
          collIds.add(res.id)
          cnt++
        }
      }
      collIds.size `should be equal to` collIds.distinct().size
    }

    blit("download collection") {
      clients.forEach { cl ->
        val coll = cl.getMyCollections()
        coll.size `should be equal to` 4
        coll.forEach { it.data.previews?.size!! `should be equal to` 8 }
      }
    }
  }
})
