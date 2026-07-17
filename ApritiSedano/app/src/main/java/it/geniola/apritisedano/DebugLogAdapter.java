package it.geniola.apritisedano;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DebugLogAdapter extends RecyclerView.Adapter<DebugLogAdapter.ViewHolder> {

    private final List<DebugLogEntry> logs;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    public DebugLogAdapter(List<DebugLogEntry> logs) {
        this.logs = logs;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_debug_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DebugLogEntry entry = logs.get(position);
        
        holder.tvTimestamp.setText(dateFormat.format(new Date(entry.getTimestamp())));

        if (entry.isTx()) {
            holder.tvDirection.setText("TX");
            holder.tvDirection.setBackgroundColor(Color.parseColor("#2196F3")); // Blue
            
            String content = "";
            if (entry.getTotp() != null && !entry.getTotp().isEmpty()) {
                content = "TOTP: " + entry.getTotp();
                holder.tvValidity.setVisibility(View.VISIBLE);
                
                // Valuta validita'
                long currentWindow = (System.currentTimeMillis() / 1000L) / 30L;
                long entryWindow = (entry.getTimestamp() / 1000L) / 30L;
                
                if (currentWindow == entryWindow) {
                    holder.tvValidity.setText("VALIDO");
                    holder.tvValidity.setBackgroundColor(Color.parseColor("#4CAF50")); // Green
                } else {
                    holder.tvValidity.setText("SCADUTO");
                    holder.tvValidity.setBackgroundColor(Color.parseColor("#F44336")); // Red
                }
            } else {
                content = "Payload: " + entry.getMessage();
                holder.tvValidity.setVisibility(View.GONE);
            }
            holder.tvContent.setText(content);
        } else {
            holder.tvDirection.setText("RX");
            holder.tvDirection.setBackgroundColor(Color.parseColor("#FF9800")); // Orange
            holder.tvValidity.setVisibility(View.GONE);
            
            holder.tvContent.setText("Messaggio: " + entry.getMessage());
            
            // Color variations based on ACK/NACK
            if (entry.getMessage() != null) {
                if (entry.getMessage().contains("ACK")) {
                    holder.tvDirection.setBackgroundColor(Color.parseColor("#4CAF50")); // Green
                } else if (entry.getMessage().contains("NACK")) {
                    holder.tvDirection.setBackgroundColor(Color.parseColor("#F44336")); // Red
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView tvDirection;
        public TextView tvTimestamp;
        public TextView tvValidity;
        public TextView tvContent;

        public ViewHolder(View itemView) {
            super(itemView);
            tvDirection = itemView.findViewById(R.id.tv_direction);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            tvValidity = itemView.findViewById(R.id.tv_validity);
            tvContent = itemView.findViewById(R.id.tv_content);
        }
    }
}
