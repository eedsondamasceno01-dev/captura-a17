package com.edsonpedreiro.gravador;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.FileDescriptor;

public class ScreenRecordService extends Service {

    public static final String ACTION_START = "com.edsonpedreiro.gravador.START";
    public static final String ACTION_PAUSE = "com.edsonpedreiro.gravador.PAUSE";
    public static final String ACTION_RESUME = "com.edsonpedreiro.gravador.RESUME";
    public static final String ACTION_STOP = "com.edsonpedreiro.gravador.STOP";
    public static final String ACTION_STATE = "com.edsonpedreiro.gravador.STATE";

    private static final String CHANNEL = "screen_record";
    private static final int NOTIF_ID = 90;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private MediaRecorder recorder;
    private VirtualDisplay virtualDisplay;
    private ParcelFileDescriptor pfd;
    private Uri videoUri;
    private boolean recording = false;
    private boolean paused = false;

    @Override public void onCreate() {
        super.onCreate();

        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Gravação de tela", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mostra quando a tela está sendo gravada.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;

        String action = intent.getAction();

        if (ACTION_START.equals(action)) {
            boolean useMic = intent.getBooleanExtra("useMic", true);

            Notification notification = buildNotification("Gravando a tela");

            if (Build.VERSION.SDK_INT >= 30) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (useMic) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NOTIF_ID, notification, type);
            } else {
                startForeground(NOTIF_ID, notification);
            }

            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) {
                resultData = intent.getParcelableExtra("resultData", Intent.class);
            } else {
                resultData = intent.getParcelableExtra("resultData");
            }

            startRecording(resultCode, resultData, useMic);
        }
        else if (ACTION_PAUSE.equals(action)) {
            pauseRecording();
        }
        else if (ACTION_RESUME.equals(action)) {
            resumeRecording();
        }
        else if (ACTION_STOP.equals(action)) {
            stopAndSave(true);
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, ScreenRecordService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Gravador de Tela A17")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Parar e salvar", stopPi)
                .build();
    }

    private void startRecording(int resultCode, Intent resultData, boolean useMic) {
        if (recording || resultData == null || resultCode != Activity.RESULT_OK) {
            broadcast("error");
            stopSelf();
            return;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);

            // Required on Android 14+: foreground service is already active before getMediaProjection().
            projection = manager.getMediaProjection(resultCode, resultData);

            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (recording) stopAndSave(true);
                }
            };
            projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));

            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager)getSystemService(WINDOW_SERVICE))
                    .getDefaultDisplay().getRealMetrics(dm);

            int width = dm.widthPixels;
            int height = dm.heightPixels;
            int density = dm.densityDpi;

            // H.264 requires even dimensions on many devices.
            if ((width & 1) == 1) width--;
            if ((height & 1) == 1) height--;

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,
                    "Tela_A17_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        "Movies/GravadorTelaA17");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }

            videoUri = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

            if (videoUri == null) throw new Exception("MediaStore insert failed");

            pfd = getContentResolver().openFileDescriptor(videoUri, "w");
            if (pfd == null) throw new Exception("Cannot open output");

            FileDescriptor fd = pfd.getFileDescriptor();

            recorder = new MediaRecorder();

            if (useMic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(fd);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(6_000_000);

            if (useMic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setAudioSamplingRate(44100);
            }

            recorder.prepare();

            // Android 14+ allows only ONE createVirtualDisplay per MediaProjection session.
            virtualDisplay = projection.createVirtualDisplay(
                    "A17ScreenRecorder",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(),
                    null,
                    new Handler(Looper.getMainLooper()));

            recorder.start();

            recording = true;
            paused = false;
            broadcast("recording");

        } catch (Exception e) {
            cleanupFailed();
            broadcast("error");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void pauseRecording() {
        if (!recording || paused || recorder == null) return;
        try {
            recorder.pause();
            paused = true;
            broadcast("paused");
        } catch (Exception ignored) {}
    }

    private void resumeRecording() {
        if (!recording || !paused || recorder == null) return;
        try {
            recorder.resume();
            paused = false;
            broadcast("recording");
        } catch (Exception ignored) {}
    }

    private void stopAndSave(boolean publish) {
        if (!recording) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        recording = false;
        boolean ok = true;

        try { recorder.stop(); } catch (Exception e) { ok = false; }
        try { recorder.reset(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}

        try {
            if (projection != null && projectionCallback != null) {
                projection.unregisterCallback(projectionCallback);
            }
        } catch (Exception ignored) {}

        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}

        recorder = null;
        virtualDisplay = null;
        projection = null;
        projectionCallback = null;
        pfd = null;
        paused = false;

        if (ok && publish && videoUri != null) {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(videoUri, values, null, null);
                } catch (Exception ignored) {}
            }
            broadcast("saved");
        } else {
            if (videoUri != null) {
                try { getContentResolver().delete(videoUri, null, null); }
                catch (Exception ignored) {}
            }
            broadcast("error");
        }

        videoUri = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanupFailed() {
        recording = false;
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try {
            if (projection != null && projectionCallback != null) {
                projection.unregisterCallback(projectionCallback);
            }
        } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        if (videoUri != null) {
            try { getContentResolver().delete(videoUri, null, null); } catch (Exception ignored) {}
        }
        recorder = null;
        virtualDisplay = null;
        projection = null;
        projectionCallback = null;
        pfd = null;
        videoUri = null;
    }

    private void broadcast(String state) {
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        i.putExtra("state", state);
        sendBroadcast(i);
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
