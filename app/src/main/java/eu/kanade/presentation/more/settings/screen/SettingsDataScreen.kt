package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.i18n.stringResource
import tachiyomi.core.util.lang.launchNonCancellable
import tachiyomi.core.util.lang.withUIContext
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDataScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun getPreferences(): List<Preference> {
        val backupPreferences = Injekt.get<BackupPreferences>()
        val storagePreferences = Injekt.get<StoragePreferences>()

        return listOf(
            getStorageLocationPref(storagePreferences = storagePreferences),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)),

            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getDataGroup(),
        )
    }

    @Composable
    fun storageLocationPicker(
        storageDirPref: tachiyomi.core.preference.Preference<String>,
    ): ManagedActivityResultLauncher<Uri?, Uri?> {
        val context = LocalContext.current

        return rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                context.contentResolver.takePersistableUriPermission(uri, flags)

                UniFile.fromUri(context, uri)?.let {
                    storageDirPref.set(it.uri.toString())
                }
            }
        }
    }

    @Composable
    fun storageLocationText(
        storageDirPref: tachiyomi.core.preference.Preference<String>,
    ): String {
        val context = LocalContext.current
        val storageDir by storageDirPref.collectAsState()

        if (storageDir == storageDirPref.defaultValue()) {
            return stringResource(MR.strings.no_location_set)
        }

        return remember(storageDir) {
            val file = UniFile.fromUri(context, storageDir.toUri())
            file?.filePath ?: file?.uri?.toString()
        } ?: stringResource(MR.strings.invalid_location, storageDir)
    }

    @Composable
    private fun getStorageLocationPref(
        storagePreferences: StoragePreferences,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory())

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_storage_location),
            subtitle = storageLocationText(storagePreferences.baseStorageDirectory()),
            onClick = {
                try {
                    pickStorageLocation.launch(null)
                } catch (e: ActivityNotFoundException) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = listOf(
                // Manual actions
                getCreateBackupPref(),
                getRestoreBackupPref(),

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    pref = backupPreferences.backupInterval(),
                    title = stringResource(MR.strings.pref_backup_interval),
                    entries = mapOf(
                        0 to stringResource(MR.strings.off),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    onValueChanged = {
                        BackupCreateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
            ),
        )
    }

    @Composable
    private fun getCreateBackupPref(): Preference.PreferenceItem.TextPreference {
        val navigator = LocalNavigator.currentOrThrow
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_create_backup),
            subtitle = stringResource(MR.strings.pref_create_backup_summ),
            onClick = { navigator.push(CreateBackupScreen()) },
        )
    }

    @Composable
    private fun getRestoreBackupPref(): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        var error by remember { mutableStateOf<Any?>(null) }
        if (error != null) {
            val onDismissRequest = { error = null }
            when (val err = error) {
                is InvalidRestore -> {
                    AlertDialog(
                        onDismissRequest = onDismissRequest,
                        title = { Text(text = stringResource(MR.strings.invalid_backup_file)) },
                        text = { Text(text = listOfNotNull(err.uri, err.message).joinToString("\n\n")) },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    context.copyToClipboard(err.message, err.message)
                                    onDismissRequest()
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_copy_to_clipboard))
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = onDismissRequest) {
                                Text(text = stringResource(MR.strings.action_ok))
                            }
                        },
                    )
                }
                is MissingRestoreComponents -> {
                    AlertDialog(
                        onDismissRequest = onDismissRequest,
                        title = { Text(text = stringResource(MR.strings.pref_restore_backup)) },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            ) {
                                val msg = buildString {
                                    append(stringResource(MR.strings.backup_restore_content_full))
                                    if (err.sources.isNotEmpty()) {
                                        append("\n\n").append(stringResource(MR.strings.backup_restore_missing_sources))
                                        err.sources.joinTo(
                                            this,
                                            separator = "\n- ",
                                            prefix = "\n- ",
                                        )
                                    }
                                    if (err.trackers.isNotEmpty()) {
                                        append(
                                            "\n\n",
                                        ).append(stringResource(MR.strings.backup_restore_missing_trackers))
                                        err.trackers.joinTo(
                                            this,
                                            separator = "\n- ",
                                            prefix = "\n- ",
                                        )
                                    }
                                }
                                Text(text = msg)
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    BackupRestoreJob.start(
                                        context = context,
                                        uri = err.uri,
                                        // TODO: allow user-selectable restore options
                                        options = RestoreOptions(
                                            appSettings = true,
                                            sourceSettings = true,
                                            library = true,
                                        ),
                                    )
                                    onDismissRequest()
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_restore))
                            }
                        },
                    )
                }
                else -> error = null // Unknown
            }
        }

        val chooseBackup = rememberLauncherForActivityResult(
            object : ActivityResultContracts.GetContent() {
                override fun createIntent(context: Context, input: String): Intent {
                    val intent = super.createIntent(context, input)
                    return Intent.createChooser(intent, context.stringResource(MR.strings.file_select_backup))
                }
            },
        ) {
            if (it == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            val results = try {
                BackupFileValidator().validate(context, it)
            } catch (e: Exception) {
                error = InvalidRestore(it, e.message.toString())
                return@rememberLauncherForActivityResult
            }

            if (results.missingSources.isEmpty() && results.missingTrackers.isEmpty()) {
                BackupRestoreJob.start(
                    context = context,
                    uri = it,
                    // TODO: allow user-selectable restore options
                    options = RestoreOptions(
                        appSettings = true,
                        sourceSettings = true,
                        library = true,
                    ),
                )
                return@rememberLauncherForActivityResult
            }

            error = MissingRestoreComponents(it, results.missingSources, results.missingTrackers)
        }

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_restore_backup),
            subtitle = stringResource(MR.strings.pref_restore_backup_summ),
            onClick = {
                if (!BackupRestoreJob.isRunning(context)) {
                    if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                        context.toast(MR.strings.restore_miui_warning, Toast.LENGTH_LONG)
                    }
                    // no need to catch because it's wrapped with a chooser
                    chooseBackup.launch("*/*")
                } else {
                    context.toast(MR.strings.restore_in_progress)
                }
            },
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }

        val chapterCache = remember { Injekt.get<ChapterCache>() }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableSize = remember(cacheReadableSizeSema) { chapterCache.readableSize }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_data),
            preferenceItems = listOf(
                getStorageInfoPref(cacheReadableSize),

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_cache, cacheReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = chapterCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = libraryPreferences.autoClearChapterCache(),
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
            ),
        )
    }

    @Composable
    fun getStorageInfoPref(
        chapterCacheReadableSize: String,
    ): Preference.PreferenceItem.CustomPreference {
        val context = LocalContext.current
        val available = remember {
            Formatter.formatFileSize(context, DiskUtil.getAvailableStorageSpace(Environment.getDataDirectory()))
        }
        val total = remember {
            Formatter.formatFileSize(context, DiskUtil.getTotalStorageSpace(Environment.getDataDirectory()))
        }

        return Preference.PreferenceItem.CustomPreference(
            title = stringResource(MR.strings.pref_storage_usage),
        ) {
            BasePreferenceWidget(
                title = stringResource(MR.strings.pref_storage_usage),
                subcomponent = {
                    // TODO: downloads, SD cards, bar representation?, i18n
                    Box(modifier = Modifier.padding(horizontal = PrefsHorizontalPadding)) {
                        Text(text = "Available: $available / $total (chapter cache: $chapterCacheReadableSize)")
                    }
                },
            )
        }
    }
}

private data class MissingRestoreComponents(
    val uri: Uri,
    val sources: List<String>,
    val trackers: List<String>,
)

private data class InvalidRestore(
    val uri: Uri? = null,
    val message: String,
)
