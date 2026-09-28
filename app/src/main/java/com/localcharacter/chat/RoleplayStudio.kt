package com.localcharacter.chat

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/** Separate editing UI for author controls. Nothing here impersonates an actual messaging contact. */
class RoleplayStudio(
    private val activity: Activity,
    private val getStore: () -> ChatStore,
    private val changed: () -> Unit,
    private val submit: (String) -> Unit,
    private val openMood: () -> Unit,
) {
    fun show() {
        AlertDialog.Builder(activity)
            .setTitle("Roleplay Studio · fictional character")
            .setItems(arrayOf("Character traits & reply style", "Scene / next-reply director", "Mood scores & memory", "Inspect the current reply instructions", "Your identity, roles & private feed")) { _, index ->
                when (index) { 0 -> character(); 1 -> director(); 2 -> openMood(); 3 -> inspect(); 4 -> activity.startActivity(android.content.Intent(activity, SocialActivity::class.java).putExtra("section", "profile")) }
            }.setNegativeButton("Close", null).show()
    }

    fun character() {
        val store = getStore()
        val r = store.settings.roleplay
        val body = column()
        note(body, "Choose how the character writes. Trait controls take priority over generic personality adjectives. Changes affect new responses, not old messages.")
        val modes = listOf("Auto: texting until a scene is set", "Text messages only", "Roleplay: actions + dialogue", "Narrative: scene prose")
        val modeValues = listOf("auto", "texting", "roleplay", "narrative")
        val mode = spinner(body, "Reply format", modes, modeValues.indexOf(r.mode).coerceAtLeast(0))
        val perspective = spinner(body, "Character's narration perspective", listOf("First person", "Third person"), if (r.perspective == "third") 1 else 0)
        val background = field(body, "Background / role", r.background, 4)
        val traits = field(body, "Character traits in your own words", r.traits, 3)
        val goals = field(body, "Goals and motivations", r.goals, 3)
        val voice = field(body, "Examples of how the character should write", r.voiceExamples, 4)
        val rules = field(body, "Reply instructions — e.g. be direct, do not ask a question every time", r.replyRules, 3)
        val boundaries = field(body, "Character boundaries / things to avoid", r.boundaries, 3)
        val warmth = slider(body, "Warmth", r.warmth)
        val humor = slider(body, "Humor", r.humor)
        val directness = slider(body, "Directness", r.directness)
        val shy = slider(body, "Shyness", r.shyness)
        val curiosity = slider(body, "Curiosity / questions", r.curiosity)
        val initiative = slider(body, "Story initiative (never controls your character)", r.initiative)
        val autoReply = toggle(body, "Reply automatically after a scene-only setup", r.autoReplyToScene)
        val repair = toggle(body, "Retry once when a draft is invalid (may take longer)", r.repairInvalidReply)
        val cards = toggle(body, "Show director-only messages as control cards", r.showDirectorCards)
        note(body, "Mood is separate from personality. Use Mood scores to choose Off, manual values, or automatic changes. Emoji, reply length and model options remain in Chat settings.")
        AlertDialog.Builder(activity).setTitle("Character & reply style").setView(scroll(body))
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val next = r.copy(
                    mode = modeValues[mode.selectedItemPosition],
                    perspective = if (perspective.selectedItemPosition == 1) "third" else "first",
                    background = background.text.toString().take(2400), traits = traits.text.toString().take(1200),
                    goals = goals.text.toString().take(1000), voiceExamples = voice.text.toString().take(1600),
                    replyRules = rules.text.toString().take(1000), boundaries = boundaries.text.toString().take(1000),
                    warmth = warmth.progress, humor = humor.progress, directness = directness.progress,
                    shyness = shy.progress, curiosity = curiosity.progress, initiative = initiative.progress,
                    autoReplyToScene = autoReply.isChecked, repairInvalidReply = repair.isChecked,
                    showDirectorCards = cards.isChecked,
                )
                getStore().saveSettings(getStore().settings.copy(roleplay = next))
                changed()
            }.show()
    }

    fun director() {
        val body = column()
        note(body, "Directions in *asterisks* are not dialogue. Scene facts persist; reply: applies to this reply only. Later location/goal/trait fields replace their earlier values. No need to restart the chat.")
        val input = field(body, "Director instructions (plain text is also accepted here)", "", 6)
        input.hint = "*scene: we are old friends waiting for a delayed train*\n*traits: dry humor, observant*\n*mood: friendly=6, tired=4*\n*reply: explain briefly why you arrived late*"
        val style = spinner(body, "Format for this scene", listOf("Keep current format", "Texting — no performed actions", "Roleplay — actions + dialogue", "Narrative"), 0)
        val status = getStore().conversation.sceneState
        if (status.active || status.director.mood.isNotEmpty()) {
            note(body, "Active state:\n" + SceneDirector.prompt(status).take(1800) +
                "\nScene mood overrides: " + status.director.mood.entries.joinToString { "${it.key}=${it.value}" })
        }
        note(body, "Other fields: location:, time:, character:, goal:, relationship:, outfit:, activity:, rules:, length:, perspective:.\n*clear scene* removes the scene and its overrides.\nPlain *directions* also persist as free-form setup.")
        val dialog = AlertDialog.Builder(activity).setTitle("Scene & next reply")
            .setView(scroll(body)).setNegativeButton("Cancel", null)
            .setNeutralButton("Clear scene") { _, _ -> submit("*clear scene*") }
            .setPositiveButton("Apply", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var text = input.text.toString().trim()
                if (text.isNotBlank() && !text.contains('*')) text = "*$text*"
                val prefix = when (style.selectedItemPosition) {
                    1 -> "*style: texting*\n"; 2 -> "*style: roleplay*\n"; 3 -> "*style: narrative*\n"; else -> ""
                }
                text = prefix + text
                val parsed = SceneDirector.parse(text)
                if (text.isBlank()) { input.error = "Write a direction or select a format."; return@setOnClickListener }
                if (parsed.warnings.isNotEmpty()) { input.error = parsed.warnings.joinToString(" "); return@setOnClickListener }
                dialog.dismiss(); submit(text)
            }
        }
        dialog.show()
    }

    fun inspect() {
        val store = getStore()
        val last = store.conversation.messages.lastOrNull { it.role == "user" }
        val body = column()
        note(body, "These are the instructions/context sent to the local model, not the model's private reasoning. Seeing a rule here verifies app wiring; it does not guarantee the model follows it.")
        val preview = runCatching {
            val snapshot = ConversationState.fromJson(store.conversation.toJson())
            val input = last ?: ChatMessage(role = "user", text = "Hi")
            if (last == null) snapshot.messages += input
            SceneDirector.applyDirectives(store.settings, snapshot, input.text, sourceMessageId = input.id)
            val plan = ReplyPipeline.buildPlan(store.settings, snapshot, store.generationMetrics)
            "Mode: ${plan.responseMode}\nPrompt: ${plan.promptCharacters} characters (estimated budget)\nOutput limit: ${plan.maxTokens} tokens\nContext: ${plan.selectedMessages} messages, ${plan.selectedMemories} facts\n\nSYSTEM\n${plan.systemPrompt}\n\nCONVERSATION\n${plan.conversationPrompt}"
        }.getOrElse { "Prompt warning: ${it.message}" }
        val text = TextView(activity).apply { this.text = preview; textSize = 12f; setTextIsSelectable(true); setTextColor(Color.DKGRAY) }
        body.addView(text)
        AlertDialog.Builder(activity).setTitle("Reply instruction inspector").setView(scroll(body)).setPositiveButton("Close", null).show()
    }

    private fun column(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(16))
    }
    private fun scroll(body: LinearLayout): ScrollView = ScrollView(activity).apply { addView(body) }
    private fun note(body: LinearLayout, value: String) {
        body.addView(TextView(activity).apply { text = value; textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(5), 0, dp(12)) })
    }
    private fun field(body: LinearLayout, name: String, value: String, lines: Int): EditText {
        body.addView(TextView(activity).apply { text = name; textSize = 12f })
        return EditText(activity).apply {
            setText(value); minLines = lines; maxLines = 9; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            body.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    private fun spinner(body: LinearLayout, title: String, values: List<String>, selected: Int): Spinner {
        body.addView(TextView(activity).apply { text = title; textSize = 12f })
        return Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(selected); body.addView(this)
        }
    }
    private fun toggle(body: LinearLayout, label: String, value: Boolean): CheckBox = CheckBox(activity).apply {
        text = label; isChecked = value; body.addView(this)
    }
    private fun slider(body: LinearLayout, name: String, value: Int): SeekBar {
        val label = TextView(activity).apply { text = "$name: $value / 10"; textSize = 13f }
        body.addView(label)
        return SeekBar(activity).apply {
            max = 10; progress = value.coerceIn(0, 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { label.text = "$name: $progress / 10" }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            body.addView(this)
        }
    }
    private fun dp(value: Int): Int = (activity.resources.displayMetrics.density * value).toInt()
}
