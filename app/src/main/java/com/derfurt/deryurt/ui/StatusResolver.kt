package com.derfurt.deryurt.ui

import android.content.Context
import com.derfurt.deryurt.core.LoginOutcome
import com.derfurt.deryurt.data.CredentialStore
import com.derfurt.deryurt.data.StatusStore
import com.derfurt.deryurt.trigger.NetworkWatch

/** Ana ekran durum kartının ve kutucuğun gösterdiği durum. */
enum class UiState { NO_CREDENTIALS, RUNNING, ONLINE, WRONG_LOCKED, WRONG_ONCE, CAPTCHA, OTHER_DEVICE, OTHER_ACCOUNT, SLOW, AWAY }

object StatusResolver {
    fun resolve(context: Context): UiState {
        if (!CredentialStore(context).hasCredentials()) return UiState.NO_CREDENTIALS
        val store = StatusStore(context)
        if (store.running) return UiState.RUNNING

        val guard = store.loadGuard()
        if (guard.captchaBlocked) return UiState.CAPTCHA
        if (guard.lockedOut) return UiState.WRONG_LOCKED

        val wifi = NetworkWatch.currentWifi(context) ?: return UiState.AWAY
        // Son başarılı giriş bu wifi bağlantısında yapıldıysa GSB'deyiz.
        val onGsb = wifi.toString() == store.lastGsbNetwork
        val last = store.lastOutcome
        if (onGsb && (NetworkWatch.isValidated(context, wifi) ||
                last == LoginOutcome.SUCCESS || last == LoginOutcome.ALREADY_ONLINE)
        ) {
            // İnternet açık ama portal oturumu kayıtlı hesaptan farklı bir hesapla açık olabilir.
            val session = store.sessionFor(wifi.toString())
            val own = store.ownAccountName
            if (session != null && own != null && !StatusStore.sameAccount(session.accountName, own)) {
                return UiState.OTHER_ACCOUNT
            }
            return UiState.ONLINE
        }

        return when (last) {
            LoginOutcome.WRONG_CREDENTIALS -> UiState.WRONG_ONCE
            LoginOutcome.OTHER_DEVICE -> UiState.OTHER_DEVICE
            LoginOutcome.TIMEOUT, LoginOutcome.PORTAL_ERROR -> UiState.SLOW
            else -> UiState.AWAY
        }
    }
}
