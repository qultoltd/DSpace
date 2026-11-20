# Resources of the keyword suggestion curation task

| File | What | Source | Licence |
|---|---|---|---|
| `prompt-keywords-f-v1.txt`, `schema-keywords-f-v1.json` | the evaluated keyword prompt ("f-v1") and JSON schema | Qulto keyword pilot (`docs/llm-pilot/phase2/system_f.py` in the installer repository) | as this repository |
| `prompt-language-v1.txt`, `schema-language-v1.json` | the evaluated title-first language prompt | pilot (`modellproba.py`) | as this repository |
| `qwen3-tokenizer.json.gz` | Qwen3 tokenizer (byte-level BPE), used only to count tokens for the excerpt budget | Qwen/Qwen3-4B-Instruct-2507, Hugging Face | Apache 2.0 |
| `background-idf-v2.json.gz` | background document frequencies of Snowball Hungarian stems (no text): 20,000 parliamentary speeches and 18,287 chunks of PIM DIA books | pilot (`phase2/background.py`, `background_v2.json`) | statistics only; derived data |
| `hu_HU.aff.gz`, `hu_HU.dic.gz` | Magyar Ispell 1.9 Hungarian Hunspell dictionary | magyarispell.sf.net, (c) Németh László, Godó Ferenc | MPL 2.0 or LGPL 3+ |

All `.gz` files are gzip-compressed copies of the originals and are read with `GZIPInputStream`.

The task's behaviour depends on these exact files. A changed prompt, tokenizer or IDF table is a new version and has
to be evaluated again (see `docs/llm-pilot/dspace-integration-spec.md`).
