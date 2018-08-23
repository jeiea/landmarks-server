package kr.ac.kw.coms.landmarks.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SchemaUtils.create
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
  val isRoute = bool("isRoute").default(false)
  val owner = reference("owner", Users)
  val parent = reference("parent", Collections).nullable()
}

class User(id: EntityID<Int>): IntEntity(id){
  companion object : IntEntityClass<User>(Users)

  var login by Users.login
  var passhash by Users.passhash
  var email by Users.email
  var nick by Users.nick
  var nation by Users.nation
  var verification by Users.verification
}

class Picture(id: EntityID<Int>): IntEntity(id) {
  companion object : IntEntityClass<Picture>(Pictures)

  var filename by Pictures.filename
  var file by Pictures.file
  var thumbnail by Pictures.thumbnail
  var owner by Pictures.owner
  var address by Pictures.address
  var latit by Pictures.latit
  var longi by Pictures.longi
}

class Collection(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<Collection>(Collections)

  var created by Collections.created
  var title by Collections.title
  var isRoute by Collections.isRoute
  var owner by Collections.owner
  var parent by Collections.parent
}

fun dbInitialize() {
  val sqliteUrl = "jdbc:sqlite:landmarks.sqlite3"
  val jdbcUrl = System.getenv("DATABASE_URL") ?: sqliteUrl
  val cfg = HikariConfig().also { it.jdbcUrl = jdbcUrl}
  val dataSource = HikariDataSource(cfg)
  Database.connect(dataSource)

  // If don't do this, exception occurs with SQLite
  if (jdbcUrl == sqliteUrl)
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

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