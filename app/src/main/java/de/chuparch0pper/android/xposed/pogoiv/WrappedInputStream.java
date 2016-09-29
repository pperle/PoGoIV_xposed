package de.chuparch0pper.android.xposed.pogoiv;

import android.support.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Wrapper for InputStream to get byte array
 */

public class WrappedInputStream extends InputStream {

    private static final int averageSize = 4096;
    private ByteBuffer byteBuffer;
    private IMitmInputStreamHandler handler;
    private boolean isProcessed = false;

    public WrappedInputStream(InputStream inputStream, IMitmInputStreamHandler handler) {
        if (inputStream == null) {
            return;
        }

        this.handler = handler;
        ByteArrayOutputStream os = new ByteArrayOutputStream(averageSize);

        byte[] bytes = new byte[averageSize];
        int bytesRead;

        try {
            while ((bytesRead = inputStream.read(bytes, 0, bytes.length)) != -1) {
                os.write(bytes, 0, bytesRead);
            }
            os.flush();

            inputStream.close();
        } catch (Exception e) {
            Helper.Log("Wrapped input stream error");
            os.reset();
        }

        byte[] allBytes = os.toByteArray();
        byteBuffer = ByteBuffer.wrap(allBytes);
    }

    @Override
    public int available() throws IOException {
        process();

        if (!byteBuffer.hasRemaining()) {
            return 0;
        }
        return byteBuffer.remaining();
    }

    public void close() throws IOException {
        //process();
        super.close();
    }

    @Override
    public int read() throws IOException {
        process();

        if (!byteBuffer.hasRemaining()) {
            return -1;
        }
        return byteBuffer.get() & 0xFF;
    }

    @Override
    public int read(@NonNull byte[] bytes, int off, int len) throws IOException {
        process();

        if (!byteBuffer.hasRemaining()) {
            return -1;
        }

        len = Math.min(len, byteBuffer.remaining());
        byteBuffer.get(bytes, off, len);
        return len;
    }

    private void process() {
        if (isProcessed)
            return;

        synchronized (WrappedInputStream.class) {
            if (handler != null) {
                    Helper.Log("Start process");
                    ByteBuffer buf = byteBuffer.asReadOnlyBuffer();
                    byte[] arr = new byte[buf.remaining()];
                    buf.get(arr);
                    handler.processBytes(arr);
            } else {
                Helper.Log("ERROR - No PoGo MITM handler code ready to receive data");
            }
        }

        isProcessed = true;
    }
}

interface IMitmInputStreamHandler {
    void processBytes(byte[] bytes);
}
