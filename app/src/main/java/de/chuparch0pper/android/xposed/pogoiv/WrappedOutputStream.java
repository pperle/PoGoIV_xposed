package de.chuparch0pper.android.xposed.pogoiv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Wrapper for OutputStream to get byte array
 */

public class WrappedOutputStream extends ByteArrayOutputStream {
    private IMitmOutputStreamHandler handler;
    private OutputStream outputStream;

    WrappedOutputStream(OutputStream outputStream, IMitmOutputStreamHandler handler) {
        super(2048);
        this.handler = handler;
        this.outputStream = outputStream;
    }

    @Override
    public void close() throws IOException {
        process();
        write();

        if (outputStream != null) {
            outputStream.close();
        }

        super.close();
    }

    @Override
    public void flush() throws IOException {
        process();
        write();

        if (outputStream != null) {
            outputStream.flush();
        }
        super.flush();
    }

    private void process() {
        ByteBuffer dRequestBody = ByteBuffer.wrap(buf, 0, count).asReadOnlyBuffer();
        dRequestBody.rewind();

        byte[] bytes = new byte[dRequestBody.remaining()];
        try {
            dRequestBody.get(bytes);
        }
        catch (Throwable e) {
            Helper.Log("Unable to get request body to process");
        }
        if (handler != null) {
            handler.processBytes(bytes);
        }
    }

    private void write() throws IOException {
        if (outputStream != null) {
            writeTo(outputStream);
        }
        reset();
    }
}

interface IMitmOutputStreamHandler {
    void processBytes(byte[] bytes);
}