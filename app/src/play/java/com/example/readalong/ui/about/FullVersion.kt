package com.example.readalong.ui.about

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The Play edition's one-time "full version" purchase (Play Console in-app product [PRODUCT_ID]).
 *
 * Promo codes made in Play Console for that product grant the same purchase, whether they're redeemed in the Play
 * Store or through [redeem]; [refresh] runs whenever ReadAlong comes back on screen, so a redeemed code shows up
 * as soon as the person returns. The last known answer is kept on the phone so an unlocked copy stays unlocked
 * offline. Nothing is ever locked: after the trial the app only reminds.
 */
class FullVersion private constructor(context: Context) : PurchasesUpdatedListener {
    data class State(
        val owned: Boolean,
        val pending: Boolean = false,
        /** Play's localized price, e.g. "$0.99"; null until the store answers. */
        val price: String? = null,
        val trialStartMs: Long,
    ) {
        fun daysLeft(nowMs: Long = System.currentTimeMillis()) = Trial.daysLeft(trialStartMs, nowMs)
        fun trialEnded(nowMs: Long = System.currentTimeMillis()) = Trial.ended(trialStartMs, nowMs)
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("full_version", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State(owned = prefs.getBoolean(KEY_OWNED, false), trialStartMs = trialStart()))
    val state: StateFlow<State> = _state
    private var details: ProductDetails? = null

    private val client = BillingClient.newBuilder(app)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** First install time, kept in preferences too so a restored backup doesn't hand out a second trial. */
    private fun trialStart(): Long {
        val installed = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime }.getOrNull() ?: System.currentTimeMillis()
        val saved = prefs.getLong(KEY_TRIAL_START, Long.MAX_VALUE)
        val start = minOf(installed, saved)
        if (start != saved) prefs.edit().putLong(KEY_TRIAL_START, start).apply()
        return start
    }

    /** Asks Play what this account owns and what the unlock costs. Safe to call often. */
    fun refresh() {
        when (client.connectionState) {
            BillingClient.ConnectionState.CONNECTED -> { queryPurchases(); queryPrice() }
            BillingClient.ConnectionState.CONNECTING -> Unit
            else -> client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) { queryPurchases(); queryPrice() }
                }
                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, complete = true)
        }
    }

    private fun queryPrice() {
        if (details != null) return
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val unlock = found.productDetailsList.firstOrNull { it.productId == PRODUCT_ID } ?: return@queryProductDetailsAsync
            details = unlock
            _state.update { it.copy(price = unlock.oneTimePurchaseOfferDetails?.formattedPrice) }
        }
    }

    /** Opens Play's purchase sheet. False when the store hasn't answered yet (no connection, or the product isn't set up). */
    fun buy(activity: Activity): Boolean {
        val product = details ?: run { refresh(); return false }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    /** Hands a promo code to the Play Store, which redeems it and grants the unlock. */
    fun redeem(activity: Activity, code: String) {
        val uri = Uri.parse("https://play.google.com/redeem?code=" + Uri.encode(code.trim()))
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handle(purchases.orEmpty(), complete = false)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            else -> Unit
        }
    }

    /**
     * [complete] is a full answer from Play (so a refunded purchase locks nothing but clears "unlocked");
     * otherwise these are only the purchases that just changed.
     */
    private fun handle(purchases: List<Purchase>, complete: Boolean) {
        val mine = purchases.filter { PRODUCT_ID in it.products }
        val owned = mine.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = mine.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        // Play refunds purchases (and promo code redemptions) that aren't acknowledged within three days.
        mine.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { purchase ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { }
        }
        if (!complete && !owned && !pending) return
        prefs.edit().putBoolean(KEY_OWNED, owned).apply()
        _state.update { it.copy(owned = owned, pending = pending && !owned) }
    }

    companion object {
        /** Create this one-time product in Play Console › Monetize › Products › In-app products, priced at $0.99. */
        const val PRODUCT_ID = "full_version"
        private const val KEY_OWNED = "owned"
        private const val KEY_TRIAL_START = "trial_start_ms"

        @Volatile private var instance: FullVersion? = null
        fun get(context: Context): FullVersion = instance ?: synchronized(this) { instance ?: FullVersion(context).also { instance = it } }
    }
}
