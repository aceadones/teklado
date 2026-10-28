// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 0
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        else -> 3
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(step) {
        if (step == 2)
            scope.launch {
                while (step == 2 && !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) {
                    delay(50)
                }
                step = 3
            }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        step = determineStep()
    }
    val useWideLayout = isWideScreen()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            if (useWideLayout) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(0.4f)) {
                        WizardHeadline(step)
                    }
                    Column(Modifier.weight(0.6f)) {
                        WizardBody(
                            step = step,
                            onStart = { step = 1 },
                            onEnable = {
                                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                    .addCategory(Intent.CATEGORY_DEFAULT)
                                launcher.launch(intent)
                            },
                            onSwitch = imm::showInputMethodPicker,
                            onConfigure = close,
                            onFinish = finish,
                        )
                    }
                }
            } else {
                WizardHeadline(step)
                Spacer(Modifier.height(20.dp))
                WizardBody(
                    step = step,
                    onStart = { step = 1 },
                    onEnable = {
                        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            .addCategory(Intent.CATEGORY_DEFAULT)
                        launcher.launch(intent)
                    },
                    onSwitch = imm::showInputMethodPicker,
                    onConfigure = close,
                    onFinish = finish,
                )
            }
        }
    }
}

@Composable
private fun WizardHeadline(step: Int) {
    val title = when (step) {
        0 -> stringResource(R.string.teklado_welcome_title)
        1 -> stringResource(R.string.teklado_onboarding_turn_on)
        2 -> stringResource(R.string.teklado_onboarding_make_keyboard)
        else -> stringResource(R.string.teklado_onboarding_pick_look)
    }
    val body = when (step) {
        0 -> stringResource(R.string.teklado_welcome_body)
        1 -> stringResource(R.string.teklado_onboarding_turn_on_body)
        2 -> stringResource(R.string.teklado_onboarding_make_keyboard_body)
        else -> stringResource(R.string.teklado_onboarding_pick_look_body)
    }
    Text(
        title,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (step == 0 && JniUtils.hasGlide()) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.setup_welcome_additional_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun WizardBody(
    step: Int,
    onStart: () -> Unit,
    onEnable: () -> Unit,
    onSwitch: () -> Unit,
    onConfigure: () -> Unit,
    onFinish: () -> Unit,
) {
    StepProgress(step)
    Spacer(Modifier.height(20.dp))
    SettingsCard {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            when (step) {
                0 -> Step0(onStart)
                1 -> Text(
                    stringResource(R.string.setup_step1_instruction, stringResource(R.string.english_ime_name)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                2 -> Text(
                    stringResource(R.string.setup_step2_instruction, stringResource(R.string.english_ime_name)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                else -> KeyboardThemeModePicker()
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    when (step) {
        0 -> Unit
        1 -> PrimaryPill(stringResource(R.string.setup_step1_action), onEnable)
        2 -> {
            PrimaryPill(stringResource(R.string.setup_step2_action), onSwitch)
            SecondaryPill(stringResource(R.string.setup_step3_action), onConfigure)
        }
        else -> {
            PrimaryPill(stringResource(R.string.setup_finish_action), onFinish)
            SecondaryPill(stringResource(R.string.setup_step3_action), onConfigure)
        }
    }
}

@Composable
private fun StepProgress(step: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(4) { index ->
            Spacer(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(
                        if (index <= step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(6.dp),
                    )
            )
        }
    }
}

@Composable
private fun PrimaryPill(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SecondaryPill(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Step0(onClick: () -> Unit) {
    PrimaryPill(stringResource(R.string.setup_start_action), onClick)
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) { }
        }
    }
}

@Preview(
    device = "spec:orientation=landscape,width=400dp,height=780dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) { }
        }
    }
}
