package tv.own.owntv.rooms

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import tv.own.owntv.BuildConfig

object SupabaseClientProvider {
    val client: SupabaseClient by lazy {
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        require(url.isNotBlank() && url != "https://placeholder.supabase.co") { "SUPABASE_URL is not configured" }
        require(key.isNotBlank() && key != "placeholder-key") { "SUPABASE_ANON_KEY is not configured" }
        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}
