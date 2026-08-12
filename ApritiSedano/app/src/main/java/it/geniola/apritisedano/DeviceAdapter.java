package it.geniola.apritisedano;

import android.graphics.Color;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {

    private List<Device> deviceList;
    private OnDeviceClickListener listener;
    private OnDeviceSettingsClickListener settingsListener;

    public interface OnDeviceClickListener {
        void onDeviceActionClick(Device device);
    }
    
    public interface OnDeviceSettingsClickListener {
        void onSettingsClick(Device device);
    }

    public DeviceAdapter(List<Device> deviceList, OnDeviceClickListener listener, OnDeviceSettingsClickListener settingsListener) {
        this.deviceList = deviceList;
        this.listener = listener;
        this.settingsListener = settingsListener;
    }

    public void updateDevices(List<Device> newDevices) {
        this.deviceList = newDevices;
        notifyDataSetChanged();
    }
    
    public void updateDeviceState(String macAddress, int state) {
        for (int i = 0; i < deviceList.size(); i++) {
            Device d = deviceList.get(i);
            if (d.getMacAddress().equalsIgnoreCase(macAddress)) {
                if (d.getCurrentState() != state) {
                    d.setCurrentState(state);
                    notifyItemChanged(i);
                }
                return;
            }
        }
    }

    public void markActionPending(String macAddress, boolean pending) {
        for (int i = 0; i < deviceList.size(); i++) {
            Device d = deviceList.get(i);
            if (d.getMacAddress().equalsIgnoreCase(macAddress)) {
                d.setActionPending(pending);
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        Device device = deviceList.get(position);
        holder.tvDeviceName.setText(device.getName());
        holder.tvDeviceMac.setText(device.getMacAddress());

        // Handling state
        if (device.getCurrentState() == 1) {
            holder.tvDeviceState.setText("Stato: APERTO");
            holder.tvDeviceState.setTextColor(Color.parseColor("#4CAF50"));
        } else if (device.getCurrentState() == 2) {
            holder.tvDeviceState.setText("Stato: CHIUSO");
            holder.tvDeviceState.setTextColor(Color.parseColor("#F44336"));
        } else if (device.getCurrentState() == 4) {
            holder.tvDeviceState.setText("OROLOGIO SCARICO");
            holder.tvDeviceState.setTextColor(Color.parseColor("#FF9800"));
        } else if (device.getCurrentState() == 3) {
            holder.tvDeviceState.setText("SYNC IN CORSO");
            holder.tvDeviceState.setTextColor(Color.parseColor("#2196F3"));
        } else {
            holder.tvDeviceState.setText("RICERCA IN CORSO...");
            holder.tvDeviceState.setTextColor(Color.parseColor("#9E9E9E"));
        }
        
        // Handling Action Button
        if (device.isActionPending()) {
            holder.btnAction.setEnabled(false);
            holder.btnAction.setText("ATTENDI...");
        } else {
            long currentWindow = System.currentTimeMillis() / 1000L / 30;
            // Simplified TOTP lockout logic for UI, for now just allow clicking
            holder.btnAction.setEnabled(true);
            if (device.getCurrentState() == 1) {
                holder.btnAction.setText("CHIUDI");
            } else if (device.getCurrentState() == 4) {
                holder.btnAction.setText("SYNC");
            } else {
                holder.btnAction.setText("APRI");
            }
        }

        holder.btnAction.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeviceActionClick(device);
            }
        });
        
        holder.ivSettings.setOnClickListener(v -> {
            if (settingsListener != null) {
                settingsListener.onSettingsClick(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView tvDeviceName;
        TextView tvDeviceMac;
        TextView tvDeviceState;
        Button btnAction;
        ImageView ivSettings;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDeviceName = itemView.findViewById(R.id.tv_device_name);
            tvDeviceMac = itemView.findViewById(R.id.tv_device_mac);
            tvDeviceState = itemView.findViewById(R.id.tv_device_state);
            btnAction = itemView.findViewById(R.id.btn_action);
            ivSettings = itemView.findViewById(R.id.iv_settings);
        }
    }
}
