import com.localcharacter.chat.*
import org.json.JSONObject

private var passed=0
private fun test(name:String, block:()->Unit) { try { block(); passed++; println("PASS $name") } catch(t:Throwable) { throw AssertionError("FAIL $name: ${t.message}",t) } }
private fun user(state:ConversationState, text:String): ChatMessage = ChatMessage(role="user", text=text).also { state.messages+=it }
private fun ai(state:ConversationState, text:String): ChatMessage = ChatMessage(role="assistant", text=text).also { state.messages+=it }
private fun apply(s:CharacterSettings, c:ConversationState, text:String):ChatMessage { val u=user(c,text); SceneDirector.applyDirectives(s,c,text,sourceMessageId=u.id); return u }
private fun plan(s:CharacterSettings, c:ConversationState)=ReplyPipeline.buildPlan(s,c,GenerationMetrics())

fun runCoreRegression() {
    passed = 0
    test("JSON fixture actually round-trips") { val a=JSONObject("{\"key\":\"value \\u263a\",\"n\":5,\"arr\":[{\"b\":true}]}"); check(a.optInt("n")==5); check(JSONObject(a.toString()).optJSONArray("arr")!!.getJSONObject(0).optBoolean("b")) }
    test("Legacy model path, context, mood preserved") {
        val s=CharacterSettings.fromJson(JSONObject("{\"modelPath\":\"/keep/model.gguf\",\"contextSize\":8000,\"temperature\":0.6,\"moodEnabled\":false}")); check(s.modelPath=="/keep/model.gguf" && s.contextSize==8000 && !s.moodEnabled)
        check(CharacterSettings.fromJson(JSONObject(s.toJson().toString())).roleplay.mode=="auto")
    }
    test("Escaped stars and bold are not directions") {
        check(SceneDirector.parse("I mean \\* literally").directions.isEmpty())
        check(SceneDirector.parse("This is **important**").directions.isEmpty())
        check(SceneDirector.parse("3*4*2").directions.isEmpty())
    }
    test("Mixed instructions kept out of spoken message") {
        val p=SceneDirector.parse("*location: cafe*\n*traits: shy*\nHi 🙂")
        check(p.directions.size==2 && p.dialogue=="Hi 🙂")
    }
    test("Unclosed director text is a visible validation error") { check(SceneDirector.parse("*scene: home").warnings.isNotEmpty()) }
    test("Explicit scene values are last-write-wins") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*location: cafe* *goal: wait for a train*"); apply(s,c,"*location: library*")
        val p=SceneDirector.prompt(c.sceneState); check("library" in p && "location: cafe" !in p)
    }
    test("Scene facts survive next message and JSON restart") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*scene: old friends waiting for a train* *location: station*"); user(c,"What now?")
        val reload=ConversationState.fromJson(JSONObject(c.toJson().toString())); check("station" in plan(s,reload).systemPrompt)
    }
    test("Reply direction is one-turn only") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*reply: explain briefly why you arrived late*")
        check("apply NOW" in plan(s,c).systemPrompt); ai(c,"The bus broke down."); user(c,"Was it raining?")
        check("NEXT-REPLY DIRECTION" !in plan(s,c).systemPrompt)
    }
    test("Configuration-only directions do not require generation") {
        val s=CharacterSettings(); check(!SceneDirector.shouldGenerate(s,SceneDirector.parse("*mood: tired=8*")))
        check(!SceneDirector.shouldGenerate(s,SceneDirector.parse("*clear scene*")))
        check(SceneDirector.shouldGenerate(s,SceneDirector.parse("*reply: answer briefly*")))
    }
    test("Scene does not make Hi a long response") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*scene: old friends at a cafe*"); user(c,"Hi")
        val p=plan(s,c); check(p.mode==PromptMode.GREETING && p.maxTokens==32 && p.maxWords==16)
    }
    test("Texting and physical roleplay are independent") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*scene: old friends* *style: texting*"); user(c,"How are you?")
        val p=plan(s,c); check(p.responseMode=="texting" && "No *actions*" in p.systemPrompt)
    }
    test("Mood directive overrides effective tone, not saved baseline") {
        val s=CharacterSettings(); val c=ConversationState(); val original=c.brain.tiredness
        apply(s,c,"*mood: friendly=2, love=0, tired=9, annoyed=5* hello")
        val b=SceneDirector.effectiveBrain(s,c); check(b.friendliness==2 && b.love==0 && b.tiredness==9); check(c.brain.tiredness==original)
        check("low energy" in plan(s,c).systemPrompt)
    }
    test("Mood off excludes both baseline and scene mood instructions") {
        val s=CharacterSettings(moodEnabled=false); val c=ConversationState(); apply(s,c,"*mood: tired=9* hi")
        check(SceneDirector.effectiveBrain(s,c).tiredness==c.brain.tiredness)
        check("Current emotion" !in plan(s,c).systemPrompt)
    }
    test("Manual mood remains stable when rebuilding branch") {
        val s=CharacterSettings(automaticMoodEnabled=false); val c=ConversationState(); c.brain.friendliness=2; c.brain.love=1
        user(c,"thanks"); ai(c,"ok"); BrainEngine.rebuildFromHistory(s,c); check(c.brain.friendliness==2 && c.brain.love==1)
    }
    test("Trait controls reach the actual prompt") {
        val s=CharacterSettings(roleplay=RoleplaySettings(warmth=1,curiosity=1,shyness=9,replyRules="Never use pet names")); val c=ConversationState(); user(c,"How was work?")
        val p=plan(s,c); check("reserved" in p.systemPrompt && "shy but still answers" in p.systemPrompt && "Never use pet names" in p.systemPrompt)
    }
    test("Why not anchors exact refusal") {
        val s=CharacterSettings(); val c=ConversationState(); user(c,"Can we meet Saturday?"); ai(c,"I work all day Saturday."); user(c,"Why not Sunday then?")
        MemoryManager.refresh(c); val p=plan(s,c); check(p.mode==PromptMode.REFERENCE && "work all day Saturday" in p.conversationPrompt)
    }
    test("Correction is not a generic short reply") {
        val s=CharacterSettings(); val c=ConversationState(); user(c,"I meant the blue one, not the red one."); check("correcting a misunderstanding" in plan(s,c).systemPrompt)
    }
    test("Superseded location never returns on repeated refresh") {
        val c=ConversationState(); user(c,"I live in Cluj."); MemoryManager.refresh(c); user(c,"I moved to Bucharest.")
        repeat(6) { MemoryManager.refresh(c); val current=c.memories.single { it.slot=="identity:location" }; check("Bucharest" in current.text) }
    }
    test("Director facts do not overwrite real user identity") {
        val s=CharacterSettings(); val c=ConversationState(); user(c,"My name is Jesse."); apply(s,c,"*scene: my name is Alex and I live in Paris* Hi")
        MemoryManager.refresh(c); check(c.memories.any { it.slot=="identity:name" && "Jesse" in it.text }); check(c.memories.none { "Paris" in it.text })
    }
    test("Likes do not turn assistant identity into user facts") {
        val c=ConversationState(); ai(c,"I live in London.").feedback=1; MemoryManager.refresh(c); check(c.memories.none { it.slot=="identity:location" })
    }
    test("Deleting scene branch restores previous location") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*location: cafe*"); val branch=apply(s,c,"*location: library*"); user(c,"Hi")
        ConversationEditor.deleteFromMessage(s,c,branch.id); check(c.sceneState.director.fields["location"]=="cafe")
    }
    test("Clear scene is durable across history rebuild") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*scene: old friends*"); apply(s,c,"*clear scene*"); SceneDirector.rebuildFromHistory(s,c)
        check(!c.sceneState.active && SceneDirector.responseMode(s,c)=="texting")
    }
    test("Reasoning removed but final text kept") {
        val s=CharacterSettings(); val c=ConversationState(); user(c,"Hi"); val p=plan(s,c)
        val r=ReplyPipeline.evaluate("<think>The user wants a greeting.</think>Hi 🙂",s,c,p); check(r.usable && r.text=="Hi 🙂")
    }
    test("No fake fallback or copied director action") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*scene: waiting at the station*")
        val r=ReplyPipeline.evaluate("<think>unfinished",s,c,plan(s,c)); check(!r.usable && r.text.isEmpty())
    }
    test("Narration removed in texting; legitimate self-report retained") {
        val s=CharacterSettings(roleplay=RoleplaySettings(mode="texting")); val c=ConversationState(); user(c,"How are you?"); val p=plan(s,c)
        check(ReplyPipeline.evaluate("*winks* I'm walking home now.",s,c,p).text=="I'm walking home now.")
        check(!ReplyPipeline.evaluate("Winks at you.",s,c,p).usable)
    }
    test("Actions retained in roleplay; no forced user decisions") {
        val s=CharacterSettings(roleplay=RoleplaySettings(mode="roleplay")); val c=ConversationState(); user(c,"I knock on the door."); val p=plan(s,c)
        check(ReplyPipeline.evaluate("*I open the door.* Hey, come in.",s,c,p).usable)
        check(!ReplyPipeline.evaluate("You decide to sit. *I smile.*",s,c,p).usable)
    }
    test("User transcript continuation never displayed") {
        val s=CharacterSettings(); val c=ConversationState(); user(c,"Hi"); val r=ReplyPipeline.evaluate("Elena: Hi.\nYou: I missed you.\nElena: Me too.",s,c,plan(s,c))
        check(r.text=="Hi.")
    }
    test("Non-Latin text is not deleted as gibberish") {
        val s=CharacterSettings(); val c=ConversationState(); user(c,"こんにちは"); check(ReplyPipeline.evaluate("こんにちは、元気ですか？",s,c,plan(s,c)).usable)
    }
    test("Hard-cut draft rejected, not disguised with ellipsis") {
        val s=CharacterSettings(); val c=ConversationState(); user(c,"Why?"); val r=ReplyPipeline.evaluate("The reason is that",s,c,plan(s,c),hitTokenLimit=true); check(!r.usable)
    }
    test("Long greeting shortens only at complete sentence boundary") {
        val s=CharacterSettings(); val c=ConversationState(); user(c,"Hi"); val r=ReplyPipeline.evaluate("Hello. " + "Let me tell you all about my day and how I felt and everything that I experienced before getting here today at the station with all of my friends.",s,c,plan(s,c))
        check(r.text=="Hello." && r.usable) { "text=${r.text} issues=${r.issues} plan=${plan(s,c).mode} max=${plan(s,c).maxWords}" }
    }
    test("Long conversations keep greeting prompt bounded; 500 index retained") {
        val s=CharacterSettings(); val c=ConversationState(); repeat(310) { user(c,"We discussed train number $it yesterday."); ai(c,"What happened to train $it?") }
        MemoryManager.refresh(c); user(c,"Hi"); MemoryManager.refresh(c); val p=plan(s,c)
        check(c.contextIndex.size==500); check(p.selectedMessages==0 && p.promptCharacters<3000)
    }
    test("Full direction persistence round-trip") {
        val s=CharacterSettings(); val c=ConversationState(); apply(s,c,"*location: cafe* *mood: tired=9* *reply: answer without questions*")
        val r=ConversationState.fromJson(JSONObject(c.toJson().toString())); check(r.sceneState.director.fields["location"]=="cafe" && r.sceneState.director.mood["tiredness"]==9)
        check(plan(s,r).systemPrompt.contains("answer without questions"))
    }
    test("Unsupported huge input fails visibly without mutating chat") {
        val s=CharacterSettings(contextSize=1024); val c=ConversationState(); val text="A".repeat(10000); user(c,text)
        check(runCatching{plan(s,c)}.isFailure); check(c.messages.last().text==text)
    }
    test("Natural next-reply directions do not become permanent scene facts") {
        val s=CharacterSettings(); val c=ConversationState(); val u=apply(s,c,"*she replies in a short sentence*")
        check(SceneDirector.nextReply(c,u.id).isNotBlank()); check(!c.sceneState.active)
        ai(c,"Yes, that works."); user(c,"What happens next?"); check("NEXT-REPLY DIRECTION" !in plan(s,c).systemPrompt)
    }
    test("Natural friend setup overrides an old partner stage") {
        val s=CharacterSettings(relationshipStage="partner"); val c=ConversationState(); apply(s,c,"*she is a friend who likes talking about trains*")
        check(c.sceneState.relationshipOverride=="friend")
    }
    test("Roleplay format requires a real generated action, never adds a fake one") {
        val s=CharacterSettings(roleplay=RoleplaySettings(mode="roleplay")); val c=ConversationState(); user(c,"I knock on the door.")
        val r=ReplyPipeline.evaluate("Hello, come in.",s,c,plan(s,c)); check(!r.usable && !r.text.contains("*"))
    }
    test("CPU thread preference survives serialization") {
        val s=CharacterSettings(cpuThreads=3); check(CharacterSettings.fromJson(JSONObject(s.toJson().toString())).cpuThreads==3)
    }
    println("TOTAL $passed tests passed. No Android device or real-model inference was executed by this suite.")
}

fun main() = runCoreRegression()
