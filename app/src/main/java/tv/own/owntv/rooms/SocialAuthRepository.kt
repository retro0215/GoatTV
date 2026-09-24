package tv.own.owntv.rooms

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

class SupabaseSocialAuthRepository : SocialAuthRepository {
    private val client = SupabaseClientProvider.client

    override val currentUser: UserInfo?
        get() = client.auth.currentUserOrNull()

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
        return runCatching {
            val existing = client.auth.currentUserOrNull()
            if (existing != null) {
                existing
            } else {
                client.auth.signInAnonymously()
                client.auth.currentUserOrNull() ?: throw IllegalStateException("Failed to sign in anonymously")
            }
        }
    }

    override suspend fun currentUserId(): String? {
        return client.auth.currentUserOrNull()?.id
    }

    override suspend fun isAnonymous(): Boolean {
        return client.auth.currentUserOrNull()?.isAnonymous == true
    }

    override suspend fun signOutDeviceSession(): Result<Unit> {
        return signOut()
    }
}
