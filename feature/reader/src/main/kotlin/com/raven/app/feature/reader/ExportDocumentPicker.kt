package com.raven.app.feature.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.raven.app.core.media.reader.ExportFile
import com.raven.app.core.media.reader.ExportMode
import com.raven.app.core.media.reader.PreparedExport
import java.io.File

// A gallery can contain 2,000 pages. Store the common title once and keep only
// the short differing suffix and MIME type per staged file in the saved state.
private val preparedExportSaver = listSaver<PreparedExport?, String>(
    save = { export ->
        if (export == null) return@listSaver emptyList()
        val prefix = export.files.map { it.file.name }.reduce { left, right -> left.commonPrefixWith(right) }
        buildList {
            add(export.folder.absolutePath)
            add(export.mode.name)
            add(prefix)
            export.files.forEach { file ->
                add(file.file.name.removePrefix(prefix))
                add(file.mimeType)
            }
        }
    },
    restore = { values ->
        val folder = File(values[0])
        val files = values.drop(3).chunked(2).map { (suffix, mime) ->
            ExportFile(File(folder, values[2] + suffix), mime)
        }
        PreparedExport(folder, files, ExportMode.valueOf(values[1]))
    },
)

/** Registration belongs to the navigation entry so Android can redeliver its result after recreation. */
@Composable
internal fun rememberExportDocumentPicker(
    onResult: (PreparedExport, Uri?, Boolean) -> Unit,
): (PreparedExport, Boolean) -> Unit {
    var pending by rememberSaveable(stateSaver = preparedExportSaver) { mutableStateOf<PreparedExport?>(null) }
    fun finish(uri: Uri?, tree: Boolean) {
        val export = pending ?: return
        pending = null
        onResult(export, uri, tree)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        finish(result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }, false)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        finish(uri, true)
    }
    return { prepared, tree ->
        pending = prepared
        try {
            if (tree) {
                treePicker.launch(null)
            } else {
                val file = prepared.files.single()
                filePicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = file.mimeType
                    putExtra(Intent.EXTRA_TITLE, file.file.name)
                })
            }
        } catch (_: ActivityNotFoundException) {
            finish(null, tree)
        }
    }
}
