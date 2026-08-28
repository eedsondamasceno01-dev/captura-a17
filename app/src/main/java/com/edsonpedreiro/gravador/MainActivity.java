package com.edsonpedreiro.gravador;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final int CAPTURE = 1001;
    private static final int AUDIO = 1002;

    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private MediaRecorder recorder;
    private VirtualDisplay recordDisplay;
    private VirtualDisplay previewDisplay;
    private ParcelFileDescriptor pfd;
    private Uri videoUri;

    private SurfaceView previewSurface;
    private TextView previewHint, status;
    private Button startBtn, pauseBtn, resumeBtn, saveBtn;
    private Switch micSwitch;

    private int width, height, density;
    private boolean recording = false;
    private boolean paused = false;
    private boolean surfaceReady = false;
    private boolean useMic = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        density = dm.densityDpi;

        previewSurface = findViewById(R.id.previewSurface);
        previewSurface.getHolder().addCallback(this);
        previewHint = findViewById(R.id.previewHint);
        status = findViewById(R.id.status);
        startBtn = findViewById(R.id.startBtn);
        pauseBtn = findViewById(R.id.pauseBtn);
        resumeBtn = findViewById(R.id.resumeBtn);
        saveBtn = findViewById(R.id.saveBtn);
        micSwitch = findViewById(R.id.micSwitch);

        micSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (recording) {
                micSwitch.setChecked(useMic);
                Toast.makeText(this, "Altere o microfone antes de iniciar uma nova gravação.", Toast.LENGTH_LONG).show();
            }
        });

        startBtn.setOnClickListener(v -> startRequest());
        pauseBtn.setOnClickListener(v -> pauseRecording());
        resumeBtn.setOnClickListener(v -> resumeRecording());
        saveBtn.setOnClickListener(v -> stopAndSave());
    }

    private void startRequest() {
        useMic = micSwitch.isChecked();

        if (useMic && ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO);
            Toast.makeText(this, "Permita o microfone e toque em iniciar novamente.", Toast.LENGTH_LONG).show();
            return;
        }

        startActivityForResult(projectionManager.createScreenCaptureIntent(), CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE) return;

        if (resultCode == Activity.RESULT_OK && data != null) {
            projection = projectionManager.getMediaProjection(resultCode, data);
            beginRecording();
        } else {
            status.setText("Gravação cancelada.");
        }
    }

    private void beginRecording() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "Tela_A17_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GravadorTelaA17");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }

            videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            pfd = getContentResolver().openFileDescriptor(videoUri, "w");

            recorder = new MediaRecorder();
            if (useMic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(pfd.getFileDescriptor());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (useMic) recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(8_000_000);
            if (useMic) {
                recorder.setAudioEncodingBitRate(128000);
                recorder.setAudioSamplingRate(44100);
            }
            recorder.prepare();

            recordDisplay = projection.createVirtualDisplay(
                    "A17Recorder",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(), null, null);

            if (surfaceReady) createPreviewDisplay();

            recorder.start();
            recording = true;
            paused = false;

            previewHint.setVisibility(TextView.GONE);
            status.setText(useMic ? "GRAVANDO com microfone." : "GRAVANDO sem microfone.");
            startBtn.setEnabled(false);
            micSwitch.setEnabled(false);
            pauseBtn.setEnabled(true);
            resumeBtn.setEnabled(false);
            saveBtn.setEnabled(true);

        } catch (Exception e) {
            status.setText("Erro ao iniciar: " + e.getClass().getSimpleName());
            cleanupFailedRecording();
        }
    }

    private void createPreviewDisplay() {
        if (projection == null || !surfaceReady || previewDisplay != null) return;
        try {
            Surface surface = previewSurface.getHolder().getSurface();
            if (surface != null && surface.isValid()) {
                previewDisplay = projection.createVirtualDisplay(
                        "A17Preview",
                        width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null);
            }
        } catch (Exception ignored) {}
    }

    private void pauseRecording() {
        if (!recording || paused || recorder == null) return;
        try {
            recorder.pause();
            paused = true;
            status.setText("GRAVAÇÃO PAUSADA.");
            pauseBtn.setEnabled(false);
            resumeBtn.setEnabled(true);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível pausar.", Toast.LENGTH_LONG).show();
        }
    }

    private void resumeRecording() {
        if (!recording || !paused || recorder == null) return;
        try {
            recorder.resume();
            paused = false;
            status.setText(useMic ? "GRAVANDO com microfone." : "GRAVANDO sem microfone.");
            pauseBtn.setEnabled(true);
            resumeBtn.setEnabled(false);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível continuar.", Toast.LENGTH_LONG).show();
        }
    }

    private void stopAndSave() {
        if (!recording) return;

        boolean ok = true;
        try {
            recorder.stop();
        } catch (Exception e) {
            ok = false;
        }

        try { recorder.release(); } catch (Exception ignored) {}
        try { if (recordDisplay != null) recordDisplay.release(); } catch (Exception ignored) {}
        try { if (previewDisplay != null) previewDisplay.release(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}

        recorder = null;
        recordDisplay = null;
        previewDisplay = null;
        projection = null;
        pfd = null;
        recording = false;
        paused = false;

        if (Build.VERSION.SDK_INT >= 29 && videoUri != null) {
            try {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(videoUri, done, null, null);
            } catch (Exception ignored) {}
        }

        if (!ok && videoUri != null) {
            try { getContentResolver().delete(videoUri, null, null); } catch (Exception ignored) {}
            status.setText("A gravação não pôde ser salva.");
        } else {
            status.setText("SALVO na galeria: Movies/GravadorTelaA17");
            Toast.makeText(this, "Vídeo salvo na galeria.", Toast.LENGTH_LONG).show();
        }

        startBtn.setEnabled(true);
        micSwitch.setEnabled(true);
        pauseBtn.setEnabled(false);
        resumeBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        previewHint.setVisibility(TextView.VISIBLE);
    }

    private void cleanupFailedRecording() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        try { if (recordDisplay != null) recordDisplay.release(); } catch (Exception ignored) {}
        try { if (previewDisplay != null) previewDisplay.release(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        if (videoUri != null) {
            try { getContentResolver().delete(videoUri, null, null); } catch (Exception ignored) {}
        }
        recorder = null;
        recordDisplay = null;
        previewDisplay = null;
        projection = null;
        pfd = null;
        recording = false;
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (recording) createPreviewDisplay();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        try {
            if (previewDisplay != null) previewDisplay.release();
        } catch (Exception ignored) {}
        previewDisplay = null;
    }

    @Override protected void onDestroy() {
        if (recording) stopAndSave();
        super.onDestroy();
    }
}
