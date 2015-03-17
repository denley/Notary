package me.denley.notary;

public class SyncedFile extends File {

    public SyncedFile(String directory, FileTransaction transaction) {
        super(directory, transaction.getSourceFileName());
    }

    public SyncedFile(java.io.File file) {
        super(file);
    }

}
