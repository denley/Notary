package me.denley.notary;

public interface FileListener {

    public void onSourceFileStatusChanged(FileTransaction transaction, int indexChanged);

    public void onDestinationFileStatusChanged(FileTransaction transaction, int indexChanged);

    public void onDeleteTransactionSuccess(FileTransaction transaction, int indexChanged);

}
