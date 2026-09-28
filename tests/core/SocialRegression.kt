import com.localcharacter.chat.*
import org.json.JSONObject

object SocialRegression {
    @JvmStatic fun main(args: Array<String>) { println("Social regression cases: ${runAll()} PASS") }
    fun runAll(): Int {
        var passed = 0
        fun expect(name: String, value: Boolean) { check(value) { name }; passed++; println("PASS $name") }
        val base = CharacterSettings(userName = "Jesse", selfProfile = SelfProfile("man", "he/him", 30, "photographer", "Likes trains"))
        val settings = CharacterSettings.fromJson(base.toJson())
        expect("self identity round trip", settings.selfProfile == base.selfProfile)
        expect("old settings migrate without gender assumption", CharacterSettings.fromJson(JSONObject()).selfProfile.gender == "unspecified")
        expect("role is applied, not inferred anatomy", settings.selfProfile.prompt("Jesse").contains("photographer") && settings.selfProfile.prompt("Jesse").contains("Do not infer"))
        expect("old settings get request-only photos", CharacterSettings.fromJson(JSONObject()).photoSharing.mode == "request")
        expect("minimum self age adult", SelfProfile.fromJson(JSONObject().put("age", 5)).age == 18)
        val world = SocialWorld.initial(base)
        val actor = world.characters.first()
        actor.settings = actor.settings.copy(characterName = "Mara", relationship = "coworker", relationshipStage = "coworker")
        val second = SocialCharacter(id = "alex", handle = "alex", role = "neighbor", settings = CharacterSettings(characterName = "Alex"))
        world.characters += second
        val post = FeedPost(id = "p", authorId = actor.id, caption = "Coffee after the photo walk.", imageDescription = "A cup on a table.")
        world.posts += post
        val a = FeedComment(id = "a", postId = "p", authorId = "self", text = "Can we meet on Saturday?", createdAt = 1)
        val b = FeedComment(id = "b", postId = "p", authorId = actor.id, text = "I am working Saturday.", parentId = "a", createdAt = 2, generated = true)
        val c = FeedComment(id = "c", postId = "p", authorId = "self", text = "Why not Sunday then?", parentId = "b", createdAt = 3)
        world.comments += listOf(a,b,c)
        val plan = SocialReplyPolicy.build(world, "p", "c", actor.id, base)
        expect("exact reply chain", listOf("a","b","c").all { it in plan.includedIds })
        expect("actual refusal in prompt", plan.prompt.contains("working Saturday"))
        expect("newest target at end", plan.prompt.substringAfter("SELECTED COMMENT:").contains(c.text))
        expect("correct character only", plan.system.contains("as Mara") && !plan.system.contains("as Alex"))
        expect("identity pronouns injected", plan.system.contains("he/him") && plan.system.contains("photographer"))
        expect("role and relationship real wiring", plan.system.contains("coworker"))
        val blank = FeedPost(id = "private", caption = "OUTSIDE_THREAD_SECRET"); world.posts += blank
        world.comments += FeedComment(postId = "private", authorId = "self", text = "OUTSIDE_THREAD_SECRET")
        expect("another post cannot leak", !SocialReplyPolicy.build(world, "p", "c", actor.id, base).prompt.contains("OUTSIDE_THREAD_SECRET"))
        val long = SocialWorld.fromJson(world.toJson())
        repeat(520) { i -> long.comments += FeedComment(id = "irrelevant$i", postId = "p", authorId = second.id, text = "Other topic ${i}: a completely unrelated topic about pencils.", createdAt = 10L+i) }
        val longPlan = SocialReplyPolicy.build(long, "p", "c", actor.id, base)
        expect("explicit old target survives 500 window", "c" in longPlan.includedIds && "b" in longPlan.includedIds)
        expect("long thread prompt stays bounded", longPlan.prompt.length < 10000 && longPlan.includedIds.size <= 16)
        val restored = SocialWorld.fromJson(long.toJson())
        expect("full public history preserved", restored.comments.size == long.comments.size)
        expect("liking does not fabricate crowd", world.posts.first().liked == false)
        restored.deleteCommentBranch("b")
        expect("delete descendants, not parent", restored.comments.any { it.id == "a" } && restored.comments.none { it.id == "b" || it.id == "c" })
        expect("delete preserves other posts", restored.comments.any { it.postId == "private" })
        val prior = restored.posts.first { it.id == "p" }.revision
        restored.deletePost("p")
        expect("post deletion removes all its comments", restored.comments.none { it.postId == "p" } && restored.posts.none { it.id == "p" })
        expect("deleted target fails rather than guessing", runCatching { SocialReplyPolicy.build(world, "p", "missing", actor.id, base) }.isFailure)
        actor.settings.moodEnabled = false
        expect("mood off omits scores", !SocialReplyPolicy.build(world,"p","c",actor.id,base).system.contains("friendliness "))
        actor.settings.moodEnabled = true; actor.settings.automaticMoodEnabled = false; actor.mood.annoyance = 8
        expect("manual mood score injected", SocialReplyPolicy.build(world,"p","c",actor.id,base).system.contains("annoyance 8/10"))
        val auto = actor.settings.copy(automaticMoodEnabled = true)
        val liked = FeedComment(id = "thanks", postId = "p", authorId = "self", text = "thank you, good job", createdAt = 400)
        world.comments += liked
        val brain = SocialBrain.replay(world,actor,"thanks",auto)
        expect("automatic mood responds to actual user message", brain.friendliness >= actor.baselineMood.friendliness && brain.lastProcessedUserMessageId == "thanks")
        val freeze = SocialBrain.replay(world, actor, "thanks", auto.copy(moodEnabled = false))
        expect("disabled mood freezes saved values", freeze.friendliness == actor.mood.friendliness && freeze.annoyance == actor.mood.annoyance)
        val global = ConversationState()
        SceneDirector.applyDirectives(base,global,"*scene: friends at a cafe* *cast: Mara is a neighbor; Alex is a photographer* *location: cafe*",sourceMessageId="g")
        val local = ConversationState()
        SceneDirector.applyDirectives(base,local,"*location: outside* *my role: visitor* *reply: explain briefly*",sourceMessageId="l")
        world.globalScene = global.sceneState; post.scene = local.sceneState
        val scene = SocialReplyPolicy.mergedScene(world.globalScene,post.scene)
        expect("scene cast persists", scene.director.fields["cast"]?.contains("Mara") == true)
        expect("post field overrides global only where specified", scene.director.fields["location"] == "outside" && scene.premise.contains("cafe"))
        expect("user-role director applied", scene.director.fields["userRole"] == "visitor")
        val p2 = SocialReplyPolicy.build(world,"p","c",actor.id,base)
        expect("one-reply directive in prompt", p2.nextDirection == "explain briefly" && p2.system.contains("explain briefly"))
        expect("scene doesn't force physical narration on public channel", p2.system.contains("not physical narration"))
        SceneDirector.applyDirectives(base,local,"*clear scene*",sourceMessageId="clear")
        expect("clear scene works", !local.sceneState.active && local.sceneState.director.nextReply.isEmpty())
        expect("unclosed direction warns", SceneDirector.parse("*scene: cafe").warnings.isNotEmpty())
        expect("speaker continuation removed", SocialReplyPolicy.clean("Mara: Hello!\nJesse: Hi!\nAlex: Bye",world,base,actor.id) == "Hello!")
        expect("reasoning-only not replaced by fake answer", runCatching { SocialReplyPolicy.clean("<think>what to write</think>",world,base,actor.id) }.isFailure)
        expect("action-only not posted", runCatching { SocialReplyPolicy.clean("*winks at you*",world,base,actor.id) }.isFailure)
        expect("photo marker not shown", SocialReplyPolicy.clean("Here is the picture. [[SEND_PHOTO:coffee]]",world,base,actor.id) == "Here is the picture.")
        val pics = listOf(CharacterPhoto(path="coffee", tags=mutableListOf("coffee", "cafe")), CharacterPhoto(path="shoe", tags=mutableListOf("shoes", "blue")))
        val sharing = PhotoSharingSettings()
        expect("no unsolicited image", PhotoSharePolicy.choose(sharing,pics,"Hi", "Hello!", "coffee") == null)
        expect("matching requested photo", PhotoSharePolicy.choose(sharing,pics,"Please send a picture of your shoes", "Sure")?.path == "shoe")
        expect("unmatched request no random substitute", PhotoSharePolicy.choose(sharing,pics,"Send a photo of the mountains", "Sure") == null)
        expect("refusal overrides marker", PhotoSharePolicy.choose(sharing,pics,"send a pic of coffee", "I'd rather not.","coffee") == null)
        expect("don't send overrides marker", PhotoSharePolicy.choose(sharing,pics,"Don't send a picture", "Okay", "coffee") == null)
        expect("public photo denied by default", PhotoSharePolicy.choose(sharing,pics,"send a pic of coffee", "Okay", public=true) == null)
        expect("explicit public permission honored", PhotoSharePolicy.choose(sharing.copy(publicReplies=true),pics,"send a pic of coffee", "Okay", public=true)?.path == "coffee")
        expect("never share setting enforced", PhotoSharePolicy.choose(sharing.copy(mode="never"),pics,"send a pic of coffee", "Okay", "coffee") == null)
        expect("recent image not repeated", PhotoSharePolicy.choose(sharing,pics,"send a pic of coffee", "Okay", recentPaths=listOf("coffee")) == null)
        expect("contextual requires meaningful marker", PhotoSharePolicy.choose(sharing.copy(mode="contextual"),pics,"lovely day", "yes", "photo") == null)
        expect("contextual cooldown enforced", PhotoSharePolicy.choose(sharing.copy(mode="contextual"),pics,"lovely day", "yes", "coffee",turnsSincePhoto=1) == null)
        expect("contextual matching after cooldown", PhotoSharePolicy.choose(sharing.copy(mode="contextual"),pics,"lovely day", "yes", "coffee",turnsSincePhoto=8)?.path == "coffee")
        expect("asking for clarification is not photo consent", PhotoSharePolicy.choose(sharing,pics,"send a pic of coffee", "Why do you want a picture?") == null)
        expect("a deferred photo is not sent despite marker", PhotoSharePolicy.choose(sharing,pics,"send a pic of coffee", "Maybe later.", "coffee") == null)
        expect("an attachment promise is identified", PhotoSharePolicy.claimsAttachment("Here's a picture of the cup."))
        expect("ordinary description not mistaken for attachment promise", !PhotoSharePolicy.claimsAttachment("That photo sounds interesting."))
        expect("cut-off text fails visibly", runCatching { SocialReplyPolicy.clean("I thought we might go to the",world,base,actor.id,true) }.isFailure)
        expect("complete sentence at limit is retained", SocialReplyPolicy.clean("Sunday works.",world,base,actor.id,true) == "Sunday works.")
        expect("nine real style settings", HumanTypingStyle.values.size == 9 && HumanTypingStyle.values.all { HumanTypingStyle.prompt(it).isNotBlank() })
        HumanTypingStyle.values.forEach { v ->
            expect("style preserves names numbers and negation: $v", HumanTypingStyle.apply("I can't meet Jesse at 14:30.",base.copy(typingRealism=v),42,false) == "I can't meet Jesse at 14:30.")
        }
        expect("new styles survive settings roundtrip", CharacterSettings.fromJson(base.copy(typingRealism="dry").toJson()).typingRealism == "dry")
        return passed
    }
}
