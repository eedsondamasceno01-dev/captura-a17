package com.edsonpedreiro.gravador;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.FileDescriptor;

public class ScreenRecordService extends Service {
    MediaProjection projection;
    MediaRecorder recorder;
    VirtualDisplay display;
    ParcelFileDescriptor pfd;
    Uri videoUri;
    static final int NOTIF=77;

    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel c=new NotificationChannel("record","Gravação de tela",NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }

    @Override public int onStartCommand(Intent intent,int flags,int id){
        if("STOP".equals(intent.getAction())) { stopRecording(); stopSelf(); return START_NOT_STICKY; }

        startForeground(NOTIF,new Notification.Builder(this,"record")
                .setContentTitle("Gravando a tela")
                .setContentText("Gravador de Tela A17 está ativo")
                .setSmallIcon(android.R.drawable.presence_video_online).build());

        try{
            int code=intent.getIntExtra("code",Activity.RESULT_CANCELED);
            Intent data;
            if(Build.VERSION.SDK_INT>=33) data=intent.getParcelableExtra("data",Intent.class);
            else data=intent.getParcelableExtra("data");

            MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection=m.getMediaProjection(code,data);

            DisplayMetrics dm=new DisplayMetrics();
            ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            int width=dm.widthPixels, height=dm.heightPixels, density=dm.densityDpi;

            ContentValues values=new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,"Tela_A17_"+System.currentTimeMillis()+".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
            if(Build.VERSION.SDK_INT>=29) values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/GravadorTelaA17");
            videoUri=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
            pfd=getContentResolver().openFileDescriptor(videoUri,"w");
            FileDescriptor fd=pfd.getFileDescriptor();

            recorder=new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(fd);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setVideoSize(width,height);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(8_000_000);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.prepare();

            display=projection.createVirtualDisplay("A17Recorder",width,height,density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(),null,null);
            recorder.start();
        }catch(Exception e){
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    void stopRecording(){
        try{ if(recorder!=null) recorder.stop(); }catch(Exception ignored){}
        try{ if(recorder!=null) recorder.release(); }catch(Exception ignored){}
        try{ if(display!=null) display.release(); }catch(Exception ignored){}
        try{ if(projection!=null) projection.stop(); }catch(Exception ignored){}
        try{ if(pfd!=null) pfd.close(); }catch(Exception ignored){}
        recorder=null; display=null; projection=null;
    }

    @Override public android.os.IBinder onBind(Intent i){ return null; }
}
