/**
 * @author FangYan
 * ctime: 2024.10.21
 */
package moe.ono.hooks.protocol

import android.util.Log
import moe.ono.service.QQInterfaces
import com.google.protobuf.CodedOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import moe.ono.BuildConfig
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.random.nextUInt

/**
 * Sends a message by constructing a JSON payload, encoding it to Protobuf, and sending it via QQInterfaces.
 *
 * @param content The content of the message.
 * @param id The identifier for the message.
 * @param isGroupMsg Indicates if the message is a group message.
 * @param type The type of the message, e.g., "element".
 */
@OptIn(ExperimentalSerializationApi::class)
fun sendMessage(content: String, id: String, isGroupMsg: Boolean, type: String) {
    val TAG = BuildConfig.TAG
    val json = Json { ignoreUnknownKeys = true }

    try {
        var basePbContent = buildBasePbContent(id, isGroupMsg)

        when (type) {
            "element" -> {
                Log.d("$TAG!pbcontent", content)
                val jsonElement = json.decodeFromString<JsonElement>(content)
                val updatedElements = when (jsonElement) {
                    is JsonArray -> jsonElement.filterIsInstance<JsonObject>()
                    is JsonObject -> listOf(jsonElement)
                    else -> {
                        Log.e("$TAG!err", "Invalid JSON!")
                        return
                    }
                }

                val jsonArray = buildJsonArray {
                    updatedElements.forEach { element ->
                        add(element)
                        Log.d("$TAG!elem:", element.toString())
                    }
                }

                basePbContent = buildJsonObject {
                    basePbContent.forEach { (key, value) ->
                        when (key) {
                            "3" -> {
                                // Navigate to ["3"]["1"]
                                val path1 = value.jsonObject["1"]?.jsonObject?.toMutableMap() ?: mutableMapOf<String, JsonElement>()
                                // Set ["3"]["1"]["2"] to the new JsonArray
                                path1["2"] = jsonArray
                                // Put the modified nested object back
                                put("3", buildJsonObject {
                                    put("1", JsonObject(path1))
                                })
                            }
                            else -> {
                                put(key, value)
                            }
                        }
                    }
                }
            }
            else -> {
                throw IllegalArgumentException("Unsupported content type '$type'")
            }
        }

        basePbContent = buildJsonObject {
            basePbContent.forEach { (key, value) ->
                put(key, value)
            }
            put("4", JsonPrimitive(Random.nextUInt()))
            put("5", JsonPrimitive(Random.nextUInt()))
        }

        val basePbContentString = json.encodeToString(basePbContent)
        Log.d("$TAG!pbcontent", "basePbContent = $basePbContentString")
        val parsedJsonElement: JsonElement = basePbContent
        val map = parseJsonToMap(parsedJsonElement)
        Log.d("$TAG!map", "Parsed JSON to Map: $map")
        val byteArray = encodeMessage(map)

        QQInterfaces.sendBuffer("MessageSvc.PbSendMsg", true, byteArray)
    } catch (e: Exception) {
        Log.e("$TAG!err", "sendMessage failed: ${e.message}", e)
    }
}

/**
 * Builds the base JSON content based on whether the message is a group message.
 *
 * @param id The identifier for the message.
 * @param isGroupMsg Indicates if the message is a group message.
 * @return The base JsonObject.
 */
fun buildBasePbContent(id: String, isGroupMsg: Boolean): JsonObject = buildJsonObject {
    putJsonObject("1") {
        if (isGroupMsg) {
            val idLong = id.toLongOrNull() ?: throw IllegalArgumentException("id must be Long for group messages")
            putJsonObject("2") {
                put("1", JsonPrimitive(idLong))
            }
        } else {
            putJsonObject("1") {
                put("2", JsonPrimitive(id))
            }
        }
    }
    putJsonObject("2") {
        put("1", JsonPrimitive(1))
        put("2", JsonPrimitive(0))
        put("3", JsonPrimitive(0))
    }
    putJsonObject("3") {
        putJsonObject("1") {
            put("2", buildJsonArray { /* Initialize as needed */ })
        }
    }
}

/**
 * Encodes a map into a Protobuf byte array.
 *
 * @param obj The map to encode.
 * @return The encoded byte array.
 */
fun encodeMessage(obj: Map<Int, Any>): ByteArray {
    ByteArrayOutputStream().use { baos ->
        val output = CodedOutputStream.newInstance(baos)
        encodeMapToProtobuf(output, obj)
        output.flush()
        return baos.toByteArray()
    }
}

/**
 * Recursively encodes a map into Protobuf format.
 *
 * @param output The CodedOutputStream to write to.
 * @param obj The map to encode.
 */
fun encodeMapToProtobuf(output: CodedOutputStream, obj: Map<Int, Any>) {
    obj.forEach { (tag, value) ->
        when (value) {
            is Int -> output.writeInt32(tag, value)
            is Long -> output.writeInt64(tag, value)
            is String -> output.writeString(tag, value)
            is ByteArray -> {
                output.writeTag(tag, com.google.protobuf.WireFormat.WIRETYPE_LENGTH_DELIMITED)
                output.writeUInt32NoTag(value.size)
                output.writeRawBytes(value)
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val nestedMessage = encodeMessage(value as Map<Int, Any>)
                output.writeTag(tag, com.google.protobuf.WireFormat.WIRETYPE_LENGTH_DELIMITED)
                output.writeUInt32NoTag(nestedMessage.size)
                output.writeRawBytes(nestedMessage)
            }
            is List<*> -> {
                value.forEach { item ->
                    if (item is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val nestedMessage = encodeMessage(item as Map<Int, Any>)
                        output.writeTag(tag, com.google.protobuf.WireFormat.WIRETYPE_LENGTH_DELIMITED)
                        output.writeUInt32NoTag(nestedMessage.size)
                        output.writeRawBytes(nestedMessage)
                    } else {
                        throw IllegalArgumentException("Unsupported list item type: ${item?.javaClass}")
                    }
                }
            }
            else -> {
                throw IllegalArgumentException("Unsupported type: ${value.javaClass}")
            }
        }
    }
}

/**
 * Parses a JsonElement into a Map<Int, Any>.
 * Converts hex strings to ByteArray when the string starts with "hex->" or when at a specific JSON path.
 *
 * @param jsonElement The JsonElement to parse.
 * @param path The current path in the JSON structure.
 * @return The resulting map.
 */
fun parseJsonToMap(jsonElement: JsonElement, path: List<String> = emptyList()): Map<Int, Any> {
    val resultMap = mutableMapOf<Int, Any>()
    when (jsonElement) {
        is JsonObject -> {
            for ((key, value) in jsonElement) {
                val intKey = key.toIntOrNull()
                if (intKey != null) {
                    val currentPath = path + key
                    Log.d("${BuildConfig.TAG}!parse", "Current path: $currentPath")
                    // 示例：当路径为 ["3", "1", "2"] 时，将 key 映射为 2；否则使用原来的 key
                    val mappedKey = if (currentPath == listOf("3", "1", "2")) 2 else intKey
                    when (value) {
                        is JsonObject -> {
                            resultMap[mappedKey] = parseJsonToMap(value, currentPath)
                        }
                        is JsonArray -> {
                            val list = value.map { parseJsonToMap(it, currentPath) }
                            resultMap[mappedKey] = list
                        }
                        is JsonPrimitive -> {
                            val content = value.content
                            if (value.isString) {
                                if (content.startsWith("hex->")) {
                                    val hexStr = content.removePrefix("hex->")
                                    if (isHexString(hexStr)) {
                                        Log.d("${BuildConfig.TAG}!hexConversion", "Converting hex string at path $currentPath")
                                        resultMap[mappedKey] = hexStringToByteArray(hexStr)
                                    } else {
                                        resultMap[mappedKey] = content
                                    }
                                }
                                else if (currentPath.takeLast(2) == listOf("5", "2") && isHexString(content)) {
                                    Log.d("${BuildConfig.TAG}!hexConversion", "Converting hex string at path $currentPath")
                                    resultMap[mappedKey] = hexStringToByteArray(content)
                                } else {
                                    resultMap[mappedKey] = content
                                }
                            } else if (value.intOrNull != null) {
                                resultMap[mappedKey] = value.int
                            } else if (value.longOrNull != null) {
                                resultMap[mappedKey] = value.long
                            } else {
                                resultMap[mappedKey] = content
                            }
                        }
                        else -> throw IllegalArgumentException("Unsupported JSON element: $value")
                    }
                } else {
                    throw IllegalArgumentException("Key is not a valid integer: $key")
                }
            }
        }
        is JsonArray -> {
            jsonElement.forEachIndexed { index, element ->
                val parsedMap = parseJsonToMap(element, path + (index + 1).toString())
                resultMap[index + 1] = parsedMap
            }
        }
        else -> throw IllegalArgumentException("Unsupported JSON element: $jsonElement")
    }
    return resultMap
}

/**
 * Checks if a string is a valid hex string.
 *
 * @param s The string to check.
 * @return True if the string is a hex string, false otherwise.
 */
fun isHexString(s: String): Boolean {
    val regex = Regex("^[0-9a-fA-F]+$")
    return s.length % 2 == 0 && regex.matches(s)
}

/**
 * Converts a hex string to a ByteArray.
 *
 * @param s The hex string.
 * @return The resulting ByteArray.
 */
fun hexStringToByteArray(s: String): ByteArray {
    val len = s.length
    val data = ByteArray(len / 2)
    var i = 0
    try {
        while (i < len) {
            val high = Character.digit(s[i], 16)
            val low = Character.digit(s[i + 1], 16)
            if (high == -1 || low == -1) {
                throw IllegalArgumentException("Invalid hex character at position $i")
            }
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
    } catch (e: Exception) {
        Log.e("${BuildConfig.TAG}!hexError", "Error converting hex string to ByteArray: ${e.message}")
        throw e
    }
    return data
}
