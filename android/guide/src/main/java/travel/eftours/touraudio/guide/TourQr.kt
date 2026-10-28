package travel.eftours.touraudio.guide

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object TourQr {
    fun bitmap(contents: String, size: Int = 720): Bitmap {
        val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size)
        val image = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return image
    }
}
