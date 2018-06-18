package kr.ac.kw.coms.landmarks.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction

object User : Table() {
  val id = varchar("id", 20).primaryKey()
  val pass = binary("passhash", 256)
  val email = varchar("email", 50).uniqueIndex()
  val nick = varchar("name", 20).uniqueIndex()
  val nation = varchar("nation", 20)
  val verification = varchar("verKey", 20).nullable()
}

object Picture : Table() {
  val id = integer("id").autoIncrement().primaryKey()
  val file = blob("file")
  val thumbnail = blob("thumbnail")
  val owner = varchar("owner", 20).references(User.id)
  val address = varchar("address", 50).nullable()
  val latit = float("latit").nullable()
  val longi = float("longi").nullable()
  val isPublic = bool("isPublic").default(false)
}

object Quiz : Table() {
  val id = integer("id").autoIncrement().primaryKey()
  val fig = integer("pictureId").references(Picture.id)
  val body = text("body")
  val author = varchar("author", 20).references(User.id)
  val answers = text("answers")
}

object Collection : Table() {
  val id = integer("id").autoIncrement().primaryKey()
  val created = datetime("created")
  val title = varchar("title", 50)
  val isRoute = bool("isRoute").default(false)
  val owner = varchar("owner", 20).references(User.id)
  val parent = integer("parent").references(Collection.id)
}

object SubCollection : Table() {

}

object CollectionShare : Table() {
  val collection = integer("collection").references(Collection.id)
  val forker = varchar("forker", 20).references(User.id)
}


fun DBInitialize() {
  val dataSource: HikariDataSource

  val cfg = HikariConfig()
  val sqliteUrl = "jdbc:sqlite:landmarks.sqlite3"
  cfg.jdbcUrl = System.getenv("DATABASE_URL") ?: sqliteUrl
  dataSource = HikariDataSource(cfg)
  Database.connect(dataSource)

  transaction {
    create(User)
    User.insertIgnore {
      it[id] = "t"
      it[pass] = byteArrayOf()
      it[email] = ""
      it[nick] = "admin"
      it[nation] = "KR"
    }
  }
}