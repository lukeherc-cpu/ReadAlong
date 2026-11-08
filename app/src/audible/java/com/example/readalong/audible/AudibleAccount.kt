package com.example.readalong.audible

import org.json.JSONObject

/**
 * A device registration on someone's Amazon account. It holds the keys Audible gave this device,
 * so it is stored only in the app's private storage and never logged.
 */
data class AudibleAccount(
    val locale: AudibleLocale,
    val customerId: String,
    val customerName: String,
    val deviceSerial: String,
    val deviceType: String,
    val adpToken: String,
    val devicePrivateKey: String,
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    /** The AAX key, fetched the first time a book only comes as AAX. */
    val activationBytes: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("locale", locale.code).put("customerId", customerId).put("customerName", customerName)
        .put("deviceSerial", deviceSerial).put("deviceType", deviceType).put("adpToken", adpToken)
        .put("devicePrivateKey", devicePrivateKey).put("accessToken", accessToken)
        .put("accessTokenExpiresAt", accessTokenExpiresAt).put("refreshToken", refreshToken)
        .put("activationBytes", activationBytes ?: JSONObject.NULL)
        .toString()

    override fun toString() = "AudibleAccount(${locale.code}, $customerName)" // Never print keys.

    companion object {
        fun fromJson(text: String): AudibleAccount = JSONObject(text).run {
            AudibleAccount(AudibleLocale.of(getString("locale")), getString("customerId"), optString("customerName"),
                getString("deviceSerial"), getString("deviceType"), getString("adpToken"), getString("devicePrivateKey"),
                optString("accessToken"), optLong("accessTokenExpiresAt"), getString("refreshToken"),
                if (isNull("activationBytes")) null else optString("activationBytes").ifEmpty { null })
        }
    }
}
