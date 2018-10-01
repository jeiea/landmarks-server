package kr.ac.kw.coms.landmarks.server

import io.ktor.application.call
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.*
import kr.ac.kw.coms.landmarks.client.CollectionRep
import kr.ac.kw.coms.landmarks.client.PictureRep
import kr.ac.kw.coms.landmarks.client.WithIntId
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import kotlin.math.max

fun Routing.collection() = route("/collection") {

  put("/{id?}") { _ ->
    val parentId = call.parameters["id"]?.toInt()
    val sessId = requireLogin().userId
    val json: CollectionRep = call.receive()
    val collection = transaction {
      Collection.new {
        created = DateTime.now()
        title = json.title ?: ""
        description = json.text ?: ""
        isRoute = json.isRoute ?: false
        owner = EntityID(sessId, Users)
        parent = parentId?.let { EntityID(it, Collections) }
      }.toIdCollection()
    }
    call.respond(collection)
  }

  post("/{id}") { _ ->
    val userId: EntityID<Int> = EntityID(requireLogin().userId, Users)
    val colId: EntityID<Int> = EntityID(getParamId(call), Collections)
    val json: CollectionRep = call.receive()

    val collection = transaction {
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
          (CollectionLikes.liker eq userId) and (CollectionLikes.collection eq colId)
        }
        if (liking) {
          CollectionLikes.insert {
            it[CollectionLikes.liker] = userId
            it[CollectionLikes.collection] = colId
          }
        }
      }
      col.toIdCollection()
    }
    call.respond(collection)
  }

  get("/{id}/{page}") {
    requireLogin()
    val ar = mutableListOf<WithIntId<PictureRep>>()
    val collectionId = EntityID(getParamId(call), Collections)
    val page: Int = call.parameters["page"]?.toIntOrNull() ?: throw ValidException("invalid page")
    ar.addAll(transaction {
      (CollectionPics innerJoin Pictures)
        .select { CollectionPics.picture eq Pictures.id }
        .andWhere { CollectionPics.collection eq collectionId }
        .limit(30, max(page - 1, 0))
        .map(Pictures::toIdPicture)
    })
    call.respond(ar)
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
