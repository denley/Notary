package me.denley.notary;

import android.support.annotation.NonNull;

import java.util.Comparator;

@SuppressWarnings("unused")
public class File {

    public static Comparator<File> SORT_ALPHABETICAL = new Comparator<File>() {
        @Override public int compare(@NonNull File lhs, @NonNull File rhs) {
            return lhs.path.compareToIgnoreCase(rhs.path);
        }
    };

    public static Comparator<File> SORT_ALPHABETICAL_DIRECTORIES_FIRST = new Comparator<File>() {
        @Override public int compare(@NonNull File lhs, @NonNull File rhs) {
            if(lhs.isDirectory && !rhs.isDirectory) {
                return -1;
            } else if(!lhs.isDirectory && rhs.isDirectory) {
                return 1;
            } else {
                return lhs.path.compareToIgnoreCase(rhs.path);
            }
        }
    };

    public File(String directory, String fileName) {
        this(new java.io.File(directory, fileName));
    }

    public File(java.io.File file) {
        path = file.getAbsolutePath();
        isDirectory = file.isDirectory();
        canRead = file.canRead();
        canWrite = file.canWrite();
    }

    protected File(String path, boolean isDirectory, boolean canRead, boolean canWrite) {
        this.path = path;
        this.isDirectory = isDirectory;
        this.canWrite = canWrite;
        this.canRead = canRead;
    }

    public final String path;
    public final boolean isDirectory;
    public final boolean canRead;
    public final boolean canWrite;

    @NonNull public java.io.File getIoFile() {
        return new java.io.File(path);
    }

    public String getName() {
        return getIoFile().getName();
    }

    @NonNull public String getFileSuffix(){
        final String name = getName();
        final int dotPos = name.lastIndexOf(".");
        if(dotPos==-1) {
            return "";
        } else {
            return name.substring(dotPos + 1);
        }
    }

    @Override public boolean equals(Object o) {
        return o!=null && o instanceof File && ((File) o).path.equalsIgnoreCase(path);
    }

}
