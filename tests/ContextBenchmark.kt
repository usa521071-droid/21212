import com.localcharacter.chat.*

object ContextBenchmark {
    @JvmStatic fun main(args:Array<String>) {
        val s=CharacterSettings()
        println("Host JVM benchmark; this does NOT measure Android inference or tokens/s.")
        for(n in listOf(20,100,500)) {
            val c=ConversationState()
            repeat(n/2) { i ->
                c.messages+=ChatMessage(role="user",text="The train plan for station $i is at 9 tomorrow.")
                c.messages+=ChatMessage(role="assistant",text="Let's meet at station $i before departure.")
            }
            c.messages+=ChatMessage(role="user",text="Why before departure?")
            MemoryManager.refresh(c)
            repeat(10) { ReplyPipeline.buildPlan(s,c,GenerationMetrics()) }
            val times=List(30) { val start=System.nanoTime(); ReplyPipeline.buildPlan(s,c,GenerationMetrics()); (System.nanoTime()-start)/1e6 }.sorted()
            val plan=ReplyPipeline.buildPlan(s,c,GenerationMetrics())
            val mean=times.average()
            println("messages=$n median_ms=%.3f mean_ms=%.3f prompt_chars=%d selected=%d".format(times[times.size/2],mean,plan.promptCharacters,plan.selectedMessages))
            check(plan.promptCharacters<8000)
        }
    }
}
