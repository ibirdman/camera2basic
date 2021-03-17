package com.example.android.output;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.provider.MediaStore;

import com.example.android.output.IImageReadyListener;

import java.io.File;


public class ImageSaver implements Runnable {
    private final ImageDataWrapper mImageData;
    /**
     * The file we save the image into.
     */
    private final File mFile;

    /**
     * The CaptureResult for this image smtCapture.
     */
    private final CaptureResult mCaptureResult;

    /**
     * The CameraCharacteristics for this camera device.
     */
    private final CameraCharacteristics mCharacteristics;

    /**
     * The Context to use when updating MediaStore with the saved images.
     */
    private final Context mContext;

    /**
     * A reference counted wrapper for the ImageReader that owns the given image.
     */
    private final RefCountedAutoCloseable<ImageReader> mReader;

    private IImageReadyListener mImageReadyListener;
    private ImageWriter mImageWriter;

    public class ImageDataWrapper {
        private int mFormat;
        /**
         * The image to save.
         */
        private final Image mImage;
        /**
         * bytes of JPEG
         */
        private final byte[] mBytes;

        public ImageDataWrapper(Image image, byte[] bytes) {
            mImage = image;
            mBytes = bytes;
            if (image != null) {
                mFormat = image.getFormat();
            } else {
                mFormat = ImageFormat.JPEG;
            }
        }

        public Image getImage() {
            return mImage;
        }

        public byte[] getBytes() {
            return mBytes;
        }

        public int getFormat() {
            return mFormat;
        }
    }

    private ImageSaver(Image image, byte[] bytes,
            File file,
            CaptureResult result,
            CameraCharacteristics characteristics,
            Context context,
            RefCountedAutoCloseable<ImageReader> reader,
            IImageReadyListener imageReadyListener) {
        mImageData = new ImageDataWrapper(image, bytes);
        mFile = file;
        mCaptureResult = result;
        mCharacteristics = characteristics;
        mContext = context;
        mReader = reader;
        mImageReadyListener = imageReadyListener;
    }

    @Override
    public void run() {
        mImageReadyListener.onImageReady(mImageData, mFile, mCaptureResult);
    }

    /**
     * Builder class for constructing {@link ImageSaver}s.
     * <p/>
     * This class is thread safe.
     */
    public static class ImageSaverBuilder {
        private byte[] mBytes;
        private Image mImage;
        private File mFile;
        private CaptureResult mCaptureResult;
        private CameraCharacteristics mCharacteristics;
        private Context mContext;
        private RefCountedAutoCloseable<ImageReader> mReader;
        private IImageReadyListener mImageReadyListener;
        private int mFlag;
//        private ImageWriter mImageWriter;

        /**
         * Construct a new ImageSaverBuilder using the given {@link Context}.
         *
         * @param context a {@link Context} to for accessing the
         *                {@link MediaStore}.
         */
        public ImageSaverBuilder(final Context context, int flag) {
            mContext = context;
            mFlag = flag;

//            if (cameraSessionInputSurface != null) {
//                mImageWriter = ImageWriter.newInstance(
//                        cameraSessionInputSurface, MAX_REQUIRED_IMAGE_NUM);
//            }
        }

        public synchronized ImageSaverBuilder setRefCountedReader(
                RefCountedAutoCloseable<ImageReader> reader) {
            if (reader == null) throw new NullPointerException();

            mReader = reader;
            return this;
        }

        public synchronized ImageSaverBuilder setImage(final Image image) {
            if (image == null) throw new NullPointerException();
            mImage = image;
            return this;
        }

        public synchronized ImageSaverBuilder setBytes(final byte[] bytes) {
            if (bytes == null) throw new NullPointerException();
            mBytes = bytes;
            return this;
        }

        public synchronized ImageSaverBuilder setFile(final File file) {
            if (file == null) throw new NullPointerException();
            mFile = file;
            return this;
        }

        public synchronized ImageSaverBuilder setResult(final CaptureResult result) {
            if (result == null) throw new NullPointerException();
            mCaptureResult = result;
            return this;
        }

        public synchronized ImageSaverBuilder setCharacteristics(
                final CameraCharacteristics characteristics) {
            if (characteristics == null) throw new NullPointerException();
            mCharacteristics = characteristics;
            return this;
        }

        public synchronized ImageSaverBuilder setImageReadyListener(
                final IImageReadyListener listener) {
            if (listener == null) throw new NullPointerException();
            mImageReadyListener = listener;
            return this;
        }

        public synchronized ImageSaver buildIfComplete() {
            if (!isComplete()) {
                return null;
            }

            return new ImageSaver(mImage, mBytes, mFile, mCaptureResult,
                    mCharacteristics, mContext, mReader,
                    mImageReadyListener);
        }

        public boolean isImageReady() {
            return mImage != null || mBytes != null;
        }

//        public synchronized String getSaveLocation() {
//            return (mFile == null) ? "Unknown" : mFile.toString();
//        }

        private boolean isComplete() {
            return isImageReady() && mCaptureResult != null && mFile != null
                    && mCharacteristics != null && mImageReadyListener != null;
        }
    }
}


