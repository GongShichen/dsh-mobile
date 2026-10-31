package io.dsh.mobile.transport

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class HLQRDecoderDeviceTest {
    @Test fun bundledScannerReadsDensePairingCodesAtRetinaDisplayResolution() {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val expected = checkNotNull(javaClass.classLoader?.getResourceAsStream("HLDenseQRPayload.txt")).bufferedReader().use { it.readText() }
        try {
            for (size in listOf(464, 928)) {
                val bitmap = checkNotNull(javaClass.classLoader?.getResourceAsStream("dense-qr-$size.png")).use { BitmapFactory.decodeStream(it) }
                try {
                    val codes = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
                    android.util.Log.i("HarnessLink", "scanner.fixture width=${bitmap.width} count=${codes.size} lengths=${codes.map { it.rawValue?.length }}")
                    assertTrue(codes.any { it.rawValue == expected }, "The complete dense QR must be decoded at $size pixels")
                } finally { bitmap.recycle() }
            }
        } finally { scanner.close() }
    }
}
