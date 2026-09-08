package kr.mooner510.backup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject

class GoogleBackupAuth(private val serverClientId: String) {
    @Volatile
    private var cached: CachedToken? = null

    val configured: Boolean get() = serverClientId.isNotBlank()

    suspend fun idToken(context: Context): String {
        require(configured) { "Google Server Client ID가 설정되지 않았습니다." }
        val now = System.currentTimeMillis()
        cached?.takeIf { it.expiresAtMillis - now > 60_000L }?.let { return it.value }

        val activity = context.findActivity()
            ?: error("Google 로그인은 Activity 화면에서 실행해야 합니다.")
        val manager = CredentialManager.create(activity)
        val credential = try {
            requestCredential(manager, activity, authorizedOnly = true)
        } catch (_: NoCredentialException) {
            requestCredential(manager, activity, authorizedOnly = false)
        }
        require(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Google ID Token을 가져오지 못했습니다." }
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        cached = CachedToken(token, tokenExpiryMillis(token))
        return token
    }

    fun clearSession() {
        cached = null
    }

    private suspend fun requestCredential(
        manager: CredentialManager,
        activity: Activity,
        authorizedOnly: Boolean,
    ) = manager.getCredential(
        context = activity,
        request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(authorizedOnly)
                    .setServerClientId(serverClientId)
                    .setAutoSelectEnabled(authorizedOnly)
                    .build(),
            )
            .build(),
    ).credential

    private data class CachedToken(val value: String, val expiresAtMillis: Long)

    private fun tokenExpiryMillis(token: String): Long = runCatching {
        val payload = token.split('.')[1]
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(decoded, Charsets.UTF_8)).getLong("exp") * 1000L
    }.getOrDefault(0L)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
