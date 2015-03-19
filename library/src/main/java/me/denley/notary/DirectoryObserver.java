package me.denley.notary;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DirectoryObserver implements FileListener {

    @NonNull private final Context context;

    @NonNull private final FileListAdapter adapter;
    @NonNull private final List<File> files;

    @NonNull private final String observedPath;
    @Nullable private final String externalObservedPathEncoded;
    @NonNull private final FileObserver fileSystemObserver;

    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());

    @NonNull private String localNodeId;

    @Nullable private final FileFilter fileFilter;
    @Nullable private final Comparator<File> sorter;

    private boolean hasSyncedState = false;

    public DirectoryObserver(@NonNull final Context context, @NonNull final FileListAdapter adapter,
                             @NonNull final String path, @Nullable final String externalPathEncoded,
                             @Nullable final FileFilter fileFilter, @Nullable final Comparator<File> sorter) {
        this.context = context;
        this.adapter = adapter;
        this.fileFilter = fileFilter;
        this.sorter = sorter;
        this.externalObservedPathEncoded = externalPathEncoded;
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

    public boolean hasSyncedWithRemoteDevice() {
        return hasSyncedState;
    }

    private void loadInitialFileList() {
        files.clear();

        final List<PendingFile> transactions = Notary.getTransactionsForDirectory(context, observedPath);
        for(PendingFile file:transactions) {
            if (file.transaction.getStatus()==FileTransaction.STATUS_IN_PROGRESS) {
                if(fileFilter==null || fileFilter.accept(file)) {
                    files.add(file);
                }
            }
        }

        final java.io.File directory = new java.io.File(observedPath);
        if(!directory.isDirectory()) {
            throw new IllegalArgumentException("Path does not represent a directory");
        }
        for(java.io.File localFile:directory.listFiles()) {
            final File file = new File(localFile);
            if(fileFilter==null || fileFilter.accept(file)) {
                if (!files.contains(file)) {
                    files.add(file);
                }
            }
        }

        displayUpdatedFileList();

        Notary.requestFileList(context, externalObservedPathEncoded, new Notary.FileListCallback() {
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

                hasSyncedState = true;
                displayUpdatedFileList();
            }
            @Override public void failure(ConnectionResult result) {
                hasSyncedState = true;
                displayUpdatedFileList();
            }
        });
    }

    private synchronized void displayUpdatedFileList() {
        if(sorter!=null) {
            Collections.sort(files, sorter);
        }
        handler.post(new Runnable(){
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private synchronized void onFileSystemEvent(int event, String path) {
        final File updatedFile = new File(observedPath, path);

        switch(event) {
            case FileObserver.MODIFY:
            case FileObserver.ATTRIB:
            case FileObserver.MOVED_TO:
            case FileObserver.CREATE: {
                final int position = files.indexOf(updatedFile);
                if (position!=-1) {
                    final File existing = files.get(position);

                    if(existing instanceof SyncedFile) {
                        files.remove(position);
                        files.add(position, new SyncedFile(new java.io.File(observedPath, path)));
                        adapter.notifyItemChanged(position);
                    } else if(!(existing instanceof PendingFile)) {
                        files.remove(position);
                        files.add(position, updatedFile);
                        adapter.notifyItemChanged(position);
                    }
                } else {
                    files.add(updatedFile);
                    adapter.notifyItemInserted(files.size() - 1);
                }
                break;
            }
            case FileObserver.DELETE:
            case FileObserver.MOVED_FROM: {
                final int position = files.indexOf(updatedFile);
                if (position != -1) {
                    files.remove(position);
                    adapter.notifyItemRemoved(position);
                }
                break;
            }
            case FileObserver.DELETE_SELF:
            case FileObserver.MOVE_SELF: {
                stopObserving();
                files.clear();
                adapter.notifyDataSetChanged();
                break;
            }
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

    @Override public void onDeleteTransactionSuccess(final FileTransaction transaction) {
        handler.post(new Runnable() {
            public void run() {
                final File file = new File(observedPath, transaction.getSourceFileName());

                if(files.contains(file)) {
                    final int position = files.indexOf(file);
                    files.remove(position);
                    files.add(position, file);
                    adapter.notifyItemChanged(position);
                }
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

        final int position = files.indexOf(file);
        if(position!=-1) {
            final File existing = files.remove(position);
            files.add(position, file);

            if(!file.getClass().equals(existing.getClass())) {
                adapter.notifyItemChanged(position);
            }
        } else if(!localNodeId.equals(transaction.sourceNode) || !transaction.hasDeleted()) {
            files.add(file);
            adapter.notifyItemInserted(files.size() - 1);
        }
    }

}
