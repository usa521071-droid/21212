package dev.ffmpegkit.llama

import kotlinx.coroutines.delay

// Controlled fake backend for host concurrency tests only; never packaged in the APK.
data class LlamaConfig(val contextSize:Int=2048, val threads:Int=4, val gpuLayers:Int=0,
    val temperature:Float=.7f, val topP:Float=.9f, val topK:Int=40, val seed:Int=-1)
class LlamaModel(val path: String) { @Volatile var released=false }
data class LlamaResult(val text:String, val tokensPerSecond:Float=10f,
    val promptEvalTimeMs:Long=10, val generateTimeMs:Long=100, val tokensGenerated:Int=3)
object Llama {
    var loads=0; var releases=0; var active=0; var maxActive=0
    suspend fun loadModel(modelPath:String, config:LlamaConfig):LlamaModel { delay(10); loads++; return LlamaModel(modelPath) }
    suspend fun complete(model:LlamaModel, prompt:String, systemPrompt:String, maxTokens:Int):LlamaResult {
        check(!model.released); active++; maxActive=maxOf(active,maxActive)
        try { delay(80); check(!model.released) { "Handle freed during completion" }; return LlamaResult(model.path + ":" + prompt) }
        finally { active-- }
    }
    fun releaseModel(model:LlamaModel) { check(!model.released); model.released=true; releases++ }
}
