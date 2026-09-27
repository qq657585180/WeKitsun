package dev.sun.wechat.features.items.chat.groupchatlabel

import dev.sun.wechat.data.KvStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class GroupChatLabel(
    val id: String,
    val name: String,
    val groupIds: Set<String> = emptySet(),
)

/**
 * 群聊标签存储：把一个名字关联到若干群聊 wxid，数据以 JSON 存在 [KvStore]。
 *
 * 标签本身只做分类，供对话菜单快速设置、以及后续按标签批量选群使用。
 */
object GroupChatLabelStore {

    private const val KEY_LABELS = "group_chat_labels_json"

    private val serializer = ListSerializer(GroupChatLabel.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<GroupChatLabel> {
        val raw = KvStore.getStringOrDef(KEY_LABELS, "")
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun save(labels: List<GroupChatLabel>) {
        KvStore.putString(KEY_LABELS, json.encodeToString(serializer, labels))
    }

    fun newLabel(name: String): GroupChatLabel =
        GroupChatLabel(UUID.randomUUID().toString(), name.trim())

    fun labelsOf(groupId: String): List<GroupChatLabel> = load().filter { groupId in it.groupIds }

    /** 按 [selectedIds] 把 [groupId] 加入/移出对应标签，未选中的标签会被移除该群。 */
    fun assign(groupId: String, selectedIds: Set<String>) {
        save(
            load().map { label ->
                val shouldContain = label.id in selectedIds
                val contains = groupId in label.groupIds
                if (shouldContain == contains) {
                    label
                } else {
                    label.copy(
                        groupIds = if (shouldContain) label.groupIds + groupId else label.groupIds - groupId,
                    )
                }
            },
        )
    }
}
