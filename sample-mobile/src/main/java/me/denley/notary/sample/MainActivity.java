package me.denley.notary.sample;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.List;

import me.denley.courier.Courier;
import me.denley.courier.LocalNode;
import me.denley.courier.RemoteNodes;
import me.denley.notary.DirectoryObserver;
import me.denley.notary.File;
import me.denley.notary.FileListAdapter;
import me.denley.notary.FileTransaction;
import me.denley.notary.Notary;
import me.denley.notary.PendingFile;
import me.denley.notary.SyncedFile;

public class MainActivity extends ActionBarActivity {

    private DirectoryObserver observer;

    @LocalNode Node localNode;
    @RemoteNodes List<Node> remoteNodes;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Courier.startReceiving(this);

        final java.io.File directory = new java.io.File(FileTransaction.getDefaultDirectory(this));
        directory.mkdir();
        if(!directory.isDirectory()) {
            throw new RuntimeException("Unable to load sample directory");
        }

        RecyclerView list = new RecyclerView(this);
        setContentView(list);
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));

        MainActivityAdapter adapter = new MainActivityAdapter();
        list.setAdapter(adapter);
        observer = new DirectoryObserver(this, adapter, directory.getAbsolutePath(), false);

        //purge();
    }

    private void purge() {
        new Thread(){
            public void run(){
                final GoogleApiClient apiClient = new GoogleApiClient.Builder(MainActivity.this)
                        .addApi(Wearable.API).build();

                final ConnectionResult result = apiClient.blockingConnect();
                if(result.isSuccess()) {
                    final DataItemBuffer buffer = Wearable.DataApi.getDataItems(apiClient).await();
                    for(DataItem item:buffer) {
                        Wearable.DataApi.deleteDataItems(apiClient, item.getUri());
                    }
                    buffer.release();
                } else {
                    throw new IllegalStateException("Unable to connect to wearable API");
                }
            }
        }.start();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        observer.stopObserving();
    }

    private class MainActivityAdapter extends FileListAdapter {

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            return new ViewHolder(getLayoutInflater().inflate(R.layout.list_item_file, viewGroup, false));
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position) {
            final TextView text = (TextView) viewHolder.itemView.findViewById(android.R.id.text1);
            final ImageView icon = (ImageView) viewHolder.itemView.findViewById(android.R.id.icon);

            final File file = getFile(position);

            text.setText(file.getName());

            if(file instanceof SyncedFile) {
                icon.setImageResource(R.drawable.ic_action_success);
            } else if(file instanceof PendingFile) {
                final PendingFile pendingFile = (PendingFile)file;

                switch(pendingFile.transaction.getStatus()) {
                    case FileTransaction.STATUS_IN_PROGRESS:
                        icon.setImageResource(R.drawable.ic_action_sync);
                        break;
                    case FileTransaction.STATUS_COMPLETE:
                        icon.setImageResource(R.drawable.ic_action_success);
                        break;
                    case FileTransaction.STATUS_FAILED_UNKNOWN:
                    case FileTransaction.STATUS_FAILED_FILE_NOT_FOUND:
                        icon.setImageResource(R.drawable.ic_action_sync_problem);
                        break;
                    case FileTransaction.STATUS_FAILED_BAD_DESTINATION:
                        text.append("\nBad Destination");
                        break;
                    case FileTransaction.STATUS_FAILED_FILE_ALREADY_EXISTS:
                        text.append("\nAlready Exists");
                        break;
                    case FileTransaction.STATUS_FAILED_NO_READ_PERMISSION:
                        text.append("\nCan't Read");
                        break;
                    case FileTransaction.STATUS_FAILED_NO_DELETE_PERMISSION:
                        text.append("\nCan't Delete");
                        break;
                    case FileTransaction.STATUS_CANCELED:
                        if(file.isDirectory) {
                            icon.setImageResource(R.drawable.ic_action_folder);
                        } else {
                            icon.setImageResource(R.drawable.ic_action_file);
                        }
                        break;
                }
            } else {
                if(file.isDirectory) {
                    icon.setImageResource(R.drawable.ic_action_folder);
                } else {
                    icon.setImageResource(R.drawable.ic_action_file);
                }
            }

            viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if(file.isDirectory) {
                        // do nothing
                    } else if(file instanceof PendingFile) {
                        // TODO cancel transaction
                    } else if(file instanceof SyncedFile) {
                        final String fileName = new java.io.File(file.path).getName();
                        final String remotePath = FileTransaction.DEFAULT_DIRECTORY+"/"+fileName;

                        Notary.requestFileDelete(
                                MainActivity.this,
                                remotePath, remoteNodes.get(0).getId(),
                                FileTransaction.DEFAULT_DIRECTORY, localNode.getId());
                    } else {
                        Notary.requestFileTransfer(MainActivity.this,
                                file.path, localNode.getId(),
                                FileTransaction.DEFAULT_DIRECTORY, remoteNodes.get(0).getId(),
                                false);
                    }
                }
            });
        }
    }

    private class ViewHolder extends RecyclerView.ViewHolder {
        public ViewHolder(View itemView) {
            super(itemView);
        }
    }
}
