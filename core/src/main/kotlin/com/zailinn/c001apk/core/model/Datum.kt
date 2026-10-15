package com.zailinn.c001apk.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One server entity. Coolapk mixes strings and numbers for the same field across
 * endpoints, so values are read leniently from the original JSON instead of being
 * forced into a rigid schema that fails on the first unexpected type.
 */
class Datum(val json: JsonObject) {
    fun string(key: String): String? = when (val value = json[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content
        else -> null
    }

    fun text(key: String): String = string(key).orEmpty()

    fun int(key: String): Int? = string(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    fun long(key: String): Long? = string(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    fun obj(key: String): Datum? = (json[key] as? JsonObject)?.let(::Datum)

    fun list(key: String): List<Datum> =
        (json[key] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::Datum) }.orEmpty()

    fun strings(key: String): List<String> =
        (json[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.filter { it.isNotEmpty() }.orEmpty()

    fun has(key: String): Boolean = json[key].let { it != null && it != JsonNull }

    fun with(key: String, value: JsonElement?): Datum =
        Datum(JsonObject(json.toMutableMap().apply { if (value == null) remove(key) else put(key, value) }))

    fun with(key: String, value: String?): Datum = with(key, value?.let(::JsonPrimitive))
    fun with(key: String, value: Number?): Datum = with(key, value?.let(::JsonPrimitive))
    fun withList(key: String, items: List<Datum>?): Datum = with(key, items?.let { list -> JsonArray(list.map { it.json }) })

    val id: String? get() = string("id")
    val entityId: String? get() = string("entityId")
    val entityType: String? get() = string("entityType")
    val entityTemplate: String? get() = string("entityTemplate")
    val title: String? get() = string("title")
    val url: String? get() = string("url")
    val pic: String? get() = string("pic")
    val logo: String? get() = string("logo")
    val uid: String? get() = string("uid")
    val username: String? get() = string("username")
    val userAvatar: String? get() = string("userAvatar")
    val message: String? get() = string("message")
    val messageTitle: String? get() = string("message_title")
    val dateline: Long? get() = long("dateline")
    val description: String? get() = string("description")
    val picArr: List<String> get() = strings("picArr")
    val entities: List<Datum> get() = list("entities")
    val userInfo: Datum? get() = obj("userInfo")
    val userAction: Datum? get() = obj("userAction")
    val replyRows: List<Datum> get() = list("replyRows")
    val relationRows: List<Datum> get() = list("relationRows")
    val targetRow: Datum? get() = obj("targetRow")
    val isLiked: Boolean get() = userAction?.int("like") == 1

    /** Display name for feeds/replies: some endpoints only fill userInfo. */
    val displayName: String get() = userInfo?.username ?: username.orEmpty()

    override fun equals(other: Any?): Boolean = other is Datum && other.json == json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = "Datum(${entityType}:${id})"
}
