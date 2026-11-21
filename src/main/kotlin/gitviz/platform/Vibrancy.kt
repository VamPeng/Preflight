package gitviz.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.AwtWindow
import androidx.compose.ui.window.FrameWindowScope
import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * macOS 的透明窗口，保留系统标题栏，内容铺到标题栏下面，只留红绿灯。
 * Compose 的 Window() 不允许有边框的窗口透明，这里自己建窗口：
 * 那个检查只看设置透明时的状态，所以创建时临时标成无边框。
 */
@Composable
fun GlassWindow(
    title: String,
    size: DpSize,
    onClose: () -> Unit,
    content: @Composable FrameWindowScope.(glass: Boolean) -> Unit,
) {
    AwtWindow(
        create = {
            ComposeWindow().apply {
                defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
                this.title = title
                setSize(size.width.value.toInt(), size.height.value.toInt())
                isLocationByPlatform = true
                isUndecorated = true
                isTransparent = true
                // 不能调 setUndecorated(false)：AWT 不许有边框的 Frame 用半透明背景，而窗口层必须保持透明，
                // 否则会是一层黑底。这里直接改字段，需要 build.gradle.kts 里的 --add-opens
                java.awt.Frame::class.java.getDeclaredField("undecorated").apply { isAccessible = true }
                    .setBoolean(this, false)
                with(rootPane) {
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                    putClientProperty("Window.shadow", true) // 透明窗口默认没有阴影
                }
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) = onClose()
                })
                setContent {
                    var glass by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        while (!window.isShowing) delay(16)
                        glass = Vibrancy.install(window)
                    }
                    content(glass)
                }
            }
        },
        dispose = ComposeWindow::dispose,
    )
}

/**
 * macOS 毛玻璃：在窗口内容下面插一个 NSVisualEffectView。
 * AppKit 只能在主线程操作，而 AWT 的事件线程不是主线程，所以要派发到主队列。
 */
object Vibrancy {
    private const val MATERIAL_SIDEBAR = 7L
    private const val BLENDING_BEHIND_WINDOW = 0L
    private const val STATE_ACTIVE = 1L
    private const val RESIZE_WIDTH_AND_HEIGHT = 18L
    private const val ORDER_BELOW = -1L

    private val process by lazy { NativeLibrary.getProcess() }
    private val msgSend by lazy { process.getFunction("objc_msgSend") }

    private val pendingTasks = java.util.Collections.synchronizedSet(HashSet<MainThreadTask>()) // 派发期间要留着引用，否则回调可能被 GC
    @Volatile private var nsWindow: Pointer? = null

    private fun interface MainThreadTask : Callback {
        fun invoke(context: Pointer?)
    }

    @Structure.FieldOrder("x", "y", "width", "height")
    class NSRect(
        @JvmField var x: Double = 0.0,
        @JvmField var y: Double = 0.0,
        @JvmField var width: Double = 0.0,
        @JvmField var height: Double = 0.0,
    ) : Structure(), Structure.ByValue

    /** 返回是否成功；失败时调用方应该退回不透明的样式。 */
    suspend fun install(window: ComposeWindow): Boolean {
        val done = CompletableDeferred<Boolean>()
        if (!onMainThread { done.complete(runCatching { insertEffectView(window) }.getOrDefault(false)) }) done.complete(false)
        return done.await()
    }

    /** 透明窗口的 AWT 焦点事件可能缺失，直接读取 AppKit 的应用激活状态。 */
    suspend fun isApplicationActive(): Boolean {
        val done = CompletableDeferred<Boolean>()
        if (!onMainThread {
            done.complete(runCatching {
                val app = objcClass("NSApplication").send("sharedApplication")
                msgSend.invokeInt(arrayOf(app, selector("isActive"))) and 0xff != 0
            }.getOrDefault(false))
        }) done.complete(false)
        return done.await()
    }

    /**
     * 双击标题栏：按系统设置“连按窗口标题栏以…”缩放或最小化，默认缩放（铺满／还原）。
     * 内容铺满了标题栏，系统收不到这次双击，只能由 Compose 转过来。
     */
    fun titleBarDoubleClick() {
        val window = nsWindow ?: return
        onMainThread {
            runCatching {
                val action = objcClass("NSUserDefaults").send("standardUserDefaults")
                    ?.send("stringForKey:", nsString("AppleActionOnDoubleClick"))
                    ?.send("UTF8String")?.getString(0, "UTF-8")
                when (action) {
                    "None" -> Unit
                    "Minimize" -> window.call("performMiniaturize:", null)
                    else -> window.call("zoom:", null) // 未设置或“缩放”“填充”：铺满可用区域，再次双击还原
                }
            }
        }
    }

    private fun onMainThread(block: () -> Unit): Boolean {
        lateinit var task: MainThreadTask
        task = MainThreadTask { try { block() } finally { pendingTasks.remove(task) } }
        pendingTasks.add(task)
        return runCatching {
            val mainQueue = process.getGlobalVariableAddress("_dispatch_main_q")
            process.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, task))
        }.onFailure { pendingTasks.remove(task) }.isSuccess
    }

    private fun insertEffectView(window: ComposeWindow): Boolean {
        val nsWindow = findNSWindow(window.title) ?: return false
        this.nsWindow = nsWindow
        // 标题栏已由 Compose TitleBarArea 移动窗口；AppKit 再处理同一拖曳会争抢位置。
        // 只关闭系统鼠标拖曳，程序设置位置和窗口缩放仍可用。必须在 AppKit 主线程设置。
        nsWindow.call("setMovable:", 0.toByte())
        val content = nsWindow.send("contentView") ?: return false
        val frame = content.send("superview") ?: return false
        val bounds = NSRect(width = window.width.toDouble(), height = window.height.toDouble())
        val effect = objcClass("NSVisualEffectView").send("alloc")?.send("initWithFrame:", bounds) ?: return false
        effect.call("setMaterial:", MATERIAL_SIDEBAR)
        effect.call("setBlendingMode:", BLENDING_BEHIND_WINDOW)
        effect.call("setState:", STATE_ACTIVE)
        effect.call("setAutoresizingMask:", RESIZE_WIDTH_AND_HEIGHT)
        frame.call("addSubview:positioned:relativeTo:", effect, ORDER_BELOW, content)
        return true
    }

    private fun findNSWindow(title: String): Pointer? {
        val windows = objcClass("NSApplication").send("sharedApplication")?.send("windows") ?: return null
        val count = msgSend.invokeLong(arrayOf(windows, selector("count")))
        return (0 until count).mapNotNull { windows.send("objectAtIndex:", it) }
            .firstOrNull { it.send("title")?.send("UTF8String")?.getString(0, "UTF-8") == title }
    }

    private fun nsString(value: String) = objcClass("NSString").send("stringWithUTF8String:", value)

    private fun objcClass(name: String) = process.getFunction("objc_getClass").invokePointer(arrayOf(name))
    private fun selector(name: String) = process.getFunction("sel_registerName").invokePointer(arrayOf(name))

    private fun Pointer.send(name: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(this, selector(name), *args))

    private fun Pointer.call(name: String, vararg args: Any?) =
        msgSend.invokeVoid(arrayOf(this, selector(name), *args))
}
