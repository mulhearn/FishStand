package edu.ucdavis.crayfis.fishstand;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

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
        if (str != ""){
            return Integer.parseInt(str);
        }
        return def;
    }

    boolean getBoolean(String param, boolean def) {
        String str = getString(param, "");
        if (str != ""){
            return Boolean.parseBoolean(str);
        }
        return def;
    }

    void editConfig(Activity activity) {
        Uri uri = Uri.fromFile(getFile());
        //Uri uri = FileProvider.getUriForFile(App.getContext(), "edu.ucdavis.crayfis.fishstand.fileprovider", getFile());
        Intent intent = new Intent(Intent.ACTION_EDIT);
        intent.setDataAndType(uri, "text/plain");
        //intent.addFlags(FLAG_GRANT_WRITE_URI_PERMISSION);
        PackageManager packageManager = activity.getPackageManager();
        if (intent.resolveActivity(packageManager) != null) {
            activity.startActivity(intent);
        }
    }

    void parseConfig(){
        params = new ArrayList<String>();
        values = new ArrayList<String>();

        File config_file = getFile();

        if (! config_file.exists()) {
            try {
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(config_file));
                writeDefault(bos);
            } catch (Exception e){
                Log.e(TAG, e.getMessage());
            }
        }

        try {
            InputStream input = new FileInputStream(config_file);
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
        return new File(path, filename);
    }


    }
