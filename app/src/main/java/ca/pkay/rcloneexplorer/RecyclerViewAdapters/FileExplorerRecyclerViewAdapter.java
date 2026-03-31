package ca.pkay.rcloneexplorer.RecyclerViewAdapters;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.request.RequestOptions;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.util.FLog;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.file.FileAccessError;

public class FileExplorerRecyclerViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String TAG = "FileExplorerRVA";

    /** ViewType constants */
    public static final int VIEW_TYPE_LIST = 0;
    public static final int VIEW_TYPE_GRID = 1;

    private List<FileItem> files;
    private View emptyView;
    private View noSearchResultsView;
    private OnClickListener listener;
    private boolean isInSelectMode;
    private List<FileItem> selectedItems;
    private boolean isInMoveMode;
    private boolean isInSearchMode;
    private boolean canSelect;
    private boolean showThumbnails;
    private boolean optionsDisabled;
    private boolean wrapFileNames;
    private boolean isGridMode;
    private Context context;
    private long sizeLimit;
    private java.util.Set<String> loadedThumbnails = new java.util.HashSet<>();
    private long refreshSignature = 0L;

    public interface OnClickListener {
        void onFileClicked(FileItem fileItem);
        void onDirectoryClicked(FileItem fileItem, int position);
        void onFilesSelected();
        void onFileDeselected();
        void onFileOptionsClicked(View view, FileItem fileItem);
        String[] getThumbnailServerParams();
    }

    public FileExplorerRecyclerViewAdapter(Context context, View emptyView, View noSearchResultsView, OnClickListener listener) {
        files = new ArrayList<>();
        this.context = context;
        this.emptyView = emptyView;
        this.noSearchResultsView = noSearchResultsView;
        this.listener = listener;
        isInSelectMode = false;
        selectedItems = new ArrayList<>();
        isInMoveMode = false;
        isInSearchMode = false;
        canSelect = true;
        wrapFileNames = true;
        optionsDisabled = false;
        isGridMode = false;
        sizeLimit = PreferenceManager.getDefaultSharedPreferences(context)
                .getLong(context.getString(R.string.pref_key_thumbnail_size_limit),
                        context.getResources().getInteger(R.integer.default_thumbnail_size_limit));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ViewType dispatch
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        return isGridMode ? VIEW_TYPE_GRID : VIEW_TYPE_LIST;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ViewHolder creation
    // ─────────────────────────────────────────────────────────────────────────

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_GRID) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.fragment_file_explorer_grid_item, parent, false);
            return new GridViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.fragment_file_explorer_item, parent, false);
            return new ViewHolder(view);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Binding
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder rawHolder, int position) {
        final FileItem item = files.get(position);

        if (rawHolder instanceof GridViewHolder) {
            bindGridViewHolder((GridViewHolder) rawHolder, item);
        } else {
            bindListViewHolder((ViewHolder) rawHolder, item);
        }
    }

    /** Bind a list-mode row (original behaviour, unchanged). */
    private void bindListViewHolder(@NonNull final ViewHolder holder, final FileItem item) {
        holder.fileItem = item;
        if (item.isDir()) {
            holder.dirIcon.setVisibility(View.VISIBLE);
            holder.fileIcon.setVisibility(View.GONE);
            holder.fileSize.setVisibility(View.GONE);
            holder.interpunct.setVisibility(View.GONE);
        } else {
            holder.fileIcon.setVisibility(View.VISIBLE);
            holder.dirIcon.setVisibility(View.GONE);
            holder.fileSize.setText(item.getHumanReadableSize());
            holder.fileSize.setVisibility(View.VISIBLE);
            holder.interpunct.setVisibility(View.VISIBLE);
        }

        if (showThumbnails && !item.isDir()) {
            loadThumbnail(holder.fileIcon, item);
        }

        RemoteItem itemRemote = item.getRemote();
        if (!itemRemote.isDirectoryModifiedTimeSupported() && item.isDir()) {
            holder.fileModTime.setVisibility(View.GONE);
        } else {
            holder.fileModTime.setVisibility(View.VISIBLE);
            holder.fileModTime.setText(item.getHumanReadableModTime());
        }

        holder.fileName.setText(item.getName());

        if (isInSelectMode) {
            if (selectedItems.contains(item)) {
                holder.view.setBackgroundColor(getSelectionBackgroundColor());
            } else {
                holder.view.setBackgroundColor(Color.TRANSPARENT);
            }
        } else {
            holder.view.setBackgroundColor(Color.TRANSPARENT);
        }

        if (isInMoveMode) {
            holder.view.setAlpha(item.isDir() ? 1f : .5f);
        } else if (holder.view.getAlpha() == .5f) {
            holder.view.setAlpha(1f);
        }

        if ((isInSelectMode || isInMoveMode) && !optionsDisabled) {
            holder.fileOptions.setVisibility(View.INVISIBLE);
        } else if (optionsDisabled) {
            holder.fileOptions.setVisibility(View.GONE);
        } else {
            holder.fileOptions.setVisibility(View.VISIBLE);
            holder.fileOptions.setOnClickListener(v -> listener.onFileOptionsClicked(v, item));
        }

        if (wrapFileNames) {
            holder.fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            holder.fileName.setSingleLine(true);
        } else {
            holder.fileName.setEllipsize(null);
            holder.fileName.setSingleLine(false);
        }

        holder.view.setOnClickListener(view -> {
            if (isInSelectMode) {
                onLongClickAction(item, holder.view, holder.fileItem);
            } else {
                onClickAction(item, holder.getAdapterPosition());
            }
        });

        holder.view.setOnLongClickListener(view -> {
            if (!isInMoveMode && canSelect) {
                onLongClickAction(item, holder.view, item);
            }
            return true;
        });

        holder.icons.setOnClickListener(v -> {
            if (!isInMoveMode && canSelect) {
                onLongClickAction(item, holder.view, item);
            }
        });
    }

    /** Bind a grid-mode cell. */
    private void bindGridViewHolder(@NonNull final GridViewHolder holder, final FileItem item) {
        if (item.isDir()) {
            holder.dirIcon.setVisibility(View.VISIBLE);
            holder.fileIcon.setVisibility(View.GONE);
        } else {
            holder.fileIcon.setVisibility(View.VISIBLE);
            holder.dirIcon.setVisibility(View.GONE);
            if (showThumbnails) {
                loadThumbnail(holder.fileIcon, item);
            } else {
                holder.fileIcon.setImageResource(R.drawable.ic_file);
            }
        }

        holder.fileName.setText(item.getName());

        // Selection overlay
        if (isInSelectMode && selectedItems.contains(item)) {
            holder.selectionOverlay.setVisibility(View.VISIBLE);
        } else {
            holder.selectionOverlay.setVisibility(View.GONE);
        }

        // Move-mode: dim files, keep dirs bright
        if (isInMoveMode) {
            holder.itemView.setAlpha(item.isDir() ? 1f : .5f);
        } else if (holder.itemView.getAlpha() == .5f) {
            holder.itemView.setAlpha(1f);
        }

        holder.itemView.setOnClickListener(view -> {
            if (isInSelectMode) {
                onLongClickAction(item, holder.itemView, item);
            } else {
                onClickAction(item, holder.getAdapterPosition());
            }
        });

        // Long-press enters selection mode (no per-file options button in grid)
        holder.itemView.setOnLongClickListener(view -> {
            if (!isInMoveMode && canSelect) {
                onLongClickAction(item, holder.itemView, item);
            }
            return true;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared thumbnail loading (used by both list & grid bind methods)
    // ─────────────────────────────────────────────────────────────────────────

    private void loadThumbnail(ImageView target, FileItem item) {
        String mimeType = item.getMimeType();
        if ((mimeType.startsWith("image/") || mimeType.startsWith("video/")) && item.getSize() <= sizeLimit) {
            RequestOptions glideOption = new RequestOptions()
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_file);

            if (refreshSignature != 0L && !loadedThumbnails.contains(item.getPath())) {
                glideOption = glideOption.signature(new com.bumptech.glide.signature.ObjectKey(refreshSignature));
            }

            boolean localLoad = item.getRemote().getType() == RemoteItem.SAFW;
            if (localLoad) {
                bindSafFile(target, item, glideOption);
            } else {
                String[] serverParams = listener.getThumbnailServerParams();
                String hiddenPath = serverParams[0];
                int serverPort = Integer.parseInt(serverParams[1]);
                String url = "http://127.0.0.1:" + serverPort + "/" + hiddenPath + '/' + item.getPath();
                Glide.with(context)
                        .load(new PersistentGlideUrl(url))
                        .apply(glideOption)
                        .thumbnail(0.1f)
                        .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                            @Override
                            public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                return false;
                            }
                            @Override
                            public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                loadedThumbnails.add(item.getPath());
                                return false;
                            }
                        })
                        .into(target);
            }
        } else {
            target.setImageResource(R.drawable.ic_file);
        }
    }

    private void bindSafFile(ImageView target, FileItem item, RequestOptions glideOption) {
        try {
            Uri contentUri = SafAccessProvider.getDirectServer(context).getDocumentUri('/' + item.getPath());
            Glide.with(context)
                    .load(contentUri)
                    .apply(glideOption)
                    .thumbnail(0.1f)
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                            return false;
                        }
                        @Override
                        public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                            loadedThumbnails.add(item.getPath());
                            return false;
                        }
                    })
                    .into(target);
        } catch (FileAccessError e) {
            FLog.e(TAG, "onBindViewHolder: SAF error", e);
            target.setImageResource(R.drawable.ic_file);
        }
    }

    private static class PersistentGlideUrl extends GlideUrl {
        public PersistentGlideUrl(String url) {
            super(url);
        }

        @Override
        public String getCacheKey() {
            try {
                URL url = super.toURL();
                String path = url.getPath();
                return path.substring(path.indexOf('/', 1));
            } catch (MalformedURLException e) {
                return super.getCacheKey();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Grid mode switch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Switch between grid and list display modes.
     * Call this before (or together with) swapping the RecyclerView LayoutManager.
     */
    public void refreshMissingThumbnails() {
        refreshSignature = System.currentTimeMillis();
        for (int i = 0; i < files.size(); i++) {
            FileItem item = files.get(i);
            String mimeType = item.getMimeType();
            if ((mimeType.startsWith("image/") || mimeType.startsWith("video/")) && item.getSize() <= sizeLimit) {
                if (!loadedThumbnails.contains(item.getPath())) {
                    notifyItemChanged(i);
                }
            }
        }
    }

    public void setGridMode(boolean gridMode) {
        this.isGridMode = gridMode;
        notifyDataSetChanged();
    }

    public boolean isGridMode() {
        return isGridMode;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Standard adapter API (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public int getItemCount() {
        if (files == null) {
            return 0;
        } else {
            return files.size();
        }
    }

    public void disableFileOptions() {
        optionsDisabled = true;
    }

    public void showThumbnails(boolean showThumbnails) {
        this.showThumbnails = showThumbnails;
    }

    public List<FileItem> getCurrentContent() {
        return new ArrayList<>(files);
    }

    public void clear() {
        if (files == null) {
            return;
        }
        int count = files.size();
        files.clear();
        isInSelectMode = false;
        if (!selectedItems.isEmpty()) {
            selectedItems.clear();
            listener.onFileDeselected();
        }
        notifyItemRangeRemoved(0, count);
    }

    public void newData(List<FileItem> data) {
        this.clear();
        files = new ArrayList<>(data);
        isInSelectMode = false;
        if (files.isEmpty()) {
            showEmptyState(true);
        } else {
            showEmptyState(false);
        }
        if (isInMoveMode) {
            notifyDataSetChanged();
        } else {
            notifyItemRangeInserted(0, files.size());
        }
    }

    public void updateData(List<FileItem> data) {
        if (data.isEmpty()) {
            int count = files.size();
            files.clear();
            notifyItemRangeRemoved(0, count);
            showEmptyState(true);
            return;
        }
        showEmptyState(false);
        List<FileItem> newData = new ArrayList<>(data);
        List<FileItem> diff = new ArrayList<>(files);

        diff.removeAll(newData);
        for (FileItem fileItem : diff) {
            int index = files.indexOf(fileItem);
            files.remove(index);
            if (selectedItems.contains(fileItem)) {
                selectedItems.remove(fileItem);
                isInSelectMode = !selectedItems.isEmpty();
                listener.onFileDeselected();
            }
            notifyItemRemoved(index);
        }

        diff = new ArrayList<>(data);
        diff.removeAll(files);
        for (FileItem fileItem : diff) {
            int index = newData.indexOf(fileItem);
            files.add(index, fileItem);
            notifyItemInserted(index);
        }
    }

    public void updateSortedData(List<FileItem> data) {
        if (files != null) {
            int count = files.size();
            files.clear();
            notifyItemRangeRemoved(0, count);
        }
        files = new ArrayList<>(data);
        if (files.isEmpty()) {
            showEmptyState(true);
        } else {
            showEmptyState(false);
        }
        if (isInMoveMode) {
            notifyDataSetChanged();
        } else {
            notifyItemRangeInserted(0, files.size());
        }
    }

    public void refreshData() {
        notifyDataSetChanged();
    }

    public void setMoveMode(Boolean mode) {
        isInMoveMode = mode;
    }

    public void setSearchMode(Boolean mode) {
        isInSearchMode = mode;
    }

    public void setSelectedItems(List<FileItem> selectedItems) {
        this.selectedItems = new ArrayList<>(selectedItems);
        this.isInSelectMode = true;
        notifyDataSetChanged();
    }

    public Boolean isInSelectMode() {
        return isInSelectMode;
    }

    public List<FileItem> getSelectedItems() {
        return new ArrayList<>(selectedItems);
    }

    public int getNumberOfSelectedItems() {
        return selectedItems.size();
    }

    public Boolean isInMoveMode() {
        return isInMoveMode;
    }

    public void setWrapFileNames(boolean wrapFileNames) {
        this.wrapFileNames = wrapFileNames;
        refreshData();
    }

    private void showEmptyState(Boolean show) {
        if (isInSearchMode) {
            noSearchResultsView.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        } else {
            emptyView.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void onClickAction(FileItem item, int position) {
        if (item.isDir() && null != listener) {
            listener.onDirectoryClicked(item, position);
        } else if (!item.isDir() && !isInMoveMode && null != listener) {
            listener.onFileClicked(item);
        }
    }

    public void toggleSelectAll() {
        if (null == files) {
            return;
        }
        if (selectedItems.size() == files.size()) {
            isInSelectMode = false;
            selectedItems.clear();
            listener.onFileDeselected();
        } else {
            isInSelectMode = true;
            selectedItems.clear();
            selectedItems.addAll(files);
            listener.onFilesSelected();
        }
        notifyDataSetChanged();
    }

    public void cancelSelection() {
        isInSelectMode = false;
        selectedItems.clear();
        listener.onFileDeselected();
        notifyDataSetChanged();
    }

    public void setCanSelect(Boolean canSelect) {
        this.canSelect = canSelect;
    }

    private int getSelectionBackgroundColor() {
        return context.getColor(R.color.selectedItem);
    }

    /**
     * Unified long-click handler used by both list and grid modes.
     * In grid mode the itemView is passed directly (no separate ViewHolder).
     */
    private void onLongClickAction(FileItem item, View itemView, FileItem ignored) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item);
            // Reset background / overlay for list mode; grid mode redraws via notifyDataSetChanged
            itemView.setBackgroundColor(Color.TRANSPARENT);
            if (selectedItems.size() == 0) {
                isInSelectMode = false;
                listener.onFileDeselected();
            }
            listener.onFileDeselected();
        } else {
            selectedItems.add(item);
            isInSelectMode = true;
            itemView.setBackgroundColor(getSelectionBackgroundColor());
            listener.onFilesSelected();
        }
        notifyDataSetChanged();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ViewHolders
    // ─────────────────────────────────────────────────────────────────────────

    /** Original list-mode ViewHolder. */
    public class ViewHolder extends RecyclerView.ViewHolder {

        public final View view;
        public final View icons;
        public final ImageView fileIcon;
        public final ImageView dirIcon;
        public final TextView fileName;
        public final TextView fileModTime;
        public final TextView fileSize;
        public final TextView interpunct;
        public final ImageButton fileOptions;
        public FileItem fileItem;

        ViewHolder(View itemView) {
            super(itemView);
            this.view = itemView;
            this.icons = view.findViewById(R.id.icons);
            this.fileIcon = view.findViewById(R.id.file_icon);
            this.dirIcon = view.findViewById(R.id.dir_icon);
            this.fileName = view.findViewById(R.id.file_name);
            this.fileModTime = view.findViewById(R.id.file_modtime);
            this.fileSize = view.findViewById(R.id.file_size);
            this.fileOptions = view.findViewById(R.id.file_options);
            this.interpunct = view.findViewById(R.id.interpunct);
        }
    }

    /** Grid-mode ViewHolder — square thumbnail cell. */
    public class GridViewHolder extends RecyclerView.ViewHolder {

        public final ImageView fileIcon;
        public final ImageView dirIcon;
        public final TextView fileName;
        /** Semi-transparent overlay shown when the cell is selected. */
        public final View selectionOverlay;

        GridViewHolder(View itemView) {
            super(itemView);
            this.fileIcon = itemView.findViewById(R.id.file_icon);
            this.dirIcon = itemView.findViewById(R.id.dir_icon);
            this.fileName = itemView.findViewById(R.id.file_name);
            this.selectionOverlay = itemView.findViewById(R.id.selection_overlay);
        }
    }
}
