package dev.ujhhgtg.wekit.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Volume_up
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.VectorPathDrawable

object VoiceMessageMenu : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val MENU_ID = 777029

    override val technicalId = "语音消息菜单"
    override val nameRes = R.string.feature_voice_message_menu_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_voice_message_menu_description

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = MENU_ID,
            text = "语音消息",
            drawable = VoiceMessageIcon(),
            imageVector = MaterialSymbols.Outlined.Volume_up,
            isSupported = { true },
        ) { _, context, _ ->
            VoicePanel.openPanel(context)
        },
    )
}

private class VoiceMessageIcon : VectorPathDrawable(
    "M3,9v6h4l5,5V4L7,9H3z M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.74 2.5,-2.26 2.5,-4.02z M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77S18.01,4.14 14,3.23z"
)
