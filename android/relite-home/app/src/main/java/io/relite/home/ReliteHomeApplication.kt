package io.relite.home

import android.app.Application
import io.relite.home.data.AppRepository
import io.relite.home.data.FileStorage
import io.relite.home.data.WorkspaceRepository
import java.io.File

/**
 * Holds the two long-lived, dependency-free singletons the launcher needs.
 * No analytics, no crash reporter, no account/session state — see master
 * plan section 20.
 */
class ReliteHomeApplication : Application() {

    lateinit var appRepository: AppRepository
        private set

    lateinit var workspaceRepository: WorkspaceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        appRepository = AppRepository(this)
        appRepository.start()
        workspaceRepository = WorkspaceRepository(FileStorage(File(filesDir, "workspace.json")))
    }
}
