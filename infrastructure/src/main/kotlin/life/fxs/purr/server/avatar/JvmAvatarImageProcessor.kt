package life.fxs.purr.server.avatar

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.MemoryCacheImageOutputStream
import life.fxs.purr.server.application.port.AvatarImageProcessor
import life.fxs.purr.server.application.port.AvatarImageRejectedException
import life.fxs.purr.server.application.port.AvatarImageProcessingUnavailableException
import life.fxs.purr.server.application.port.ProcessedAvatar
import life.fxs.purr.server.config.AvatarConfig

/** Decodes untrusted input and emits a metadata-free, fixed-size JPEG. */
class JvmAvatarImageProcessor(
    private val config: AvatarConfig,
) : AvatarImageProcessor {
    private val processingPermits = Semaphore(config.maxConcurrentProcessing, true)

    override fun process(contentType: String, bytes: ByteArray): ProcessedAvatar {
        val acquired = try {
            processingPermits.tryAcquire(PROCESSING_QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AvatarImageProcessingUnavailableException("Avatar processing was interrupted", error)
        }
        if (!acquired) {
            throw AvatarImageProcessingUnavailableException("Avatar processing capacity is temporarily exhausted")
        }
        try {
            return processWithPermit(contentType, bytes)
        } finally {
            processingPermits.release()
        }
    }

    private fun processWithPermit(contentType: String, bytes: ByteArray): ProcessedAvatar {
        val declaredFormat = when (contentType.lowercase()) {
            JPEG_CONTENT_TYPE -> JPEG_FORMAT
            PNG_CONTENT_TYPE -> PNG_FORMAT
            else -> throw AvatarImageRejectedException("Avatar must be a JPEG or PNG image")
        }
        try {
            return ByteArrayInputStream(bytes).use { source ->
                ImageIO.createImageInputStream(source)?.use { imageInput ->
                    val reader = ImageIO.getImageReaders(imageInput).asSequence().firstOrNull()
                        ?: throw AvatarImageRejectedException("Avatar is not a decodable image")
                    reader.use {
                        it.input = imageInput
                        validateFormat(declaredFormat, it)
                        val orientation = readExifOrientation(bytes)
                        val width = it.getWidth(0)
                        val height = it.getHeight(0)
                        validateDimensions(width, height)
                        val decoded = it.read(0)
                            ?: throw AvatarImageRejectedException("Avatar is not a decodable image")
                        val oriented = applyExifOrientation(decoded, orientation)
                        try {
                            encodeSquareJpeg(oriented)
                        } finally {
                            if (oriented !== decoded) oriented.flush()
                            decoded.flush()
                        }
                    }
                } ?: throw AvatarImageRejectedException("Avatar is not a decodable image")
            }
        } catch (error: AvatarImageRejectedException) {
            throw error
        } catch (error: IOException) {
            throw AvatarImageRejectedException("Avatar image is malformed", error)
        } catch (error: RuntimeException) {
            throw AvatarImageRejectedException("Avatar image could not be decoded safely", error)
        }
    }

    private fun readExifOrientation(bytes: ByteArray): Int = try {
        ByteArrayInputStream(bytes).use { input ->
            ImageMetadataReader.readMetadata(input)
                .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
                ?.takeIf { it in EXIF_ORIENTATION_NORMAL..EXIF_ORIENTATION_ROTATE_270 }
                ?: EXIF_ORIENTATION_NORMAL
        }
    } catch (_: Exception) {
        EXIF_ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(source: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == EXIF_ORIENTATION_NORMAL) return source
        val width = source.width.toDouble()
        val height = source.height.toDouble()
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, width, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, width, height)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, height, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, height, width)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width)
            else -> return source
        }
        val swapsDimensions = orientation >= EXIF_ORIENTATION_TRANSPOSE
        val output = BufferedImage(
            if (swapsDimensions) source.height else source.width,
            if (swapsDimensions) source.width else source.height,
            if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB,
        )
        output.createGraphics().use { graphics ->
            graphics.drawRenderedImage(source, transform)
        }
        return output
    }

    private fun validateFormat(declaredFormat: String, reader: ImageReader) {
        val detected = reader.formatName.lowercase()
        val matches = when (declaredFormat) {
            JPEG_FORMAT -> detected == JPEG_FORMAT || detected == JPG_FORMAT
            PNG_FORMAT -> detected == PNG_FORMAT
            else -> false
        }
        if (!matches) {
            throw AvatarImageRejectedException("Avatar content type does not match its encoded format")
        }
    }

    private fun validateDimensions(width: Int, height: Int) {
        val pixels = width.toLong() * height.toLong()
        if (
            width <= 0 ||
            height <= 0 ||
            width > config.maxSourceDimensionPixels ||
            height > config.maxSourceDimensionPixels ||
            pixels > config.maxSourcePixels
        ) {
            throw AvatarImageRejectedException("Avatar dimensions exceed the processing limit")
        }
    }

    private fun encodeSquareJpeg(source: BufferedImage): ProcessedAvatar {
        val side = minOf(source.width, source.height)
        val sourceX = (source.width - side) / 2
        val sourceY = (source.height - side) / 2
        val output = BufferedImage(config.outputSizePixels, config.outputSizePixels, BufferedImage.TYPE_INT_RGB)
        try {
            output.createGraphics().use { graphics ->
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, output.width, output.height)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.drawImage(
                    source,
                    0,
                    0,
                    output.width,
                    output.height,
                    sourceX,
                    sourceY,
                    sourceX + side,
                    sourceY + side,
                    null,
                )
            }
            val encoded = encodeJpeg(output)
            if (encoded.size > config.maxOutputBytes) {
                throw AvatarImageRejectedException("Processed avatar exceeds the output size limit")
            }
            return ProcessedAvatar(
                contentType = JPEG_CONTENT_TYPE,
                bytes = encoded,
                width = output.width,
                height = output.height,
            )
        } finally {
            output.flush()
        }
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName(JPEG_FORMAT).asSequence().firstOrNull()
            ?: error("JVM JPEG writer is unavailable")
        val output = ByteArrayOutputStream(minOf(config.maxOutputBytes, DEFAULT_BUFFER_BYTES))
        writer.use {
            MemoryCacheImageOutputStream(output).use { imageOutput ->
                it.output = imageOutput
                val parameters = it.defaultWriteParam.apply {
                    compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = config.jpegQualityPercent / 100f
                }
                it.write(null, IIOImage(image, null, null), parameters)
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val JPEG_CONTENT_TYPE = "image/jpeg"
        const val PNG_CONTENT_TYPE = "image/png"
        const val JPEG_FORMAT = "jpeg"
        const val JPG_FORMAT = "jpg"
        const val PNG_FORMAT = "png"
        const val DEFAULT_BUFFER_BYTES = 128 * 1024
        const val PROCESSING_QUEUE_TIMEOUT_SECONDS = 5L
        const val EXIF_ORIENTATION_NORMAL = 1
        const val EXIF_ORIENTATION_TRANSPOSE = 5
        const val EXIF_ORIENTATION_ROTATE_270 = 8
    }
}

private inline fun <T : ImageReader, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    dispose()
}

private inline fun <T : javax.imageio.ImageWriter, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    dispose()
}

private inline fun <R> java.awt.Graphics2D.use(block: (java.awt.Graphics2D) -> R): R = try {
    block(this)
} finally {
    dispose()
}
