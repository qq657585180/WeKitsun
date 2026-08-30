# 需求文档：语音消息（Voice Messages）

Feature Name: voice-messages
Updated: 2026-08-30

## Introduction

WeKit 新增语音消息功能：在微信聊天中通过长按消息菜单打开语音面板，将文字转为语音（Fish Audio TTS）、管理本地语音包、从自定义在线目录下载语音包，并以微信原生语音消息的形式发送到当前会话。参考 Wtonec 的功能形态，第一期覆盖核心链路：TTS 生成、语音包管理、SILK 编码与微信语音发送。

## Glossary

- **语音面板**：长按消息菜单入口打开的 Compose 对话框，包含文字转语音、语音包两个标签页。
- **TTS**：Text-To-Speech，文字转语音服务。第一期仅支持 Fish Audio。
- **Fish Audio**：在线 TTS 服务，提供预设音色与用户克隆音色（reference_id）。
- **音色**：TTS 说话人声音，由 Fish Audio 的 reference_id 标识。
- **语音包**：用户可复用的语音条目，包含标题与音频数据，存于 WeKit 语音库。
- **在线目录源**：用户自定义的 JSON 格式语音目录 URL，WeKit 从该地址拉取可下载语音列表。
- **SILK**：腾讯微信语音消息使用的音频编码格式。发送前须将音频转换为 24kHz 单声道 SILK。
- **发送链**：微信内部发送语音消息的调用路径，由 WeKit 通过 DexKit 定位并 hook。
- **独立配置**：存于 MMKV 的本功能专属配置（API Key、音色、目录源 URL），与智能回复及 WeAgent 配置隔离。

## Requirements

### Requirement 1: 语音面板入口

**User Story:** 作为用户，我想在聊天中通过长按消息菜单或聊天工具栏打开语音面板，以便随时快速发送语音消息。

#### Acceptance Criteria

1. WHEN 用户在消息长按菜单中点击「语音消息」菜单项，WeKit SHALL 在当前聊天页之上显示语音面板对话框。
2. WHEN 用户点击聊天工具栏中的「语音消息」注入条目，WeKit SHALL 在当前聊天页之上显示语音面板对话框。
3. WHEN 语音面板打开时，WeKit SHALL 显示文字转语音标签页作为默认页。
4. WHILE 语音面板处于打开状态，WeKit SHALL 提供「文字转语音」与「语音包」两个标签页的切换控件。
5. WHEN 用户点击面板关闭控件，WeKit SHALL 关闭语音面板。

### Requirement 2: Fish Audio 文字转语音

**User Story:** 作为用户，我想输入文字并选择音色生成语音，以便发送自定义声音的消息。

#### Acceptance Criteria

1. WHEN 用户在文字转语音标签页输入文字并点击生成，WeKit SHALL 调用 Fish Audio TTS 接口以当前选中音色生成音频。
2. WHEN 生成成功时，WeKit SHALL 自动试听播放生成的音频，并提供「保存」「发送」两个操作按钮。
3. WHEN 用户点击「保存」，WeKit SHALL 将生成的音频以可编辑标题写入语音包库。
4. IF Fish Audio 返回错误或网络请求失败，WeKit SHALL 在面板内显示错误信息且保持已输入文字与音色选择不变。
5. WHILE 生成请求进行中，WeKit SHALL 显示加载状态并禁用生成按钮。

### Requirement 3: 音色选择

**User Story:** 作为用户，我想在预设音色与我自己保存的克隆音色之间选择，以便控制生成语音的声音。

#### Acceptance Criteria

1. WHEN 用户打开音色选择控件，WeKit SHALL 显示用户保存的全部音色（名称 + reference_id）。
2. WHEN 用户选中一个音色，WeKit SHALL 将该音色记为当前生成音色并持久化。
3. IF 未保存任何音色且未配置 API Key，WeKit SHALL 在生成时提示未完成配置并打开独立配置页。
4. WHEN 用户在独立配置页新增、编辑或删除音色条目（显示名称 + reference_id），WeKit SHALL 持久化变更并即时反映到音色选择列表。

### Requirement 4: 语音包管理

**User Story:** 作为用户，我想管理本地语音包（浏览、试听、重命名、删除、导入），以便复用常用语音。

#### Acceptance Criteria

1. WHEN 用户切换到语音包标签页，WeKit SHALL 按最近添加顺序显示全部语音包条目（标题、时长）。
2. WHEN 用户点击某条目的试听控件，WeKit SHALL 播放该条目音频，再次点击 SHALL 停止播放。
3. WHEN 用户对某条目执行重命名，WeKit SHALL 更新标题并持久化。
4. WHEN 用户确认删除某条目，WeKit SHALL 从语音包库移除该条目及其音频文件。
5. WHEN 用户通过导入控件选择设备上的音频文件（MP3/WAV/M4A/AAC），WeKit SHALL 将其解码入库为语音包条目。
6. IF 导入的文件无法解码，WeKit SHALL 提示导入失败并保留语音包库原状。

### Requirement 5: 在线语音包目录

**User Story:** 作为用户，我想配置一个自定义在线目录源并下载其中的语音，以便扩充语音包。

#### Acceptance Criteria

1. WHEN 用户配置目录源 URL 并刷新，WeKit SHALL 请求该 URL 并解析 JSON 格式的语音条目列表（名称 + 音频 URL）。
2. WHEN 用户选择某在线条目下载，WeKit SHALL 下载音频到本地缓存并显示下载状态。
3. WHEN 下载完成，WeKit SHALL 将音频解码入库为语音包条目。
4. IF 目录源请求失败或 JSON 格式无效，WeKit SHALL 显示失败原因且保留已缓存的条目可用。
5. WHILE 下载进行中，WeKit SHALL 允许用户取消该下载任务。

### Requirement 6: 语音消息发送

**User Story:** 作为用户，我想将试听满意或选中的语音以微信原生语音消息发送到当前会话，以便对方收到语音。

#### Acceptance Criteria

1. WHEN 用户在语音包条目或生成结果上点击「发送」，WeKit SHALL 将音频转换为 24kHz 单声道 SILK 格式。
2. WHEN SILK 编码完成，WeKit SHALL 通过微信语音发送链将语音以当前会话的语音消息形式发出。
3. IF 音频解码或 SILK 编码失败，WeKit SHALL 显示错误信息且保持会话状态不变。
4. IF 发送链调用失败，WeKit SHALL 显示错误信息。
5. WHILE 转换与发送进行中，WeKit SHALL 禁用发送按钮并显示进行状态。

### Requirement 7: 独立配置

**User Story:** 作为用户，我想通过面板内齿轮入口配置 Fish Audio API Key、音色与目录源，以便集中管理本功能设置。

#### Acceptance Criteria

1. WHEN 用户点击语音面板标题栏的齿轮图标，WeKit SHALL 显示独立配置对话框（与主面板分离，主面板状态保留）。
2. WHEN 用户在配置对话框修改 API Key、音色条目或目录源 URL 并保存，WeKit SHALL 写入 MMKV 独立配置键（voice_* 前缀）。
3. WHEN 用户点击配置对话框的 API 连接测试，WeKit SHALL 调用 Fish Audio 接口验证 Key 有效性并提示结果。
4. WHILE 配置对话框处于打开状态，WeKit SHALL 保持主语音面板的状态（输入文字、选中音色、标签页）不丢失。

### Requirement 8: 播放管理

**User Story:** 作为用户，我希望同一时间只播放一段音频，以便避免多个声音叠加。

#### Acceptance Criteria

1. WHEN 任一音频开始播放（试听或生成后自动播放），WeKit SHALL 停止之前正在播放的音频。
2. WHEN 语音面板关闭，WeKit SHALL 释放播放器资源。
