package me.denley.notary;

public class PendingFile extends File {

    public final FileTransaction transaction;

    PendingFile(String directory, FileTransaction transaction, int sourceFileIndex) {
        super(new java.io.File(directory, transaction.getSourceFileName(sourceFileIndex)).getAbsolutePath(), false, false, false);
        this.transaction = transaction;
    }

}
