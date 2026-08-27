# Japanese review — HiLight Studio

Manual localization review snapshot, with English beside Japanese and grouped by resource file. It
is not generated and may lag the XML resources; `app/src/main/res/values/` and `values-ja/` are the
source of truth. Update or regenerate this snapshot before treating it as an exhaustive string audit.

What to check, in the order it matters:

1. **Meaning** — does the Japanese say what the English says, no more and no less. Two places
   this matters most: the notification inspector's privacy statement (message text is never shown
   or exported) and the distinction between a chat matched by stable id versus by name.
2. **Register** — polite-neutral です・ます for sentences, bare noun phrases for labels, buttons
   and badges. No exclamation marks, no emoji.
3. **Terms** — consistency with `docs/i18n/ja-glossary.md`. The same English term must not appear
   as two different Japanese words.
4. **Length** — anything that would clip in a pill, a badge, a Quick Settings tile subtitle, or a
   third of a button row.
5. **Placeholders** — `%1$s`, `%1$d`, `%%` must survive, though their order may change.

Leave Latin as-is: HiLight, Shizuku, ADB, LED, JSON, MessagingStyle, shortcutId, Pixel, Gemini.

## `strings_common.xml`

| name | English | Japanese |
|---|---|---|
| `tab_live` | Live | ライブ |
| `tab_style` | Style | スタイル |
| `tab_apps` | Apps | アプリ |
| `tab_setup` | Setup | 設定 |
| `pattern_off` | Off | オフ |
| `pattern_solid` | Solid | 点灯 |
| `pattern_gradient` | Gradient | グラデーション |
| `pattern_breathe` | Breathe | 呼吸 |
| `pattern_blink` | Blink | 点滅 |
| `pattern_pulse` | Pulse | パルス |
| `pattern_chase` | Chase | 追いかけ |
| `pattern_comet` | Comet | コメット |
| `pattern_wave` | Wave | ウェーブ |
| `pattern_rainbow` | Rainbow | レインボー |
| `pattern_rainbow_short` | Rainbow | 虹 |
| `pattern_random` | Random colours | ランダムな色 |
| `pattern_custom` | Per-LED custom | LED ごとに設定 |
| `cycle_breathe` | One full breath: dim up to full, back down. | ひと呼吸分です。暗い状態から最大まで明るくなり、また暗くなります。 |
| `cycle_blink` | One on-off pair — lit for the first half. | 点灯と消灯の 1 組です。前半が点灯です。 |
| `cycle_pulse` | One flash: snap to full, then fade away. | 1 回の閃光です。一気に最大まで明るくなり、そのまま消えていきます。 |
| `cycle_chase` | One lap of a single lit LED around all eight. | 点灯した 1 個の LED が 8 個を 1 周します。 |
| `cycle_comet` | One lap of the comet head, its tail trailing 3 LEDs. | コメットの先頭が 1 周します。尾は LED 3 個分です。 |
| `cycle_wave` | One wave travelling once across the array. | 波が LED アレイを 1 回横切ります。 |
| `cycle_rainbow` | One trip through every hue, back to the start. | すべての色相を一巡して元に戻ります。 |
| `suppression_quiet_hours` | Quiet hours | サイレント時間 |
| `suppression_low_battery` | Low battery | 電池残量が少ない |
| `suppression_power_saver` | Battery Saver | バッテリーセーバー |
| `suppression_screen_on` | Screen-off only | 画面消灯時のみ |
| `transport_auto` | Auto | 自動 |
| `transport_shizuku` | Shizuku | Shizuku |
| `transport_adb` | ADB helper | ADB ヘルパー |
| `duration_ms` | %1$dms | %1$dミリ秒 |
| `duration_seconds_fraction` | %1$ss | %1$s秒 |
| `duration_seconds` | %1$ds | %1$d秒 |
| `duration_minutes` | %1$dm | %1$d分 |
| `duration_minutes_seconds` | %1$dm %2$ds | %1$d分%2$d秒 |
| `common_cancel` | Cancel | キャンセル |
| `common_save` | Save | 保存 |
| `common_close` | Close | 閉じる |
| `common_edit` | Edit | 編集 |
| `common_test` | Test | テスト |
| `common_delete` | Delete | 削除 |
| `common_continue` | Continue | 続行 |
| `common_keep_it_short` | Keep it short | 短いままにする |
| `common_none` | none | なし |
| `common_i_understand` | I understand | 理解しました |
| `common_off_lowercase` | off | オフ |
| `common_percent` | %1$d%% | %1$d%% |
| `device_generic` | this device | この端末 |

## `strings_inspector.xml`

| name | English | Japanese |
|---|---|---|
| `inspector_title` | Notification inspector | 通知インスペクター |
| `inspector_copy_json` | Copy as JSON | JSON としてコピー |
| `inspector_send` | Send | 送信 |
| `inspector_privacy_note` | Names, ids and structure only. Message text is never shown here and never exported. | 名前と ID、構造だけです。メッセージ本文はここに表示されず、出力にも含まれません。 |
| `inspector_seen_one` | The last notification HiLight saw. | HiLight が最後に検知した通知です。 |
| `inspector_seen_many` | The last %1$d notifications HiLight saw, newest first. | HiLight が最後に検知した %1$d 件の通知です。新しい順に並んでいます。 |
| `inspector_export_note` | HiLight notification inspector, newest first. %1$s | HiLight の通知インスペクター、新しい順です。%1$s |
| `inspector_copied_toast` | Copied as JSON | JSON としてコピーしました |
| `inspector_share_chooser_title` | Send inspector output | 通知インスペクターの出力を送信 |
| `inspector_empty_title` | Nothing seen yet | まだ何も検知していません |
| `inspector_empty_body` | Notifications appear here as they arrive, so leave this open — or reopen it once one has landed. | 通知は届いた時点でここに表示されます。この画面を開いたままにしておくか、通知が届いてから開き直してください。 |
| `inspector_empty_access` | Nothing is seen at all without notification access. The list is kept in memory only, so it empties whenever HiLight stops. | 通知へのアクセスがないと、通知はまったく検知されません。一覧はメモリ上にだけ保持されるため、HiLight が停止するたびに空になります。 |
| `inspector_pill_read_failed` | could not read | 読み取り失敗 |
| `inspector_pill_name_found` | name found | 名前あり |
| `inspector_pill_no_name` | no name | 名前なし |
| `inspector_field_shortcut_id` | Shortcut id: %1$s | shortcutId: %1$s |
| `inspector_field_sender` | Sender: %1$s | 送信者: %1$s |
| `inspector_field_conversation` | Conversation: %1$s | チャット: %1$s |
| `inspector_field_title` | Title: %1$s | タイトル: %1$s |
| `inspector_field_messaging_style_detected` | MessagingStyle: detected | MessagingStyle: 検出 |
| `inspector_field_messaging_style_not_detected` | MessagingStyle: not detected | MessagingStyle: 未検出 |
| `inspector_read_failed_note` | This notification could not be read, so the fields below are missing rather than absent. That is a fault worth reporting with this output attached. | この通知は読み取れませんでした。以下の項目は、アプリが値を設定しなかったのではなく、読み取りに失敗して欠けています。この出力を添えて報告する価値のある不具合です。 |
| `inspector_group_summary` | Group summary, which HiLight ignores so a chat cannot fire twice. | グループ通知のまとめです。同じチャットで 2 回点灯しないよう、HiLight は無視します。 |
| `inspector_no_name_note` | This app gave no name to match on, so no per-chat rule can be written from it. A rule for the whole app still works. | このアプリは一致に使える名前を渡していないため、この通知から連絡先別ルールを作ることはできません。アプリ全体のルールならそのまま使えます。 |
| `service_watcher_channel_name` | HiLight app watcher | HiLight のアプリ監視 |
| `service_watcher_title` | HiLight Studio | HiLight Studio |
| `service_watcher_text` | Watching for apps with light rules | 点灯ルールのあるアプリを監視中 |

## `strings_live.xml`

| name | English | Japanese |
|---|---|---|
| `live_status_unavailable` | HiLight not available | HiLight 非対応 |
| `live_status_testing` | Testing · %1$s | テスト中 · %1$s |
| `live_status_on` | HiLight · %1$s | HiLight · %1$s |
| `live_status_system` | HiLight is with the system | HiLight はシステムが制御中 |
| `live_hint_no_array` | %1$s has no HiLight array — the feature is Pro-only. | %1$s に HiLight の LED アレイはありません。Pro モデル専用の機能です。 |
| `live_hint_no_renderer` | Connect a renderer in Setup to drive the array. | LED アレイを制御するには、設定タブでレンダラーを接続してください。 |
| `live_hint_look` | Turn the phone over to see it for real. | 実際の点灯を見るには、端末を裏返してください。 |
| `live_hint_take_over` | Take over the array with the switch below. | 下のスイッチで LED アレイの制御を引き継げます。 |
| `live_toggle_driving` | Driving HiLight | HiLight を制御中 |
| `live_toggle_system` | System has HiLight | システムが HiLight を制御中 |
| `live_suppressed_quiet_hours` | Quiet hours: the array stays dark until your window ends. | サイレント時間です。設定した時間帯が終わるまで LED アレイは消灯したままです。 |
| `live_suppressed_low_battery` | Battery is low, so the array is paused. Charging resumes it. | 電池残量が少ないため、LED アレイを一時停止しています。充電すると再開します。 |
| `live_suppressed_power_saver` | Battery Saver is on, so the array is paused. | バッテリーセーバーがオンのため、LED アレイを一時停止しています。 |
| `live_suppressed_screen_on` | Set to light only while the screen is off. | 画面消灯時のみ点灯する設定になっています。 |
| `live_safety_resting` | Resting to protect the LEDs — they have been lit too much recently. | LED を保護するため休止しています。直近の点灯時間が長すぎました。 |
| `live_safety_timed_out` | Auto-off reached. Change the style or flip the switch to light it again. | 自動オフに達しました。もう一度点灯するには、スタイルを変えるかスイッチを切り替えてください。 |
| `live_safety_countdown` | Auto-off in %1$ds · duty used %2$d%% | 自動オフまで %1$d秒 · 点灯時間 %2$d%% |
| `live_renderer_pid` | renderer pid %1$d · %2$s | レンダラー pid %1$d · %2$s |
| `live_session_open` | session open | セッションあり |
| `live_session_closed` | session closed | セッションなし |
| `live_tests_title` | Try an effect | エフェクトを試す |
| `live_tests_caption` | Fires for four seconds on the real LEDs, then returns to your style. | 実際の LED で 4 秒間点灯してから、元のスタイルに戻ります。 |
| `live_test_random` | Random | ランダム |
| `live_rules_title` | App rules | アプリ別ルール |
| `live_rules_on_count` | %1$d on | %1$d 件オン |
| `live_rules_empty` | Nothing yet. Add per-app colours in the Apps tab. | まだありません。アプリタブでアプリ別の色を追加できます。 |
| `live_rule_summary` | %1$s · %2$s | %1$s · %2$s |
| `live_rule_random` | random | ランダム |
| `live_rule_notify` | notify | 通知 |
| `live_rule_in_app` | in app | 使用中 |
| `tile_label` | HiLight | HiLight |
| `tile_no_renderer` | No renderer | 未接続 |
| `tile_off` | Off | 消灯 |
| `tile_resting` | Resting | 休止中 |
| `tile_timed_out` | Timed out | 自動オフ |
| `widget_colour` | Colour | 色 |
| `widget_saturation` | Saturation | 彩度 |
| `widget_intensity` | Intensity | 明度 |
| `widget_percent` | %1$d%% | %1$d%% |

## `strings_main.xml`

| name | English | Japanese |
|---|---|---|
| `main_connected_pill` | %1$d LEDs · %2$s | LED %1$d 個 · %2$s |
| `main_not_connected` | not connected | 未接続 |
| `hero_description` | %1$s, camera bar with the HiLight array. %2$s | %1$s、HiLight の LED アレイがあるカメラバーです。%2$s |
| `hero_no_array` | This model has no HiLight array. | この機種に HiLight の LED アレイはありません。 |
| `hero_array_off` | The array is off. | LED アレイはオフです。 |
| `hero_showing` | Showing %1$s. | %1$s を表示しています。 |
| `hero_strip_preview` | Preview of %1$s across eight LEDs | %1$s のプレビュー、LED 8 個 |
| `hero_strip_off` | Preview off: %1$s | プレビューはオフです: %1$s |

## `strings_rules.xml`

| name | English | Japanese |
|---|---|---|
| `rules_section_title` | Per-app rules | アプリ別ルール |
| `rules_intro_apps` | Choose an app, then what HiLight does when it notifies you — or while it is open. | アプリを選び、そのアプリから通知が届いたとき、またはアプリ表示中に HiLight が何をするかを設定します。 |
| `rules_intro_messaging` | Messaging apps go one step further: a colour for a single contact or chat. | メッセージアプリではもう一歩進んで、特定の連絡先やチャットだけに色を設定できます。 |
| `rules_add` | Add app rule | アプリ別ルールを追加 |
| `rules_card_summary` | %1$s · %2$s | %1$s · %2$s |
| `rules_random_colour` | Random colour | ランダムな色 |
| `rules_trigger_notification_short` | on notification | 通知時 |
| `rules_trigger_foreground_short` | while open | 表示中 |
| `rules_last_matched` | Last matched %1$s | 最終一致 %1$s |
| `rules_not_matched_yet` | Not matched yet | まだ一致なし |
| `rules_badge_groups_too` | Groups too | グループも |
| `rules_picker_title` | Choose an app | アプリを選択 |
| `rules_picker_search` | Search | 検索 |
| `rules_any_app` | Any app | すべてのアプリ |
| `rules_any_app_caption` | Catch-all for apps without their own rule | 個別のルールがないアプリすべてに適用されます。 |
| `rules_editor_title_chat` | %1$s › %2$s | %1$s › %2$s |
| `rules_per_chat_notifications_only` | Per-chat rules fire on notifications. | 連絡先別ルールは通知時に発動します。 |
| `rules_trigger_notification` | On notification | 通知時 |
| `rules_trigger_foreground` | While open | アプリ表示中 |
| `rules_random_colour_each_time` | Random colour each time | 毎回ランダムな色 |
| `rules_chat_is_group` | This chat is a group, so anything posted in it fires the rule. | このチャットはグループのため、ここでの投稿すべてでルールが発動します。 |
| `rules_include_groups` | Also when they post in a group | グループでの発言も対象にする |
| `rules_include_groups_hint` | Left off, only their own chat lights this colour, so the group chatter they are part of stays dark. | オフのままにすると、その相手との個別チャットだけがこの色で点灯し、参加しているグループチャットは消灯したままになります。 |
| `rules_keyword_label` | Only if it mentions (optional) | この語を含む場合のみ（任意） |
| `rules_only_screen_off` | Only when the screen is off | 画面消灯時のみ |
| `rules_time_per_cycle` | Time per cycle | 1 周期の時間 |
| `rules_brightness` | Brightness | 明るさ |
| `rules_test_on_leds` | Test on the LEDs | LED でテスト |
| `rules_show_for` | Show for | 点灯時間 |
| `rules_allow_one_minute` | Allow up to 1 minute | 最大 1 分まで許可 |
| `rules_duration_warn_first_title` | Longer than 30 seconds? | 30 秒より長くしますか |
| `rules_duration_warn_first_body` | Every notification from this app would light the array for that long, which costs battery and is far beyond what stock HiLight does. | このアプリから通知が届くたびに、LED アレイがその時間ずっと点灯します。電池を消費し、標準の HiLight の動作をはるかに超えます。 |
| `rules_duration_warn_second_title` | Are you sure? | 本当に設定しますか |
| `rules_duration_warn_second_body` | A busy app can fire often, so the LEDs may end up lit most of the time. You can turn this back down at any time. | 通知の多いアプリでは頻繁に発動するため、LED がほとんどの時間点灯したままになることがあります。設定はいつでも戻せます。 |
| `rules_replace_warning` | An existing rule for this app already covers that, and saving will replace its settings with these. | このアプリには同じ対象の既存のルールがすでにあり、保存するとその設定がこの内容に置き換わります。 |
| `rules_match_by_id` | Matched by chat id, which survives the chat being renamed. | チャット ID で一致するため、チャットの名前が変わっても一致し続けます。 |
| `rules_match_id_dropped` | The stored chat id is dropped when you save, and a fresh one is learned the next time that chat messages. | 保存すると保存済みのチャット ID を削除し、そのチャットから次にメッセージが届いたときに新しい ID を取得します。 |
| `rules_match_by_name` | Matched by name, so renaming the chat in %1$s can stop it matching. | 名前で一致するため、%1$s でチャットの名前を変更すると一致しなくなることがあります。 |
| `rules_relearn_chat` | Re-learn this chat | このチャットを再取得 |
| `rules_relearn_hint` | Forgets the stored chat id and matches on the name instead, taking up the new id the next time that chat messages — the repair for an id changed by a reinstall or a restored backup. | 保存済みのチャット ID を削除して名前で一致するようにし、そのチャットから次にメッセージが届いたときに新しい ID を取得します。再インストールやバックアップの復元で ID が変わった場合の修復手段です。 |
| `chat_scope_title` | What should this rule cover? | このルールは何を対象にしますか |
| `chat_scope_whole_app` | All notifications from %1$s | %1$s のすべての通知 |
| `chat_scope_whole_app_hint` | Any chat, any sender. | すべてのチャット、すべての送信者が対象です。 |
| `chat_scope_one_chat` | One contact or chat | 特定の連絡先またはチャット |
| `chat_scope_one_chat_hint` | A colour for them alone, chosen from the chats HiLight has seen. | その相手だけに色を設定します。HiLight が検出したチャットから選べます。 |
| `chat_picker_title` | Choose a chat | チャットを選択 |
| `chat_picker_intro` | Which chat in %1$s this rule watches. | このルールが監視する %1$s のチャットを選びます。 |
| `chat_seen_list_header` | Chats HiLight has seen, newest first. | HiLight が検出したチャットを新しい順に表示しています。 |
| `chat_no_chats_yet` | No chats to list yet: HiLight has not seen a message from %1$s. | 表示できるチャットはまだありません。HiLight は %1$s からのメッセージを検出していません。 |
| `chat_badge_group` | Group | グループ |
| `chat_last_message` | Last message %1$s | 最終メッセージ %1$s |
| `chat_learn_next` | Learn the next message | 次のメッセージから取得 |
| `chat_learn_next_hint` | The surest way. The name comes from a real notification, so it is exactly what HiLight compares against later. | 最も確実な方法です。実際の通知から名前を取得するため、HiLight が後で比較する文字列とまったく同じになります。 |
| `chat_pick_contact` | Pick from contacts | 連絡先から選択 |
| `chat_pick_contact_hint` | Opens the system picker, so HiLight reads only the contact you tap and never your address book. If the app shows a different name for them, the rule can miss. | システムの選択画面を開きます。HiLight はタップした連絡先だけを読み取り、アドレス帳全体を読み取ることはありません。アプリ側で別の名前が表示されている場合、ルールが一致しないことがあります。 |
| `chat_listening` | Listening | 待機中 |
| `chat_waiting` | Waiting for the next message from %1$s… | %1$s からの次のメッセージを待っています… |
| `chat_waiting_hint` | Open the chat you want and send something, or wait for them to write. | 対象のチャットを開いて何か送信するか、相手からのメッセージを待ってください。 |
| `chat_stop_waiting` | Stop waiting | 待機を停止 |
| `chat_captured` | Captured: %1$s — use this? | %1$s を取得しました。これを使いますか |
| `chat_captured_has_id` | This chat came with a stable id, so renaming it will not break the rule. | このチャットには固定のチャット ID が付いていたため、名前を変更してもルールは動作し続けます。 |
| `chat_captured_no_id` | No chat id came with it, so the rule will match on the name. | チャット ID は付いていなかったため、ルールは名前で一致します。 |
| `chat_use_this` | Use this chat | これを使う |
| `chat_wait_another` | Wait for another | もう一度待つ |
| `chat_not_listening` | Not listening | 待機できません |
| `chat_no_access_title` | HiLight cannot watch for the next message yet. | HiLight はまだ次のメッセージを待てません。 |
| `chat_no_access_body` | Without notification access HiLight sees no notifications at all, and Android withdraws that access on its own once an app has gone unused for a while. | 通知へのアクセスがないと、HiLight は通知をまったく受け取れません。また Android は、アプリが一定期間使われないとこのアクセスを自動的に取り消します。 |
| `chat_open_notification_access` | Open notification access | 通知へのアクセスを開く |
| `chat_contact_no_name` | That contact had no name to read. Try one of the other two ways. | その連絡先から読み取れる名前がありませんでした。他の 2 つの方法を試してください。 |
| `chat_unusable` | That chat has no name HiLight can compare and no stable id either, so a rule for it could never fire. A rule for the whole app still works. | そのチャットには HiLight が比較できる名前も固定のチャット ID もないため、ルールを作っても発動しません。アプリ全体を対象にするルールなら動作します。 |
| `chat_ago_just_now` | just now | たった今 |
| `chat_ago_over_a_week` | over a week ago | 1週間以上前 |
| `chat_ago_unknown` | a while ago | しばらく前 |
| `chat_ago_minutes [one]` | %1$d min ago | — (Japanese has no singular form) |
| `chat_ago_minutes [other]` | %1$d min ago | %1$d分前 |
| `chat_ago_hours [one]` | %1$d hour ago | — (Japanese has no singular form) |
| `chat_ago_hours [other]` | %1$d hours ago | %1$d時間前 |
| `chat_ago_yesterday` | yesterday | 昨日 |
| `chat_ago_days [other]` | %1$d days ago | %1$d日前 |

## `strings_setup.xml`

| name | English | Japanese |
|---|---|---|
| `setup_auto_off_title` | Auto-off | 自動オフ |
| `setup_auto_off_body` | The always-on look switches itself off after this. App rules still work. | 常時点灯スタイルはこの時間が過ぎると自動でオフになります。アプリ別ルールはそのまま動作します。 |
| `setup_auto_off_protection` | Hardware protection is always on: brightness eases down after 10s of unbroken light, and the array rests if it has been lit for more than half of the last 10 minutes. | ハードウェア保護は常に有効です。10 秒続けて点灯すると明るさが徐々に下がり、直近 10 分のうち半分以上点灯していた場合は LED アレイが休止します。 |
| `setup_stay_on_for` | Stay on for | 点灯時間 |
| `setup_allow_five_minutes` | Allow up to 5 minutes | 最大 5 分まで許可 |
| `setup_warn_long_title` | Longer than 30 seconds? | 30 秒より長くしますか |
| `setup_warn_long_body` | The LEDs draw power the whole time they are lit, and stock HiLight only flashes for a moment — nothing about the hardware is built for minutes of continuous light. | LED は点灯している間ずっと電力を消費します。標準の HiLight は一瞬光るだけで、このハードウェアは数分間の連続点灯を想定して作られていません。 |
| `setup_warn_long_confirm_title` | Are you sure? | 本当に設定しますか |
| `setup_warn_long_confirm_body` | Up to 5 minutes of continuous illumination will cost battery, and animations freeze lit if the phone sleeps. You can turn this back down at any time. | 最大 5 分の連続点灯は電池を消費します。端末がスリープに入るとアニメーションは点灯したまま停止します。設定はいつでも戻せます。 |
| `setup_dark_title` | When to stay dark | 消灯する条件 |
| `setup_screen_off_only` | Only while the screen is off | 画面消灯時のみ |
| `setup_quiet_from` | From %1$s | 開始 %1$s |
| `setup_quiet_until` | Until %1$s | 終了 %1$s |
| `setup_quiet_dim` | Dim instead of dark | 消灯せずに暗くする |
| `setup_dim_to` | Dim to | 明るさ |
| `setup_respect_dnd` | Respect Do Not Disturb | サイレントモードに従う |
| `setup_pause_saver` | Pause in Battery Saver | バッテリーセーバー中は停止 |
| `setup_pause_low_battery` | Pause on low battery | 電池残量が少ないときは停止 |
| `setup_pause_below` | Pause below | 停止する残量 |
| `setup_battery_note` | Ignored while charging. Battery Saver pauses it either way. | 充電中は適用されません。バッテリーセーバー中はいずれの場合も停止します。 |
| `setup_percent` | %1$d%% | %1$d%% |
| `setup_privileged_title` | Privileged access | 特権アクセス |
| `setup_privileged_body` | The renderer needs shell-UID privileges. Choose how it starts. | レンダラーには shell UID の権限が必要です。起動方法を選んでください。 |
| `setup_transport_auto_note` | Prefers Shizuku, falls back to ADB. | Shizuku を優先し、使えない場合は ADB を使います。 |
| `shizuku_state_connected` | connected | 接続済み |
| `shizuku_state_connecting` | connecting | 接続中 |
| `shizuku_state_needs_permission` | approve it | 許可が必要 |
| `shizuku_state_not_running` | not running | 未起動 |
| `shizuku_state_not_installed` | not installed | 未インストール |
| `shizuku_state_failed` | failed | 失敗 |
| `shizuku_reattach_note` | After Shizuku restarts (or a reboot), reopen this app once to reattach. | Shizuku を再起動した後、または端末を再起動した後は、このアプリを一度開き直して再接続してください。 |
| `shizuku_not_installed_body` | No computer needed — start it via Wireless debugging. | パソコンは不要です。ワイヤレスデバッグから起動できます。 |
| `shizuku_get` | Get Shizuku | Shizuku を入手 |
| `shizuku_not_running_body` | Start it under Wireless debugging. Needed again after each reboot. | ワイヤレスデバッグから起動してください。端末を再起動するたびに必要です。 |
| `shizuku_open` | Open Shizuku | Shizuku を開く |
| `shizuku_check_again` | Check again | 再確認 |
| `shizuku_needs_permission_body` | Running. Approve this app to use it. | 起動しています。このアプリの使用を許可してください。 |
| `shizuku_request_access` | Request access | 許可を求める |
| `shizuku_connected_body` | Renderer running in Shizuku\'s shell-UID process. | レンダラーは Shizuku の shell UID プロセスで動作しています。 |
| `shizuku_disconnect` | Disconnect | 切断 |
| `shizuku_error_dead_binder` | service returned a dead binder | サービスが無効なバインダーを返しました |
| `shizuku_error_too_old` | Shizuku is too old; v12 or newer is required | Shizuku のバージョンが古すぎます。v12 以降が必要です |
| `shizuku_error_renderer_incompatible` | The renderer did not match this app. Restart Shizuku and try again. | レンダラーがこのアプリと一致しません。Shizuku を再起動して、もう一度お試しください。 |
| `shizuku_unreachable` | Could not reach Shizuku. | Shizuku に接続できませんでした。 |
| `shizuku_retry` | Retry | 再試行 |
| `setup_no_browser` | No browser available | 利用できるブラウザがありません |
| `adb_title` | ADB | ADB |
| `adb_body` | Run the one copied command with the phone plugged in. Nothing to push. Re-run after a reboot. It stops old renderers, waits for confirmed exit, then starts one new helper. | 端末を接続した状態で、コピーした 1 つのコマンドを実行してください。転送するファイルはありません。端末を再起動したら再実行が必要です。このコマンドは古いレンダラーを停止し、終了を確認してから、新しいヘルパーを 1 つ起動します。 |
| `adb_shells_note` | Works in Terminal on macOS and Linux, and in PowerShell. In Windows Command Prompt, copy the cmd.exe version instead — it has no single quotes. | macOS と Linux のターミナル、および PowerShell で動作します。Windows のコマンドプロンプトでは、シングルクォートを使わない cmd.exe 用をコピーしてください。 |
| `adb_copy` | Copy | コピー |
| `adb_copy_cmd` | Copy for cmd.exe | cmd.exe 用をコピー |
| `adb_copied` | Command copied | コマンドをコピーしました |
| `adb_copied_cmd` | cmd.exe version copied | cmd.exe 用をコピーしました |
| `adb_verify_note` | The command normally prints nothing because the helper runs in the background and writes to its log. The array response or helper log confirms startup. | ヘルパーはバックグラウンドで動作し、出力はログに書き込まれるため、通常このコマンドは何も表示しません。LED アレイの反応またはヘルパーログで起動を確認してください。 |
| `adb_send` | Send to computer | パソコンに送る |
| `adb_share_title` | Send command | コマンドを送信 |
| `setup_notif_title` | Notification access | 通知へのアクセス |
| `setup_state_granted` | granted | 許可済み |
| `setup_state_needed` | needed | 許可が必要 |
| `setup_notif_body` | Lets rules see which app notified you. | どのアプリから通知が届いたかをルールが判別できるようになります。 |
| `setup_open_notif_access` | Open notification access | 通知へのアクセスを開く |
| `setup_inspector_body` | Shows what HiLight reads from each notification. No message text. | HiLight が各通知から読み取った内容を表示します。メッセージ本文は含みません。 |
| `setup_inspector_button` | Notification inspector | 通知インスペクター |
| `setup_chats_none` | No chats remembered yet. Chats are listed here so per-contact rules need no typing. | 記憶されたチャットはまだありません。ここに一覧されることで、連絡先別ルールを作るときに名前を入力する必要がなくなります。 |
| `setup_chats_remembered` | %1$d chat names remembered on this device, so per-contact rules need no typing. Existing rules keep working if you clear them. | この端末に %1$d 件のチャット名を記憶しています。そのため連絡先別ルールを作るときに名前を入力する必要がありません。削除しても既存のルールはそのまま動作します。 |
| `setup_forget_chats_button` | Forget remembered chats | 記憶したチャットを削除 |
| `setup_forget_chats_title` | Forget remembered chats? | 記憶したチャットを削除しますか |
| `setup_forget_chats_body` | The list the per-contact picker offers is cleared. Rules you have already made keep working, and chats are remembered again as messages arrive. | 連絡先別ルールの選択画面に表示される一覧が消去されます。すでに作成したルールはそのまま動作し、メッセージが届くたびにチャットは再び記憶されます。 |
| `setup_forget_chats_confirm` | Forget them | 削除する |
| `setup_forget_chats_dismiss` | Keep them | 残す |
| `setup_usage_title` | Usage access | 使用状況へのアクセス |
| `setup_state_optional` | optional | 任意 |
| `setup_usage_body` | Only for \"while open\" rules. | 「アプリ表示中」ルールにのみ使用します。 |
| `setup_open_usage_access` | Open usage access | 使用状況へのアクセスを開く |
| `setup_appearance_title` | Appearance | 外観 |
| `setup_wallpaper_colours` | Wallpaper colours | 壁紙の色 |
| `setup_updates_title` | Updates | アップデート |
| `setup_updates_installed` | Installed %1$s | インストール済み %1$s |
| `setup_updates_body` | Check GitHub for a newer experimental release. | GitHub で新しい試験版リリースを確認します。 |
| `setup_updates_check` | Check for updates | アップデートを確認 |
| `setup_updates_checking` | Checking GitHub… | GitHub を確認中… |
| `setup_updates_available` | Version %1$s is available. | バージョン %1$s を利用できます。 |
| `setup_updates_view_release` | View release | リリースを見る |
| `setup_updates_check_again` | Check again | 再確認 |
| `setup_updates_current` | You are up to date. | 最新版です。 |
| `setup_updates_none` | No published releases were found. | 公開済みのリリースが見つかりません。 |
| `setup_updates_failed` | Could not check for updates. Check your connection and try again. | アップデートを確認できませんでした。接続を確認して、もう一度お試しください。 |
| `setup_test_title` | End-to-end test | 通し動作テスト |
| `setup_test_body` | Posts a notification from this app. Add a rule for HiLight Studio first. | このアプリから通知を送信します。先に HiLight Studio のルールを追加してください。 |
| `setup_test_button` | Post test notification | テスト通知を送信 |
| `setup_selftest_channel` | Self test | セルフテスト |
| `setup_selftest_title` | HiLight self test | HiLight セルフテスト |
| `setup_selftest_body` | If a rule exists for this app, the LEDs just fired | このアプリのルールがあれば、今 LED が点灯しました |
| `setup_priority_title` | Session priority | セッションの優先度 |
| `setup_priority_body` | Raise if the system\'s own effects interrupt yours; lower to let them win. | システム側の演出に邪魔される場合は上げ、システム側を優先させたい場合は下げてください。 |
| `setup_priority_label` | Priority | 優先度 |

## `strings_style.xml`

| name | English | Japanese |
|---|---|---|
| `style_always_on_style` | Always-on style | 常時点灯スタイル |
| `style_control_off_warning` | Control is off — turn it on in Live to see this on the hardware. | 制御はオフです。実機で確認するには、ライブタブでオンにしてください。 |
| `style_change_every` | Change every | 切り替え間隔 |
| `style_colour_per_led` | A colour per LED | LED ごとに別の色 |
| `style_fade_between_colours` | Fade between colours | 色をフェードで切り替え |
| `style_saturation` | Saturation | 彩度 |
| `style_rainbow_spread` | Spread across the array | LED アレイ全体に広げる |
| `style_rainbow_spread_off` | Off puts every LED on the same hue and cycles them together. | オフにすると、すべての LED が同じ色相になり、まとめて移り変わります。 |
| `style_per_led_colours` | Per-LED colours | LED ごとの色 |
| `style_per_led_hint` | LED 1 sits closest to the flash. Tap one, then pick its colour. | LED 1 がフラッシュに最も近い位置です。1 つをタップして色を選んでください。 |
| `style_led_number` | LED %1$d | LED %1$d |
| `style_rotate_around_array` | Rotate around array | LED アレイを回転 |
| `style_rotate_off` | off | オフ |
| `style_wallpaper` | Wallpaper | 壁紙 |
| `style_gradient_start` | Start | 開始色 |
| `style_gradient_end` | End | 終了色 |
| `style_off_body` | The array stays dark until an app rule fires. | アプリ別ルールが発動するまで、LED アレイは消灯したままです。 |
| `style_colour` | Colour | 色 |
| `style_timing` | Timing | タイミング |
| `style_time_per_cycle` | Time per cycle | 1 周期の時間 |
| `style_shorter_is_faster` | Shorter is faster. | 短いほど速くなります。 |
| `style_brightness` | Brightness | 明るさ |
| `style_brightness_note` | The LEDs have no brightness channel, so this scales the RGB values. | LED に明るさのチャンネルはないため、RGB の値を比例して調整します。 |
| `style_percent` | %1$d%% | %1$d%% |
| `style_presets` | Presets | プリセット |
| `style_presets_saved` | %1$d saved | %1$d 件保存済み |
| `style_presets_empty` | Save the current look to come back to it later. | 現在のスタイルを保存すると、あとから呼び出せます。 |
| `style_export` | Export | 書き出し |
| `style_import` | Import | 読み込み |
| `style_name_this_look` | Name this look | スタイルに名前を付ける |
| `style_name_field` | Name | 名前 |
| `style_preset_default_name` | Preset %1$d | プリセット %1$d |
| `style_paste_exported_presets` | Paste exported presets | 書き出したプリセットを貼り付け |
| `style_import_json_field` | JSON | JSON |
| `style_import_failed` | That JSON could not be read | その JSON は読み取れませんでした |
| `style_import_count` | Imported %1$d | %1$d 件読み込みました |
| `style_export_chooser` | Export presets | プリセットを書き出す |
