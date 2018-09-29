package kr.ac.kw.coms.landmarks.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kr.ac.kw.coms.landmarks.client.CollectionRep
import kr.ac.kw.coms.landmarks.client.PictureRep
import kr.ac.kw.coms.landmarks.client.WithIntId
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.SchemaUtils.drop
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

object Users : IntIdTable() {
  val login = varchar("login", 20).uniqueIndex()
  val passhash = binary("passhash", 256).nullable()
  val email = varchar("email", 50).uniqueIndex()
  val nick = varchar("nick", 20).uniqueIndex()
  val nation = varchar("nation", 20)
  val verification = varchar("verKey", 20).nullable()
}

object Pictures : IntIdTable() {
  val filename = varchar("filename", 128)
  val file = blob("file")
  val thumbnail = blob("thumbnail")
  val owner = entityId("owner", Users).references(Users.id)
  val address = varchar("address", 50).nullable()
  val latit = float("latit").nullable()
  val longi = float("longi").nullable()
  val created = datetime("created")
  val public = bool("public")

  fun toIdPicture(row: ResultRow): WithIntId<PictureRep> {
    val pic = PictureRep(
      row[Pictures.owner].value,
      row[Pictures.address],
      row[Pictures.latit],
      row[Pictures.longi],
      row[Pictures.created].toDate(),
      row[Pictures.public]
    )
    return WithIntId(row[Pictures.id].value, pic)
  }
}

object Quiz : IntIdTable() {
  val figure = entityId("figure", Pictures).references(Pictures.id)
  val body = text("body")
  val author = entityId("author", Users).references(Users.id)
  val answers = text("answers")
}

object Collections : IntIdTable() {
  val created = datetime("created")
  val title = varchar("title", 50)
  val description = text("description")
  val isRoute = bool("isRoute").default(false)
  val owner = reference("owner", Users)
  val parent = reference("parent", Collections).nullable()
}

object CollectionPics : Table() {
  val collection = reference("collection", Collections)
  val picture = reference("picture", Pictures)
}

object CollectionLikes : Table() {
  val collection = reference("collection", Collections)
  val liker = reference("liker", Users)
}

class User(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<User>(Users)

  var login by Users.login
  var passhash by Users.passhash
  var email by Users.email
  var nick by Users.nick
  var nation by Users.nation
  var verification by Users.verification
}

class Picture(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<Picture>(Pictures)

  var filename by Pictures.filename
  var file by Pictures.file
  var thumbnail by Pictures.thumbnail
  var owner by Pictures.owner
  var address by Pictures.address
  var latit by Pictures.latit
  var longi by Pictures.longi
  var created by Pictures.created
  var public by Pictures.public

  fun toIdPicture(): WithIntId<PictureRep> {
    val pic = PictureRep(
      owner.value, address,
      latit, longi, created.toDate(), public
    )
    return WithIntId(id.value, pic)
  }
}

class Collection(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<Collection>(Collections)

  var created by Collections.created
  var title by Collections.title
  var description by Collections.description
  var isRoute by Collections.isRoute
  var owner by Collections.owner
  var parent by Collections.parent

  fun toIdCollection(): WithIntId<CollectionRep> {
    val pics = CollectionPics
      .select { CollectionPics.collection eq id }
      .adjustSlice { slice(CollectionPics.picture) }
      .map { row -> row[CollectionPics.picture].value }
    val likes = CollectionLikes.select { CollectionLikes.liker eq id }
    val likeNum = likes.count()
    val liking = likes.any { row -> row[CollectionLikes.liker] == id }
    val collection = CollectionRep(
      title, description, pics, likeNum, liking,
      isRoute, parent?.value)
    return WithIntId(id.value, collection)
  }
}

fun dbInitialize() {
  val sqliteUrl = "jdbc:sqlite:landmarks.sqlite3"
  val jdbcUrl = System.getenv("DATABASE_URL") ?: sqliteUrl
  val cfg = HikariConfig().also { it.jdbcUrl = jdbcUrl }
  val dataSource = HikariDataSource(cfg)
  Database.connect(dataSource)

  // If don't do this, exception occurs with SQLite
  if (jdbcUrl == sqliteUrl)
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

  createTables()
}

fun createTables() {
  transaction {
    create(Users)
    create(Pictures)

    if (User.find { Users.nick eq "admin" }.count() < 1) {
      User.new {
        login = "t"
        passhash = byteArrayOf()
        email = ""
        nick = "admin"
        nation = "KR"
      }
    }
  }
}

fun dropTables() {
  transaction {
    drop(Users)
    drop(Pictures)
  }
}

fun resetTables() {
  dropTables()
  createTables()
}
