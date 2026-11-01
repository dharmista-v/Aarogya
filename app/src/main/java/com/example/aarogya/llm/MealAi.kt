package com.example.aarogya.llm

import com.example.aarogya.data.MealParser
import com.example.aarogya.data.ParsedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps one SmolLM2 instance loaded and uses it to turn meal text into structured items. */
/** [onLoad] lets the app unload other models first to cap memory. */
class MealAi(private val models: ModelManager, private val onLoad: () -> Unit = {}) {
    private val lock = Mutex()
    private var loaded: Pair<ModelOption, SmolLm>? = null
    // Closing a session mid-inference crashes natively, so a release requested while busy waits for the run to end.
    private var busy = false
    private var releaseWhenIdle = false

    suspend fun parse(option: ModelOption, text: String): List<ParsedItem> = withContext(Dispatchers.Default) {
        lock.withLock {
            synchronized(this@MealAi) { busy = true }
            try {
                parseLocked(option, text)
            } finally {
                synchronized(this@MealAi) { busy = false; if (releaseWhenIdle) release() }
            }
        }
    }

    /**
     * Free-form chat answer. [onPartial] receives the reply so far after every token (for streaming).
     * A mild repetition penalty keeps the small model from looping in longer answers.
     */
    suspend fun chat(
        option: ModelOption,
        system: String,
        turns: List<Pair<String, String>>,
        question: String,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        lock.withLock {
            synchronized(this@MealAi) { busy = true }
            try {
                val lm = loadLocked(option)
                val ctx = kotlin.coroutines.coroutineContext
                lm.generate(lm.chatPrompt(system, turns, question), maxNewTokens = 160, repetitionPenalty = 1.15f) { soFar ->
                    ctx.ensureActive()
                    onPartial(soFar)
                    false
                }.trim()
            } finally {
                synchronized(this@MealAi) { busy = false; if (releaseWhenIdle) release() }
            }
        }
    }

    fun isLoaded(option: ModelOption): Boolean = loaded?.first == option

    /** Loads the model ahead of time (e.g. when the Chat tab opens) so the first answer is quick. */
    suspend fun preload(option: ModelOption) = withContext(Dispatchers.Default) {
        lock.withLock { loadLocked(option) }
    }

    private fun loadLocked(option: ModelOption): SmolLm =
        loaded?.takeIf { it.first == option }?.second ?: run {
            loaded?.second?.close(); loaded = null
            onLoad()
            SmolLm(models.modelFile(option), models.tokenizerFile(option)).also { loaded = option to it }
        }

    private suspend fun parseLocked(option: ModelOption, text: String): List<ParsedItem> {
        val lm = loadLocked(option)
        val ctx = kotlin.coroutines.coroutineContext
        val prompt = lm.chatPrompt(MealParser.LLM_SYSTEM, MealParser.LLM_SHOTS, text)
        val output = lm.generate(prompt, maxNewTokens = 80) { soFar ->
            ctx.ensureActive()
            soFar.endsWith("\n\n") // the model sometimes rambles after a blank line
        }
        return MealParser.parseLlmOutput(output)
    }

    @Synchronized
    fun release() {
        if (busy) { releaseWhenIdle = true; return }
        releaseWhenIdle = false
        loaded?.second?.close()
        loaded = null
    }
}
