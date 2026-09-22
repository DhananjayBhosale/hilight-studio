package com.hilight.studio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
private const val PRODUCT_ID = "supporter_badge_lifetime"
private const val PREFS = "play_support"
private const val KEY_CONTINUE_FREE = "continue_free"
private const val KEY_SUPPORTER = "supporter_owned"
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
    private var productDetails: ProductDetails? = null
    private var offerToken: String? = null

    private var showChoice by mutableStateOf(false)
    private var freeContinuationChosen by mutableStateOf(prefs.getBoolean(KEY_CONTINUE_FREE, false))
    private var supporterOwned by mutableStateOf(prefs.getBoolean(KEY_SUPPORTER, false))
    private var formattedPrice by mutableStateOf<String?>(null)
    private var purchaseStatus by mutableStateOf(PurchaseStatus.CONNECTING)

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

    private fun launchPurchase() {
        val details = productDetails
        val token = offerToken
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

    override fun onBillingSetupFinished(result: BillingResult) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            purchaseStatus = PurchaseStatus.ERROR
            Log.w(TAG, "Billing setup failed: ${result.responseCode}")
            return
        }
        purchaseStatus = PurchaseStatus.READY
        queryProduct()
        restorePurchase()
    }

    override fun onBillingServiceDisconnected() {
        // PBL 9 reconnects automatically. This state only keeps the purchase button honest.
        purchaseStatus = PurchaseStatus.CONNECTING
    }

    private fun queryProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        ) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseStatus = PurchaseStatus.ERROR
                return@queryProductDetailsAsync
            }
            val details = queryResult.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
            productDetails = details
            offerToken = offer?.offerToken
            formattedPrice = offer?.formattedPrice
            if (details == null || offer == null) purchaseStatus = PurchaseStatus.ERROR
        }
    }

    private fun restorePurchase() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach(::handlePurchase)
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
        if (PRODUCT_ID !in purchase.products) return
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            purchaseStatus = PurchaseStatus.PENDING
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.isAcknowledged) {
            grantSupporterStatus()
            return
        }
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                grantSupporterStatus()
            } else {
                purchaseStatus = PurchaseStatus.ERROR
                Log.w(TAG, "Could not acknowledge purchase: ${result.responseCode}")
            }
        }
    }

    private fun grantSupporterStatus() {
        supporterOwned = true
        purchaseStatus = PurchaseStatus.READY
        prefs.edit { putBoolean(KEY_SUPPORTER, true) }
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
                FilledTonalButton(
                    onClick = ::launchPurchase,
                    enabled = productDetails != null && purchaseStatus != PurchaseStatus.PURCHASING,
                ) {
                    Text(
                        formattedPrice?.let { stringResource(R.string.supporter_buy_price, it) }
                            ?: stringResource(R.string.supporter_buy)
                    )
                }
                PurchaseStatusText(purchaseStatus)
            }
        }

        if (showChoice) {
            AlertDialog(
                onDismissRequest = ::dismissChoice,
                title = { Text(stringResource(R.string.supporter_prompt_title)) },
                text = {
                    Text(
                        stringResource(R.string.supporter_prompt_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = ::launchPurchase,
                        enabled = productDetails != null && purchaseStatus != PurchaseStatus.PURCHASING,
                    ) {
                        Text(
                            formattedPrice?.let { stringResource(R.string.supporter_buy_price, it) }
                                ?: stringResource(R.string.supporter_buy)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = ::continueFree) {
                        Text(stringResource(R.string.supporter_continue_free))
                    }
                },
            )
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
