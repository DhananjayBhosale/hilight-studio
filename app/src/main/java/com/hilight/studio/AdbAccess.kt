package com.hilight.studio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AdbAccessState(val label: String) {
    UNKNOWN("checking"),
    LOCAL_NETWORK_OFF("needs local network"),
    DEVELOPER_OFF("developer options off"),
    WIRELESS_OFF("wireless debugging off"),
    NEEDS_PAIRING("not paired"),
    WORKING("connecting"),
    READY("connected"),
    FAILED("failed"),
}

data class PairingTarget(val host: String, val port: Int)

object AdbAccess {
    private const val TAG = "HiLightAdb"
    private const val PREFS = "hilight_adb"
    private const val KEY_PAIRED = "paired"
    private const val KEY_FILE = "adb_key.pk8"
    private const val CERT_FILE = "adb_cert.der"
    private const val DISCOVERY_MS = 20_000L
    private const val SHELL_WAIT_MS = 8_000L
    private const val HELPER_WAIT_MS = 10_000L
    private const val RETRY_BASE_MS = 30_000L
    private const val RETRY_CEILING_MS = 900_000L

    private const val HELPER_LOG_COMMAND = "tail -5 /data/local/tmp/hilight.log"

    private const val START_COMMAND =
        "K=com.hilight.core.Adb; J=com.hilight.studio:hi; " +
            "pkill -f \"${'$'}{K}Helper|${'$'}{J}light\"; " +
            "sleep 1; " +
            "S=${'$'}(command -v setsid); " +
            "CP=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2); " +
            "CLASSPATH=${'$'}CP nohup ${'$'}S app_process / \"${'$'}{K}Helper\" " +
            "> /data/local/tmp/hilight.log 2>&1 < /dev/null & " +
            "P=${'$'}!; sleep 2; " +
            "kill -0 ${'$'}P 2>/dev/null && echo detached || echo \"renderer exited early\""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    private val _state = MutableStateFlow(AdbAccessState.UNKNOWN)
    val state: StateFlow<AdbAccessState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<String?>(null)
    val detail: StateFlow<String?> = _detail.asStateFlow()

    @Volatile
    private var manager: AbsAdbConnectionManager? = null

    @Volatile
    private var lastAttemptAt = 0L

    @Volatile
    private var failures = 0

    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun localNetworkGranted(ctx: Context): Boolean =
        ctx.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun developerOptionsEnabled(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(
            ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
    }.getOrDefault(false)

    fun wirelessDebuggingEnabled(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)

    fun paired(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PAIRED, false) && keyFile(ctx).exists()

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        if (_state.value == AdbAccessState.WORKING) return
        _state.value = when {
            Bridge.readStatus(app).alive -> AdbAccessState.READY
            !localNetworkGranted(app) -> AdbAccessState.LOCAL_NETWORK_OFF
            !developerOptionsEnabled(app) -> AdbAccessState.DEVELOPER_OFF
            !wirelessDebuggingEnabled(app) -> AdbAccessState.WIRELESS_OFF
            !paired(app) -> AdbAccessState.NEEDS_PAIRING
            _state.value == AdbAccessState.FAILED -> AdbAccessState.FAILED
            else -> AdbAccessState.UNKNOWN
        }
    }

    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        if (Bridge.readStatus(app).alive) {
            settle(AdbAccessState.READY, null)
            return
        }
        if (!paired(app) || !wirelessDebuggingEnabled(app) || !localNetworkGranted(app)) {
            refresh(app)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastAttemptAt != 0L && now - lastAttemptAt < backoffMs()) return
        lastAttemptAt = now
        scope.launch { connect(app) }
    }

    suspend fun retry(ctx: Context): Boolean {
        failures = 0
        lastAttemptAt = 0
        return connect(ctx)
    }

    private fun backoffMs(): Long {
        val steps = failures.coerceIn(0, 5)
        return (RETRY_BASE_MS shl steps).coerceAtMost(RETRY_CEILING_MS)
    }

    suspend fun connect(ctx: Context): Boolean {
        val app = ctx.applicationContext
        return gate.withLock {
            when {
                Bridge.readStatus(app).alive -> settle(AdbAccessState.READY, null)
                !localNetworkGranted(app) -> settle(AdbAccessState.LOCAL_NETWORK_OFF, null)
                !wirelessDebuggingEnabled(app) -> settle(AdbAccessState.WIRELESS_OFF, null)
                !paired(app) -> settle(AdbAccessState.NEEDS_PAIRING, null)
                else -> {
                    _state.value = AdbAccessState.WORKING
                    withContext(Dispatchers.IO) { attach(app) }
                }
            }
        }
    }

    suspend fun pairAndConnect(ctx: Context, target: PairingTarget, code: String): Boolean {
        val app = ctx.applicationContext
        return gate.withLock {
            _state.value = AdbAccessState.WORKING
            _detail.value = null
            withContext(Dispatchers.IO) {
                val connection = manager(app) ?: return@withContext fail("no ADB key available")
                val paired = runCatching { connection.pair(target.host, target.port, code) }
                if (paired.isFailure) {
                    Log.w(TAG, "pairing failed", paired.exceptionOrNull())
                    return@withContext fail(
                        readable(paired.exceptionOrNull()) ?: "pairing was refused"
                    )
                }
                prefs(app).edit().putBoolean(KEY_PAIRED, true).apply()
                attach(app)
            }
        }
    }

    suspend fun findPairingTarget(ctx: Context, timeoutMs: Long = DISCOVERY_MS): PairingTarget? =
        discover(ctx.applicationContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)

    fun forget(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            gate.withLock {
                runCatching { manager?.disconnect() }
                manager = null
                keyFile(app).delete()
                certFile(app).delete()
                prefs(app).edit().putBoolean(KEY_PAIRED, false).apply()
                _state.value = AdbAccessState.UNKNOWN
                refresh(app)
            }
        }
    }

    fun openWirelessDebugging(ctx: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            val opened = runCatching {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
    }

    private suspend fun attach(app: Context): Boolean {
        val connection = manager(app) ?: return fail("no ADB key available")
        Bridge.ensureFiles(app)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            Log.i(TAG, "looking for the debug daemon")
            connection.connectTls(app, DISCOVERY_MS)
            Log.i(TAG, "connected in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (e: AdbPairingRequiredException) {
            Log.w(TAG, "daemon asked for pairing", e)
            prefs(app).edit().putBoolean(KEY_PAIRED, false).apply()
            return settle(AdbAccessState.NEEDS_PAIRING, "this phone no longer trusts the app key")
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed", t)
            return fail(readable(t) ?: "could not reach the on-device ADB daemon")
        }
        return try {
            val start = shell(connection, START_COMMAND)
            Log.i(TAG, "renderer launched${suffix(start)}")
            if (awaitHelper(app)) {
                settle(AdbAccessState.READY, null)
            } else {
                val tail = shell(connection, HELPER_LOG_COMMAND).trim()
                Log.w(TAG, "renderer never reported back; log tail: ${tail.ifEmpty { "(empty)" }}")
                fail(tail.lines().lastOrNull { it.isNotBlank() } ?: "the renderer did not start")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "helper launch failed", t)
            fail(readable(t) ?: "could not launch the renderer")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private suspend fun awaitHelper(app: Context): Boolean {
        val deadline = SystemClock.elapsedRealtime() + HELPER_WAIT_MS
        var polls = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            polls++
            if (Bridge.readStatus(app).alive) {
                Log.i(TAG, "renderer reported back after $polls polls")
                return true
            }
            delay(400)
        }
        Log.w(TAG, "no heartbeat after $polls polls over ${HELPER_WAIT_MS}ms")
        return false
    }

    private fun suffix(output: String): String =
        output.trim().let { if (it.isEmpty()) "" else ": $it" }

    private fun shell(connection: AbsAdbConnectionManager, command: String): String {
        val out = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline = SystemClock.elapsedRealtime() + SHELL_WAIT_MS
        try {
            connection.openStream("shell:" + command).use { stream ->
                while (SystemClock.elapsedRealtime() < deadline && !stream.isClosed) {
                    if (stream.available() <= 0) {
                        Thread.sleep(80)
                        continue
                    }
                    val read = stream.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.append(String(buffer, 0, read, Charsets.UTF_8))
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "shell stream ended: ${t.message}")
        }
        return out.toString()
    }

    private suspend fun discover(
        app: Context,
        serviceType: String,
        timeoutMs: Long,
    ): PairingTarget? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val holder = arrayOfNulls<AdbMdns>(1)
            val mdns = AdbMdns(app, serviceType) { host: InetAddress?, port: Int ->
                val address = host?.hostAddress
                if (address != null && port > 0 && done.compareAndSet(false, true)) {
                    runCatching { holder[0]?.stop() }
                    if (cont.isActive) cont.resumeWith(Result.success(PairingTarget(address, port)))
                }
            }
            holder[0] = mdns
            cont.invokeOnCancellation { runCatching { mdns.stop() } }
            runCatching { mdns.start() }.onFailure {
                Log.w(TAG, "mDNS discovery could not start", it)
                if (done.compareAndSet(false, true) && cont.isActive) {
                    cont.resumeWith(Result.success(null))
                }
            }
        }
    }

    private fun settle(next: AdbAccessState, why: String?): Boolean {
        _state.value = next
        _detail.value = why
        val ready = next == AdbAccessState.READY
        if (ready) {
            failures = 0
        } else if (next == AdbAccessState.FAILED) {
            failures++
            Log.w(TAG, "attempt $failures failed, next try in ${backoffMs() / 1000}s")
        }
        return ready
    }

    private fun fail(why: String): Boolean = settle(AdbAccessState.FAILED, why)

    private fun readable(t: Throwable?): String? =
        t?.message?.takeIf { it.isNotBlank() } ?: t?.javaClass?.simpleName

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyFile(ctx: Context) = File(ctx.applicationContext.filesDir, KEY_FILE)

    private fun certFile(ctx: Context) = File(ctx.applicationContext.filesDir, CERT_FILE)

    @Synchronized
    private fun manager(app: Context): AbsAdbConnectionManager? {
        manager?.let { return it }
        return runCatching { Manager(Credentials(app, keyFile(app), certFile(app))) }
            .onFailure { Log.w(TAG, "could not build the ADB identity", it) }
            .getOrNull()
            ?.also { manager = it }
    }

    private class Manager(private val credentials: Credentials) : AbsAdbConnectionManager() {
        init {
            api = Build.VERSION.SDK_INT
            setTimeout(20, TimeUnit.SECONDS)
        }

        override fun getPrivateKey(): PrivateKey = credentials.privateKey

        override fun getCertificate(): Certificate = credentials.certificate

        override fun getDeviceName(): String = "HiLight Studio"
    }

    private class Credentials(app: Context, keyFile: File, certFile: File) {
        val privateKey: PrivateKey
        val certificate: Certificate

        init {
            val restored = restore(keyFile, certFile)
            if (restored != null) {
                privateKey = restored.first
                certificate = restored.second
            } else {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(KEY_BITS, SecureRandom())
                val pair = generator.generateKeyPair()
                privateKey = pair.private
                certificate = selfSigned(pair, app.packageName)
                keyFile.writeBytes(privateKey.encoded)
                certFile.writeBytes(certificate.encoded)
            }
        }

        private fun restore(keyFile: File, certFile: File): Pair<PrivateKey, Certificate>? {
            if (!keyFile.exists() || !certFile.exists()) return null
            return runCatching {
                val key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                key to cert
            }.getOrNull()
        }

        private fun selfSigned(pair: KeyPair, subject: String): Certificate {
            val now = System.currentTimeMillis()
            val name = X500Name("CN=$subject")
            val algorithm =
                AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val generator = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger.valueOf(now)))
                setIssuer(name)
                setSubject(name)
                setStartDate(Time(Date(now - DAY_MS)))
                setEndDate(Time(Date(now + DECADE_MS)))
                setSignature(algorithm)
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
            }
            val tbs = generator.generateTBSCertificate()
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(pair.private)
            signer.update(tbs.getEncoded(ASN1Encoding.DER))
            val body = DERSequence(
                ASN1EncodableVector().apply {
                    add(tbs)
                    add(algorithm)
                    add(DERBitString(signer.sign()))
                }
            )
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(body.getEncoded(ASN1Encoding.DER)))
        }

        private companion object {
            const val KEY_BITS = 2048
            const val DAY_MS = 86_400_000L
            const val DECADE_MS = 10L * 365L * 86_400_000L
        }
    }
}
