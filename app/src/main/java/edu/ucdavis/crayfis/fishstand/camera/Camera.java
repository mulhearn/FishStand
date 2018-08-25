package edu.ucdavis.crayfis.fishstand.camera;

import java.nio.ShortBuffer;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;


import android.content.Context;
import android.hardware.camera2.CameraMetadata;
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
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.support.annotation.NonNull;
import android.util.Range;
import android.util.SizeF;
import android.util.Size;
import android.view.Surface;

import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Config;


public class Camera {

    private static final String TAG = "Camera";

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

    private ImageReader ireader;
    private Surface surface;

    private Frame.Producer frame_producer;
    private Frame.OnFrameCallback frame_callback;

    private int max_images=10;

    // discovered camera properties for RAW format at highest resolution
    private long min_exp=0;
    private long max_exp=0;
    private int min_sens=0;
    private int max_sens=0;
    private int max_analog=0;

    private boolean yuv;
    private Size raw_size;
    private int iso;
    private long exposure;


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
            } else {
                App.log().append("Could not find camera device with sufficient capabilities.  Cannot init.");
            }
        } catch (CameraAccessException | SecurityException e) {
            e.printStackTrace();
        }
    }

    private void create_session() {
        App.log().append("Creating capture session\n");

        if (yuv) {
            frame_producer = null;
            surface = null;
        } else {
            ireader = ImageReader.newInstance(raw_size.getWidth(), raw_size.getHeight(), ImageFormat.RAW_SENSOR, max_images);
            surface = ireader.getSurface();
            frame_producer = new RawFrame.Producer(ireader, frame_handler, frame_callback);
        }

        List<Surface> outputs = new ArrayList<>(1);
        outputs.add(surface);
        try {
            cdevice.createCaptureSession(outputs, sessionCallback, camera_handler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //This is called when the camera is open
            cdevice = camera;
            App.log().append("Camera is open.\n");
            create_session();
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
            try {
                CaptureRequest.Builder b = cdevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(surface);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f); // put focus at infinity
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF); // need to see if any effect
                b.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF); // need to see if any effect!
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF); // need to see if any effect!

                App.log().append("camera initialization has succeeded.\n");

                csession.setRepeatingRequest(b.build(), frame_producer.getCaptureCallback(), frame_handler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            App.log().append("Camera capture session closed\n");
        }
    };

    public void start(Frame.OnFrameCallback callback, Config cfg) {

        frame_callback = callback;

        yuv = cfg.getBoolean("yuv", false);
        int iso_ref = cfg.getInteger("sensitivity_reference", max_analog);
        long exposure_ref = cfg.getLong("exposure_reference", max_exp);
        double iso_scale = cfg.getDouble("sensitivity_scale", 1.0);
        double exposure_scale = cfg.getDouble("exposure_scale", 1.0);
        iso = (int) (iso_scale * iso_ref);
        exposure = (long) (exposure_scale * exposure_ref);

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
            create_session();
        }

    }

    public void stop(boolean quit) {
        csession.close();
        if(ireader != null) {
            ireader.close();
            ireader = null;
        }
        if (frame_producer != null){
            frame_producer.stop();
        }
        if(quit) {
            cdevice.close();
            cdevice = null;
        }
    }

    public int getResX() {
        return raw_size.getWidth();
    }

    public int getResY() {
        return raw_size.getHeight();
    }

    public int getISO() {
        return iso;
    }

    public long getExposure() {
        return exposure;
    }

}
