package com.lin.spencer.smilecounter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.things.contrib.driver.apa102.Apa102;
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay;
import com.google.android.things.contrib.driver.ht16k33.Ht16k33;
import com.google.android.things.contrib.driver.rainbowhat.RainbowHat;
import com.google.android.things.pio.Gpio;
import com.google.firebase.FirebaseApp;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.face.FirebaseVisionFace;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetector;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Skeleton of an Android Things activity.
 * <p>
 * Android Things peripheral APIs are accessible through the class
 * PeripheralManagerService. For example, the snippet below will open a GPIO pin and
 * set it to HIGH:
 *
 * <pre>{@code
 * PeripheralManagerService service = new PeripheralManagerService();
 * mLedGpio = service.openGpio("BCM6");
 * mLedGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
 * mLedGpio.setValue(true);
 * }</pre>
 * <p>
 * For more complex peripherals, look for an existing user-space driver, or implement one if none
 * is available.
 *
 * @see <a href="https://github.com/androidthings/contrib-drivers#readme">https://github.com/androidthings/contrib-drivers#readme</a>
 */
public class SmileCounterActivity extends Activity {
    private static final int CAMERA_RESOLUTION_WIDTH = 320; // max 640
    private static final int CAMERA_RESOLUTION_HEIGHT = 240; // max 480
    private static final double MIN_SMILE_PROB_PCT = 0.90;
    private static final int LED_STRIP_BRIGHTNESS = 1;
    private static final Duration DEBOUNCE_DURATION = Duration.ofMillis(500);
    private static final String TAG = SmileCounterActivity.class.getSimpleName();

    private int mSmileCount = 0;
    private AlphanumericDisplay mDisplay;
    private Apa102 mLEDStrip;
    private int[] mRainbow;
    private CameraCaptureSession mCameraCaptureSession;
    private CaptureRequest mCaptureRequest;
    private ImageReader mImageReader;
    private Surface mCaptureSurface;

    private OnSuccessListener<List<FirebaseVisionFace>> mFaceDetectionSuccessListener = new OnSuccessListener<List<FirebaseVisionFace>>() {
        @Override
        public void onSuccess(List<FirebaseVisionFace> faces) {
            Log.d(TAG, String.format("Face detection succeeded, found %d faces", faces.size()));
            boolean foundSmile = false;
            for (FirebaseVisionFace face : faces) {
                float smilingProb = face.getSmilingProbability();
                Log.d(TAG, String.format("Smiling probability: %f", smilingProb));
                if (smilingProb >= MIN_SMILE_PROB_PCT) {
                    foundSmile = true;
                    mSmileCount++;
                    Log.d(TAG, String.format("Smile count increased to %d", mSmileCount));
                    flashRainbow();
                    setDisplayInt(mSmileCount);
                }
            }

            if (foundSmile) {
                Log.d(TAG, "Debouncing");
                try {
                    Thread.sleep(DEBOUNCE_DURATION.toMillis());
                } catch (InterruptedException e) {
                    Log.e(TAG, "Error sleeping", e);
                }
            }

            try {
                mCameraCaptureSession.capture(mCaptureRequest, null, null);
            } catch (CameraAccessException cae) {
                Log.e(TAG, "Failed to capture picture", cae);
            }
        }
    };

    private OnFailureListener mFaceDetectionFailureListener = new OnFailureListener() {
        @Override
        public void onFailure (@NonNull Exception e){
            Log.e(TAG, "Face detection failed", e);
        }
    };

    private ImageReader.OnImageAvailableListener mImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        FirebaseVisionFaceDetectorOptions faceDetectorOptions =
                new FirebaseVisionFaceDetectorOptions.Builder()
                        .setClassificationMode(FirebaseVisionFaceDetectorOptions.ALL_CLASSIFICATIONS)
                        .build();

        FirebaseVisionFaceDetector detector = FirebaseVision.getInstance()
                .getVisionFaceDetector(faceDetectorOptions);

        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Log.d(TAG, "Image available");
            Image image = imageReader.acquireNextImage();
            FirebaseVisionImage firebaseImage = FirebaseVisionImage.fromMediaImage(image, FirebaseVisionImageMetadata.ROTATION_0);
            image.close();

            detector.detectInImage(firebaseImage)
                    .addOnSuccessListener(mFaceDetectionSuccessListener)
                    .addOnFailureListener(mFaceDetectionFailureListener);
        }
    };

    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {

        /**
         * Creates a cameraCaptureSession, which is handled by `mCameraCaptureSessionStateCallback`
         * and also sets `mImageAvailableListener` as the listener for new images
         */
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, "Camera opened");

            mImageReader = ImageReader.newInstance(
                    CAMERA_RESOLUTION_WIDTH,
                    CAMERA_RESOLUTION_HEIGHT,
                    ImageFormat.YUV_420_888,
                    1);

            mImageReader.setOnImageAvailableListener(mImageAvailableListener, null);
            mCaptureSurface = mImageReader.getSurface();
            try {
                cameraDevice.createCaptureSession(
                        Collections.singletonList(mCaptureSurface),
                        mCameraCaptureSessionStateCallback,
                        null);
            } catch (CameraAccessException cae) {
                throw new RuntimeException("Error encountered creating camera capture session", cae);
            }
        }

        @Override
        public void onClosed(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, "Camera closed");
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, "Camera disconnected");
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.e(TAG, String.format("Camera had error, code: %d", error));
            cameraDevice.close();
        }

    };

    private CameraCaptureSession.StateCallback mCameraCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {

        /**
         * Starts capturing the first image
         */
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.d(TAG, "Camera capture session configured");
            mCameraCaptureSession = cameraCaptureSession;

            try {
                CaptureRequest.Builder captureRequestBuilder = mCameraCaptureSession.getDevice()
                        .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                captureRequestBuilder.addTarget(mCaptureSurface);
                mCaptureRequest = captureRequestBuilder.build();
                mCameraCaptureSession.capture(mCaptureRequest, null, null);
            } catch (CameraAccessException cae) {
                throw new RuntimeException("Error encountered creating camera capture request", cae);
            }
            setDisplayInt(mSmileCount);
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.e(TAG, "Camera capture session configuration failed");
        }

        @Override
        public void onReady(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.d(TAG, "Camera capture session ready");
        }

        @Override
        public void onActive(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.d(TAG, "Camera capture session active");
        }

        @Override
        public void onCaptureQueueEmpty(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.d(TAG, "Camera capture session capture queue empty");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.d(TAG, "Camera capture session closed");
        }

        @Override
        public void onSurfacePrepared(@NonNull CameraCaptureSession cameraCaptureSession, Surface surface) {
            Log.d(TAG, "Camera capture session surface prepared");
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeAlphanumericDisplay();
        setDisplay("INIT");

        turnOffRGBLEDs();
        initializeLEDStrip();

        FirebaseApp.initializeApp(this);

        openCamera();

        setContentView(R.layout.activity_home);
    }

    /**
     * Tries to open a {@link CameraDevice}. The result is listened by `mCameraDeviceStateCallback`.
     */
    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        Log.d(TAG, "Calling open camera");
        CameraManager manager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            throw new RuntimeException("Got null camera manager");
        }

        String[] camIds;
        try {
            camIds = manager.getCameraIdList();
        } catch (CameraAccessException e) {
            throw new RuntimeException("Cannot get a list of available cameras", e);
        }
        if (camIds.length < 1) {
            throw new RuntimeException("No cameras found");
        }

        String camId = camIds[0];
        Log.d(TAG, "Using camera id " + camId);

        try {
          manager.openCamera(camId, mCameraDeviceStateCallback, null);
        } catch (CameraAccessException cae) {
            throw new RuntimeException("Error encountered opening camera", cae);
        }

        Log.d(TAG, "Successfully opened camera");
    }

    private void setDisplayInt(int i) {
        setDisplay(String.format("%d", i));
    }

    private void setDisplay(String msg) {
        try {
            mDisplay.display(msg);
        } catch (IOException e) {
            Log.e(TAG, "Error updating display", e);
        }
    }

    private void initializeAlphanumericDisplay() {
        Log.d(TAG, "Initializing alphanumeric display");
        try {
            mDisplay = RainbowHat.openDisplay();
            mDisplay.setBrightness(Ht16k33.HT16K33_BRIGHTNESS_MAX);
            mDisplay.setEnabled(true);
            mDisplay.clear();
            Log.d(TAG, "Initialized I2C Display");
        } catch (IOException e) {
            Log.e(TAG, "Error initializing display", e);
            throw new RuntimeException("Error initializing I2C display", e);
        }
    }

    private void initializeLEDStrip() {
        Log.d(TAG, "Initializing LED strip lights");
        try {
            mLEDStrip = RainbowHat.openLedStrip();
            mLEDStrip.setBrightness(0);
            mRainbow = new int[RainbowHat.LEDSTRIP_LENGTH];
            for (int i = 0; i < mRainbow.length; i++) {
                mRainbow[i] = Color.HSVToColor(255, new float[]{i * 360.f / mRainbow.length, 1.0f, 1.0f});
            }
            mLEDStrip.write(mRainbow);
        } catch (IOException e) {
            Log.e(TAG, "Error initializing LED strip lights", e);
            throw new RuntimeException("Error initializing LED strip lights", e);
        }
    }

    private void flashRainbow() {
        Log.d(TAG, "Flashing LED strip lights");
        try {
            int lightIdx = mRainbow.length - 1;
            for (int colorValue : mRainbow) {
                int[] ledStrip = new int[mRainbow.length];
                ledStrip[lightIdx] = colorValue;

                mLEDStrip.setBrightness(LED_STRIP_BRIGHTNESS);
                mLEDStrip.write(ledStrip);
                mLEDStrip.setBrightness(0);
                mLEDStrip.write(ledStrip);

                lightIdx--;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error flashing LED strip lights", e);
        }
    }

    private void turnOffRGBLEDs() {
        Log.d(TAG, "Turning off RGB LEDs");
        Gpio led;
        try {
            led = RainbowHat.openLedRed();
            led.setActiveType(Gpio.ACTIVE_LOW);
            led.close();

            led = RainbowHat.openLedGreen();
            led.setActiveType(Gpio.ACTIVE_LOW);
            led.close();

            led = RainbowHat.openLedBlue();
            led.setActiveType(Gpio.ACTIVE_LOW);
            led.close();
        } catch (IOException e) {
            Log.e(TAG, "Error turning off RGB LEDs", e);
        }
    }
}
