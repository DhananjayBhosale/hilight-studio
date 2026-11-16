package com.hilight.studio

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.WeakHashMap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect

private const val TAG = "HiLightSupport"
private const val LIFETIME_PRODUCT_ID = "supporter_badge_lifetime"
private const val MONTHLY_PRODUCT_ID = "supporter_monthly"
private const val MONTHLY_BASE_PLAN_ID = "monthly"
private const val PREFS = "play_support"
private const val KEY_LAST_PROMPTED_VERSION = "last_prompted_version"
private const val KEY_LIFETIME_SUPPORTER = "lifetime_supporter_owned"
private const val KEY_MONTHLY_SUPPORTER = "monthly_supporter_active"
private const val KEY_SEEN_PATTERNS = "seen_patterns"
private const val KEY_LAST_SEEN_VERSION = "last_seen_version"
/** How long the after-update offer waits for Play's ownership answer before using saved state. */
private const val OWNERSHIP_WAIT_MS = 4_000L
private const val HERO_LEDS = 8
private const val LED_WAVE_MS = 3_200
private val LED_HERO_BACKGROUND = Color(0xFF16171A)

private enum class PromptReason { STYLES, UPDATE }

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
    // Compose state: the purchase buttons must notice when Google Play's answer arrives (#76).
    private var lifetimeDetails by mutableStateOf<ProductDetails?>(null)
    private var lifetimeOfferToken by mutableStateOf<String?>(null)
    private var monthlyDetails by mutableStateOf<ProductDetails?>(null)
    private var monthlyOfferToken by mutableStateOf<String?>(null)

    private var showChoice by mutableStateOf(false)
    private var promptReason by mutableStateOf(PromptReason.STYLES)
    // Product types Play has answered an owned-purchase query for, at least once.
    private val ownershipAnswered = mutableSetOf<String>()
    private var ownershipKnown by mutableStateOf(false)
    private var lastPromptedVersion by mutableIntStateOf(
        prefs.getInt(KEY_LAST_PROMPTED_VERSION, 0)
    )
    private var lifetimeOwned by mutableStateOf(prefs.getBoolean(KEY_LIFETIME_SUPPORTER, false))
    // Remembered so an offline start doesn't treat a subscriber as a non-supporter; Play's answer
    // replaces it once billing connects.
    private var monthlyActive by mutableStateOf(prefs.getBoolean(KEY_MONTHLY_SUPPORTER, false))
    private var lifetimePrice by mutableStateOf<String?>(null)
    private var monthlyPrice by mutableStateOf<String?>(null)
    private var purchaseStatus by mutableStateOf(PurchaseStatus.CONNECTING)
    // An outstanding startConnection(). Kept apart from the caption: after a disconnect the
    // caption says "connecting" but nothing is in flight, so a tap must still be able to retry.
    private var connectionInFlight = true
    // Purchases whose acknowledgement failed, by token → product type. Kept apart from product
    // loading so a late product reply can't hide the failure and its "Try again".
    private val failedAcknowledgements = mutableMapOf<String, String>()
    // Mirrors failedAcknowledgements being non-empty, as state the card can observe.
    private var acknowledgementFailed by mutableStateOf(false)
    // Each restore or purchase update supersedes older restores still in flight.
    private var ownershipGeneration = 0
    // Product replies still outstanding; a tap meanwhile waits instead of reporting a failure.
    private var productQueriesInFlight = 0
    // Product types whose latest owned-purchase answer listed a pending purchase.
    private val pendingByType = mutableMapOf<String, Boolean>()
    private val mainThread = Handler(Looper.getMainLooper())

    /** Billing callbacks may arrive on a library thread; all state here is main-thread state. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainThread.post(block)
    }

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
                // Read the saved value: another screen's prompt (or the after-update offer) may
                // already have used this version's one showing.
                promptShownThisVersion = wasSupportPromptShownForVersion(
                    prefs.getInt(KEY_LAST_PROMPTED_VERSION, 0),
                    BuildConfig.VERSION_CODE,
                ),
                supporterOwned = supporterOwned,
            )
        ) {
            markPromptShownForCurrentVersion()
            promptReason = PromptReason.STYLES
            pendingPattern = pattern
            pendingApply = apply
            showChoice = true
            return
        }
        rememberPattern(pattern)
        apply()
    }

    /**
     * Offers the supporter choice once on the first open after an update, never on a fresh install
     * and never to a supporter. Shares the once-per-version limit with the style prompt.
     */
    suspend fun offerAfterUpdateIfDue() {
        val lastSeen = prefs.getInt(KEY_LAST_SEEN_VERSION, 0)
        val byUpdate = runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.lastUpdateTime > info.firstInstallTime
        }.getOrDefault(false)
        fun decide() = decideSupportOfferAfterUpdate(
            lastSeenVersion = lastSeen,
            installedByUpdate = byUpdate,
            currentVersion = BuildConfig.VERSION_CODE,
            promptShownThisVersion = wasSupportPromptShownForVersion(
                prefs.getInt(KEY_LAST_PROMPTED_VERSION, 0),
                BuildConfig.VERSION_CODE,
            ),
            supporterOwned = supporterOwned,
            ownershipKnown = ownershipKnown,
        )
        if (decide() == UpdateOfferDecision.WAIT) {
            // A supporter who bought on another device is only known once Play answers. The
            // version stays unrecorded meanwhile, so a rotation or an offline start asks again.
            withTimeoutOrNull(OWNERSHIP_WAIT_MS) { snapshotFlow { ownershipKnown }.first { it } }
        }
        when (decide()) {
            UpdateOfferDecision.WAIT -> Unit
            UpdateOfferDecision.SKIP -> prefs.edit { putInt(KEY_LAST_SEEN_VERSION, BuildConfig.VERSION_CODE) }
            // Recorded only once the user answers (see completePendingPattern), so a rotation or
            // process death while the dialog is up offers it again instead of losing it.
            UpdateOfferDecision.SHOW -> {
                promptReason = PromptReason.UPDATE
                showChoice = true
            }
        }
    }

    private fun syncAcknowledgementFailed() {
        acknowledgementFailed = failedAcknowledgements.isNotEmpty()
    }

    private fun noteOwnershipAnswered(productType: String) {
        ownershipAnswered.add(productType)
        if (ownershipAnswered.size == 2) ownershipKnown = true
    }

    private fun rememberPattern(pattern: Pattern) {
        if (seenPatterns.add(pattern.key)) {
            prefs.edit { putStringSet(KEY_SEEN_PATTERNS, seenPatterns.toSet()) }
        }
    }

    private fun continueFree() {
        markPromptShownForCurrentVersion()
        completePendingPattern()
    }

    private fun markPromptShownForCurrentVersion() {
        lastPromptedVersion = BuildConfig.VERSION_CODE
        prefs.edit { putInt(KEY_LAST_PROMPTED_VERSION, BuildConfig.VERSION_CODE) }
    }

    private fun completePendingPattern() {
        if (showChoice && promptReason == PromptReason.UPDATE) {
            markPromptShownForCurrentVersion()
            prefs.edit { putInt(KEY_LAST_SEEN_VERSION, BuildConfig.VERSION_CODE) }
        }
        pendingPattern?.let(::rememberPattern)
        pendingApply?.invoke()
        pendingPattern = null
        pendingApply = null
        showChoice = false
    }

    private fun launchPurchase(details: ProductDetails?, token: String?, tokenRequired: Boolean) {
        if (activity == null || details == null || (tokenRequired && token.isNullOrBlank())) {
            // A tap must always answer (#76). While Google Play is still connecting, the caption
            // already says so; otherwise explain the failure and ask Google Play again.
            Log.w(TAG, "Purchase unavailable: activity=${activity != null} details=${details != null}")
            if (connectionInFlight || productQueriesInFlight > 0) return
            purchaseStatus = PurchaseStatus.ERROR
            if (activity != null) retryProducts()
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply { if (!token.isNullOrBlank()) setOfferToken(token) }
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

    private fun launchMonthlyPurchase() =
        launchPurchase(monthlyDetails, monthlyOfferToken, tokenRequired = true)

    private fun launchLifetimePurchase() =
        launchPurchase(lifetimeDetails, lifetimeOfferToken, tokenRequired = false)

    override fun onBillingSetupFinished(result: BillingResult) = onMain {
        connectionInFlight = false
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            purchaseStatus = PurchaseStatus.ERROR
            Log.w(TAG, "Billing setup failed: ${result.responseCode}")
            return@onMain
        }
        purchaseStatus = PurchaseStatus.READY
        queryProducts()
        restorePurchases()
    }

    override fun onBillingServiceDisconnected() = onMain {
        // PBL 9 reconnects on the next billing call; nothing is in flight until then, so the
        // next tap or "Try again" must be free to make that call.
        connectionInFlight = false
        if (purchaseStatus != PurchaseStatus.PURCHASING) purchaseStatus = PurchaseStatus.CONNECTING
    }

    /**
     * Reconnects or re-queries after a failed or incomplete answer from Google Play. Owned
     * purchases are re-read too, so a purchase whose acknowledgement failed is retried.
     */
    private fun retryProducts() {
        // Never start a second connection while one is in flight.
        if (connectionInFlight || productQueriesInFlight > 0) return
        if (billingClient.isReady) {
            queryProducts()
            restorePurchases()
        } else {
            connectionInFlight = true
            purchaseStatus = PurchaseStatus.CONNECTING
            billingClient.startConnection(this)
        }
    }

    private fun markProductsLoaded() {
        if (purchaseStatus == PurchaseStatus.ERROR && failedAcknowledgements.isEmpty() &&
            lifetimeDetails != null && monthlyDetails != null
        ) {
            purchaseStatus = PurchaseStatus.READY
        }
    }

    private fun queryProducts() {
        productQueriesInFlight = 2
        queryLifetimeProduct()
        queryMonthlyProduct()
    }

    /** Names why Play left a product out (inactive, wrong id, not sold here) in the log. */
    private fun logUnfetched(queryResult: QueryProductDetailsResult) {
        queryResult.unfetchedProductList.forEach {
            Log.w(TAG, "Play did not return ${it.productId} (${it.productType}): status ${it.statusCode}")
        }
    }

    private fun queryLifetimeProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(LIFETIME_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        ) { result, queryResult -> onMain {
            productQueriesInFlight = (productQueriesInFlight - 1).coerceAtLeast(0)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseStatus = PurchaseStatus.ERROR
                Log.w(TAG, "Lifetime product query failed: ${result.responseCode}")
                return@onMain
            }
            logUnfetched(queryResult)
            val details = queryResult.productDetailsList.firstOrNull {
                it.productId == LIFETIME_PRODUCT_ID
            }
            val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
            lifetimeOfferToken = offer?.offerToken
            lifetimePrice = offer?.formattedPrice
            lifetimeDetails = details?.takeIf { offer != null }
            if (details == null || offer == null) {
                purchaseStatus = PurchaseStatus.ERROR
                Log.w(TAG, "Lifetime product missing: details=${details != null} offer=${offer != null}")
            } else markProductsLoaded()
        } }
    }

    private fun queryMonthlyProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(MONTHLY_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        ) { result, queryResult -> onMain {
            productQueriesInFlight = (productQueriesInFlight - 1).coerceAtLeast(0)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseStatus = PurchaseStatus.ERROR
                Log.w(TAG, "Monthly product query failed: ${result.responseCode}")
                return@onMain
            }
            logUnfetched(queryResult)
            val details = queryResult.productDetailsList.firstOrNull {
                it.productId == MONTHLY_PRODUCT_ID
            }
            val offer = details?.subscriptionOfferDetails?.firstOrNull {
                it.basePlanId == MONTHLY_BASE_PLAN_ID
            }
            monthlyOfferToken = offer?.offerToken
            monthlyPrice = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            monthlyDetails = details?.takeIf { offer != null }
            if (details == null || offer == null) {
                purchaseStatus = PurchaseStatus.ERROR
                Log.w(TAG, "Monthly product missing: details=${details != null} offer=${offer != null}")
            } else markProductsLoaded()
        } }
    }

    private fun restorePurchases() {
        val generation = ++ownershipGeneration
        // Ownership is known once both answers of one restore arrive; a stale answer doesn't count.
        ownershipAnswered.clear()
        queryOwnedPurchases(BillingClient.ProductType.INAPP) { purchases ->
            if (generation != ownershipGeneration) return@queryOwnedPurchases
            // Ownership comes from Play's answer as a whole, set once: no revoke-then-grant flicker.
            val owned = purchases.any { it.grants(LIFETIME_PRODUCT_ID) }
            lifetimeOwned = owned
            prefs.edit { putBoolean(KEY_LIFETIME_SUPPORTER, owned) }
            settleAcknowledgements(BillingClient.ProductType.INAPP, purchases)
            noteOwnershipAnswered(BillingClient.ProductType.INAPP)
        }
        queryOwnedPurchases(BillingClient.ProductType.SUBS) { purchases ->
            if (generation != ownershipGeneration) return@queryOwnedPurchases
            val active = purchases.any { it.grants(MONTHLY_PRODUCT_ID) }
            monthlyActive = active
            prefs.edit { putBoolean(KEY_MONTHLY_SUPPORTER, active) }
            settleAcknowledgements(BillingClient.ProductType.SUBS, purchases)
            noteOwnershipAnswered(BillingClient.ProductType.SUBS)
        }
    }

    private fun Purchase.grants(productId: String) =
        productId in products && purchaseState == Purchase.PurchaseState.PURCHASED

    /** Forgets failures for purchases Play no longer lists, then acknowledges what still needs it. */
    private fun settleAcknowledgements(productType: String, purchases: List<Purchase>) {
        val tokens = purchases.mapTo(HashSet()) { it.purchaseToken }
        failedAcknowledgements.entries.removeAll { it.value == productType && it.key !in tokens }
        syncAcknowledgementFailed()
        pendingByType[productType] =
            purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        // A pending payment that was cancelled or declined simply disappears from Play's list.
        if (purchaseStatus == PurchaseStatus.PENDING && pendingByType.values.none { it }) {
            purchaseStatus = PurchaseStatus.READY
        }
        purchases.forEach(::handlePurchase)
        markProductsLoaded()
    }

    private fun queryOwnedPurchases(productType: String, onSuccess: (List<Purchase>) -> Unit) {
        val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                onMain { onSuccess(purchases) }
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) = onMain {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                ownershipGeneration++          // an older restore must not overwrite this purchase
                purchases.orEmpty().forEach(::handlePurchase)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> purchaseStatus = PurchaseStatus.READY
            // Bought earlier (perhaps unacknowledged, or on another device): grant it from Play.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                purchaseStatus = PurchaseStatus.READY
                restorePurchases()
            }
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
        val productType =
            if (grantsLifetime) BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS
        billingClient.acknowledgePurchase(params) { result -> onMain {
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                failedAcknowledgements.remove(purchase.purchaseToken)
                syncAcknowledgementFailed()
                grantSupporterStatus(grantsLifetime, grantsMonthly)
            } else {
                // Play refunds a purchase not acknowledged within three days; keep "Try again".
                failedAcknowledgements[purchase.purchaseToken] = productType
                syncAcknowledgementFailed()
                purchaseStatus = PurchaseStatus.ERROR
                Log.w(TAG, "Could not acknowledge purchase: ${result.responseCode}")
            }
        } }
    }

    private fun grantSupporterStatus(grantsLifetime: Boolean, grantsMonthly: Boolean) {
        val flags = supporterFlagsToSave(grantsLifetime, grantsMonthly)
        if (SupporterFlag.LIFETIME in flags) lifetimeOwned = true
        if (SupporterFlag.MONTHLY in flags) monthlyActive = true
        prefs.edit {
            if (SupporterFlag.LIFETIME in flags) putBoolean(KEY_LIFETIME_SUPPORTER, true)
            if (SupporterFlag.MONTHLY in flags) putBoolean(KEY_MONTHLY_SUPPORTER, true)
        }
        if (failedAcknowledgements.isEmpty()) purchaseStatus = PurchaseStatus.READY
        completePendingPattern()
    }

    @Composable
    fun Content(showCard: Boolean = true) {
        if (showCard) {
            PixelCard(tone = if (supporterOwned) 2 else 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.supporter_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Caption(
                    stringResource(
                        if (supporterOwned) R.string.supporter_badge_body
                        else R.string.supporter_card_body
                    )
                )
                if (!supporterOwned) {
                    PurchaseButtons()
                    TextButton(
                        onClick = ::continueFree,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.supporter_free_summary),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Caption(stringResource(R.string.supporter_terms))
                }
                if (showsPurchaseStatus(supporterOwned, acknowledgementFailed)) {
                    PurchaseStatusText(purchaseStatus, ::retryProducts, acknowledgementFailed)
                }
            }
        }

    }

    /** The supporter choice, drawn once at the app's root for every screen sharing this state. */
    @Composable
    fun Dialogs() {
        if (showChoice) SupportDialog()
    }

    /** The once-per-version supporter choice: eight glowing LEDs over a short thank-you. */
    @Composable
    private fun SupportDialog() {
        val purchasesEnabled = purchaseStatus != PurchaseStatus.PURCHASING
        Dialog(
            onDismissRequest = ::continueFree,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(),
            ) {
                Column {
                    LedHero()
                    Column(
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(
                                if (promptReason == PromptReason.UPDATE) R.string.supporter_prompt_update_title
                                else R.string.supporter_prompt_title
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.supporter_prompt_line),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Button(
                            onClick = ::launchLifetimePurchase,
                            enabled = purchasesEnabled,
                            // Black on a light dialog, white on a dark one.
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(
                                lifetimePrice?.let { stringResource(R.string.supporter_popup_lifetime, it) }
                                    ?: stringResource(R.string.supporter_lifetime)
                            )
                        }
                        OutlinedButton(
                            onClick = ::launchMonthlyPurchase,
                            enabled = purchasesEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(
                                monthlyPrice?.let { stringResource(R.string.supporter_popup_monthly, it) }
                                    ?: stringResource(R.string.supporter_monthly)
                            )
                        }
                        TextButton(onClick = ::continueFree, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.supporter_maybe_later))
                        }
                        PurchaseStatusText(purchaseStatus, ::retryProducts, acknowledgementFailed)
                        Text(
                            stringResource(R.string.supporter_prompt_fine),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PurchaseButtons() {
        val purchasesEnabled = purchaseStatus != PurchaseStatus.PURCHASING
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = ::launchMonthlyPurchase,
                enabled = purchasesEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    monthlyPrice?.let { stringResource(R.string.supporter_monthly_price, it) }
                        ?: stringResource(R.string.supporter_monthly)
                )
            }
            OutlinedButton(
                onClick = ::launchLifetimePurchase,
                enabled = purchasesEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    lifetimePrice?.let { stringResource(R.string.supporter_lifetime_price, it) }
                        ?: stringResource(R.string.supporter_lifetime)
                )
            }
        }
    }
}

/**
 * Eight LEDs in a row, like the camera bar: a rainbow travels across them and each glow breathes
 * with it. With animations removed in Accessibility settings, a still rainbow.
 */
@Composable
private fun LedHero() {
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "ledWave")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LED_WAVE_MS, easing = LinearEasing)),
            label = "ledPhase",
        ).value
    } else 0f
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .background(LED_HERO_BACKGROUND)
    ) {
        val dot = 16.dp.toPx()
        val gap = 9.dp.toPx()
        val glow = 26.dp.toPx()
        val width = HERO_LEDS * dot + (HERO_LEDS - 1) * gap
        val left = (size.width - width) / 2f + dot / 2f
        val y = size.height / 2f
        for (i in 0 until HERO_LEDS) {
            val position = i.toFloat() / HERO_LEDS
            val hue = ((position - phase) * 360f).mod(360f)
            val color = Color.hsv(hue, 0.85f, 1f)
            // Brightness crest moves with the colours; still mode keeps every LED fully lit.
            val level = if (animate) {
                0.55f + 0.45f * (0.5f + 0.5f * sin(2f * PI.toFloat() * (position - phase)))
            } else 1f
            val center = Offset(left + i * (dot + gap), y)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.55f * level), Color.Transparent),
                    center = center,
                    radius = glow,
                ),
                radius = glow,
                center = center,
            )
            drawCircle(color = lerp(Color.White, color, 0.35f + 0.65f * level), radius = dot / 2f, center = center)
        }
    }
}

@Composable
private fun PurchaseStatusText(
    status: PurchaseStatus,
    onRetry: () -> Unit,
    acknowledgementFailed: Boolean = false,
) {
    val message = if (acknowledgementFailed && status == PurchaseStatus.ERROR) {
        R.string.supporter_unconfirmed
    } else when (status) {
        PurchaseStatus.CONNECTING -> R.string.supporter_connecting
        PurchaseStatus.PURCHASING -> R.string.supporter_purchasing
        PurchaseStatus.PENDING -> R.string.supporter_pending
        PurchaseStatus.ERROR -> R.string.supporter_unavailable
        else -> null
    }
    message?.let { Caption(stringResource(it)) }
    if (status == PurchaseStatus.ERROR) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.supporter_retry)) }
    }
}

/** The supporter choice offered once after an update; hosted once, at the app's root. */
@Composable
internal fun SupportPromptOnUpdate() {
    val state = rememberSupportPromptState()
    LaunchedEffect(state) { state.offerAfterUpdateIfDue() }
    state.Dialogs()
}

/**
 * One state per activity, shared by every screen: a single billing connection, and a purchase made
 * from one screen is seen by all of them. Closed when the last screen using it leaves.
 */
private class SharedPromptState(val state: SupportPromptState) {
    var users = 0
}

private val sharedPromptStates = WeakHashMap<Activity, SharedPromptState>()

@Composable
internal fun rememberSupportPromptState(): SupportPromptState {
    val context = LocalContext.current
    val activity = context.findActivity()
    val shared = remember(context) {
        activity?.let { sharedPromptStates[it] }
            ?: SharedPromptState(SupportPromptState(context.applicationContext, activity)).also {
                if (activity != null) sharedPromptStates[activity] = it
            }
    }
    DisposableEffect(shared) {
        shared.users++
        onDispose {
            if (--shared.users == 0) {
                shared.state.close()
                activity?.let { if (sharedPromptStates[it] === shared) sharedPromptStates.remove(it) }
            }
        }
    }
    return shared.state
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
