# Замеры: распознавание и слежение за текстом

Скрипты, которыми выбраны движок, подсказка слов и параметры трекера (итоговые таблицы — в `../../README.md`).

Схема: отрывки текста озвучиваются (Piper, 4 русских голоса) либо берутся записи живой речи (FLEURS, русский, CC BY 4.0),
звук портится (тихо, шумно), распознаватель (Vosk или нейросеть sherpa-onnx) обрабатывает его, поток `partial/final`
записывается в `events*/*.jsonl` (события вместе с «истиной» — какая строка читается). Затем **настоящий `TextTracker`
приложения** прогоняется по этим записям (`VoskExperimentTest`, Kotlin) и пишет траектории подсветки в `traces*/`, а
Python-скрипты считают по ним метрики. Python-прототип трекера (`hmm2.py`) нужен только для быстрого подбора параметров —
эталон поведения всегда Kotlin-версия.

## Подготовка

```bash
cd android/tools/recognition-experiments
python3 -m venv venv && . venv/bin/activate && pip install vosk sherpa-onnx piper-tts numpy scipy
# рядом кладём (всё скачивается один раз):
#   models/vosk-model-small-ru-0.22/        https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
#   zip/{encoder.int8.onnx,decoder.onnx,joiner.int8.onnx,tokens.txt,unigram_500.vocab}   — файлы нейросети, адреса в NeuralModelStore.kt
#   voices/ru_RU-{irina,denis,dmitri,ruslan}-medium.onnx(.json)   https://huggingface.co/rhasspy/piper-voices (ru/ru_RU)
#   fleurs/dev.tsv и fleurs/dev/*.wav       https://huggingface.co/datasets/google/fleurs (ru_ru, раздел dev)
```

## Запись распознанных потоков

```bash
python exp/gen_audio.py && python exp/gen_foreign.py          # синтез «Одиссеи» и чужой речи -> exp/audio
python exp/make_fleurs.py                                     # живая речь FLEURS -> exp/audio, exp/docs/fleurs.txt

# Vosk: без подсказок и с ограничением словаря словами окна вокруг позиции
python exp/run_vosk.py "nogram,words_yo@300" "clean,quiet_gain,noisy,quietnoisy_gain,foreign,noise,elsewhere"
# нейросеть без подсказок и с «горячими словами» из окна
python exp/run_sherpa.py "clean,quiet_gain,noisy,quietnoisy_gain,foreign,noise,elsewhere"
HOT_SCORE=2.0 HOT_WINDOW=300 python exp/run_sherpa_hot.py "clean,quiet_gain,noisy,quietnoisy_gain,foreign,noise,elsewhere"
# то же на живой речи: DS=fl (события пишутся в exp/events_fl)
DS=fl python exp/run_vosk.py "nogram,words_yo@300" "clean,quiet_gain,noisy,foreign,noise,elsewhere"
```

Условия: `clean`, `quiet_gain` (тихо + программное усиление), `noisy`, `quietnoisy_gain`, `foreign` (чужая речь), `noise`
(шум без речи), `elsewhere` (озвучено одно место, а позиция и подсказка — в другом). Варианты: `nogram` — Vosk без ограничения,
`words_yo@N` — словарь из N слов окна, `zip_small` — нейросеть без подсказок, `zip_hotSS@N` — с «горячими словами» (SS = усиление × 10).

## Прогон трекера и метрики

```bash
# из android/: события -> траектории подсветки настоящим трекером (EXP_EVENTS=events_fl для живой речи)
EXP_DIR=$PWD/tools/recognition-experiments EXP_EVENTS=events ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests '*VoskExperimentTest*'
#   EXP_TRACKER=legacy EXP_SUFFIX=_old  — прежний трекер версии 1 для сравнения (app/src/test/.../tracking/LegacyTextTracker.kt)
#   EXP_CFG="needMid=3,sTau=0.97"        — разовое изменение параметров (см. testutil/ConfigOverride.kt)

cd tools/recognition-experiments
python exp/wordacc.py "nogram,words_yo@300,zip_small,zip_hot20@300" clean,quiet_gain,noisy      # точность распознавания слов
python exp/analyze.py exp/traces "zip_hot20@300,words_yo@300" clean,quiet_gain,noisy,quietnoisy_gain   # задержка, строки, ложные сдвиги
python exp/find_time.py exp/traces "zip_hot20@300"                                              # как быстро найдено новое место
DS=fl python exp/analyze.py exp/traces_fl "zip_hot20@300" clean,quiet_gain,noisy                # живая речь
```

`tune.py` и `run_hmm.py` — подбор параметров на Python-прототипе (`HMM_MODULE=hmm2 python exp/tune.py '{"need_near":3}' quick`).
