package com.example.readalong.ui.about

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.domain.i18n.tr

@Composable
private fun rememberFullVersion(): FullVersion.State {
    val context = LocalContext.current
    val fullVersion = remember { FullVersion.get(context) }
    // Coming back from the Play Store (a purchase or a redeemed code) picks up the unlock straight away.
    LifecycleResumeEffect(fullVersion) { fullVersion.refresh(); onPauseOrDispose { } }
    val state by fullVersion.state.collectAsStateWithLifecycle()
    return state
}

/**
 * After the 90-day trial, a reminder each time ReadAlong opens. It never blocks anything: "Not now" closes it
 * until the next launch.
 */
@Composable
fun FullVersionPrompt() {
    val state = rememberFullVersion()
    var closed by rememberSaveable { mutableStateOf(false) }
    if (closed || state.owned || state.pending || !state.trialEnded()) return
    UnlockDialog(state, trialOver = true) { closed = true }
}

/** Settings › About: trial days left, or the unlock. */
@Composable
fun FullVersionSettings() {
    val state = rememberFullVersion()
    var open by rememberSaveable { mutableStateOf(false) }
    val days = state.daysLeft()
    when {
        state.owned -> SettingsRow("Full version", summary = "Unlocked. Thank you for supporting ReadAlong", enabled = false, onClick = {}, trailing = {})
        state.pending -> SettingsRow("Full version", summary = "Your purchase is waiting for payment to go through", enabled = false, onClick = {}, trailing = {})
        else -> SettingsRow("Unlock the full version", summary = if (days > 0) tr("Free trial: $days ${if (days == 1) "day" else "days"} left") else tr("Your free trial has ended"), onClick = { open = true })
    }
    if (open && !state.owned) UnlockDialog(state, trialOver = days == 0) { open = false }
}

@Composable
private fun UnlockDialog(state: FullVersion.State, trialOver: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.take(16).filterIsInstance<Activity>().firstOrNull()
    }
    val fullVersion = remember { FullVersion.get(context) }
    var codeEntry by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var storeMissing by remember { mutableStateOf(false) }
    val price = state.price ?: "\$0.99"
    val colors = MaterialTheme.colorScheme
    fun redeem() { if (code.isNotBlank() && activity != null) { fullVersion.redeem(activity, code); onClose() } }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surface, tonalElevation = 2.dp) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                com.example.readalong.ui.launch.PageTurnLogo(0f, Modifier.size(96.dp))
                Text(tr(if (trialOver) "Your free trial has ended" else "Unlock ReadAlong"), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = colors.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(tr(if (trialOver) "Thanks for trying ReadAlong for ${Trial.DAYS} days. Unlock it for $price to keep it for good. Everything keeps working either way."
                    else "Unlock ReadAlong for $price to keep it for good once the ${Trial.DAYS}-day trial ends. It's a one-time purchase."),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Button({ storeMissing = activity == null || !fullVersion.buy(activity) }, Modifier.fillMaxWidth().height(48.dp)) {
                    Text(tr("Unlock for $price"), fontWeight = FontWeight.Bold)
                }
                if (storeMissing) Text(tr("Google Play isn't reachable right now. Try again in a moment."), Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                if (!codeEntry) FilledTonalButton({ codeEntry = true }, Modifier.fillMaxWidth().height(48.dp)) { Text(tr("I have a code"), fontWeight = FontWeight.Bold) }
                else {
                    OutlinedTextField(code, { code = it.uppercase() }, Modifier.fillMaxWidth(), label = { Text(tr("Code")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { redeem() }))
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(::redeem, Modifier.fillMaxWidth().height(48.dp), enabled = code.isNotBlank()) { Text(tr("Redeem in Google Play"), fontWeight = FontWeight.Bold) }
                }
                TextButton(onClose, Modifier.padding(top = 4.dp)) { Text(tr("Not now")) }
            }
        }
    }
}
