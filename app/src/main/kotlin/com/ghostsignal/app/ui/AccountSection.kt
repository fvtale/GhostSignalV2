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
import com.ghostsignal.app.glasses.GlassesState
import com.ghostsignal.app.net.AccountPlan
import com.ghostsignal.app.net.GlassesEvidence
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Optional account block on the setup screen: sign in, plan, premium, and
 * glasses linking. The game plays identically signed out; an account makes
 * finished runs and premium follow the runner between phones.
 */
@Composable
fun AccountSection(graph: AppGraph) {
  val plan by graph.account.plan.collectAsStateWithLifecycle()
  val settings by graph.settings.flow.collectAsStateWithLifecycle()
  val glasses by graph.glasses.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current

  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  var awaitingPayment by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { graph.account.refresh() }

  // After the payment page, poll for premium for a few minutes; the
  // provider's webhook can lag behind the runner's return.
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
    val currentPlan = plan
    when {
      settings.backendUrl.isBlank() -> {
        Label("Accounts need the GhostSignal server. Set it in Settings.", Rail.Dim, 11)
      }

      currentPlan == null -> SignedOutForm(
          email = email,
          password = password,
          busy = busy,
          onEmailChange = { email = it },
          onPasswordChange = { password = it },
          onSubmit = { createAccount ->
            busy = true
            message = null
            scope.launch {
              message = graph.account.signIn(email, password, createAccount)
              if (message == null) password = ""
              busy = false
            }
          },
      )

      else -> SignedInPlan(
          graph = graph,
          plan = currentPlan,
          glassesState = glasses,
          busy = busy,
          awaitingPayment = awaitingPayment,
          onBusyChange = { busy = it },
          onMessage = { message = it },
          onAwaitPayment = {
            awaitingPayment = true
            currentPlan.payment.url?.let { url -> uriHandler.openUri(url) }
          },
          onOpenUri = { url -> uriHandler.openUri(url) },
      )
    }
    message?.let { Label(it, Rail.Brass, 12) }
  }
}

@Composable
private fun SignedOutForm(
    email: String,
    password: String,
    busy: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: (createAccount: Boolean) -> Unit,
) {
  Label(
      "Optional. Sign in to keep your finished runs across phones and to get premium soundtracks.",
      Rail.Dim,
      11,
  )
  OutlinedTextField(
      value = email,
      onValueChange = onEmailChange,
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Email") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
  )
  OutlinedTextField(
      value = password,
      onValueChange = onPasswordChange,
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Password (10+ characters)") },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    RailButton(
        if (busy) "…" else "Sign in",
        { onSubmit(false) },
        Modifier.weight(1f),
        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
    )
    GhostButton(
        "Create account",
        { onSubmit(true) },
        Modifier.weight(1f),
        enabled = !busy && email.isNotBlank() && password.length >= 10,
    )
  }
}

@Composable
private fun SignedInPlan(
    graph: AppGraph,
    plan: AccountPlan,
    glassesState: GlassesState,
    busy: Boolean,
    awaitingPayment: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onMessage: (String?) -> Unit,
    onAwaitPayment: () -> Unit,
    onOpenUri: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val premium = plan.account.premium

  Status("Signed in", plan.account.email, true)
  Status(
      "Plan",
      if (premium.active) {
        "Premium" + (premium.untilMs?.let { until ->
          " until ${DateFormat.getDateInstance().format(Date(until))}"
        } ?: "")
      } else {
        "Free"
      },
      if (premium.active) true else null,
  )

  if (plan.premiumTracks.isNotEmpty()) {
    Label(
        (if (premium.active) "Premium soundtracks: " else "Premium adds: ") +
          plan.premiumTracks.joinToString { track -> track.title },
        Rail.Dim,
        11,
    )
  }

  val paymentUrl = plan.payment.url
  if (!premium.active && paymentUrl != null) {
    RailButton(
        "Get premium" + plan.payment.priceLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
        onAwaitPayment,
        color = Rail.Brass,
    )
    Label(
        if (awaitingPayment) {
          "Waiting for the payment to come through…"
        } else {
          "Pay in the browser, then come back here: premium switches on by itself."
        },
        if (awaitingPayment) Rail.Brass else Rail.Dim,
        11,
    )
  }

  // Linked glasses: each approved pair makes the account premium.
  plan.glasses.forEach { link ->
    val (statusText, ok) = when (link.status) {
      "approved" -> "linked · premium" to true
      "pending" -> "waiting for an admin to approve" to null
      "rejected" -> "not approved" to false
      else -> "removed by an admin" to false
    }
    Status(
        "${link.deviceType.replace('_', ' ').lowercase()} ${link.fingerprint}",
        statusText,
        ok,
    )
    GhostButton(
        "Unlink ${link.fingerprint}",
        { scope.launch { onMessage(graph.account.unlinkGlasses(link.id)) } },
        enabled = !busy,
    )
  }

  val connectedDeviceId = glassesState.deviceId
  if (connectedDeviceId != null && !glassesState.mock) {
    GhostButton(
        "Link these glasses · ${glassesState.deviceName ?: "connected pair"}",
        {
          onBusyChange(true)
          scope.launch {
            onMessage(
                graph.account.linkGlasses(
                    GlassesEvidence(
                        identifier = connectedDeviceId,
                        deviceType = glassesState.deviceType ?: "UNKNOWN",
                        name = glassesState.deviceName.orEmpty(),
                        firmware = glassesState.firmware.orEmpty(),
                    ),
                ),
            )
            onBusyChange(false)
          }
        },
        enabled = !busy,
    )
    Label("Linked glasses make your account premium once an admin approves the pair.", Rail.Dim, 11)
  } else if (plan.glasses.isEmpty()) {
    Label(
        "Own Meta glasses? Connect them above, then link them here: an approved pair makes your account premium.",
        Rail.Dim,
        11,
    )
  }

  val settings by graph.settings.flow.collectAsStateWithLifecycle()
  val manageUrl = premium.manageUrl ?: plan.payment.manageUrl
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (premium.active && premium.source == "payment" && manageUrl != null) {
      GhostButton("Manage billing", { onOpenUri(manageUrl) }, Modifier.weight(1f))
    }
    GhostButton(
        "Account page",
        { onOpenUri(settings.backendUrl.trimEnd('/') + "/account/") },
        Modifier.weight(1f),
    )
    GhostButton(
        "Sign out",
        {
          onBusyChange(true)
          scope.launch {
            graph.account.signOut()
            onBusyChange(false)
          }
        },
        Modifier.weight(1f),
        enabled = !busy,
    )
  }
}
