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

/**
 * Created by mulhearn on 7/31/18.
 */

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
                        int arg_num = Integer.parseInt(tokens[1].substring(1, tokens[1].length()-1));
                        // okay if this throws NumberFormatException: should be handled by Macro
                        tokens[1] = args[arg_num];
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
            writer.write("# config\n");
            writer.write("# Fishstand Run Configuration File\n");
            writer.write("# Created on Run " + date + "\n\n");

            writer.write("tag # default_tag\n");

            writer.write("\n### camera ###\n");
            writer.write("num # 1\n");
            writer.write("sensitivity # iso value\n");
            writer.write("exposure # ns\n");
            writer.write("yuv # bool\n");
            writer.write("delay # ms\n");

            writer.write("\n### photo ###\n");
            writer.write("analysis # photo\n");
            writer.write("dimension # 256\n");
            writer.write("x_offset # 0\n");
            writer.write("y_offset # 0\n");

            writer.write("\n### pixelstats ###\n");
            writer.write("analysis # pixelstats\n");
            writer.write("filesize # 5000000\n");
            writer.write("samplefile # 171\n");

            writer.write("\n### cosmics ###\n");
            writer.write("analysis # cosmics\n");
            writer.write("pass_rate # .1\n");
            writer.write("max_n # 120\n");

            writer.flush();
            writer.close();
        } catch (Exception e){
            e.printStackTrace();
        }

    }

    private static File getFile(String filename) {
        File path = new File(Environment.getExternalStoragePublicDirectory(""),
                Storage.WORK_DIR);
        path.mkdirs();
        File cfg_file = new File(path, filename);

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
