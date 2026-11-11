package com.example.readalong.audible

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signs in by registering ReadAlong as a device on the person's Amazon account, the way the Audible
 * app does. The person types their password into Amazon's own page; ReadAlong only sees the
 * one-time code Amazon hands back on the final redirect.
 *
 * Ported from LibriSync (GPL-3.0, itself a port of Libation): native/rust-core/src/api/auth.rs. It
 * registers with the updated iPhone profile (mkb79/Audible PR #910), which Audible still grants
 * download licences to; see LibriSync's AUDIBLE_DEVICE_REGISTRATION.md.
 */
object AudibleAuth {
    const val DEVICE_TYPE = "A2CZJZGLK2JJVM"
    internal const val APP_VERSION = "4.60"
    internal const val OS_VERSION = "26.2"
    internal const val SOFTWARE_VERSION = "46000856"
    private const val SERIAL_LENGTH = 40
    private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    /** One sign-in attempt. Keep it until [register] runs: the verifier and serial must match the page that was opened. */
    data class Login(val locale: AudibleLocale, val deviceSerial: String, val codeVerifier: String, val url: String)

    fun startLogin(locale: AudibleLocale): Login {
        val serial = (1..SERIAL_LENGTH).map { ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)] }.joinToString("")
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val amazon = "https://www.${locale.amazonDomain}"
        val query = linkedMapOf(
            "openid.oa2.response_type" to "code",
            "openid.oa2.code_challenge_method" to "S256",
            "openid.oa2.code_challenge" to challenge,
            "openid.return_to" to "$amazon/ap/maplanding",
            "openid.assoc_handle" to "amzn_audible_ios_${locale.code}",
            "openid.identity" to "http://specs.openid.net/auth/2.0/identifier_select",
            "pageId" to "amzn_audible_ios",
            "accountStatusPolicy" to "P1",
            "openid.claimed_id" to "http://specs.openid.net/auth/2.0/identifier_select",
            "openid.mode" to "checkid_setup",
            "openid.ns.oa2" to "http://www.amazon.com/ap/ext/oauth/2",
            "openid.oa2.client_id" to "device:${clientId(serial)}",
            "openid.ns.pape" to "http://specs.openid.net/extensions/pape/1.0",
            "marketPlaceId" to locale.marketplace,
            "openid.oa2.scope" to "device_auth_access",
            "forceMobileLayout" to "true",
            "openid.ns" to "http://specs.openid.net/auth/2.0",
            "openid.pape.max_auth_age" to "0",
        ).entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return Login(locale, serial, verifier, "$amazon/ap/signin?$query")
    }

    /** True for the address Amazon sends the browser to once sign-in has finished. */
    fun isFinalRedirect(url: String) = runCatching { URI(url).path == "/ap/maplanding" }.getOrDefault(false)

    /** The one-time code in Amazon's final redirect, or an error naming what went wrong. */
    fun authorizationCode(redirectUrl: String): String {
        val query = runCatching { URI(redirectUrl.trim()).rawQuery }.getOrNull()
            ?: throw AudibleException("That isn't the address Amazon showed after signing in. Copy the whole address and try again.")
        val parameters = query.split('&').mapNotNull { pair ->
            pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> decode(key) to decode(value) }
        }.toMap()
        parameters["error"]?.let { throw AudibleException("Amazon didn't finish signing in: ${parameters["error_description"] ?: it}") }
        return parameters["openid.oa2.authorization_code"] ?: parameters["code"]
            ?: throw AudibleException("Amazon's address has no sign-in code. Sign in again, then copy the address of the final page.")
    }

    /** Registers this device with the code from Amazon's final redirect. */
    fun register(login: Login, redirectUrl: String, now: Long = System.currentTimeMillis()): AudibleAccount {
        val code = authorizationCode(redirectUrl)
        val body = JSONObject()
            .put("requested_token_type", JSONArray(listOf("bearer", "mac_dms", "website_cookies", "store_authentication_cookie")))
            .put("cookies", JSONObject().put("website_cookies", JSONArray()).put("domain", ".${login.locale.amazonDomain}"))
            .put("registration_data", JSONObject()
                .put("domain", "Device").put("app_version", APP_VERSION).put("device_serial", login.deviceSerial)
                .put("device_type", DEVICE_TYPE)
                .put("device_name", "%FIRST_NAME%%FIRST_NAME_POSSESSIVE_STRING%%DUPE_STRATEGY_1ST%Audible for iPhone")
                .put("os_version", OS_VERSION).put("software_version", SOFTWARE_VERSION)
                .put("device_model", "iPhone").put("app_name", "Audible"))
            .put("auth_data", JSONObject()
                .put("client_id", clientId(login.deviceSerial)).put("authorization_code", code)
                .put("code_verifier", login.codeVerifier).put("code_algorithm", "SHA-256").put("client_domain", "DeviceLegacy"))
            .put("requested_extensions", JSONArray(listOf("device_info", "customer_info")))
        val response = AudibleHttp.request("POST", "${login.locale.amazonApi}/auth/register",
            mapOf("Content-Type" to "application/json", "Accept" to "application/json"), body.toString().toByteArray())
        if (response.status !in 200..299) throw AudibleException("Amazon didn't register ReadAlong (HTTP ${response.status}). Sign in again.", response.status)
        val success = JSONObject(response.text).getJSONObject("response").getJSONObject("success")
        val tokens = success.getJSONObject("tokens")
        val bearer = tokens.getJSONObject("bearer")
        val macDms = tokens.getJSONObject("mac_dms")
        val extensions = success.getJSONObject("extensions")
        val device = extensions.getJSONObject("device_info")
        val customer = extensions.getJSONObject("customer_info")
        return AudibleAccount(login.locale, customer.getString("user_id"), customer.optString("given_name").ifEmpty { customer.optString("name") },
            device.optString("device_serial_number", login.deviceSerial), device.optString("device_type", DEVICE_TYPE),
            macDms.getString("adp_token"), macDms.getString("device_private_key"),
            bearer.getString("access_token"), now + bearer.optString("expires_in", "3600").toLong() * 1000, bearer.getString("refresh_token"))
    }

    /** A fresh access token; only signing out needs one; everything else is signed with the device key. */
    fun refresh(account: AudibleAccount, now: Long = System.currentTimeMillis()): AudibleAccount {
        if (account.accessToken.isNotEmpty() && account.accessTokenExpiresAt > now + 5 * 60_000) return account
        val form = mapOf("app_name" to "Audible", "app_version" to APP_VERSION, "source_token" to account.refreshToken,
            "source_token_type" to "refresh_token", "requested_token_type" to "access_token")
            .entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val response = AudibleHttp.request("POST", "${account.locale.amazonApi}/auth/token",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"), form.toByteArray())
        if (response.status !in 200..299) throw AudibleException("Amazon didn't renew the sign-in (HTTP ${response.status}).", response.status)
        val json = JSONObject(response.text)
        return account.copy(accessToken = json.getString("access_token"), accessTokenExpiresAt = now + json.optLong("expires_in", 3600) * 1000,
            refreshToken = json.optString("refresh_token").ifEmpty { account.refreshToken })
    }

    /** Removes this device from the Amazon account. Best effort: signing out locally never depends on it. */
    fun deregister(account: AudibleAccount) {
        val fresh = refresh(account)
        val response = AudibleHttp.request("POST", "${account.locale.amazonApi}/auth/deregister",
            mapOf("Authorization" to "Bearer ${fresh.accessToken}", "Content-Type" to "application/json"),
            JSONObject().put("deregister_all_existing_accounts", false).toString().toByteArray())
        if (response.status !in 200..299) throw AudibleException("Amazon didn't remove the device (HTTP ${response.status}).", response.status)
    }

    /** Amazon's client id for a device: hex of "serial#type", lowercase. */
    internal fun clientId(serial: String) = "$serial#$DEVICE_TYPE".toByteArray().joinToString("") { "%02x".format(it) }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
}
