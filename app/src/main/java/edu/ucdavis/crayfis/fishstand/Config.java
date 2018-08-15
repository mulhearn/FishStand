package edu.ucdavis.crayfis.fishstand;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

/**
 * Created by mulhearn on 7/31/18.
 */

public class Config {
    private static final String TAG = "Config";
    List<String> params = null; //new ArrayList<String>();
    List<String> values = null; //new ArrayList<String>();

    void logConfig() {
        for (int i = 0; i < params.size(); i++) {
            App.log().append("parameter " + params.get(i) + ":  " + values.get(i) + "\n");
        }
    }

    String getString(String param, String def){
        if (params.contains(param)) {
            int index = params.indexOf(param);
            return values.get(index);
        }
        return def;
    }

    int getInteger(String param, int def) {
        String str = getString(param, "");
        if (!str.isEmpty()){
            return Integer.parseInt(str);
        }
        return def;
    }

    boolean getBoolean(String param, boolean def) {
        String str = getString(param, "");
        if (!str.isEmpty()){
            return Boolean.parseBoolean(str);
        }
        return def;
    }

    void editConfig(Context context) {
        Intent intent = new Intent(Intent.ACTION_EDIT);
        final Uri uri;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(context,
                    "edu.ucdavis.crayfis.fishstand.provider",
                    getFile());
        } else {
            uri = Uri.fromFile(getFile());
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

    void parseConfig(){
        params = new ArrayList<String>();
        values = new ArrayList<String>();

        try {
            InputStream input = new FileInputStream(getFile());
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.split("#")[0]; // remove comments
                String[] tokens = line.split("\\s+", 2);  // tokenize by first whitespace
                if ((tokens.length > 1) && (tokens[0].length() > 0)) {
                    Log.i(TAG, "parameter:  " + tokens[0] + " value:  " + tokens[1]);
                    params.add(tokens[0]);
                    values.add(tokens[1]);
                }
            }
        } catch (Exception e){
            Log.e(TAG, e.getMessage());
        }
    }

    private void writeDefault(OutputStream out){
        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());
        Writer writer = new OutputStreamWriter(out);
        try {
            writer.write("# config\n");
            writer.write("# Fishstand Run Configuration File\n");
            writer.write("# Created on Run " + date + "\n");
            writer.write("tag initial\n");
            writer.write("num 1\n");
            writer.write("repeat false\n");
            writer.write("analysis none\n");
            writer.write("delay 0\n");
            writer.write("yuv false\n");
            writer.flush();
        } catch (Exception e){
            Log.e(TAG, e.getMessage());
        }

    }

    private File getFile() {
        File path = new File(Environment.getExternalStoragePublicDirectory(""),
                Storage.WORK_DIR);
        path.mkdirs();
        final String filename = "config.txt";
        File cfg_file = new File(path, filename);

        if (! cfg_file.exists()) {
            try {
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(cfg_file));
                writeDefault(bos);
            } catch (Exception e){
                Log.e(TAG, e.getMessage());
            }
        }

        return cfg_file;
    }

}
