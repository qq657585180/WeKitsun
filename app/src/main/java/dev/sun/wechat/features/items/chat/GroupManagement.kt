package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeApi
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.WeGroupApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.DropDownMenuWidget
import dev.sun.wechat.ui.content.m3.DropdownOption
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 群管理（白名单群自动处理）。
 *
 * 检测项（各自可开关）：
 *  - 纯文本里的 URL、链接卡片、小程序卡片、联系人名片、图片/视频/语音/文件
 *  - 长篇大论（正文超字数）
 *  - 禁言时段（默认 23 -> 7，可改可关；时段内发任何消息都算违规）
 *  - 防刷屏（同一人 N 秒内超过 M 条）
 *  - 非群主 @所有人
 *  - 自定义关键词/正则规则表：每行 `模式|动作号`，模式以 `re:` 开头按正则，否则忽略大小写包含匹配
 *
 * 处置：
 *  - 动作：移出并发提示 / 只移出 / 只提示 / 移出并永久拉黑
 *  - 每人冷却；黑名单内的人不受冷却限制
 *  - 阶梯处罚：第 1 次提示、第 2 次移出、第 3 次起永久拉黑
 *  - 群主自动豁免（读 ChatRoom 的 ChatRoomOwner 列；微信本地没有管理员名单，管理员只能手动加豁免）
 *  - 处理记录（内存环形缓冲）与黑名单一键拉回
 *
 * 说明：微信没有"管理员撤回他人消息"的通道（8.0.74 只有自己的 NetSceneRevokeMsg），
 * 所以这里只能移出群聊；[WeGroupApi.delMembers] 是 fire-and-forget，无结果回调。
 */
object GroupManagement : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "群管理"
    override val nameRes = R.string.feature_glg_name
    override val descriptionRes = R.string.feature_glg_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    private const val TAG = "GroupManagement"
    // sendImage 是异步提交任务：上传线程读走 PNG 之前不能删文件，否则发送失败
    private const val CARD_FILE_KEEP_MS = 60_000L

    // 动作
    const val ACTION_KICK_AND_HINT = 0
    const val ACTION_KICK_ONLY = 1
    const val ACTION_HINT_ONLY = 2
    const val ACTION_KICK_AND_BAN = 3

    // 配置
    var groups by KvStore.prefOption("glg_groups_json", "")
    var banned by KvStore.prefOption("glg_banned_json", "")
    var exempt by KvStore.prefOption("glg_exempt_json", "")
    var cooldownMs by KvStore.prefOption("glg_cooldown_ms", 60_000L)
    var action by KvStore.prefOption("glg_action", ACTION_KICK_AND_HINT)
    var hintText by KvStore.prefOption("glg_hint_text", DEFAULT_HINT)
    var detectTextLink by KvStore.prefOption("glg_detect_text_link", true)
    var detectCardLink by KvStore.prefOption("glg_detect_card_link", true)
    var detectMiniApp by KvStore.prefOption("glg_detect_miniapp", true)
    var detectContactCard by KvStore.prefOption("glg_detect_contact_card", true)
    var detectImage by KvStore.prefOption("glg_detect_image", false)
    var detectVideo by KvStore.prefOption("glg_detect_video", false)
    var detectVoice by KvStore.prefOption("glg_detect_voice", false)
    var detectFile by KvStore.prefOption("glg_detect_file", false)
    var maxTextLength by KvStore.prefOption("glg_max_text_length", 300)
    var nightEnabled by KvStore.prefOption("glg_night_enabled", false)
    var nightStartHour by KvStore.prefOption("glg_night_start_hour", 23)
    var nightEndHour by KvStore.prefOption("glg_night_end_hour", 7)
    var nightHintText by KvStore.prefOption("glg_night_hint_text", DEFAULT_NIGHT_HINT)

    // ---- 进退群监控 ----
    var notifyEnabled by KvStore.prefOption("glg_notify_enabled", false)
    var welcomeText by KvStore.prefOption("glg_welcome_text", "欢迎新成员入群！")
    var leaveText by KvStore.prefOption("glg_leave_text", "")
    // 卡片模式：进退群发图片卡片（头像+通知文本），关闭则用下方纯文本
    var cardEnabled by KvStore.prefOption("glg_card_enabled", false)
    var newbieKickEnabled by KvStore.prefOption("glg_newbie_kick", false)
    var newbieMinutes by KvStore.prefOption("glg_newbie_minutes", 10)
    var floodEnabled by KvStore.prefOption("glg_flood_enabled", false)
    var floodWindowSec by KvStore.prefOption("glg_flood_window_sec", 60)
    var floodCount by KvStore.prefOption("glg_flood_count", 10)
    var atAllEnabled by KvStore.prefOption("glg_atall_enabled", false)
    var ownerExempt by KvStore.prefOption("glg_owner_exempt", true)
    var ladderEnabled by KvStore.prefOption("glg_ladder_enabled", false)
    var rules by KvStore.prefOption("glg_rules", "")

    private const val DEFAULT_HINT = "群内禁止发送链接和小程序，已自动移出群聊。"
    private const val DEFAULT_NIGHT_HINT = "禁言时段内发言，已自动移出群聊。"

    // 名单用换行符序列化进单个字符串：SharedPreferences 的 StringSet 返回共享实例，直接改会不生效
    private fun loadSet(raw: String): Set<String> = raw.lineSequence().filter { it.isNotBlank() }.toSet()

    private fun saveSet(values: Set<String>): String = values.joinToString("\n")

    private val groupIds get() = loadSet(groups)
    private val exemptIds get() = loadSet(exempt)

    /** 黑名单条目是 `群ID|成员ID`，这样"一键拉回"才知道该拉回哪个群。 */
    private data class BanKey(val groupId: String, val memberId: String)

    private val bannedKeys: List<BanKey>
        get() = loadSet(banned).mapNotNull { raw ->
            val i = raw.indexOf('|')
            if (i <= 0) null else BanKey(raw.substring(0, i), raw.substring(i + 1))
        }

    private fun isBanned(groupId: String, memberId: String): Boolean =
        "$groupId|$memberId" in loadSet(banned)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastHandledAt = ConcurrentHashMap<String, Long>()
    private val msgTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val strikes = ConcurrentHashMap<String, Int>()
    private val handledSvrIds = ConcurrentHashMap.newKeySet<Long>()
    private val actionLock = Any()
    private var lastActionSentAt = 0L

    private val logLock = Any()
    private val logs = ArrayDeque<HandleLog>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())

    data class HandleLog(val time: Long, val groupId: String, val memberId: String, val reason: String, val actionTaken: Int)

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        memberSweeper?.cancel()
        memberSweeper = scope.launch {
            while (isActive) {
                delay(30_000L)
                if (!notifyEnabled) continue
                for (g in groupIds) runCatching { checkMembers(g, force = true) }
            }
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        memberSweeper?.cancel()
        lastHandledAt.clear()
        msgTimes.clear()
        strikes.clear()
    }

    override fun onClick(context: ComponentActivity) = showSettings(context)

    // ---------------- 检测 ----------------

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val groups = groupIds
        if (groups.isEmpty()) return

        val talker = values.getAsString("talker") ?: return
        if (!talker.endsWith("@chatroom") || talker !in groups) return

        val isSend = values.getAsInteger("isSend") ?: 1
        if (isSend != 0) return

        // DB diff 监控：群里来消息就顺带核对成员列表（节流 5s/群），不依赖系统消息格式
        if (notifyEnabled) checkMembers(talker)

        val type = values.getAsInteger("type") ?: return
        val content = values.getAsString("content") ?: return

        // ---- 系统消息（进群/退群事件，8.0.74 为 <sysmsg type="sysmsgtemplate"> XML）----
        // type 因版本而异（8.0.74 实测 570425393，并非 10000），按内容识别更稳。
        if (content.contains("<sysmsg")) {
            if (!notifyEnabled) return
            runCatching { handleSystemMessage(talker, content) }
                .onFailure { WeLogger.e(TAG, "system message handling failed", it) }
            return
        }

        runCatching {
            val sender = senderOf(content)
                ?: values.getAsString("sender")?.takeIf { it.isNotBlank() }
                ?: values.getAsString("msgUser")?.takeIf { it.isNotBlank() }
            if (sender == null) {
                // 诊断：content 无发送者前缀，且 ContentValues 里也没有 sender/msgUser 字段
                WeLogger.i(TAG, "GM no-sender group=$talker type=$type content=${content.take(80)}")
                return
            }
            val body = content.substringAfter(":\n")
            WeLogger.i(TAG, "GM inspect group=$talker type=$type sender=$sender body=${body.take(40)}")
            if (sender == WeApi.selfWxId) return
            if (sender in exemptIds) return
            if (ownerExempt && sender == groupOwner(talker)) return

            val svrId = values.getAsLong("msgSvrId") ?: 0L
            if (svrId != 0L && !handledSvrIds.add(svrId)) return
            if (handledSvrIds.size > 4000) handledSvrIds.clear()

            // 刷屏统计对每条消息都累计，所以先算再判违规
            val flooded = floodEnabled && bumpFlood(talker, sender)
            val atAll = atAllEnabled && content.contains("announcement@all")
            val verdict = if (isNewbie(talker, sender)) Verdict(REASON_NEWBIE) else detect(type, body, flooded, atAll)
            WeLogger.i(TAG, "GM detect group=$talker sender=$sender -> ${verdict?.reason ?: "null"}")
            if (verdict == null) return
            if (!allowHandle(talker, sender)) return

            scope.launch { handle(talker, sender, verdict) }
        }.onFailure { WeLogger.e(TAG, "inspect group message failed", it) }
    }

    /** 群消息 content 形如 `发送者wxId:\n正文`；取不到发送者前缀就返回 null（不能瞎踢）。 */
    private fun senderOf(content: String): String? {
        val i = content.indexOf(":\n")
        if (i <= 0 || i > 80) return null
        val candidate = content.substring(0, i)
        return candidate.takeIf { s -> s.isNotBlank() && s.none { c -> c.isWhitespace() || c == '<' } }
    }

    private fun detect(type: Int, body: String, flooded: Boolean, atAll: Boolean): Verdict? {
        if (nightEnabled && inSilenceWindow()) return Verdict(REASON_NIGHT)
        if (flooded) return Verdict(REASON_FLOOD)
        if (atAll) return Verdict(REASON_AT_ALL)

        classifyMedia(type, body)?.let { return Verdict(it) }

        if (maxTextLength > 0 && type == TYPE_TEXT && body.length > maxTextLength) {
            return Verdict(REASON_TOO_LONG)
        }
        matchRule(body)?.let { (pattern, ruleAction) ->
            return Verdict("$REASON_RULE$pattern", ruleAction)
        }
        return null
    }

    private fun classifyMedia(type: Int, body: String): String? = when (type) {
        TYPE_TEXT -> {
            when {
                detectTextLink && URL_PATTERN.containsMatchIn(body) -> REASON_TEXT_URL
                else -> null
            }
        }

        TYPE_IMAGE -> if (detectImage) REASON_IMAGE else null
        TYPE_VOICE -> if (detectVoice) REASON_VOICE else null
        TYPE_VIDEO, TYPE_MICRO_VIDEO -> if (detectVideo) REASON_VIDEO else null

        TYPE_APP, TYPE_LINK, MessageType.LINK.code, MessageType.MUSIC.code, MessageType.PRODUCT.code -> {
            val xml = body
            when {
                detectMiniApp && (xml.contains("<weappinfo") || xml.contains("weapp")) -> REASON_MINIAPP
                detectFile && xml.contains("<type>6</type>") -> REASON_FILE
                detectCardLink && (xml.contains("<url>") || xml.contains("http://") || xml.contains("https://")) -> REASON_LINK_CARD
                else -> null
            }
        }

        TYPE_CARD -> if (detectContactCard) REASON_CONTACT_CARD else null

        33 -> if (detectMiniApp) REASON_MINIAPP else null

        else -> null
    }

    /** 规则表：每行 `模式|动作`，动作缺省则用全局动作。 */
    private fun matchRule(body: String): Pair<String, Int>? {
        for (line in rules.lineSequence()) {
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#")) continue
            val sep = text.lastIndexOf('|')
            val pattern = if (sep > 0) text.substring(0, sep).trim() else text
            if (pattern.isEmpty()) continue
            val ruleAction = if (sep > 0) text.substring(sep + 1).trim().toIntOrNull() else null
            val hit = if (pattern.startsWith("re:")) {
                runCatching { Regex(pattern.removePrefix("re:")).containsMatchIn(body) }.getOrDefault(false)
            } else {
                body.contains(pattern, ignoreCase = true)
            }
            if (hit) return pattern to (ruleAction ?: action)
        }
        return null
    }

    /** 跨零点窗口：start=23,end=7 表示 [23,24) 与 [0,7)。 */
    private fun inSilenceWindow(): Boolean {
        val start = nightStartHour.coerceIn(0, 23)
        val end = nightEndHour.coerceIn(0, 23)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (start == end) true else if (start < end) hour in start until end else hour >= start || hour < end
    }

    private fun bumpFlood(groupId: String, memberId: String): Boolean {
        val now = System.currentTimeMillis()
        val window = floodWindowSec.coerceIn(1, 3600) * 1000L
        val limit = floodCount.coerceAtLeast(2)
        val key = "$groupId|$memberId"
        val queue = synchronized(msgTimes) { msgTimes.getOrPut(key) { ArrayDeque() } }
        synchronized(queue) {
            while (queue.isNotEmpty() && now - queue.first() > window) queue.removeFirst()
            queue.addLast(now)
            return queue.size > limit
        }
    }

    /** 冷却：黑名单里的人不受冷却限制。 */
    private fun allowHandle(groupId: String, memberId: String): Boolean {
        if (isBanned(groupId, memberId)) return true
        val now = System.currentTimeMillis()
        val key = "$groupId|$memberId"
        val last = lastHandledAt[key] ?: 0L
        if (now - last < cooldownMs.coerceAtLeast(0L)) return false
        lastHandledAt[key] = now
        return true
    }

    /** 群主：ChatRoom 表的 ChatRoomOwner 列；列名大小写按游标实际返回匹配。不加缓存（ConcurrentHashMap 不允许 null 值，曾经因此 NPE 把每条群消息都搞挂）。 */
    private fun groupOwner(groupId: String): String? = runCatching {
        WeDatabaseApi.executeQuery("SELECT * FROM ChatRoom WHERE ChatRoomName = ?", arrayOf(groupId))
            .firstOrNull()
            ?.entries
            ?.firstOrNull { it.key.equals("chatroomowner", ignoreCase = true) }
            ?.value
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }.onFailure { WeLogger.w(TAG, "read group owner failed: $groupId", it) }.getOrNull()

    // ---------------- 进退群监控 ----------------

    private val joinTimes = ConcurrentHashMap<String, Long>()
    // DB diff 监控状态：groupId -> 成员 wxid 集合 / 上次核对时间
    private val memberSnapshots = ConcurrentHashMap<String, MutableSet<String>>()
    private val lastMemberCheck = ConcurrentHashMap<String, Long>()
    private var memberSweeper: Job? = null
    // 进退群事件去重：踢人/sysmsg/diff 三条路径可能对同一事件各发一次，30s 内只发一次。
    private val dispatchedRecently = ConcurrentHashMap<String, Long>()

    /** 8.0.74 群事件系统消息为 XML `<sysmsg type="tmpl_type_profile">` 内嵌 username/nickname 等。 */
    private fun handleSystemMessage(groupId: String, xml: String) {
        WeLogger.i(TAG, "GM sysmsg group=$groupId len=${xml.length} xml=$xml")

        // 提取 XML 内的字段
        val username = xmlTag(xml, "username")
        val nickname = xmlTag(xml, "nickname")

        // 退群
        if (xml.contains("退出了群聊") || xml.contains("tmpl_type_profile")) {
            val leaver = nickname.ifBlank { username.ifBlank { "未知" } }
            WeLogger.i(TAG, "GM leave group=$groupId member=$leaver wxid=$username")
            if (username.isBlank()) {
                WeLogger.w(TAG, "GM leave skip: empty wxid (sysmsgtemplate 未解析出成员), group=$groupId")
                return
            }
            joinTimes.remove("$groupId|$username")
            memberSnapshots[groupId]?.remove(username)
            dispatchEvent(groupId, username, leaver, isJoin = false)
            record(groupId, leaver, "leave", ACTION_HINT_ONLY)
            return
        }
        // 进群
        if (xml.contains("加入了群聊") || xml.contains("邀请")) {
            val joiner = nickname.ifBlank { username.ifBlank { "未知" } }
            WeLogger.i(TAG, "GM join group=$groupId member=$joiner wxid=$username")
            if (username.isBlank()) {
                WeLogger.w(TAG, "GM join skip: empty wxid (sysmsgtemplate 未解析出成员), group=$groupId")
                return
            }
            if (newbieKickEnabled) joinTimes["$groupId|$username"] = System.currentTimeMillis()
            memberSnapshots[groupId]?.add(username)
            dispatchEvent(groupId, username, joiner, isJoin = true)
            record(groupId, joiner, "join", ACTION_HINT_ONLY)
        }
    }

    /**
     * 成员列表 diff 监控（Hchat 同款）：读 chatroom.memberlist 与快照对比，
     * 不依赖系统消息格式；首次检查只建快照不发事件；DB 读空跳过防误判全员退群。
     * 系统消息路径触发时会同步快照，两条路径不会重复发。
     */
    private fun checkMembers(groupId: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(lastMemberCheck) {
            val last = lastMemberCheck[groupId] ?: 0L
            if (!force && now - last < 5_000L) return
            lastMemberCheck[groupId] = now
        }
        scope.launch {
            runCatching {
                val members = WeDatabaseApi.getGroupMembers(groupId).map { it.wxId }.toMutableSet()
                if (members.isEmpty()) return@runCatching
                val prev = memberSnapshots[groupId]
                if (prev != null) {
                    val joined = members - prev
                    val left = prev - members
                    WeLogger.d(TAG, "GM diff group=$groupId prev=${prev.size} cur=${members.size} joined=${joined.size} left=${left.size}")
                    if (joined.isNotEmpty()) WeLogger.i(TAG, "GM diff join group=$groupId new=${joined.size}")
                    if (left.isNotEmpty()) WeLogger.i(TAG, "GM diff leave group=$groupId gone=${left.size}")
                    for (wxid in left) {
                        val nick = runCatching { WeDatabaseApi.getDisplayName(wxid) }.getOrNull().orEmpty().ifBlank { wxid }
                        joinTimes.remove("$groupId|$wxid")
                        dispatchEvent(groupId, wxid, nick, isJoin = false)
                        record(groupId, nick, "leave", ACTION_HINT_ONLY)
                    }
                    for (wxid in joined) {
                        if (newbieKickEnabled) joinTimes["$groupId|$wxid"] = System.currentTimeMillis()
                        val nick = runCatching { WeDatabaseApi.getDisplayName(wxid) }.getOrNull().orEmpty().ifBlank { wxid }
                        dispatchEvent(groupId, wxid, nick, isJoin = true)
                        record(groupId, nick, "join", ACTION_HINT_ONLY)
                    }
                }
                memberSnapshots[groupId] = members
            }.onFailure { WeLogger.e(TAG, "member diff check failed group=$groupId", it) }
        }
    }

    /**
     * 进退群事件输出：卡片模式渲染图片卡片（头像+通知文本）经 [WeMessageApi.sendImage] 发出；
     * 否则回退纯文本（welcomeText/leaveText，支持 %userName% %userWxid% %groupName% %time%）。
     */
    private fun dispatchEvent(groupId: String, wxid: String, nick: String, isJoin: Boolean) {
        // 踢人/sysmsg/diff 三路径去重：30s 内同一 (群,成员,事件类型) 只发一次
        val dkey = "$groupId|$wxid|$isJoin"
        val now = System.currentTimeMillis()
        val last = dispatchedRecently[dkey] ?: 0L
        if (now - last < 30_000L) return
        dispatchedRecently[dkey] = now
        if (cardEnabled) {
            scope.launch {
                val ev = GroupEventCard.buildEvent(groupId, wxid, nick, isJoin)
                val sent = runCatching { GroupEventCard.sendAppMsg(groupId, ev) }
                    .onFailure { WeLogger.e(TAG, "GM event appmsg failed group=$groupId wxid=$wxid", it) }
                    .getOrDefault(false)
                if (sent) {
                    WeLogger.i(TAG, "GM event appmsg sent group=$groupId wxid=$wxid isJoin=$isJoin")
                    return@launch
                }
                // 图文卡片发不出去时回退到图片卡片
                runCatching {
                    val file = GroupEventCard.render(ev) ?: error("card render failed")
                    val submitted = WeMessageApi.sendImage(groupId, file.absolutePath)
                    if (submitted) {
                        // 上传是异步的：给足时间让微信读走 PNG 再删，否则文件消失导致发送失败
                        scope.launch {
                            delay(CARD_FILE_KEEP_MS)
                            runCatching { file.delete() }
                        }
                    } else {
                        file.delete()
                    }
                    WeLogger.i(
                        TAG,
                        "GM event image fallback group=$groupId wxid=$wxid isJoin=$isJoin " +
                            "submitted=$submitted file=${file.name}",
                    )
                }.onFailure { WeLogger.e(TAG, "GM event card failed group=$groupId wxid=$wxid", it) }
            }
            return
        }
        val text = (if (isJoin) welcomeText else leaveText).trim()
        if (text.isBlank()) return
        val replaced = text
            .replace("%userName%", nick)
            .replace("%userWxid%", wxid)
            .replace("%groupName%", groupName(groupId))
            .replace("%time%", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
        scope.launch { WeMessageApi.sendText(groupId, replaced) }
    }

    private fun xmlTag(xml: String, tag: String): String {
        // 支持 <tag>value</tag> 和 <tag><![CDATA[value]]></tag>（8.0.74 sysmsgtemplate 用 CDATA 包裹）
        val raw = Regex("""<$tag>([\s\S]{0,800}?)</$tag>""").find(xml)?.groupValues?.get(1)?.trim() ?: ""
        return raw.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
    }

    /** 新人冷静期：进群后 X 分钟内发消息即触发。 */
    private fun isNewbie(talker: String, sender: String): Boolean {
        if (!newbieKickEnabled) return false
        val joinTime = joinTimes["$talker|$sender"] ?: return false
        return System.currentTimeMillis() - joinTime < newbieMinutes.coerceAtLeast(1) * 60_000L
    }

    // ---------------- 处置 ----------------

    private fun handle(groupId: String, memberId: String, verdict: Verdict) {
        val ladderStep = if (ladderEnabled) (strikes.merge("$groupId|$memberId", 1, Int::plus) ?: 1) else 0
        val effective = when {
            !ladderEnabled -> verdict.action ?: action
            ladderStep <= 1 -> ACTION_HINT_ONLY
            ladderStep == 2 -> ACTION_KICK_AND_HINT
            else -> ACTION_KICK_AND_BAN
        }

        val shouldKick = effective == ACTION_KICK_AND_HINT || effective == ACTION_KICK_ONLY || effective == ACTION_KICK_AND_BAN
        val shouldBan = effective == ACTION_KICK_AND_BAN
        val shouldHint = effective == ACTION_KICK_AND_HINT || effective == ACTION_HINT_ONLY || effective == ACTION_KICK_AND_BAN

        try {
            if (shouldKick) {
                WeGroupApi.delMember(groupId, memberId)
                if (shouldBan) banned = saveSet(loadSet(banned) + "$groupId|$memberId")
                // 踢人卡片由「群成员行为监控」功能统一发（监听 chatroom 表更新），这里不重复发
            }
            if (shouldHint) {
                val text = if (verdict.reason == REASON_NIGHT) nightHintText else hintText
                if (text.isNotBlank()) {
                    synchronized(actionLock) {
                        if (cooldownMs > 0L) {
                            val wait = cooldownMs - (System.currentTimeMillis() - lastActionSentAt)
                            if (wait > 0L) Thread.sleep(wait.coerceAtMost(MAX_WAIT_MS))
                        }
                        WeMessageApi.sendText(groupId, text.trim())
                        lastActionSentAt = System.currentTimeMillis()
                    }
                }
            }
            record(groupId, memberId, verdict.reason, effective)
            WeLogger.i(
                TAG,
                "handled group=$groupId member=$memberId reason=${verdict.reason} action=$effective" +
                    if (ladderEnabled) " strike=$ladderStep" else "",
            )
        } catch (e: Exception) {
            WeLogger.e(TAG, "handle failed group=$groupId member=$memberId reason=${verdict.reason}", e)
        }
    }

    private fun record(groupId: String, memberId: String, reason: String, actionTaken: Int) {
        synchronized(logLock) {
            logs.addFirst(HandleLog(System.currentTimeMillis(), groupId, memberId, reason, actionTaken))
            while (logs.size > MAX_LOGS) logs.removeLast()
        }
    }

    // ---------------- 设置界面 ----------------

    private fun showSettings(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            var actionInput by remember { mutableStateOf(action) }
            var cooldown by remember { mutableStateOf(cooldownMs.toString()) }
            var hint by remember { mutableStateOf(hintText) }
            var maxLength by remember { mutableStateOf(maxTextLength.toString()) }
            var rulesInput by remember { mutableStateOf(rules) }
            var textLink by remember { mutableStateOf(detectTextLink) }
            var cardLink by remember { mutableStateOf(detectCardLink) }
            var miniApp by remember { mutableStateOf(detectMiniApp) }
            var contactCard by remember { mutableStateOf(detectContactCard) }
            var image by remember { mutableStateOf(detectImage) }
            var video by remember { mutableStateOf(detectVideo) }
            var voice by remember { mutableStateOf(detectVoice) }
            var file by remember { mutableStateOf(detectFile) }
            var night by remember { mutableStateOf(nightEnabled) }
            var nightStart by remember { mutableStateOf(nightStartHour.toString()) }
            var nightEnd by remember { mutableStateOf(nightEndHour.toString()) }
            var nightHint by remember { mutableStateOf(nightHintText) }
            var notify by remember { mutableStateOf(notifyEnabled) }
            var welcomeInput by remember { mutableStateOf(welcomeText) }
            var leaveInput by remember { mutableStateOf(leaveText) }
            var card by remember { mutableStateOf(cardEnabled) }
            var newbie by remember { mutableStateOf(newbieKickEnabled) }
            var newbieMinutesInput by remember { mutableStateOf(newbieMinutes.toString()) }
            var flood by remember { mutableStateOf(floodEnabled) }
            var floodWindow by remember { mutableStateOf(floodWindowSec.toString()) }
            var floodLimit by remember { mutableStateOf(floodCount.toString()) }
            var atAll by remember { mutableStateOf(atAllEnabled) }
            var owner by remember { mutableStateOf(ownerExempt) }
            var ladder by remember { mutableStateOf(ladderEnabled) }

            fun openGroupPicker() {
                showComposeDialog(context) {
                    ContactsSelector(
                        title = stringResource(R.string.glg_pick_groups),
                        contacts = WeDatabaseApi.getGroups(),
                        initialSelectedWxIds = loadSet(groups),
                        onDismiss = onDismiss,
                    ) { selected ->
                        groups = saveSet(selected)
                        onDismiss()
                    }
                }
            }

            fun openExemptPicker() {
                showComposeDialog(context) {
                    ContactsSelector(
                        title = stringResource(R.string.glg_configure_exempt),
                        contacts = WeDatabaseApi.getFriends(),
                        initialSelectedWxIds = loadSet(exempt),
                        onDismiss = onDismiss,
                    ) { selected ->
                        exempt = saveSet(selected)
                        onDismiss()
                    }
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.glg_config_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_whitelisted_groups),
                                    description = localizedContext.getString(R.string.glg_groups_desc, groupIds.size),
                                    onClick = { openGroupPicker() },
                                )
                            }
                            item {
                                DropDownMenuWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_action),
                                    description = null,
                                    value = actionInput,
                                    options = listOf(
                                        DropdownOption(ACTION_KICK_AND_HINT, stringResource(R.string.glg_action_kick_hint)),
                                        DropdownOption(ACTION_KICK_ONLY, stringResource(R.string.glg_action_kick)),
                                        DropdownOption(ACTION_HINT_ONLY, stringResource(R.string.glg_action_hint)),
                                        DropdownOption(ACTION_KICK_AND_BAN, stringResource(R.string.glg_action_ban)),
                                    ),
                                    onValueChange = { actionInput = it },
                                )
                            }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_cooldown),
                                    value = cooldown,
                                    onValueChange = { cooldown = digitsOnly(it, 7) },
                                    description = stringResource(R.string.glg_cooldown_desc),
                                )
                            }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_hint_text),
                                    value = hint,
                                    onValueChange = { hint = it },
                                    description = stringResource(R.string.glg_hint_text_desc),
                                    singleLine = false,
                                )
                            }

                            item { SectionLabel(stringResource(R.string.glg_section_detect)) }
                            item { SwitchRow(R.string.glg_detect_text, R.string.glg_detect_text_desc, textLink) { textLink = it } }
                            item { SwitchRow(R.string.glg_detect_card, null, cardLink) { cardLink = it } }
                            item { SwitchRow(R.string.glg_detect_miniapp, null, miniApp) { miniApp = it } }
                            item { SwitchRow(R.string.glg_detect_contact_card, R.string.glg_detect_contact_card_desc, contactCard) { contactCard = it } }
                            item { SwitchRow(R.string.glg_detect_image, null, image) { image = it } }
                            item { SwitchRow(R.string.glg_detect_video, null, video) { video = it } }
                            item { SwitchRow(R.string.glg_detect_voice, null, voice) { voice = it } }
                            item { SwitchRow(R.string.glg_detect_file, null, file) { file = it } }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_max_length),
                                    value = maxLength,
                                    onValueChange = { maxLength = digitsOnly(it, 5) },
                                    description = stringResource(R.string.glg_max_length_desc),
                                )
                            }

                            item { SectionLabel(stringResource(R.string.glg_section_rules)) }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_rules_title),
                                    value = rulesInput,
                                    onValueChange = { rulesInput = it },
                                    description = stringResource(R.string.glg_rules_desc),
                                    singleLine = false,
                                )
                            }

                            item { SectionLabel(stringResource(R.string.glg_section_night)) }
                            item { SwitchRow(R.string.glg_night_title, R.string.glg_night_desc, night) { night = it } }
                            item {
                                Row(Modifier.fillMaxWidth()) {
                                    Box(Modifier.weight(1f)) {
                                        FieldRow(
                                            label = stringResource(R.string.glg_night_start),
                                            value = nightStart,
                                            onValueChange = { nightStart = digitsOnly(it, 2) },
                                        )
                                    }
                                    Box(Modifier.weight(1f)) {
                                        FieldRow(
                                            label = stringResource(R.string.glg_night_end),
                                            value = nightEnd,
                                            onValueChange = { nightEnd = digitsOnly(it, 2) },
                                        )
                                    }
                                }
                            }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_night_hint),
                                    value = nightHint,
                                    onValueChange = { nightHint = it },
                                    description = stringResource(R.string.glg_night_hint_desc),
                                    singleLine = false,
                                )
                            }

                            item { SectionLabel(stringResource(R.string.glg_notify_title)) }
                            item { SwitchRow(R.string.glg_notify_title, R.string.glg_notify_desc, notify) { notify = it } }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_welcome),
                                    value = welcomeInput,
                                    onValueChange = { welcomeInput = it },
                                    description = stringResource(R.string.glg_welcome_desc),
                                    singleLine = false,
                                )
                            }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_leave),
                                    value = leaveInput,
                                    onValueChange = { leaveInput = it },
                                    description = stringResource(R.string.glg_leave_desc),
                                    singleLine = false,
                                )
                            }
                            item { SwitchRow(R.string.glg_card_switch, R.string.glg_card_switch_desc, card) { card = it } }
                            item { SwitchRow(R.string.glg_newbie_title, R.string.glg_newbie_desc, newbie) { newbie = it } }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_newbie_minutes),
                                    value = newbieMinutesInput,
                                    onValueChange = { v -> newbieMinutesInput = v.filter { c -> c.isDigit() }.take(4) },
                                )
                            }

                            item { SectionLabel(stringResource(R.string.glg_section_misc)) }
                            item { SwitchRow(R.string.glg_flood_title, R.string.glg_flood_desc, flood) { flood = it } }
                            item {
                                Row(Modifier.fillMaxWidth()) {
                                    Box(Modifier.weight(1f)) {
                                        FieldRow(
                                            label = stringResource(R.string.glg_flood_window),
                                            value = floodWindow,
                                            onValueChange = { floodWindow = digitsOnly(it, 4) },
                                        )
                                    }
                                    Box(Modifier.weight(1f)) {
                                        FieldRow(
                                            label = stringResource(R.string.glg_flood_count),
                                            value = floodLimit,
                                            onValueChange = { floodLimit = digitsOnly(it, 3) },
                                        )
                                    }
                                }
                            }
                            item { SwitchRow(R.string.glg_atall_title, R.string.glg_atall_desc, atAll) { atAll = it } }
                            item { SwitchRow(R.string.glg_owner_title, R.string.glg_owner_desc, owner) { owner = it } }
                            item { SwitchRow(R.string.glg_ladder_title, R.string.glg_ladder_desc, ladder) { ladder = it } }

                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_configure_exempt),
                                    description = localizedContext.getString(R.string.glg_exempt_desc, exemptIds.size),
                                    onClick = { openExemptPicker() },
                                )
                            }
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_banned_title),
                                    description = localizedContext.getString(R.string.glg_banned_desc2, bannedKeys.size),
                                    onClick = { showBanList(context) },
                                )
                            }
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_logs_title),
                                    description = localizedContext.getString(R.string.glg_logs_desc, logSnapshot().size),
                                    onClick = { showLogs(context) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button({
                        action = actionInput
                        cooldownMs = (cooldown.toLongOrNull() ?: cooldownMs).coerceIn(0L, 3_600_000L)
                        hintText = hint
                        maxTextLength = (maxLength.toIntOrNull() ?: maxTextLength).coerceIn(0, 99999)
                        rules = rulesInput.trim()
                        detectTextLink = textLink
                        detectCardLink = cardLink
                        detectMiniApp = miniApp
                        detectContactCard = contactCard
                        detectImage = image
                        detectVideo = video
                        detectVoice = voice
                        detectFile = file
                        nightEnabled = night
                        nightStartHour = (nightStart.toIntOrNull() ?: nightStartHour).coerceIn(0, 23)
                        nightEndHour = (nightEnd.toIntOrNull() ?: nightEndHour).coerceIn(0, 23)
                        nightHintText = nightHint
                        notifyEnabled = notify
                        welcomeText = welcomeInput
                        leaveText = leaveInput
                        cardEnabled = card
                        newbieKickEnabled = newbie
                        newbieMinutes = (newbieMinutesInput.toIntOrNull() ?: newbieMinutes).coerceIn(1, 9999)
                        floodEnabled = flood
                        floodWindowSec = (floodWindow.toIntOrNull() ?: floodWindowSec).coerceIn(1, 3600)
                        floodCount = (floodLimit.toIntOrNull() ?: floodCount).coerceIn(2, 999)
                        atAllEnabled = atAll
                        ownerExempt = owner
                        ladderEnabled = ladder
                        lastHandledAt.clear()
                        msgTimes.clear()
                        strikes.clear()
                        showToast(localizedContext.getString(R.string.glg_saved))
                        onDismiss()
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showBanList(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            AlertDialogContent(
                title = { Text(stringResource(R.string.glg_banned_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        val items = bannedKeys
                        if (items.isEmpty()) {
                            Text(stringResource(R.string.glg_banned_empty), style = MaterialTheme.typography.bodyMedium)
                        }
                        items.forEach { ban ->
                            BaseWidget(
                                iconPlaceholder = false,
                                title = groupName(ban.groupId),
                                description = memberName(ban.groupId, ban.memberId),
                                onClick = {
                                    WeGroupApi.inviteMember(ban.groupId, ban.memberId)
                                    banned = saveSet(loadSet(banned) - "${ban.groupId}|${ban.memberId}")
                                    strikes.remove("${ban.groupId}|${ban.memberId}")
                                    showToast(localizedContext.getString(R.string.glg_pulled_back))
                                },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showLogs(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            AlertDialogContent(
                title = { Text(stringResource(R.string.glg_logs_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        val items = logSnapshot()
                        if (items.isEmpty()) {
                            Text(stringResource(R.string.glg_logs_empty), style = MaterialTheme.typography.bodyMedium)
                        }
                        items.forEach { entry ->
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "${timeFormat.format(Date(entry.time))}  ${memberName(entry.groupId, entry.memberId)}",
                                description = "${groupName(entry.groupId)} · ${entry.reason} · ${actionLabel(entry.actionTaken)}",
                                onClick = null,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button({
                        synchronized(logLock) { logs.clear() }
                        showToast(localizedContext.getString(R.string.glg_logs_cleared))
                        onDismiss()
                    }) { Text(stringResource(R.string.glg_logs_clear)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun logSnapshot(): List<HandleLog> = synchronized(logLock) { logs.toList() }

    private fun actionLabel(value: Int): String = when (value) {
        ACTION_KICK_ONLY -> "kick"
        ACTION_HINT_ONLY -> "hint"
        ACTION_KICK_AND_BAN -> "kick+ban"
        else -> "kick+hint"
    }

    private fun groupName(groupId: String): String =
        runCatching { WeDatabaseApi.getGroup(groupId)?.nickname }.getOrNull()?.takeIf { it.isNotBlank() } ?: groupId

    private fun memberName(groupId: String, memberId: String): String =
        runCatching { WeDatabaseApi.getGroupMemberDisplayName(groupId, memberId) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { WeDatabaseApi.getDisplayName(memberId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: memberId

    @Composable
    private fun SectionLabel(text: String) {
        Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge)
    }

    @Composable
    private fun SwitchRow(titleRes: Int, descRes: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
        SwitchWidget(
            iconPlaceholder = false,
            title = stringResource(titleRes),
            description = descRes?.let { stringResource(it) },
            checked = checked,
            onCheckedChange = onChange,
        )
    }

    @Composable
    private fun FieldRow(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        description: String? = null,
        singleLine: Boolean = true,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    private fun digitsOnly(value: String, maxLen: Int): String = value.filter { it.isDigit() }.take(maxLen)

    private data class Verdict(val reason: String, val action: Int? = null)

    private const val MAX_LOGS = 300
    private const val MAX_WAIT_MS = 5_000L

    private const val TYPE_TEXT = 1
    private const val TYPE_SYSTEM = 10000
    private const val TYPE_LINK = 5
    private const val TYPE_IMAGE = 3
    private const val TYPE_VOICE = 34
    private const val TYPE_VIDEO = 43
    private const val TYPE_MICRO_VIDEO = 62
    private const val TYPE_APP = 49
    private const val TYPE_CARD = 42

    private const val REASON_TEXT_URL = "text_url"
    private const val REASON_LINK_CARD = "link_card"
    private const val REASON_MINIAPP = "miniapp"
    private const val REASON_CONTACT_CARD = "contact_card"
    private const val REASON_IMAGE = "image"
    private const val REASON_VIDEO = "video"
    private const val REASON_VOICE = "voice"
    private const val REASON_FILE = "file"
    private const val REASON_TOO_LONG = "too_long"
    private const val REASON_NIGHT = "night_silence"
    private const val REASON_FLOOD = "flood"
    private const val REASON_AT_ALL = "at_all"
    private const val REASON_NEWBIE = "newbie"
    private const val REASON_RULE = "rule:"

    private val URL_PATTERN = Regex("""https?://\S+|www\.[A-Za-z0-9.\-]+""")
}
