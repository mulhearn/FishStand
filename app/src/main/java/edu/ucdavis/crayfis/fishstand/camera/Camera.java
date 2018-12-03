package edu.ucdavis.crayfis.fishstand.camera;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;


import android.content.Context;
import android.graphics.PointF;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.params.TonemapCurve;
import android.graphics.ImageFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.SizeF;
import android.util.Size;
import android.view.Surface;

import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Config;


public class Camera {

    private static final String TAG = "Camera";

    private static int N_ALLOC;

    //camera2 api objects
    private String cid;
    private final CameraManager cmanager;
    private CameraCharacteristics cchars;
    private CameraDevice cdevice;
    private CameraCaptureSession csession;
    
    private HandlerThread camera_thread;
    private Handler camera_handler;
    
    private HandlerThread frame_thread;
    private Handler frame_handler;

    private Frame.Producer frame_producer;
    private Frame.OnFrameCallback frame_callback;


    // discovered camera properties for RAW format at highest resolution
    private long min_exp=0;
    private long max_exp=0;
    private int min_sens=0;
    private int max_sens=0;
    private int max_analog=0;
    private long min_duration_raw;
    private long min_duration_yuv;
    private long min_duration;

    private boolean yuv;
    private Size raw_size;
    private Size yuv_size;
    private int iso;
    private long exposure;
    private Size size;
    private float[] tonemap;

    private boolean configured = false;


    public Camera() {
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

                int maxprod;
                Size[] sizes;

                sizes = configs.getOutputSizes(ImageFormat.RAW_SENSOR);
                summary += "RAW format available sizes:  ";
                maxprod = 0;
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

                min_duration_raw = configs.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, raw_size);
                summary += "with minimum frame duration " + min_duration_raw + "\n";

                sizes = configs.getOutputSizes(Allocation.class );
                summary += "YUV format available sizes:  ";
                maxprod = 0;
                for (Size s : sizes) {
                    int h = s.getHeight();
                    int w = s.getWidth();
                    int p = h * w;
                    summary += w + " x " + h + ",";
                    if (p > maxprod) {
                        maxprod = p;
                        yuv_size = s;
                    }
                }
                summary += "\n";
                summary += "Largest size is " + yuv_size + "\n";
                min_duration_yuv = configs.getOutputMinFrameDuration(ImageFormat.YUV_420_888, yuv_size);
                summary += "with minimum frame duration " + min_duration_yuv + "\n";


                summary += "focal lengths:  ";
                for(float fl: cchars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)){
                    summary += fl + ", ";
                }
                summary += "\n";

                summary += "color correction mode:  ";
                String color_mode = "ON";
                for(int n: cchars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)){
                    if(n == CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF) {
                        color_mode = "OFF";
                        break;
                    }
                }
                summary += color_mode + "\n";

                summary += "noise reduction mode:  ";
                String noise_mode = "ON";
                for(int n: cchars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)){
                    if(n == CameraMetadata.NOISE_REDUCTION_MODE_OFF) {
                        noise_mode = "OFF";
                        break;
                    }
                }
                summary += noise_mode + "\n";

                summary += "hotpixel mode:  ";
                String hotpixel_mode = "ON";
                for(int n: cchars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)){
                    if(n == CameraMetadata.HOT_PIXEL_MODE_OFF) {
                        hotpixel_mode = "OFF";
                        break;
                    }
                }
                summary += hotpixel_mode + "\n";

                summary += "edge mode:  ";
                String edge_mode = "ON";
                for(int n: cchars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)){
                    if(n == CameraMetadata.HOT_PIXEL_MODE_OFF) {
                        edge_mode = "OFF";
                        break;
                    }
                }
                summary += edge_mode + "\n";

                summary += "tonemap control: ";
                String tonemap_control = "DISABLED";
                for(int n: cchars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)){
                    if(n == CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) {
                        tonemap_control = "ENABLED";
                        break;
                    }
                }
                summary += tonemap_control + "\n";


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
                summary += "Max Analog Sensitivity:  " + max_analog + "\n";

                final String filter_str;
                switch (cchars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
                    case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB:
                        filter_str = "RGGB";
                        break;
                    case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG:
                        filter_str = "GRBG";
                        break;
                    case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR:
                        filter_str = "BGGR";
                        break;
                    case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB:
                        filter_str = "RGB";
                        break;
                    default:
                        filter_str = "Unknown";
                }
                summary += "Color filter arrangement:  " + filter_str + "\n";

                App.log().append(summary);

                App.log().append("Camera settings initialized.\n");
            } else {
                App.log().append("Could not find camera device with sufficient capabilities.  Cannot init.");
            }
        } catch (CameraAccessException | SecurityException e) {
            e.printStackTrace();
        }
    }

    private void createSession() {
        App.log().append("Creating capture session\n");

        if (yuv) {
            App.log().append("Using YUV image format.\n");
            frame_producer = new YuvFrame.Producer(N_ALLOC, size, frame_handler, frame_callback);
        } else {
            App.log().append("Using RAW image format.\n");
            frame_producer = new RawFrame.Producer(N_ALLOC, raw_size, frame_handler, frame_callback);
        }

        List<Surface> outputs = frame_producer.getSurfaces();
        try {
            cdevice.createCaptureSession(outputs, sessionCallback, camera_handler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    private List<CaptureRequest> makeRequests(List<Surface> surfaces) {
        List<CaptureRequest> requests = new ArrayList<>();
        for(Surface s : surfaces) {
            try {
                App.log().append("setting exposure to " + exposure + "\n");

                if (exposure < min_duration){
                    App.log().append("setting frame duration to " + min_duration + "\n");
                }

                CaptureRequest.Builder b = cdevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(s);
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
                if (exposure < min_duration) {
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, min_duration);
                }
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f); // put focus at infinity
                b.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF); // need to see if any effect
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
                b.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF);
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF);
                b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF);
                b.set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, false);

                // extra params for non-RAW
                b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
                //b.set(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector(1, 1, 1, 1));
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, new ColorSpaceTransform
                        (new int[]{1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1})); // identity matrix
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
                b.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(tonemap, tonemap, tonemap));


                App.log().append("camera initialization has succeeded.\n");

                requests.add(b.build());
            } catch (CameraAccessException e) {
                e.printStackTrace();
                return null;
            }
        }
        return requests;
    }


    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //This is called when the camera is open
            cdevice = camera;
            App.log().append("Camera is open.\n");
            createSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            App.log().append("Camera is closed.\n");
            camera_thread.quitSafely();
            frame_thread.quitSafely();
            try {
                camera_thread.join();
                frame_thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            cdevice.close();
            cdevice = null;
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            App.log().append("Camera in error! " + error + "\n");
            camera_thread.quitSafely();
            frame_thread.quitSafely();
            try {
                camera_thread.join();
                frame_thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            cdevice.close();
            cdevice = null;
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback(){
        @Override
        public void	onConfigured(@NonNull CameraCaptureSession session){
            App.log().append("Camera capture session configured.\n");
            csession = session;
            final List<CaptureRequest> requests = makeRequests(frame_producer.getSurfaces());
            if(requests == null) return;

            try {
                // do an initial capture to query the CaptureResult
                csession.capture(requests.get(0), initialCallback, frame_handler);

                // wait 3 seconds so the CPU can keep up with the buffers
                camera_handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (csession == null){ // run was stopped after posting.
                            return;
                        }
                        try {
                            if(N_ALLOC > 1)
                                csession.setRepeatingBurst(requests, frame_producer, frame_handler);
                            else
                                csession.setRepeatingRequest(requests.get(0), frame_producer, frame_handler);
                        } catch (CameraAccessException | NullPointerException e) {
                            e.printStackTrace();
                        }
                    }
                }, 3000L);

            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }


        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            App.log().append("Configure failed!  Switching to single-buffer mode");
            N_ALLOC = 1;
            createSession();
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            App.log().append("Camera capture session closed\n");
        }
    };


    public void configure(Config cfg) {
        yuv = cfg.getBoolean("yuv", false);
        if (yuv){
            min_duration = min_duration_yuv;
            N_ALLOC = 2;
        } else {
            min_duration = min_duration_raw;
            N_ALLOC = 1;
        }
        int iso_ref = cfg.getInteger("sensitivity_reference", max_analog);
        long exposure_ref = cfg.getLong("exposure_reference", max_exp);
        double iso_scale = cfg.getDouble("sensitivity_scale", 1.0);
        double exposure_scale = cfg.getDouble("exposure_scale", 1.0);
        double min_duration_scale = cfg.getDouble("min_duration_scale", 0.0);

        iso = (int) (iso_scale * iso_ref);
        exposure = (long) (exposure_scale * exposure_ref);

        if (min_duration < min_duration_scale * exposure_ref){
            min_duration = (long) (min_duration_scale * exposure_ref);
        }
        
        Log.i(TAG, "setting exposure to " + exposure);
        Log.i(TAG, "setting sensitivity to " + iso);

        String res_str = cfg.getString("resolution", null);
        if(yuv && res_str != null && res_str.matches("\\d+x\\d+")) {
            String[] res = cfg.getString("resolution", "").split("x");
            size = new Size(Integer.parseInt(res[0]), Integer.parseInt(res[1]));
        } else {
            size = yuv ? yuv_size : raw_size;
        }
        App.log().append("Size = " + size + "\n");

        int saturation = cfg.getInteger("saturation", 1023);
        if(saturation == 1023) {
            tonemap = new float[]{0f, 0f, 1f, 1f};
        } else {
            // linear up to saturation point
            tonemap = new float[]{0f, 0f, (float)(saturation+1)/1024, 1f, 1f, 1f};
        }

        configured = true;
    }

    public void start(Frame.OnFrameCallback callback) {
        if(!configured) {
            App.log().append("Camera not configured!\n");
            return;
        }

        frame_callback = callback;

        if(camera_thread == null || !camera_thread.isAlive()) {
            camera_thread = new HandlerThread("Camera");
            camera_thread.start();
            camera_handler = new Handler(camera_thread.getLooper());
        }
        if(frame_thread == null || !frame_thread.isAlive()) {
            frame_thread = new HandlerThread("Frames");
            frame_thread.start();
            frame_handler = new Handler(frame_thread.getLooper());
        }

        if(cdevice == null) {
            try {
                cmanager.openCamera(cid, deviceCallback, camera_handler);
            } catch (CameraAccessException | SecurityException e) {
                e.printStackTrace();
            }
        } else {
            createSession();
        }
    }

    public void stop() {
        configured = false;
        if (csession != null) {
            csession.close();
            csession = null;
        }
        if (frame_producer != null){
            frame_producer.stop();
        }
    }

    public void close(){
        if (cdevice != null) {
            cdevice.close();
            cdevice = null;
        }
        if (frame_producer != null){
            frame_producer.close();
            frame_producer = null;
        }
    }

    public int getResX() {
        return size.getWidth();
    }

    public int getResY() {
        return size.getHeight();
    }

    public int getISO() {
        return iso;
    }

    public long getExposure() {
        return exposure;
    }

    public CameraCharacteristics getCharacteristics() {
        return cchars;
    }

    private final CameraCaptureSession.CaptureCallback initialCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            StringBuilder sb = new StringBuilder();
            sb.append("capture complete with exposure " + result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    + " duration " + result.get(CaptureResult.SENSOR_FRAME_DURATION)
                    + " sensitivity " + result.get(CaptureResult.SENSOR_SENSITIVITY) + "\n")
                    .append("effective exposure factor:  " + result.get(CaptureResult.REPROCESS_EFFECTIVE_EXPOSURE_FACTOR) + "\n");

            TonemapCurve curve = result.get(CaptureResult.TONEMAP_CURVE);
            if(curve != null) {
                float saturation_x = 1f;
                for (int i = 0; i < curve.getPointCount(TonemapCurve.CHANNEL_GREEN); i++) {
                    PointF point = curve.getPoint(TonemapCurve.CHANNEL_GREEN, i);
                    if (point.y > 0.999f) {
                        saturation_x = point.x;
                        break;
                    }
                }

                sb.append("saturation point: " + (int) (saturation_x * 1023) + "\n");
            }
            sb.append("color gains:  " + result.get(CaptureResult.COLOR_CORRECTION_GAINS) + "\n")
                    .append("sRGB transform:" + result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) + "\n");

            Pair<Double, Double>[] noise_coeffs = result.get(CaptureResult.SENSOR_NOISE_PROFILE);
            if(noise_coeffs != null) {
                sb.append("noise_coeffs:\n");
                for (Pair<Double, Double> coeffs : noise_coeffs){
                    sb.append("first:  " + coeffs.first + " second:  " + coeffs.second + "\n");
                }
            }

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                float[] black_levels = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
                if(black_levels != null)
                    sb.append("black level:  " + black_levels[0] + ", " + black_levels[1] + ", " + black_levels[2] + "\n");
                sb.append("white level:  " + result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) + "\n");
            }

            App.log().append(sb.toString());
        }
    };

}
