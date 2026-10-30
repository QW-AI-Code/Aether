package studio.cluvex.aether.ui.announcements

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.data.AnnouncementStore
import studio.cluvex.aether.ui.theme.Vazirmatn

// ------------------------------------------------------------------ palette

/** Dark navy dialog palette (1.5.0 announcement spec). */
private object NewsColors {
    val Background = Color(0xFF0D1527)
    val Border = Color(0xFF1E2B4A)
    val Divider = Color(0xFF1A2640)
    val Text = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFCBD5E1)
    val Note = Color(0xFF94A3B8)
    val Accent = Color(0xFF38BDF8)
    val AccentAlt = Color(0xFF60A5FA)
    val OnAccent = Color(0xFF0D1527)
    val WarningBg = Color(0x26EAB308) // rgba(234, 179, 8, 0.15)
    val WarningBar = Color(0xFFEAB308)
    val ScrollTrack = Color(0x1A94A3B8)
    val ScrollThumb = Color(0xFF38BDF8)
    val CheckboxIdle = Color(0xFF64748B)
}

private const val LRI = '\u2066' // LEFT-TO-RIGHT ISOLATE  ~ <bdi dir="ltr">
private const val PDI = '\u2069' // POP DIRECTIONAL ISOLATE

/** Wraps a Latin run so it can never reorder the Persian text around it. */
internal fun ltr(text: String): String = "$LRI$text$PDI"

// ------------------------------------------------------------------ launch gate

/**
 * Opens the announcement automatically on the first launch after a fresh
 * install or an upgrade, and on later launches of the same version until the
 * user closes it with "دیگر این پیام را نمایش نده" ticked. See [AnnouncementStore].
 *
 * Placed by MainActivity next to the home screen, i.e. only once onboarding is
 * finished, so a fresh install never stacks the dialog on top of the pager.
 */
@Composable
fun WhatsNewGate() {
    val context = LocalContext.current
    val store = remember { AnnouncementStore(context) }
    // The core-creator support note follows the same rule as the news: it opens on
    // every launch until the user ticks "دیگر نمایش نده" (remembered permanently).
    var visible by rememberSaveable { mutableStateOf(store.shouldShowOnLaunch()) }
    var supportVisible by rememberSaveable { mutableStateOf(false) }
    // The "never show again" choice is read from disk every time, never from saved UI
    // state: a language change / activity recreation must not revive a silenced note.
    var launchHandled by rememberSaveable { mutableStateOf(false) }
    // Record the launch (decision above is already taken for this one).
    LaunchedEffect(Unit) {
        if (launchHandled) return@LaunchedEffect
        launchHandled = true
        store.markLaunched()
        if (!visible && !store.supportSuppressed) supportVisible = true
    }
    if (visible) {
        WhatsNewDialog(
            announcement = Announcements.forVersion(BuildConfig.VERSION_NAME),
            showDontShowAgain = true,
            onDismiss = { dontShowAgain ->
                store.onDismissed(dontShowAgain)
                visible = false
                if (!store.supportSuppressed) supportVisible = true
            },
        )
    }
    if (supportVisible && !store.supportSuppressed) {
        CreatorSupportDialog(
            showDontShowAgain = true,
            onDismiss = { dontShowAgain ->
                store.markSupportPromptShown()
                if (dontShowAgain) store.suppressSupportPrompt()
                supportVisible = false
            },
        )
    }
}

// ------------------------------------------------------------------ dialog

/**
 * The "What's new" dialog.
 *
 * @param showDontShowAgain false when opened from the archive in settings: the
 *   checkbox only makes sense for the automatic launch-time dialog.
 * @param onDismiss receives the checkbox state; called for "متوجه شدم", the close
 *   icon and the system back gesture alike.
 */
@Composable
fun WhatsNewDialog(
    announcement: Announcement,
    showDontShowAgain: Boolean,
    onDismiss: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    val close = { onDismiss(dontShowAgain) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // A stray tap next to a long text should not throw the guide away.
            dismissOnClickOutside = false,
        ),
    ) {
        // The whole dialog is Persian, so it is RTL regardless of the app language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = NewsColors.Background,
                border = BorderStroke(1.dp, NewsColors.Border),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxHeight),
            ) {
                Column {
                    DialogHeader(version = announcement.version, onClose = close)
                    HorizontalDivider(color = NewsColors.Divider)

                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .navyScrollbar(scroll)
                            .verticalScroll(scroll)
                            .padding(start = 20.dp, end = 22.dp, top = 16.dp, bottom = 16.dp),
                    ) {
                        AnnouncementBody(announcement)
                    }

                    HorizontalDivider(color = NewsColors.Divider)
                    DialogFooter(
                        showDontShowAgain = showDontShowAgain,
                        dontShowAgain = dontShowAgain,
                        onDontShowAgainChange = { dontShowAgain = it },
                        onConfirm = close,
                    )
                }
            }
        }
    }
}


/** Persian-only copy: the dialog is RTL regardless of the app language. */
private const val SUPPORT_TITLE = "حمایت از سازندهٔ هستهٔ اتر"
private const val SUPPORT_IMAGE_DESC = "تصویر پروفایل تلگرام سازندهٔ هستهٔ اتر"
private const val SUPPORT_BODY = "برای حمایت از سازندهٔ هستهٔ اتر، لطفاً به کانال تلگرام زیر مراجعه کنید:"
private val SUPPORT_WARNING = "لطفاً برای این موضوع هیچ پیامی به ${ltr("QW-AI-Code")} ارسال نکنید. " +
    "${ltr("QW-AI-Code")} فقط توسعه‌دهندهٔ رابط کاربری (${ltr("GUI")}) اتر است و سازندهٔ هستهٔ اتر نیست. " +
    "برای حمایت و قدردانی از سازندهٔ هسته، مستقیماً به آدرس تلگرام زیر مراجعه کنید."

/**
 * The core-creator support note. Also opened from the announcements archive
 * (showDontShowAgain = false there).
 */
@Composable
fun CreatorSupportDialog(showDontShowAgain: Boolean, onDismiss: (dontShowAgain: Boolean) -> Unit) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    val closeIt = { onDismiss(dontShowAgain) }
    val uriHandler = LocalUriHandler.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    val supportUrl = "https://t.me/CluvexStudio/140"
    Dialog(
        onDismissRequest = closeIt,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = NewsColors.Background,
                border = BorderStroke(1.dp, NewsColors.Border),
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 560.dp).heightIn(max = maxHeight),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = SUPPORT_TITLE,
                                style = rtlStyle(17.sp, NewsColors.Text, lineHeight = 26.sp),
                            )
                        }
                        IconButton(onClick = closeIt, colors = IconButtonDefaults.iconButtonColors()) {
                            Icon(Icons.Rounded.Close, contentDescription = "بستن", tint = NewsColors.TextSecondary)
                        }
                    }
                    HorizontalDivider(color = NewsColors.Divider)
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier.weight(1f, fill = false).navyScrollbar(scroll)
                            .verticalScroll(scroll)
                            .padding(start = 20.dp, end = 22.dp, top = 18.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Surface(
                                shape = CircleShape,
                                color = NewsColors.Border,
                                border = BorderStroke(2.dp, NewsColors.Accent.copy(alpha = 0.65f)),
                                modifier = Modifier.size(104.dp),
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(R.drawable.aether_creator),
                                    contentDescription = SUPPORT_IMAGE_DESC,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(CircleShape),
                                )
                            }
                        }
                        Text(
                            text = SUPPORT_BODY,
                            style = bodyStyle(),
                        )
                        Text(
                            text = SUPPORT_WARNING,
                            style = bodyStyle().copy(color = NewsColors.Text),
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = NewsColors.Border.copy(alpha = 0.92f),
                            border = BorderStroke(1.dp, NewsColors.Accent.copy(alpha = 0.24f)),
                            modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri(supportUrl) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_telegram),
                                    contentDescription = null,
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = supportUrl,
                                    style = rtlStyle(13.5.sp, NewsColors.Text, lineHeight = 24.sp).copy(textDirection = TextDirection.Ltr),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = NewsColors.Divider)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (showDontShowAgain) {
                            SupportDontShowRow(dontShowAgain) { dontShowAgain = it }
                            Spacer(Modifier.height(6.dp))
                        }
                        Button(
                            onClick = closeIt,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NewsColors.Accent, contentColor = NewsColors.OnAccent),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text(
                                text = "متوجه شدم",
                                style = TextStyle(fontFamily = Vazirmatn, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportDontShowRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 2.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = NewsColors.Accent,
                uncheckedColor = NewsColors.CheckboxIdle,
                checkmarkColor = NewsColors.OnAccent,
            ),
            modifier = Modifier.padding(12.dp),
        )
        Text(
            text = "دیگر نمایش نده",
            style = rtlStyle(14.sp, NewsColors.TextSecondary, lineHeight = 22.sp),
        )
    }
}

@Composable
private fun DialogHeader(version: String, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "📢 تازه‌های نسخهٔ ${ltr(version)}",
                style = rtlStyle(17.sp, NewsColors.Text, lineHeight = 26.sp),
            )
            Text(
                text = "پیام‌ها و اطلاعیه‌های اتر",
                style = rtlStyle(12.5.sp, NewsColors.TextSecondary, lineHeight = 20.sp),
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "بستن",
                tint = NewsColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun DialogFooter(
    showDontShowAgain: Boolean,
    dontShowAgain: Boolean,
    onDontShowAgainChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (showDontShowAgain) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    // The whole row is the touch target, not just the 20dp box.
                    .toggleable(
                        value = dontShowAgain,
                        role = Role.Checkbox,
                        onValueChange = onDontShowAgainChange,
                    )
                    .padding(vertical = 2.dp),
            ) {
                Checkbox(
                    checked = dontShowAgain,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = NewsColors.Accent,
                        uncheckedColor = NewsColors.CheckboxIdle,
                        checkmarkColor = NewsColors.OnAccent,
                    ),
                    modifier = Modifier.padding(12.dp),
                )
                Text(
                    text = "دیگر این پیام را نمایش نده",
                    style = rtlStyle(14.sp, NewsColors.TextSecondary, lineHeight = 22.sp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Button(
            onClick = onConfirm,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NewsColors.Accent,
                contentColor = NewsColors.OnAccent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = "متوجه شدم",
                style = TextStyle(fontFamily = Vazirmatn, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

// ------------------------------------------------------------------ body

/** Renders an announcement's blocks. Also reused by the settings archive. */
@Composable
fun AnnouncementBody(announcement: Announcement) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        announcement.blocks.forEachIndexed { index, block ->
            when (block) {
                is AnnouncementBlock.Heading -> {
                    if (!block.large && index > 0) Spacer(Modifier.height(10.dp))
                    Text(
                        text = rich(block.text),
                        style = rtlStyle(
                            size = if (block.large) 19.sp else 16.5.sp,
                            color = NewsColors.Accent,
                            lineHeight = if (block.large) 32.sp else 28.sp,
                        ),
                        modifier = if (block.large) Modifier.padding(bottom = 2.dp) else Modifier,
                    )
                }

                is AnnouncementBlock.Paragraph -> Text(
                    text = rich(block.text),
                    style = if (block.note) {
                        rtlStyle(13.sp, NewsColors.Note, lineHeight = 23.sp)
                    } else {
                        bodyStyle()
                    },
                )

                is AnnouncementBlock.Bullets -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    block.items.forEach { item ->
                        ListRow(marker = {
                            Box(
                                modifier = Modifier
                                    .padding(top = 11.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(NewsColors.AccentAlt),
                            )
                        }, text = item)
                    }
                }

                is AnnouncementBlock.Numbered -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    block.items.forEachIndexed { i, item ->
                        ListRow(marker = {
                            Text(
                                text = "${persianDigits(i + 1)}.",
                                style = rtlStyle(14.5.sp, NewsColors.Accent, lineHeight = 26.sp),
                                modifier = Modifier.width(18.dp),
                            )
                        }, text = item)
                    }
                }

                is AnnouncementBlock.Warning -> Row(
                    modifier = Modifier
                        .padding(vertical = 5.dp)
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(6.dp))
                        .background(NewsColors.WarningBg),
                ) {
                    // First child of an RTL row = the RIGHT edge (border-right).
                    Box(
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(NewsColors.WarningBar),
                    )
                    Text(
                        text = rich(block.text),
                        style = bodyStyle(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ListRow(marker: @Composable () -> Unit, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        marker()
        Text(text = rich(text), style = bodyStyle(), modifier = Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------ text

private fun rtlStyle(size: TextUnit, color: Color, lineHeight: TextUnit) = TextStyle(
    fontFamily = Vazirmatn,
    fontSize = size,
    lineHeight = lineHeight,
    color = color,
    // Paragraph direction is pinned rather than guessed from the first strong
    // character, so a line that happens to open with a Latin word stays RTL.
    textDirection = TextDirection.Rtl,
    textAlign = TextAlign.Start,
)

/** line-height: 1.8, as in the spec. */
private fun bodyStyle() = rtlStyle(14.5.sp, NewsColors.TextSecondary, lineHeight = 26.sp)

private val StrongStyle = SpanStyle(color = NewsColors.Text, fontWeight = FontWeight.Bold)
private val EmphasisStyle = SpanStyle(fontStyle = FontStyle.Italic, color = NewsColors.TextSecondary)

/** Turns the small markup described in [Announcements] into styled text. */
internal fun rich(source: String): AnnotatedString = buildAnnotatedString {
    var strong = false
    var emphasis = false
    var i = 0
    while (i < source.length) {
        val c = source[i]
        when {
            c == '*' && source.startsWith("**", i) -> {
                if (strong) pop() else pushStyle(StrongStyle)
                strong = !strong
                i += 2
                continue
            }
            c == '*' -> {
                if (emphasis) pop() else pushStyle(EmphasisStyle)
                emphasis = !emphasis
            }
            c == '{' -> append(LRI)
            c == '}' -> append(PDI)
            else -> append(c)
        }
        i++
    }
}

private fun persianDigits(n: Int): String =
    n.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

// ------------------------------------------------------------------ scrollbar

/**
 * A slim scrollbar in the navy theme. Compose has none built in; this draws a
 * faint track and an accent thumb over the viewport. Apply it BEFORE
 * verticalScroll so it is drawn in viewport space and does not scroll away.
 * The thumb sits on the END edge (left in RTL, as browsers do for RTL pages)
 * and brightens while the user is scrolling.
 */
@Composable
private fun Modifier.navyScrollbar(
    state: ScrollState,
    width: Dp = 3.dp,
    inset: Dp = 6.dp,
    minThumb: Dp = 28.dp,
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) 1f else 0.55f,
        animationSpec = tween(durationMillis = if (state.isScrollInProgress) 120 else 600),
        label = "scrollbarAlpha",
    )
    return drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val viewport = size.height
        val w = width.toPx()
        val radius = CornerRadius(w / 2f)
        val x = if (layoutDirection == LayoutDirection.Rtl) inset.toPx() else size.width - inset.toPx() - w
        val trackTop = 8.dp.toPx()
        val trackHeight = viewport - trackTop * 2
        if (trackHeight <= 0f) return@drawWithContent
        val thumbHeight = (trackHeight * viewport / (viewport + max))
            .coerceIn(minThumb.toPx().coerceAtMost(trackHeight), trackHeight)
        val thumbTop = trackTop + (trackHeight - thumbHeight) * (state.value.toFloat() / max)

        drawRoundRect(
            color = NewsColors.ScrollTrack,
            topLeft = Offset(x, trackTop),
            size = Size(w, trackHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = NewsColors.ScrollThumb.copy(alpha = alpha),
            topLeft = Offset(x, thumbTop),
            size = Size(w, thumbHeight),
            cornerRadius = radius,
        )
    }
}
