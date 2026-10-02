package dev.offgridmesh.data

import dev.offgridmesh.sync.MemberState
import dev.offgridmesh.sync.ObjectType
import dev.offgridmesh.sync.StatusPayload
import dev.offgridmesh.sync.WireCodec
import kotlinx.coroutines.flow.Flow

fun StatusPayload.toEntity() = MemberStatusEntity(deviceId, displayName, state, updatedAtMs, note, lat, lng)

/** This phone's own status (§7.6). NEED_HELP is sticky: only an explicit "I'm OK" clears it. */
class StatusRepository(private val db: MeshDatabase, private val w: LocalWriter) {
    fun observeAll(): Flow<List<MemberStatusEntity>> = db.statuses().observeAll()

    suspend fun set(state: MemberState, note: String? = null, lat: Double? = null, lng: Double? = null) {
        val me = w.me()
        val old = db.statuses().get(me.deviceId)
        val s = StatusPayload(
            deviceId = me.deviceId,
            displayName = me.displayName,
            state = state,
            updatedAtMs = w.nextVersion(old?.updatedAtMs),
            note = note?.trim()?.take(StatusPayload.MAX_NOTE)?.ifEmpty { null },
            lat = lat,
            lng = lng,
        )
        w.publish(ObjectType.STATUS, StatusPayload.objectId(me.deviceId), s.updatedAtMs, WireCodec.encodePayload(s)) {
            db.statuses().upsert(s.toEntity())
        }
    }
}
