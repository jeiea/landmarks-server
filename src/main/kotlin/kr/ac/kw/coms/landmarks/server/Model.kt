package kr.ac.kw.coms.landmarks.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

object User : Table() {
  val id = integer("id").autoIncrement().primaryKey()
  val login = varchar("login", 20).uniqueIndex()
  val passhash = binary("passhash", 256).nullable()
  val email = varchar("email", 50).uniqueIndex()
  val nick = varchar("nick", 20).uniqueIndex()
  val nation = varchar("nation", 20)
  val verification = varchar("verKey", 20).nullable()
}

object Picture : Table() {
  val id = integer("id").autoIncrement().primaryKey()
  val file = blob("file")
  val thumbnail = blob("thumbnail")
  val owner = integer("owner").references(User.id)
  val address = varchar("address", 50).nullable()
  val latit = float("latit").nullable()
  val longi = float("longi").nullable()
  val isPublic = bool("isPublic").default(false)
}

object Quiz : Table() {
  val id = integer("id").autoIncrement().primaryKey()
  val fig = integer("pictureId").references(Picture.id)
  val body = text("body")
  val author = integer("author").references(User.id)
  val answers = text("answers")
}

object Collection : Table() {
  val id = integer("id").autoIncrement().primaryKey()
  val created = datetime("created")
  val title = varchar("title", 50)
  val isRoute = bool("isRoute").default(false)
  val owner = integer("owner").references(User.id)
  val parent = integer("parent").references(Collection.id)
}

object SubCollection : Table() {

}

object CollectionShare : Table() {
  val collection = integer("collection").references(Collection.id)
  val forker = Quiz.integer("forker").references(User.id)
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
    create(User)
    if (User.select { User.nick eq "admin" }.count() < 1) {
      User.insert {
        it[login] = "t"
        it[passhash] = byteArrayOf()
        it[email] = ""
        it[nick] = "admin"
        it[nation] = "KR"
      }
    }
  }
}