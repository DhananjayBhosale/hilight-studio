# Japanese glossary — HiLight+

Fixed translations for the terms that recur across the app. The point of a glossary is consistency:
the same English term must not appear as two different Japanese words on two screens.

## Register

- Polite-neutral です・ます for anything addressed to the reader.
- Labels, buttons and pills are noun phrases, not sentences, and take no full stop.
- Explanatory captions are sentences and take 。
- No exclamation marks, no ！, no emoji. The English copy is deliberately plain and the Japanese
  should read the same way.
- Prefer a native word over katakana where one reads naturally (点灯 over ライティング), but keep
  katakana where it is what Android itself uses on the phone (通知, 電池セーバー).

## Left in Latin script, never translated

| Term | Why |
|---|---|
| HiLight, HiLight+ | Product name. Also the Google feature name on the phone |
| Shizuku | Product name |
| ADB, adb | Command name, and the command text itself is untranslated |
| LED | Used as-is in Japanese technical writing |
| Gemini, Pixel | Product names |
| JSON, MessagingStyle, shortcutId | Identifiers a reader would search for verbatim |

## Terms

| English | Japanese | Note |
|---|---|---|
| array (the eight LEDs) | LED アレイ | "アレイ" alone is ambiguous |
| ambient / always-on look | 常時点灯スタイル | 常時点灯 is the established term for always-on |
| pattern | パターン | |
| preset | プリセット | |
| rule | ルール | |
| per-app rule | アプリ別ルール | |
| per-contact rule | 連絡先別ルール | one name for the feature everywhere; do not also use 相手別 |
| chat / conversation | チャット | 会話 reads as "a conversation held", not a thread |
| group chat | グループチャット | |
| notification | 通知 | matches Android's own Japanese |
| notification access | 通知へのアクセス | as Android's settings screen names it |
| usage access | 使用状況へのアクセス | as Android names it |
| overlay | オーバーレイ | |
| quiet hours | サイレント時間 | |
| Do Not Disturb | サイレントモード | Android's own Japanese for DND |
| Battery Saver | バッテリーセーバー | Android's own Japanese |
| low battery | 電池残量が少ない | |
| brightness | 明るさ | not 輝度, which is instrumentation register |
| speed / time per cycle | 1 周期の時間 | |
| cycle | 周期 | |
| fade | フェード | |
| glow | 光 / 発光 | by context |
| screen off | 画面消灯時 | |
| auto-off | 自動オフ | |
| duty cycle / resting | 点灯時間の制限 / 休止 | the safety guard, not a hardware term |
| taper | 段階的な減光 | |
| renderer | レンダラー | |
| helper | ヘルパー | |
| session | セッション | |
| transport | 接続方法 | "トランスポート" is opaque here |
| priority | 優先度 | |
| last matched | 最終一致 | on a card; 最後に一致した日時 in a caption |
| learn the next message | 次のメッセージから取得 | avoids 学習, which implies training |
| inspector | 通知インスペクター | |
| group summary | グループ通知のまとめ | |
| stable chat id | 固定のチャット ID | |
| test | テスト | |
| forget | 削除 | 忘れる reads oddly for data |
| update | アップデート | familiar Android Settings term |

## Formatting

- Numbers and units: `10秒`, `1分30秒`, `300ミリ秒`, `50%` — no space between number and unit, which
  is why the duration strings are separate resources rather than concatenation.
- Half-width digits and Latin, full-width punctuation (、。) — do not mix ，．
- No space around Japanese text and Latin terms unless it aids reading: `LED アレイ` keeps one, `10秒`
  does not.
- Keep strings short. Several of these appear inside pills and segmented buttons where the English is
  two words; a long Japanese rendering will clip. Where a choice exists, take the shorter one.
