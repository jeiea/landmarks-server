package kr.ac.kw.coms.landmarks.server

import ch.qos.logback.classic.Level
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.ac.kw.coms.landmarks.client.CollectionInfo
import kr.ac.kw.coms.landmarks.client.IdCollectionInfo
import kr.ac.kw.coms.landmarks.client.IdPictureInfo
import kr.ac.kw.coms.landmarks.client.PictureInfo
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.SchemaUtils.drop
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.Period
import java.sql.Connection

open class UserTable : IntIdTable() {
  val login = varchar("login", 20).uniqueIndex()
  val passhash = binary("passhash", 256).nullable()
  val email = varchar("email", 50).uniqueIndex()
  val nick = varchar("nick", 20).uniqueIndex()
  val nation = varchar("nation", 20)
  val verification = varchar("verKey", 20).nullable()
  val registered = datetime("registered")
}

object Users : UserTable()

object Pictures : IntIdTable() {
  val hash = binary("hash", 32)
  val file = blob("file")
  val width = integer("width")
  val height = integer("height")
  val thumbnail1 = blob("thumbnail1")
  val thumbnail2 = blob("thumbnail2")
  val thumbnail3 = blob("thumbnail3")
  val thumbnail4 = blob("thumbnail4")
  val owner = entityId("owner", Users).references(Users.id)
  val address = varchar("address", 50).nullable()
  val latit = double("latit").nullable()
  val longi = double("longi").nullable()
  val created = datetime("created")
  val isPublic = bool("public")
}

object Collections : IntIdTable() {
  val created = datetime("created")
  val title = varchar("title", 50)
  val description = text("description")
  val isRoute = bool("isRoute").default(false)
  val owner = reference("owner", Users)
  val isPublic = bool("public")
  val parent = reference("parent", Collections).nullable()
}

object CollectionPics : IntIdTable() {
  val collection = reference("collection", Collections, ReferenceOption.CASCADE)
  val picture = reference("picture", Pictures, ReferenceOption.CASCADE)
}

object CollectionLikes : IntIdTable() {
  val collection = reference("collection", Collections, ReferenceOption.CASCADE)
  val liker = reference("liker", Users, ReferenceOption.CASCADE)
}

class User(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<User>(Users)

  var login by Users.login
  var passhash by Users.passhash
  var email by Users.email
  var nick by Users.nick
  var nation by Users.nation
  var verification by Users.verification
  var registered by Users.registered
}

class Picture(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<Picture>(Pictures)

  var hash by Pictures.hash
  var file by Pictures.file
  var width by Pictures.width
  var height by Pictures.height
  var thumbnail1 by Pictures.thumbnail1
  var thumbnail2 by Pictures.thumbnail2
  var thumbnail3 by Pictures.thumbnail3
  var thumbnail4 by Pictures.thumbnail4
  var address by Pictures.address
  var owner by Pictures.owner
  var latit by Pictures.latit
  var longi by Pictures.longi
  var created by Pictures.created
  var public by Pictures.isPublic

  var author by User referencedOn Pictures.owner

  fun toIdPicture(): IdPictureInfo {
    val pic = PictureInfo(
      owner.value, author.nick, width, height, address,
      latit, longi, created.toDate(), public
    )
    return IdPictureInfo(id.value, pic)
  }
}

class Collection(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<Collection>(Collections)

  var created by Collections.created
  var title by Collections.title
  var description by Collections.description
  var isRoute by Collections.isRoute
  var parent by Collections.parent
  var owner by Collections.owner
  var isPublic by Collections.isPublic

  var author by User referencedOn Collections.owner
  val pics by CollectionPic referrersOn CollectionPics.collection

  fun toIdCollection(): IdCollectionInfo {
    val likes = CollectionLikes.select { CollectionLikes.liker eq id }
    val likeNum = likes.count()
    val liking = likes.any { row -> row[CollectionLikes.liker] == id }
    val collection = CollectionInfo(
      title, description,
      ArrayList(pics.map { it.picture.id.value }),
      ArrayList(pics.map { it.picture.toIdPicture() }),
      likeNum, liking, isRoute, isPublic, parent?.value
    )
    return IdCollectionInfo(id.value, collection)
  }
}

class CollectionPic(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<CollectionPic>(CollectionPics)

  var picture by Picture referencedOn CollectionPics.picture
}

val landmarksDb by lazy { dbInitialize() }

private fun dbInitialize(): Database {
  val cfg = HikariConfig()
  val pgJdbcUrl = System.getenv("JDBC_DATABASE_URL")
  if (pgJdbcUrl != null) {
    cfg.driverClassName = "org.postgresql.Driver"
    cfg.jdbcUrl = pgJdbcUrl
  }
  else {
    cfg.jdbcUrl = "jdbc:sqlite:landmarks.sqlite3"
  }

  setLoggingLevel(Level.WARN, "com.zaxxer.hikari")
  val dataSource = HikariDataSource(cfg)
  val db: Database = Database.connect(dataSource)

  // If don't do this, exception occurs with SQLite
  if (pgJdbcUrl == null) {
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
  }

  createTables()
  return db
}

fun <T> transaction(db: Database? = null, statement: Transaction.() -> T): T {
  val beg = DateTime.now()
  val ret = transaction(TransactionManager.manager.defaultIsolationLevel, 3, db, statement)
  val dur = Period(DateTime.now().millis - beg.millis)
  val period = periodMinFormat.print(dur)
  exposedLogger.info("transaction taken $period")
  return ret
}

fun setLoggingLevel(level: ch.qos.logback.classic.Level, target: String) {
  val root = org.slf4j.LoggerFactory.getLogger(target) as ch.qos.logback.classic.Logger
  root.level = level
}

private val tables = arrayOf(Users, Pictures, Collections, CollectionPics, CollectionLikes)

fun createTables() {
  transaction {
    create(*tables)

    if (User.find { Users.nick eq "admin" }.count() < 1) {
      User.new {
        login = "t"
        passhash = byteArrayOf()
        email = ""
        nick = "admin"
        nation = "KR"
        registered = DateTime.now()
      }
    }
  }
}

fun dropTables() {
  transaction {
    drop(*tables.reversedArray())
  }
}

fun resetTables() {
  dropTables()
  createTables()
}

val isSqlite get() = landmarksDb.url.contains("sqlite")

fun pushTables() {
  transaction {
    val tables = getTables()
    val top = findBackupTop(tables)
    tables.forEach {
      copyTable(it, "${it}_$top")
    }
  }
}

fun popTables() {
  transaction {
    val tables = getTables()
    val topb = findBackupTop(tables) - 1
    if (topb == 0) {
      return@transaction
    }
    dropTables()
    tables.forEach {
      val backup = "${it}_$topb"
      copyTable(backup, it)
      drop(Table(backup))
    }
  }
}

private fun findBackupTop(tables: MutableList<String>): Int {
  for (i in 1..Int.MAX_VALUE) {
    if (tables.none { it.endsWith("_$i") }) {
      return i
    }
  }
  return 0
}

private fun Transaction.copyTable(src: String, dst: String) {
  exec("CREATE TABLE '$src' AS SELECT * FROM '$dst'")
}

private fun Transaction.getTables(): MutableList<String> {
  val sqliteQuery = "SELECT name FROM sqlite_master WHERE type='table'"
  val postgreQuery = "SELECT table_name FROM information_schema.tables WHERE table_schema='public';"
  val query = if (isSqlite) sqliteQuery else postgreQuery
  return execStringColumn(query)
}

private fun Transaction.execStringColumn(query: String): MutableList<String> {
  val fields = mutableListOf<String>()
  exec(query) { cursor ->
    while (cursor.next()) {
      fields.add(cursor.getString(0))
    }
  }
  return fields
}
