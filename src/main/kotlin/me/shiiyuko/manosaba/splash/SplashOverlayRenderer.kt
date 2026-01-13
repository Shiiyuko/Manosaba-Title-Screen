package me.shiiyuko.manosaba.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.systems.RenderSystem
import me.shiiyuko.manosaba.utils.GlStateUtils
import me.shiiyuko.manosaba.utils.UnitySpriteParser
import net.minecraft.client.MinecraftClient
import org.jetbrains.skia.*
import org.lwjgl.opengl.GL33C
import kotlin.math.max

@OptIn(InternalComposeUiApi::class)
object SplashOverlayRenderer {

    private const val DESIGN_WIDTH = 1920f
    private const val DESIGN_HEIGHT = 1080f

    private val mc = MinecraftClient.getInstance()
    private var skiaContext: DirectContext? = null
    private var surface: Surface? = null
    private var renderTarget: BackendRenderTarget? = null
    private var composeScene: ComposeScene? = null

    private var brandLogo: ImageBitmap? = null
    private var companyLogo: ImageBitmap? = null
    private var resourcesLoaded = false

    private var progress = mutableStateOf(0f)
    private var logoAlpha = mutableStateOf(1f)

    private val window get() = mc.window

    private fun loadResources() {
        if (resourcesLoaded) return

        runCatching {
            val atlasStream = javaClass.getResourceAsStream("/assets/SplashScreen.png") ?: return
            val jsonStream = javaClass.getResourceAsStream("/assets/SplashScreen.json") ?: return

            val atlasBytes = atlasStream.use { it.readBytes() }
            val jsonString = jsonStream.use { it.bufferedReader().readText() }

            val atlasData = UnitySpriteParser.parseAtlas(jsonString)
            val atlasImage = Image.makeFromEncoded(atlasBytes)

            atlasData.sprites["BrandLogo_Acacia"]?.let { spriteData ->
                brandLogo = UnitySpriteParser.cropSprite(atlasImage, spriteData).toComposeImageBitmap()
            }

            atlasData.sprites["CompanyLogo_ReAER"]?.let { spriteData ->
                companyLogo = UnitySpriteParser.cropSprite(atlasImage, spriteData).toComposeImageBitmap()
            }

            resourcesLoaded = true
        }.onFailure { it.printStackTrace() }
    }

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
        ).apply { setContent { SplashContent() } }).also {
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

    private fun resetPixelStore() {
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SWAP_BYTES, GL33C.GL_FALSE)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_LSB_FIRST, GL33C.GL_FALSE)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, 0)
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 4)
    }

    @Composable
    private fun SplashContent() {
        val currentLogoAlpha by logoAlpha

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.graphicsLayer { alpha = currentLogoAlpha },
                horizontalArrangement = Arrangement.spacedBy(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                brandLogo?.let { logo ->
                    Image(
                        bitmap = logo,
                        contentDescription = "Brand Logo"
                    )
                }

                companyLogo?.let { logo ->
                    Image(
                        bitmap = logo,
                        contentDescription = "Company Logo",
                        modifier = Modifier.offset(y = 32.dp)
                    )
                }
            }
        }
    }

    @JvmStatic
    fun render(loadProgress: Float, alpha: Float): Boolean {
        loadResources()

        progress.value = loadProgress
        logoAlpha.value = alpha

        if (composeScene == null) {
            initCompose()
        }

        buildSkiaSurface()

        val fbWidth = window.framebufferWidth.toFloat()
        val fbHeight = window.framebufferHeight.toFloat()
        val scaleX = fbWidth / DESIGN_WIDTH
        val scaleY = fbHeight / DESIGN_HEIGHT
        val renderScale = max(scaleX, scaleY)
        val offsetX = (fbWidth - DESIGN_WIDTH * renderScale) / 2f
        val offsetY = (fbHeight - DESIGN_HEIGHT * renderScale) / 2f

        GlStateUtils.save()
        resetPixelStore()
        skiaContext?.resetAll()

        RenderSystem.enableBlend()
        surface?.let { s ->
            val canvas = s.canvas
            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(renderScale, renderScale)
            composeScene?.render(canvas.asComposeCanvas(), System.nanoTime())
            canvas.restore()
            s.flush()
        }
        GlStateUtils.restore()
        RenderSystem.disableBlend()

        return true
    }

    @JvmStatic
    fun cleanup() {
        closeSkiaResources()
        composeScene?.close()
        composeScene = null
        resourcesLoaded = false
        progress.value = 0f
        logoAlpha.value = 1f
    }
}
