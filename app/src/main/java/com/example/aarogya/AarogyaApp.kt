package com.example.aarogya

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.example.aarogya.classify.ClassifyWorker
import com.example.aarogya.classify.FoodClassifier
import com.example.aarogya.data.FoodDb
import com.example.aarogya.data.PinLock
import com.example.aarogya.data.Prefs
import com.example.aarogya.data.Store
import com.example.aarogya.llm.MealAi
import com.example.aarogya.llm.ModelManager
import com.example.aarogya.llm.PlateAi
import com.example.aarogya.vision.FoodPhotos
import com.example.aarogya.googlehealth.GoogleHealth
import com.example.aarogya.reminders.MealReminders
import com.example.aarogya.whoop.Whoop

class AarogyaApp : Application() {
    val store by lazy { Store(this) }
    val prefs by lazy { Prefs(this) }
    val foodDb by lazy { assets.open("foods.csv").use { FoodDb.load(it) } }
    val models by lazy { ModelManager(this) }
    // Only one on-device model stays loaded at a time; each unloads the other before loading.
    val mealAi: MealAi by lazy { MealAi(models) { plateAi.release() } }
    val plateAi: PlateAi by lazy { PlateAi(models) { mealAi.release() } }
    val photos by lazy { FoodPhotos(this) }
    val whoop by lazy { Whoop(this, store) }
    val classifier by lazy { FoodClassifier(this) }
    val googleHealth by lazy { GoogleHealth(this, store) }
    val pinLock by lazy { PinLock(this) }
    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        prefs.state // loads settings, which sets AppTime's tracking zone before anything dates data
        MealReminders.createChannel(this)
        appScope.launch(Dispatchers.IO) { MealReminders.scheduleAll(this@AarogyaApp) }
        // Resume classifying anything still pending from a previous session.
        appScope.launch(Dispatchers.IO) { if (store.pendingFoods().isNotEmpty()) ClassifyWorker.enqueue(this@AarogyaApp) }
        // Sync WHOOP and Google Health every time the app comes to the foreground (not on rotation or in-app navigation).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (whoop.state.value.connected && !whoop.state.value.syncing) appScope.launch { whoop.sync() }
                if (googleHealth.state.value.connected) appScope.launch { googleHealth.syncSilently() }
            }

            // Leaving the app re-locks the Weight tab.
            override fun onStop(owner: LifecycleOwner) = pinLock.lock()
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The LLM holds a few hundred MB; drop it when the app is backgrounded under pressure.
        if (level >= TRIM_MEMORY_BACKGROUND) { mealAi.release(); plateAi.release() }
    }
}
