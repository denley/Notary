package me.denley.notary;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;

import java.util.List;

public class DirectoryObserver implements FileListener {

    private final Context context;

    private final FileListAdapter adapter;
    private final List<File> files;

    private final String observedPath;
    private final FileObserver fileSystemObserver;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String localNodeId;

    public DirectoryObserver(@NonNull final Context context, @NonNull final FileListAdapter adapter, @NonNull final String path) {
        this.context = context;
        this.adapter = adapter;
        files = adapter.getFiles();
        observedPath = path;

        fileSystemObserver = new FileObserver(observedPath) {
            @Override public void onEvent(final int event, final String path) {
                if(path!=null) {
                    handler.post(new Runnable() {
                        public void run() {
                            onFileSystemEvent(event, path);
                        }
                    });
                }
            }
        };

        new Thread() {
            public void run() {
                localNodeId = NotaryWearableListenerService.getLocalNode(context).getId();
                loadInitialFileList();
                fileSystemObserver.startWatching();
                Notary.registerFileListener(DirectoryObserver.this, path, localNodeId);
            }
        }.start();
    }

    public void stopObserving() {
        fileSystemObserver.stopWatching();
        Notary.unregisterFileListener(this);
    }

    private void loadInitialFileList() {
        final List<PendingFile> transactions = Notary.getTransactionsForDirectory(context, observedPath);
        for(PendingFile file:transactions) {
            if (file.transaction.getStatus()==FileTransaction.STATUS_IN_PROGRESS) {
                files.add(file);
            }
        }

        final java.io.File directory = new java.io.File(observedPath);
        if(!directory.isDirectory()) {
            throw new IllegalArgumentException("Path does not represent a directory");
        }
        for(java.io.File localFile:directory.listFiles()) {
            final File file = new File(localFile);
            if(!files.contains(file)) {
                files.add(file);
            }
        }

        displayUpdatedFileList();

        Notary.requestFileList(context, new Notary.FileListCallback() {
            @Override public void success(FileListContainer fileList) {
                if(fileList.outcome==FileListContainer.SUCCESS) {
                    for (String path : fileList.files) {
                        final String fileName = new java.io.File(path).getName();
                        final SyncedFile syncedFile = new SyncedFile(new java.io.File(observedPath, fileName));

                        final int position = files.indexOf(syncedFile);
                        if (position != -1) {
                            files.remove(position);
                            files.add(position, syncedFile);
                        }
                    }
                }
                displayUpdatedFileList();
            }
            @Override public void failure(ConnectionResult result) { }
        });
    }

    private void displayUpdatedFileList() {
        // TODO sort files

        handler.post(new Runnable(){
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private synchronized void onFileSystemEvent(int event, String path) {
        final File updatedFile = new File(observedPath, path);

        switch(event) {
            case FileObserver.ACCESS:
            case FileObserver.MODIFY:
            case FileObserver.ATTRIB:
            case FileObserver.MOVED_TO:
            case FileObserver.CREATE:
                if(files.contains(updatedFile)) {
                    final int position = files.indexOf(updatedFile);
                    files.remove(position);
                    files.add(position, updatedFile);
                    adapter.notifyItemChanged(position);
                } else {
                    files.add(updatedFile);
                    adapter.notifyItemInserted(files.size() - 1);
                }
                break;
            case FileObserver.DELETE:
            case FileObserver.MOVED_FROM:
                if(files.contains(updatedFile)) {
                    final int position = files.indexOf(updatedFile);
                    files.remove(position);
                    adapter.notifyItemRemoved(position);
                }
                break;
            case FileObserver.DELETE_SELF:
            case FileObserver.MOVE_SELF:
                stopObserving();
                files.clear();
                adapter.notifyDataSetChanged();
                break;
        }
    }

    @Override public void onSourceFileStatusChanged(final FileTransaction transaction) {
        handler.post(new Runnable() {
            public void run() {
                updateForTransaction(transaction);
            }
        });
    }

    @Override public void onDestinationFileStatusChanged(final FileTransaction transaction) {
        handler.post(new Runnable() {
            public void run() {
                updateForTransaction(transaction);
            }
        });
    }

    private void updateForTransaction(FileTransaction transaction) {
        final File file;
        if(transaction.getStatus()==FileTransaction.STATUS_COMPLETE) {
            file = new SyncedFile(observedPath, transaction);
        } else {
            file = new PendingFile(observedPath, transaction);
        }

        if(files.contains(file)) {
            final int position = files.indexOf(file);
            files.remove(position);
            files.add(position, file);
            adapter.notifyItemChanged(position);
        } else if(!localNodeId.equals(transaction.sourceNode) || !transaction.hasDeleted()) {
            files.add(file);
            adapter.notifyItemInserted(files.size() - 1);
        }
    }

}
