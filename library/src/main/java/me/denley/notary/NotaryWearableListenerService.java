package me.denley.notary;

import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;

public class NotaryWearableListenerService extends WearableListenerService {

    public static Node getLocalNode(final Context context) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            return Wearable.NodeApi.getLocalNode(apiClient).await().getNode();
        } else {
            throw new IllegalStateException("Unable to connect to wearable API");
        }
    }


    Node localNode;

    @Override public void onPeerConnected(Node peer) {
        super.onPeerConnected(peer);

        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            final DataItemBuffer items = Wearable.DataApi.getDataItems(apiClient).await();
            for (DataItem item : items) {
                if(item.getData().length>0 && localNode !=null && FileTransaction.isFileTransactionItem(item)) {
                    onTransactionDataItemChanged(item);
                }
            }
            items.release();
        }
    }

    @Override public void onDataChanged(@NonNull final DataEventBuffer dataEvents) {
        super.onDataChanged(dataEvents);
        localNode = getLocalNode(this);

        for(DataEvent event:dataEvents) {
            onDataChanged(event);
        }
    }

    private void onDataChanged(@NonNull final DataEvent event) {
        final DataItem item = event.getDataItem();

        if(event.getType()!=DataEvent.TYPE_DELETED && item.getData().length>0 && localNode !=null && FileTransaction.isFileTransactionItem(item)) {
            onTransactionDataItemChanged(item);
        }
    }

    private void onTransactionDataItemChanged(@NonNull final DataItem item) {
        final FileTransaction transaction = new FileTransaction(item);
        Notary.notifyListeners(this, transaction);

        if(transaction.getStatus()==FileTransaction.STATUS_IN_PROGRESS) {
            checkForActionables(transaction);
        }
    }

    private void checkForActionables(@NonNull final FileTransaction transaction) {
        if (transaction.pendingCopy()) {
            if(localNode.getId().equals(transaction.sourceNode)) {
                loadSourceFile(transaction);
            }
        } else if (transaction.pendingSave()) {
            if(localNode.getId().equals(transaction.destinationNode)) {
                saveDestinationFile(transaction);
            }
        } else if (transaction.pendingDelete()) {
            if(localNode.getId().equals(transaction.sourceNode)) {
                deleteSourceFile(transaction);
            }
        }
    }

    private void loadSourceFile(@NonNull final FileTransaction transaction) {
        final File file = transaction.getSourceFile();

        if(!file.exists() || file.isDirectory()) {
            transaction.status = FileTransaction.STATUS_FAILED_FILE_NOT_FOUND;
        } else if(!file.canRead()) {
            transaction.status = FileTransaction.STATUS_FAILED_NO_READ_PERMISSION;
        } else {
            transaction.fileAsset = Asset.createFromUri(Uri.fromFile(file));
        }

        updateTransaction(transaction);
    }

    private void saveDestinationFile(@NonNull final FileTransaction transaction) {
        final File directory = transaction.getDestinationDirectoryFile(this);

        if (!directory.exists() || !directory.isDirectory()) {
            transaction.status = FileTransaction.STATUS_FAILED_BAD_DESTINATION;
        } else {
            final File file = new File(directory, transaction.getSourceFileName());

            if(file.exists()) {
                transaction.status = FileTransaction.STATUS_FAILED_FILE_ALREADY_EXISTS;
            } else {
                try {
                    final InputStream in = openAssetInputStream(transaction.fileAsset);
                    final FileOutputStream out = new FileOutputStream(file);

                    final byte[] buffer = new byte[1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                    }

                    out.flush();
                    transaction.setHasCopied();

                    in.close();
                    out.close();
                } catch (Exception e) {
                    transaction.status = FileTransaction.STATUS_FAILED_UNKNOWN;
                }
            }
        }

        updateTransaction(transaction);
    }

    private void deleteSourceFile(@NonNull final FileTransaction transaction) {
        final File file = transaction.getSourceFile();

        if(!file.exists()) {
            transaction.setHasDeleted();
        } else if(file.delete()) {
            transaction.setHasDeleted();
        } else {
            transaction.status = FileTransaction.STATUS_FAILED_NO_DELETE_PERMISSION;
        }

        updateTransaction(transaction);
    }

    private void updateTransaction(@NonNull final FileTransaction transaction) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {

            // Delete old data
            final Uri uri = new Uri.Builder()
                    .scheme(PutDataRequest.WEAR_URI_SCHEME)
                    .path(transaction.getDataApiPath())
                    .build();
            Wearable.DataApi.deleteDataItems(apiClient, uri);

            // Put new transaction
            Wearable.DataApi.putDataItem(apiClient, transaction.asPutDataRequest());
        } else {
            throw new IllegalStateException("Unable to connect to wearable API");
        }
    }

    private InputStream openAssetInputStream(@NonNull final Asset asset) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            return Wearable.DataApi.getFdForAsset(apiClient, asset).await().getInputStream();
        } else {
            throw new IllegalStateException("Unable to connect to wearable API");
        }
    }


    @Override public void onMessageReceived(@NonNull final MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);

        if(messageEvent.getPath().equals(Notary.REQUEST_LIST_FILES)) {
            final byte[] data = messageEvent.getData();
            final String requestedDirectory = data.length>0?new String(data):null;
            final String usedDirectory = requestedDirectory!=null?FileTransaction.normalizePath(requestedDirectory):Notary.getDefaultDirectory(this);

            try {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                final ObjectOutputStream out = new ObjectOutputStream(bytes);

                out.writeObject(createFileListResponse(usedDirectory, requestedDirectory));

                sendMessage(messageEvent.getSourceNodeId(), Notary.RESPONSE_LIST_FILES, bytes.toByteArray());
            }catch(IOException e) {}
        }
    }

    private FileListContainer createFileListResponse(@NonNull final String usedDirectory, @Nullable final String requestedDirectory) {
        final File directoryFile = new File(usedDirectory);

        final FileListContainer response = new FileListContainer();
        response.directory = requestedDirectory;

        if(!directoryFile.exists() || !directoryFile.isDirectory()) {
            response.outcome = FileListContainer.ERROR_DIRECTORY_NOT_FOUND;
        } else {
            final File[] contents = directoryFile.listFiles();
            response.files = new String[contents.length];
            response.isDirectory = new boolean[contents.length];
            for (int i = 0; i < contents.length; i++) {
                response.files[i] = contents[i].getName();
                response.isDirectory[i] = contents[i].isDirectory();
            }
        }

        return response;
    }

    private void sendMessage(@NonNull final String node, @NonNull final String path, @NonNull final byte[] data) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();
        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            Wearable.MessageApi.sendMessage(apiClient, node, path, data);
            apiClient.disconnect();
        }
    }
}
