# Эксперимент: ограничивать ли словарь Vosk словами из текста

Скрипты, которыми выбран режим распознавания по умолчанию (подробности и итоговая таблица — в `../../README.md`).

Схема: отрывки книги озвучиваются нейронным TTS (Piper, 4 русских голоса), звук портится (тихо, шумно),
Vosk `small-ru-0.22` распознаёт его с разными словарями и без, поток `partial/final` записывается в `events/*.jsonl`,
а затем **настоящий `TextTracker` приложения** прогоняется по этим записям (`VoskExperimentTest`) и считает метрики.

```bash
cd android/tools/vosk-experiment
python3 -m venv venv && . venv/bin/activate && pip install vosk piper-tts numpy scipy
# модель Vosk и голоса Piper кладём рядом:
#   models/vosk-model-small-ru-0.22/            https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
#   voices/ru_RU-{irina,denis,dmitri,ruslan}-medium.onnx(.json)   https://huggingface.co/rhasspy/piper-voices (ru/ru_RU)
python exp/gen_audio.py && python exp/gen_foreign.py
python exp/run_vosk.py "nogram,words_yo@300,words_yo@100,bi@300,tri@300,quad@300,lines@300" \
                       "clean,quiet_gain,noisy,quietnoisy_gain,foreign,noise,elsewhere"
(cd ../.. && EXP_DIR=$PWD/tools/vosk-experiment/exp ./gradlew testDebugUnitTest --tests '*VoskExperimentTest*')
python exp/aggregate.py
```

Варианты словаря: `nogram` — без ограничения; `words_yo@N` — уникальные слова окна из N слов вокруг позиции
(с вариантами через «ё») и `[unk]`; `bi/tri/quad@N` — перекрывающиеся фразы по 2/3/4 слова; `lines@N` — строки текста как фразы.
Условия: `clean`, `quiet_gain` (тихо + программное усиление), `noisy`, `quietnoisy_gain`, `foreign` (чужая речь — придуманные
фразы), `noise` (шум без речи), `elsewhere` (озвучено одно место книги, а позиция и словарь — в другом).
