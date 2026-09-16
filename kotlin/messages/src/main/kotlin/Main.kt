import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

object KotlinCodec : MessageCodec.Codec {
    override fun encode(frame: MessageCodec.Frame): ByteArray {
        val text = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(frame.text()))
        require(frame.id() != 0L && frame.kind() in 1..2 && text.remaining() <= 4096 && (frame.kind() != 2 || !text.hasRemaining())) { "invalid message" }
        return ByteBuffer.allocate(16 + text.remaining()).putInt(0x55524d53).put(1.toByte()).put(frame.kind().toByte())
            .putShort(text.remaining().toShort()).putLong(frame.id()).put(text).array()
    }
    override fun decode(bytes: ByteArray): MessageCodec.Frame {
        require(bytes.size in 16..4112) { "invalid frame size" }
        val b = ByteBuffer.wrap(bytes)
        require(b.int == 0x55524d53 && b.get().toInt() == 1) { "invalid header" }
        val kind = b.get().toInt() and 255
        val length = b.short.toInt() and 65535
        val id = b.long
        require(id != 0L && kind in 1..2 && length == bytes.size - 16 && (kind != 2 || length == 0)) { "invalid payload" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(b).toString()
        return MessageCodec.Frame(kind, id, text)
    }
}

fun main(args: Array<String>) = UrMessages.run(args, KotlinCodec)
