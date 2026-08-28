package com.edsonpedreiro.gravador;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAPTURE = 2001;
    private static final int REQ_AUDIO = 2002;
    private static final int REQ_NOTIF = 2003;

    private MediaProjectionManager projectionManager;
    private Button startBtn, pauseBtn, resumeBtn, saveBtn;
    private Switch micSwitch;
    private TextView status, previewText;
    private boolean useMic = true;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra("state");
            if ("recording".equals(state)) setUiRecording(false);
            else if ("paused".equals(state)) setUiRecording(true);
            else if ("saved".equals(state)) {
                setUiIdle();
                status.setText("Vídeo salvo na Galeria: Movies/GravadorTelaA17");
                previewText.setText("GRAVAÇÃO SALVA ✓");
            }
            else if ("error".equals(state)) {
                setUiIdle();
                status.setText("Erro ao gravar. Tente novamente.");
                previewText.setText("ERRO NA GRAVAÇÃO");
            }
            else if ("stopped".equals(state)) {
                setUiIdle();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startBtn = findViewById(R.id.startBtn);
        pauseBtn = findViewById(R.id.pauseBtn);
        resumeBtn = findViewById(R.id.resumeBtn);
        saveBtn = findViewById(R.id.saveBtn);
        micSwitch = findViewById(R.id.micSwitch);
        status = findViewById(R.id.status);
        previewText = findViewById(R.id.previewText);

        startBtn.setOnClickListener(v -> requestCapture());
        pauseBtn.setOnClickListener(v -> sendServiceAction(ScreenRecordService.ACTION_PAUSE));
        resumeBtn.setOnClickListener(v -> sendServiceAction(ScreenRecordService.ACTION_RESUME));
        saveBtn.setOnClickListener(v -> sendServiceAction(ScreenRecordService.ACTION_STOP));

        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void requestCapture() {
        useMic = micSwitch.isChecked();

        if (useMic && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            Toast.makeText(this, "Permita o microfone e toque em iniciar novamente.", Toast.LENGTH_LONG).show();
            return;
        }

        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent service = new Intent(this, ScreenRecordService.class);
                service.setAction(ScreenRecordService.ACTION_START);
                service.putExtra("resultCode", resultCode);
                service.putExtra("resultData", data);
                service.putExtra("useMic", useMic);

                ContextCompat.startForegroundService(this, service);

                status.setText("Iniciando gravação...");
                previewText.setText("GRAVANDO A TELA DO CELULAR");
                setUiRecording(false);

                Toast.makeText(this,
                        "Gravação iniciada. Agora você pode abrir outros aplicativos.",
                        Toast.LENGTH_LONG).show();
            } else {
                status.setText("Permissão de captura cancelada.");
            }
        }
    }

    private void sendServiceAction(String action) {
        Intent i = new Intent(this, ScreenRecordService.class);
        i.setAction(action);
        startService(i);
    }

    private void setUiRecording(boolean paused) {
        startBtn.setEnabled(false);
        micSwitch.setEnabled(false);
        pauseBtn.setEnabled(!paused);
        resumeBtn.setEnabled(paused);
        saveBtn.setEnabled(true);
        status.setText(paused ? "GRAVAÇÃO PAUSADA." :
                (useMic ? "GRAVANDO com microfone." : "GRAVANDO sem microfone."));
        previewText.setText(paused ? "PAUSADO" : "GRAVANDO A TELA DO CELULAR");
    }

    private void setUiIdle() {
        startBtn.setEnabled(true);
        micSwitch.setEnabled(true);
        pauseBtn.setEnabled(false);
        resumeBtn.setEnabled(false);
        saveBtn.setEnabled(false);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(ScreenRecordService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
    }
}
