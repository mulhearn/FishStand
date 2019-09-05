package edu.ucdavis.crayfis.fishstand.camera;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;


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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.params.TonemapCurve;
import android.graphics.ImageFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.support.annotation.NonNull;
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
    private final List<CaptureRequest> crequests = new ArrayList<>();

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
    private boolean refresh;

    private boolean configured = false;

    private long baseTimeMillis;


    public Camera() {
        App.log().append("init started at "
                + DateFormat.getDateTimeInstance().format(new Date(System.currentTimeMillis())));
        cmanager = (CameraManager) App.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            cid = "";
            ArrayList<String> summary = new ArrayList<>();
            for (String id : cmanager.getCameraIdList()) {
                summary.add("camera id string:  " + id);
                CameraCharacteristics chars = cmanager.getCameraCharacteristics(id);
                // Does the camera have a forwards facing lens?
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);

                summary.add("facing:  " + facing);
                SizeF fsize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                Size isize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                summary.add("physical sensor size (w x h):  " + fsize.getWidth() + " mm x " + fsize.getHeight() + " mm");
                summary.add("pixel array size (w x h):  " + isize.getWidth() + " x " + isize.getHeight());

                //check for needed capabilities:
                Boolean manual_mode = false;
                Boolean raw_mode = false;
                int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                String capsSummary = "caps:  ";
                if(caps != null) {
                    for (int i : caps) {
                        capsSummary += i + ", ";
                        if (i == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                            manual_mode = true;
                        }
                        if (i == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                            raw_mode = true;
                        }
                    }
                } else {
                    capsSummary += "None";
                }
                summary.add(capsSummary);

                summary.add("RAW mode support:  " + raw_mode);
                summary.add("Manual mode support:  " + manual_mode);
                if ((cid.isEmpty()) && (facing == 1) && raw_mode && manual_mode) {
                    cid = id;
                    break;
                }
            }

            if (!cid.isEmpty()) {
                summary.add("selected camera ID " + cid);
                cchars = cmanager.getCameraCharacteristics(cid);
                //Size isize = cchars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                StreamConfigurationMap configs = cchars.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Boolean raw_available = false;
                int[] fmts = configs.getOutputFormats();
                for (int fmt : fmts) {
                    if (fmt == ImageFormat.RAW_SENSOR) raw_available = true;
                }
                if (!raw_available && !yuv) {
                    summary.add("RAW_SENSOR format not available.  Cannot init...");
                    return;
                }

                int maxprod;
                Size[] sizes;

                sizes = configs.getOutputSizes(ImageFormat.RAW_SENSOR);
                String rawSizeSummary = "RAW format available sizes:  ";
                maxprod = 0;
                for (Size s : sizes) {
                    int h = s.getHeight();
                    int w = s.getWidth();
                    int p = h * w;
                    rawSizeSummary += w + " x " + h + ",";
                    if (p > maxprod) {
                        maxprod = p;
                        raw_size = s;
                    }
                }
                summary.add(rawSizeSummary);
                summary.add("Largest size is " + raw_size);

                min_duration_raw = configs.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, raw_size);
                summary.add("with minimum frame duration " + min_duration_raw);

                sizes = configs.getOutputSizes(Allocation.class);
                String yuvSizeSummary = "YUV format available sizes:  ";
                maxprod = 0;
                for (Size s : sizes) {
                    int h = s.getHeight();
                    int w = s.getWidth();
                    int p = h * w;
                    yuvSizeSummary += w + " x " + h + ",";
                    if (p > maxprod) {
                        maxprod = p;
                        yuv_size = s;
                    }
                }
                summary.add(yuvSizeSummary);
                summary.add("Largest size is " + yuv_size);
                min_duration_yuv = configs.getOutputMinFrameDuration(ImageFormat.YUV_420_888, yuv_size);
                summary.add("with minimum frame duration " + min_duration_yuv);

                String flSummary = "focal lengths:  ";
                for(float fl: cchars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)){
                    flSummary += fl + ", ";
                }
                summary.add(flSummary);

                String color_mode = "ON";
                for(int n: cchars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)){
                    if(n == CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF) {
                        color_mode = "OFF";
                        break;
                    }
                }
                summary.add("color correction mode: " + color_mode);

                String noise_mode = "ON";
                for(int n: cchars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)){
                    if(n == CameraMetadata.NOISE_REDUCTION_MODE_OFF) {
                        noise_mode = "OFF";
                        break;
                    }
                }
                summary.add("noise reduction mode: " + noise_mode);

                String hotpixel_mode = "ON";
                for(int n: cchars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)){
                    if(n == CameraMetadata.HOT_PIXEL_MODE_OFF) {
                        hotpixel_mode = "OFF";
                        break;
                    }
                }
                summary.add("hotpixel mode: " + hotpixel_mode);

                String edge_mode = "ON";
                for(int n: cchars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)){
                    if(n == CameraMetadata.HOT_PIXEL_MODE_OFF) {
                        edge_mode = "OFF";
                        break;
                    }
                }
                summary.add("edge mode: " + edge_mode);

                String tonemap_control = "DISABLED";
                for(int n: cchars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)){
                    if(n == CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) {
                        tonemap_control = "ENABLED";
                        break;
                    }
                }
                summary.add("tonemap control: " + tonemap_control);


                Range<Long> rexp = cchars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                min_exp = rexp.getLower();
                max_exp = rexp.getUpper();
                summary.add("Exposure range:  " + min_exp*1E-6 + " to " + max_exp*1E-6 + " ms");
                // set default selected exposure to maximum:

                Range<Integer> rsens = cchars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                min_sens = rsens.getLower();
                max_sens = rsens.getUpper();
                summary.add("Sensitivity range:  " + min_sens + " to " + max_sens + " (ISO)");

                max_analog = cchars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
                summary.add("Max Analog Sensitivity:  " + max_analog);

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
                summary.add("Color filter arrangement:  " + filter_str);
                summary.add("Camera settings initialized");

            } else {
                summary.add("Could not find camera device with sufficient capabilities.  Cannot init.");
            }

            App.log().append(summary);
        } catch (CameraAccessException | SecurityException e) {
            e.printStackTrace();
        }
    }

    private void createSession() {

        if (yuv) {
            App.log().append("Creating capture session: using YUV image format.");
            frame_producer = new YuvFrame.Producer(N_ALLOC, size, frame_handler, frame_callback);
        } else {
            App.log().append("Creating capture session: using RAW image format.");
            frame_producer = new RawFrame.Producer(N_ALLOC, raw_size, frame_handler, frame_callback);
        }

        List<Surface> outputs = frame_producer.getSurfaces();
        try {
            cdevice.createCaptureSession(outputs, sessionCallback, camera_handler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void makeRequests(List<Surface> surfaces) {
        crequests.clear();

        try {
            CaptureRequest.Builder b = cdevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

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

            for(Surface s: surfaces) {
                b.addTarget(s);
                crequests.add(b.build());
            }

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        App.log().append("camera initialization has succeeded.");
    }


    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //This is called when the camera is open
            cdevice = camera;
            App.log().append("Camera is open.");
            createSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            App.log().append("Camera is closed.");
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
            App.updateState(App.STATE.STOPPING);
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            App.log().append("Camera in error! " + error);
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
            App.updateState(App.STATE.STOPPING);
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback(){
        @Override
        public void	onConfigured(@NonNull CameraCaptureSession session){
            App.log().append("Camera capture session configured");
            csession = session;
            makeRequests(frame_producer.getSurfaces());

            if(crequests.isEmpty()) {
                App.log().append("Unable to make camera request");
            }

            try {
                // do an initial capture to query the CaptureResult
                csession.capture(crequests.get(0), initialCallback, frame_handler);
                stream();

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
            App.log().append("Camera capture session closed");
        }
    };

    private void stream() {
        // wait 3 seconds so the CPU can keep up with the buffers
        camera_handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (csession == null){ // run was stopped after posting.
                    return;
                }
                // endpoint for start of stream
                App.log().append(getStatus());
                try {
                    if(N_ALLOC > 1)
                        csession.setRepeatingBurst(crequests, frame_producer, frame_handler);
                    else
                        csession.setRepeatingRequest(crequests.get(0), frame_producer, frame_handler);
                } catch (CameraAccessException | NullPointerException e) {
                    e.printStackTrace();
                }
            }
        }, 3000L);
    }


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
        App.log().append("Size = " + size);

        int saturation = cfg.getInteger("saturation", 1023);
        if(saturation == 1023) {
            tonemap = new float[]{0f, 0f, 1f, 1f};
        } else {
            // linear up to saturation point
            tonemap = new float[]{0f, 0f, (float)(saturation+1)/1024, 1f, 1f, 1f};
        }

        refresh = cfg.getBoolean("refresh", true);

        configured = true;
    }

    public void start(Frame.OnFrameCallback callback) {
        if(!configured) {
            App.log().append("Camera not configured!");
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
        App.log().append(getStatus());
    }

    public void refresh() {
        if(refresh && csession != null) {
            try {
                // clear session and restart
                csession.stopRepeating();
                csession.abortCaptures();
                stream();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        // don't count this frame towards total
        frame_producer.matches--;
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

    public long getBaseTime() {
        return baseTimeMillis;
    }

    public String getStatus() {
        return "matched: " + frame_producer.matches + ", "
                + "dropped: " + frame_producer.dropped_images + " images / "
                + frame_producer.result_collector.dropped() + " results";
    }

    private final CameraCaptureSession.CaptureCallback initialCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                     @NonNull CaptureRequest request,
                                     long timestamp, long frameNumber) {
            baseTimeMillis = System.currentTimeMillis() - timestamp / 1000000L;
            Log.d(TAG, "base = " + baseTimeMillis);
        }


        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            ArrayList<String> captureSummary = new ArrayList<>();
            captureSummary.add("capture complete with exposure " + result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    + " duration " + result.get(CaptureResult.SENSOR_FRAME_DURATION)
                    + " sensitivity " + result.get(CaptureResult.SENSOR_SENSITIVITY));
            captureSummary.add("effective exposure factor:  " + result.get(CaptureResult.REPROCESS_EFFECTIVE_EXPOSURE_FACTOR));

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

                captureSummary.add("saturation point: " + (int) (saturation_x * 1023) + "\n");
            }
            captureSummary.add("color gains:  " + result.get(CaptureResult.COLOR_CORRECTION_GAINS));
            captureSummary.add("sRGB transform:" + result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM));

            Pair<Double, Double>[] noise_coeffs = result.get(CaptureResult.SENSOR_NOISE_PROFILE);
            if(noise_coeffs != null) {
                captureSummary.add("noise_coeffs:");
                for (Pair<Double, Double> coeffs : noise_coeffs){
                    captureSummary.add("first:  " + coeffs.first + " second:  " + coeffs.second);
                }
            }

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                float[] black_levels = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
                if(black_levels != null)
                    captureSummary.add("black level:  " + black_levels[0] + ", " + black_levels[1] + ", " + black_levels[2]);
                captureSummary.add("white level:  " + result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL));
            }

            App.log().append(captureSummary);
        }
    };

}
