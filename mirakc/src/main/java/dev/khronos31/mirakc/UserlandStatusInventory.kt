package dev.khronos31.mirakc

internal enum class BroadcastSystem { ISDB_T, ISDB_S }

internal data class UserlandReceiver(
    val id: Int,
    val systems: Set<BroadcastSystem>,
    val present: Boolean,
    val state: String
)

internal data class UserlandStatusInventory(
    val product: String,
    val instance: String?,
    val epoch: String,
    val state: String,
    val topologyState: String,
    val expectedFunctionCount: Int?,
    val receivers: List<UserlandReceiver>?
) {
    val ready: Boolean
        get() = state == "ready" && topologyState == "complete" &&
            expectedFunctionCount != null && expectedFunctionCount > 0 && receivers != null && receivers.isNotEmpty() &&
            receivers.all { it.present && it.state !in setOf("absent", "error") }

    companion object {
        fun parse(json: String, expectedProduct: String, expectedInstance: String): UserlandStatusInventory {
            val root = JsonParser(json).parse().objectValue("root")
            val schema = root.required("schema").objectValue("schema")
            require(schema.required("name").stringValue("schema.name") == "userland.status")
            require(schema.required("major").intValue("schema.major") == 1)
            require(schema.required("minor").intValue("schema.minor") >= 0)
            val producer = root.required("producer").objectValue("producer")
            val product = producer.required("product").stringValue("producer.product")
            require(producer.required("version").stringValue("producer.version").isNotBlank())
            require(product == expectedProduct)
            val context = root.required("context").objectValue("context")
            val epoch = context.required("epoch").stringValue("context.epoch")
            require(epoch.matches(Regex("[0-9a-f]{32}")) && epoch.any { it != '0' })
            val instanceValue = context.required("instance")
            val instance = if (instanceValue === JsonValue.Null) null else instanceValue.stringValue("context.instance")
            require(instance == expectedInstance)
            val state = context.required("state").stringValue("context.state")
            require(state in setOf("starting", "ready", "stopping", "error", "disconnected"))
            val scope = root.required("scope").objectValue("scope")
            require(scope.required("kind").stringValue("scope.kind") == "enclosure")
            val topology = root.required("topology").objectValue("topology")
            val topologyState = topology.required("state").stringValue("topology.state")
            require(topologyState in setOf("unknown", "partial", "complete"))
            val countValue = topology.required("expected_function_count")
            val expectedFunctionCount = if (countValue === JsonValue.Null) null else countValue.intValue("topology.expected_function_count")
            require(expectedFunctionCount == null || expectedFunctionCount >= 0)
            val receivers = when (val value = root["receivers"]) {
                null, JsonValue.Null -> null
                is JsonValue.Array -> value.values.map { parseReceiver(it) }.also { list ->
                    require(list.map { it.id }.toSet().size == list.size)
                }
                else -> throw IllegalArgumentException("receivers must be an array or null")
            }
            return UserlandStatusInventory(product, instance, epoch, state, topologyState, expectedFunctionCount, receivers)
        }

        private fun parseReceiver(value: JsonValue): UserlandReceiver {
            val receiver = value.objectValue("receiver")
            val descriptor = receiver.required("descriptor").objectValue("receiver.descriptor")
            val id = descriptor.required("receiver").intValue("receiver.descriptor.receiver")
            require(id in 0..65_535)
            val systemValues = descriptor.required("systems").arrayValue("receiver.descriptor.systems").values
            val systems = systemValues.map { item ->
                when (item.stringValue("receiver system")) {
                    "ISDB-T" -> BroadcastSystem.ISDB_T
                    "ISDB-S" -> BroadcastSystem.ISDB_S
                    else -> throw IllegalArgumentException("unknown receiver system")
                }
            }.toSet()
            require(systems.isNotEmpty() && systems.size == systemValues.size)
            val present = receiver.required("present").booleanValue("receiver.present")
            val state = receiver.required("state").stringValue("receiver.state")
            require(state in setOf("free", "leased", "tuned", "streaming", "error", "absent"))
            return UserlandReceiver(id, systems, present, state)
        }
    }
}

private sealed interface JsonValue {
    data class Object(val values: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

private fun JsonValue.objectValue(path: String) = (this as? JsonValue.Object)?.values
    ?: throw IllegalArgumentException("$path must be an object")
private fun JsonValue.arrayValue(path: String) = (this as? JsonValue.Array)
    ?: throw IllegalArgumentException("$path must be an array")
private fun JsonValue.stringValue(path: String) = (this as? JsonValue.StringValue)?.value
    ?: throw IllegalArgumentException("$path must be a string")
private fun JsonValue.booleanValue(path: String) = (this as? JsonValue.BooleanValue)?.value
    ?: throw IllegalArgumentException("$path must be a boolean")
private fun JsonValue.intValue(path: String) = (this as? JsonValue.NumberValue)?.value?.toIntOrNull()
    ?: throw IllegalArgumentException("$path must be an integer")
private fun Map<String, JsonValue>.required(key: String) = this[key]
    ?: throw IllegalArgumentException("missing $key")

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        val result = value(0)
        whitespace()
        require(index == text.length) { "trailing JSON data" }
        return result
    }

    private fun value(depth: Int): JsonValue {
        require(depth <= 64) { "JSON nesting too deep" }
        whitespace()
        require(index < text.length) { "unexpected end of JSON" }
        return when (text[index]) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> JsonValue.StringValue(string())
            't' -> literal("true", JsonValue.BooleanValue(true))
            'f' -> literal("false", JsonValue.BooleanValue(false))
            'n' -> literal("null", JsonValue.Null)
            '-', in '0'..'9' -> number()
            else -> throw IllegalArgumentException("invalid JSON value")
        }
    }

    private fun objectValue(depth: Int): JsonValue {
        index++
        whitespace()
        val fields = linkedMapOf<String, JsonValue>()
        if (take('}')) return JsonValue.Object(fields)
        while (true) {
            whitespace()
            require(index < text.length && text[index] == '"') { "invalid JSON object key" }
            val key = string()
            whitespace()
            require(take(':')) { "missing JSON colon" }
            val item = value(depth)
            require(fields.put(key, item) == null) { "duplicate JSON key" }
            whitespace()
            if (take('}')) return JsonValue.Object(fields)
            require(take(',')) { "missing JSON object comma at $index" }
        }
    }

    private fun arrayValue(depth: Int): JsonValue {
        index++
        whitespace()
        val values = mutableListOf<JsonValue>()
        if (take(']')) return JsonValue.Array(values)
        while (true) {
            values += value(depth)
            whitespace()
            if (take(']')) return JsonValue.Array(values)
            require(take(',')) { "missing JSON array comma" }
        }
    }

    private fun string(): String {
        require(take('"'))
        val result = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' -> {
                    var offset = 0
                    while (offset < result.length) {
                        val current = result[offset]
                        if (current in '\ud800'..'\udbff') {
                            require(offset + 1 < result.length && result[offset + 1] in '\udc00'..'\udfff') {
                                "unpaired JSON surrogate"
                            }
                            offset += 2
                        } else {
                            require(current !in '\udc00'..'\udfff') { "unpaired JSON surrogate" }
                            offset++
                        }
                    }
                    return result.toString()
                }
                char == '\\' -> {
                    require(index < text.length) { "unfinished JSON escape" }
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= text.length) { "short unicode escape" }
                            val code = text.substring(index, index + 4).toIntOrNull(16)
                                ?: throw IllegalArgumentException("invalid unicode escape")
                            result.append(code.toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("invalid JSON escape")
                    }
                }
                char.code < 0x20 -> throw IllegalArgumentException("control byte in JSON string")
                else -> result.append(char)
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    private fun number(): JsonValue {
        val start = index
        if (text[index] == '-') index++
        require(index < text.length)
        if (text[index] == '0') index++ else {
            require(text[index] in '1'..'9')
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] == '.') {
            index++
            require(index < text.length && text[index] in '0'..'9')
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] in "eE") {
            index++
            if (index < text.length && text[index] in "+-") index++
            require(index < text.length && text[index] in '0'..'9')
            while (index < text.length && text[index] in '0'..'9') index++
        }
        return JsonValue.NumberValue(text.substring(start, index))
    }

    private fun <T : JsonValue> literal(value: String, result: T): T {
        require(text.startsWith(value, index)) { "invalid JSON literal" }
        index += value.length
        return result
    }

    private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
    private fun take(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
}
