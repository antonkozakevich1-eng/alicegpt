package com.alicegpt.textfollower.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.alicegpt.textfollower.R
import com.alicegpt.textfollower.Settings
import com.alicegpt.textfollower.speech.EngineKind
import com.alicegpt.textfollower.speech.ModelStore
import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.TextSearch

/** Диалоги приложения: настройки, поиск по тексту. */
object Dialogs {

    interface SettingsCallbacks {
        fun onEngineChanged(kind: EngineKind)
        fun onThemeChanged()
        fun onTypographyChanged()
        fun onSensitivityChanged(value: Int)
        fun onContextChanged(enabled: Boolean)
        fun onAutoScrollChanged(enabled: Boolean)
        fun onResetStats()
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun attrColor(a: Activity, attr: Int): Int {
        val tv = TypedValue()
        a.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    // ---------- настройки ----------

    fun showSettings(a: Activity, settings: Settings, cb: SettingsCallbacks) {
        val pad = dp(a, 20)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(a, 4), pad, dp(a, 8))
        }
        val main = attrColor(a, R.attr.textMain)
        val subtle = attrColor(a, R.attr.textSubtle)

        fun title(text: String) = TextView(a, null, 0, R.style.SectionTitle).apply { this.text = text.uppercase() }
        fun hint(text: String) = TextView(a).apply {
            this.text = text
            setTextColor(subtle)
            textSize = 12f
            setPadding(0, dp(a, 2), 0, dp(a, 2))
        }
        fun label(textRes: Int, value: String? = null) = TextView(a).apply {
            text = if (value == null) a.getString(textRes) else a.getString(textRes, value)
            setTextColor(main)
            textSize = 15f
            setPadding(0, dp(a, 8), 0, dp(a, 2))
        }
        fun radio(id: Int, textRes: Int, checked: Boolean) = RadioButton(a).apply {
            this.id = id
            setText(textRes)
            setTextColor(main)
            isChecked = checked
        }

        // --- распознавание ---
        root.addView(title(a.getString(R.string.settings_section_recognition)))
        val engineGroup = RadioGroup(a).apply { orientation = RadioGroup.VERTICAL }
        engineGroup.addView(radio(2000 + EngineKind.NEURAL.id, R.string.engine_neural, settings.engine == EngineKind.NEURAL))
        engineGroup.addView(radio(2000 + EngineKind.VOSK.id, R.string.engine_vosk, settings.engine == EngineKind.VOSK))
        root.addView(engineGroup)
        root.addView(hint(a.getString(R.string.engine_hint, ModelStore.of(EngineKind.NEURAL).downloadMb, ModelStore.of(EngineKind.VOSK).downloadMb)))
        engineGroup.setOnCheckedChangeListener { _, id ->
            val kind = EngineKind.of(id - 2000)
            if (kind != settings.engine) cb.onEngineChanged(kind)
        }

        val sensLabel = label(R.string.settings_sensitivity, "${settings.sensitivity}%")
        root.addView(sensLabel)
        root.addView(SeekBar(a).apply {
            max = 100
            progress = settings.sensitivity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    sensLabel.text = a.getString(R.string.settings_sensitivity, "$value%")
                    settings.sensitivity = value
                    cb.onSensitivityChanged(value)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        root.addView(hint(a.getString(R.string.settings_sensitivity_hint)))

        root.addView(Switch(a).apply {
            setText(R.string.settings_context)
            setTextColor(main)
            isChecked = settings.useContext
            setPadding(0, dp(a, 10), 0, dp(a, 2))
            setOnCheckedChangeListener { _, on ->
                settings.useContext = on
                cb.onContextChanged(on)
            }
        })
        root.addView(hint(a.getString(R.string.settings_context_hint)))

        // --- оформление ---
        root.addView(title(a.getString(R.string.settings_section_look)))
        val themeGroup = RadioGroup(a).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            Settings.THEME_SYSTEM to R.string.theme_system, Settings.THEME_LIGHT to R.string.theme_light,
            Settings.THEME_SEPIA to R.string.theme_sepia, Settings.THEME_DARK to R.string.theme_dark,
            Settings.THEME_BLACK to R.string.theme_black,
        ).forEach { (id, res) -> themeGroup.addView(radio(1000 + id, res, settings.theme == id)) }
        themeGroup.setOnCheckedChangeListener { _, id ->
            val chosen = id - 1000
            if (chosen != settings.theme) {
                settings.theme = chosen
                cb.onThemeChanged()
            }
        }
        root.addView(themeGroup)

        val sizeLabel = label(R.string.settings_font_size, "${settings.fontSp.toInt()}")
        root.addView(sizeLabel)
        root.addView(SeekBar(a).apply {
            max = (Settings.MAX_FONT - Settings.MIN_FONT).toInt()
            progress = (settings.fontSp - Settings.MIN_FONT).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    settings.fontSp = Settings.MIN_FONT + value
                    sizeLabel.text = a.getString(R.string.settings_font_size, "${settings.fontSp.toInt()}")
                    cb.onTypographyChanged()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })

        val spacingLabel = label(R.string.settings_line_spacing, "%.2f".format(settings.lineSpacing))
        root.addView(spacingLabel)
        root.addView(SeekBar(a).apply {
            max = 80
            progress = ((settings.lineSpacing - 1.1f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    settings.lineSpacing = 1.1f + value / 100f
                    spacingLabel.text = a.getString(R.string.settings_line_spacing, "%.2f".format(settings.lineSpacing))
                    cb.onTypographyChanged()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })

        root.addView(Switch(a).apply {
            setText(R.string.settings_serif)
            setTextColor(main)
            isChecked = settings.serif
            setPadding(0, dp(a, 10), 0, dp(a, 2))
            setOnCheckedChangeListener { _, on ->
                settings.serif = on
                cb.onTypographyChanged()
            }
        })
        root.addView(Switch(a).apply {
            setText(R.string.settings_autoscroll)
            setTextColor(main)
            isChecked = settings.autoScroll
            setPadding(0, dp(a, 10), 0, dp(a, 2))
            setOnCheckedChangeListener { _, on ->
                settings.autoScroll = on
                cb.onAutoScrollChanged(on)
            }
        })

        // --- прочее ---
        root.addView(title(a.getString(R.string.settings_section_other)))
        root.addView(Button(a, null, 0, R.style.Btn).apply {
            setText(R.string.settings_reset_stats)
            setOnClickListener { cb.onResetStats() }
        })
        root.addView(hint(a.getString(R.string.about_text)).apply { setPadding(0, dp(a, 14), 0, dp(a, 4)) })

        AlertDialog.Builder(a)
            .setTitle(R.string.settings)
            .setView(ScrollView(a).apply { addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---------- поиск по тексту ----------

    fun showSearch(a: Activity, doc: Doc, onPick: (word: Int) -> Unit) {
        val pad = dp(a, 20)
        val main = attrColor(a, R.attr.textMain)
        val subtle = attrColor(a, R.attr.textSubtle)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(a, 8), pad, 0)
        }
        val input = EditText(a).apply {
            hint = a.getString(R.string.search_hint)
            setHintTextColor(subtle)
            setTextColor(main)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val info = TextView(a).apply {
            setTextColor(subtle)
            textSize = 12f
            setPadding(0, dp(a, 4), 0, dp(a, 4))
        }
        val list = ListView(a).apply { divider = null }
        root.addView(input)
        root.addView(info)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 320)))

        var hits = emptyList<TextSearch.Hit>()
        val adapter = object : ArrayAdapter<TextSearch.Hit>(a, 0) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val h = getItem(position)!!
                val row = (convertView as? LinearLayout) ?: LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(a, 8), 0, dp(a, 8))
                    addView(TextView(a).apply { id = R.id.search_snippet; setTextColor(main); textSize = 15f; maxLines = 3 })
                    addView(TextView(a).apply { id = R.id.search_chapter; setTextColor(subtle); textSize = 12f })
                }
                row.findViewById<TextView>(R.id.search_snippet).text = h.snippet
                row.findViewById<TextView>(R.id.search_chapter).text = h.chapter
                return row
            }
        }
        list.adapter = adapter

        val handler = Handler(Looper.getMainLooper())
        val run = Runnable {
            val q = input.text.toString()
            hits = if (q.trim().length >= 2) TextSearch.find(doc, q) else emptyList()
            adapter.clear()
            adapter.addAll(hits)
            info.text = when {
                q.trim().length < 2 -> a.getString(R.string.search_prompt)
                hits.isEmpty() -> a.getString(R.string.search_nothing)
                hits.size >= 60 -> a.getString(R.string.search_many)
                else -> a.getString(R.string.search_found, hits.size)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, 180)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        run.run()

        val dialog = AlertDialog.Builder(a)
            .setTitle(R.string.search)
            .setView(root)
            .setNegativeButton(R.string.close, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onPick(hits[position].word)
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ---------- первое знакомство ----------

    fun showIntro(a: Activity, onDone: () -> Unit) {
        val pad = dp(a, 22)
        val main = attrColor(a, R.attr.textMain)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(a, 8), pad, 0)
        }
        listOf(R.string.intro_1, R.string.intro_2, R.string.intro_3, R.string.intro_4).forEach { res ->
            box.addView(TextView(a).apply {
                setText(res)
                setTextColor(main)
                textSize = 15f
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(a, 6), 0, dp(a, 6))
                gravity = Gravity.START
            })
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.intro_title)
            .setView(box)
            .setPositiveButton(R.string.intro_ok) { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }
}
