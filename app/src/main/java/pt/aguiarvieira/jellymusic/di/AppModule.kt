package pt.aguiarvieira.jellymusic.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJellyfin(@ApplicationContext context: Context): Jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "JellyMusic", version = "0.1.0")
        this.context = context
    }
}
