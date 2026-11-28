package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.ui.screens.InvoiceEditor
import com.superbiz.app.ui.screens.RolePermissionsScreen
import com.superbiz.app.ui.theme.SuperBizTheme
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.InvoicesVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * يلتقط لقطات شاشة حقيقية للواجهة داخل CI (Robolectric native graphics) وتُرفع كأرتيفاكت.
 * لا يفشل الاختبار أبداً: الفشل يُكتب في capture-log.txt فقط، فلا يعطل دمج أي PR.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "ar-rSA-w411dp-h891dp-xxhdpi")
class ScreenshotCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: Application
    private val testMain = UnconfinedTestDispatcher()
    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun resetGraph() {
        val inst = Class.forName("com.superbiz.app.AppGraph").getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    @Before
    fun setUp() {
        resetGraph()
        app = ApplicationProvider.getApplicationContext()
        val g = AppGraph.from(app)
        Dispatchers.setMain(testMain)
        runBlocking { g.settings.snapshot() }
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        resetGraph()
        Dispatchers.resetMain()
    }

    private fun log(msg: String): Unit = File(outDir, "capture-log.txt").appendText(msg + "\n")

    private fun shot(name: String) {
        runCatching {
            compose.mainClock.advanceTimeBy(1500)
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(outDir, "$name.png").outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            log("ok $name ${bmp.width}x${bmp.height}")
        }.onFailure { log("FAIL $name: $it") }
    }

    @Test
    fun roleTemplates() {
        runCatching {
            compose.mainClock.autoAdvance = false
            compose.setContent { SuperBizTheme("light") { RolePermissionsScreen(onBack = {}) } }
            shot("role-permissions")
        }.onFailure { log("FAIL roleTemplates: $it") }
    }

    @Test
    fun invoiceEditor() {
        runCatching {
            val vm = InvoicesVM(app)
            vm.editorItems.value = listOf(InvoicesVM.EditorItem(null, "صنف تجريبي", "2", "150"))
            compose.mainClock.autoAdvance = false
            compose.setContent { SuperBizTheme("light") { InvoiceEditor(vm, AppVM(app)) } }
            shot("invoice-editor")
        }.onFailure { log("FAIL invoiceEditor: $it") }
    }
}
