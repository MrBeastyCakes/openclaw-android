package com.openclaw.androidclient.data.model

import android.os.Build
import com.openclaw.androidclient.data.auth.signDevicePayload
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ConnectionConfig(
    val gatewayUrl: String,
    val gatewayToken: String,
    val sessionKey: String,
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val isStreaming: Boolean = false,
)

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Authenticating,
    Connected,
    Error,
}

data class ChatUiState(
    val gatewayUrl: String = DEFAULT_GATEWAY_URL,
    val token: String = "",
    val sessionKey: String = DEFAULT_SESSION_KEY,
    val draftMessage: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val statusMessage: String = "Ready",
    val isConnecting: Boolean = false,
    val isSending: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val currentRunId: String? = null,
)

sealed interface GatewayEvent {
    data class Challenge(val nonce: String) : GatewayEvent
    data class Chat(val payload: JsonObject) : GatewayEvent
    data class SessionTool(val payload: JsonObject) : GatewayEvent
    data class Unknown(val name: String, val payload: JsonElement?) : GatewayEvent
}

data class GatewayResponse(
    val ok: Boolean,
    val payload: JsonElement?,
    val errorCode: String?,
    val errorMessage: String?,
    val errorDetails: JsonElement?,
)

sealed interface SendMessageResult {
    data object Success : SendMessageResult
    data class Failure(val message: String) : SendMessageResult
}

data class DeviceIdentity(
    val deviceId: String,
    val publicKeyBase64Url: String,
    val privateKeyPkcs8Base64: String,
)

data class StoredDeviceAuth(
    val token: String,
    val scopes: List<String>,
)

data class ConnectAuthBundle(
    val authToken: String,
    val deviceToken: String?,
    val requestedScopes: List<String>,
)

const val DEFAULT_GATEWAY_URL = "ws://10.0.2.2:18789"
const val DEFAULT_SESSION_KEY = "agent:main:main"
const val ROLE_OPERATOR = "operator"
private const val DEFAULT_CLIENT_ID = "openclaw-android"
private const val DEFAULT_CLIENT_NAME = "OpenClaw Android"
private const val DEFAULT_CLIENT_VERSION = "0.1.0"
private const val DEFAULT_CLIENT_MODE = "ui"
val DEFAULT_REQUESTED_SCOPES: List<String> = normalizeDeviceScopes(listOf("operator.admin"))

fun normalizeDeviceScopes(scopes: List<String>): List<String> {
    val values = linkedSetOf<String>()
    scopes.map(String::trim).filter(String::isNotEmpty).forEach(values::add)
    if ("operator.admin" in values) {
        values += "operator.read"
        values += "operator.write"
    } else if ("operator.write" in values) {
        values += "operator.read"
    }
    return values.toList().sorted()
}

fun buildConnectParams(
    auth: ConnectAuthBundle,
    identity: DeviceIdentity,
    nonce: String,
): JsonObject = buildJsonObject {
    val signedAtMs = System.currentTimeMillis()
    val payload = buildDeviceAuthPayloadV3(
        deviceId = identity.deviceId,
        clientId = DEFAULT_CLIENT_ID,
        clientMode = DEFAULT_CLIENT_MODE,
        role = ROLE_OPERATOR,
        scopes = auth.requestedScopes,
        signedAtMs = signedAtMs,
        token = auth.authToken,
        nonce = nonce,
        platform = buildPlatformString(),
        deviceFamily = "android",
    )

    put("minProtocol", 3)
    put("maxProtocol", 3)
    putJsonObject("client") {
        put("id", DEFAULT_CLIENT_ID)
        put("displayName", DEFAULT_CLIENT_NAME)
        put("version", DEFAULT_CLIENT_VERSION)
        put("platform", buildPlatformString())
        put("deviceFamily", "android")
        put("modelIdentifier", Build.MODEL ?: "android-device")
        put("mode", DEFAULT_CLIENT_MODE)
        put("instanceId", identity.deviceId)
    }
    putJsonArray("caps") {
        add(JsonPrimitive("tool-events"))
    }
    put("role", ROLE_OPERATOR)
    putJsonArray("scopes") {
        auth.requestedScopes.forEach { add(JsonPrimitive(it)) }
    }
    putJsonObject("auth") {
        put("token", auth.authToken)
        auth.deviceToken?.let { put("deviceToken", it) }
    }
    putJsonObject("device") {
        put("id", identity.deviceId)
        put("publicKey", identity.publicKeyBase64Url)
        put("signature", signDevicePayload(identity.privateKeyPkcs8Base64, payload))
        put("signedAt", signedAtMs)
        put("nonce", nonce)
    }
}

fun buildHistoryParams(sessionKey: String): JsonObject = buildJsonObject {
    put("sessionKey", sessionKey)
    put("limit", 200)
}

fun buildSendParams(sessionKey: String, message: String, idempotencyKey: String): JsonObject = buildJsonObject {
    put("sessionKey", sessionKey)
    put("message", message)
    put("idempotencyKey", idempotencyKey)
}

fun buildAbortParams(sessionKey: String, runId: String): JsonObject = buildJsonObject {
    put("sessionKey", sessionKey)
    put("runId", runId)
}

fun generateIdempotencyKey(): String = UUID.randomUUID().toString()

fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

fun extractMessageText(element: JsonElement?): String {
    if (element == null || element is JsonNull) return ""

    return when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString(separator = "") { extractMessageText(it) }
        is JsonObject -> {
            when {
                "message" in element -> extractMessageText(element["message"])
                "content" in element -> extractMessageText(element["content"])
                element.string("type") == "text" -> element.string("text").orEmpty()
                else -> listOfNotNull(
                    element["text"],
                    element["content"],
                    element["message"],
                    element["data"],
                ).joinToString(separator = "") { extractMessageText(it) }
            }
        }
    }
}

fun extractMessageRole(element: JsonObject): String {
    val nested = element["message"] as? JsonObject
    return nested?.string("role") ?: element.string("role") ?: "system"
}

fun extractMessagesFromHistory(payload: JsonElement?): List<ChatMessage> {
    val messages = payload
        ?.jsonObject
        ?.get("messages")
        ?.jsonArray
        ?: return emptyList()

    return messages.mapNotNullIndexed { index, item ->
        val raw = item as? JsonObject ?: return@mapNotNullIndexed null
        val role = extractMessageRole(raw)
        val text = extractMessageText(raw).trim()
        if (text.isBlank()) return@mapNotNullIndexed null
        ChatMessage(
            id = raw.string("id") ?: "history-$index",
            role = role,
            text = text,
            isStreaming = false,
        )
    }
}

fun extractHelloAuth(payload: JsonElement?): StoredDeviceAuth? {
    val auth = payload?.jsonObject?.get("auth")?.jsonObject ?: return null
    val token = auth.string("deviceToken") ?: return null
    val scopes = auth["scopes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    return StoredDeviceAuth(token = token, scopes = normalizeDeviceScopes(scopes))
}

fun extractPairingRequestId(response: GatewayResponse): String? {
    val details = response.errorDetails?.jsonObject ?: return null
    return details.string("requestId")
}

fun buildDeviceAuthPayloadV3(
    deviceId: String,
    clientId: String,
    clientMode: String,
    role: String,
    scopes: List<String>,
    signedAtMs: Long,
    token: String,
    nonce: String,
    platform: String,
    deviceFamily: String,
): String = listOf(
    "v3",
    deviceId,
    clientId,
    clientMode,
    role,
    scopes.joinToString(","),
    signedAtMs.toString(),
    token,
    nonce,
    normalizeDeviceMetadataForAuth(platform),
    normalizeDeviceMetadataForAuth(deviceFamily),
).joinToString("|")

private fun normalizeDeviceMetadataForAuth(value: String): String {
    return value.trim().lowercase().replace('|', '-').replace(',', '-')
}

private fun buildPlatformString(): String = "android-${Build.VERSION.RELEASE ?: "unknown"}"

private inline fun <T, R : Any> Iterable<T>.mapNotNullIndexed(transform: (index: Int, T) -> R?): List<R> {
    val destination = ArrayList<R>()
    forEachIndexed { index, item ->
        transform(index, item)?.let(destination::add)
    }
    return destination
}
