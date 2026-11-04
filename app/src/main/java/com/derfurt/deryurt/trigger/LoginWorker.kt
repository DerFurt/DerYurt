package com.derfurt.deryurt.trigger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.derfurt.deryurt.core.LoginOutcome
import com.derfurt.deryurt.core.LoginRunner
import com.derfurt.deryurt.data.CredentialStore
import com.derfurt.deryurt.data.EventLog
import com.derfurt.deryurt.data.SavedSession
import com.derfurt.deryurt.data.StatusStore
import com.derfurt.deryurt.net.GsbPortal
import com.derfurt.deryurt.net.NetworkBoundClient
import com.derfurt.deryurt.net.PortalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Wifi ağı üzerinden bir giriş çalıştırması yapar, sonucu kaydeder ve bildirir. */
class LoginWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = StatusStore(applicationContext)
        try {
            run(store)
        } finally {
            // Ekran "Giriş yapılıyor…"da takılı kalmasın.
            store.running = false
            LoginTileService.requestUpdate(applicationContext)
        }
        Result.success()
    }

    private suspend fun run(store: StatusStore) {
        val ctx = applicationContext
        val manual = inputData.getBoolean(LoginWork.KEY_MANUAL, false)
        val fresh = inputData.getBoolean(LoginWork.KEY_FRESH, false)
        EventLog.add(ctx, "WORK_START", inputData.getString(LoginWork.KEY_SOURCE).orEmpty())

        if (!manual && !store.autoLogin) return
        val credentials = CredentialStore(ctx).load() ?: return

        val network = waitForWifi(if (manual) 3_000L else 10_000L)
        // Wifi yokken uyandırma kaydını yeniden kur (15 dk'lık kontrol de buradan geçer). Bkz. NetworkWatch.arm.
        if (network == null) NetworkWatch.arm(ctx, force = true)
        if (network == null) {
            if (manual) store.recordOutcome(LoginOutcome.NO_WIFI, null)
            EventLog.add(ctx, "OUTCOME", LoginOutcome.NO_WIFI.name)
            return
        }

        // Otomatik tetiklemede Android'in bağlantıyı oturtmasına kısa süre tanı.
        if (!manual) delay(2_000L)

        // Diğer cihazı kapatma: butonla istendiyse her zaman; ayarla otomatikse bu bağlantıda en fazla bir kez
        // (diğer cihaz oturumu geri alırsa iki cihaz birbirini atmasın).
        val netId = network.toString()
        val kickRequested = inputData.getBoolean(LoginWork.KEY_KICK, false)
        val autoKickUsedHere = store.lastAutoKickNetwork == netId
        val autoKick = !kickRequested && store.autoKickOther && !autoKickUsedHere
        val kick = kickRequested || autoKick

        // Uygulama açılışındaki sessiz kontrol (çoğu zaman ev wifi'si) ekranda "Giriş yapılıyor…" göstermesin.
        if (inputData.getString(LoginWork.KEY_SOURCE) != "app_open") {
            store.running = true
            LoginTileService.requestUpdate(ctx)
        }
        val portal = GsbPortal(NetworkBoundClient.create(network))
        val outcome = try {
            LoginRunner(portal, store).run(credentials, manual, fresh, kick,
                endCurrentSession = manual && inputData.getBoolean(LoginWork.KEY_SWITCH, false))
        } catch (e: Exception) {
            EventLog.add(ctx, "WORK_ERROR", e.javaClass.simpleName)
            LoginOutcome.PORTAL_ERROR
        }
        if (autoKick && portal.kickedOther) store.lastAutoKickNetwork = netId
        EventLog.add(ctx, "OUTCOME", outcome.name + if (portal.kickedOther) " kicked" else "")
        handleOutcome(ctx, store, network, portal, outcome, manual,
            autoKickBlocked = store.autoKickOther && !kickRequested && autoKickUsedHere)
    }

    private fun handleOutcome(
        ctx: Context,
        store: StatusStore,
        network: Network,
        portal: GsbPortal,
        outcome: LoginOutcome,
        manual: Boolean,
        autoKickBlocked: Boolean,
    ) {
        if (outcome != LoginOutcome.OTHER_DEVICE) store.otherSessionSince = null
        when (outcome) {
            LoginOutcome.SUCCESS -> {
                store.recordOutcome(outcome, network.toString())
                // Android ağı yeniden doğrulasın: "Ağda oturum açın" bildirimi kalkar, wifi varsayılan ağ olur.
                try {
                    ctx.getSystemService(ConnectivityManager::class.java)?.reportNetworkConnectivity(network, true)
                } catch (_: Exception) {
                }
                Notifier.success(ctx, kickedOther = portal.kickedOther)
                // Kendi bilgilerimizle girdik: portalda görünen ad kayıtlı hesabın adıdır.
                val session = portal.probe().session
                if (session != null) {
                    store.ownAccountName = session.accountName
                    saveSession(store, network, session)
                }
                EventLog.add(ctx, "SESSION", if (session != null) "own" else "unknown")
            }
            LoginOutcome.ALREADY_ONLINE -> {
                // Portal oturumu hatırladıysa wifi'ye bağlanınca internet kendiliğinden açılır ve giriş gerekmez.
                // Bu wifi'nin GSB olduğu (şifre göndermeden) portala bakılarak doğrulanır; yoksa ekran
                // "GSB wifi'sinde değilsin" der. Ev wifi'sinde portal çözülmez, işaretlenmez.
                // Aynı bakışta oturumun hangi hesapla açık olduğu ve kota da okunur.
                val netId = network.toString()
                val probe = portal.probe()
                if (probe.reachable) {
                    if (store.lastGsbNetwork != netId) {
                        store.markGsbNetwork(netId)
                        EventLog.add(ctx, "GSB_REMEMBERED")
                    }
                    val session = probe.session
                    if (session != null) saveSession(store, network, session) else store.clearSession()
                    val own = store.ownAccountName
                    EventLog.add(ctx, "SESSION", when {
                        session == null -> "unknown"
                        own == null -> "unverified"
                        StatusStore.sameAccount(session.accountName, own) -> "own"
                        else -> "other_account"
                    })
                }
                store.recordOutcome(outcome, null)
                Notifier.cancelResult(ctx)
            }
            LoginOutcome.NOT_GSB -> {
                store.recordOutcome(outcome, null)
                Notifier.cancelResult(ctx)
            }
            LoginOutcome.WRONG_CREDENTIALS -> {
                store.recordOutcome(outcome, null)
                Notifier.failure(ctx, locked = store.loadGuard().lockedOut)
            }
            LoginOutcome.CAPTCHA -> {
                store.recordOutcome(outcome, null)
                Notifier.captcha(ctx)
            }
            LoginOutcome.OTHER_DEVICE -> {
                store.otherSessionSince = portal.otherSession?.startClock
                store.recordOutcome(outcome, null)
                Notifier.otherDevice(ctx, since = store.otherSessionSince,
                    retaken = autoKickBlocked, kickFailed = portal.kickedOther)
            }
            LoginOutcome.TIMEOUT, LoginOutcome.PORTAL_ERROR -> {
                store.recordOutcome(outcome, null)
                if (manual) Notifier.slow(ctx)
            }
            LoginOutcome.NO_WIFI, LoginOutcome.NO_CREDENTIALS,
            LoginOutcome.SKIPPED_LOCKED, LoginOutcome.SKIPPED_BACKOFF -> Unit
        }
    }

    private fun saveSession(store: StatusStore, network: Network, s: PortalSession) {
        store.saveSession(network.toString(), SavedSession(
            accountName = s.accountName,
            quotaTotalMb = s.quota?.totalMb?.toFloat(),
            quotaRemainingMb = s.quota?.remainingMb?.toFloat(),
            quotaExpires = s.quota?.expires,
        ))
    }

    private suspend fun waitForWifi(timeoutMs: Long): Network? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            NetworkWatch.currentWifi(applicationContext)?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            delay(500L)
        }
    }

    /** Android 11 ve altında hızlandırılmış iş, kısa bir ön plan bildirimiyle çalışır. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifier.ID_WORKING, Notifier.working(applicationContext))
}
