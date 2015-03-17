package me.denley.notary;

public class File {

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

    public String getName() {
        return new java.io.File(path).getName();
    }

    @Override public boolean equals(Object o) {
        return o!=null && o instanceof File && ((File) o).path.equalsIgnoreCase(path);
    }

}
