package me.denley.notary;

public class SyncedFile extends File {

    public SyncedFile(String directory, FileTransaction transaction, int sourceIndex) {
        super(directory, transaction.getSourceFileName(sourceIndex));
    }

    public SyncedFile(java.io.File file) {
        super(file);
    }

}
