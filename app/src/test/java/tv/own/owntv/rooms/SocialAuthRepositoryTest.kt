package tv.own.owntv.rooms

import io.github.jan.supabase.auth.user.UserInfo
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import tv.own.owntv.core.isolation.IsolatedTestBuild
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs in every variant. Customer builds sign in anonymously at most once; the isolated test APK
 * ([IsolatedTestBuild]) must refuse every call and never sign in, whatever the current user state.
 */
class SocialAuthRepositoryTest {

    private val dummyUser: UserInfo = mockk(relaxed = true)

    private fun assertRefused(result: Result<UserInfo>) {
        assertTrue(result.isFailure)
        assertEquals(IsolatedTestBuild.REFUSED, result.exceptionOrNull()?.message)
    }

    @Test
    fun `existing user results in zero sign-in calls`() = runBlocking {
        val signIns = AtomicInteger(0)
        var currentUserState: UserInfo? = dummyUser

        val repo = SupabaseSocialAuthRepository(
            clientProvider = { mockk(relaxed = true) },
            signInBlock = { signIns.incrementAndGet() },
            userProvider = { currentUserState }
        )

        val result = repo.ensureTvAuthenticated()
        if (IsolatedTestBuild.active) assertRefused(result) else assertTrue(result.isSuccess)
        assertEquals(0, signIns.get())
    }

    @Test
    fun `missing user results in exactly one sign-in call`() = runBlocking {
        val signIns = AtomicInteger(0)
        var currentUserState: UserInfo? = null

        val repo = SupabaseSocialAuthRepository(
            clientProvider = { mockk(relaxed = true) },
            signInBlock = {
                signIns.incrementAndGet()
                currentUserState = dummyUser
            },
            userProvider = { currentUserState }
        )

        val result = repo.ensureTvAuthenticated()
        if (IsolatedTestBuild.active) {
            // No backend user is ever created from the isolated build.
            assertRefused(result)
            assertEquals(0, signIns.get())
            assertNull(currentUserState)
        } else {
            assertTrue(result.isSuccess)
            assertEquals(1, signIns.get())
        }
    }

    @Test
    fun `five simultaneous ensureTvAuthenticated calls execute exactly ONE signInAnonymously call`() = runBlocking {
        val signIns = AtomicInteger(0)
        var currentUserState: UserInfo? = null

        val repo = SupabaseSocialAuthRepository(
            clientProvider = { mockk(relaxed = true) },
            signInBlock = {
                kotlinx.coroutines.delay(100) // simulate network latency
                signIns.incrementAndGet()
                currentUserState = dummyUser
            },
            userProvider = { currentUserState }
        )

        val deferreds = (1..5).map {
            async(Dispatchers.Default) {
                repo.ensureTvAuthenticated()
            }
        }
        val results = deferreds.awaitAll()

        if (IsolatedTestBuild.active) {
            results.forEach(::assertRefused)
            assertEquals(0, signIns.get())
        } else {
            for (res in results) {
                assertTrue(res.isSuccess)
            }
            assertEquals(1, signIns.get())
        }
    }
}
