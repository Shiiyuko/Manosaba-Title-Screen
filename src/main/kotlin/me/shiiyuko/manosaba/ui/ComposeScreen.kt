package me.shiiyuko.manosaba.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.mojang.blaze3d.systems.RenderSystem
import me.shiiyuko.manosaba.utils.AWTUtils
import me.shiiyuko.manosaba.utils.GlStateUtils
import me.shiiyuko.manosaba.utils.glfwToAwtKeyCode
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL33C
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.math.max

@OptIn(InternalComposeUiApi::class)
abstract class ComposeScreen(title: Text) : Screen(title) {

    companion object {
        const val DESIGN_WIDTH = 1920f
        const val DESIGN_HEIGHT = 1080f
    }

    private val mc = MinecraftClient.getInstance()
    private var skiaContext: DirectContext? = null
    private var surface: Surface? = null
    private var renderTarget: BackendRenderTarget? = null
    private var composeScene: ComposeScene? = null

    private val window get() = mc.window
    private val currentTime get() = System.currentTimeMillis()
    private val awtMods get() = AWTUtils.getAwtMods(window.handle)

    private var renderScale = 1f
    private var renderOffsetX = 0f
    private var renderOffsetY = 0f

    @Composable
    abstract fun Content()

    private fun closeSkiaResources() {
        listOf(surface, renderTarget, skiaContext).forEach { it?.close() }
        skiaContext = null
        renderTarget = null
        surface = null
    }

    private fun initCompose() {
        composeScene = (composeScene ?: CanvasLayersComposeScene(
            density = Density(1f),
            invalidate = {}
        ).apply { setContent { Content() } }).also {
            it.density = Density(1f)
            it.size = IntSize(DESIGN_WIDTH.toInt(), DESIGN_HEIGHT.toInt())
        }
    }

    private fun buildSkiaSurface() {
        val (frameWidth, frameHeight) = window.framebufferWidth to window.framebufferHeight

        surface?.takeIf { it.width == frameWidth && it.height == frameHeight }?.let { return }

        closeSkiaResources()

        skiaContext = DirectContext.makeGL()
        renderTarget = BackendRenderTarget.makeGL(
            frameWidth, frameHeight, 0, 8,
            mc.framebuffer.fbo, FramebufferFormat.GR_GL_RGBA8
        )
        surface = Surface.makeFromBackendRenderTarget(
            skiaContext!!, renderTarget!!, SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.BGRA_8888, ColorSpace.sRGB
        )
    }

    private fun calculateScaleParams() {
        val fbWidth = window.framebufferWidth.toFloat()
        val fbHeight = window.framebufferHeight.toFloat()

        val scaleX = fbWidth / DESIGN_WIDTH
        val scaleY = fbHeight / DESIGN_HEIGHT

        renderScale = max(scaleX, scaleY)
        renderOffsetX = (fbWidth - DESIGN_WIDTH * renderScale) / 2f
        renderOffsetY = (fbHeight - DESIGN_HEIGHT * renderScale) / 2f
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        if (composeScene == null) {
            initCompose()
        }

        buildSkiaSurface()
        calculateScaleParams()

        GlStateUtils.save()
        resetPixelStore()
        skiaContext?.resetAll()

        RenderSystem.enableBlend()
        surface?.let { s ->
            val canvas = s.canvas
            canvas.save()
            canvas.translate(renderOffsetX, renderOffsetY)
            canvas.scale(renderScale, renderScale)
            composeScene?.render(canvas.asComposeCanvas(), System.nanoTime())
            canvas.restore()
            s.flush()
        }
        GlStateUtils.restore()
        RenderSystem.disableBlend()
    }

    private fun resetPixelStore() {
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SWAP_BYTES, GL33C.GL_FALSE)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_LSB_FIRST, GL33C.GL_FALSE)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 4)
    }

    private fun toDesignCoord(windowX: Double, windowY: Double): Offset {
        val scaleFactor = window.scaleFactor
        val fbX = windowX * scaleFactor
        val fbY = windowY * scaleFactor

        val designX = ((fbX - renderOffsetX) / renderScale).toFloat()
        val designY = ((fbY - renderOffsetY) / renderScale).toFloat()

        return Offset(designX, designY)
    }

    private fun sendMouseEvent(
        mouseX: Double,
        mouseY: Double,
        button: Int = 0,
        eventType: Int,
        pointerEventType: PointerEventType,
        scrollDelta: Offset? = null
    ) {
        val designCoord = toDesignCoord(mouseX, mouseY)
        val event = AWTUtils.createMouseEvent(
            designCoord.x.toInt(), designCoord.y.toInt(), awtMods, button, eventType
        )
        composeScene?.sendPointerEvent(
            eventType = pointerEventType,
            position = designCoord,
            type = PointerType.Mouse,
            scrollDelta = scrollDelta ?: Offset.Zero,
            nativeEvent = event
        )
    }

    private fun sendKeyEvent(eventId: Int, keyCode: Int, char: Char, location: Int) {
        composeScene?.sendKeyEvent(
            AWTUtils.createKeyEvent(eventId, currentTime, awtMods, keyCode, char, location)
        )
    }

    override fun resize(client: MinecraftClient?, width: Int, height: Int) {
        surface?.close()
        renderTarget?.close()
        surface = null
        renderTarget = null
        super.resize(client, width, height)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        sendMouseEvent(mouseX, mouseY, eventType = MouseEvent.MOUSE_MOVED, pointerEventType = PointerEventType.Move)
        super.mouseMoved(mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        sendMouseEvent(mouseX, mouseY, button, MouseEvent.MOUSE_PRESSED, PointerEventType.Press)
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        sendMouseEvent(mouseX, mouseY, button, MouseEvent.MOUSE_DRAGGED, PointerEventType.Move)
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        sendMouseEvent(mouseX, mouseY, button, MouseEvent.MOUSE_RELEASED, PointerEventType.Release)
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        val designCoord = toDesignCoord(mouseX, mouseY)
        val event = AWTUtils.createMouseWheelEvent(
            designCoord.x.toInt(), designCoord.y.toInt(), mouseY, awtMods, MouseEvent.MOUSE_WHEEL
        )
        composeScene?.sendPointerEvent(
            position = designCoord,
            eventType = PointerEventType.Scroll,
            scrollDelta = Offset(horizontalAmount.toFloat(), -verticalAmount.toFloat()),
            nativeEvent = event
        )
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        sendKeyEvent(
            KeyEvent.KEY_TYPED,
            Key.Unknown.keyCode.toInt(),
            chr,
            KeyEvent.KEY_LOCATION_UNKNOWN
        )
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return close().let { true }
        sendKeyEvent(
            KeyEvent.KEY_PRESSED,
            glfwToAwtKeyCode(keyCode),
            KeyEvent.CHAR_UNDEFINED,
            KeyEvent.KEY_LOCATION_STANDARD
        )
        return true
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        sendKeyEvent(
            KeyEvent.KEY_RELEASED,
            glfwToAwtKeyCode(keyCode),
            0.toChar(),
            KeyEvent.KEY_LOCATION_STANDARD
        )
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun shouldCloseOnEsc() = false

    override fun close() {
        closeSkiaResources()
        composeScene?.close()
        super.close()
    }
}
