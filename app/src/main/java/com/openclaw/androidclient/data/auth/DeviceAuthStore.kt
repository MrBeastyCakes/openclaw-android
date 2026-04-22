package com.openclaw.androidclient.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.openclaw.androidclient.data.model.DeviceIdentity
import com.openclaw.androidclient.data.model.StoredDeviceAuth
import com.openclaw.androidclient.data.model.normalizeDeviceScopes
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import org.json.JSONArray
import org.json.JSONObject

class DeviceAuthStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadOrCreateIdentity(): DeviceIdentity {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        val publicKey = prefs.getString(KEY_PUBLIC_KEY_B64URL, null)
        val privateKey = prefs.getString(KEY_PRIVATE_KEY_PKCS8_B64, null)
        if (deviceId != null && publicKey != null && privateKey != null) {
            return DeviceIdentity(deviceId = deviceId, publicKeyBase64Url = publicKey, privateKeyPkcs8Base64 = privateKey)
        }

        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val rawPublicKey = deriveRawEd25519PublicKey(keyPair.public.encoded)
        val identity = DeviceIdentity(
            deviceId = sha256Hex(rawPublicKey),
            publicKeyBase64Url = base64UrlEncode(rawPublicKey),
            privateKeyPkcs8Base64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP),
        )
        prefs.edit()
            .putString(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_PUBLIC_KEY_B64URL, identity.publicKeyBase64Url)
            .putString(KEY_PRIVATE_KEY_PKCS8_B64, identity.privateKeyPkcs8Base64)
            .apply()
        return identity
    }

    fun loadStoredDeviceToken(deviceId: String, role: String): StoredDeviceAuth? {
        val rawStore = prefs.getString(KEY_DEVICE_AUTH_STORE, null) ?: return null
        val store = JSONObject(rawStore)
        if (store.optString("deviceId", "") != deviceId) return null
        val tokens = store.optJSONObject("tokens") ?: return null
        val tokenEntry = tokens.optJSONObject(role) ?: return null
        val token = tokenEntry.optString("token", "") ?: return null
        val scopesArray = tokenEntry.optJSONArray("scopes")
        val scopes = mutableListOf<String>()
        if (scopesArray != null) {
            for (i in 0 until scopesArray.length()) {
                scopesArray.optString(i, null)?.let { scopes.add(it) }
            }
        }
        return StoredDeviceAuth(token = token, scopes = normalizeDeviceScopes(scopes))
    }

    fun storeDeviceToken(deviceId: String, role: String, auth: StoredDeviceAuth) {
        val existing = prefs.getString(KEY_DEVICE_AUTH_STORE, null)
        val currentStore = existing?.let { JSONObject(it) } ?: JSONObject().apply {
            put("version", 1)
            put("deviceId", deviceId)
            put("tokens", JSONObject())
        }
        val nextTokens = currentStore.optJSONObject("tokens") ?: JSONObject()
        val roleObj = JSONObject().apply {
            put("token", auth.token)
            put("role", role)
            put("updatedAtMs", System.currentTimeMillis())
            put("scopes", JSONArray(auth.scopes))
        }
        nextTokens.put(role, roleObj)
        currentStore.put("tokens", nextTokens)
        prefs.edit().putString(KEY_DEVICE_AUTH_STORE, currentStore.toString()).apply()
    }

    fun clearStoredDeviceToken(deviceId: String, role: String) {
        val existing = prefs.getString(KEY_DEVICE_AUTH_STORE, null) ?: return
        val currentStore = JSONObject(existing)
        if (currentStore.optString("deviceId", null) != deviceId) return
        val nextTokens = currentStore.optJSONObject("tokens") ?: return
        nextTokens.remove(role)
        currentStore.put("tokens", nextTokens)
        prefs.edit().putString(KEY_DEVICE_AUTH_STORE, currentStore.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "openclaw_client"
        private const val KEY_DEVICE_ID = "device_identity_id"
        private const val KEY_PUBLIC_KEY_B64URL = "device_identity_public"
        private const val KEY_PRIVATE_KEY_PKCS8_B64 = "device_identity_private"
        private const val KEY_DEVICE_AUTH_STORE = "device_auth_store"
        private const val ED25519_SPKI_PREFIX_HEX = "302a300506032b6570032100"

        private fun deriveRawEd25519PublicKey(encoded: ByteArray): ByteArray {
            val prefix = hexToBytes(ED25519_SPKI_PREFIX_HEX)
            return if (encoded.size == prefix.size + 32 && encoded.copyOfRange(0, prefix.size).contentEquals(prefix)) {
                encoded.copyOfRange(prefix.size, encoded.size)
            } else {
                encoded
            }
        }

        private fun hexToBytes(value: String): ByteArray {
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        private fun sha256Hex(input: ByteArray): String {
            return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
        }

        private fun base64UrlEncode(input: ByteArray): String {
            return Base64.encodeToString(input, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
        }
    }
}

fun signDevicePayload(privateKeyPkcs8Base64: String, payload: String): String {
    val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
    val privateKeySpec = PKCS8EncodedKeySpec(Base64.decode(privateKeyPkcs8Base64, Base64.NO_WRAP))
    val privateKey: PrivateKey = keyFactory.generatePrivate(privateKeySpec)
    val signature = Signature.getInstance("Ed25519")
    signature.initSign(privateKey)
    signature.update(payload.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(signature.sign(), Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
}
