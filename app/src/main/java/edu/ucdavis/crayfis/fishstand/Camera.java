package edu.ucdavis.crayfis.fishstand;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;


import android.content.Context;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.CameraCaptureSession;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Range;
import android.util.SizeF;
import android.util.Size;
import android.view.Surface;


public class Camera {

    private static final String TAG = "Camera";

    //camera2 api objects
    private String cid;
    private CameraManager cmanager;
    private CameraCharacteristics cchars;
    private CameraDevice cdevice;
    private CameraCaptureSession csession;
    CaptureRequest.Builder crequestbuilder;
    private HandlerThread cthread;
    private Handler chandler;

    private ImageReader ireader;
    private final Frame.Builder fbuilder = new Frame.Builder();
    private Frame.OnFrameCallback fcallback;
    private AtomicInteger num_frames;

    private Boolean init = false;

    private final int max_images=10;

    // discovered camera properties for RAW format at highest resolution
    public Size raw_size;
    public long min_exp=0;
    public long max_exp=0;
    public int min_sens=0;
    public int max_sens=0;
    public int max_analog=0;



    public void Init() {
        num_frames = new AtomicInteger();

        cthread = new HandlerThread("Camera");
        cthread.start();
        chandler = new Handler(cthread.getLooper());
        chandler.post(new Runnable() {
            @Override
            public void run() {
                init_stage1();
            }
        });
        // should put a timeout here...
        while(!init){
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                e.printStackTrace();
                // continue
            }
        }
    }

    private void init_stage1() {
        App.log().append("init started at "
                + DateFormat.getDateTimeInstance().format(new Date(System.currentTimeMillis())) + "\n");
        cmanager = (CameraManager) App.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            cid = "";
            String summary = "";
            for (String id : cmanager.getCameraIdList()) {
                summary += "camera id string:  " + id + "\n";
                CameraCharacteristics chars = cmanager.getCameraCharacteristics(id);
                // Does the camera have a forwards facing lens?
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);

                summary += "facing:  " + facing + "\n";
                SizeF fsize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                Size isize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                summary += "physical sensor size (w x h):  " + fsize.getWidth() + " mm x " + fsize.getHeight() + " mm\n";
                summary += "pixel array size (w x h):  " + isize.getWidth() + " x " + isize.getHeight() + "\n";

                //check for needed capabilities:
                Boolean manual_mode = false;
                Boolean raw_mode = false;
                int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                summary += "caps:  ";
                for (int i : caps) {
                    summary += i + ", ";
                    if (i == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR){ manual_mode = true; }
                    if (i == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW){ raw_mode = true; }
                }
                summary += "\n";
                summary += "RAW mode support:  " + raw_mode + "\n";
                summary += "Manual mode support:  " + manual_mode + "\n";
                if ((cid.isEmpty()) && (facing == 1) && raw_mode && manual_mode) {
                    cid = id;
                    break;
                }
            }
            App.log().append(summary);
            if (!cid.isEmpty()) {
                summary = "";
                summary += "selected camera ID " + cid + "\n";
                cchars = cmanager.getCameraCharacteristics(cid);
                Size isize = cchars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                StreamConfigurationMap configs = cchars.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Boolean raw_available = false;
                int[] fmts = configs.getOutputFormats();
                for (int fmt : fmts) {
                    if (fmt == ImageFormat.RAW_SENSOR) raw_available = true;
                }
                if (!raw_available) {
                    summary += "RAW_SENSOR format not available.  Cannot init...";
                    return;
                }

                Size[] sizes = configs.getOutputSizes(ImageFormat.RAW_SENSOR);
                summary += "RAW format available sizes:  ";
                int maxprod = 0;
                for (Size s : sizes) {
                    int h = s.getHeight();
                    int w = s.getWidth();
                    int p = h * w;
                    summary += w + " x " + h + ",";
                    if (p > maxprod) {
                        maxprod = p;
                        raw_size = s;
                    }
                }
                summary += "\n";
                summary += "Largest size is " + raw_size + "\n";

                float fls[] = cchars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                summary += "focal lengths:  ";
                for(float fl: fls){
                    summary += fl + ", ";
                }
                summary += "\n";

                int ns[] = cchars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
                summary+="noise modes:  ";
                for(int n: ns){
                    summary += n + ", ";
                }
                summary += "\n";

                Range<Long> rexp = cchars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                min_exp = rexp.getLower();
                max_exp = rexp.getUpper();
                summary += "Exposure range:  " + min_exp*1E-6 + " to " + max_exp*1E-6 + " ms\n";
                // set default selected exposure to maximum:

                Range<Integer> rsens = cchars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                min_sens = rsens.getLower();
                max_sens = rsens.getUpper();
                summary += "Sensitivity range:  " + min_sens + " to " + max_sens + " (ISO)\n";

                max_analog = cchars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
                summary += "Make Analog Sensitivity:  " + max_analog + "\n";

                int filter_arrangement = cchars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                summary += "Color filter arrangement:  " + filter_arrangement + "\n";

                //Boolean shading = cchars.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED);
                //summary += "Lens shading correction applied to RAW:  " + shading + "\n";
                App.log().append(summary);

                App.log().append("Camera settings initialized.\n");

                cmanager.openCamera(cid, deviceCallback, chandler);
            } else {
                App.log().append("Could not find camera device with sufficient capabilities.  Cannot init.");
            }
        } catch (CameraAccessException |SecurityException e) {
            e.printStackTrace();
        }
    }

    private void init_stage2() {
        //summary += "stage1 init success\n";
        // check camera open?  Or at least non-null?
        App.log().append("Creating capture session\n");
        if(App.getConfig().getBoolean("yuv", false)) {
            App.log().append("Using YUV.\n");
            ireader = ImageReader.newInstance(raw_size.getWidth(), raw_size.getHeight(), ImageFormat.YUV_420_888, max_images);
        } else {
            App.log().append("Using RAW.\n");
            ireader = ImageReader.newInstance(raw_size.getWidth(), raw_size.getHeight(), ImageFormat.RAW_SENSOR, max_images);
        }
        List<Surface> outputs = new ArrayList<Surface>(1);
        outputs.add(ireader.getSurface());
        ireader.setOnImageAvailableListener(imageCallback, chandler);
        try {
            cdevice.createCaptureSession(outputs, sessionCallback, chandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // call back from stage1, saves open camera device and calls stage2:

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //This is called when the camera is open
            cdevice = camera;
            App.log().append("Camera is open.\n");
            init_stage2();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            App.log().append("Camera is closed.\n");
            cdevice.close();
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            App.log().append("Camera in error!\n");
            cdevice.close();
            cdevice = null;
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback(){
        @Override
        public void	onConfigured(@NonNull CameraCaptureSession session){
            App.log().append("Camera capture session configured.\n");
            csession = session;
            try {
                crequestbuilder = cdevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                crequestbuilder.addTarget(ireader.getSurface());
                crequestbuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.min(max_exp, 1000000000L));
                crequestbuilder.set(CaptureRequest.SENSOR_SENSITIVITY, max_analog);
                crequestbuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                crequestbuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                crequestbuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                crequestbuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
                crequestbuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f); // put focus at infinity
                crequestbuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF); // need to see if any effect
                crequestbuilder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF); // need to see if any effect!
                crequestbuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF); // need to see if any effect!
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            App.log().append("camera initialization has succeeded.\n");
            init = true;
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            App.log().append("Camera capture session closed");
        }
    };

    public void start(Frame.OnFrameCallback callback) {
        if(init) {
            App.log().append("Starting camera!\n");
            fcallback = callback;
            ireader.acquireLatestImage();
            try {
                csession.setRepeatingRequest(crequestbuilder.build(), captureCallback, chandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    public void stop() {
        try {
            csession.stopRepeating();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Frame frame = fbuilder.setResult(result)
                    .build();
            if (frame != null) {
                fcallback.onFrame(frame, num_frames.incrementAndGet());
            }
        }
    };

    private final ImageReader.OnImageAvailableListener imageCallback = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Frame frame = fbuilder.setImage(imageReader.acquireLatestImage())
                    .build();
            if (frame != null) {
                fcallback.onFrame(frame, num_frames.incrementAndGet());
            }
        }
    };

    public static class Frame {

        public final Image image;
        public final TotalCaptureResult result;

        private Frame(Image image, TotalCaptureResult result) {
            this.image = image;
            this.result = result;
        }

        private static class Builder {
            private Image bImage;
            private TotalCaptureResult bResult;

            private Builder setImage(Image image) {
                bImage = image;
                return this;
            }

            private Builder setResult(TotalCaptureResult result) {
                bResult = result;
                return this;
            }

            private synchronized Frame build() {
                if(bImage != null && bResult != null) {
                    Frame frame = new Frame(bImage, bResult);
                    bImage = null;
                    bResult = null;
                    return frame;
                }

                return null;
            }
        }

        public interface OnFrameCallback {
            void onFrame(@NonNull Frame frame, int num_frames);
        }
    }

}
