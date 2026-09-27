package dev.sun.wechat.features.items.system

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.sun.wechat.R
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.fs.KnownPaths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** 模块级「安全模式」开关 */
object SafeMode {

    private const val TAG = "SafeMode"
    private val flagFile = KnownPaths.moduleRoot / "safe_mode.flag"

    val isEnabled: Boolean
        get() = flagFile.exists()

    fun showEnableConfirmDialog(context: Context, onConfirmed: () -> Unit) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.system_safe_mode_enable_title)) },
                text = { Text(stringResource(R.string.system_safe_mode_enable_message)) },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        onConfirmed()
                    }) { Text(stringResource(R.string.system_safe_mode_enable)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            runCatching {
                flagFile.writeText("")
            }.onFailure {
                WeLogger.e(TAG, "failed to create safe mode flag", it)
            }
        } else {
            runCatching { flagFile.deleteIfExists() }.onFailure {
                WeLogger.e(TAG, "failed to delete safe mode flag", it)
            }
        }
        WeLogger.i(TAG, "safe mode flag ${if (enabled) "created" else "deleted"}: $flagFile")
    }
}
