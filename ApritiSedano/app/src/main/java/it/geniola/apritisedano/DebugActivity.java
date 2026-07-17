package it.geniola.apritisedano;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class DebugActivity extends AppCompatActivity {

    private RecyclerView rvLogs;
    private DebugLogAdapter adapter;
    private List<DebugLogEntry> logsList;
    private Timer timer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_debug);

        rvLogs = findViewById(R.id.rv_debug_logs);
        rvLogs.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> {
            DebugLogger.getInstance().clearLogs();
            updateList();
        });

        // Setup adapter with current logs
        logsList = DebugLogger.getInstance().getLogs();
        adapter = new DebugLogAdapter(logsList);
        rvLogs.setAdapter(adapter);

        // Listen for new logs
        DebugLogger.getInstance().setListener(entry -> {
            mainHandler.post(() -> {
                logsList.add(0, entry);
                if (logsList.size() > 100) {
                    logsList.remove(logsList.size() - 1);
                }
                adapter.notifyDataSetChanged();
                rvLogs.scrollToPosition(0);
            });
        });
    }

    private void updateList() {
        logsList.clear();
        logsList.addAll(DebugLogger.getInstance().getLogs());
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateList();
        
        // Timer to refresh the UI every second so the "VALIDO/SCADUTO" label updates dynamically
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                mainHandler.post(() -> {
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }, 1000, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DebugLogger.getInstance().setListener(null);
    }
}
