import com.localcharacter.chat.*
import dev.ffmpegkit.llama.Llama
import kotlinx.coroutines.*
import java.io.File

object NativeLockRegression {
    @JvmStatic fun main(args:Array<String>)=runBlocking {
        val f=File.createTempFile("pcc-mock-model", ".gguf").apply { writeText("Test fixture, not a real model") }
        try {
            val s=CharacterSettings(modelPath=f.absolutePath)
            val engine=LocalLlmEngine()
            coroutineScope { List(5) { async { engine.ensureLoaded(s) } }.awaitAll() }
            check(Llama.loads==1); println("PASS simultaneous loads share one model")
            coroutineScope { List(3) { async { engine.complete("p","s",32) } }.awaitAll() }
            check(Llama.maxActive==1); println("PASS native generation serialized")
            val running=async { engine.complete("p","s",32) }; delay(20)
            engine.unload(); check(Llama.releases==0)
            running.await(); check(Llama.releases==1 && !engine.isLoaded)
            println("PASS unload waits until generation returns")
            engine.ensureLoaded(s); engine.ensureLoaded(s)
            check(Llama.loads==2)
            engine.unload(); check(Llama.releases==2)
            println("PASS unchanged settings do not reload model each turn")
            val second = File.createTempFile("pcc-second-mock", ".gguf").apply { writeText("another fixture") }
            try {
                val results = coroutineScope {
                    listOf(async { engine.completeFor(s, "first", "s", 32) },
                           async { engine.completeFor(s.copy(modelPath=second.absolutePath), "second", "s", 32) }).awaitAll()
                }
                check(results[0].text == f.absolutePath + ":first")
                check(results[1].text == second.absolutePath + ":second")
                println("PASS model selection plus generation is atomic across callers")
            } finally { engine.unload(); second.delete() }
            println("5 concurrency tests passed with a fake backend, not native inference.")
        } finally { f.delete() }
    }
}
