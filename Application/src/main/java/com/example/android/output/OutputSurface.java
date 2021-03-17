package com.example.android.output;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

public class OutputSurface {

    private Handler mImageReaderHandler;
    private int mFlags;
    private ImageWriter mImageWriter;

    private static final int JPEG_MAX_BUFFER        = 20;
    private static final int NON_JPEG_MAX_BUFFER    = 15;


    private class ImageReaderWrapper {
        private RefCountedAutoCloseable<ImageReader> mImageReader;
        private final TreeMap<Integer, ImageSaver.ImageSaverBuilder> mResultQueue = new TreeMap<>();

        private final ImageReader.OnImageAvailableListener mImageReaderListener =
                reader -> {
                        android.util.Log.d("gwas", "OnImageAvailable");
                        dequeueAndSaveImageLocked();
                };
        private int mImageFormat;

        private ImageReaderWrapper (int imageFormat, Size surfaceSize) {
            mImageFormat = imageFormat;
            android.util.Log.d("gwas", "ImageReaderWrapper, width:" + surfaceSize.getWidth() + ", height:" + surfaceSize.getHeight());

            int maxBuffer;
            if (mImageFormat == ImageFormat.JPEG) {
                maxBuffer = JPEG_MAX_BUFFER;
            } else {
                maxBuffer = NON_JPEG_MAX_BUFFER;
            }

            mImageReader = new RefCountedAutoCloseable<>(ImageReader.newInstance(surfaceSize.getWidth(),
                    surfaceSize.getHeight(), mImageFormat, maxBuffer));
            mImageReader.get().setOnImageAvailableListener(mImageReaderListener, mImageReaderHandler);
        }

        private Surface getSurface() {
            return mImageReader.get().getSurface();
        }

        private ImageSaver.ImageSaverBuilder dequeueRequest(int requestId) {
            android.util.Log.d("gwas", "dequeueRequest " + requestId + " " + mResultQueue.size());
            return mResultQueue.get(requestId);
        }

        private void queueRequest(int requestId, ImageSaver.ImageSaverBuilder builder) {
            mResultQueue.put(requestId, builder);
            android.util.Log.d("gwas", "queueRequest " + requestId + " " + mResultQueue.size());
        }

        private void removeRequest(int id) {
            mResultQueue.remove(id);
        }

        private void close() {
            mImageReader.close();
        }


        private void dequeueAndSaveImageLocked() {

            Iterator<Map.Entry<Integer, ImageSaver.ImageSaverBuilder>>
                    iterator = mResultQueue.entrySet().iterator();
            Map.Entry<Integer, ImageSaver.ImageSaverBuilder> entry = null;
            while (iterator.hasNext()) {
                entry = iterator.next();
                if ((entry != null) && (!entry.getValue().isImageReady())) {
                    break;
                }
            }

//            Map.Entry<Integer, ImageSaver.ImageSaverBuilder> entry =
//                    mResultQueue.firstEntry();
            if (entry == null) {
                android.util.Log.e("gwas", "ResultQueue first entry is null, return!");
                return;
            }
            android.util.Log.d("gwas", "dequeueAndSaveImageLocked requestId:" + entry.getKey());

            ImageSaver.ImageSaverBuilder builder = entry.getValue();

            // Increment reference count to prevent ImageReader from being closed while we
            // are saving its Images in a background thread (otherwise their resources may
            // be freed while we are writing to a file).
            if (mImageReader == null || mImageReader.getAndRetain() == null) {
                android.util.Log.e("gwas", "Paused the activity before we could save the image," +
                        " ImageReader already closed.");
                mResultQueue.remove(entry.getKey());
                return;
            }

            Image image;
            try {
                image = mImageReader.get().acquireNextImage();
            } catch (IllegalStateException e) {
                android.util.Log.e("gwas", "Too many images queued for saving, dropping image for request: " +
                        entry.getKey());
                mResultQueue.remove(entry.getKey());
                return;
            }
            if (image.getFormat() == ImageFormat.JPEG) {
                builder.setRefCountedReader(mImageReader).setBytes(getJpegBytes(image));
            } else {
                builder.setRefCountedReader(mImageReader).setImage(image);
            }
            handleCompletionInternalLocked(entry.getKey(), builder, this);
        }

    }

    private byte[] getJpegBytes(Image image) {
        byte[] bytes = null;
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
        } finally {
            image.close();
        }
        return bytes;
    }

    private int convert2ImageFormat(int sensorFormat) {
        int format = ImageFormat.JPEG;
/*        switch (sensorFormat) {
            case SURFACE_FORMAT_JPEG:
                format = ImageFormat.JPEG;
                break;

            case SURFACE_FORMAT_YUV:
                format = ImageFormat.YUV_420_888;
                break;

            case SURFACE_FORMAT_SENSOR_RAW:
                format = ImageFormat.RAW_SENSOR;
                break;
        }*/
        return format;
    }

    private HashMap<Integer, Size> mSurfaceSize;
    private HashMap<Integer, ImageReaderWrapper> mImageReaders = new HashMap<>();

    public OutputSurface(HashMap<Integer, Size> surfaceSize,
                         Handler imageReaderHandler, Object cameraLock) {
        mSurfaceSize = surfaceSize;
        mImageReaderHandler = imageReaderHandler;
        initialize();
    }

    public Surface getSurfaceLocked(int flag) {

        if (mImageReaders.containsKey(flag)) {
            return mImageReaders.get(flag).getSurface();
        }

        return null; //gwas
    }

    public void queueRequestLocked(int flag, int requestId, ImageSaver.ImageSaverBuilder builder) {
        if (mImageReaders.containsKey(flag)) {
            android.util.Log.d("gwas", "queueRequestLocked, flag" + flag + ", requestId:" + requestId);
            mImageReaders.get(flag).queueRequest(requestId, builder);
            return;
        }
    }

    public ImageSaver.ImageSaverBuilder dequeueRequestLocked(int flag, int requestId) {
        if (mImageReaders.containsKey(flag)) {
            android.util.Log.d("gwas", "dequeueRequestLocked, flag:" + flag + ", requestId:" + requestId);
            return mImageReaders.get(flag).dequeueRequest(requestId);
        }
        return null; //gwas
    }

    public void removewRequestLocked(int flag, int id) {
        if (mImageReaders.containsKey(flag)) {
            mImageReaders.get(flag).removeRequest(id);
            return;
        }
    }

    public void handleCompletionLocked(int flag, int requestId,
            ImageSaver.ImageSaverBuilder builder) {
        if (mImageReaders.containsKey(flag)) {
            handleCompletionInternalLocked(requestId, builder, mImageReaders.get(flag));
            return;
        }
    }

    public void initReprocessImageWriter(Surface surface) {
        android.util.Log.d("gwas", "initImageWriter");
        mImageWriter = ImageWriter.newInstance(surface, NON_JPEG_MAX_BUFFER);
    }

    public void queueReprocessImage(Image image) {
        mImageWriter.queueInputImage(image);
    }

    private void initialize() {
        for(Map.Entry<Integer, Size> entry : mSurfaceSize.entrySet()) {
            ImageReaderWrapper wrapper = new ImageReaderWrapper(
                    convert2ImageFormat(entry.getKey()), entry.getValue());
            mImageReaders.put(entry.getKey(), wrapper);
            mFlags |= entry.getKey();
        }
    }

    private void handleCompletionInternalLocked(int requestId, ImageSaver.ImageSaverBuilder builder,
            ImageReaderWrapper wrapper) {
        if (builder == null) return;
        ImageSaver saver = builder.buildIfComplete();
        if (saver != null) {
            wrapper.removeRequest(requestId);
            android.util.Log.d("gwas", "execute saver");
            AsyncTask.THREAD_POOL_EXECUTOR.execute(saver);
        }
    }

    public void release() {
        for(Map.Entry<Integer, ImageReaderWrapper> entry : mImageReaders.entrySet()) {
            entry.getValue().close();
        }
    }

}
