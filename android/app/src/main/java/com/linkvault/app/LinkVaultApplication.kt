package com.linkvault.app

import android.app.Application
import com.linkvault.app.auth.AccountClient

class LinkVaultApplication : Application() {
    val accountClient: AccountClient by lazy {
        AccountClient(
            context = this,
            url = BuildConfig.SUPABASE_URL,
            key = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            debug = BuildConfig.DEBUG,
        )
    }
}
