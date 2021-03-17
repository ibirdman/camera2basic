package com.example.android.output;

import android.hardware.camera2.CaptureResult;
import java.io.File;

public interface IImageReadyListener {
    void onImageReady(ImageSaver.ImageDataWrapper imageDataWrapper,
            File file, CaptureResult captureResult);
}
