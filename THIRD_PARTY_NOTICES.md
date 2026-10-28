# Third party components

- Junrar 7.5.5 — https://github.com/junrar/junrar. Used only for reading RAR4 archives, under the UnRAR License. The complete upstream license is packaged in `app/src/main/assets/licenses/Junrar-UnRAR-LICENSE.txt`. The code must not be used to develop a RAR/WinRAR compatible compression archiver. This application neither implements nor derives a RAR compression algorithm.
- SLF4J API 1.7.36 (Junrar runtime dependency) — https://www.slf4j.org/license.html, MIT license.
- Jsoup 1.18.3 — https://jsoup.org/license, MIT license.
- ECDICT — https://github.com/skywind3000/ECDICT, MIT license. The English assistant reads derived English/Chinese lookup shards from the upstream public `ecdict.csv`; its exact Git blob, input SHA-256 and derived shard hashes are recorded in `app/src/main/assets/english-dictionary/manifest.json`. The complete upstream notice is included in that directory as `LICENSE.txt`. The source CSV is a build input, kept out of the repository; `tools/build_english_dictionary.py` regenerates the packaged data after verifying its source identity. User selections are never added to this dictionary.
- Google ML Kit Text Recognition 16.0.1 and Translation 17.0.3 — device-side OCR and English-to-Chinese translation. Model download is opt-in; downloaded language models use SDK-managed storage. ML Kit terms and translation attribution requirements apply: https://developers.google.com/ml-kit/terms and https://developers.google.com/ml-kit/language/translation/translation-terms. Machine-translated text is attributed to Google Translate in the translation panel.
- AndroidX and Kotlin components retain their upstream license metadata. Robolectric is used only by host tests, not packaged as an application runtime dependency.

Event artwork is fetched from the recorded source cover URLs. These URLs and their source article links are separate from the official metadata references; the application does not claim ownership or official endorsement.
