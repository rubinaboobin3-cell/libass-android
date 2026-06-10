package io.github.peerless2012.ass.media.text

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.TrackOutput
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * This class is only used by the overlay renderer. It's needed to get the start time of the subtitles.
 */
@UnstableApi
class AssTrackOutput(
    private val delegate: TrackOutput,
    private val assHandler: AssHandler,
    private val extractor: AssMatroskaExtractor,
) : TrackOutput by delegate {

    private var isAss = false

    private var trackId: String? = null

    override fun format(format: Format) {
        if (format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA) {
            isAss = true
            trackId = format.id
        }
        delegate.format(format)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAss && timeUs.isValidTs) {
            val sample = extractor.subtitleSample
            val endIndex = findTokenIndex(sample.data, 1)
            val lineIndex = findTokenIndex(sample.data, 2)

            val rawDuration = sample.data.decodeToString(endIndex, lineIndex - 1)
            val durationUs = parseTimecodeUs(rawDuration)
            val dialogue = sample.data.dialoguePayload(
                offset = lineIndex,
                limit = sample.limit()
            )

            assHandler.readTrackDialogue(
                trackId = trackId,
                start = timeUs / 1000,
                duration = durationUs / 1000,
                data = dialogue,
                offset = 0,
                length = dialogue.size
            )
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun parseTimecodeUs(timeString: String): Long {
        val matcher = SSA_TIMECODE_PATTERN.matcher(timeString.trim { it <= ' ' })
        if (!matcher.matches()) {
            return C.TIME_UNSET
        }
        var timestampUs =
            Util.castNonNull(matcher.group(1)).toLong() * 60 * 60 * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(2)).toLong() * 60 * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(3)).toLong() * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(4)).toLong() * 10000
        return timestampUs
    }

    private fun findTokenIndex(array: ByteArray, tokenNumber: Int): Int {
        if (tokenNumber == 0) return 0
        var tokensFound = 0
        array.forEachIndexed { index, byte ->
            if (byte == COMMA && ++tokensFound == tokenNumber) {
                return index + 1
            }
        }
        return 0
    }

    private fun ByteArray.dialoguePayload(offset: Int, limit: Int): ByteArray {
        if (offset >= size) return EMPTY_BYTE_ARRAY
        val boundedLimit = limit.coerceIn(offset, size)
        val rawEnd = if (looksLikeZlib(offset, size)) size else boundedLimit
        val rawPayload = copyOfRange(offset, rawEnd)
        return maybeInflate(rawPayload)
    }

    private fun ByteArray.looksLikeZlib(offset: Int, limit: Int): Boolean {
        if (limit - offset < 2) return false
        val cmf = this[offset].toInt() and 0xFF
        val flg = this[offset + 1].toInt() and 0xFF
        return cmf and 0x0F == 8 && ((cmf shl 8) + flg) % 31 == 0
    }

    private fun maybeInflate(data: ByteArray): ByteArray {
        if (!data.looksLikeZlib(offset = 0, limit = data.size)) return data

        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(INFLATE_BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                } else {
                    break
                }
            }
            val inflated = output.toByteArray()
            if (inflater.finished() && inflated.isNotEmpty()) inflated else data
        } catch (_: DataFormatException) {
            data
        } finally {
            inflater.end()
        }
    }

    private val Long.isValidTs
        get() = this != C.TIME_UNSET

    private companion object {
        val SSA_TIMECODE_PATTERN: Pattern =
            Pattern.compile("""(?:(\d+):)?(\d+):(\d+)[:.](\d+)""")

        const val COMMA = ','.code.toByte()
        const val INFLATE_BUFFER_SIZE = 4096
        val EMPTY_BYTE_ARRAY = ByteArray(0)
    }
}
