package io.flutter.plugins.camera;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
import static io.flutter.plugins.camera.CameraUtils.computeBestPreviewSize;
import static io.flutter.plugins.camera.CameraUtils.mapScreenPointToCameraPoint;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
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
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import androidx.annotation.NonNull;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Camera {
  private static final String TAG = "Camera2";

  private final Activity activity;

  private final SurfaceTextureEntry flutterTexture;
  private final CameraManager cameraManager;
  private final OrientationEventListener orientationEventListener;
  private final boolean isFrontFacing;
  private final boolean isMeteringAreaAFSupported;
  private final Rect sensorActiveArraySize;
  private final int sensorOrientation;
  private final String cameraName;
  private final Size captureSize;
  private final Size previewSize;
  private CameraCharacteristics mCameraCharacteristics;
  private final boolean enableAudio;
  private boolean acquiringFocus = false;
  private boolean mAutoFocus = false;
  private FlashMode mFlash = FlashMode.off;

  private CameraDevice cameraDevice;
  private CameraCaptureSession mCaptureSession;
  private ImageReader pictureImageReader;
  private ImageReader imageStreamReader;
  private DartMessenger dartMessenger;
  private CaptureRequest.Builder mPreviewRequestBuilder;

  private MediaRecorder mediaRecorder;
  private boolean recordingVideo;
  private CamcorderProfile recordingProfile;
  private int currentOrientation = ORIENTATION_UNKNOWN;

  // Mirrors camera.dart
  public enum ResolutionPreset {
    low,
    medium,
    high,
    veryHigh,
    ultraHigh,
    max,
  }

  public enum FlashMode {
    on,
    off,
    torch,
    auto
  }

  public Camera(
      final Activity activity,
      final SurfaceTextureEntry flutterTexture,
      final DartMessenger dartMessenger,
      final String cameraName,
      final String resolutionPreset,
      final boolean enableAudio)
      throws CameraAccessException {
    if (activity == null) {
      throw new IllegalStateException("No activity available!");
    }
    this.activity = activity;

    this.cameraName = cameraName;
    this.enableAudio = enableAudio;
    this.flutterTexture = flutterTexture;
    this.dartMessenger = dartMessenger;
    this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    orientationEventListener =
        new OrientationEventListener(activity.getApplicationContext()) {
          @Override
          public void onOrientationChanged(int i) {
            if (i == ORIENTATION_UNKNOWN) {
              return;
            }
            // Convert the raw deg angle to the nearest multiple of 90.
            currentOrientation = (int) Math.round(i / 90.0) * 90;
          }
        };
    orientationEventListener.enable();

    mCameraCharacteristics = cameraManager.getCameraCharacteristics(cameraName);
    StreamConfigurationMap streamConfigurationMap =
        mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    //noinspection ConstantConditions
    sensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    //noinspection ConstantConditions
    isFrontFacing =
        mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
            == CameraMetadata.LENS_FACING_FRONT;
    isMeteringAreaAFSupported =
        mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) >= 1;
    sensorActiveArraySize =
        mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    ResolutionPreset preset = ResolutionPreset.valueOf(resolutionPreset);
    recordingProfile =
        CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset);
    captureSize = new Size(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
    previewSize = computeBestPreviewSize(cameraName, preset);
  }

  PictureCaptureCallback mCaptureCallback =
      new PictureCaptureCallback() {
        @Override
        public void onPrecaptureRequired() {
          mPreviewRequestBuilder.set(
              CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
              CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
          setState(STATE_PRECAPTURE);
          try {
            mCaptureSession.capture(mPreviewRequestBuilder.build(), this, null);
            mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
          } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to run precapture sequence.", e);
          }
        }

        @Override
        public void onReady() {
          captureStillPicture();
        }
      };

  private void prepareMediaRecorder(String outputFilePath) throws IOException {
    if (mediaRecorder != null) {
      mediaRecorder.release();
    }
    mediaRecorder = new MediaRecorder();

    // There's a specific order that mediaRecorder expects. Do not change the order
    // of these function calls.
    if (enableAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    mediaRecorder.setOutputFormat(recordingProfile.fileFormat);
    if (enableAudio) mediaRecorder.setAudioEncoder(recordingProfile.audioCodec);
    mediaRecorder.setVideoEncoder(recordingProfile.videoCodec);
    mediaRecorder.setVideoEncodingBitRate(recordingProfile.videoBitRate);
    if (enableAudio) mediaRecorder.setAudioSamplingRate(recordingProfile.audioSampleRate);
    mediaRecorder.setVideoFrameRate(recordingProfile.videoFrameRate);
    mediaRecorder.setVideoSize(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
    mediaRecorder.setOutputFile(outputFilePath);
    mediaRecorder.setOrientationHint(getMediaOrientation());

    mediaRecorder.prepare();
  }

  @SuppressLint("MissingPermission")
  public void open(@NonNull final Result result) throws CameraAccessException {
    pictureImageReader =
        ImageReader.newInstance(
            captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);

    // Used to steam image byte data to dart side.
    imageStreamReader =
        ImageReader.newInstance(
            previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

    cameraManager.openCamera(
        cameraName,
        new CameraDevice.StateCallback() {
          @Override
          public void onOpened(@NonNull CameraDevice device) {
            cameraDevice = device;
            try {
              startPreview();
            } catch (CameraAccessException e) {
              result.error("CameraAccess", e.getMessage(), null);
              close();
              return;
            }
            Map<String, Object> reply = new HashMap<>();
            reply.put("textureId", flutterTexture.id());
            reply.put("previewWidth", previewSize.getWidth());
            reply.put("previewHeight", previewSize.getHeight());
            result.success(reply);
          }

          @Override
          public void onClosed(@NonNull CameraDevice camera) {
            dartMessenger.sendCameraClosingEvent();
            super.onClosed(camera);
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            close();
            dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.");
          }

          @Override
          public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
            close();
            String errorDescription;
            switch (errorCode) {
              case ERROR_CAMERA_IN_USE:
                errorDescription = "The camera device is in use already.";
                break;
              case ERROR_MAX_CAMERAS_IN_USE:
                errorDescription = "Max cameras in use";
                break;
              case ERROR_CAMERA_DISABLED:
                errorDescription = "The camera device could not be opened due to a device policy.";
                break;
              case ERROR_CAMERA_DEVICE:
                errorDescription = "The camera device has encountered a fatal error";
                break;
              case ERROR_CAMERA_SERVICE:
                errorDescription = "The camera service has encountered a fatal error.";
                break;
              default:
                errorDescription = "Unknown camera error";
            }
            dartMessenger.send(DartMessenger.EventType.ERROR, errorDescription);
          }
        },
        null);
  }

  private void writeToFile(ByteBuffer buffer, File file) throws IOException {
    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      while (0 < buffer.remaining()) {
        outputStream.getChannel().write(buffer);
      }
    }
  }

  SurfaceTextureEntry getFlutterTexture() {
    return flutterTexture;
  }

  public void takePicture(String filePath, @NonNull final Result result) {
    mCaptureCallback.setFilePath(filePath);
    mCaptureCallback.setResult(result);

    captureStillPicture();
  }

  private void captureStillPicture() {
    String filePath = mCaptureCallback.getFilePath();
    Result result = mCaptureCallback.getResult();

    final File file = new File(filePath);

    if (file.exists()) {
      result.error(
          "fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
      return;
    }

    pictureImageReader.setOnImageAvailableListener(
        reader -> {
          try (Image image = reader.acquireLatestImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            writeToFile(buffer, file);
            result.success(null);
          } catch (IOException e) {
            result.error("IOError", "Failed saving image", null);
          }
        },
        null);

    try {
      final CaptureRequest.Builder captureBuilder =
          cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      captureBuilder.addTarget(pictureImageReader.getSurface());
      captureBuilder.set(
          CaptureRequest.CONTROL_AE_MODE,
          mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_MODE));
      captureBuilder.set(
          CaptureRequest.CONTROL_AF_MODE,
          mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE));
      captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());

      updateFlash(captureBuilder);

      mCaptureSession.stopRepeating();
      mCaptureSession.capture(
          captureBuilder.build(),
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureFailed(
                @NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull CaptureFailure failure) {
              String reason;
              switch (failure.getReason()) {
                case CaptureFailure.REASON_ERROR:
                  reason = "An error happened in the framework";
                  break;
                case CaptureFailure.REASON_FLUSHED:
                  reason = "The capture has failed due to an abortCaptures() call";
                  break;
                default:
                  reason = "Unknown reason";
              }
              result.error("captureFailure", reason, null);
            }

            @Override
            public void onCaptureCompleted(
                @NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull TotalCaptureResult result) {
              unlockFocus();
            }
          },
          null);
    } catch (CameraAccessException e) {
      result.error("cameraAccess", e.getMessage(), null);
    }
  }

  /** Locks the focus as the first step for a still image capture. */
  private void lockFocus() {
    mPreviewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
    try {
      mCaptureCallback.setState(PictureCaptureCallback.STATE_LOCKING);
      mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, null);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Failed to lock focus.", e);
    }
  }

  /**
   * Unlocks the auto-focus and restart camera preview. This is supposed to be called after
   * capturing a still picture.
   */
  void unlockFocus() {
    mPreviewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
    try {
      mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, null);

      updateAutoFocus(mPreviewRequestBuilder);
      updateFlash(mPreviewRequestBuilder);

      mPreviewRequestBuilder.set(
          CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
      mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, null);
      mCaptureCallback.setState(PictureCaptureCallback.STATE_PREVIEW);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Failed to restart camera preview.", e);
    }
  }

  public void acquireFocus(PointF screenPoint, int touchRadius, @NonNull final Result result)
      throws CameraAccessException {
    if (acquiringFocus) {
      result.success(null);
      return;
    }

    final PointF cameraPoint = mapScreenPointToCameraPoint(screenPoint, sensorOrientation);

    final int x = (int) (cameraPoint.x * (float) sensorActiveArraySize.width());
    final int y = (int) (cameraPoint.y * (float) sensorActiveArraySize.height());

    CameraCaptureSession.CaptureCallback captureCallbackHandler =
        new CameraCaptureSession.CaptureCallback() {
          @Override
          public void onCaptureCompleted(
              CameraCaptureSession session, CaptureRequest req, TotalCaptureResult res) {
            super.onCaptureCompleted(session, req, res);

            acquiringFocus = false;

            if (req.getTag() == "FOCUS_TAG") {
              mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null);
              try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, null);
              } catch (CameraAccessException e) {
                dartMessenger.send(DartMessenger.EventType.ERROR, e.getMessage());
              }
            }
          }

          @Override
          public void onCaptureFailed(
              CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            acquiringFocus = false;
            dartMessenger.send(DartMessenger.EventType.ERROR, "Manual AF failure: " + failure);
            result.error("acquiringFocusFailed", "Manual AF failure: " + failure, null);
          }
        };

    mCaptureSession.stopRepeating();

    mPreviewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
    try {
      mCaptureSession.capture(mPreviewRequestBuilder.build(), captureCallbackHandler, null);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Failed to manual focus.", e);
    }

    if (isMeteringAreaAFSupported) {
      MeteringRectangle focusAreaTouch =
          new MeteringRectangle(
              Math.max(x - touchRadius, 0),
              Math.max(y - touchRadius, 0),
              touchRadius * 2,
              touchRadius * 2,
              MeteringRectangle.METERING_WEIGHT_MAX);
      mPreviewRequestBuilder.set(
          CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[] {focusAreaTouch});
    }

    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
    mPreviewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
    mPreviewRequestBuilder.setTag("FOCUS_TAG");

    mCaptureSession.capture(mPreviewRequestBuilder.build(), captureCallbackHandler, null);

    acquiringFocus = true;
  }

  public void setFlash(FlashMode flash) {
    if (mFlash == flash) {
      return;
    }

    FlashMode saved = mFlash;
    mFlash = flash;
    if (mPreviewRequestBuilder != null) {
      updateFlash(mPreviewRequestBuilder);
      if (mCaptureSession != null) {
        try {
          mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
          mFlash = saved; // Revert
        }
      }
    }
  }

  void updateFlash(CaptureRequest.Builder builder) {
    switch (mFlash) {
      case off:
        builder.set(
            CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        break;
      case on:
        builder.set(
            CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
        break;
      case torch:
        builder.set(
            CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
        break;
      case auto:
        builder.set(
            CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        break;
      default:
        break;
    }
  }

  void setAutoFocus(boolean autoFocus) {
    if (mAutoFocus == autoFocus) {
      return;
    }
    mAutoFocus = autoFocus;
    if (mPreviewRequestBuilder != null) {
      updateAutoFocus(mPreviewRequestBuilder);
      if (mCaptureSession != null) {
        try {
          mCaptureSession.setRepeatingRequest(
              mPreviewRequestBuilder.build(), mCaptureCallback, null);
        } catch (CameraAccessException e) {
          mAutoFocus = !mAutoFocus; // Revert
        }
      }
    }
  }

  /** Updates the internal state of auto-focus to {@link #mAutoFocus}. */
  void updateAutoFocus(CaptureRequest.Builder builder) {
    if (mAutoFocus) {
      int[] modes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
      // Auto focus is not supported
      if (modes == null
          || modes.length == 0
          || (modes.length == 1 && modes[0] == CameraCharacteristics.CONTROL_AF_MODE_OFF)) {
        mAutoFocus = false;
        builder.set(
            CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
      } else {
        builder.set(
            CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      }
    } else {
      builder.set(
          CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
    }
  }

  private void createCaptureSession(int templateType, Surface... surfaces)
      throws CameraAccessException {
    createCaptureSession(templateType, null, surfaces);
  }

  private void createCaptureSession(
      int templateType, Runnable onSuccessCallback, Surface... surfaces)
      throws CameraAccessException {
    // Close any existing capture session.
    closeCaptureSession();

    // Create a new capture builder.
    mPreviewRequestBuilder = cameraDevice.createCaptureRequest(templateType);

    // Build Flutter surface to render to
    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
    Surface flutterSurface = new Surface(surfaceTexture);
    mPreviewRequestBuilder.addTarget(flutterSurface);

    List<Surface> remainingSurfaces = Arrays.asList(surfaces);
    if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
      // If it is not preview mode, add all surfaces as targets.
      for (Surface surface : remainingSurfaces) {
        mPreviewRequestBuilder.addTarget(surface);
      }
    }

    // Prepare the callback
    CameraCaptureSession.StateCallback callback =
        new CameraCaptureSession.StateCallback() {
          @Override
          public void onConfigured(@NonNull CameraCaptureSession session) {
            try {
              if (cameraDevice == null) {
                dartMessenger.send(
                    DartMessenger.EventType.ERROR, "The camera was closed during configuration.");
                return;
              }
              mCaptureSession = session;
              mPreviewRequestBuilder.set(
                  CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
              setAutoFocus(true);
              mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, null);
              if (onSuccessCallback != null) {
                onSuccessCallback.run();
              }
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
              dartMessenger.send(DartMessenger.EventType.ERROR, e.getMessage());
            }
          }

          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            dartMessenger.send(
                DartMessenger.EventType.ERROR, "Failed to configure camera session.");
          }
        };

    // Collect all surfaces we want to render to.
    List<Surface> surfaceList = new ArrayList<>();
    surfaceList.add(flutterSurface);
    surfaceList.addAll(remainingSurfaces);
    // Start the session
    cameraDevice.createCaptureSession(surfaceList, callback, null);
  }

  public void startVideoRecording(String filePath, Result result) {
    if (new File(filePath).exists()) {
      result.error("fileExists", "File at path '" + filePath + "' already exists.", null);
      return;
    }
    try {
      prepareMediaRecorder(filePath);
      recordingVideo = true;
      createCaptureSession(
          CameraDevice.TEMPLATE_RECORD, () -> mediaRecorder.start(), mediaRecorder.getSurface());
      result.success(null);
    } catch (CameraAccessException | IOException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      recordingVideo = false;
      mediaRecorder.stop();
      mediaRecorder.reset();
      startPreview();
      result.success(null);
    } catch (CameraAccessException | IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void pauseVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.pause();
      } else {
        result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void resumeVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.resume();
      } else {
        result.error(
            "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void startPreview() throws CameraAccessException {
    createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.getSurface());
  }

  public void startPreviewWithImageStream(EventChannel imageStreamChannel)
      throws CameraAccessException {
    createCaptureSession(CameraDevice.TEMPLATE_RECORD, imageStreamReader.getSurface());

    imageStreamChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
            setImageStreamImageAvailableListener(imageStreamSink);
          }

          @Override
          public void onCancel(Object o) {
            imageStreamReader.setOnImageAvailableListener(null, null);
          }
        });
  }

  private void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
    imageStreamReader.setOnImageAvailableListener(
        reader -> {
          Image img = reader.acquireLatestImage();
          if (img == null) return;

          List<Map<String, Object>> planes = new ArrayList<>();
          for (Image.Plane plane : img.getPlanes()) {
            ByteBuffer buffer = plane.getBuffer();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes, 0, bytes.length);

            Map<String, Object> planeBuffer = new HashMap<>();
            planeBuffer.put("bytesPerRow", plane.getRowStride());
            planeBuffer.put("bytesPerPixel", plane.getPixelStride());
            planeBuffer.put("bytes", bytes);

            planes.add(planeBuffer);
          }

          Map<String, Object> imageBuffer = new HashMap<>();
          imageBuffer.put("width", img.getWidth());
          imageBuffer.put("height", img.getHeight());
          imageBuffer.put("format", img.getFormat());
          imageBuffer.put("planes", planes);

          imageStreamSink.success(imageBuffer);
          img.close();
        },
        null);
  }

  private void closeCaptureSession() {
    if (mCaptureSession != null) {
      mCaptureSession.close();
      mCaptureSession = null;
    }
  }

  public void close() {
    closeCaptureSession();

    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }
    if (pictureImageReader != null) {
      pictureImageReader.close();
      pictureImageReader = null;
    }
    if (imageStreamReader != null) {
      imageStreamReader.close();
      imageStreamReader = null;
    }
    if (mediaRecorder != null) {
      mediaRecorder.reset();
      mediaRecorder.release();
      mediaRecorder = null;
    }
  }

  public void dispose() {
    close();
    flutterTexture.release();
    orientationEventListener.disable();
  }

  private int getMediaOrientation() {
    final int sensorOrientationOffset =
        (currentOrientation == ORIENTATION_UNKNOWN)
            ? 0
            : (isFrontFacing) ? -currentOrientation : currentOrientation;
    return (sensorOrientationOffset + sensorOrientation + 360) % 360;
  }
}
