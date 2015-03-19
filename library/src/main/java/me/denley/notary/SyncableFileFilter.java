package me.denley.notary;

public interface SyncableFileFilter {

    public boolean display(File file);
    public boolean autoSync(File file);

}
