package com.edsonpedreiro.gravador;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {
    private static final int CAPTURE = 1001;
    private static final int AUDIO = 1002;
    MediaProjectionManager projectionManager;
    TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        projectionManager=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        status=findViewById(R.id.status);
        Button start=findViewById(R.id.startBtn);
        Button stop=findViewById(R.id.stopBtn);

        start.setOnClickListener(v -> {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.RECORD_AUDIO},AUDIO);
                Toast.makeText(this,"Permita o microfone e toque em iniciar novamente.",Toast.LENGTH_LONG).show();
                return;
            }
            startActivityForResult(projectionManager.createScreenCaptureIntent(), CAPTURE);
        });

        stop.setOnClickListener(v -> {
            Intent i=new Intent(this,ScreenRecordService.class);
            i.setAction("STOP");
            startService(i);
            status.setText("Gravação salva na galeria.");
        });
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==CAPTURE && resultCode==Activity.RESULT_OK && data!=null){
            Intent i=new Intent(this,ScreenRecordService.class);
            i.setAction("START");
            i.putExtra("code",resultCode);
            i.putExtra("data",data);
            startForegroundService(i);
            status.setText("GRAVANDO... use o celular normalmente.");
            moveTaskToBack(true);
        } else if(requestCode==CAPTURE) {
            status.setText("Gravação cancelada.");
        }
    }
}
