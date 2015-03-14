package me.denley.notary;

import java.io.Serializable;

public class FileListContainer implements Serializable {

    public static final int SUCCESS = 0;
    public static final int ERROR_DIRECTORY_NOT_FOUND = 1;


    int outcome = SUCCESS;
    String directory;
    String[] files;
    boolean[] isDirectory;

}
