package io.github.syldegil.helivoice;

import java.io.IOException;
import java.io.OutputStream;

final class WavEncoder {
    private WavEncoder() {}

    static int encodedLength(int pcmLength) {
        return 44 + pcmLength;
    }

    static void writePcm16Mono16k(OutputStream output, byte[] pcm, int pcmLength)
            throws IOException {
        output.write(new byte[] {'R', 'I', 'F', 'F'});
        writeInt(output, 36 + pcmLength);
        output.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        writeInt(output, 16);
        writeShort(output, 1);
        writeShort(output, 1);
        writeInt(output, 16000);
        writeInt(output, 32000);
        writeShort(output, 2);
        writeShort(output, 16);
        output.write(new byte[] {'d', 'a', 't', 'a'});
        writeInt(output, pcmLength);
        output.write(pcm, 0, pcmLength);
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeShort(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }
}
