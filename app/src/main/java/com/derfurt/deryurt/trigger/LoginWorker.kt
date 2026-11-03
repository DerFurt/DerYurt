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
import com.derfurt.deryurt.data.StatusStore
import com.derfurt.deryurt.net.GsbPortal
import com.derfurt.deryurt.net.NetworkBoundClient
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
        if (network == null) {
            if (manual) store.recordOutcome(LoginOutcome.NO_WIFI, null)
            EventLog.add(ctx, "OUTCOME", LoginOutcome.NO_WIFI.name)
            return
        }

        // Otomatik tetiklemede Android'in bağlantıyı oturtmasına kısa süre tanı.
        if (!manual) delay(2_000L)

        store.running = true
        LoginTileService.requestUpdate(ctx)
        val outcome = try {
            val portal = GsbPortal(NetworkBoundClient.create(network))
            LoginRunner(portal, store).run(credentials, manual, fresh)
        } catch (e: Exception) {
            EventLog.add(ctx, "WORK_ERROR", e.javaClass.simpleName)
            LoginOutcome.PORTAL_ERROR
        }
        EventLog.add(ctx, "OUTCOME", outcome.name)
        handleOutcome(ctx, store, network, outcome, manual)
    }

    private fun handleOutcome(ctx: Context, store: StatusStore, network: Network, outcome: LoginOutcome, manual: Boolean) {
        when (outcome) {
            LoginOutcome.SUCCESS -> {
                store.recordOutcome(outcome, network.toString())
                // Android ağı yeniden doğrulasın: "Ağda oturum açın" bildirimi kalkar, wifi varsayılan ağ olur.
                try {
                    ctx.getSystemService(ConnectivityManager::class.java)?.reportNetworkConnectivity(network, true)
                } catch (_: Exception) {
                }
                Notifier.success(ctx)
            }
            LoginOutcome.ALREADY_ONLINE, LoginOutcome.NOT_GSB -> {
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
                store.recordOutcome(outcome, null)
                Notifier.otherDevice(ctx)
            }
            LoginOutcome.TIMEOUT, LoginOutcome.PORTAL_ERROR -> {
                store.recordOutcome(outcome, null)
                if (manual) Notifier.slow(ctx)
            }
            LoginOutcome.NO_WIFI, LoginOutcome.NO_CREDENTIALS,
            LoginOutcome.SKIPPED_LOCKED, LoginOutcome.SKIPPED_BACKOFF -> Unit
        }
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
