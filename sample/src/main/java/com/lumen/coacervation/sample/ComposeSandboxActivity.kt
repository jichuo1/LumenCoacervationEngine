package com.lumen.coacervation.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.coacervation.engine.compose.LumenComposeHost
import com.lumen.coacervation.engine.compose.LumenComposeState
import com.lumen.coacervation.engine.compose.lumenElastic
import com.lumen.coacervation.engine.compose.lumenSurface
import com.lumen.coacervation.engine.compose.rememberLumenComposeState
import com.lumen.coacervation.engine.host.LumenSurfaceEnhancements
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.model.LumenPalette

/** One Composition, one Window anchor, and independent native surface nodes. */
class ComposeSandboxActivity : AppCompatActivity() {
    internal lateinit var composeView: ComposeView
    internal lateinit var lumenState: LumenComposeState
    internal val surfaceBounds = mutableMapOf<String, Rect>()
    internal val surfaceCoordinates = mutableMapOf<String, LayoutCoordinates>()
    internal var showSurfaces by mutableStateOf(true)
    internal var showSelectionIndicator by mutableStateOf(true)
    internal var decorateBackdrop by mutableStateOf(false)
    internal var selectedIndex by mutableIntStateOf(0)
    internal var cardOffsetDp by mutableFloatStateOf(0f)
    internal var rowGeneration by mutableIntStateOf(0)
    internal var requestedRowIndex by mutableIntStateOf(0)
    internal var entryText by mutableStateOf("原生 Compose 输入框")
    internal var clickCount by mutableIntStateOf(0)
    internal var surfaceOptions by mutableStateOf(LumenSurfaceOptions(
        radiusDp = 18f, tintEnabled = false, edgeEnabled = false,
        sampling = LumenSurfaceSampling(blurEnabled = false, refractionEnabled = false,
            minIntervalMs = 0, softwareScale = 1f, maxSoftwarePixels = 96_000)))
    internal var enhancements by mutableStateOf(LumenSurfaceEnhancements.DEFAULT)
    internal val stateReady get() = ::lumenState.isInitialized

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Scene() }
        }
        setContentView(composeView)
    }

    @Composable
    private fun Scene() {
        val state = rememberLumenComposeState(LumenPalette.neutral(false))
        SideEffect { lumenState = state }
        LumenComposeHost(state, Modifier.fillMaxSize(), backdrop = {
            Canvas(Modifier.fillMaxSize().then(if (decorateBackdrop)
                Modifier.lumenSurface(state, key = "backdrop-surface") else Modifier)) {
                val stripe = 24.dp.toPx()
                var x = 0f
                var index = 0
                while (x < size.width) {
                    drawRect(if (index % 2 == 0) RED else BLUE,
                        topLeft = Offset(x, 0f), size = Size(stripe, size.height))
                    x += stripe
                    index++
                }
                drawRect(GREEN, topLeft = Offset(size.width * .08f, size.height * .72f),
                    size = Size(size.width * .34f, size.height * .08f))
                drawCircle(Color(0xFFF1CA58), radius = size.width * .13f,
                    center = Offset(size.width * .84f, size.height * .86f))
            }
        }) {
            if (showSurfaces) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    BasicText("Compose 原生材质", style = TEXT_STYLE)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(78.dp).offset(x = cardOffsetDp.dp)
                        .lumenElastic()
                        .tracked(CARD).lumenSurface(state, surfaceOptions, enhancements, CARD)
                        .clickable { clickCount++ }.padding(16.dp)) {
                        BasicText("独立节点 · 点击 $clickCount", style = TEXT_STYLE)
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(64.dp).tracked(INPUT)
                        .lumenSurface(state, surfaceOptions, enhancements, INPUT).padding(16.dp)) {
                        BasicTextField(entryText, { entryText = it },
                            modifier = Modifier.fillMaxWidth(), textStyle = TEXT_STYLE)
                    }
                    Spacer(Modifier.height(12.dp))
                    Selection(state)
                    Spacer(Modifier.height(12.dp))
                    Rows(state)
                }
            }
        }
    }

    @Composable
    private fun Selection(state: LumenComposeState) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp).tracked(SELECTOR)) {
            val optionWidth = maxWidth / 3
            if (showSelectionIndicator) {
                Box(Modifier.offset(x = optionWidth * selectedIndex).size(optionWidth, 56.dp)
                    .tracked(SELECTION).lumenSurface(state,
                        surfaceOptions.copy(color = android.graphics.Color.WHITE,
                            tintEnabled = true, tintOpacity = .35f), enhancements, SELECTION))
            }
            Row(Modifier.fillMaxSize()) {
                listOf("推荐", "动态", "关注").forEachIndexed { index, label ->
                    Box(Modifier.width(optionWidth).height(56.dp)
                        .clickable { selectedIndex = index }, contentAlignment = Alignment.Center) {
                        BasicText(label, style = TEXT_STYLE)
                    }
                }
            }
        }
    }

    @Composable
    private fun Rows(state: LumenComposeState) {
        val scroll = rememberLazyListState()
        LaunchedEffect(requestedRowIndex, rowGeneration) { scroll.scrollToItem(requestedRowIndex) }
        LazyColumn(Modifier.fillMaxWidth(), state = scroll) {
            items((0 until 60).toList(), key = { "row-$rowGeneration-$it" }) { index ->
                val key = "row-$rowGeneration-$index"
                Box(Modifier.fillMaxWidth().height(48.dp).lumenElastic().tracked(key)
                    .lumenSurface(state, surfaceOptions, enhancements, key)
                    .clickable { clickCount++ }.padding(12.dp)) {
                    BasicText("稳定 key 条目 $rowGeneration / $index", style = TEXT_STYLE)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun Modifier.tracked(key: String): Modifier {
        DisposableEffect(key) { onDispose { surfaceBounds.remove(key); surfaceCoordinates.remove(key) } }
        return onGloballyPositioned { surfaceBounds[key] = it.boundsInWindow(); surfaceCoordinates[key] = it }
    }

    internal companion object {
        const val CARD = "native-card"
        const val INPUT = "native-input"
        const val SELECTOR = "native-selector-row"
        const val SELECTION = "native-selection"
        val RED = Color(0xFFCC3355)
        val BLUE = Color(0xFF246CC6)
        val GREEN = Color(0xFF36AD71)
        private val TEXT_STYLE = TextStyle(color = Color(0xFF172338), fontSize = 16.sp)
    }
}
