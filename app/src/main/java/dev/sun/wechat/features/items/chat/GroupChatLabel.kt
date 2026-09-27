package dev.sun.wechat.features.items.chat

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.sun.wechat.features.api.ui.WeConversationContextMenuApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.chat.groupchatlabel.GroupChatLabelStore
import dev.sun.wechat.features.items.chat.groupchatlabel.GroupChatLabel as GroupChatLabelModel
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.DefaultColumn
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.EditIcon
import dev.sun.wechat.ui.utils.ListItem
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.strings.isGroupChatWxId

object GroupChatLabel : ClickableFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    override val technicalId = "群聊标签"
    override val nameRes = R.string.feature_group_chat_label_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_group_chat_label_description

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    private const val MENU_ITEM_ID = 777020

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun onClick(context: ComponentActivity) {
        showManageDialog(context)
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ITEM_ID,
            text = localizedChatString(R.string.group_chat_label_menu),
            drawable = EditIcon,
            shouldShow = { context, _ -> context.talker.isGroupChatWxId },
        ) { context -> showAssignDialog(context.activity, context.talker) }
    )

    private fun showManageDialog(context: Context) {
        showComposeDialog(context) {
            var labels by remember { mutableStateOf(GroupChatLabelStore.load()) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_group_chat_label_name)) },
                text = {
                    DefaultColumn {
                        if (labels.isEmpty()) {
                            Text(stringResource(R.string.group_chat_label_empty))
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                                items(labels, key = { it.id }) { label ->
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            showEditDialog(context, label) {
                                                labels = GroupChatLabelStore.load()
                                            }
                                        },
                                        content = { Text(label.name) },
                                        supportingContent = {
                                            Text(
                                                stringResource(
                                                    R.string.group_chat_label_group_count,
                                                    label.groupIds.size,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showCreateDialog(context) { labels = GroupChatLabelStore.load() }
                    }) { Text(stringResource(R.string.group_chat_label_new)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun showAssignDialog(context: Context, talker: String) {
        showComposeDialog(context) {
            var labels by remember { mutableStateOf(GroupChatLabelStore.load()) }
            var selected by remember {
                mutableStateOf(labels.filter { talker in it.groupIds }.map { it.id }.toSet())
            }
            AlertDialogContent(
                title = { Text(stringResource(R.string.group_chat_label_menu)) },
                text = {
                    DefaultColumn {
                        if (labels.isEmpty()) {
                            Text(stringResource(R.string.group_chat_label_empty))
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                                items(labels, key = { it.id }) { label ->
                                    val checked = label.id in selected
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            selected = if (checked) {
                                                selected - label.id
                                            } else {
                                                selected + label.id
                                            }
                                        },
                                        content = { Text(label.name) },
                                        supportingContent = {
                                            Text(
                                                stringResource(
                                                    R.string.group_chat_label_group_count,
                                                    label.groupIds.size,
                                                ),
                                            )
                                        },
                                        trailingContent = {
                                            Checkbox(checked = checked, onCheckedChange = null)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        GroupChatLabelStore.assign(talker, selected)
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCreateDialog(context) { created ->
                            selected = selected + created.id
                            GroupChatLabelStore.assign(talker, selected)
                            labels = GroupChatLabelStore.load()
                        }
                    }) { Text(stringResource(R.string.group_chat_label_new)) }
                },
            )
        }
    }

    private fun showCreateDialog(context: Context, onCreated: (GroupChatLabelModel) -> Unit) {
        showComposeDialog(context) {
            var name by remember { mutableStateOf("") }
            AlertDialogContent(
                title = { Text(stringResource(R.string.group_chat_label_new)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.group_chat_label_name_hint)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val created = GroupChatLabelStore.newLabel(name)
                            GroupChatLabelStore.save(GroupChatLabelStore.load() + created)
                            onCreated(created)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }

    private fun showEditDialog(
        context: Context,
        label: GroupChatLabelModel,
        onChanged: () -> Unit,
    ) {
        showComposeDialog(context) {
            var name by remember { mutableStateOf(label.name) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.group_chat_label_edit)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.group_chat_label_name_hint)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = {
                            GroupChatLabelStore.save(
                                GroupChatLabelStore.load().map {
                                    if (it.id == label.id) it.copy(name = name.trim()) else it
                                },
                            )
                            onChanged()
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        GroupChatLabelStore.save(GroupChatLabelStore.load().filterNot { it.id == label.id })
                        onChanged()
                        onDismiss()
                    }) {
                        Text(
                            text = stringResource(R.string.group_chat_label_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }
}
