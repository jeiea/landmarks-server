package kr.ac.kw.coms.landmarks.server

import io.ktor.application.call
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.*
import kr.ac.kw.coms.landmarks.client.CollectionRep
import kr.ac.kw.coms.landmarks.client.ServerOK
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

fun Routing.collection() = route("/collection") {

  put("/{id?}") {
    val parentId = call.parameters["id"]?.toInt()
    val sessId = requireLogin().userId
    val json: CollectionRep = call.receive()
    val id = transaction {
      Collection.new {
        created = DateTime.now()
        title = json.title ?: ""
        isRoute = json.isRoute ?: false
        owner = EntityID(sessId, Users)
        parent = EntityID(parentId, Collections)
      }.id.value
    }
    call.respond(id)
  }

  post("/{id}") { _ ->
    val userId: EntityID<Int> = EntityID(requireLogin().userId, Users)
    val colId: EntityID<Int> = EntityID(getParamId(call), Collections)
    val json: CollectionRep = call.receive()

    transaction {
      val col: Collection = Collection.findById(colId) ?: notFoundPage()
      json.title?.also { col.title = it }
      json.text?.also { col.description = it }
      json.isRoute?.also { col.isRoute = it }
      json.images?.also {
        CollectionPics.deleteWhere { CollectionPics.collection eq colId }
        CollectionPics.batchInsert(it) { imageId ->
          this[CollectionPics.collection] = colId
          this[CollectionPics.picture] = EntityID(imageId, Pictures)
        }
      }
      json.liking?.also { liking ->
        CollectionLikes.deleteWhere {
          (CollectionLikes.liker eq userId) and(CollectionLikes.collection eq colId)
        }
        if (liking) {
          CollectionLikes.insert {
            it[CollectionLikes.liker] = userId
            it[CollectionLikes.collection] = colId
          }
        }
      }
      Unit
    }
    call.respond(ServerOK("update success"))
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
