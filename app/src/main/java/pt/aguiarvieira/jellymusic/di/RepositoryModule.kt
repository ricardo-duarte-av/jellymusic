package pt.aguiarvieira.jellymusic.di

import pt.aguiarvieira.jellymusic.data.auth.AuthRepositoryImpl
import pt.aguiarvieira.jellymusic.data.repository.LibraryRepositoryImpl
import pt.aguiarvieira.jellymusic.data.repository.MusicRepositoryImpl
import pt.aguiarvieira.jellymusic.domain.repository.AuthRepository
import pt.aguiarvieira.jellymusic.domain.repository.LibraryRepository
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindMusicRepository(impl: MusicRepositoryImpl): MusicRepository
}
