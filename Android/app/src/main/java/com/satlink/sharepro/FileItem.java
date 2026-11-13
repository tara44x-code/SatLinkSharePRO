package com.satlink.sharepro;

import android.net.Uri;

public class FileItem {
    public final Uri uri;
    public final String name;
    public final long size;

    public FileItem(Uri uri, String name, long size) {
        this.uri = uri;
        this.name = name;
        this.size = size;
    }
}
