package me.denley.notary;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.wearable.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DirectoryObserver implements FileListener {

    public interface SyncCallback {
        public void onSyncComplete();
    }

    @NonNull private final Context context;

    @NonNull private final FileListAdapter adapter;
    @NonNull private final List<File> files;
    @NonNull private final List<File> autoSyncFiles = new ArrayList<>();

    @NonNull private final String observedPath;
    @NonNull private final String externalObservedPathEncoded;
    @NonNull private final FileObserver fileSystemObserver;

    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());

    @NonNull private String localNodeId;

    @Nullable private final SyncableFileFilter fileFilter;
    @Nullable private final Comparator<File> sorter;

    private boolean hasSyncedState = false;
    @Nullable private final SyncCallback callback;

    public DirectoryObserver(@NonNull final Context context, @NonNull final FileListAdapter adapter,
                             @NonNull final String path, @Nullable final String externalPathEncoded,
                             @Nullable final SyncableFileFilter fileFilter, @Nullable final Comparator<File> sorter,
                             @Nullable final SyncCallback callback) {
        this.context = context;
        this.adapter = adapter;
        this.fileFilter = fileFilter;
        this.sorter = sorter;
        this.callback = callback;
        this.externalObservedPathEncoded = externalPathEncoded!=null?externalPathEncoded:FileTransaction.DEFAULT_DIRECTORY;
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
                final Node localNode = NotaryWearableListenerService.getLocalNode(context);
                localNodeId = localNode==null?"":localNode.getId();
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

    @SuppressWarnings("unused")
    public boolean hasSyncedWithRemoteDevice() {
        return hasSyncedState;
    }

    private void loadInitialFileList() {
        files.clear();

        final List<PendingFile> transactions = Notary.getTransactionsForDirectory(context, observedPath);
        for(PendingFile file:transactions) {
            if (file.transaction.getStatus()==FileTransaction.STATUS_IN_PROGRESS) {
                if(fileFilter==null || fileFilter.display(file)) {
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
            if(fileFilter==null || fileFilter.display(file)) {
                if (!files.contains(file)) {
                    files.add(file);
                }
            }
            if(fileFilter!=null && fileFilter.autoSync(file)) {
                autoSyncFiles.add(file);
            }
        }

        for(PendingFile file:transactions) {
            if (file.transaction.getStatus()==FileTransaction.STATUS_IN_PROGRESS) {
                autoSyncFiles.remove(file);
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

                        autoSyncFiles.remove(syncedFile);
                    }

                    doAutoSync();
                }

                hasSyncedState = true;
                displayUpdatedFileList();
                if(callback!=null) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            callback.onSyncComplete();
                        }
                    });
                }
            }
            @Override public void failure(ConnectionResult result) {
                hasSyncedState = true;
                displayUpdatedFileList();
                if(callback!=null) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            callback.onSyncComplete();
                        }
                    });
                }
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

    private void doAutoSync() {
        new Thread() {
            public void run() {
                final List<Node> remoteNodes = NotaryWearableListenerService.getRemoteNodes(context);
                if(!remoteNodes.isEmpty()) {
                    final String remoteNodeId = remoteNodes.get(0).getId();

                    for (File file : autoSyncFiles) {
                        if (!file.isDirectory && file.getClass()==File.class) { // file is not syncing, nor synced
                            if(fileFilter==null || fileFilter.autoSync(file)) {
                                Notary.requestFileTransfer(context, file.path, localNodeId, externalObservedPathEncoded, remoteNodeId, false);
                            }
                        }
                    }
                }
            }
        }.start();
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

    @Override public void onSourceFileStatusChanged(final FileTransaction transaction, final int indexUpdated) {
        handler.post(new Runnable() {
            public void run() {
                updateForTransaction(transaction, indexUpdated);
            }
        });
    }

    @Override public void onDestinationFileStatusChanged(final FileTransaction transaction, final int indexUpdated) {
        handler.post(new Runnable() {
            public void run() {
                updateForTransaction(transaction, indexUpdated);
            }
        });
    }

    @Override public void onDeleteTransactionSuccess(final FileTransaction transaction, final int indexUpdated) {
        handler.post(new Runnable() {
            public void run() {
                final File file = new File(observedPath, transaction.getSourceFileName(indexUpdated));

                if(files.contains(file)) {
                    final int position = files.indexOf(file);
                    files.remove(position);
                    files.add(position, file);
                    adapter.notifyItemChanged(position);
                }
            }
        });
    }

    private void updateForTransaction(final FileTransaction transaction, final int indexUpdated) {
        final File file;
        if(transaction.getStatus()==FileTransaction.STATUS_COMPLETE || indexUpdated < transaction.getActionableIndex()) {
            file = new SyncedFile(observedPath, transaction, indexUpdated);
        } else {
            file = new PendingFile(observedPath, transaction, indexUpdated);
        }

        final int position = files.indexOf(file);
        if(position!=-1) {
            final File existing = files.remove(position);
            files.add(position, file);

            if(!file.getClass().equals(existing.getClass())) {
                adapter.notifyItemChanged(position);
            }
        } else if(!localNodeId.equals(transaction.sourceNode) || !transaction.hasDeleted()) {
            if(fileFilter==null || fileFilter.display(file)) {
                files.add(file);
                adapter.notifyItemInserted(files.size() - 1);
            }
        }
    }

}
