package com.example.aarogya

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.Meal
import androidx.compose.runtime.LaunchedEffect
import com.example.aarogya.ui.AarogyaTheme
import com.example.aarogya.ui.AddActivitySheet
import com.example.aarogya.ui.AddFoodSheet
import com.example.aarogya.ui.EditFoodSheet
import com.example.aarogya.ui.FoodsScreen
import com.example.aarogya.ui.SettingsSheet
import com.example.aarogya.ui.StatusDot
import com.example.aarogya.ui.TodayScreen
import com.example.aarogya.ui.TrendsScreen
import com.example.aarogya.ui.ChatScreen
import com.example.aarogya.ui.CreatePinScreen
import com.example.aarogya.ui.UnlockPinScreen
import com.example.aarogya.ui.WeightScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    companion object {
        const val EXTRA_ADD_FOOD_MEAL = "add_food_meal"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AarogyaTheme { AppRoot(vm) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** WHOOP redirects back to aarogya://whoop-callback; meal reminders open Add food for their meal. */
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ADD_FOOD_MEAL)?.let { name ->
            runCatching { Meal.valueOf(name) }.getOrNull()?.let(vm::requestAddFood)
            intent.removeExtra(EXTRA_ADD_FOOD_MEAL)
        }
        val uri = intent?.data ?: return
        if (uri.scheme == "aarogya" && uri.host == "whoop-callback") {
            vm.onWhoopCallback(uri)
            intent.data = null
        }
    }
}

private enum class Tab { TODAY, FOODS, WEIGHT, TRENDS, CHAT }
private enum class Sheet { NONE, FOOD, ACTIVITY, SETTINGS, EDIT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var editingId by rememberSaveable { mutableStateOf(0L) }
    val editFood: (FoodEntry) -> Unit = { editingId = it.id; sheet = Sheet.EDIT }
    val allFoods by vm.allFoods.collectAsStateWithLifecycle()
    val foodSearch by vm.foodSearch.collectAsStateWithLifecycle()
    val healthState by vm.googleHealth.collectAsStateWithLifecycle()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val overall by vm.overall.collectAsStateWithLifecycle()
    val chatMessages by vm.chat.collectAsStateWithLifecycle()
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    val pinUnlocked by vm.pinUnlocked.collectAsStateWithLifecycle()
    var changingPin by rememberSaveable { mutableStateOf(false) }
    val addFoodRequest by vm.addFoodRequest.collectAsStateWithLifecycle()
    LaunchedEffect(addFoodRequest) {
        vm.consumeAddFoodRequest()?.let { meal ->
            vm.startDraft(meal)
            tab = Tab.TODAY
            sheet = Sheet.FOOD
        }
    }
    val dayUi by vm.dayUi.collectAsStateWithLifecycle()
    val trends by vm.trends.collectAsStateWithLifecycle()
    val whoop by vm.whoop.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Aarogya", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(12.dp))
                        StatusDot("WHOOP", ok = whoop.connected && whoop.error == null) { sheet = Sheet.SETTINGS }
                        Spacer(Modifier.width(6.dp))
                        StatusDot("Google Health", ok = healthState.connected && healthState.error == null && !healthState.needsSignIn) { tab = Tab.WEIGHT }
                    }
                },
                actions = { IconButton({ sheet = Sheet.SETTINGS }) { Icon(Icons.Filled.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == Tab.TODAY, onClick = { tab = Tab.TODAY },
                    icon = { Icon(painterResource(R.drawable.ic_today), null) }, label = { Text("Day") },
                )
                NavigationBarItem(
                    selected = tab == Tab.FOODS, onClick = { tab = Tab.FOODS },
                    icon = { Icon(painterResource(R.drawable.ic_list), null) }, label = { Text("Foods") },
                )
                NavigationBarItem(
                    selected = tab == Tab.WEIGHT, onClick = { tab = Tab.WEIGHT },
                    icon = { Icon(painterResource(R.drawable.ic_scale), null) }, label = { Text("Weight") },
                )
                NavigationBarItem(
                    selected = tab == Tab.TRENDS, onClick = { tab = Tab.TRENDS },
                    icon = { Icon(painterResource(R.drawable.ic_chart), null) }, label = { Text("Trends") },
                )
                NavigationBarItem(
                    selected = tab == Tab.CHAT, onClick = { tab = Tab.CHAT },
                    icon = { Icon(painterResource(R.drawable.ic_chat), null) }, label = { Text("Chat") },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Crossfade(tab, label = "tab") { t ->
            Box(Modifier.fillMaxSize()) {
                when (t) {
                    Tab.TODAY -> dayUi?.let { ui ->
                        TodayScreen(
                            ui = ui,
                            contentPadding = padding,
                            onShiftDay = vm::shiftDay,
                            onAddFood = { vm.startDraft(); sheet = Sheet.FOOD },
                            onAddActivity = { sheet = Sheet.ACTIVITY },
                            onDeleteFood = { vm.deleteFood(it) },
                            onEditFood = editFood,
                            onUsePhoneZone = { vm.setTimeZone(java.time.ZoneId.systemDefault().id) },
                            onDeleteBurn = { vm.deleteBurn(it) },
                        )
                    }
                    Tab.FOODS -> allFoods?.let { foods ->
                        FoodsScreen(foods, foodSearch, vm::setFoodSearch, padding, editFood, onDeleteMany = { vm.deleteFoods(it) })
                    }
                    Tab.WEIGHT -> when {
                        !pinSet -> CreatePinScreen(padding, onCreated = vm::setPin)
                        !pinUnlocked -> UnlockPinScreen(padding, vm::unlockPin, vm::pinLockedOutSeconds, onForgot = { vm.forgotPin() })
                        changingPin -> CreatePinScreen(padding, onCreated = { vm.setPin(it); changingPin = false }, onCancel = { changingPin = false })
                        else -> WeightScreen(vm, healthState, weights.orEmpty(), padding, onLock = vm::lockWeight, onChangePin = { changingPin = true })
                    }
                    Tab.CHAT -> ChatScreen(
                        messages = chatMessages,
                        busy = chatMessages.lastOrNull()?.streaming == true,
                        contentPadding = padding,
                        onAsk = vm::ask,
                        onNewChat = vm::newChat,
                        onOpen = { vm.warmUpChat() },
                    )
                    Tab.TRENDS -> trends?.let { ui ->
                        TrendsScreen(ui, overall, padding) { day -> vm.selectDay(day); tab = Tab.TODAY }
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.FOOD -> AddFoodSheet(vm) { sheet = Sheet.NONE }
        Sheet.ACTIVITY -> AddActivitySheet(
            whoopConnected = whoop.connected,
            onSave = { name, kcal -> vm.addBurn(name, kcal) },
        ) { sheet = Sheet.NONE }
        Sheet.SETTINGS -> SettingsSheet(vm) { sheet = Sheet.NONE }
        Sheet.EDIT -> vm.foodById(editingId)?.let { entry ->
            EditFoodSheet(
                entry = entry,
                onSave = vm::updateFood,
                onDelete = { vm.deleteFood(it.id) },
                onRetry = vm::retryClassification,
                onApply = vm::applySuggestions,
                derive = vm::deriveLocally,
                servingFood = vm.foodDb.match(entry.name),
            ) { sheet = Sheet.NONE }
        } ?: run { sheet = Sheet.NONE }
        Sheet.NONE -> Unit
    }
}
