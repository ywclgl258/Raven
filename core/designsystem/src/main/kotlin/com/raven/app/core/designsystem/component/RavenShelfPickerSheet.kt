package com.raven.app.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular

/**
 * Presentation model for a selectable shelf entry in [RavenShelfPickerSheet].
 */
data class RavenShelfOption(
    val id: String,
    val name: String,
    val itemCount: Int,
    val selected: Boolean,
    val isSystem: Boolean = false,
)

/**
 * Shared Dark Obsidian custom shelf picker bottom sheet (`ModalBottomSheet`) usable from
 * browse cards, video player, and gallery detail without nested `AlertDialog`s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RavenShelfPickerSheet(
    title: String,
    shelves: List<RavenShelfOption>,
    onToggleShelf: (shelfId: String, add: Boolean) -> Unit,
    onCreateShelf: (name: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    var newShelfName by rememberSaveable { mutableStateOf("") }

    fun submitCreate() {
        val trimmed = newShelfName.trim()
        if (trimmed.isNotEmpty()) {
            onCreateShelf(trimmed)
            newShelfName = ""
        }
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = title.ifBlank { "加入自定义片单 / 书架" },
        subtitle = "点击切换所属分组，或在下方快速新建",
        modifier = modifier.testTag("raven_shelf_picker_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newShelfName,
                    onValueChange = { newShelfName = it.take(40) },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "新建片单 / 书架名称",
                            color = colors.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.crimson,
                        unfocusedBorderColor = colors.hairline,
                        focusedContainerColor = colors.surface1,
                        unfocusedContainerColor = colors.surface1,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    shape = RavenShapes.ControlShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitCreate() }),
                    modifier = Modifier.weight(1f),
                )
                val createInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        submitCreate()
                    },
                    enabled = newShelfName.trim().isNotEmpty(),
                    interactionSource = createInteraction,
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                        disabledContainerColor = colors.surface3,
                        disabledContentColor = colors.inkTertiary,
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .ravenSpringPress(
                            interactionSource = createInteraction,
                            enabled = newShelfName.trim().isNotEmpty(),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "新建",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)

            if (shelves.isEmpty()) {
                Text(
                    text = "暂无片单，在上方输入名称即可创建",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = shelves, key = { it.id }) { shelf ->
                        val rowBg by animateColorAsState(
                            targetValue = if (shelf.selected) colors.crimson.copy(alpha = 0.12f) else colors.surface1,
                            animationSpec = if (motionReduced) {
                                snap()
                            } else {
                                tween(
                                    durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                                    easing = RavenMotion.HarmonyDecelerationEasing,
                                )
                            },
                            label = "shelfRowBg",
                        )
                        val rowBorder by animateColorAsState(
                            targetValue = if (shelf.selected) colors.crimson.copy(alpha = 0.45f) else colors.hairline,
                            animationSpec = if (motionReduced) {
                                snap()
                            } else {
                                tween(
                                    durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                                    easing = RavenMotion.HarmonyDecelerationEasing,
                                )
                            },
                            label = "shelfRowBorder",
                        )
                        Row(
                            modifier = Modifier
                                .animateItem()
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RavenShapes.ControlShape)
                                .background(rowBg)
                                .border(
                                    width = 0.5.dp,
                                    color = rowBorder,
                                    shape = RavenShapes.ControlShape,
                                )
                                .clickable(role = Role.Checkbox) {
                                    feedback.perform(
                                        if (shelf.selected) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.TOGGLE_ON,
                                    )
                                    onToggleShelf(shelf.id, !shelf.selected)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = if (shelf.selected) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = if (shelf.selected) colors.crimson else colors.inkSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = shelf.name,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = if (shelf.selected) FontWeight.SemiBold else FontWeight.Medium,
                                    ),
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${shelf.itemCount} 项",
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = colors.inkSecondary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
