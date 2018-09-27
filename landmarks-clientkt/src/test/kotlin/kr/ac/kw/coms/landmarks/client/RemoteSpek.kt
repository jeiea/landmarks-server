package kr.ac.kw.coms.landmarks.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.engine.config
import io.ktor.client.features.cookies.AcceptAllCookiesStorage
import io.ktor.client.features.cookies.HttpCookies
import kotlinx.coroutines.experimental.runBlocking
import org.amshove.kluent.*
import org.apache.http.HttpHost
import org.apache.http.conn.ssl.TrustSelfSignedStrategy
import org.apache.http.ssl.SSLContextBuilder
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.*
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.*

@RunWith(JUnitPlatform::class)
class RemoteSpek : Spek({
  describe("landmarks server single user") {
    val client = Remote(getTestClient(), "http://localhost:8080")

    xblit("does reverse geocoding") {
      val res: ReverseGeocodeResult = client.reverseGeocode(37.54567, 126.9944)
      res.country!! `should be equal to` "대한민국"
      res.detail!! `should be equal to` "서울특별시"
    }

    blit("checks server health") {
      client.checkAlive().`should be true`()
    }

    blit("reset all DB") {
      client.resetAllDatabase()
    }

    val ident = getRandomString(8)
    val pass = "pasowo"
    val email = "$ident@b.c"
    blit("registers a user") {
      client.register(ident, pass, email, ident)
    }

    var profile: LoginRep? = null
    blit("does login") {
      val p = client.login(ident, "pasowo")
      p.login!! `should be equal to` ident
      p.email!! `should be equal to` email
      p.nick!! `should be equal to` ident
      profile = p
    }

    val pics: ArrayList<PictureRep> = arrayListOf()
    blit("uploads picture") {
      for (i in 0..3) {
        val gps = i.toFloat()
        val pic = client.uploadPicture(File("../data/coord$i.jpg"), gps, gps, "address$i")
        pics.add(pic)
      }
    }

    blit("receives quiz info") {
      val quizs: ArrayList<PictureRep> = arrayListOf()
      quizs.addAll(client.getRandomProblems(2))
      quizs[0].id `should not be equal to` quizs[1].id
    }

    blit("download picture") {
      client.getPicture(pics[0].id).readBytes().size `should be greater than` 0
    }

    val rep: PictureRep = pics[0].copy(address = "Manhatan?", lat = 110.0f, lon = 20.0f)
    blit("modify picture info") {
      client.modifyPicture(rep)
    }

    blit("receive picture info") {
      val modified: PictureRep = client.getPictureInfo(pics[0].id)
      modified.address!! `should be equal to` rep.address!!
      modified.lat!! `should be equal to` rep.lat!!
      modified.lon!! `should be equal to` rep.lon!!
    }

    blit("query my pictures") {
      client.getMyPictureInfos()
    }

    blit("query my collections") {
      client.getMyCollections()
    }

    blit("query a collection") {
      client.getCollections(0)
    }
  }
})

fun TestContainer.blit(description: String, body: suspend TestBody.() -> Unit) {
  it(description) {
    runBlocking { body() }
  }
}

fun TestContainer.xblit(description: String, body: suspend TestBody.() -> Unit) {
  xit(description) {
    runBlocking { body() }
  }
}

fun getTestClient(): HttpClient {
  return HttpClient(Apache.config {
    customizeClient {
      setProxy(HttpHost("localhost", 8888))
      val sslContext = SSLContextBuilder().loadTrustMaterial(null,
        TrustSelfSignedStrategy.INSTANCE).build()
      setSSLContext(sslContext)
    }
    socketTimeout = 0
    connectTimeout = 0
    connectionRequestTimeout = 0

  }) {
    install(HttpCookies) {
      storage = AcceptAllCookiesStorage()
    }
  }
}

fun getSystemProxy(): InetSocketAddress? {
  val vmoUseProxy = "java.net.useSystemProxies"
  System.setProperty(vmoUseProxy, "true")
  val proxies = ProxySelector
    .getDefault().select(URI("http://localhost"))
  for (proxy: Proxy in proxies) {
    println("proxy: $proxy")
    return proxy.address() as InetSocketAddress
  }
  return null
}

fun getRandomString(length: Long): String {
  val source = "abcdefghijklmnopqrstuvwxyz0123456789"
  return Random()
    .ints(length, 0, source.length)
    .mapToObj(source::get)
    .toArray()
    .joinToString("")
}