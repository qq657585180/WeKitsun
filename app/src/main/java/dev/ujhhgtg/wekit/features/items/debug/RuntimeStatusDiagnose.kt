package dev.ujhhgtg.wekit.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.FeatureRuntimeReporter
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

object RuntimeStatusDiagnose : ClickableFeature() {

    override val technicalId = "运行时状态诊断"
    override val nameRes = R.string.feature_runtime_status_diagnose_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_runtime_status_diagnose_description

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context, directlyDismissable = false) {
            var refresh by remember { mutableStateOf(0) }
            val records = remember(refresh) { FeatureRuntimeReporter.snapshot() }

            AlertDialogContent(
                title = { Text(stringResource(R.string.debug_runtime_status_dialog_title)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (records.isEmpty()) {
                            Text(
                                text = stringResource(R.string.debug_runtime_status_empty),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            records.forEach { record ->
                                val statusText = when (record.status) {
                                    FeatureRuntimeReporter.Status.OK ->
                                        stringResource(R.string.debug_runtime_status_ok)

                                    FeatureRuntimeReporter.Status.PARTIAL ->
                                        stringResource(R.string.debug_runtime_status_partial)
                                }
                                Text(
                                    text = "${record.technicalId}  [$statusText]",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (record.status == FeatureRuntimeReporter.Status.OK) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                if (record.detail.isNotBlank()) {
                                    Text(
                                        text = record.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { refresh++ }) {
                        Text(stringResource(R.string.debug_runtime_status_refresh))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            )
        }
    }
}