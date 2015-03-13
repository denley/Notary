package me.denley.notary;

import android.net.Uri;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class NotaryWearableListenerService extends WearableListenerService {

    Node localNode;

    @Override public void onDataChanged(DataEventBuffer dataEvents) {
        super.onDataChanged(dataEvents);
        initLocalNode();

        for(DataEvent event:dataEvents) {
            onDataChanged(event);
        }
    }

    private void initLocalNode() {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            localNode = Wearable.NodeApi.getLocalNode(apiClient).await().getNode();
        } else {
            throw new IllegalStateException("Unable to connect to wearable API");
        }
    }

    private void onDataChanged(final DataEvent event) {
        final DataItem item = event.getDataItem();

        if(event.getType()!=DataEvent.TYPE_DELETED && localNode !=null && FileTransaction.isFileTransactionItem(item)) {
            checkForActionables(item);
        }
    }

    private void checkForActionables(final DataItem item) {
        final FileTransaction transaction = new FileTransaction(item);

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

    private void loadSourceFile(final FileTransaction transaction) {
        final File file = new File(transaction.sourceFile);

        if(!file.exists() && file.isDirectory()) {
            transaction.status = FileTransaction.STATUS_FAILED_FILE_NOT_FOUND;
        } else if(!file.canRead()) {
            transaction.status = FileTransaction.STATUS_FAILED_NO_READ_PERMISSION;
        } else {
            transaction.fileAsset = Asset.createFromUri(Uri.fromFile(file));
        }

        updateTransaction(transaction);
    }

    private void saveDestinationFile(final FileTransaction transaction) {
        if(transaction.destinationDirectory==null) {
            transaction.status = FileTransaction.STATUS_FAILED_BAD_DESTINATION;
        } else {
            final File directory = new File(transaction.destinationDirectory);

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
                        while ((count = in.read()) != -1) {
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
        }

        updateTransaction(transaction);
    }

    private void deleteSourceFile(final FileTransaction transaction) {
        final File file = new File(transaction.sourceFile);

        if(!file.exists()) {
            transaction.setHasDeleted();
        } else if(file.delete()) {
            transaction.setHasDeleted();
        } else {
            transaction.status = FileTransaction.STATUS_FAILED_NO_DELETE_PERMISSION;
        }

        updateTransaction(transaction);
    }

    private void updateTransaction(final FileTransaction transaction) {
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

    private InputStream openAssetInputStream(Asset asset) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            return Wearable.DataApi.getFdForAsset(apiClient, asset).await().getInputStream();
        } else {
            throw new IllegalStateException("Unable to connect to wearable API");
        }
    }

}
