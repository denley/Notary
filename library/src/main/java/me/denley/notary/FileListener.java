package me.denley.notary;

public interface FileListener {

    public void onSourceFileStatusChanged(FileTransaction transaction);

    public void onDestinationFileStatusChanged(FileTransaction transaction);

}
