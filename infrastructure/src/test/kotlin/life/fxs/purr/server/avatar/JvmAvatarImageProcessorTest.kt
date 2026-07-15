package life.fxs.purr.server.avatar

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import life.fxs.purr.server.application.port.AvatarImageRejectedException
import life.fxs.purr.server.config.AvatarConfig

class JvmAvatarImageProcessorTest {
    @Test
    fun `decodes crops and emits fixed metadata-free jpeg`() {
        val input = imageBytes(width = 800, height = 600, format = "png")

        val result = JvmAvatarImageProcessor(config()).process("image/png", input)

        assertEquals("image/jpeg", result.contentType)
        assertEquals(512, result.width)
        assertEquals(512, result.height)
        assertTrue(result.bytes.size <= config().maxOutputBytes)
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))
        assertEquals(512, decoded.width)
        assertEquals(512, decoded.height)
        val outputMetadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(result.bytes))
        assertTrue(outputMetadata.getDirectoriesOfType(ExifIFD0Directory::class.java).none())
    }

    @Test
    fun `applies exif orientation before center cropping`() {
        val input = orientedJpegBytes(orientation = 6)

        val result = JvmAvatarImageProcessor(config().copy(outputSizePixels = 120))
            .process("image/jpeg", input)
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))

        assertColorNear(Color.MAGENTA, Color(decoded.getRGB(15, 25)), tolerance = 55)
        assertColorNear(Color.BLUE, Color(decoded.getRGB(60, 25)), tolerance = 55)
        assertColorNear(Color.RED, Color(decoded.getRGB(105, 25)), tolerance = 55)
        assertColorNear(Color.CYAN, Color(decoded.getRGB(15, 95)), tolerance = 55)
        assertColorNear(Color.YELLOW, Color(decoded.getRGB(60, 95)), tolerance = 55)
        assertColorNear(Color.GREEN, Color(decoded.getRGB(105, 95)), tolerance = 55)
    }

    @Test
    fun `rejects truncated image with a valid png signature`() {
        val signatureOnly = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        assertFailsWith<AvatarImageRejectedException> {
            JvmAvatarImageProcessor(config()).process("image/png", signatureOnly)
        }
    }

    @Test
    fun `rejects declared type mismatch and excessive dimensions`() {
        val png = imageBytes(width = 64, height = 64, format = "png")
        val tooWide = imageBytes(width = 8_193, height = 1, format = "png")

        assertFailsWith<AvatarImageRejectedException> {
            JvmAvatarImageProcessor(config()).process("image/jpeg", png)
        }
        assertFailsWith<AvatarImageRejectedException> {
            JvmAvatarImageProcessor(config()).process("image/png", tooWide)
        }
    }

    private fun imageBytes(width: Int, height: Int, format: String): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        try {
            val graphics = image.createGraphics()
            try {
                graphics.color = Color(20, 80, 160, 180)
                graphics.fillRect(0, 0, width, height)
            } finally {
                graphics.dispose()
            }
            return ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, format, output))
                output.toByteArray()
            }
        } finally {
            image.flush()
        }
    }

    private fun orientedJpegBytes(orientation: Int): ByteArray {
        val image = BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB)
        val jpeg = try {
            image.createGraphics().let { graphics ->
                try {
                    val colors = listOf(
                        Color.RED,
                        Color.GREEN,
                        Color.BLUE,
                        Color.YELLOW,
                        Color.MAGENTA,
                        Color.CYAN,
                    )
                    colors.forEachIndexed { index, color ->
                        graphics.color = color
                        graphics.fillRect((index % 2) * 100, (index / 2) * 100, 100, 100)
                    }
                } finally {
                    graphics.dispose()
                }
            }
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "jpeg", output))
                output.toByteArray()
            }
        } finally {
            image.flush()
        }
        val exifPayload = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
            orientation.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        return ByteArrayOutputStream(jpeg.size + exifPayload.size + 4).use { output ->
            output.write(jpeg, 0, 2)
            output.write(0xFF)
            output.write(0xE1)
            output.write(0x00)
            output.write(exifPayload.size + 2)
            output.write(exifPayload)
            output.write(jpeg, 2, jpeg.size - 2)
            output.toByteArray()
        }
    }

    private fun assertColorNear(expected: Color, actual: Color, tolerance: Int) {
        assertTrue(kotlin.math.abs(expected.red - actual.red) <= tolerance, "red: expected=$expected actual=$actual")
        assertTrue(kotlin.math.abs(expected.green - actual.green) <= tolerance, "green: expected=$expected actual=$actual")
        assertTrue(kotlin.math.abs(expected.blue - actual.blue) <= tolerance, "blue: expected=$expected actual=$actual")
    }

    private fun config() = AvatarConfig(
        bucket = "purr-avatars",
        endpoint = "http://localhost:9000",
        publicEndpoint = "http://localhost:9000",
        accessKey = "key",
        secretKey = "secret",
        region = "us-east-1",
        forcePathStyle = true,
        outputSizePixels = 512,
        maxSourceDimensionPixels = 8_192,
        maxSourcePixels = 40_000_000,
        jpegQualityPercent = 88,
        maxOutputBytes = 1_048_576,
        maxConcurrentProcessing = 2,
        cleanupEnabled = true,
        cleanupIntervalSeconds = 60,
        cleanupBatchSize = 100,
        cleanupMaxAttempts = 20,
        cleanupRetryBaseSeconds = 5,
        cleanupRetryMaxSeconds = 3_600,
        orphanGraceSeconds = 3_600,
    )
}
