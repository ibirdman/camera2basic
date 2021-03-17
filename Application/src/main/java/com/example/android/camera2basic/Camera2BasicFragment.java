/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2basic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2BasicFragment extends Fragment
        implements View.OnClickListener, ActivityCompat.OnRequestPermissionsResultCallback {

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 2;
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2BasicFragment";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    //thumbnail for taking photo
    private ImageButton mGalleryButton = null;
    private Bitmap mLastThumbnail = null;
    private boolean mThumbnailAnimNeeded = false;
    private long mThumbnailAnimStartTime = 0;
    private RectF thumbnail_anim_src_rect = new RectF();
    private RectF thumbnail_anim_dst_rect = new RectF();
    private Matrix thumbnail_anim_matrix = new Matrix();
    private final Paint mPaint = new Paint();

    private StorageUtils mStorageUtils = null;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened. We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;

    /**
     * This is the output file for our picture.
     */
    private File mFile;

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "onImageAvailable");
            Image image = reader.acquireNextImage();
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            mBackgroundHandler.post(new ImageSaver(Camera2BasicFragment.this, bytes, mFile));
            image.close();
        }

    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
            int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                    option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public static Camera2BasicFragment newInstance() {
        return new Camera2BasicFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        //view.findViewById(R.id.info).setOnClickListener(this);
        view.findViewById(R.id.video).setOnClickListener(this);

        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);

        mGalleryButton = (ImageButton) view.findViewById(R.id.gallery);
        mGalleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGallery();
            }
        });

        mPaint.setAntiAlias(true);
        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        float scale = getContext().getResources().getDisplayMetrics().density;
        float stroke_width = (1.0f * scale + 0.5f); // convert dps to pixels
        mPaint.setStrokeWidth(stroke_width);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mFile = new File(getActivity().getExternalFilesDir(null), "pic.jpg");
        mStorageUtils = new StorageUtils(getActivity());
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }

        updateGalleryIcon();
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new ConfirmationDialog().setRequestCode(REQUEST_CAMERA_PERMISSION)
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private void requestStoragePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            new ConfirmationDialog().setRequestCode(REQUEST_STORAGE_PERMISSION)
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_camera_permission))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_storage_permission))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());
                largest = new Size(4000, 2000);
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                mPreviewSize = new Size(1920, 1080);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    /**
     * Opens the camera specified by {@link Camera2BasicFragment#mCameraId}.
     */
    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestStoragePermission();
            return;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(mPreviewRequestBuilder);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        rotation = 1;
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    /**
     * Initiate a still image capture.
     */
    private void takePicture() {
        lockFocus();
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(captureBuilder);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            CameraCaptureSession.CaptureCallback captureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    showToast("Saved: " + mFile);
                    Log.d(TAG, "onCaptureCompleted: " + mFile.toString());
                    unlockFocus();
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    Log.e(TAG, "onCaptureFailed: reason: " + failure.getReason());
                }
            };

            mCaptureSession.stopRepeating();
            //mCaptureSession.abortCaptures();
            mCaptureSession.capture(captureBuilder.build(), captureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                takePicture();
                break;
            }
            case R.id.video: {
                CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
                try {
                    String[] cameraIds = manager.getCameraIdList();
                    if (cameraIds.length > 0) {
                        String cameraId = cameraIds[0];
                        // Choose the sizes for camera preview and video recording
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                        StreamConfigurationMap map = characteristics
                                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                        Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);

                        Log.d(TAG, "lensFacing: " + lensFacing + "  sensorOrientation: " + sensorOrientation);

                        Rect rect = new Rect();
                        mTextureView.getWindowVisibleDisplayFrame(rect);

                        Display display = getActivity().getWindowManager().getDefaultDisplay();
                        int w = display.getWidth();
                        int h = display.getHeight();

                        Point size = new Point();
                        display.getSize(size);

                        Log.d(TAG, "size: " + size);
                    }
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }

/*                FragmentActivity activity = getActivity();
                if (null != activity) {
                    activity.getSupportFragmentManager().beginTransaction()
                            .replace(R.id.container, Camera2VideoFragment.newInstance())
                            .commit();
                }*/
                break;
            }
/*            case R.id.info: {
                Activity activity = getActivity();
                if (null != activity) {
                    new AlertDialog.Builder(activity)
                            .setMessage(R.string.intro_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                break;
            }*/
        }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    protected StorageUtils getStorageUtils() {
        return mStorageUtils;
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final byte[] mImageData;
        /**
         * The file we save the image into.
         */
        private final File mFile;

        private final Fragment mFragment;

        ImageSaver(Fragment fragment, byte[] data, File file) {
            mFragment = fragment;
            mImageData = data;
            mFile = file;
        }

        @Override
        public void run() {
            //ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            //byte[] bytes = new byte[buffer.remaining()];
            //buffer.get(bytes);
            FileOutputStream output = null;
            StorageUtils storageUtils = ((Camera2BasicFragment)mFragment).getStorageUtils();
            try {
                //output = new FileOutputStream(mFile);
                Date current_date = new Date();
                File picFile = storageUtils.createOutputMediaFile(StorageUtils.MEDIA_TYPE_IMAGE, "", "jpg", current_date);
                output = new FileOutputStream(picFile);
                output.write(mImageData);
                storageUtils.broadcastFile(picFile, true, false, true);
                postUpdateThumbnail();
                Log.d(TAG, "ImageSaver: picFile: " + picFile.toString());
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                //mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        void postUpdateThumbnail() {
            Bitmap thumbnail;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = false;
            options.inSampleSize = 2; //TBD gwas
            thumbnail = BitmapFactory.decodeByteArray(mImageData, 0, mImageData.length, options);
            if (true) {
                Log.d(TAG, "thumbnail width: " + thumbnail.getWidth());
                Log.d(TAG, "thumbnail height: " + thumbnail.getHeight());
            }
            // now get the rotation from the Exif data
            thumbnail = rotateForExif(thumbnail, mImageData, mFile);

            if( thumbnail == null ) {
                // received crashes on Google Play suggesting that thumbnail could not be created
                Log.e(TAG, "failed to create thumbnail bitmap");
            }
            else {
                final Bitmap thumbnail_f = thumbnail;
                mFragment.getActivity().runOnUiThread(new Runnable() {
                    public void run() {
                        ((Camera2BasicFragment)mFragment).updateGalleryIcon(thumbnail_f);
                        ((Camera2BasicFragment)mFragment).updateThumbnail(thumbnail_f, false, true);
                    }
                });
            }
        }

        /** Rotates the supplied bitmap according to the orientation tag stored in the exif data. On
         *  Android 7 onwards, we use the jpeg data; on earlier versions the supplied exifTimeFile is
         *  used. If no rotation is required, the input bitmap is returned.
         * @param data Jpeg data containing the Exif information to use.
         * @param exifTempFile Ignored on Android 7+. If this is null on older versions, the bitmap is
         *                     returned without rotation.
         */
        private Bitmap rotateForExif(Bitmap bitmap, byte [] data, File exifTempFile) {
            InputStream inputStream = null;
            try {
                ExifInterface exif;

                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ) {
                    inputStream = new ByteArrayInputStream(data);
                    exif = new ExifInterface(inputStream);
                }
                else {
                    if( exifTempFile != null ) {
                        exif = new ExifInterface(exifTempFile.getAbsolutePath());
                    }
                    else {
                        return bitmap;
                    }
                }

                int exif_orientation_s = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
                boolean needs_tf = false;
                int exif_orientation = 0;
                // see http://jpegclub.org/exif_orientation.html
                // and http://stackoverflow.com/questions/20478765/how-to-get-the-correct-orientation-of-the-image-selected-from-the-default-image
                switch (exif_orientation_s) {
                    case ExifInterface.ORIENTATION_UNDEFINED:
                    case ExifInterface.ORIENTATION_NORMAL:
                        // leave unchanged
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        needs_tf = true;
                        exif_orientation = 180;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        needs_tf = true;
                        exif_orientation = 90;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        needs_tf = true;
                        exif_orientation = 270;
                        break;
                    default:
                        break;
                }

                if( needs_tf ) {
                    Matrix m = new Matrix();
                    m.setRotate(exif_orientation, bitmap.getWidth() * 0.5f, bitmap.getHeight() * 0.5f);
                    Bitmap rotated_bitmap = Bitmap.createBitmap(bitmap, 0, 0,bitmap.getWidth(), bitmap.getHeight(), m, true);
                    if( rotated_bitmap != bitmap ) {
                        bitmap.recycle();
                        bitmap = rotated_bitmap;
                    }
                }
            }
            catch(IOException exception) {
                exception.printStackTrace();
            }
            catch(NoClassDefFoundError exception) {
                exception.printStackTrace();
            }
            finally {
                if( inputStream != null ) {
                    try {
                        inputStream.close();
                    }
                    catch(IOException e) {
                        e.printStackTrace();
                    }
                    inputStream = null;
                }
            }
            return bitmap;
        }
    }

    /** Shows a thumbnail for the gallery icon.
     */
    private void updateGalleryIcon(Bitmap thumbnail) {
        if (mGalleryButton != null) {
            mGalleryButton.setImageBitmap(thumbnail);
        }
        //gallery_bitmap = thumbnail;
    }

    /** Sets a current thumbnail for a photo or video just taken. Used for thumbnail animation,
     *  and when ghosting the last image.
     */
    protected void updateThumbnail(Bitmap thumbnail, boolean is_video, boolean want_thumbnail_animation) {
        if( want_thumbnail_animation) {
            mThumbnailAnimNeeded = true;
            mThumbnailAnimStartTime = System.currentTimeMillis();
        }
        Bitmap oldThumbnail = this.mLastThumbnail;
        this.mLastThumbnail = thumbnail;
        //this.last_thumbnail_is_video = is_video;
        //this.allow_ghost_last_image = true;
        if (oldThumbnail != null) {
            // only recycle after we've set the new thumbnail
            oldThumbnail.recycle();
        }
    }

    private void doThumbnailAnimation(Canvas canvas) {
        if (mThumbnailAnimNeeded && mLastThumbnail != null ) {
            int ui_rotation = 90; //TBD
            long time_ms = System.currentTimeMillis();
            long elapse = time_ms - mThumbnailAnimStartTime;
            final long duration = 500;
            if (elapse > duration) {
                mThumbnailAnimNeeded = false;
            } else {
                thumbnail_anim_src_rect.left = 0;
                thumbnail_anim_src_rect.top = 0;
                thumbnail_anim_src_rect.right = mLastThumbnail.getWidth();
                thumbnail_anim_src_rect.bottom = mLastThumbnail.getHeight();
                View galleryButton = mGalleryButton;
                float alpha = ((float)elapse)/(float)duration;

                int st_x = canvas.getWidth()/2;
                int st_y = canvas.getHeight()/2;
                int nd_x = galleryButton.getLeft() + galleryButton.getWidth()/2;
                int nd_y = galleryButton.getTop() + galleryButton.getHeight()/2;
                int thumbnail_x = (int)( (1.0f-alpha)*st_x + alpha*nd_x );
                int thumbnail_y = (int)( (1.0f-alpha)*st_y + alpha*nd_y );

                float st_w = canvas.getWidth();
                float st_h = canvas.getHeight();
                float nd_w = galleryButton.getWidth();
                float nd_h = galleryButton.getHeight();
                //int thumbnail_w = (int)( (1.0f-alpha)*st_w + alpha*nd_w );
                //int thumbnail_h = (int)( (1.0f-alpha)*st_h + alpha*nd_h );
                float correction_w = st_w/nd_w - 1.0f;
                float correction_h = st_h/nd_h - 1.0f;
                int thumbnail_w = (int)(st_w/(1.0f+alpha*correction_w));
                int thumbnail_h = (int)(st_h/(1.0f+alpha*correction_h));
                thumbnail_anim_dst_rect.left = thumbnail_x - thumbnail_w/2.0f;
                thumbnail_anim_dst_rect.top = thumbnail_y - thumbnail_h/2.0f;
                thumbnail_anim_dst_rect.right = thumbnail_x + thumbnail_w/2.0f;
                thumbnail_anim_dst_rect.bottom = thumbnail_y + thumbnail_h/2.0f;
                //canvas.drawBitmap(this.thumbnail, thumbnail_anim_src_rect, thumbnail_anim_dst_rect, p);
                thumbnail_anim_matrix.setRectToRect(thumbnail_anim_src_rect, thumbnail_anim_dst_rect, Matrix.ScaleToFit.FILL);
                //thumbnail_anim_matrix.reset();
                if( ui_rotation == 90 || ui_rotation == 270 ) {
                    float ratio = ((float)mLastThumbnail.getWidth())/(float)mLastThumbnail.getHeight();
                    thumbnail_anim_matrix.preScale(ratio, 1.0f/ratio, mLastThumbnail.getWidth()/2.0f, mLastThumbnail.getHeight()/2.0f);
                }
                thumbnail_anim_matrix.preRotate(ui_rotation, mLastThumbnail.getWidth()/2.0f, mLastThumbnail.getHeight()/2.0f);
                canvas.drawBitmap(mLastThumbnail, thumbnail_anim_matrix, mPaint);
            }
        }
    }

    private void openGallery() {
        //Intent intent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        Uri uri = getStorageUtils().getLastMediaScanned();
        boolean is_raw = false; // note that getLastMediaScanned() will never return RAW images, as we only record JPEGs
        if( uri == null ) {
            StorageUtils.Media media = getStorageUtils().getLatestMedia();
            if( media != null ) {
                uri = media.uri;
                is_raw = media.path != null && media.path.toLowerCase(Locale.US).endsWith(".dng");
            }
        }

        if( uri != null ) {
            try {
                ContentResolver cr = getActivity().getContentResolver();
                ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
                if( pfd == null ) {
                    uri = null;
                    is_raw = false;
                }
                else {
                    pfd.close();
                }
            }
            catch(IOException e) {
                uri = null;
                is_raw = false;
            }
        }
        if( uri == null ) {
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            is_raw = false;
        }
        if (true) {
            // don't do if testing, as unclear how to exit activity to finish test (for testGallery())
            final String REVIEW_ACTION = "com.android.camera.action.REVIEW";
            boolean done = false;
            if( !is_raw ) {
                // REVIEW_ACTION means we can view video files without autoplaying
                // however, Google Photos at least has problems with going to a RAW photo (in RAW only mode),
                // unless we first pause and resume Open Camera
                try {
                    Intent intent = new Intent(REVIEW_ACTION, uri);
                    this.startActivity(intent);
                    done = true;
                }
                catch(ActivityNotFoundException e) {
                    e.printStackTrace();
                }
            }
            if( !done ) {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                // see http://stackoverflow.com/questions/11073832/no-activity-found-to-handle-intent - needed to fix crash if no gallery app installed
                //Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("blah")); // test
                if( intent.resolveActivity(getActivity().getPackageManager()) != null ) {
                    try {
                        this.startActivity(intent);
                    }
                    catch(SecurityException e2) {
                        // have received this crash from Google Play - don't display a toast, simply do nothing
                        Log.e(TAG, "SecurityException from ACTION_VIEW startActivity");
                        e2.printStackTrace();
                    }
                }
                else{
                    //getActivity().showToast(null, R.string.no_gallery_app);
                }
            }
        }
    }

    /** Updates the gallery icon by searching for the most recent photo.
     *  Launches the task in a separate thread.
     */
    public void updateGalleryIcon() {
        long debug_time = 0;
        boolean MyDebugLOG = true;
        if( MyDebugLOG ) {
            Log.d(TAG, "updateGalleryIcon");
            debug_time = System.currentTimeMillis();
        }

        new AsyncTask<Void, Void, Bitmap>() {
            private static final String TAG = "MainActivity/AsyncTask";
            private boolean is_video;

            /** The system calls this to perform work in a worker thread and
             * delivers it the parameters given to AsyncTask.execute() */
            protected Bitmap doInBackground(Void... params) {
                if( MyDebugLOG )
                    Log.d(TAG, "doInBackground");
                StorageUtils.Media media = getStorageUtils().getLatestMedia();
                Bitmap thumbnail = null;
                KeyguardManager keyguard_manager = (KeyguardManager)getActivity().getSystemService(Context.KEYGUARD_SERVICE);
                boolean is_locked = keyguard_manager != null && keyguard_manager.inKeyguardRestrictedInputMode();
                if( MyDebugLOG )
                    Log.d(TAG, "is_locked?: " + is_locked);
                if( media != null && getActivity().getContentResolver() != null && !is_locked ) {
                    // check for getContentResolver() != null, as have had reported Google Play crashes
                    if( thumbnail == null ) {
                        try {
                            if( media.video ) {
                                if( MyDebugLOG )
                                    Log.d(TAG, "load thumbnail for video");
                                thumbnail = MediaStore.Video.Thumbnails.getThumbnail(getActivity().getContentResolver(), media.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                                is_video = true;
                            }
                            else {
                                if( MyDebugLOG )
                                    Log.d(TAG, "load thumbnail for photo");
                                thumbnail = MediaStore.Images.Thumbnails.getThumbnail(getActivity().getContentResolver(), media.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                            }
                        }
                        catch(Throwable exception) {
                            // have had Google Play NoClassDefFoundError crashes from getThumbnail() for Galaxy Ace4 (vivalto3g), Galaxy S Duos3 (vivalto3gvn)
                            // also NegativeArraySizeException - best to catch everything
                            if( MyDebugLOG )
                                Log.e(TAG, "exif orientation exception");
                            exception.printStackTrace();
                        }
                    }
                    if( thumbnail != null ) {
                        if( media.orientation != 0 ) {
                            if( MyDebugLOG )
                                Log.d(TAG, "thumbnail size is " + thumbnail.getWidth() + " x " + thumbnail.getHeight());
                            Matrix matrix = new Matrix();
                            matrix.setRotate(media.orientation, thumbnail.getWidth() * 0.5f, thumbnail.getHeight() * 0.5f);
                            try {
                                Bitmap rotated_thumbnail = Bitmap.createBitmap(thumbnail, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), matrix, true);
                                // careful, as rotated_thumbnail is sometimes not a copy!
                                if( rotated_thumbnail != thumbnail ) {
                                    thumbnail.recycle();
                                    thumbnail = rotated_thumbnail;
                                }
                            }
                            catch(Throwable t) {
                                if( MyDebugLOG )
                                    Log.d(TAG, "failed to rotate thumbnail");
                            }
                        }
                    }
                }
                return thumbnail;
            }

            /** The system calls this to perform work in the UI thread and delivers
             * the result from doInBackground() */
            protected void onPostExecute(Bitmap thumbnail) {
                if( MyDebugLOG )
                    Log.d(TAG, "onPostExecute");
                // since we're now setting the thumbnail to the latest media on disk, we need to make sure clicking the Gallery goes to this
                getStorageUtils().clearLastMediaScanned();
                if( thumbnail != null ) {
                    if( MyDebugLOG )
                        Log.d(TAG, "set gallery button to thumbnail");
                    updateGalleryIcon(thumbnail);
                    updateThumbnail(thumbnail, is_video, false); // needed in case last ghost image is enabled
                }
                else {
                    if( MyDebugLOG )
                        Log.d(TAG, "set gallery button to blank");
                    //updateGalleryIconToBlank();
                }
            }
        }.execute();

        if( MyDebugLOG )
            Log.d(TAG, "updateGalleryIcon: total time to update gallery icon: " + (System.currentTimeMillis() - debug_time));
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment {

        private int mRequestCode = -1;

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            int msgId = 0;
            if (mRequestCode == REQUEST_CAMERA_PERMISSION) {
                msgId = R.string.request_camera_permission;
            } else if (mRequestCode == REQUEST_STORAGE_PERMISSION) {
                msgId = R.string.request_storage_permission;
            }
            return new AlertDialog.Builder(getActivity())
                    .setMessage(msgId)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            parent.requestPermissions(new String[]{Manifest.permission.CAMERA},
                                    REQUEST_CAMERA_PERMISSION);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Activity activity = parent.getActivity();
                                    if (activity != null) {
                                        activity.finish();
                                    }
                                }
                            })
                    .create();
        }

        ConfirmationDialog setRequestCode(int requestCode) {
            mRequestCode = requestCode;
            return this;
        }
    }

}
