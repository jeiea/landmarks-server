package kr.ac.kw.coms.landmarks.client

import kotlinx.coroutines.experimental.*
import org.amshove.kluent.`should be equal to`
import org.amshove.kluent.`should be greater than`
import org.amshove.kluent.`should be true`
import org.amshove.kluent.`should throw`
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.xdescribe
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.io.File
import java.util.*

@RunWith(JUnitPlatform::class)
class RemoteMultiSpek : Spek({

  fun newClient(): Remote {
//    return Remote(getTestClient(), "http://localhost:8080")
    return Remote(getTestClient(), "http://landmarks-coms.herokuapp.com/")
  }

  val client = newClient()

  val validUsers = listOf(
    AccountForm("login", "password", "email", "nick"),
    AccountForm("user01", "fight!", "some@a.com", "헐크"),
    AccountForm("user02", "비밀번호한글?", "some@b.com", "냥냥"),
    AccountForm("user03", "fight!", "some@c.com", "음..")
  )

  val invalidUsers = listOf(
    // a field empty
    AccountForm("", "fight!", "some@d.com", "헐크"),
    AccountForm("user04", "", "some@d.com", "헐크"),
    AccountForm("user05", "fight!", "", "헐크"),
    AccountForm("user06", "fight!", "some@d.com", ""),

    // a field null
    AccountForm("", "fight!", "some@e.com", "헐크"),
    AccountForm("user04", "", "some@f.com", "헐크"),
    AccountForm("user05", "fight!", "", "헐크"),
    AccountForm("user06", "fight!", "some@g.com", ""),

    // duplicate fields
    AccountForm("user01", "fight!", "some@e.com", "ahh"),
    AccountForm("user07", "fight!", "some@a.com", "grr"),
    AccountForm("user08", "fight!", "some@h.com", "nick")
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
        {
          runBlocking {
            val x = client.register(rep.login!!, rep.password!!, rep.email!!, rep.nick!!)
            throw RuntimeException(x.toString())
          }
        } `should throw` ServerFault::class
      }
    }
  }

  val userPics = mutableListOf<MutableList<IdPictureInfo>>()
  describe("test picture features with multiple users") {
    blit("uploads pictures") {
      val archive = File("../../landmarks-data/archive1")
      val catalog = archive.resolve("catalog.tsv").readText()
      val meta: List<List<String>> = catalog.split('\n').map { it.split('\t') }
      val tasks = mutableListOf<Deferred<IdPictureInfo>>()
      for ((idx: Int, vs: List<String>) in meta.withIndex()) {
        val file: File = archive.resolve(vs[0])
        val lat = vs[1].toFloat()
        val lon = vs[2].toFloat()
        val addr = file.nameWithoutExtension.replace('_', ' ')
        val info = PictureInfo(lat = lat, lon = lon, address = addr)
        tasks.add(GlobalScope.async {
          clients[idx % clients.size].uploadPicture(info, file)
        })
      }
      tasks.awaitAll()
    }

    blit("test valid access") {
      clients.forEach {
        val pics = it.getMyPictureInfos()
        pics.size `should be greater than` 7
        userPics.add(pics)
      }
    }
  }

  describe("test collection features with multiple users") {
    blit("upload collections") {
      val ids = userPics.flatten().map { it.id }.toMutableList()
      val collIds = mutableListOf<Int>();
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
