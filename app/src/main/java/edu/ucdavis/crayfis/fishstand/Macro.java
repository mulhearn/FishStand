package edu.ucdavis.crayfis.fishstand;

import android.os.Environment;
import androidx.annotation.Nullable;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class Macro {

    private static final String TAG = "Macro";

    private final List<String> lines;
    private int current_line = -1;
    private final Deque<Integer> repeats = new ArrayDeque<>();
    private final HashMap<String, String> for_vars = new HashMap<>();
    private final Deque<Pair<String, Deque<String>>> for_vals = new ArrayDeque<>();
    private final Deque<Integer> loop_lines =  new ArrayDeque<>();

    private final String FILE_NAME;

    private Config next_config;

    private Macro(String name, ArrayList<String> lines) {
        this.lines = lines;
        this.FILE_NAME = name;
    }

    @Nullable
    public static Macro create(String filename) {
        if (!filename.endsWith(".mac")) throw new IllegalArgumentException("Invalid file name");

        File path = new File(Environment.getExternalStoragePublicDirectory(""),
                Storage.WORK_DIR);
        path.mkdirs();
        File cfg_file = new File(path, filename);

        if (cfg_file.exists()) {

            try {
                InputStream input = new FileInputStream(cfg_file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input));

                ArrayList<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    Log.d(TAG, line);
                }

                return new Macro(filename, lines);

            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        return null;
    }

    public Config next() {

        // see if we already found next config with hasNext()
        if(next_config != null) {
            Config cfg = next_config;
            next_config = null;
            return cfg;
        }

        while (true) {
            current_line++;
            //Log.i(TAG, "current line:  " + current_line);
            if(current_line >= lines.size()) return null;

            String line = lines.get(current_line);
            if(line.startsWith("#") || line.startsWith("!") || line.trim().isEmpty()) continue;

            String[] tokens = line.split("\\s+", 0);
            for(int i=0; i<tokens.length; i++) {
                if(tokens[i].startsWith("$")) {
                    // interpret this as a variable
                    tokens[i] = for_vars.get(tokens[i].substring(1));
                    if(tokens[i] == null)
                        throw new IllegalArgumentException();
                }
            }

            try {
                switch (tokens[0].toLowerCase()) {
                    case "run":
                        String[] args = Arrays.copyOfRange(tokens,2, tokens.length);
                        Config cfg = new Config(tokens[1], args);
                        return cfg;
                    case "goto":
                        // -2 for zero-indexing and increment at start of loop
                        current_line = Integer.parseInt(tokens[1]) - 2;
                        break;
                    case "repeat":
                        repeats.addFirst(Integer.parseInt(tokens[1]) - 1);
                        loop_lines.addFirst(current_line);
                        break;
                    case "endrepeat":
                        int count = repeats.pollFirst();
                        if(count > 0) {
                            repeats.addFirst(count-1);
                            // for increment at start of loop: should go to line after repeat
                            current_line = loop_lines.getFirst();
                        } else {
                            loop_lines.pollFirst();
                        }
                        break;
                    case "for":
                        String var = tokens[1];
                        if(!tokens[2].equalsIgnoreCase("in"))
                            throw new IllegalArgumentException();
                        Deque<String> deque= new ArrayDeque<>(Arrays.asList(tokens));
                        for(int i=0; i<3; i++) deque.pollFirst();

                        for_vars.put(var, deque.pollFirst());
                        for_vals.addFirst(new Pair<>(var, deque));
                        loop_lines.addFirst(current_line);
                        break;

                    case "endfor":
                        Pair<String, Deque<String>> pair = for_vals.pollFirst();
                        if(pair.second.size() > 0) {
                            // still more values
                            //String newVal = pair.second.pollFirst();
                            //Log.d(TAG, "Setting to " + newVal);
                            for_vars.put(pair.first, pair.second.pollFirst());
                            for_vals.addFirst(pair);
                            current_line = loop_lines.getFirst();
                        } else {
                            // get rid of variable
                            for_vars.remove(pair.first);
                            loop_lines.pollFirst();
                        }
                        break;

                    default:
                        throw new IllegalArgumentException();

                }

            } catch (Exception e) {
                e.printStackTrace();

                lines.set(current_line, "! " + line);

                // overwrite file
                File path = new File(Environment.getExternalStoragePublicDirectory(""),
                        Storage.WORK_DIR);
                path.mkdirs();
                File cfg_file = new File(path, FILE_NAME);
                try {
                    OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(cfg_file));
                    for(String l : lines) {
                        writer.write(l + "\n");
                    }
                    writer.flush();
                    writer.close();
                } catch (Exception e2) {
                    e2.printStackTrace();
                }

            }
        }
    }

    public boolean hasNext() {
        if(next_config != null) return true;
        next_config = next();
        return next_config != null;
    }

    static void writeDefault(FileOutputStream out) {
        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());
        Writer writer = new OutputStreamWriter(out);
        try {
            writer.write("# macro\n");
            writer.write("# Fishstand Configuration Macro File\n");
            writer.write("# Created on Run " + date + "\n");
            writer.write("#\n");
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
