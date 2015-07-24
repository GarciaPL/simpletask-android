/**
 * This file is part of Todo.txt Touch, an Android app for managing your todo.txt file (http://todotxt.com).
 * <p/>
 * Copyright (c) 2009-2012 Todo.txt contributors (http://todotxt.com)
 * <p/>
 * LICENSE:
 * <p/>
 * Todo.txt Touch is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.
 * <p/>
 * Todo.txt Touch is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 * <p/>
 * You should have received a copy of the GNU General Public License along with Todo.txt Touch.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * @author Todo.txt contributors <todotxt@yahoogroups.com>
 * @license http://www.gnu.org/licenses/gpl.html
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 */
package nl.mpcjanssen.simpletask.util;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v4.provider.DocumentFile;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;

import java.io.*;

public class TaskIo {
    private final static String TAG = TaskIo.class.getSimpleName();

    @NonNull
    public static String loadFromFile(@NonNull File file, LineProcessor<String> lineProc) throws IOException {
        return Files.readLines(file, Charsets.UTF_8, lineProc);
    }

    public static void writeToFile(@NonNull String contents, @NonNull Context ctx,  @NonNull DocumentFile out, boolean append) throws IOException {
        Util.createParentDirectory(out);
        OutputStream os;
        if (append) {
            os = ctx.getContentResolver().openOutputStream(out.getUri(), "wa");
        } else {
            os = ctx.getContentResolver().openOutputStream(out.getUri(), "w");
        }

        Writer fw = new BufferedWriter(new OutputStreamWriter(
                os, "UTF-8"));
        fw.write(contents);
        fw.close();
        os.close();
    }
}

