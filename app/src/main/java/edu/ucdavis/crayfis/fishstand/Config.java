package edu.ucdavis.crayfis.fishstand;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class Config {
    private static final String TAG = "Config";
    private final HashMap<String, String> config_map = new HashMap<>();

    public Config(String filename, String[] args) {
        if(!filename.endsWith(".cfg")) throw new IllegalArgumentException("Invalid config file");

        try {
            InputStream input = new FileInputStream(getFile(filename));
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.split("#")[0]; // remove comments
                String[] tokens = line.split("\\s+", 2);  // tokenize by first whitespace
                if (tokens.length > 1 && !tokens[0].isEmpty() && !tokens[1].isEmpty()) {
                    Log.i(TAG, "parameter:  " + tokens[0] + " value:  " + tokens[1]);
                    if(tokens[1].matches("\\{\\d+\\}")) {
                        Log.i(TAG, "argument reference detected");
                        int arg_num = Integer.parseInt(tokens[1].substring(1, tokens[1].length()-1));
                        Log.i(TAG, "argument index:  " + arg_num);
                        // okay if this throws NumberFormatException: should be handled by Macro
                        tokens[1] = args[arg_num];
                    } else {
                        Log.i(TAG, "not argument reference:  " + tokens[1]);
                    }
                    config_map.put(tokens[0], tokens[1]);
                }
            }
        } catch (IOException e){
            e.printStackTrace();
        }
    }

    public Config(String filename) {
        this(filename, null);
    }

    public void logConfig() {
        for(Map.Entry<String, String> entry: config_map.entrySet()) {
            App.log().append("parameter " + entry.getKey() + ":  " + entry.getValue() + "\n");
        }
    }

    public String getString(String param, String def){
        String val = config_map.get(param);
        if(val == null) return def;
        return val;
    }

    public int getInteger(String param, int def) {
        String str = getString(param, "");
        if (!str.isEmpty()){
            return Integer.parseInt(str);
        }
        return def;
    }

    public int[] getIntegerArray(String param, int[] def) {
        String str = getString(param, "");
        if (!str.isEmpty()){
            String[] tokens = str.split("\\s+");  // tokenize by whitespace
            int dat[] = new int[tokens.length];
            for (int i=0; i<dat.length; i++){
                dat[i] = Integer.parseInt(tokens[i]);
            }
            return dat;
        }
        return def;
    }

    public boolean getBoolean(String param, boolean def) {
        String str = getString(param, "");
        if (!str.isEmpty()){
            return Boolean.parseBoolean(str);
        }
        return def;
    }

    public long getLong(String param, long def) {
        String str = getString(param, "");
        if (!str.isEmpty()) {
            // for scientific notation
            return Double.valueOf(str).longValue();
        }
        return def;
    }

    public double getDouble(String param, double def) {
        String str = getString(param, "");
        if(!str.isEmpty()) {
            return Double.parseDouble(str);
        }
        return def;
    }

    public static void editConfig(Context context, String filename) {
        Intent intent = new Intent(Intent.ACTION_EDIT);
        final Uri uri;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(context,
                    "edu.ucdavis.crayfis.fishstand.provider",
                    getFile(filename));
        } else {
            uri = Uri.fromFile(getFile(filename));
        }

        intent.setDataAndType(uri, "text/plain");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        PackageManager packageManager = context.getPackageManager();
        if (intent.resolveActivity(packageManager) != null) {
            context.startActivity(intent);
        } else {
            Toast.makeText(App.getContext(), "No text editor found", Toast.LENGTH_LONG)
                    .show();
        }
    }

    private static void writeDefault(OutputStream out){
        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());
        Writer writer = new OutputStreamWriter(out);
        try {
            writer.append("# config\n")
                    .append("# Fishstand Run Configuration File\n")
                    .append("# Created on Run " + date + "\n\n")

                    .append("tag # default_tag\n")

                    .append("\n### camera ###\n")
                    .append("num # 1\n")
                    .append("sensitivity_reference # iso value, defaults to max analog\n")
                    .append("exposure_reference # ns, defaults to maximum exposure\n")
                    .append("sensitivity_scale # 1.0 \n")
                    .append("exposure_scale # 1.0\n")
                    .append("delay # ms\n")

                    .append("\n### additional formats ###\n")
                    .append("yuv # bool\n")
                    .append("resolution # RESxRES, defaults to max\n")
                    .append("saturation # 1023\n")

                    .append("\n### processing ###\n")
                    .append("gzip # 0-9\n")
                    .append("frame_threads # 3\n")
                    .append("frame_keep_alive_time # ms\n")
                    .append("upload_threads # 3\n")
                    .append("upload_keep_alive_time # ms\n")

                    .append("\n### photo ###\n")
                    .append("analysis # photo\n")
                    .append("dimension # 256\n")
                    .append("x_offset # 0\n")
                    .append("y_offset # 0\n")

                    .append("\n### pixelstats ###\n")
                    .append("analysis # pixelstats\n")
                    .append("filesize # 5000000\n")
                    .append("samplefile # 171\n")

                    .append("\n### cosmics ###\n")
                    .append("analysis # cosmics\n")
                    .append("num_zerobias # 10\n")
                    .append("raw_thresh # 10\n")
                    .append("threshold # 10 ...\n")
                    .append("prescale # 100 ...\n")
                    .append("images_per_file # 1000\n")
                    .append("max_pixel # 100 (can't exceed 100)\n")
                    .append("region_dx # 2\n")
                    .append("region_dy # 2\n")

                    .append("\n### triggered_image ###\n")
                    .append("analysis # triggered_image\n")
                    .append("sample_frac # 1.0 = mean (default), 0.0 = max\n")
                    .append("thresh # 0.0\n")
                    .append("zero_bias # 0 (or every n frames)");

            writer.flush();
            writer.close();
        } catch (Exception e){
            e.printStackTrace();
        }

    }

    private static File getFile(String filename) {
        File cfg_file = Storage.getFile(filename);

        if (! cfg_file.exists()) {
            try {
                FileOutputStream out = new FileOutputStream(cfg_file);
                if(filename.endsWith(".cfg"))
                    writeDefault(out);
                else
                    Macro.writeDefault(out);
            } catch (Exception e){
                e.printStackTrace();
            }
        }

        return cfg_file;
    }

}
