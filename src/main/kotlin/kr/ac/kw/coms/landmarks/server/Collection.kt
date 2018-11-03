package kr.ac.kw.coms.landmarks.server

import io.ktor.application.call
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.*
import kr.ac.kw.coms.landmarks.client.CollectionInfo
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.joda.time.DateTime

fun Routing.collection() = route("/collection") {

  get("/") { _ ->
    val uid = EntityID(requireLogin().userId, Users)
    val colls = transaction {
      Collection.find {
        (Collections.isPublic eq true) and (Collections.owner neq uid)
      }
        .map(Collection::toIdCollection)
    }
    call.respond(colls)
  }

  post("/{id?}") { _ ->
    val parentId = call.parameters["id"]?.toInt()
    val sessId = requireLogin().userId
    val json: CollectionInfo = call.receive()
    val collection = transaction {
      val coll = Collection.new {
        created = DateTime.now()
        title = json.title ?: ""
        description = json.text ?: ""
        isRoute = json.isRoute ?: false
        owner = EntityID(sessId, Users)
        isPublic = json.isPublic ?: true
        parent = parentId?.let { EntityID(it, Collections) }
      }
      json.images?.also {
        CollectionPics.batchInsert(it) { imageId ->
          this[CollectionPics.collection] = coll.id
          this[CollectionPics.picture] = EntityID(imageId, Pictures)
        }
      }
      coll.toIdCollection()
    }
    call.respond(collection)
  }

  put("/{id}") { _ ->
    val userId: EntityID<Int> = EntityID(requireLogin().userId, Users)
    val colId: EntityID<Int> = EntityID(getParamId(call), Collections)
    val json: CollectionInfo = call.receive()

    val collection = transaction {
      val col: Collection = Collection.findById(colId) ?: notFoundPage()
      json.title?.also { col.title = it }
      json.text?.also { col.description = it }
      json.isRoute?.also { col.isRoute = it }
      json.isPublic?.also { col.isPublic = it }
      json.images?.also {
        CollectionPics.deleteWhere { CollectionPics.collection eq colId }
        CollectionPics.batchInsert(it) { imageId ->
          this[CollectionPics.collection] = colId
          this[CollectionPics.picture] = EntityID(imageId, Pictures)
        }
      }
      json.liking?.also { liking ->
        CollectionLikes.deleteWhere {
          (CollectionLikes.liker eq userId) and (CollectionLikes.collection eq colId)
        }
        if (liking) {
          CollectionLikes.insert {
            it[CollectionLikes.liker] = userId
            it[CollectionLikes.collection] = colId
          }
        }
      }
      col.refresh(true)
      col.toIdCollection()
    }
    call.respond(collection)
  }

  delete("/{id}") { _ ->
    val userId: EntityID<Int> = EntityID(requireLogin().userId, Users)
    val colId: EntityID<Int> = EntityID(getParamId(call), Collections)

    transaction {
      val col: Collection = Collection.findById(colId) ?: notFoundPage()
      if (col.owner == userId) {
        col.delete()
      } else {
        errorPage("Not permitted")
      }
    }
    call.respond("")
  }

  get("/contains/picture/{id}") { _ ->
    requireLogin()
    val picId = EntityID(getParamId(call), Pictures)
    val colls = transaction {
      val matches: Query = CollectionPics
        .innerJoin(Collections)
        .select { CollectionPics.picture eq picId }
      Collection
        .wrapRows(matches)
        .notForUpdate()
        .map { it.toIdCollection() }
    }
    call.respond(colls)
  }

  get("/user/{id}") { _ ->
    requireLogin()
    val id = getParamId(call)
    val collections = transaction {
      Collection
        .find { Collections.owner eq id }
        .map(Collection::toIdCollection)
    }
    call.respond(collections)
  }
}
