package tv.own.owntv.rooms

import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SocialAuthRepository {
    val currentUser: UserInfo?
    val currentProfile: StateFlow<SocialProfile?>
    val isAuthenticated: Boolean

    suspend fun refreshProfile(): Result<SocialProfile?>
    suspend fun signOut(): Result<Unit>
    suspend fun ensureTvAuthenticated(): Result<UserInfo>
    suspend fun currentUserId(): String?
    suspend fun isAnonymous(): Boolean
    suspend fun signOutDeviceSession(): Result<Unit>
}

class SupabaseSocialAuthRepository(
    private val clientProvider: () -> io.github.jan.supabase.SupabaseClient = { SupabaseClientProvider.client },
    private val signInBlock: suspend (io.github.jan.supabase.SupabaseClient) -> Unit = { it.auth.signInAnonymously() },
    private val userProvider: (io.github.jan.supabase.SupabaseClient) -> UserInfo? = { it.auth.currentUserOrNull() }
) : SocialAuthRepository {
    private val client: io.github.jan.supabase.SupabaseClient by lazy { clientProvider() }
    private val authMutex = Mutex()
    private var cooldownUntilMs = 0L

    override val currentUser: UserInfo?
        get() = userProvider(client)

    override val isAuthenticated: Boolean
        get() = currentUser != null

    private val _currentProfile = MutableStateFlow<SocialProfile?>(null)
    override val currentProfile: StateFlow<SocialProfile?> = _currentProfile.asStateFlow()

    override suspend fun refreshProfile(): Result<SocialProfile?> {
        return runCatching {
            val user = currentUser ?: return@runCatching null
            val profileDto = client.postgrest["profiles"]
                .select {
                    filter { eq("id", user.id) }
                }
                .decodeSingleOrNull<SocialProfileDto>()

            val profile = profileDto?.toDomain()
            _currentProfile.value = profile
            profile
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return runCatching {
            client.auth.signOut()
            _currentProfile.value = null
        }
    }

    override suspend fun ensureTvAuthenticated(): Result<UserInfo> {
        val existing = userProvider(client)
        if (existing != null) {
            Log.d("SocialAuth", "ROOM_AUTH_REUSE: existing session valid userId=${existing.id}")
            return Result.success(existing)
        }

        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) {
            val remainingSec = (cooldownUntilMs - now) / 1000
            Log.w("SocialAuth", "ROOM_AUTH_RATE_LIMITED: in cooldown for ${remainingSec}s")
            return Result.failure(IllegalStateException("Authentication rate limited. Cooldown active for ${remainingSec}s."))
        }

        return authMutex.withLock {
            val lockedExisting = userProvider(client)
            if (lockedExisting != null) {
                Log.d("SocialAuth", "ROOM_AUTH_REUSE: acquired lock, session already established userId=${lockedExisting.id}")
                return@withLock Result.success(lockedExisting)
            }

            val recheckNow = System.currentTimeMillis()
            if (recheckNow < cooldownUntilMs) {
                val remainingSec = (cooldownUntilMs - recheckNow) / 1000
                return@withLock Result.failure(IllegalStateException("Authentication rate limited. Cooldown active for ${remainingSec}s."))
            }

            Log.d("SocialAuth", "ROOM_AUTH_START: attempting anonymous sign-in")
            try {
                signInBlock(client)
                val user = userProvider(client) ?: throw IllegalStateException("Failed to sign in anonymously")
                Log.d("SocialAuth", "ROOM_AUTH_READY: anonymous sign-in successful userId=${user.id}")
                Result.success(user)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val isRateLimit = msg.contains("429") || msg.contains("rate limit") || msg.contains("over_request_rate_limit") || msg.contains("over_email_send_rate_limit")
                if (isRateLimit) {
                    cooldownUntilMs = System.currentTimeMillis() + 60_000L // 60s cooldown
                    Log.e("SocialAuth", "ROOM_AUTH_RATE_LIMITED: hit rate limit (429), set cooldown 60s: $msg", e)
                } else {
                    Log.e("SocialAuth", "ROOM_AUTH_ERROR: anonymous sign-in failed: $msg", e)
                }
                Result.failure(e)
            }
        }
    }

    override suspend fun currentUserId(): String? {
        return userProvider(client)?.id
    }

    override suspend fun isAnonymous(): Boolean {
        return userProvider(client)?.isAnonymous == true
    }

    override suspend fun signOutDeviceSession(): Result<Unit> {
        return signOut()
    }
}
