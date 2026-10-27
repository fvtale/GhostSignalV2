package com.ghostsignal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostsignal.app.AppGraph
import com.ghostsignal.app.net.GlassesEvidence
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sign in, see the plan, get premium. Optional: the game plays the same signed out. */
@Composable
fun AccountSection(graph: AppGraph) {
  val plan by graph.account.plan.collectAsStateWithLifecycle()
  val settings by graph.settings.flow.collectAsStateWithLifecycle()
  val glasses by graph.glasses.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val uri = LocalUriHandler.current
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  var awaitingPayment by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { graph.account.refresh() }
  // After opening the payment page, look for premium for a few minutes; the webhook may lag.
  LaunchedEffect(awaitingPayment) {
    if (!awaitingPayment) return@LaunchedEffect
    repeat(36) {
      delay(5_000)
      graph.account.refresh()
      if (graph.account.plan.value?.account?.premium?.active == true) {
        awaitingPayment = false
        return@LaunchedEffect
      }
    }
    awaitingPayment = false
  }

  Section("Account") {
    val p = plan
    if (settings.backendUrl.isBlank()) {
      Label("Accounts need the GhostSignal server. Set it in Settings.", Neon.Dim, 11)
    } else if (p == null) {
      Label("Optional. Sign in to keep your finished runs across phones and to get premium soundtracks.", Neon.Dim, 11)
      OutlinedTextField(
          email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
      OutlinedTextField(
          password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password (10+ characters)") }, singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fun go(create: Boolean) {
          busy = true
          message = null
          scope.launch {
            message = graph.account.signIn(email, password, create)
            if (message == null) password = ""
            busy = false
          }
        }
        NeonButton(if (busy) "…" else "Sign in", { go(create = false) }, Modifier.weight(1f), enabled = !busy && email.isNotBlank() && password.isNotBlank())
        GhostButton("Create account", { go(create = true) }, Modifier.weight(1f), enabled = !busy && email.isNotBlank() && password.length >= 10)
      }
    } else {
      val premium = p.account.premium
      Status("Signed in", p.account.email, true)
      Status(
          "Plan",
          if (premium.active) "Premium" + (premium.untilMs?.let { " until ${DateFormat.getDateInstance().format(Date(it))}" } ?: "") else "Free",
          if (premium.active) true else null)
      if (p.premiumTracks.isNotEmpty()) {
        Label((if (premium.active) "Premium soundtracks: " else "Premium adds: ") + p.premiumTracks.joinToString { it.title }, Neon.Dim, 11)
      }
      val pay = p.payment.url
      if (!premium.active && pay != null) {
        NeonButton(
            "Get premium" + p.payment.priceLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            {
              awaitingPayment = true
              uri.openUri(pay)
            },
            color = Neon.Amber)
        Label(
            if (awaitingPayment) "Waiting for the payment to come through…" else "Pay in the browser, then come back here: premium switches on by itself.",
            if (awaitingPayment) Neon.Amber else Neon.Dim,
            11)
      }

      // Linked glasses: each pair makes the account premium once an admin approves it.
      p.glasses.forEach { l ->
        val (what, ok) =
            when (l.status) {
              "approved" -> "linked · premium" to true
              "pending" -> "waiting for an admin to approve" to null
              "rejected" -> "not approved" to false
              else -> "removed by an admin" to false
            }
        Status("${l.deviceType.replace('_', ' ').lowercase()} ${l.fingerprint}", what, ok)
        GhostButton(
            "Unlink ${l.fingerprint}",
            {
              scope.launch { message = graph.account.unlinkGlasses(l.id) }
            },
            enabled = !busy)
      }
      val deviceId = glasses.deviceId
      if (deviceId != null && !glasses.mock) {
        GhostButton(
            "Link these glasses · ${glasses.deviceName ?: "connected pair"}",
            {
              busy = true
              scope.launch {
                message =
                    graph.account.linkGlasses(
                        GlassesEvidence(deviceId, glasses.deviceType ?: "UNKNOWN", glasses.deviceName.orEmpty(), glasses.firmware.orEmpty()))
                busy = false
              }
            },
            enabled = !busy)
        Label("Linked glasses make your account premium once an admin approves the pair.", Neon.Dim, 11)
      } else if (p.glasses.isEmpty()) {
        Label("Own Meta glasses? Connect them above, then link them here: an approved pair makes your account premium.", Neon.Dim, 11)
      }

      val manage = premium.manageUrl ?: p.payment.manageUrl
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (premium.active && premium.source == "payment" && manage != null) GhostButton("Manage billing", { uri.openUri(manage) }, Modifier.weight(1f))
        GhostButton("Account page", { uri.openUri(settings.backendUrl.trimEnd('/') + "/account/") }, Modifier.weight(1f))
        GhostButton(
            "Sign out",
            {
              busy = true
              scope.launch {
                graph.account.signOut()
                busy = false
              }
            },
            Modifier.weight(1f),
            enabled = !busy)
      }
    }
    message?.let { Label(it, Neon.Amber, 12) }
  }
}
