package com.hilight.studio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
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

private const val TAG = "HiLightSupport"
private const val LIFETIME_PRODUCT_ID = "supporter_badge_lifetime"
private const val MONTHLY_PRODUCT_ID = "supporter_monthly"
private const val MONTHLY_BASE_PLAN_ID = "monthly"
private const val PREFS = "play_support"
private const val KEY_CONTINUE_FREE = "continue_free"
private const val KEY_LIFETIME_SUPPORTER = "lifetime_supporter_owned"
private const val KEY_SEEN_PATTERNS = "seen_patterns"

private enum class PurchaseStatus { IDLE, CONNECTING, READY, PURCHASING, PENDING, ERROR }

internal class SupportPromptState(
    private val appContext: Context,
    private val activity: Activity?,
) : PurchasesUpdatedListener, BillingClientStateListener {
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val seenPatterns = prefs.getStringSet(KEY_SEEN_PATTERNS, emptySet()).orEmpty().toMutableSet()
    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    private var pendingPattern: Pattern? = null
    private var pendingApply: (() -> Unit)? = null
    private var lifetimeDetails: ProductDetails? = null
    private var lifetimeOfferToken: String? = null
    private var monthlyDetails: ProductDetails? = null
    private var monthlyOfferToken: String? = null

    private var showChoice by mutableStateOf(false)
    private var freeContinuationChosen by mutableStateOf(prefs.getBoolean(KEY_CONTINUE_FREE, false))
    private var lifetimeOwned by mutableStateOf(prefs.getBoolean(KEY_LIFETIME_SUPPORTER, false))
    private var monthlyActive by mutableStateOf(false)
    private var lifetimePrice by mutableStateOf<String?>(null)
    private var monthlyPrice by mutableStateOf<String?>(null)
    private var purchaseStatus by mutableStateOf(PurchaseStatus.CONNECTING)

    private val supporterOwned: Boolean
        get() = lifetimeOwned || monthlyActive

    init {
        billingClient.startConnection(this)
    }

    fun close() = billingClient.endConnection()

    fun onPatternSelected(pattern: Pattern, apply: () -> Unit) {
        if (shouldOfferSupportChoice(
                seenPatternKeys = seenPatterns,
                candidatePatternKey = pattern.key,
                freeContinuationChosen = freeContinuationChosen,
                supporterOwned = supporterOwned,
            )
        ) {
            pendingPattern = pattern
            pendingApply = apply
            showChoice = true
            return
        }
        rememberPattern(pattern)
        apply()
    }

    private fun rememberPattern(pattern: Pattern) {
        if (seenPatterns.add(pattern.key)) {
            prefs.edit { putStringSet(KEY_SEEN_PATTERNS, seenPatterns.toSet()) }
        }
    }

    private fun continueFree() {
        freeContinuationChosen = true
        prefs.edit { putBoolean(KEY_CONTINUE_FREE, true) }
        completePendingPattern()
    }

    private fun completePendingPattern() {
        pendingPattern?.let(::rememberPattern)
        pendingApply?.invoke()
        pendingPattern = null
        pendingApply = null
        showChoice = false
    }

    private fun dismissChoice() {
        pendingPattern = null
        pendingApply = null
        showChoice = false
    }

    private fun launchPurchase(details: ProductDetails?, token: String?) {
        if (activity == null || details == null || token.isNullOrBlank()) {
            purchaseStatus = PurchaseStatus.ERROR
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(token)
            .build()
        purchaseStatus = PurchaseStatus.PURCHASING
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build(),
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            purchaseStatus = PurchaseStatus.ERROR
            Log.w(TAG, "Could not launch billing flow: ${result.responseCode}")
        }
    }

    private fun launchMonthlyPurchase() = launchPurchase(monthlyDetails, monthlyOfferToken)

    private fun launchLifetimePurchase() = launchPurchase(lifetimeDetails, lifetimeOfferToken)

    override fun onBillingSetupFinished(result: BillingResult) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            purchaseStatus = PurchaseStatus.ERROR
            Log.w(TAG, "Billing setup failed: ${result.responseCode}")
            return
        }
        purchaseStatus = PurchaseStatus.READY
        queryProducts()
        restorePurchases()
    }

    override fun onBillingServiceDisconnected() {
        // PBL 9 reconnects automatically. This state only keeps the purchase button honest.
        purchaseStatus = PurchaseStatus.CONNECTING
    }

    private fun queryProducts() {
        queryLifetimeProduct()
        queryMonthlyProduct()
    }

    private fun queryLifetimeProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(LIFETIME_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        ) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseStatus = PurchaseStatus.ERROR
                return@queryProductDetailsAsync
            }
            val details = queryResult.productDetailsList.firstOrNull {
                it.productId == LIFETIME_PRODUCT_ID
            }
            val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
            lifetimeDetails = details
            lifetimeOfferToken = offer?.offerToken
            lifetimePrice = offer?.formattedPrice
            if (details == null || offer == null) purchaseStatus = PurchaseStatus.ERROR
        }
    }

    private fun queryMonthlyProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(MONTHLY_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        ) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseStatus = PurchaseStatus.ERROR
                return@queryProductDetailsAsync
            }
            val details = queryResult.productDetailsList.firstOrNull {
                it.productId == MONTHLY_PRODUCT_ID
            }
            val offer = details?.subscriptionOfferDetails?.firstOrNull {
                it.basePlanId == MONTHLY_BASE_PLAN_ID
            }
            monthlyDetails = details
            monthlyOfferToken = offer?.offerToken
            monthlyPrice = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            if (details == null || offer == null) purchaseStatus = PurchaseStatus.ERROR
        }
    }

    private fun restorePurchases() {
        queryOwnedPurchases(BillingClient.ProductType.INAPP) { purchases ->
            lifetimeOwned = false
            prefs.edit { putBoolean(KEY_LIFETIME_SUPPORTER, false) }
            purchases.forEach(::handlePurchase)
        }
        queryOwnedPurchases(BillingClient.ProductType.SUBS) { purchases ->
            monthlyActive = false
            purchases.forEach(::handlePurchase)
        }
    }

    private fun queryOwnedPurchases(productType: String, onSuccess: (List<Purchase>) -> Unit) {
        val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                onSuccess(purchases)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::handlePurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> purchaseStatus = PurchaseStatus.READY
            else -> {
                purchaseStatus = PurchaseStatus.ERROR
                Log.w(TAG, "Purchase failed: ${result.responseCode}")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        val grantsLifetime = LIFETIME_PRODUCT_ID in purchase.products
        val grantsMonthly = MONTHLY_PRODUCT_ID in purchase.products
        if (!grantsLifetime && !grantsMonthly) return
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            purchaseStatus = PurchaseStatus.PENDING
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.isAcknowledged) {
            grantSupporterStatus(grantsLifetime, grantsMonthly)
            return
        }
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                grantSupporterStatus(grantsLifetime, grantsMonthly)
            } else {
                purchaseStatus = PurchaseStatus.ERROR
                Log.w(TAG, "Could not acknowledge purchase: ${result.responseCode}")
            }
        }
    }

    private fun grantSupporterStatus(grantsLifetime: Boolean, grantsMonthly: Boolean) {
        if (grantsLifetime) {
            lifetimeOwned = true
            prefs.edit { putBoolean(KEY_LIFETIME_SUPPORTER, true) }
        }
        if (grantsMonthly) monthlyActive = true
        purchaseStatus = PurchaseStatus.READY
        completePendingPattern()
    }

    @Composable
    fun Content() {
        PixelCard(tone = if (supporterOwned) 2 else 1) {
            SectionTitle(
                stringResource(
                    if (supporterOwned) R.string.supporter_badge_title
                    else R.string.supporter_card_title
                )
            )
            Caption(
                stringResource(
                    if (supporterOwned) R.string.supporter_badge_body
                    else R.string.supporter_card_body
                )
            )
            if (!supporterOwned) {
                PurchaseButtons()
                Caption(stringResource(R.string.supporter_terms))
                PurchaseStatusText(purchaseStatus)
            }
        }

        if (showChoice) {
            AlertDialog(
                onDismissRequest = ::dismissChoice,
                title = { Text(stringResource(R.string.supporter_prompt_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.supporter_prompt_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        PurchaseButtons()
                        Caption(stringResource(R.string.supporter_terms))
                        PurchaseStatusText(purchaseStatus)
                    }
                },
                confirmButton = {
                    TextButton(onClick = ::continueFree) {
                        Text(stringResource(R.string.supporter_continue_free))
                    }
                },
            )
        }
    }

    @Composable
    private fun PurchaseButtons() {
        val purchasesEnabled = purchaseStatus != PurchaseStatus.PURCHASING
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = ::launchMonthlyPurchase,
                enabled = monthlyDetails != null && purchasesEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    monthlyPrice?.let { stringResource(R.string.supporter_monthly_price, it) }
                        ?: stringResource(R.string.supporter_monthly)
                )
            }
            FilledTonalButton(
                onClick = ::launchLifetimePurchase,
                enabled = lifetimeDetails != null && purchasesEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    lifetimePrice?.let { stringResource(R.string.supporter_lifetime_price, it) }
                        ?: stringResource(R.string.supporter_lifetime)
                )
            }
        }
    }
}

@Composable
private fun PurchaseStatusText(status: PurchaseStatus) {
    val message = when (status) {
        PurchaseStatus.CONNECTING -> R.string.supporter_connecting
        PurchaseStatus.PURCHASING -> R.string.supporter_purchasing
        PurchaseStatus.PENDING -> R.string.supporter_pending
        PurchaseStatus.ERROR -> R.string.supporter_unavailable
        else -> null
    }
    message?.let { Caption(stringResource(it)) }
}

@Composable
internal fun rememberSupportPromptState(): SupportPromptState {
    val context = LocalContext.current
    val state = remember(context) { SupportPromptState(context.applicationContext, context.findActivity()) }
    DisposableEffect(state) { onDispose(state::close) }
    return state
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
