package dev.khronos31.mirakc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class EpgService(
    val networkId: Int,
    val serviceId: Int,
    val name: String,
    val type: Int,
    val channel: String,
    val channelType: String,
    val remoteControlKeyId: Int?,
    @Volatile var epgUpdatedAt: Long = 0L
) {
    val id: Long get() = networkId * 100000L + serviceId
}

internal data class EpgProgram(
    val networkId: Int,
    val serviceId: Int,
    val eventId: Int,
    val startAt: Long,
    val duration: Long,
    val isFree: Boolean,
    val name: String?,
    val description: String?,
    val genres: List<IntArray>
) {
    val id: Long get() = networkId * 10_000_000_000L + serviceId * 100000L + eventId
}

internal class EpgStore {
    private val services = ConcurrentHashMap<Long, EpgService>()
    private val programs = ConcurrentHashMap<Long, EpgProgram>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, String) -> Unit>()
    private val lastProgramEvent = ConcurrentHashMap<Long, Long>()
    private val lastOnAir = ConcurrentHashMap<Long, Long>()
    @Volatile private var suppressEvents = false

    fun addListener(listener: (String, String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String, String) -> Unit) {
        listeners -= listener
    }

    fun upsertService(service: EpgService) {
        val previous = services.put(service.id, service)
        if (!suppressEvents && (previous?.name != service.name || previous?.channel != service.channel)) {
            emit("epg.programs-updated", "{\"serviceId\":${service.id}}")
        }
    }

    fun upsertProgram(program: EpgProgram) {
        val previous = programs.put(program.id, program)
        if (!suppressEvents && (previous == null || previous.name != program.name || previous.startAt != program.startAt ||
                previous.duration != program.duration)
        ) {
            val serviceId = program.networkId * 100000L + program.serviceId
            services[serviceId]?.epgUpdatedAt = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            val previousEmit = lastProgramEvent[serviceId] ?: 0L
            if (now - previousEmit >= 8_000L) {
                lastProgramEvent[serviceId] = now
                emit("epg.programs-updated", "{\"serviceId\":$serviceId}")
            }
        }
    }

    fun markOnAir(networkId: Int, serviceId: Int) {
        if (suppressEvents) return
        val id = networkId * 100000L + serviceId
        val now = System.currentTimeMillis()
        val previous = lastOnAir[id] ?: 0L
        if (now - previous < 8_000L) return
        lastOnAir[id] = now
        emit("onair.program-changed", "{\"serviceId\":$id}")
    }

    fun services(): List<EpgService> = services.values.sortedBy { it.id }

    fun programs(): List<EpgProgram> = programs.values.sortedBy { it.startAt }

    fun service(id: Long): EpgService? = services[id]

    fun program(id: Long): EpgProgram? = programs[id]

    fun programsForService(id: Long): List<EpgProgram> {
        val service = services[id] ?: return emptyList()
        return programs.values.filter { it.networkId == service.networkId && it.serviceId == service.serviceId }
            .sortedBy { it.startAt }
    }

    fun pruneExpired(now: Long = System.currentTimeMillis()) {
        programs.entries.removeIf { it.value.startAt + it.value.duration < now - 3_600_000L }
    }

    fun counts(): Pair<Int, Int> = services.size to programs.size

    fun save(file: File) {
        val root = JSONObject()
        val serviceArray = JSONArray()
        for (service in services()) {
            serviceArray.put(
                JSONObject()
                    .put("nid", service.networkId)
                    .put("sid", service.serviceId)
                    .put("name", service.name)
                    .put("type", service.type)
                    .put("channel", service.channel)
                    .put("channelType", service.channelType)
                    .put("remoteControlKeyId", service.remoteControlKeyId ?: JSONObject.NULL)
                    .put("epgUpdatedAt", service.epgUpdatedAt)
            )
        }
        val programArray = JSONArray()
        for (program in programs()) {
            val genres = JSONArray()
            program.genres.forEach { genre ->
                genres.put(JSONArray().put(genre[0]).put(genre[1]).put(genre[2]).put(genre[3]))
            }
            programArray.put(
                JSONObject()
                    .put("nid", program.networkId)
                    .put("sid", program.serviceId)
                    .put("eid", program.eventId)
                    .put("startAt", program.startAt)
                    .put("duration", program.duration)
                    .put("isFree", program.isFree)
                    .put("name", program.name ?: JSONObject.NULL)
                    .put("description", program.description ?: JSONObject.NULL)
                    .put("genres", genres)
            )
        }
        root.put("services", serviceArray)
        root.put("programs", programArray)
        file.writeText(root.toString())
    }

    fun load(file: File) {
        if (!file.isFile) return
        suppressEvents = true
        try {
        val root = JSONObject(file.readText())
        val serviceArray = root.optJSONArray("services") ?: JSONArray()
        for (index in 0 until serviceArray.length()) {
            val obj = serviceArray.getJSONObject(index)
            val remote = if (obj.isNull("remoteControlKeyId")) null else obj.optInt("remoteControlKeyId")
            upsertService(
                EpgService(
                    networkId = obj.getInt("nid"),
                    serviceId = obj.getInt("sid"),
                    name = obj.getString("name"),
                    type = obj.optInt("type", 1),
                    channel = obj.getString("channel"),
                    channelType = obj.optString("channelType", "GR"),
                    remoteControlKeyId = remote,
                    epgUpdatedAt = obj.optLong("epgUpdatedAt")
                )
            )
        }
        val programArray = root.optJSONArray("programs") ?: JSONArray()
        for (index in 0 until programArray.length()) {
            val obj = programArray.getJSONObject(index)
            val genres = mutableListOf<IntArray>()
            val raw = obj.optJSONArray("genres")
            if (raw != null) {
                for (g in 0 until raw.length()) {
                    val row = raw.getJSONArray(g)
                    genres += intArrayOf(row.optInt(0), row.optInt(1), row.optInt(2, 15), row.optInt(3, 15))
                }
            }
            upsertProgram(
                EpgProgram(
                    networkId = obj.getInt("nid"),
                    serviceId = obj.getInt("sid"),
                    eventId = obj.getInt("eid"),
                    startAt = obj.getLong("startAt"),
                    duration = obj.getLong("duration"),
                    isFree = obj.optBoolean("isFree", true),
                    name = if (obj.isNull("name")) null else obj.optString("name"),
                    description = if (obj.isNull("description")) null else obj.optString("description"),
                    genres = genres
                )
            )
        }
        } finally {
            suppressEvents = false
        }
    }

    private fun emit(event: String, data: String) {
        listeners.forEach { listener ->
            try {
                listener(event, data)
            } catch (_: Exception) {
            }
        }
    }
}
