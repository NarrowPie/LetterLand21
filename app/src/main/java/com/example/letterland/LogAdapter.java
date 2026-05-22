package com.example.letterland;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {

    private Context context;
    private List<LogEntry> logList;

    public LogAdapter(Context context, List<LogEntry> logList) {
        this.context = context;
        this.logList = logList;
    }

    public void updateData(List<LogEntry> newList) {
        this.logList = newList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_user_log, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogEntry logEntry = logList.get(position);

        // Safely parse the 3-part pipe format: Word|ImagePath|ProfileName
        String[] parts = logEntry.details.split("\\|");
        String wordText = parts.length > 0 ? parts[0] : "Unknown Word";
        String imagePath = parts.length > 1 ? parts[1] : "";
        String rawProfile = parts.length > 2 ? parts[2] : "Default";

        holder.tvLogWord.setText(wordText);

        if (imagePath != null && !imagePath.isEmpty()) {
            holder.ivLogImage.setImageURI(Uri.parse(imagePath));
        } else {
            holder.ivLogImage.setImageResource(R.drawable.admin_pic);
        }

        // Direct, high-performance date formatting using the log's built-in timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.US);
        String dateTimeString = sdf.format(new Date(logEntry.timestamp));

        String details;
        if ("ADMIN ADDED WORD".equals(logEntry.action) || rawProfile.startsWith("ADMIN|")) {
            String actualPlayerName = rawProfile.replace("ADMIN|", "");
            details = "Added by: Admin to " + actualPlayerName + "\nDate: " + dateTimeString;
        } else {
            details = "Profile: " + rawProfile + "\nDate: " + dateTimeString;
        }

        holder.tvLogDetails.setText(details);
    }

    @Override
    public int getItemCount() {
        return logList != null ? logList.size() : 0;
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        ImageView ivLogImage;
        TextView tvLogWord, tvLogDetails;

        public LogViewHolder(@NonNull View itemView) {
            super(itemView);
            ivLogImage = itemView.findViewById(R.id.ivLogImage);
            tvLogWord = itemView.findViewById(R.id.tvLogWord);
            tvLogDetails = itemView.findViewById(R.id.tvLogDetails);
        }
    }
}